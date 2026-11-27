package com.penpot.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Docker Hub OCI 镜像下载器（移植自已验证的 dl3.mjs / oneblob.mjs）。
 * 流程：anonymous token -> manifest(index->arm64) -> 每层 2MB 分块 8 并发 14 重试 -> sha256 校验。
 * 纯 Java 8 + Android API24 兼容（org.json / HttpURLConnection 为框架自带，无第三方依赖）。
 */
public final class RegistryClient {
    private static final String REG = "https://registry-1.docker.io";
    private static final String AUTH =
            "https://auth.docker.io/token?service=registry.docker.io&scope=repository:%s:pull";
    private static final String ACCEPT =
            "application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,"
                    + "application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json";
    private static final int CHUNK = 2 * 1024 * 1024;   // 2MB
    private static final int CONC = 8;                   // 并发分块
    private static final int TRIES = 14;                 // 每块重试
    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 120000;

    private String token;
    private final Callback cb;

    public interface Callback {
        void log(String msg);
        void progress(String layerLabel, long bytes, long total, int layerIdx, int layerCount);
    }

    public RegistryClient(Callback cb) {
        this.cb = cb;
    }

    private static final class Layer {
        String digest;
        long size;
        String mediaType;
    }

    // ---------- HTTP 基础 ----------

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** 取 anonymous pull token；force=true 强制刷新。 */
    private String token(String repo, boolean force) throws Exception {
        if (token != null && !force) return token;
        String url = String.format(AUTH, repo);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        byte[] body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code != 200) throw new Exception("token http " + code + " " + new String(body, "UTF-8"));
        JSONObject j = new JSONObject(new String(body, "UTF-8"));
        token = j.getString("token");
        return token;
    }

    /** GET JSON（manifest / config），带 Bearer + Accept。 */
    private JSONObject getJson(String urlStr, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestMethod("GET");
        c.setRequestProperty("authorization", "Bearer " + token);
        if (accept != null) c.setRequestProperty("accept", accept);
        int code = c.getResponseCode();
        byte[] body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code != 200) throw new Exception("GET http " + code + " : " + new String(body, "UTF-8").substring(0, Math.min(200, body.length)));
        return new JSONObject(new String(body, "UTF-8"));
    }

    /** 获取 manifest，若是 index 则选出 arm64/linux 子 manifest。 */
    private JSONObject manifest(String repo, String ref) throws Exception {
        JSONObject m = getJson(REG + "/v2/" + repo + "/manifests/" + ref, ACCEPT);
        if (m.has("manifests")) {
            JSONArray arr = m.getJSONArray("manifests");
            JSONObject arm = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.getJSONObject(i);
                if (x.has("platform")) {
                    JSONObject p = x.getJSONObject("platform");
                    if ("arm64".equals(p.optString("architecture")) && "linux".equals(p.optString("os"))) {
                        arm = x;
                        break;
                    }
                }
            }
            if (arm == null) throw new Exception("no arm64 manifest");
            m = getJson(REG + "/v2/" + repo + "/manifests/" + arm.getString("digest"), ACCEPT);
        }
        return m;
    }

    private List<Layer> layers(JSONObject m) throws Exception {
        List<Layer> out = new ArrayList<Layer>();
        JSONArray arr = m.getJSONArray("layers");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.getJSONObject(i);
            Layer l = new Layer();
            l.digest = x.getString("digest");
            l.size = x.getLong("size");
            l.mediaType = x.optString("mediaType", "");
            out.add(l);
        }
        return out;
    }

    // ---------- 分块下载 ----------

    /** 下载单个 Range 块，带重试 + 401 刷新 token。 */
    private void chunk(String repo, String digest, long off, long end, FileChannel fc) throws Exception {
        Exception last = null;
        for (int a = 0; a < TRIES; a++) {
            try {
                if (token == null) token(repo, true);
                HttpURLConnection c = (HttpURLConnection) new URL(REG + "/v2/" + repo + "/blobs/" + digest).openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT);
                c.setReadTimeout(READ_TIMEOUT);
                c.setRequestMethod("GET");
                c.setRequestProperty("authorization", "Bearer " + token);
                c.setRequestProperty("range", "bytes=" + off + "-" + end);
                int code = c.getResponseCode();
                if (code == 401) {
                    c.disconnect();
                    token = null;
                    token(repo, true);
                    throw new Exception("401 refresh");
                }
                if (code != 206 && code != 200) {
                    c.disconnect();
                    throw new Exception("http " + code);
                }
                byte[] body = readAll(c.getInputStream());
                c.disconnect();
                int want = (int) (end - off + 1);
                if (body.length != want) throw new Exception("short " + body.length + "/" + want);
                fc.write(ByteBuffer.wrap(body), off);   // FileChannel 按绝对 position 写，线程安全
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(600L * (a + 1));
            }
        }
        throw last;
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 16];
        FileInputStream in = new FileInputStream(f);
        int n;
        while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        in.close();
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private void downloadLayer(final String repo, final Layer l, final File out, final String label,
                               final int idx, final int count) throws Exception {
        String want = l.digest.contains(":") ? l.digest.substring(l.digest.indexOf(':') + 1) : l.digest;
        // 断点：已存在且大小对 + sha256 对，则跳过
        if (out.exists() && out.length() == l.size) {
            String got = sha256(out);
            if (got.equals(want)) {
                cb.log("跳过已下载 " + label);
                return;
            }
            cb.log("校验失败重下 " + label);
        }
        cb.log("下载 " + label + " " + (l.size / 1048576) + "MB");
        long t0 = System.currentTimeMillis();
        for (int att = 1; att <= 4; att++) {
            RandomAccessFile raf = new RandomAccessFile(out, "rw");
            FileChannel fc = raf.getChannel();
            try {
                fc.truncate(l.size);
                final long[] cursor = {0};
                final Object lock = new Object();
                final AtomicLong done = new AtomicLong(0);
                ExecutorService pool = Executors.newFixedThreadPool(CONC);
                final List<Future<?>> futures = new ArrayList<Future<?>>();
                for (int w = 0; w < CONC; w++) {
                    futures.add(pool.submit(new Runnable() {
                        public void run() {
                            while (true) {
                                long off, end;
                                synchronized (lock) {
                                    if (cursor[0] >= l.size) break;
                                    off = cursor[0];
                                    cursor[0] += CHUNK;
                                    end = Math.min(off + CHUNK, l.size) - 1;
                                }
                                try {
                                    chunk(repo, l.digest, off, end, fc);
                                    long d = done.addAndGet(end - off + 1);
                                    cb.progress(label, d, l.size, idx, count);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    }));
                }
                pool.shutdown();
                for (Future<?> f : futures) f.get();   // 抛错则进入下一 att
                fc.close();
                raf.close();
                String got = sha256(out);
                if (!got.equals(want)) {
                    cb.log("层 " + label + " sha256 不符，重试 " + att);
                    out.delete();
                    continue;
                }
                cb.log("完成 " + label + " " + ((System.currentTimeMillis() - t0) / 1000) + "s");
                return;
            } catch (Exception e) {
                try { fc.close(); } catch (Throwable ignored) {}
                try { raf.close(); } catch (Throwable ignored) {}
                out.delete();
                cb.log("层 " + label + " 下载失败(" + e.getMessage() + ")，重试 " + att);
                Thread.sleep(1500);
            }
        }
        throw new Exception("layer download failed: " + label);
    }

    /** 下载一个完整镜像的全部层到 outDir。返回层文件列表（按序）。 */
    public List<File> downloadImage(String repo, String ref, File outDir) throws Exception {
        Exception last = null;
        JSONObject man = null;
        for (int a = 0; ; a++) {
            try {
                token(repo, true);
                man = manifest(repo, ref);
                break;
            } catch (Exception e) {
                last = e;
                if (a >= 20) throw e;
                cb.log("manifest 重试 " + a + " " + e.getMessage());
                Thread.sleep(2000);
            }
        }
        if (!outDir.exists()) outDir.mkdirs();
        writeText(new File(outDir, "manifest.json"), man.toString());
        List<Layer> ls = layers(man);
        List<File> files = new ArrayList<File>();
        for (int i = 0; i < ls.size(); i++) {
            Layer l = ls.get(i);
            String ext = l.mediaType.contains("zstd") ? ".tar.zst" : ".tar.gz";
            String shortDigest = l.digest.length() > 19 ? l.digest.substring(7, 19) : l.digest;
            String name = String.format("%02d_%s%s", i + 1, shortDigest, ext);
            File f = new File(outDir, name);
            downloadLayer(repo, l, f, name, i + 1, ls.size());
            files.add(f);
        }
        cb.log("镜像下载完成: " + repo + " (" + ls.size() + " 层)");
        return files;
    }

    private static void writeText(File f, String s) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8"));
        o.close();
    }
}
