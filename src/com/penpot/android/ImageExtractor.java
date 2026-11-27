package com.penpot.android;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * OCI 镜像层解包器：把 .tar.gz / .tar.zst 层按顺序解到 rootfs 目录。
 * - gzip: JDK GZIPInputStream
 * - zstd: exec 外部 bionic zstd 二进制（zstd -d -c file -> stdout=tar）
 * - tar:  手写解析，支持 GNU longname、目录、普通文件、符号链接、硬链接、权限
 * - Android 禁 link()：硬链接退化为 copyFile
 * - 大文件流式写盘（不整块读入内存，避免 OOM：exporter 里有 487MB 的单个文件）
 */
public final class ImageExtractor {
    private final String zstdBin;
    private final String libDir;
    private final Callback cb;

    public interface Callback {
        void log(String msg);
    }

    /** libDir: 放 libzstd.so.1/libz.so.1/liblzma.so.5 的目录（zstd 是 bionic 动态链接）。 */
    public ImageExtractor(String zstdBin, String libDir, Callback cb) {
        this.zstdBin = zstdBin;
        this.libDir = libDir;
        this.cb = cb;
    }

    // ---------- tar 头解析 ----------

    private static final class TarEntry {
        String name;
        int mode;
        char type;
        String linkName;
        long size;
        boolean isDir;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        try {
            return new String(b, off, end - off, "UTF-8");
        } catch (Exception e) {
            return new String(b, off, end - off);
        }
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xff;
            if (c >= '0' && c <= '7') v = v * 8 + (c - '0');
            else if (c == 0 || c == ' ') continue;
            else break;
        }
        return v;
    }

    private static TarEntry parseHeader(byte[] h) {
        if (h.length < 512) return null;
        boolean zero = true;
        for (byte x : h) if (x != 0) { zero = false; break; }
        if (zero) return null;
        TarEntry e = new TarEntry();
        e.name = str(h, 0, 100);
        e.mode = (int) octal(h, 100, 8);
        e.size = octal(h, 124, 12);
        e.type = h[156] == 0 ? '0' : (char) (h[156] & 0xff);
        e.linkName = str(h, 157, 100);
        String prefix = str(h, 345, 155);
        if (prefix.length() > 0) e.name = prefix + "/" + e.name;
        e.isDir = (e.type == '5') || e.name.endsWith("/");
        return e;
    }

    // ---------- 权限 ----------

    private void chmod(File f, int mode) {
        try {
            Set<PosixFilePermission> p = new HashSet<PosixFilePermission>();
            if ((mode & 0400) != 0) p.add(PosixFilePermission.OWNER_READ);
            if ((mode & 0200) != 0) p.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100) != 0) p.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040) != 0) p.add(PosixFilePermission.GROUP_READ);
            if ((mode & 0020) != 0) p.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010) != 0) p.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004) != 0) p.add(PosixFilePermission.OTHERS_READ);
            if ((mode & 0002) != 0) p.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001) != 0) p.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(f.toPath(), p);
        } catch (Throwable ignored) {
            // API24 或 FS 不支持时忽略；关键二进制后续统一 chmod +x
        }
    }

    private static boolean isSymlink(File f) {
        try { return Files.isSymbolicLink(f.toPath()); } catch (Throwable t) { return false; }
    }

    /**
     * 逐级建目录，遇到「挡路的非目录」（普通文件，或指向不存在目标的悬空符号链接）就先删掉。
     * 直接 parent.mkdirs() 在这种情形下会静默失败，随后 open 报 ENOENT。
     * （exporter 层里 /root/.local/share/pnpm/... 就有这种结构）
     */
    private static void mkdirsRobust(File root, File dir) {
        if (dir == null) return;
        String rootCanon, dirCanon;
        try {
            rootCanon = root.getCanonicalPath();
            dirCanon = dir.getCanonicalPath();
        } catch (Throwable t) { dir.mkdirs(); return; }
        if (!dirCanon.startsWith(rootCanon)) return;
        String rel = dirCanon.substring(rootCanon.length());
        File cur = root;
        for (String part : rel.split("/")) {
            if (part.length() == 0) continue;
            cur = new File(cur, part);
            if (cur.isDirectory() && !isSymlink(cur)) continue;
            if (cur.exists() || isSymlink(cur)) deleteRecursive(cur);
            if (!cur.mkdir() && !cur.isDirectory()) {
                deleteRecursive(cur);
                cur.mkdir();
            }
        }
    }

    /** 递归删除（目录挡路时用）。不跟随符号链接。 */
    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        try {
            if (Files.isSymbolicLink(f.toPath())) { f.delete(); return; }
        } catch (Throwable ignored) {}
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    /** 跳过 n 字节（tar 数据区）。 */
    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        byte[] buf = new byte[1 << 16];
        while (remaining > 0) {
            int want = (int) Math.min(buf.length, remaining);
            int got = in.read(buf, 0, want);
            if (got < 0) break;
            remaining -= got;
        }
    }

    /**
     * 处理单个 tar 条目：普通文件直接流式写盘（不占内存）。
     * 调用前 din 位于该条目数据区起始处。
     */
    private void applyStream(File root, TarEntry e, InputStream din,
                             List<String[]> pendingLinks) throws IOException {
        if (e.name == null || e.name.length() == 0) { skipFully(din, e.size); return; }
        String name = e.name;
        while (name.startsWith("./")) name = name.substring(2);
        File f = new File(root, name);
        String canon = root.getCanonicalPath();
        if (!f.getCanonicalPath().startsWith(canon)) { skipFully(din, e.size); return; }

        if (e.isDir) {
            if (!f.isDirectory()) { deleteRecursive(f); }
            mkdirsRobust(root, f);
            skipFully(din, e.size);
            return;
        }
        mkdirsRobust(root, f.getParentFile());

        char type = e.type;
        if (type == 'L') { // GNU long name（数据即真实名字，较小）
            byte[] d = new byte[(int) e.size];
            readFully(din, d);
            return;
        }
        if (type == '2') { // 符号链接
            String target = e.linkName;
            skipFully(din, e.size);
            try {
                deleteRecursive(f);
                mkdirsRobust(root, f.getParentFile());
                Files.createSymbolicLink(f.toPath(), Paths.get(target));
            } catch (Throwable t) {
                File tf = new File(root, target.startsWith("/") ? target.substring(1) : target);
                if (tf.exists()) {
                    try { copyFile(tf, f); } catch (Throwable ignored) {}
                }
            }
            return;
        }
        if (type == '1') { // 硬链接
            skipFully(din, e.size);
            pendingLinks.add(new String[]{name, e.linkName});
            return;
        }
        // 普通文件：流式写盘（关键：不 new byte[size]，否则大文件 OOM）
        // 注意 dangling symlink：exists() 为 false 但路径仍被符号链接占着，
        // 直接 open 会跟随链接报 ENOENT，必须先删掉链接本身。
        if (f.exists() || isSymlink(f)) deleteRecursive(f);
        FileOutputStream o = new FileOutputStream(f);
        try {
            long remaining = e.size;
            byte[] buf = new byte[1 << 16];
            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                int got = din.read(buf, 0, want);
                if (got < 0) break;
                o.write(buf, 0, got);
                remaining -= got;
            }
        } finally {
            try { o.close(); } catch (Throwable ignored) {}
        }
        chmod(f, e.mode);
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) break;
            got += n;
        }
    }

    /** 解一层压缩 tar 到 root。 */
    private void extractLayer(File tarFile, boolean zstd, File root) throws Exception {
        InputStream raw;
        final Process zproc;
        final StringBuilder zerr = new StringBuilder();
        if (zstd) {
            ProcessBuilder pb = new ProcessBuilder(zstdBin, "-d", "-c", tarFile.getAbsolutePath());
            if (libDir != null && new File(libDir).exists()) {
                pb.environment().put("LD_LIBRARY_PATH", libDir);
            }
            zproc = pb.start();
            raw = zproc.getInputStream();
            final InputStream err = zproc.getErrorStream();
            new Thread(new Runnable() { public void run() {
                try {
                    byte[] b = new byte[1024]; int n;
                    while ((n = err.read(b)) != -1) {
                        try { zerr.append(new String(b, 0, n, "UTF-8")); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            } }).start();
        } else {
            zproc = null;
            raw = new FileInputStream(tarFile);
        }
        InputStream in = zstd ? raw : new GZIPInputStream(raw);
        List<String[]> pending = new ArrayList<String[]>();
        DataInputStream din = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        String pendingLongName = null;
        String pendingPaxPath = null;
        String pendingPaxLink = null;
        int entries = 0;
        try {
            while (true) {
                byte[] h = new byte[512];
                int got = 0;
                while (got < 512) {
                    int n = din.read(h, got, 512 - got);
                    if (n < 0) break;
                    got += n;
                }
                if (got < 512) break;
                TarEntry e = parseHeader(h);
                if (e == null) break;

                // GNU longname：数据就是真实文件名，需先读出来
                if (e.type == 'L') {
                    byte[] d = new byte[(int) e.size];
                    readFully(din, d);
                    pendingLongName = str(d, 0, d.length).trim();
                    int padL = (int) ((512 - (e.size % 512)) % 512);
                    if (padL > 0) skipFully(din, padL);
                    continue;
                }
                // PAX 扩展头（'x' 单条 / 'g' 全局）：路径超 100 字节时 Docker 用它存完整名字。
                // 不解析会导致超长路径被截断成 100 字节内的假名字（曾造成 ENOENT / 同名冲突）。
                if (e.type == 'x' || e.type == 'g') {
                    byte[] d = new byte[(int) e.size];
                    readFully(din, d);
                    String rec = str(d, 0, d.length);
                    int padP = (int) ((512 - (e.size % 512)) % 512);
                    if (padP > 0) skipFully(din, padP);
                    // 记录格式："<len> <key>=<value>\n"
                    int i = 0;
                    while (i < rec.length()) {
                        int sp = rec.indexOf(' ', i);
                        if (sp < 0) break;
                        int len;
                        try { len = Integer.parseInt(rec.substring(i, sp).trim()); }
                        catch (Exception bad) { break; }
                        if (len <= 0 || i + len > rec.length()) break;
                        String line = rec.substring(sp + 1, i + len);
                        if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            String k = line.substring(0, eq);
                            String v = line.substring(eq + 1);
                            if ("path".equals(k)) pendingPaxPath = v;
                            else if ("linkpath".equals(k)) pendingPaxLink = v;
                        }
                        i += len;
                    }
                    continue;
                }
                if (pendingLongName != null) {
                    e.name = pendingLongName;
                    pendingLongName = null;
                }
                if (pendingPaxPath != null) {
                    e.name = pendingPaxPath;
                    pendingPaxPath = null;
                }
                if (pendingPaxLink != null) {
                    e.linkName = pendingPaxLink;
                    pendingPaxLink = null;
                }

                applyStream(root, e, din, pending);
                entries++;
                int pad = (int) ((512 - (e.size % 512)) % 512);
                if (pad > 0) skipFully(din, pad);
            }
        } finally {
            try { din.close(); } catch (Throwable ignored) {}
        }
        if (zproc != null) {
            int zc = -1;
            try { zc = zproc.waitFor(); } catch (InterruptedException ignored) {}
            if (zc != 0) {
                throw new IOException("zstd 解压失败 exit=" + zc + " err=" + zerr.substring(0, Math.min(300, zerr.length())));
            }
        }
        if (entries == 0) {
            // Docker 允许“空层”：仅元数据变更，标准空 tar.gz 恰好 32 字节。
            cb.log("  空层（仅元数据，0 条目） " + tarFile.getName());
        } else {
            cb.log("  解包 " + entries + " 条目 " + tarFile.getName());
        }
        // 处理硬链接（多轮，目标可能在其他层/稍后）
        for (int round = 0; round < 5; round++) {
            int n = 0;
            for (String[] pair : pending) {
                File d = new File(root, pair[0]);
                if (d.exists()) continue;
                File t = new File(root, pair[1].startsWith("/") ? pair[1].substring(1) : pair[1]);
                if (!t.exists()) continue;
                File p = d.getParentFile();
                if (p != null) p.mkdirs();
                try { copyFile(t, d); n++; } catch (Throwable ignored) {}
            }
            if (n == 0) break;
        }
    }

    /** 按顺序解一组层（File 列表，扩展名判定 gzip/zstd）。 */
    public void extractAll(List<File> layerFiles, File root) throws Exception {
        if (!root.exists()) root.mkdirs();
        int i = 0;
        for (File f : layerFiles) {
            i++;
            cb.log("解包 " + i + "/" + layerFiles.size() + " " + f.getName());
            extractLayer(f, f.getName().endsWith(".zst"), root);
        }
        cb.log("解包完成 " + root.getName());
    }
}
