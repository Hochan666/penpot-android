package com.penpot.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** 动态获取本机局域网 IP —— 提示文案里的 X.X.X.X 必须运行时取，不能写死。 */
public class LanIpHelper {

    /** 返回最佳猜测的局域网 IP（优先 WiFi，其次遍历网卡）。失败返回 null。 */
    public static String getLanIp(Context ctx) {
        // 1) WiFi 的 IP（最常见场景：手机连 WiFi，局域网设备同网段）
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    int ip = wi.getIpAddress();
                    if (ip != 0) return intToIp(ip);
                }
            }
        } catch (Throwable ignored) {}

        // 2) 遍历所有网卡，找非 loopback 的 IPv4（覆盖热点/以太网场景）
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                String name = ni.getName();
                // 跳过虚拟/隧道网卡
                if (name != null && (name.startsWith("tun") || name.startsWith("ppp") ||
                        name.startsWith("dummy") || name.startsWith("lo"))) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && !a.isLinkLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static String intToIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." +
                ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    /** 生成提示文案 */
    public static String buildHint(Context ctx, int port) {
        String ip = getLanIp(ctx);
        if (ip == null) {
            return "服务已启动\n请确保已连接 WiFi 或开启热点后，在局域网访问 3449 端口";
        }
        return "Penpot 服务已启动，在局域网中访问：\n" + ip + ":" + port + "\n可访问 Penpot 网页";
    }
}
