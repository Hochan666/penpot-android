package com.penpot.android;

import java.io.*;
import java.util.*;

/**
 * Penpot 运行时引擎：通过静态 loader 在 musl rootfs 里启动各组件。
 * 关键约束（来自已验证的部署实践）：
 *  - initdb 用 v_sigact.so（禁所有 sigaction），postgres 主进程用 libsig.so（只禁 SIGSYS）
 *  - 两者都配 libsysv.so（SysV IPC 用 POSIX shm 模拟）+ libnet.so（accept4 放行）
 *  - guest 内 mkdir -p /dev/shm /tmp/.sysvshm 必须
 *  - PG conf: listen_addresses=127.0.0.1, shared_memory_type=mmap, dynamic_shared_memory_type=posix
 */
public final class RuntimeEngine {
    private final AssetInstaller inst;
    private final List<Process> procs = Collections.synchronizedList(new ArrayList<Process>());

    public RuntimeEngine(AssetInstaller inst) { this.inst = inst; }

    public static final class Result {
        public final int code;
        public final String out;
        public Result(int c, String o) { code = c; out = o; }
        public boolean ok() { return code == 0; }
    }

    /** 通过 loader 执行 guest /bin/sh -c <script>，捕获合并输出，带超时。 */
    public Result runGuest(String script, long timeoutMs) throws Exception {
        String loader = inst.loader().getAbsolutePath();
        String rootfs = inst.baseDir().getAbsolutePath();
        String[] cmd = { loader, rootfs, "/lib/ld-musl-aarch64.so.1", "/bin/sh", "-c", script };
        LogBus.log("guest: " + script.length() + "B script");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        final StringBuilder sb = new StringBuilder();
        final InputStream is = p.getInputStream();
        // 读取线程
        Thread reader = new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        String s = new String(buf, 0, n, "UTF-8");
                        sb.append(s);
                        LogBus.log("[guest] " + s.trim());
                    }
                } catch (Throwable ignored) {}
            }
        });
        reader.setDaemon(true);
        reader.start();
        long deadline = System.currentTimeMillis() + timeoutMs;
        int code = -1;
        while (System.currentTimeMillis() < deadline) {
            try {
                code = p.exitValue();
                break;
            } catch (IllegalThreadStateException e) {
                try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
            }
        }
        if (code == -1) {
            p.destroy();
            LogBus.log("guest 超时");
            return new Result(-99, sb.toString());
        }
        reader.join(1000);
        return new Result(code, sb.toString());
    }

    /** 后台启动一个 guest 命令（postgres/valkey 等长驻进程），保留 Process 句柄。 */
    public Process startDaemon(String script, String logName) throws Exception {
        String loader = inst.loader().getAbsolutePath();
        String rootfs = inst.baseDir().getAbsolutePath();
        String[] cmd = { loader, rootfs, "/lib/ld-musl-aarch64.so.1", "/bin/sh", "-c", script };
        ProcessBuilder pb = new ProcessBuilder(cmd);
        File log = new File(inst.filesDir(), logName);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        Process p = pb.start();
        procs.add(p);
        LogBus.log("daemon started: " + logName + " pid=" + pidOf(p));
        return p;
    }

    private static String pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return String.valueOf(f.getInt(p));
        } catch (Throwable t) { return "?"; }
    }

    /**
     * 一次性 guest 环境准备（幂等）：
     *  1) busybox --install -s /bin 建立 mkdir/ls/... 全套 applet 软链
     *  2) 给当前 euid 在 /etc/passwd 追加用户条目，否则 initdb 报
     *     "could not look up effective user ID <uid>: user does not exist"
     * 必须在 initPg 的幂等检查之前无条件执行。
     */
    public boolean prepareGuest() throws Exception {
        LogBus.log("准备 guest 环境（busybox 软链 + passwd）...");
        String script =
            "/bin/busybox --install -s /bin 2>/dev/null;" +
            "UID=$(/bin/busybox id -u);" +
            "/bin/busybox grep -q \":$UID:\" /etc/passwd 2>/dev/null || " +
            "/bin/busybox echo \"penpotapp:x:$UID:0:PenpotApp:/var/lib/postgresql:/bin/sh\" >> /etc/passwd;" +
            "/bin/busybox echo \"guest ready uid=$UID\"";
        Result r = runGuest(script, 30000);
        LogBus.log("prepareGuest exit=" + r.code + " :: " + tail(r.out, 400).replace("\n", " | "));
        return r.ok();
    }

    /** 初始化 PostgreSQL 数据目录（幂等）。 */
    public boolean initPg() throws Exception {
        // 环境准备必须先做（无论 PG 是否已初始化），否则 mkdir/passwd 缺失
        if (!prepareGuest()) { LogBus.log("❌ guest 环境准备失败"); return false; }
        File pgver = new File(inst.baseDir(), "var/lib/postgresql/data/PG_VERSION");
        if (pgver.exists()) { LogBus.log("PG 数据目录已初始化"); return true; }
        // 上次可能 initdb 半途失败留下残留目录，先删干净再重建，避免"目录非空"
        File dataDir = new File(inst.baseDir(), "var/lib/postgresql/data");
        if (dataDir.exists()) {
            LogBus.log("清理残留 data 目录...");
            delTree(dataDir);
        }
        LogBus.log("初始化 PostgreSQL 数据目录...");
        String script =
            "export LD_PRELOAD=\"/opt/shim/v_sigact.so /opt/shim/libsysv.so /opt/shim/libnet.so\";" +
            "/bin/busybox mkdir -p /dev/shm /tmp/.sysvshm;" +
            "exec /usr/libexec/postgresql17/initdb -D /var/lib/postgresql/data -U penpot -A trust --no-sync";
        Result r = runGuest(script, 180000);
        LogBus.log("initdb exit=" + r.code);
        if (!r.ok()) {
            LogBus.log("❌ initdb 失败:\n" + tail(r.out, 1500));
            return false;
        }
        // 写配置
        String confPath = inst.baseDir() + "/var/lib/postgresql/data/postgresql.conf";
        StringBuilder conf = new StringBuilder();
        conf.append("\nlisten_addresses='127.0.0.1'\n")
            .append("port=5432\n")
            .append("unix_socket_directories='/tmp'\n")
            .append("shared_memory_type=mmap\n")
            .append("dynamic_shared_memory_type=posix\n")
            .append("shared_buffers=128MB\n")
            .append("max_connections=100\n")
            .append("fsync=off\n")
            .append("synchronous_commit=off\n")
            .append("full_page_writes=off\n");
        FileOutputStream fos = new FileOutputStream(confPath, true);
        fos.write(conf.toString().getBytes("UTF-8"));
        fos.close();
        LogBus.log("✅ PG 数据目录初始化完成");
        return true;
    }

    /**
     * 停掉可能残留的 PostgreSQL 进程并清理 postmaster.pid。
     * 上次启动失败/被中断时 PG 可能还活着，直接再起会报
     * `FATAL: lock file "postmaster.pid" already exists`。
     */
    public void stopPg() {
        try {
            // 用 "[p]ostgres" 写法避免 pkill 匹配到执行它的 shell 自身
            runGuest("/bin/busybox pkill -f '[p]ostgres' 2>/dev/null;" +
                     "/bin/busybox sleep 2;" +
                     "/bin/busybox rm -f /var/lib/postgresql/data/postmaster.pid 2>/dev/null;" +
                     "echo stopPg-done", 15000);
        } catch (Throwable t) {
            LogBus.log("stopPg 警告: " + t.getMessage());
        }
    }

    /** 启动 PostgreSQL（后台）。 */
    public boolean startPg() throws Exception {
        // 先清理上次残留（PG 进程 + postmaster.pid 锁文件），否则会启动失败
        stopPg();
        // 关键：startDaemon 用 >> 追加日志，若不清空，残留的上次 "ready" 行会让
        // isPgReady() 误判新 PG 已就绪（实际还在 starting up），导致 PgClient 收到 57P03。
        try {
            FileOutputStream fos = new FileOutputStream(new File(inst.filesDir(), "pg.log"), false);
            fos.close();
        } catch (Throwable ignored) {}
        String script =
            "export LD_PRELOAD=\"/opt/shim/libsig.so /opt/shim/libsysv.so /opt/shim/libnet.so\";" +
            "/bin/busybox mkdir -p /dev/shm /tmp/.sysvshm;" +
            "exec /usr/libexec/postgresql17/postgres -D /var/lib/postgresql/data";
        startDaemon(script, "pg.log");
        // 等 ready
        for (int i = 0; i < 40; i++) {
            Thread.sleep(500);
            if (isPgReady()) {
                LogBus.log("✅ PostgreSQL ready");
                // 全新 initdb 只建了超级用户 penpot，没有 penpot 数据库；
                // BE 连接串指向 postgresql://127.0.0.1:5432/penpot，必须先建库（幂等）。
                try {
                    PgClient.ensureDatabase("127.0.0.1", 5432, "penpot", "penpot");
                    LogBus.log("✅ penpot 数据库已就绪");
                } catch (Throwable t) {
                    LogBus.log("❌ 创建 penpot 数据库失败: " + t.getMessage());
                    return false;
                }
                return true;
            }
        }
        LogBus.log("❌ PostgreSQL 未在 20s 内 ready，日志:");
        LogBus.log(tail(readLog("pg.log"), 1500));
        return false;
    }

    private boolean isPgReady() {
        // 通过 loader 跑一个小的 socket 探测：用 busybox nc 连 127.0.0.1:5432
        // 简化：直接检查 pg.log 里的 "ready to accept connections"
        String log = readLog("pg.log");
        return log.contains("ready to accept connections");
    }

    private String readLog(String name) {
        try {
            File f = new File(inst.filesDir(), name);
            if (!f.exists()) return "";
            byte[] b = new byte[(int) Math.min(f.length(), 200000)];
            FileInputStream fis = new FileInputStream(f);
            int n = fis.read(b);
            fis.close();
            return n > 0 ? new String(b, 0, n, "UTF-8") : "";
        } catch (Throwable t) { return ""; }
    }

    /** 递归删除目录及其内容（不删除传入的目录本身）。 */
    private static void delTree(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File f : kids) {
                if (f.isDirectory() && !f.getAbsolutePath().endsWith("/data") && !isSymlink(f)) {
                    delTree(f);
                }
                f.delete();
            }
        }
    }

    private static boolean isSymlink(File f) {
        try { return java.nio.file.Files.isSymbolicLink(f.toPath()); }
        catch (Throwable t) { return false; }
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(s.length() - max);
    }

    /** P4：启动 Valkey（musl base，无需 shim）。 */
    public boolean startValkey() throws Exception {
        String script =
            "/bin/busybox mkdir -p /var/lib/valkey /dev/shm;" +
            "exec /usr/bin/valkey-server --port 6379 --bind 127.0.0.1 " +
            "--dir /var/lib/valkey --save '' --appendonly no";
        startDaemon(script, "valkey.log");
        for (int i = 0; i < 30; i++) {
            Thread.sleep(400);
            java.net.Socket s = new java.net.Socket();
            try {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", 6379), 300);
                s.close();
                LogBus.log("✅ Valkey ready");
                return true;
            } catch (Throwable t) {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }
        LogBus.log("❌ Valkey 未在 12s 内 ready");
        return false;
    }

    /**
     * P4：用 glibc rootfs 启动 BE/FE/EX（不同于 musl 的 PG/Valkey）。
     * cmd = loader <glibcRootfs> /lib/ld-linux-aarch64.so.1 /bin/bash <script>
     * 用 -m 把可写对象目录挂到 guest 的 /opt/data/assets（BE 对象存储需要）。
     */
    public Process startGlibcDaemon(File rootfs, String script, String logName, File assetsDir) throws Exception {
        String loader = inst.loader().getAbsolutePath();
        java.util.List<String> cmd = new ArrayList<String>();
        cmd.add(loader);
        if (assetsDir != null) {
            assetsDir.mkdirs();
            cmd.add("-m");
            cmd.add("/opt/data/assets:" + assetsDir.getAbsolutePath());
        }
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("/lib/ld-linux-aarch64.so.1");
        cmd.add("/bin/bash");
        cmd.add(script);
        LogBus.log("glibc daemon: " + rootfs.getName() + " " + script);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        File log = new File(inst.filesDir(), logName);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        Process p = pb.start();
        procs.add(p);
        LogBus.log("glibc daemon started: " + logName + " pid=" + pidOf(p));
        return p;
    }

    /** 等待某端口 ready（通过 guest busybox nc，或宿主 socket）。 */
    public boolean waitPort(int port, int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            java.net.Socket s = new java.net.Socket();
            try {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 400);
                s.close();
                LogBus.log("port " + port + " ready");
                return true;
            } catch (Throwable t) {
                try { s.close(); } catch (Throwable ignored) {}
                try { Thread.sleep(500); } catch (InterruptedException ie) { return false; }
            }
        }
        LogBus.log("port " + port + " 超时未 ready");
        return false;
    }

    /**
     * 停止所有守护进程。
     * 注意：loader 自带信号兼容层会吞掉 SIGTERM，p.destroy() 往往什么都不做，
     * 所以必须 destroyForcibly()（SIGKILL），再按 PID 兜底 kill -9 整个进程组。
     */
    public void stopAll() {
        List<Process> snapshot;
        synchronized (procs) {
            snapshot = new ArrayList<Process>(procs);
            procs.clear();
        }
        for (Process p : snapshot) {
            try {
                int pid = Integer.parseInt(pidOf(p));
                // 先杀整个进程组（postgres/nginx/java 各有子进程）
                killTree(pid);
            } catch (Throwable ignored) {}
            try { p.destroyForcibly(); } catch (Throwable ignored) {}
        }
        // 兜底：扫掉仍残留的本 rootfs 进程
        killAllStrays(inst);
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        LogBus.log("所有子进程已停止");
    }

    /** 按 PID 强杀，先子后父（kill -9）。 */
    private void killTree(int pid) {
        try {
            // 找出该 pid 的所有子孙
            java.util.List<Integer> kids = new ArrayList<Integer>();
            Process ps = new ProcessBuilder("/system/bin/sh", "-c",
                    "ps -A -o pid,ppid | awk '$2==" + pid + "{print $1}'").start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(ps.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try { kids.add(Integer.parseInt(line)); } catch (Throwable ignored) {}
            }
            r.close();
            for (Integer k : kids) killTree(k);
            new ProcessBuilder("/system/bin/kill", "-9", String.valueOf(pid)).start();
        } catch (Throwable ignored) {}
    }

    /**
     * 兜底清扫：扫描属于本 App uid 且命令行含本 App files 目录的进程，全部 kill -9。
     * 用于「Activity 重启后 engine 句柄丢失」或「上次异常退出留下野进程」的场景。
     */
    public static void killAllStrays(AssetInstaller inst) {
        killAllStrays(inst, inst == null ? null : inst.filesDir().getAbsolutePath());
    }

    public static void killAllStrays(android.content.Context ctx) {
        killAllStrays(null, ctx.getFilesDir().getAbsolutePath());
    }

    /**
     * 兜底清扫：扫掉属于本 App uid 的所有 Penpot 组件进程。
     *
     * 不依赖 `ps` 输出格式（App 内与 shell 下的列宽/字段可能不同），
     * 直接读 /proc/<pid>/{status,cmdline} —— 同 uid 一定可读，结果确定。
     */
    private static void killAllStrays(AssetInstaller inst, String filesPath) {
        // nginx master 被 SIGKILL 后仍可能被父进程拉起、或残留 worker，
        // 因此多轮扫描直到不再出现新进程（最多 5 轮）。
        int killedTotal = 0;
        for (int round = 0; round < 5; round++) {
            int n = killStraysOnce();
            killedTotal += n;
            if (n == 0) break;
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
        }
        if (killedTotal > 0) LogBus.log("已清理残留进程 " + killedTotal + " 个");
    }

    /** 扫描一轮 /proc，杀掉属于本 App uid 的 Penpot 组件进程，返回杀掉的数量。 */
    private static int killStraysOnce() {
        // 采用「本 App uid 的全部子进程」策略：App 自身只运行 Java 主进程 +
        // 我们 spawn 的组件守护进程，因此同 uid 的其它进程都可以安全清理。
        // 这样即使某个进程（如 nginx master 或 start.sh）会重新拉起子进程，
        // 也能在下一次扫描中一并清掉，直到没有进程残留。
        final int myUid = android.os.Process.myUid();
        final int selfPid = android.os.Process.myPid();
        java.util.List<Integer> victims = new ArrayList<Integer>();
        try {
            String[] names = new File("/proc").list();
            if (names == null) return 0;
            for (String n : names) {
                if (n.isEmpty()) continue;
                char c0 = n.charAt(0);
                if (c0 < '0' || c0 > '9') continue;
                int pid;
                try { pid = Integer.parseInt(n); } catch (Throwable e) { continue; }
                if (pid == selfPid || pid == 1) continue;

                String status = readSmall(new File("/proc/" + n + "/status"));
                if (status == null) continue;
                int uid = -1;
                for (String line : status.split("\\n")) {
                    if (line.startsWith("Uid:")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            try { uid = Integer.parseInt(parts[1]); } catch (Throwable ignored) {}
                        }
                        break;
                    }
                }
                if (uid == myUid) victims.add(pid);
            }
        } catch (Throwable ignored) { }

        for (Integer pid : victims) {
            // 注意：android.os.Process.killProcess() 在本机 ROM 上对「孤儿」子进程
            // （父进程已被 init 收养）实际无效且不报错，因此改走 /system/bin/kill。
            // 与 run-as 手工 kill -9 行为一致，实测可靠。
            if (!forceKill(pid)) {
                try { android.os.Process.killProcess(pid); } catch (Throwable ignored) {}
            }
        }
        return victims.size();
    }

    /** 用 /system/bin/kill -9 强杀指定 pid，成功返回 true。 */
    private static boolean forceKill(int pid) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Process p = null;
            try {
                p = new ProcessBuilder("/system/bin/kill", "-9", String.valueOf(pid))
                        .redirectErrorStream(true).start();
                p.waitFor();
                if (new File("/proc/" + pid).exists()) {
                    try { Thread.sleep(120); } catch (InterruptedException ignored) {}
                } else {
                    return true;
                }
            } catch (Throwable ignored) {
                return false;
            } finally {
                if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
        return !new File("/proc/" + pid).exists();
    }

    /** 读小文件（/proc 用），失败返回 null。 */
    private static String readSmall(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[4096];
            int total = 0, n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) total += n;
            return new String(buf, 0, total, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
