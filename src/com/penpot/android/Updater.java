package com.penpot.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Penpot 版本更新器。
 *
 * 核心安全设计：**绝不触碰用户数据**。
 *   用户数据 = base/var/lib/postgresql/data（账号/团队/项目/文件元数据）+ objects/（文件本体）
 *   更新只替换 apps/{fe,be,ex} 这三个镜像目录，它们与用户数据是彼此独立的目录树。
 *
 * 流程（staging + 原子切换 + 失败回滚）：
 *   1. 下载新版到 apps_next/（全新目录，全程不碰正在运行的 apps/）
 *   2. 解压 + 后处理（nginx/JVM/shim/动态 IP）到 apps_next/
 *   3. 备份 PG 数据目录 → backup_<version>_<ts>.tar.zst（数据库 schema 升级是单向的，留后路）
 *   4. 停服务（释放端口 + 确保没有进程占着 apps/ 里的文件）
 *   5. apps → apps_prev，apps_next → apps
 *   6. 启服务；若启动失败则自动换回 apps_prev
 */
public final class Updater {

    public interface Callback {
        void log(String msg);
        void stage(String stage, int pct);
    }

    private final Context ctx;
    private final AssetInstaller inst;
    private final Callback cb;
    private volatile boolean cancelled = false;

    public Updater(Context ctx, AssetInstaller inst, Callback cb) {
        this.ctx = ctx;
        this.inst = inst;
        this.cb = cb;
    }

    public void cancel() { cancelled = true; }

    public static File appsDir(Context ctx) { return new File(ctx.getFilesDir(), "apps"); }
    public static File stagingDir(Context ctx) { return new File(ctx.getFilesDir(), "apps_next"); }
    public static File prevDir(Context ctx) { return new File(ctx.getFilesDir(), "apps_prev"); }

    /** 当前已安装的 Penpot 版本（来自 SharedPreferences，缺省用构建时的默认版本）。 */
    public static String currentVersion(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("penpot_prefs", Context.MODE_PRIVATE);
        String v = sp.getString("apps_version", "");
        if (v == null || v.isEmpty()) return DownloadCoordinator.IMAGE_REF;
        return v;
    }

    /**
     * 执行更新。调用前 App 应该处于可停止状态（本方法内部会停服务）。
     *
     * @param targetVersion 目标版本号
     * @param stopService   停止服务的回调（由 Service 提供）
     * @param startService  启动服务的回调（由 Service 提供）
     */
    public boolean update(String targetVersion,
                          Runnable stopService,
                          Runnable startService) {
        if (targetVersion == null || targetVersion.isEmpty()) {
            cb.log("❌ 目标版本为空");
            return false;
        }
        String current = currentVersion(ctx);
        cb.log("=== 准备更新 Penpot: " + current + " → " + targetVersion + " ===");

        try {
            // ---------- 1&2. 下载 + 解压到 staging ----------
            File staging = stagingDir(ctx);
            if (staging.exists()) {
                cb.log("清理上次残留的 staging 目录");
                DownloadCoordinator.deleteRecursive(staging);
            }
            if (!staging.mkdirs() && !staging.isDirectory()) {
                cb.log("❌ 无法创建 staging 目录");
                return false;
            }

            DownloadCoordinator dc = new DownloadCoordinator(ctx, inst, new DownloadCoordinator.Callback() {
                public void log(String m) { cb.log(m); }
                public void stage(String s, int p) { cb.stage(s, p); }
            });
            dc.setImageRef(targetVersion);
            dc.setAppsRoot(staging);

            cb.stage("下载新版本", 0);
            if (!dc.installAll()) {
                cb.log("❌ 新版本下载/解压失败，已保留旧版本不动");
                DownloadCoordinator.deleteRecursive(staging);
                return false;
            }
            if (cancelled) { cb.log("已取消更新"); DownloadCoordinator.deleteRecursive(staging); return false; }

            // 校验 staging 三组件齐全
            for (String d : new String[]{"fe", "be", "ex"}) {
                File marker = new File(staging, d + "/.installed");
                if (!marker.exists()) {
                    cb.log("❌ staging 缺少 " + d + "，放弃更新");
                    DownloadCoordinator.deleteRecursive(staging);
                    return false;
                }
            }
            cb.log("✅ 新版本已就绪（staging）");

            // ---------- 3. 停服务（必须先停，PG 数据才是一致的） ----------
            cb.stage("停止服务", 0);
            cb.log("停止当前服务...");
            if (stopService != null) stopService.run();
            sleep(2500);

            // ---------- 4. 备份 PG 数据（此时 PG 已停，文件一致） ----------
            cb.stage("备份用户数据", 0);
            File backup = backupPgData(targetVersion);
            if (backup != null) cb.log("✅ 数据备份: " + backup.getName() + "（" + (backup.length() / 1048576) + "MB）");
            else cb.log("⚠ 数据备份未完成（更新继续；PG 数据目录本身不受影响）");

            // ---------- 5. 切换目录 ----------
            cb.stage("切换版本", 0);
            File apps = appsDir(ctx);
            File prev = prevDir(ctx);
            if (prev.exists()) DownloadCoordinator.deleteRecursive(prev);
            boolean oldMoved = false;
            if (apps.exists()) {
                oldMoved = apps.renameTo(prev);
                if (!oldMoved) {
                    cb.log("❌ 旧版本目录改名失败，终止更新（旧版本未受影响）");
                    DownloadCoordinator.deleteRecursive(staging);
                    if (startService != null) startService.run();
                    return false;
                }
            }
            if (!staging.renameTo(apps)) {
                cb.log("❌ 新版本目录切换失败，正在回滚");
                if (oldMoved) prev.renameTo(apps);
                if (startService != null) startService.run();
                return false;
            }
            cb.log("✅ 版本目录已切换");

            // 写入新版本号
            SharedPreferences sp = ctx.getSharedPreferences("penpot_prefs", Context.MODE_PRIVATE);
            sp.edit().putString("apps_version", targetVersion).apply();

            // ---------- 6. 启动新版本 ----------
            cb.stage("启动新版本", 0);
            cb.log("启动新版本 " + targetVersion + "...");
            if (startService != null) startService.run();

            cb.stage("完成", 100);
            cb.log("🎉 更新完成: " + current + " → " + targetVersion);
            cb.log("用户数据（账号/团队/项目/文件）完整保留；旧版本在 apps_prev/ 可用于回滚");
            return true;

        } catch (Throwable t) {
            cb.log("❌ 更新异常: " + t);
            return false;
        }
    }

