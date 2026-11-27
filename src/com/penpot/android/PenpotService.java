package com.penpot.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.File;

/**
 * 前台服务：真实执行资产释放 + PG 初始化/启动，并用常驻通知保活。
 *
 * 状态机（STATE）放在 Service 里，Activity 只做渲染，避免 Activity 重建后状态错乱：
 *   0 = 已停止 / 1 = 启动中 / 2 = 运行中 / 3 = 启动失败 / 4 = 停止中
 */
public class PenpotService extends Service {

    public static final String ACTION_START = "com.penpot.android.action.START";
    public static final String ACTION_STOP = "com.penpot.android.action.STOP";
    public static final String ACTION_PROBE_PG = "com.penpot.android.action.PROBE_PG";

    public static final int ST_STOPPED = 0;
    public static final int ST_STARTING = 1;
    public static final int ST_RUNNING = 2;
    public static final int ST_FAILED = 3;
    public static final int ST_STOPPING = 4;

    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "penpot_service";

    /** 供 Activity 直接读取的全局状态。 */
    public static volatile int STATE = ST_STOPPED;

    private static volatile boolean running = false;
    private static RuntimeEngine engine = null;
    private static volatile Thread startThread = null;
    private PowerManager.WakeLock wakeLock = null;

    public static boolean isRunning() { return running || STATE == ST_RUNNING || STATE == ST_STARTING; }

    /** 供设置页发起停止（走 startService，确保和主界面同一条路径）。 */
    public static void requestStop(Context ctx) {
        try {
            Intent i = new Intent(ctx, PenpotService.class);
            i.setAction(ACTION_STOP);
            ctx.startService(i);
        } catch (Throwable t) { LogBus.log("requestStop 失败: " + t.getMessage()); }
    }

    /** 供设置页发起启动。 */
    public static void requestStart(Context ctx) {
        try {
            Intent i = new Intent(ctx, PenpotService.class);
            i.setAction(ACTION_START);
            ctx.startService(i);
        } catch (Throwable t) { LogBus.log("requestStart 失败: " + t.getMessage()); }
    }

