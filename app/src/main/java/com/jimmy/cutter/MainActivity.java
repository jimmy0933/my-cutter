package com.jimmy.cutter;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
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
import android.widget.SeekBar;
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

public class MainActivity extends Activity {
    private static final int REQ_PICK = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<long[]> segments = new ArrayList<>();

    private Uri uri;
    private VideoInfo info;
    private long selStartUs = 0, selEndUs = 0;
    private boolean seeking;

    private VideoView video;
    private SeekBar seek;
    private TextView timeLabel, infoLabel, selLabel, status;
    private Button btnPlay, btnExport;
    private LinearLayout segList;
    private CheckBox keepAudio;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (video != null && info != null && !seeking) {
                int pos = video.getCurrentPosition();
                seek.setProgress(pos);
                updateTime(pos);
            }
            ui.postDelayed(this, 150);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        ui.post(ticker);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private Button button(String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        return bt;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void addWeighted(LinearLayout r, View v) {
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(24));
        scroll.addView(root);

        root.addView(button("選擇影片", v -> pick()));

        infoLabel = new TextView(this);
        infoLabel.setText("尚未選擇影片");
        infoLabel.setTextSize(12);
        root.addView(infoLabel);

        video = new VideoView(this);
        root.addView(video, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) { video.seekTo(p); updateTime(p); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { seeking = false; }
        });
        root.addView(seek);

        timeLabel = new TextView(this);
        timeLabel.setGravity(Gravity.CENTER);
        timeLabel.setText("00:00.000 / 00:00.000");
        root.addView(timeLabel);

        LinearLayout r1 = row();
        btnPlay = button("▶ 播放", v -> togglePlay());
        addWeighted(r1, btnPlay);
        addWeighted(r1, button("◀ 上個關鍵幀", v -> jumpKeyframe(false)));
        addWeighted(r1, button("下個關鍵幀 ▶", v -> jumpKeyframe(true)));
        root.addView(r1);

        LinearLayout r2 = row();
        addWeighted(r2, button("設為起點", v -> setStart()));
        addWeighted(r2, button("設為終點", v -> setEnd()));
        root.addView(r2);

        selLabel = new TextView(this);
        selLabel.setText("選取：—");
        root.addView(selLabel);

        root.addView(button("＋ 加入片段", v -> addSegment()));

        TextView h = new TextView(this);
        h.setText("保留的片段（依序合併）");
        h.setTextSize(14);
        h.setPadding(0, dp(12), 0, dp(4));
        root.addView(h);
        segList = new LinearLayout(this);
        segList.setOrientation(LinearLayout.VERTICAL);
        root.addView(segList);

        keepAudio = new CheckBox(this);
        keepAudio.setText("保留音訊（僅第一條音軌；其餘軌道與 metadata 會移除以瘦身）");
        keepAudio.setChecked(true);
        root.addView(keepAudio);

        btnExport = button("無損匯出", v -> export());
        btnExport.setEnabled(false);
        root.addView(btnExport);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, 0);
        status.setTextColor(Color.DKGRAY);
        root.addView(status);

        setContentView(scroll);
    }

    private void refreshSegments() {
        segList.removeAllViews();
        long sum = 0;
        for (int i = 0; i < segments.size(); i++) {
            final int idx = i;
            long[] s = segments.get(i);
            sum += s[1] - s[0];
            LinearLayout r = row();
            TextView t = new TextView(this);
            t.setText(String.format(Locale.US, "#%d  %s → %s  (%s)", i + 1, fmt(s[0]), fmt(s[1]), fmt(s[1] - s[0])));
            addWeighted(r, t);
            r.addView(button("刪除", v -> { segments.remove(idx); refreshSegments(); }));
            segList.addView(r);
        }
        btnExport.setEnabled(!segments.isEmpty());
        if (!segments.isEmpty()) {
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
        btnExport.setEnabled(false);
        status.setText("分析關鍵幀中…");
        infoLabel.setText("");
        video.setVideoURI(u);
        video.setOnPreparedListener((MediaPlayer mp) -> {
            seek.setMax(video.getDuration());
            btnPlay.setText("▶ 播放");
        });
        new Thread(() -> {
            try {
                final VideoInfo vi = VideoInfo.analyze(this, u);
                ui.post(() -> {
                    info = vi;
                    selStartUs = vi.keyframes.length > 0 ? vi.keyframes[0] : 0;
                    selEndUs = vi.durationUs;
                    infoLabel.setText(String.format(Locale.US, "%s｜%s｜%s｜關鍵幀 %d 個｜旋轉 %d°\n%s",
                            vi.containerMime, size(vi.sizeBytes), fmt(vi.durationUs),
                            vi.keyframes.length, vi.rotation, vi.description));
                    updateSel();
                    status.setText("起點會自動對齊到關鍵幀；終點可在任意幀。");
                });
            } catch (final Exception e) {
                ui.post(() -> status.setText("分析失敗：" + e.getMessage()));
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
        seek.setProgress(ms);
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
        selLabel.setText("選取：" + fmt(selStartUs) + " → " + fmt(selEndUs) + "（起點已對齊關鍵幀）");
    }

    private void updateTime(long ms) {
        timeLabel.setText(fmt(ms * 1000L) + " / " + fmt(info != null ? info.durationUs : 0));
    }

    private void export() {
        if (info == null || segments.isEmpty()) return;
        btnExport.setEnabled(false);
        final boolean audio = keepAudio.isChecked();
        final List<long[]> segs = new ArrayList<>(segments);
        final VideoInfo vi = info;
        final Uri src = uri;
        final boolean webm = vi.containerMime.contains("webm");
        status.setText("匯出中 0%");
        new Thread(() -> {
            File tmp = new File(getCacheDir(), "out_" + System.currentTimeMillis() + (webm ? ".webm" : ".mp4"));
            try {
                Cutter.Result r = Cutter.cut(this, src, vi, segs, audio, tmp,
                        f -> ui.post(() -> status.setText(String.format(Locale.US, "匯出中 %d%%", (int) (f * 100)))));
                final String where = publish(tmp, webm);
                final String msg = String.format(Locale.US, "完成：%s\n原檔 %s → 輸出 %s（約省 %d%%）",
                        where, size(vi.sizeBytes), size(r.outBytes),
                        vi.sizeBytes > 0 ? (int) (100 - r.outBytes * 100.0 / vi.sizeBytes) : 0);
                ui.post(() -> { status.setText(msg); btnExport.setEnabled(true); });
            } catch (final Exception e) {
                ui.post(() -> { status.setText("匯出失敗：" + e); btnExport.setEnabled(true); });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
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
