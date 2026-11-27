package com.penpot.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

/**
 * P4 编排：下载 backend/frontend/exporter 三套 OCI 镜像 → 解压 → 后处理成可运行 rootfs。
 *
 * 后处理清单见 POSTPROCESS.md（8 项功能性必需）：
 *  1. libacceptshim.so 注入 + 三个 start.sh 的 LD_PRELOAD（模板已含）
 *  2. JVM -Djdk.lang.Process.launchMechanism=FORK（模板已含）
 *  3. Chromium wrapper（--single-process，assets 模板）
 *  4. PENPOT_INTERNAL_URI 指向 frontend 3449（模板已含）
 *  5. 硬链接修复（ImageExtractor.extractAll 已做）
 *  6. nginx temp/pid→/tmp + 日志→stdout（nginx.conf 模板）
 *  7. resolv.conf 自写（模板，动态替换网关）
 *  8. 下载层完整性校验（RegistryClient 已做）
 * 动态：PENPOT_PUBLIC_URI / PENPOT_HOST / resolver 网关 —— 运行时填当前 LAN IP。
 */
public final class DownloadCoordinator {
    private final Context ctx;
    private final AssetInstaller inst;
    private final String zstdBin;
    private final String libDir;
    private final String lanIp;
    private final String gateway;
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final String PREF = "penpot_prefs";
    private static final String KEY_APPS = "apps_installed";
    private static final String APPS_VER = "apps_v1";
    private static final String TAG = "DownloadCoordinator";

    private static final String[] IMAGES = {
        "penpotapp/frontend",   // FE 先下（小，前端立即可见）
        "penpotapp/backend",
        "penpotapp/exporter"    // EX 最大（含 Chromium ~695M），最后
    };
    private static final String[] DIRS = { "fe", "be", "ex" };
    /** 默认镜像版本；升级流程可通过 setImageRef 覆盖。 */
    public static final String IMAGE_REF = "2.19.5";
    private String imageRef = IMAGE_REF;
    /** 目标 apps 根目录；默认 filesDir/apps，升级时指向 filesDir/apps_next。 */
    private File appsRootOverride = null;

    public interface Callback {
        void log(String msg);
        void stage(String stage, int pct);
    }

    public DownloadCoordinator(Context ctx, AssetInstaller inst, Callback cb) {
        this.ctx = ctx;
        this.inst = inst;
        this.cb = cb;
        this.zstdBin = inst.zstd().getAbsolutePath();
        this.libDir = inst.libDir().getAbsolutePath();
        this.lanIp = LanIpHelper.getLanIp(ctx);
        this.gateway = deriveGateway(this.lanIp);
    }

    private final Callback cb;

    /** 由当前 IP 推导网关（常见 /24：把最后一段换 1）；拿不到就用 223.5.5.5 公网 DNS。 */
    static String deriveGateway(String ip) {
        try {
            int d = ip.lastIndexOf('.');
            if (d > 0) {
                String cand = ip.substring(0, d + 1) + "1";
                // 简单校验四段
                String[] p = cand.split("\\.");
                if (p.length == 4) return cand;
            }
        } catch (Throwable ignored) {}
        return "223.5.5.5";
    }

    public boolean isInstalled() {
        // staging 目录（升级用）只看文件标记，不看 SharedPreferences：
        // 那时 apps_next 刚解压完、还没切换，不能依赖全局的 KEY_APPS。
        if (appsRootOverride != null) return allRootsExist();
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return APPS_VER.equals(sp.getString(KEY_APPS, "")) && allRootsExist();
    }

    private boolean allRootsExist() {
        for (String d : DIRS) {
            File marker = new File(appsDir(), d + "/.installed");
            if (!marker.exists()) return false;
        }
        return true;
    }

    public void cancel() { cancelled.set(true); }

    /** 指定要下载的镜像版本（升级用）。 */
    public void setImageRef(String ref) { if (ref != null && !ref.isEmpty()) this.imageRef = ref; }
    public String getImageRef() { return imageRef; }

    /** 指定 apps 根目录（升级时指向 staging 目录，避免动到正在运行的 apps/）。 */
    public void setAppsRoot(File dir) { this.appsRootOverride = dir; }

    /** 应用数据根目录：镜像统一下载到 filesDir/apps（ext4，支持 symlink/chmod）。 */
    public File appsDir() {
        if (appsRootOverride != null) return appsRootOverride;
        return new File(inst.filesDir(), "apps");
    }