    /** 下载/初始化期间持有 PARTIAL_WAKE_LOCK，防止锁屏后 MIUI 冻结后台网络导致下载卡死。 */
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "penpot:download");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            LogBus.log("🔒 WakeLock 已获取（下载期间保持唤醒）");
        } catch (Throwable t) {
            LogBus.log("⚠ WakeLock 获取失败: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                LogBus.log("🔓 WakeLock 已释放");
            }
        } catch (Throwable t) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
    }

    /** 常驻前台通知：显著提高进程优先级，避免切后台被系统回收。 */
    private void startAsForeground() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Penpot 服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Penpot 后台服务保活通知");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Penpot 服务运行中")
                .setContentText("Penpot 正在后台运行，局域网可访问网页版")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIF_ID, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        final String action = intent == null ? null : intent.getAction();

        // ---- 停止 ----
        if (ACTION_STOP.equals(action)) {
            doStop();
            return START_NOT_STICKY;
        }

        // ---- 系统回收后重启（intent 为 null）：已在运行就别重复启动 ----
        if (action == null) {
            if (STATE == ST_RUNNING || STATE == ST_STARTING) return START_STICKY;
            // 之前是运行中却被杀了 → 重新拉起
            if (running || STATE == ST_FAILED) { /* 落到下面重新启动 */ }
        }

        // ---- 启动（幂等：已在启动/运行中则不重复）----
        if (STATE == ST_STARTING || STATE == ST_RUNNING || running) {
            LogBus.log("服务已在运行，忽略重复启动");
            return START_STICKY;
        }
        STATE = ST_STARTING;
        LogBus.init(new java.io.File(getFilesDir(), "app.log"));
        startThread = new Thread(new Runnable() {
            public void run() { doStart(); }
        }, "penpot-init");
        startThread.start();
        return START_STICKY;
    }

    /** 真正停止：强杀所有子进程（loader 会吞 SIGTERM，必须用 SIGKILL + 兜底清扫）。 */
    private void doStop() {
        STATE = ST_STOPPING;
        LogBus.init(new java.io.File(getFilesDir(), "app.log"));
        LogBus.log("=== 正在停止服务 ===");
        running = false;
        releaseWakeLock();
        try {
            if (engine != null) engine.stopAll();
            else RuntimeEngine.killAllStrays(this);
        } catch (Throwable t) {
            LogBus.log("⚠ 停止异常: " + t);
        }
        engine = null;
        STATE = ST_STOPPED;
        LogBus.log("✅ Penpot 服务已停止");
        stopForeground(true);
        stopSelf();
    }

    private void doStart() {
        try {
            LogBus.init(new java.io.File(getFilesDir(), "app.log"));
            acquireWakeLock();
            LogBus.log("=== 开始启动 ===");
            final AssetInstaller.Callback cb = new AssetInstaller.Callback() {
                public void log(String m) { LogBus.log(m); }
            };
            AssetInstaller inst = new AssetInstaller(this, cb);
            LogBus.log("安装运行环境资产...");
            inst.install();
            LogBus.log("✅ 资产就绪");

            engine = new RuntimeEngine(inst);

            // 1) PostgreSQL（musl base + 三 shim）
            LogBus.log("初始化 PostgreSQL...");
            if (!engine.initPg()) { fail("PG 初始化失败"); return; }
            LogBus.log("启动 PostgreSQL...");
            if (!engine.startPg()) { fail("PG 启动失败"); return; }

            // 2) Valkey（musl base）
            LogBus.log("启动 Valkey...");
            if (!engine.startValkey()) { fail("Valkey 启动失败"); return; }

            // 3) 下载 backend/frontend/exporter OCI 镜像 + 后处理（首次较慢）
            final DownloadCoordinator dc = new DownloadCoordinator(this, inst,
                new DownloadCoordinator.Callback() {
                    public void log(String m) { LogBus.log(m); }
                    public void stage(String stage, int pct) {
                        LogBus.log("[" + pct + "%] " + stage);
                    }
                });
            LogBus.log("检查 Penpot 应用镜像...");
            if (!dc.installAll()) { fail("应用镜像下载/安装失败"); return; }

            // 4) 对象存储目录（BE 的 -m /opt/data/assets 挂载目标）
            File objects = new File(inst.filesDir(), "objects");
            objects.mkdirs();

            // 5) backend（glibc，6060）
            LogBus.log("启动 backend...");
            engine.startGlibcDaemon(dc.rootFs("be"),
                "/opt/penpot/backend/start.sh", "be.log", objects);
            if (!engine.waitPort(6060, 180)) { fail("backend 未在 180s 内就绪"); return; }

            // 6) frontend（glibc nginx，3449）
            LogBus.log("启动 frontend...");
            // FE 也要挂同一份对象存储：nginx 的 /internal/assets 直接 alias 到 /opt/data/assets，
            // 不挂的话图片/封面/缩略图全部 404（BE 能写、FE 读不到）。
            engine.startGlibcDaemon(dc.rootFs("fe"), "/start.sh", "fe.log", objects);
            if (!engine.waitPort(3449, 90)) { fail("frontend 未在 90s 内就绪"); return; }

            // 7) exporter（glibc node+chromium，6061）——可选，失败不影响主界面
            LogBus.log("启动 exporter...");
            engine.startGlibcDaemon(dc.rootFs("ex"),
                "/opt/penpot/exporter/start.sh", "ex.log", objects);
            engine.waitPort(6061, 90);

            running = true;
            STATE = ST_RUNNING;
            LogBus.log("✅✅ Penpot 全部组件启动成功，局域网网页版可访问");
            releaseWakeLock();
        } catch (Throwable t) {
            running = false;
            STATE = ST_FAILED;
            releaseWakeLock();
            LogBus.log("❌ 启动异常: " + t);
            LogBus.log(stack(t));
        }
    }

    private void fail(String reason) {
        running = false;
        STATE = ST_FAILED;
        releaseWakeLock();
        LogBus.log("❌ " + reason + "，终止启动");
    }

    private static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        t.printStackTrace(pw);
        String s = sw.toString();
        return s.length() > 1200 ? s.substring(0, 1200) : s;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 保底：服务被销毁时也不要留下野进程
        try { if (engine != null) engine.stopAll(); } catch (Throwable ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
