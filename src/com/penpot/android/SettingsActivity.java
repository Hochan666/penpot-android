package com.penpot.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设置页：版本信息 + 检查更新 + 下载并更新。
 *
 * 更新绝不触碰用户数据（账号/团队/项目/文件）：
 *   只替换 apps/{fe,be,ex}，PG 数据目录与 objects/ 全程不动，且更新前先备份 PG 数据。
 */
public class SettingsActivity extends Activity {

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

    private TextView tvCurVer, tvLatestVer, tvStatus, tvLog;
    private Button btnCheck, btnUpdate;
    private ScrollView logScroll;

    private volatile String latestVersion = null;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshVersions();
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
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{C_BG_TOP, 0xFF0B1030, C_BG_BOTTOM}));

        // ---------- 顶部：返回 + 标题 ----------
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(24);
        back.setTextColor(C_TEXT);
        back.setPadding(0, 0, dp(14), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        head.addView(back);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(24);
        title.setTextColor(C_TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        head.addView(title);
        root.addView(head);

        TextView subtitle = new TextView(this);
        subtitle.setText("SETTINGS · PENPOT LOCAL SERVER");
        subtitle.setTextSize(10);
        subtitle.setTextColor(C_SUB);
        subtitle.setLetterSpacing(0.12f);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        // ---------- 版本卡 ----------
        LinearLayout verCard = new LinearLayout(this);
        verCard.setOrientation(LinearLayout.VERTICAL);
        verCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        verCard.setBackground(round(C_CARD, C_CARD_STROKE, 14f));

        TextView verTitle = new TextView(this);
        verTitle.setText("Penpot 版本");
        verTitle.setTextSize(13);
        verTitle.setTextColor(C_SUB);
        verTitle.setLetterSpacing(0.06f);
        verCard.addView(verTitle);

        LinearLayout verRow = new LinearLayout(this);
        verRow.setOrientation(LinearLayout.HORIZONTAL);
        verRow.setPadding(0, dp(10), 0, 0);

        tvCurVer = new TextView(this);
        tvCurVer.setTextSize(20);
        tvCurVer.setTextColor(C_TEXT);
        tvCurVer.setTypeface(Typeface.MONOSPACE);

        TextView arrow = new TextView(this);
        arrow.setText("   →   ");
        arrow.setTextSize(15);
        arrow.setTextColor(C_SUB);

        tvLatestVer = new TextView(this);
        tvLatestVer.setTextSize(20);
        tvLatestVer.setTextColor(C_SUB);
        tvLatestVer.setTypeface(Typeface.MONOSPACE);

        verRow.addView(tvCurVer);
        verRow.addView(arrow);
        verRow.addView(tvLatestVer);
        verCard.addView(verRow);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(C_SUB);
        tvStatus.setPadding(0, dp(10), 0, 0);
        verCard.addView(tvStatus);
        root.addView(verCard);

        // ---------- 按钮 ----------
        btnCheck = new Button(this);
        btnCheck.setText("检 查 更 新");
        btnCheck.setTextSize(15);
        btnCheck.setTextColor(0xFF06121A);
        btnCheck.setAllCaps(false);
        btnCheck.setStateListAnimator(null);
        btnCheck.setBackground(round(C_GREEN, 0, 12f));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp1.topMargin = dp(18);
        btnCheck.setLayoutParams(lp1);
        btnCheck.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { checkUpdate(); }
        });
        root.addView(btnCheck);

        btnUpdate = new Button(this);
        btnUpdate.setText("下 载 并 更 新");
        btnUpdate.setTextSize(15);
        btnUpdate.setTextColor(0xFF06121A);
        btnUpdate.setAllCaps(false);
        btnUpdate.setStateListAnimator(null);
        btnUpdate.setBackground(round(C_PURPLE, 0, 12f));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp2.topMargin = dp(12);
        btnUpdate.setLayoutParams(lp2);
        btnUpdate.setVisibility(View.GONE);
        btnUpdate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmUpdate(); }
        });
        root.addView(btnUpdate);

        // ---------- 数据安全说明 ----------
        LinearLayout note = new LinearLayout(this);
        note.setOrientation(LinearLayout.VERTICAL);
        note.setPadding(dp(16), dp(14), dp(16), dp(14));
        note.setBackground(round(0xFF0E1730, 0xFF1E3A5F, 12f));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = dp(18);
        note.setLayoutParams(lp3);

        TextView noteTitle = new TextView(this);
        noteTitle.setText("🔒 更新不会丢失你的数据");
        noteTitle.setTextSize(13);
        noteTitle.setTextColor(C_GREEN);
        note.addView(noteTitle);

        TextView noteBody = new TextView(this);
        noteBody.setText("升级只替换 Penpot 程序文件（前端/后端/导出器），"
                + "你的账号、团队、项目、文件全部保留；更新前还会自动备份一次数据库。");
        noteBody.setTextSize(11);
        noteBody.setTextColor(C_SUB);
        noteBody.setLineSpacing(dp(3), 1f);
        noteBody.setPadding(0, dp(8), 0, 0);
        note.addView(noteBody);
        root.addView(note);

        // ---------- 日志 ----------
        TextView logTitle = new TextView(this);
        logTitle.setText("更新日志");
        logTitle.setTextSize(12);
        logTitle.setTextColor(C_SUB);
        logTitle.setLetterSpacing(0.06f);
        logTitle.setPadding(0, dp(18), 0, dp(8));
        root.addView(logTitle);

        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        logScroll.setLayoutParams(lp4);
        logScroll.setBackground(round(0xFF080C18, C_CARD_STROKE, 12f));
        logScroll.setPadding(dp(12), dp(12), dp(12), dp(12));

        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTextColor(0xFF9EB0D4);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setText("等待操作...");
        logScroll.addView(tvLog);
        root.addView(logScroll);

        setContentView(root);
    }

    private void refreshVersions() {
        String cur = Updater.currentVersion(this);
        tvCurVer.setText("v" + cur);
        if (latestVersion == null) {
            tvLatestVer.setText("—");
            tvLatestVer.setTextColor(C_SUB);
        } else {
            tvLatestVer.setText("v" + latestVersion);
            int c = VersionChecker.cmp(latestVersion, cur);
            tvLatestVer.setTextColor(c > 0 ? C_AMBER : C_GREEN);
            if (c > 0) {
                tvStatus.setText("发现新版本 v" + latestVersion + "，可以更新");
                tvStatus.setTextColor(C_AMBER);
            } else {
                tvStatus.setText("已是最新版本");
                tvStatus.setTextColor(C_GREEN);
            }
        }
    }

    private void log(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                String cur = tvLog.getText().toString();
                if ("等待操作...".equals(cur)) cur = "";
                tvLog.setText(cur + msg + "\n");
                logScroll.post(new Runnable() {
                    public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    private void setBusy(final boolean b) {
        runOnUiThread(new Runnable() {
            public void run() {
                btnCheck.setEnabled(!b);
                btnUpdate.setEnabled(!b);
                btnCheck.setAlpha(b ? 0.5f : 1f);
                btnUpdate.setAlpha(b ? 0.5f : 1f);
            }
        });
    }

    // ------------------------------------------------------------------ 检查更新

    private void checkUpdate() {
        if (!busy.compareAndSet(false, true)) return;
        setBusy(true);
        log("正在检查官方最新版本...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    String latest = new VersionChecker(new VersionChecker.Callback() {
                        public void log(String m) { SettingsActivity.this.log("  " + m); }
                    }).fetchLatest();
                    final String cur = Updater.currentVersion(SettingsActivity.this);
                    final int c = VersionChecker.cmp(latest, cur);
                    latestVersion = latest;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            refreshVersions();
                            if (c > 0) {
                                btnUpdate.setVisibility(View.VISIBLE);
                                log("✅ 有新版本：" + cur + " → " + latest);
                                log("点击【下载并更新】升级（用户数据保留）");
                            } else {
                                btnUpdate.setVisibility(View.GONE);
                                log("✅ 已是最新版本 " + cur);
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            tvStatus.setText("检查失败：" + e.getMessage());
                            tvStatus.setTextColor(C_RED);
                        }
                    });
                    log("❌ 检查失败: " + e.getMessage());
                    log("请确认手机已联网（需要访问 registry-1.docker.io）");
                } finally {
                    busy.set(false);
                    setBusy(false);
                }
            }
        }, "penpot-check").start();
    }

    // ------------------------------------------------------------------ 下载并更新

    private void confirmUpdate() {
        final String cur = Updater.currentVersion(this);
        final String tgt = latestVersion;
        if (tgt == null) return;

        long free = freeBytes();
        String sizeHint = "约 2.1 GB";
        if (free < 3L * 1024 * 1024 * 1024) {
            new AlertDialog.Builder(this)
                    .setTitle("存储空间不足")
                    .setMessage("可用空间 " + (free / 1048576) + " MB，更新约需 3 GB。\n请清理后再试。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("更新到 " + tgt)
                .setMessage("将从 v" + cur + " 升级到 v" + tgt + "。\n\n"
                        + "· 下载量 " + sizeHint + "（断点续传）\n"
                        + "· 更新期间服务会停止约 1~3 分钟\n"
                        + "· 你的账号/团队/项目/文件全部保留\n"
                        + "· 更新前自动备份数据库\n\n"
                        + "期间请保持 App 在前台或不要强制关闭。\n"
                        + "可用空间: " + (free / 1024 / 1024 / 1024) + " GB")
                .setPositiveButton("开始更新", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { startUpdate(tgt); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startUpdate(final String target) {
        if (!busy.compareAndSet(false, true)) return;
        setBusy(true);
        btnUpdate.setVisibility(View.GONE);
        log("=== 开始更新到 v" + target + " ===");

        new Thread(new Runnable() {
            public void run() {
                try {
                    AssetInstaller inst = new AssetInstaller(SettingsActivity.this,
                            new AssetInstaller.Callback() {
                                public void log(String m) { SettingsActivity.this.log(m); }
                            });
                    if (!inst.isInstalled()) {
                        log("运行环境尚未安装，请先回主界面点【启动服务】完成首次安装。");
                        return;
                    }
                    Updater up = new Updater(SettingsActivity.this, inst, new Updater.Callback() {
                        public void log(String m) { SettingsActivity.this.log(m); }
                        public void stage(String s, int p) {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    tvStatus.setText(s + (p > 0 ? " " + p + "%" : ""));
                                    tvStatus.setTextColor(C_PURPLE);
                                }
                            });
                        }
                    });

                    boolean ok = up.update(target,
                            new Runnable() { public void run() { PenpotService.requestStop(SettingsActivity.this); } },
                            new Runnable() { public void run() { PenpotService.requestStart(SettingsActivity.this); } });

                    if (ok) {
                        log("🎉 更新完成，正在启动新版本服务...");
                        runOnUiThread(new Runnable() {
                            public void run() {
                                tvStatus.setText("已更新到 v" + target);
                                tvStatus.setTextColor(C_GREEN);
                            }
                        });
                    } else {
                        log("❌ 更新未完成（旧版本与用户数据均未受影响）");
                    }
                } catch (Throwable t) {
                    log("❌ 更新异常: " + t);
                } finally {
                    busy.set(false);
                    setBusy(false);
                    runOnUiThread(new Runnable() {
                        public void run() { refreshVersions(); }
                    });
                }
            }
        }, "penpot-update").start();
    }

    private long freeBytes() {
        try {
            File f = getFilesDir();
            return f.getUsableSpace();
        } catch (Throwable t) { return 0; }
    }
}
