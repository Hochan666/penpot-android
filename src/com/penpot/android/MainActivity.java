package com.penpot.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 主界面：深色科技风。服务逻辑与之前完全一致，仅升级视觉。 */
public class MainActivity extends Activity {

    private static final String PREF = "penpot_prefs";
    private static final int PORT = 3449;

    // ---- 调色板（Penpot 绿紫系）----
    private static final int C_BG_TOP = 0xFF0A0E20;
    private static final int C_BG_BOTTOM = 0xFF05070F;
    private static final int C_CARD = 0xFF121A33;
    private static final int C_CARD_STROKE = 0xFF26304F;
    private static final int C_TEXT = 0xFFE9EDFA;
    private static final int C_SUB = 0xFF8791B2;
    private static final int C_GREEN = 0xFF31EFB9;
    private static final int C_PURPLE = 0xFF9B7BFF;
    private static final int C_RED = 0xFFFF6B6B;
    private static final int C_AMBER = 0xFFFFB84D;

    private TextView tvStatus, tvHint, tvLog, tvStatusDot, tvStorage;
    private Button btnStart, btnStop;
    private SharedPreferences prefs;
    private boolean serviceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        buildUi();
        serviceRunning = PenpotService.isRunning();
        updateButtons();
        if (serviceRunning) {
            setStatus("运行中", C_GREEN);
            tvHint.setText(LanIpHelper.buildHint(this, PORT));
        }
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private GradientDrawable round(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(38), dp(22), dp(20));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{C_BG_TOP, 0xFF0B1030, C_BG_BOTTOM});
        root.setBackground(bg);

        // ---------- 顶部品牌 ----------
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        // 左上角设置按钮（齿轮）——点击进入设置页
        TextView btnSettings = new TextView(this);
        btnSettings.setText("⚙");
        btnSettings.setTextSize(21);
        btnSettings.setTextColor(C_SUB);
        btnSettings.setGravity(Gravity.CENTER);
        btnSettings.setBackground(round(0xFF0E1730, C_CARD_STROKE, 11));
        btnSettings.setWidth(dp(40));
        btnSettings.setHeight(dp(40));
        btnSettings.setClickable(true);
        btnSettings.setContentDescription("设置");
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        gearLp.rightMargin = dp(12);
        btnSettings.setLayoutParams(gearLp);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        head.addView(btnSettings);

        TextView mark = new TextView(this);
        mark.setText("P");
        mark.setTextSize(30);
        mark.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(0xFF07101F);
        GradientDrawable markBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{C_GREEN, C_PURPLE});
        markBg.setCornerRadius(dp(16));
        mark.setBackground(markBg);
        mark.setWidth(dp(52));
        mark.setHeight(dp(52));
        head.addView(mark);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp(14), 0, 0, 0);

        TextView title = new TextView(this);
        title.setText("Penpot");
        title.setTextSize(25);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setTextColor(C_TEXT);
        title.setLetterSpacing(0.04f);
        titleCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText("本地设计服务 · LOCAL SERVER");
        sub.setTextSize(11);
        sub.setTextColor(C_SUB);
        sub.setLetterSpacing(0.10f);
        sub.setPadding(0, dp(3), 0, 0);
        titleCol.addView(sub);

        head.addView(titleCol);
        root.addView(head);

        // ---------- 状态卡 ----------
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        statusCard.setBackground(round(C_CARD, C_CARD_STROKE, 18));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.topMargin = dp(24);
        statusCard.setLayoutParams(scLp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        tvStatusDot = new TextView(this);
        tvStatusDot.setText("●");
        tvStatusDot.setTextSize(13);
        tvStatusDot.setTextColor(C_SUB);
        statusRow.addView(tvStatusDot);

        tvStatus = new TextView(this);
        tvStatus.setText("未启动");
        tvStatus.setTextSize(17);
        tvStatus.setTextColor(C_TEXT);
        tvStatus.setPadding(dp(8), 0, 0, 0);
        statusRow.addView(tvStatus);

        statusCard.addView(statusRow);

        tvHint = new TextView(this);
        tvHint.setText("");
        tvHint.setTextSize(14);
        tvHint.setLineSpacing(dp(3), 1f);
        tvHint.setTextColor(C_GREEN);
        tvHint.setPadding(0, dp(10), 0, 0);
        tvHint.setVisibility(View.GONE);
        statusCard.addView(tvHint);

        root.addView(statusCard);

        // ---------- 操作按钮 ----------
        btnStart = new Button(this);
        btnStart.setText("启 动 服 务");
        btnStart.setTextSize(17);
        btnStart.setAllCaps(false);
        btnStart.setTextColor(0xFF05121C);
        btnStart.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnStart.setStateListAnimator(null);
        GradientDrawable startBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{C_GREEN, 0xFF5FD8FF, C_PURPLE});
        startBg.setCornerRadius(dp(16));
        btnStart.setBackground(startBg);
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onStartService(); }
        });
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        bLp.topMargin = dp(16);
        btnStart.setLayoutParams(bLp);
        root.addView(btnStart);

        btnStop = new Button(this);
        btnStop.setText("停止服务");
        btnStop.setTextSize(15);
        btnStop.setAllCaps(false);
        btnStop.setTextColor(0xFFC7CFE6);
        btnStop.setStateListAnimator(null);
        btnStop.setBackground(round(0xFF161E38, C_CARD_STROKE, 16));
        btnStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onStopService(); }
        });
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        sLp.topMargin = dp(10);
        btnStop.setLayoutParams(sLp);
        root.addView(btnStop);

        // ---------- 数据存储卡 ----------
        LinearLayout storeCard = new LinearLayout(this);
        storeCard.setOrientation(LinearLayout.VERTICAL);
        storeCard.setPadding(dp(18), dp(14), dp(18), dp(16));
        storeCard.setBackground(round(C_CARD, C_CARD_STROKE, 18));
        LinearLayout.LayoutParams stcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stcLp.topMargin = dp(16);
        storeCard.setLayoutParams(stcLp);

        LinearLayout storeHead = new LinearLayout(this);
        storeHead.setOrientation(LinearLayout.HORIZONTAL);
        storeHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView storeTitle = new TextView(this);
        storeTitle.setText("数据存储");
        storeTitle.setTextSize(14);
        storeTitle.setTextColor(C_TEXT);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        storeTitle.setLayoutParams(stLp);
        storeHead.addView(storeTitle);

        tvStorage = new TextView(this);
        tvStorage.setText("计算中…");
        tvStorage.setTextSize(13);
        tvStorage.setTypeface(Typeface.MONOSPACE);
        tvStorage.setTextColor(C_GREEN);
        storeHead.addView(tvStorage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        storeCard.addView(storeHead);

        TextView storeDesc = new TextView(this);
        storeDesc.setText("账号、团队、项目与上传文件全部保存在本机应用私有目录，不依赖外部路径。");
        storeDesc.setTextSize(11);
        storeDesc.setTextColor(C_SUB);
        storeDesc.setLineSpacing(dp(3), 1f);
        storeDesc.setPadding(0, dp(8), 0, 0);
        storeCard.addView(storeDesc);

        root.addView(storeCard);
        refreshStorageAsync();

        // ---------- 日志终端 ----------
        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        logCard.setBackground(round(0xFF070B18, 0xFF1E2740, 16));
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lcLp.topMargin = dp(16);
        logCard.setLayoutParams(lcLp);

        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView d1 = new TextView(this); d1.setText("●"); d1.setTextSize(8); d1.setTextColor(0xFFFF5F57);
        TextView d2 = new TextView(this); d2.setText("●"); d2.setTextSize(8); d2.setTextColor(0xFFFFBD2E); d2.setPadding(dp(5),0,0,0);
        TextView d3 = new TextView(this); d3.setText("●"); d3.setTextSize(8); d3.setTextColor(0xFF28C840); d3.setPadding(dp(5),0,0,0);
        logHead.addView(d1); logHead.addView(d2); logHead.addView(d3);
        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(11);
        logTitle.setTextColor(C_SUB);
        logTitle.setLetterSpacing(0.08f);
        logTitle.setPadding(dp(12), 0, 0, 0);
        logHead.addView(logTitle);
        logCard.addView(logHead);

        tvLog = new TextView(this);
        tvLog.setText("（暂无日志）");
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextColor(0xFF9FB4CC);
        tvLog.setLineSpacing(dp(2), 1f);
        tvLog.setPadding(0, dp(10), 0, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(tvLog);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(svLp);
        logCard.addView(sv);

        root.addView(logCard);

        setContentView(root);
    }

    private void setStatus(String text, int dotColor) {
        tvStatus.setText(text);
        tvStatusDot.setTextColor(dotColor);
    }

    // -------------------------------------------------------------- 逻辑

    /** Activity 自身是否在前台（决定轮询是否继续，与「服务是否运行」无关）。 */
    private boolean uiActive = false;
    private int lastLogIdx = 0;
    private int lastState = -1;

    @Override
    protected void onResume() {
        super.onResume();
        uiActive = true;
        // 回到前台立刻同步一次真实状态（Service 可能已启动完成或被系统杀掉）
        syncFromService();
        startLogPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiActive = false;
    }

    private void onStartService() {
        LogBus.clear();
        lastLogIdx = 0;          // 清空后必须重置游标，否则新日志永远读不到
        lastState = -1;
        tvLog.setText("（暂无日志）");
        log("正在启动服务...");
        Intent i = new Intent(this, PenpotService.class);
        i.setAction(PenpotService.ACTION_START);
        startService(i);
        setStatus("启动中...", C_AMBER);
        syncFromService();
        startLogPolling();
    }

    /** 每 700ms 轮询：先同步状态，再追加增量日志。只要界面在前台就一直跑。 */
    private void startLogPolling() {
        if (!uiActive) return;
        tvLog.postDelayed(new Runnable() {
            public void run() {
                if (!uiActive) return;
                syncFromService();
                String s = LogBus.snapshot(lastLogIdx);
                if (!s.isEmpty()) {
                    lastLogIdx = LogBus.size();
                    String cur = tvLog.getText().toString();
                    if (cur.equals("（暂无日志）")) cur = "";
                    tvLog.setText(cur.isEmpty() ? s.trim() : (cur + "\n" + s.trim()));
                }
                tvLog.postDelayed(this, 700);
            }
        }, 700);
    }

    /** 把 PenpotService 的真实状态映射到 UI（唯一状态源，避免局部变量漂移）。 */
    private void syncFromService() {
        int st = PenpotService.STATE;
        serviceRunning = PenpotService.isRunning();

        // 组件级检测（用于「运行中」时的局域网提示）
        String all = LogBus.snapshot(0);

        switch (st) {
            case PenpotService.ST_STARTING:
                setStatus("启动中...", C_AMBER);
                tvHint.setVisibility(View.GONE);
                break;
            case PenpotService.ST_RUNNING:
                setStatus("运行中", C_GREEN);
                tvHint.setText(LanIpHelper.buildHint(this, PORT));
                tvHint.setVisibility(View.VISIBLE);
                break;
            case PenpotService.ST_FAILED:
                setStatus("启动失败（见日志）", C_RED);
                tvHint.setVisibility(View.GONE);
                break;
            case PenpotService.ST_STOPPING:
                setStatus("停止中...", C_AMBER);
                tvHint.setVisibility(View.GONE);
                break;
            default: // ST_STOPPED
                setStatus("已停止", C_SUB);
                tvHint.setVisibility(View.GONE);
                break;
        }
        if (st != lastState) {
            lastState = st;
            updateButtons();
        }
    }

    private void onStopService() {
        log("正在停止服务...");
        Intent i = new Intent(this, PenpotService.class);
        i.setAction(PenpotService.ACTION_STOP);
        startService(i);
        setStatus("停止中...", C_AMBER);
        serviceRunning = false;
        updateButtons();
        // 不停止轮询：停止过程本身要写日志，且要等 Service 回报 ST_STOPPED
        startLogPolling();
    }

    /** 后台统计 App 数据目录占用，回填到「数据存储」卡。 */
    private void refreshStorageAsync() {
        final java.io.File root = getFilesDir();
        new Thread(new Runnable() {
            public void run() {
                final long bytes = dirSize(root);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (tvStorage != null) tvStorage.setText(human(bytes));
                    }
                });
            }
        }, "penpot-storage-size").start();
    }

    /** 统计真实占用：跳过符号链接（rootfs 里有 lib -> usr/lib 之类软链，跟随会重复计数甚至递归）。 */
    private static long dirSize(java.io.File f) {
        return dirSize(f, 0);
    }

    private static long dirSize(java.io.File f, int depth) {
        if (f == null || depth > 24) return 0;
        if (isSymlink(f)) return 0;                 // 软链不计、不进入
        if (!f.isDirectory()) return Math.max(0, f.length());
        long total = 0;
        java.io.File[] kids = f.listFiles();
        if (kids == null) return 0;
        for (java.io.File k : kids) total += dirSize(k, depth + 1);
        return total;
    }

    private static boolean isSymlink(java.io.File f) {
        try {
            android.system.StructStat st = android.system.Os.lstat(f.getAbsolutePath());
            return android.system.OsConstants.S_ISLNK(st.st_mode);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.0f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }

    private void updateButtons() {
        boolean canStart = !serviceRunning;
        boolean canStop = serviceRunning;
        btnStart.setEnabled(canStart);
        btnStart.setAlpha(canStart ? 1f : 0.45f);
        btnStop.setEnabled(canStop);
        btnStop.setAlpha(canStop ? 1f : 0.45f);
    }

    private void log(String msg) {
        String cur = tvLog.getText().toString();
        String line = java.text.DateFormat.getTimeInstance().format(new java.util.Date()) + "  " + msg;
        tvLog.setText(cur + (cur.equals("（暂无日志）") ? "" : "\n") + line);
    }
}