    /**
     * 实际下载根目录。SAF 目录在外部存储，用 DocumentFile 逐文件写较复杂且慢；
     * 为保证可靠，镜像层下载+解压统一在 filesDir/apps（ext4，支持 symlink/chmod），
     * 用户选的 SAF 目录仅作为“项目/字体保存路径”供 BE 的对象存储使用。
     */
    File internalAppsDir() { return appsDir(); }

    public synchronized boolean installAll() throws Exception {
        if (isInstalled()) {
            cb.log("Penpot 应用镜像已安装，跳过");
            // 升级兼容 + 动态 IP：即使整体已安装，也刷新一次已存在组件的配置模板
            // （FE 的 /tmp/nginx.conf.template 必须 listen 3449；start.sh 的 PUBLIC_URI 随 IP 变）。
            // 仅覆写配置文件，幂等无副作用，不重新下载/解压。
            try {
                File installedRoot = appsDir();
                for (int k = 0; k < DIRS.length; k++) {
                    File r = new File(installedRoot, DIRS[k]);
                    if (r.exists()) postProcess(DIRS[k], r);
                }
                cb.log("已安装组件配置已刷新");
            } catch (Exception e) {
                cb.log("配置刷新失败: " + e.getMessage());
            }
            return true;
        }
        File root = internalAppsDir();
        root.mkdirs();
        RegistryClient rc = new RegistryClient(new RegistryClient.Callback() {
            public void log(String m) { cb.log(m); }
            public void progress(String label, long bytes, long total, int idx, int count) {
                int pct = total > 0 ? (int)(bytes * 100 / total) : 0;
                cb.stage("下载 " + label, pct);
            }
        });
        ImageExtractor xtr = new ImageExtractor(zstdBin, libDir, new ImageExtractor.Callback() {
            public void log(String m) { cb.log(m); }
        });
        for (int i = 0; i < IMAGES.length; i++) {
            if (cancelled.get()) { cb.log("已取消"); return false; }
            String repo = IMAGES[i], dir = DIRS[i];
            // 单组件幂等：整体 isInstalled() 要求三者全装好，但中途重装/失败续跑时
            // 已装好的组件不该重下（其下载缓存已删）。各自检查 .installed 标记。
            File doneRoot = new File(root, dir);
            if (new File(doneRoot, ".installed").exists()) {
                cb.log(dir + " 已安装，跳过下载");
                // 已装好的组件仍重跑一次后处理：确保配置模板是最新版
                // （如 FE 的 /tmp/nginx.conf.template 必须 listen 3449，否则 entrypoint 覆盖回 8080；
                //   start.sh 的 PENPOT_PUBLIC_URI 也要随当前 IP 刷新）。仅覆写配置文件，幂等无副作用。
                try {
                    postProcess(dir, doneRoot);
                    cb.log(dir + " 配置已刷新");
                } catch (Exception e) {
                    cb.log(dir + " 配置刷新失败: " + e.getMessage());
                }
                continue;
            }
            cb.stage("准备 " + dir, 0);
            cb.log("===== 下载 " + repo + ":" + imageRef + " =====");
            File imgDir = new File(root, "_images_" + dir);
            imgDir.mkdirs();
            List<File> layers = rc.downloadImage(repo, imageRef, imgDir);
            cb.log(dir + " 下载完成，共 " + layers.size() + " 层");
            if (cancelled.get()) return false;
            cb.stage("解压 " + dir, 0);
            File rootfs = new File(root, dir);
            // 上次解压若中途失败，rootfs 会留下半成品；重试必须从干净目录开始，
            // 否则残留文件会混进新解压结果（此处能安全删除，因为 .installed 尚未写入）。
            if (rootfs.exists()) deleteRecursive(rootfs);
            rootfs.mkdirs();
            xtr.extractAll(layers, rootfs);
            cb.log(dir + " 解压完成");
            if (cancelled.get()) return false;
            cb.stage("后处理 " + dir, 0);
            postProcess(dir, rootfs);
            new File(rootfs, ".installed").createNewFile();
            // 清理下载层，释放空间（约 2G）
            deleteRecursive(imgDir);
            cb.log(dir + " 后处理完成，已清理下载缓存");
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_APPS, APPS_VER).putString("apps_version", imageRef).apply();
        cb.stage("完成", 100);
        cb.log("✅ Penpot 应用镜像全部安装完成");
        return true;
    }

