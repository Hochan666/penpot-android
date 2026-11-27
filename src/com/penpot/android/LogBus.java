package com.penpot.android;

import java.io.File;
import java.io.FileWriter;
import java.util.*;

/** 线程安全日志：Service 写内存（Activity 轮询读），同时落盘到 files/app.log 便于事后诊断。 */
public final class LogBus {
    private static final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    private static final int MAX = 400;
    private static File sink;   // 落盘目标；init() 之前只写内存

    /** 由 Service 启动时调用一次，指定落盘文件（一般是 files/app.log）。 */
    public static void init(File f) {
        synchronized (LogBus.class) {
            sink = f;
            if (sink != null) {
                File p = sink.getParentFile();
                if (p != null) p.mkdirs();
                try { if (sink.exists()) sink.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    public static void log(String s) {
        String line = java.text.DateFormat.getTimeInstance().format(new java.util.Date()) + "  " + s;
        synchronized (lines) {
            lines.add(line);
            while (lines.size() > MAX) lines.remove(0);
        }
        synchronized (LogBus.class) {
            if (sink == null) return;
            FileWriter w = null;
            try {
                w = new FileWriter(sink, true);
                w.write(line);
                w.write('\n');
            } catch (Throwable ignored) {
            } finally {
                if (w != null) try { w.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public static void clear() { synchronized (lines) { lines.clear(); } }

    public static String snapshot(int fromIdx) {
        synchronized (lines) {
            if (fromIdx < 0) fromIdx = 0;
            if (fromIdx >= lines.size()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = fromIdx; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
            return sb.toString();
        }
    }

    public static int size() { synchronized (lines) { return lines.size(); } }
}
