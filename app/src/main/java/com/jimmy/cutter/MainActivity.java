package com.jimmy.cutter;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements View.OnClickListener {
    private static final int REQ_PICK = 1;
    private static final int A_PICK = 1, A_PLAY = 2, A_PREV = 3, A_NEXT = 4, A_START = 5,
            A_END = 6, A_ADD = 7, A_EXPORT = 8, A_GALLERY = 9, A_CHOOSER = 10, A_LIBRARY = 11;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<long[]> segments = new ArrayList<>();

    private Uri uri;
    private VideoInfo info;
    private long selStartUs = 0, selEndUs = 0;
    private boolean seeking;

    private VideoView video;
    private TextView timeLabel, infoLabel, selLabel, status;
    private Button btnPlay, btnExport;
    private LinearLayout segList;
    private LinearLayout audioBox;
    private final List<CheckBox> audioChecks = new ArrayList<>();

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (video != null && info != null && !seeking) {
                int pos = video.getCurrentPosition();
                timeline.setPositionMs(pos);
                updateTime(pos);
            }
            ui.postDelayed(this, 150);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashReporter.install(this);
        try {
            buildUi();
        } catch (Throwable t) {
            CrashReporter.save(this, t);
            throw new RuntimeException(t);
        }
        refreshSegments();
        String last = CrashReporter.consume(this);
        if (last != null) status.setText("上次閃退原因（請截圖回報）：\n" + last);
        ui.post(ticker);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private static final int BG = 0xFF0F1115, CARD = 0xFF1A1D23, ACCENT = 0xFFFF7043,
            TEXT = 0xFFE8EAED, MUTED = 0xFF9AA0A6, TONAL = 0xFF2A2E36, GREEN = 0xFF42D392;

    private ProgressBar progress;
    private TimelineView timeline;
    private TextView emptyHint;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private Drawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null);
    }

    private TextView text(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        c.setBackground(g);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    /** style: 0 = 主要（橘色實心）, 1 = 次要（深灰）, 2 = 危險/文字 */
    private Button button(String label, int action, int style) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setAllCaps(false);
        bt.setTextSize(14);
        bt.setTypeface(Typeface.DEFAULT_BOLD);
        bt.setTextColor(style == 0 ? 0xFF1A1208 : style == 1 ? TEXT : 0xFFFF8A80);
        bt.setBackground(round(style == 0 ? ACCENT : TONAL, 12));
        bt.setMinHeight(0);
        bt.setMinimumHeight(0);
        bt.setMinWidth(0);
        bt.setMinimumWidth(0);
        bt.setPadding(dp(10), dp(10), dp(10), dp(10));
        bt.setStateListAnimator(null);
        bt.setId(action);
        bt.setOnClickListener(this);
        return bt;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void addWeighted(LinearLayout r, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        r.addView(v, lp);
    }

    @Override public void onClick(View v) {
        switch (v.getId()) {
            case A_LIBRARY: startActivityForResult(new Intent(this, VideoPickerActivity.class), REQ_PICK); break;
            case A_PICK: pick(); break;
            case A_GALLERY: pickGallery(); break;
            case A_CHOOSER: pickChooser(); break;
            case A_PLAY: togglePlay(); break;
            case A_PREV: jumpKeyframe(false); break;
            case A_NEXT: jumpKeyframe(true); break;
            case A_START: setStart(); break;
            case A_END: setEnd(); break;
            case A_ADD: addSegment(); break;
            case A_EXPORT: export(); break;
            default: break;
        }
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        // 標題
        TextView title = text("無損剪輯", 24, TEXT, true);
        header.addView(title);
        String ver = "";
        try { ver = " v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        header.addView(text("不重新編碼 · 畫質不變 · 檔案更小" + ver, 12, MUTED, false));

        // 選擇來源
        pickCard = card();
        pickCard.addView(text("選擇影片", 13, MUTED, false));
        LinearLayout pickRow = row();
        pickRow.setPadding(0, dp(8), 0, 0);
        addWeighted(pickRow, button("影片庫（推薦）", A_LIBRARY, 0));
        pickCard.addView(pickRow);
        LinearLayout pickRow2 = row();
        pickRow2.setPadding(0, dp(6), 0, 0);
        addWeighted(pickRow2, button("檔案", A_PICK, 1));
        addWeighted(pickRow2, button("相簿", A_GALLERY, 1));
        addWeighted(pickRow2, button("其他來源", A_CHOOSER, 1));
        pickCard.addView(pickRow2);
        infoLabel = text("尚未選擇影片", 12, MUTED, false);
        infoLabel.setPadding(0, dp(10), 0, 0);
        pickCard.addView(infoLabel);

        // 預覽 + 時間軸
        player = card();
        player.setPadding(dp(8), dp(8), dp(8), dp(8));
        frame = new FrameLayout(this);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(0xFF000000);
        fg.setCornerRadius(dp(12));
        frame.setBackground(fg);
        video = new VideoView(this);
        frame.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        emptyHint = text("預覽區", 14, MUTED, false);
        frame.addView(emptyHint, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        player.addView(frame, new LinearLayout.LayoutParams(-1, dp(220)));

        timeline = new TimelineView(this);
        timeline.setOnSeek(new TimelineView.OnSeek() {
            @Override public void onSeek(int ms, boolean finished) {
                seeking = !finished;
                video.seekTo(ms);
                updateTime(ms);
            }
        });
        player.addView(timeline, new LinearLayout.LayoutParams(-1, -2));

        timeLabel = text("00:00.000 / 00:00.000", 16, TEXT, true);
        timeLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timeLabel.setGravity(Gravity.CENTER);
        player.addView(timeLabel);

        LinearLayout r1 = row();
        r1.setPadding(0, dp(10), 0, 0);
        btnPlay = button("▶ 播放", A_PLAY, 0);
        addWeighted(r1, button("[ 設為起點", A_START, 1));
        addWeighted(r1, btnPlay);
        addWeighted(r1, button("設為終點 ]", A_END, 1));
        player.addView(r1);

        // 選取
        LinearLayout sel = selCard = card();
        sel.addView(text("剪輯範圍", 13, MUTED, false));
        selLabel = text("—", 18, TEXT, true);
        selLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        selLabel.setPadding(0, dp(4), 0, dp(8));
        sel.addView(selLabel);
        LinearLayout r2 = row();
        addWeighted(r2, button("◀ 上個關鍵幀", A_PREV, 1));
        addWeighted(r2, button("下個關鍵幀 ▶", A_NEXT, 1));
        sel.addView(r2);
        LinearLayout r3 = row();
        r3.setPadding(0, dp(8), 0, 0);
        addWeighted(r3, button("＋ 加入片段", A_ADD, 0));
        sel.addView(r3);
        sel.addView(text("起點自動對齊關鍵幀；終點可在任意幀。", 11, MUTED, false));

        // 片段列表
        segCard = card();
        segCard.addView(text("保留的片段（依序合併）", 13, MUTED, false));
        segList = new LinearLayout(this);
        segList.setOrientation(LinearLayout.VERTICAL);
        segCard.addView(segList);

        // 匯出
        LinearLayout out = outCard = card();
        out.addView(text("音軌（勾選要保留的；未勾選的會移除以瘦身）", 13, MUTED, false));
        audioBox = new LinearLayout(this);
        audioBox.setOrientation(LinearLayout.VERTICAL);
        out.addView(audioBox);
        showAudioTracks(null);
        btnExport = button("無損匯出", A_EXPORT, 0);
        btnExport.setTextSize(16);
        btnExport.setEnabled(false);
        btnExport.setAlpha(0.4f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        out.addView(btnExport, lp);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(6));
        pl.topMargin = dp(10);
        out.addView(progress, pl);
        status = text("", 13, TEXT, false);
        status.setPadding(0, dp(8), 0, 0);
        out.addView(status);

        applyLayout();
    }

    private LinearLayout header, pickCard, player, selCard, segCard, outCard;
    private FrameLayout frame;

    private void detach(View v) {
        ViewGroup p = (ViewGroup) v.getParent();
        if (p != null) p.removeView(v);
    }

    private LinearLayout.LayoutParams cardParams(int w, int h, float weight, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    /** 直向：單欄捲動。橫向：左側預覽＋時間軸，右側控制面板（可捲動）。 */
    private void applyLayout() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        // 重排版面會讓 VideoView 重建 surface，先記下播放狀態再還原
        int pos = video.getCurrentPosition();
        boolean playing = video.isPlaying();

        View[] all = {header, pickCard, player, selCard, segCard, outCard};
        for (View v : all) detach(v);

        if (land) {
            frame.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setBackgroundColor(BG);
            root.setPadding(dp(6), dp(6), dp(6), dp(6));

            player.setPadding(dp(6), dp(6), dp(6), dp(6));
            root.addView(player, cardParams(0, -1, 3f, 0));

            ScrollView right = new ScrollView(this);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, 0, dp(16));
            right.addView(col);
            col.addView(header, new LinearLayout.LayoutParams(-1, -2));
            col.addView(pickCard, cardParams(-1, -2, 0f, 10));
            col.addView(selCard, cardParams(-1, -2, 0f, 10));
            col.addView(segCard, cardParams(-1, -2, 0f, 10));
            col.addView(outCard, cardParams(-1, -2, 0f, 10));
            root.addView(right, new LinearLayout.LayoutParams(0, -1, 1.15f));
            setContentView(root);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            frame.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(220)));
            player.setPadding(dp(8), dp(8), dp(8), dp(8));
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            ScrollView scroll = new ScrollView(this);
            scroll.setBackgroundColor(BG);
            scroll.setFillViewport(true);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14), dp(16), dp(14), dp(28));
            scroll.addView(root);
            root.addView(header, new LinearLayout.LayoutParams(-1, -2));
            root.addView(pickCard, cardParams(-1, -2, 0f, 12));
            root.addView(player, cardParams(-1, -2, 0f, 12));
            root.addView(selCard, cardParams(-1, -2, 0f, 12));
            root.addView(segCard, cardParams(-1, -2, 0f, 12));
            root.addView(outCard, cardParams(-1, -2, 0f, 12));
            setContentView(scroll);
        }

        if (info != null && uri != null) {
            video.seekTo(pos);
            if (playing) video.start();
        }
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyLayout();
    }

    private void showAudioTracks(VideoInfo vi) {
        audioBox.removeAllViews();
        audioChecks.clear();
        if (vi == null || vi.audioTracks.isEmpty()) {
            TextView t = text(vi == null ? "選擇影片後顯示" : "此影片沒有音軌", 12, MUTED, false);
            t.setPadding(0, dp(4), 0, dp(4));
            audioBox.addView(t);
            return;
        }
        for (int i = 0; i < vi.audioTracks.size(); i++) {
            CheckBox cb = new CheckBox(this);
            cb.setText("音軌 " + (i + 1) + "：" + vi.audioDesc.get(i));
            cb.setTextColor(TEXT);
            cb.setTextSize(13);
            cb.setChecked(true);
            cb.setTag(vi.audioTracks.get(i));
            audioChecks.add(cb);
            audioBox.addView(cb);
        }
    }

    private void setExportEnabled(boolean on) {
        btnExport.setEnabled(on);
        btnExport.setAlpha(on ? 1f : 0.4f);
    }

    private void refreshSegments() {
        segList.removeAllViews();
        long sum = 0;
        if (segments.isEmpty()) {
            TextView t = text("還沒有片段，設定起點與終點後按「加入片段」。", 12, MUTED, false);
            t.setPadding(0, dp(8), 0, 0);
            segList.addView(t);
        }
        for (int i = 0; i < segments.size(); i++) {
            final int idx = i;
            long[] s = segments.get(i);
            sum += s[1] - s[0];
            LinearLayout r = row();
            r.setPadding(0, dp(8), 0, 0);
            TextView badge = text(String.valueOf(i + 1), 12, 0xFF08130E, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(round(GREEN, 12));
            r.addView(badge, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView t = text(fmt(s[0]) + " → " + fmt(s[1]) + "  (" + fmt(s[1] - s[0]) + ")", 13, TEXT, false);
            t.setTypeface(Typeface.MONOSPACE);
            t.setPadding(dp(10), 0, 0, 0);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            Button del = button("刪除", 100 + i, 2);
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { segments.remove(idx); refreshSegments(); }
            });
            r.addView(del);
            segList.addView(r);
        }
        timeline.setSegments(segments);
        setExportEnabled(!segments.isEmpty());
        if (!segments.isEmpty() && info != null) {
            long est = info.durationUs > 0 ? (long) (info.sizeBytes * (sum / (double) info.durationUs)) : 0;
            status.setText(String.format(Locale.US, "合計 %s，預估輸出約 %s（原檔 %s）",
                    fmt(sum), size(est), size(info.sizeBytes)));
        }
    }

    // ---------------------------------------------------------------- actions

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        startActivityForResult(i, REQ_PICK);
    }

    /** 從媒體庫（相簿）選；在 Samsung 安全資料夾內會顯示該空間自己的相簿。 */
    private void pickGallery() {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        i.setType("video/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            toast("找不到相簿應用程式，請改用其他來源");
        }
    }

    /** 讓系統列出所有可選來源（含安全資料夾內的「我的檔案」等）。 */
    private void pickChooser() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        startActivityForResult(Intent.createChooser(i, "選擇影片來源"), REQ_PICK);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            load(data.getData());
        }
    }

    private void load(final Uri u) {
        uri = u;
        info = null;
        segments.clear();
        refreshSegments();
        setExportEnabled(false);
        status.setText("分析關鍵幀中…");
        infoLabel.setText("");
        video.setVideoURI(u);
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                emptyHint.setVisibility(View.GONE);
                btnPlay.setText("▶ 播放");
            }
        });
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final VideoInfo vi = VideoInfo.analyze(MainActivity.this, u);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            info = vi;
                            showAudioTracks(vi);
                            timeline.setData(vi.durationUs, vi.keyframes);
                            selStartUs = vi.keyframes.length > 0 ? vi.keyframes[0] : 0;
                            selEndUs = vi.durationUs;
                            infoLabel.setText(String.format(Locale.US, "%s｜%s｜%s｜關鍵幀 %d 個｜旋轉 %d°\n%s",
                                    vi.containerMime, size(vi.sizeBytes), fmt(vi.durationUs),
                                    vi.keyframes.length, vi.rotation, vi.description));
                            updateSel();
                            status.setText("起點會自動對齊到關鍵幀；終點可在任意幀。");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { status.setText("分析失敗：" + e.getMessage()); }
                    });
                }
            }
        }).start();
    }

    private void togglePlay() {
        if (info == null) return;
        if (video.isPlaying()) { video.pause(); btnPlay.setText("▶ 播放"); }
        else { video.start(); btnPlay.setText("⏸ 暫停"); }
    }

    private void jumpKeyframe(boolean next) {
        if (info == null) return;
        long curUs = video.getCurrentPosition() * 1000L;
        long target = next ? info.nextKeyframe(curUs + 1000) : info.prevKeyframe(curUs - 1000);
        if (target < 0) { toast(next ? "已是最後一個關鍵幀" : "已是第一個關鍵幀"); return; }
        video.pause();
        btnPlay.setText("▶ 播放");
        int ms = (int) ((target + 999) / 1000);
        video.seekTo(ms);
        timeline.setPositionMs(ms);
        updateTime(ms);
    }

    private void setStart() {
        if (info == null) return;
        long pos = video.getCurrentPosition() * 1000L;
        selStartUs = info.snapBack(pos);
        if (selEndUs <= selStartUs) selEndUs = info.durationUs;
        updateSel();
    }

    private void setEnd() {
        if (info == null) return;
        long pos = video.getCurrentPosition() * 1000L;
        if (pos <= selStartUs) { toast("終點必須在起點之後"); return; }
        selEndUs = pos;
        updateSel();
    }

    private void addSegment() {
        if (info == null) return;
        if (selEndUs <= selStartUs) { toast("終點必須在起點之後"); return; }
        segments.add(new long[]{selStartUs, selEndUs});
        refreshSegments();
    }

    private void updateSel() {
        selLabel.setText(fmt(selStartUs) + " → " + fmt(selEndUs));
        timeline.setSelection(selStartUs, selEndUs);
    }

    private void updateTime(long ms) {
        timeLabel.setText(fmt(ms * 1000L) + " / " + fmt(info != null ? info.durationUs : 0));
    }

    private void export() {
        if (info == null || segments.isEmpty()) return;
        setExportEnabled(false);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        final java.util.Set<Integer> audio = new java.util.HashSet<>();
        for (CheckBox cb : audioChecks) if (cb.isChecked()) audio.add((Integer) cb.getTag());
        final List<long[]> segs = new ArrayList<>(segments);
        final VideoInfo vi = info;
        final Uri src = uri;
        final boolean webm = vi.containerMime.contains("webm");
        status.setText("匯出中 0%");
        new Thread(new Runnable() {
            @Override public void run() {
                File tmp = new File(getCacheDir(), "out_" + System.currentTimeMillis() + (webm ? ".webm" : ".mp4"));
                try {
                    final Cutter.Result r = Cutter.cut(MainActivity.this, src, vi, segs, audio, tmp,
                            new Cutter.Progress() {
                                @Override public void onProgress(final float f) {
                                    ui.post(new Runnable() {
                                        @Override public void run() {
                                            progress.setProgress((int) (f * 100));
                                            status.setText(String.format(Locale.US, "匯出中 %d%%", (int) (f * 100)));
                                        }
                                    });
                                }
                            });
                    final String where = publish(tmp, webm);
                    final String msg = String.format(Locale.US, "完成：%s\n原檔 %s → 輸出 %s（約省 %d%%）",
                            where, size(vi.sizeBytes), size(r.outBytes),
                            vi.sizeBytes > 0 ? (int) (100 - r.outBytes * 100.0 / vi.sizeBytes) : 0);
                    ui.post(new Runnable() {
                        @Override public void run() { progress.setVisibility(View.GONE); status.setText(msg); setExportEnabled(true); }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { progress.setVisibility(View.GONE); status.setText("匯出失敗：" + e); setExportEnabled(true); }
                    });
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }).start();
    }

    /** 把暫存檔複製到公開的 Movies/MyCutter（Android 10+ 走 MediaStore），回傳位置描述。 */
    private String publish(File tmp, boolean webm) throws Exception {
        String name = "cut_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date())
                + (webm ? ".webm" : ".mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, webm ? "video/webm" : "video/mp4");
            cv.put("relative_path", Environment.DIRECTORY_MOVIES + "/MyCutter");
            cv.put("is_pending", 1);
            Uri out = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (out == null) throw new IllegalStateException("無法建立輸出檔");
            try (InputStream in = new FileInputStream(tmp); OutputStream os = getContentResolver().openOutputStream(out)) {
                copy(in, os);
            }
            ContentValues done = new ContentValues();
            done.put("is_pending", 0);
            getContentResolver().update(out, done, null, null);
            return "Movies/MyCutter/" + name;
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) throw new IllegalStateException("無可用儲存空間");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dst = new File(dir, name);
        try (InputStream in = new FileInputStream(tmp); OutputStream os = new java.io.FileOutputStream(dst)) {
            copy(in, os);
        }
        return dst.getAbsolutePath();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] b = new byte[256 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.flush();
    }

    // ---------------------------------------------------------------- helpers

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    static String fmt(long us) {
        long ms = Math.max(0, us) / 1000;
        long m = ms / 60000, s = (ms / 1000) % 60, r = ms % 1000;
        return String.format(Locale.US, "%02d:%02d.%03d", m, s, r);
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }
}
