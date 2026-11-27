package com.penpot.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 检查 Penpot 官方最新版本。
 *
 * 数据源：Docker Registry v2 的 tags/list（penpotapp/frontend）。
 * 为什么不用 GitHub releases API：实测被限流/不稳定（返回 undefined）；而
 * registry-1.docker.io 正是我们下载镜像用的同一个源，可用性已被验证（143 个版本）。
 */
public final class VersionChecker {

    private static final String REG = "https://registry-1.docker.io";
    private static final String AUTH =
            "https://auth.docker.io/token?service=registry.docker.io&scope=repository:%s:pull";
    private static final String REPO = "penpotapp/frontend";

    public interface Callback { void log(String msg); }

    private final Callback cb;

    public VersionChecker(Callback cb) { this.cb = cb; }

    /** 返回最新版本号（如 "2.19.5"）；失败抛异常。 */
    public String fetchLatest() throws Exception {
        String tok = token();
        String url = REG + "/v2/" + REPO + "/tags/list?n=2000";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Authorization", "Bearer " + tok);
        c.setRequestProperty("User-Agent", "penpot-android/1.0");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("tags/list HTTP " + code);
            String body = new String(readAll(c.getInputStream()), "UTF-8");
            JSONObject j = new JSONObject(body);
            JSONArray arr = j.optJSONArray("tags");
            if (arr == null) throw new Exception("tags 字段缺失");
            List<String> sem = new ArrayList<String>();
            for (int i = 0; i < arr.length(); i++) {
                String t = arr.optString(i, "");
                if (isSemver(t)) sem.add(t);
            }
            if (sem.isEmpty()) throw new Exception("没有找到语义化版本 tag");
            Collections.sort(sem, new Comparator<String>() {
                public int compare(String a, String b) { return cmp(a, b); }
            });
            String latest = sem.get(sem.size() - 1);
            if (cb != null) cb.log("官方版本共 " + sem.size() + " 个，最新 " + latest);
            return latest;
        } finally {
            c.disconnect();
        }
    }

    private String token() throws Exception {
        String url = String.format(AUTH, REPO);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try {
            if (c.getResponseCode() != 200) throw new Exception("获取 token 失败 HTTP " + c.getResponseCode());
            String body = new String(readAll(c.getInputStream()), "UTF-8");
            String t = new JSONObject(body).optString("token", "");
            if (t.isEmpty()) throw new Exception("token 为空");
            return t;
        } finally {
            c.disconnect();
        }
    }

    private static boolean isSemver(String s) {
        if (s == null || s.isEmpty()) return false;
        String[] p = s.split("\\.");
        if (p.length != 3) return false;
        for (String x : p) {
            if (x.isEmpty()) return false;
            for (int i = 0; i < x.length(); i++) if (!Character.isDigit(x.charAt(i))) return false;
        }
        return true;
    }

    /** 语义化版本比较：a>b 返回正，a<b 负，相等 0。 */
    public static int cmp(String a, String b) {
        int[] A = parts(a), B = parts(b);
        for (int i = 0; i < 3; i++) {
            if (A[i] != B[i]) return A[i] - B[i];
        }
        return 0;
    }

    private static int[] parts(String v) {
        int[] r = new int[]{0, 0, 0};
        try {
            String[] p = v.split("\\.");
            for (int i = 0; i < 3 && i < p.length; i++) r[i] = Integer.parseInt(p[i]);
        } catch (Throwable ignored) {}
        return r;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally { in.close(); }
    }
}