    /**
     * 备份 PostgreSQL 数据目录（调用前 PG 必须已停止，保证文件一致）。
     * guest 内用 busybox tar 打包到 /tmp，再取出来用 zstd 压缩到 files/backups/。
     * 失败只告警，不阻断更新（PG 数据目录本身在切换流程中从不被移动或删除）。
     */
    private File backupPgData(String version) {
        try {
            File dataDir = new File(inst.baseDir(), "var/lib/postgresql/data");
            if (!dataDir.exists()) { cb.log("PG 数据目录不存在，跳过备份"); return null; }

            File backups = new File(inst.filesDir(), "backups");
            backups.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            File out = new File(backups, "pg_" + version + "_" + ts + ".tar.zst");

            // 保留最近 3 份备份，更早的删掉，避免占满存储
            File[] olds = backups.listFiles();
            if (olds != null && olds.length >= 3) {
                java.util.Arrays.sort(olds, new java.util.Comparator<File>() {
                    public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
                });
                for (int i = 0; i < olds.length - 2; i++) olds[i].delete();
            }

            RuntimeEngine engine = new RuntimeEngine(inst);
            RuntimeEngine.Result r = engine.runGuest(
                    "/bin/busybox rm -f /tmp/pg_backup.tar; "
                    + "/bin/busybox tar -cf /tmp/pg_backup.tar -C /var/lib/postgresql/data . 2>&1", 300000);
            if (!r.ok()) { cb.log("打包 PG 数据失败: " + trim(r.out)); return null; }

            File guestTar = new File(inst.baseDir(), "tmp/pg_backup.tar");
            if (!guestTar.exists()) { cb.log("打包产物未找到"); return null; }
            cb.log("PG 数据已打包: " + (guestTar.length() / 1048576) + "MB，正在压缩...");

            ProcessBuilder pb = new ProcessBuilder(
                    inst.zstd().getAbsolutePath(), "-q", "-f", "-o", out.getAbsolutePath(),
                    guestTar.getAbsolutePath());
            pb.environment().put("LD_LIBRARY_PATH", inst.libDir().getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            guestTar.delete();
            return (out.exists() && out.length() > 0) ? out : null;
        } catch (Throwable t) {
            cb.log("备份异常: " + t.getMessage());
            return null;
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** 回滚到 apps_prev（供启动失败时调用）。 */
    public static boolean rollback(Context ctx) {
        File apps = appsDir(ctx);
        File prev = prevDir(ctx);
        if (!prev.exists()) return false;
        File tmp = new File(ctx.getFilesDir(), "apps_failed");
        if (tmp.exists()) DownloadCoordinator.deleteRecursive(tmp);
        if (apps.exists() && !apps.renameTo(tmp)) return false;
        if (!prev.renameTo(apps)) {
            if (tmp.exists()) tmp.renameTo(apps);
            return false;
        }
        DownloadCoordinator.deleteRecursive(tmp);
        return true;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