    /** 把 assets/templates 下的后处理文件覆写进 rootfs，并动态替换 IP/网关。 */
    private void postProcess(String dir, File rootfs) throws Exception {
        String prefix = "templates/" + dir + "/";
        if ("be".equals(dir)) {
            writeTemplate(prefix + "start.sh", new File(rootfs, "opt/penpot/backend/start.sh"), true);
            writeTemplate("templates/libacceptshim.so", new File(rootfs, "opt/shim/libacceptshim.so"), false);
            writeTemplate("templates/resolv.conf", new File(rootfs, "etc/resolv.conf"), true);
        } else if ("fe".equals(dir)) {
            writeTemplate(prefix + "start.sh", new File(rootfs, "start.sh"), true);
            writeTemplate(prefix + "nginx.conf", new File(rootfs, "etc/nginx/nginx.conf"), false);
            // entrypoint.sh 每次启动都会 envsubst /tmp/nginx.conf.template → nginx.conf，
            // 所以真正生效的是 template；必须把 listen 3449 的版本也写到这里，否则被覆盖回 8080。
            writeTemplate(prefix + "nginx.conf", new File(rootfs, "tmp/nginx.conf.template"), false);
            writeTemplate(prefix + "resolvers.conf", new File(rootfs, "etc/nginx/overrides/http.d/resolvers.conf"), true);
            writeTemplate("templates/libacceptshim.so", new File(rootfs, "opt/shim/libacceptshim.so"), false);
            writeTemplate("templates/resolv.conf", new File(rootfs, "etc/resolv.conf"), true);
        } else if ("ex".equals(dir)) {
            writeTemplate(prefix + "start.sh", new File(rootfs, "opt/penpot/exporter/start.sh"), true);
            writeTemplate("templates/libacceptshim.so", new File(rootfs, "opt/shim/libacceptshim.so"), false);
            installWrapper(rootfs, "opt/penpot/browsers/chromium-1234/chrome-linux/chrome",
                           "templates/ex/chrome-wrapper", "chrome.real");
            installWrapper(rootfs, "opt/penpot/browsers/chromium_headless_shell-1234/chrome-linux/headless_shell",
                           "templates/ex/headless-wrapper", "headless_shell.real");
        }
    }

    /** 把 chromium ELF 改名为 .real，wrapper 覆盖原名（保留可执行权限）。 */
    private void installWrapper(File rootfs, String relPath, String template, String realName) throws Exception {
        File bin = new File(rootfs, relPath);
        if (!bin.exists()) { cb.log("wrapper 目标不存在，跳过: " + relPath); return; }
        File real = new File(bin.getParentFile(), realName);
        if (!real.exists() && !bin.renameTo(real)) {
            copyFile(bin, real);
        }
        writeTemplate(template, bin, false);
        bin.setExecutable(true, false);
        real.setExecutable(true, false);
        cb.log("wrapper 已装: " + relPath);
    }

    /** 从 assets 读模板，可选替换 IP/网关，写到目标（建父目录、保留可执行位）。 */
    private void writeTemplate(String asset, File dst, boolean replaceIp) throws Exception {
        byte[] data = readAsset(ctx, asset);
        if (replaceIp) {
            String s = new String(data, "UTF-8");
            s = s.replace("192.168.2.8", lanIp)      // PENPOT_PUBLIC_URI / PENPOT_HOST
                 .replace("192.168.2.1", gateway);   // resolver / nameserver
            data = s.getBytes("UTF-8");
        }
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        FileOutputStream fos = new FileOutputStream(dst);
        try { fos.write(data); } finally { fos.close(); }
        if (asset.endsWith(".so") || asset.endsWith(".sh") || asset.contains("wrapper") || asset.endsWith("chrome")) {
            dst.setExecutable(true, false);
        }
        cb.log("  写入 " + dst.getName() + (replaceIp ? " (IP=" + lanIp + ", 网关=" + gateway + ")" : ""));
    }

    static byte[] readAsset(Context ctx, String path) throws IOException {
        InputStream is = ctx.getAssets().open(path);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally { is.close(); }
    }

    static void copyFile(File src, File dst) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        try {
            FileOutputStream fos = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
            } finally { fos.close(); }
        } finally { fis.close(); }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) {
            if (!c.isDirectory() && !isSymlink(c)) c.delete();
            else if (isSymlink(c)) c.delete();
            else deleteRecursive(c);
        }
        f.delete();
    }

    static boolean isSymlink(File f) {
        try { return java.nio.file.Files.isSymbolicLink(f.toPath()); }
        catch (Throwable t) { return false; }
    }

    /** 供 Service 启动各组件用的 rootfs 路径。 */
    public File rootFs(String name) { return new File(internalAppsDir(), name); }
}
