package com.penpot.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.*;
import java.util.*;

/**
 * 首次启动资产释放：
 * - dsh_alpine (静态 loader) -> filesDir/dsh_alpine
 * - zstd (bionic 链接解压器) -> filesDir/zstd
 * - base-min.tar.gz -> filesDir/base  (PG/Valkey/musl/shim 精简 rootfs)
 * 全部落在 App 私有目录 (ext4)，支持符号链接与 chmod +x。
 */
public final class AssetInstaller {
    private final Context ctx;
    private final Callback cb;

    public interface Callback {
        void log(String msg);
    }

    private static final String PREF = "penpot_prefs";
    private static final String KEY_INSTALLED = "base_installed";
    private static final String BASE_VER = "base_v3";

    public AssetInstaller(Context ctx, Callback cb) {
        this.ctx = ctx;
        this.cb = cb;
    }

    public File filesDir() { return ctx.getFilesDir(); }
    public File baseDir() { return new File(filesDir(), "base"); }
    public File loader() { return new File(filesDir(), "dsh_alpine"); }
    public File zstd() { return new File(filesDir(), "zstd"); }
    public File libDir() { return new File(filesDir(), "zlib"); }

    public boolean isInstalled() {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return BASE_VER.equals(sp.getString(KEY_INSTALLED, "")) && baseDir().exists();
    }

    private void copyAsset(String name, File dest) throws IOException {
        cb.log("释放 " + name);
        InputStream in = ctx.getAssets().open(name);
        OutputStream os = new FileOutputStream(dest);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.close();
        in.close();
        dest.setExecutable(true, false);
        dest.setReadable(true, false);
    }

    /** 解压 base tar.gz 到 filesDir/base。 */
    private void unpackBase(File gz) throws Exception {
        cb.log("解压运行环境 base（约 30M，请稍候）...");
        File root = baseDir();
        // 清理上次可能残留的空/坏 base，避免假成功后遗留
        if (root.exists()) deleteRecursive(root);
        root.mkdirs();
        ImageExtractor extractor = new ImageExtractor(zstd().getAbsolutePath(), libDir().getAbsolutePath(), new ImageExtractor.Callback() {
            public void log(String m) { cb.log(m); }
        });
        extractor.extractAll(Arrays.asList(gz), root);
        // 关键二进制统一 chmod +x（tar 权限可能被 FS 吞掉）
        String[] execs = {
            "bin/busybox",
            "opt/shim/pgstart.sh", "opt/shim/pgctl.sh",
            "usr/libexec/postgresql17/initdb", "usr/libexec/postgresql17/postgres",
            "usr/libexec/postgresql17/pg_ctl", "usr/libexec/postgresql17/psql",
            "usr/bin/valkey-server"
        };
        for (String e : execs) {
            File f = new File(root, e);
            if (f.exists()) f.setExecutable(true, false);
        }
        cb.log("运行环境 base 释放完成");
    }

    /** 执行安装（幂等）。 */
    public synchronized void install() throws Exception {
        if (isInstalled()) { cb.log("运行环境已安装，跳过"); return; }
        copyAsset("dsh_alpine", loader());
        copyAsset("zstd", zstd());
        // zstd 是 bionic 动态链接，需三个依赖库；放到 zlib/ 并传给 ImageExtractor 作 LD_LIBRARY_PATH
        File lib = libDir();
        lib.mkdirs();
        copyAsset("libzstd.so.1", new File(lib, "libzstd.so.1"));
        copyAsset("libz.so.1", new File(lib, "libz.so.1"));
        copyAsset("liblzma.so.5", new File(lib, "liblzma.so.5"));
        File gz = new File(filesDir(), "base.tar.zst");
        copyAsset("base.tar.zst", gz);
        unpackBase(gz);
        gz.delete(); // 释放空间
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_INSTALLED, BASE_VER).commit();
        cb.log("✅ 运行环境安装完成");
    }

    /** 强制重装（调试用）。 */
    public synchronized void reinstall() throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_INSTALLED).commit();
        deleteRecursive(baseDir());
        install();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
