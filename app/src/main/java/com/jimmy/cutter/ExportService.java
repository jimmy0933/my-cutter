package com.jimmy.cutter;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 前景服務：匯出在背景執行，鎖屏或切換 app 都不會中斷。
 * 顯示常駐進度通知，並持有 PARTIAL_WAKE_LOCK 讓 CPU 保持運作。
 */
public class ExportService extends Service {
    public interface Listener {
        void onProgress(float fraction);
        void onDone(String message);
        void onError(String message);
    }

    public static final class Job {
        public Uri uri;
        public VideoInfo info;
        public List<long[]> segments;
        public Set<Integer> audio;
    }

    private static final String CHANNEL = "export";
    private static final int NOTIF_RUN = 1, NOTIF_DONE = 2;

    /** Activity 在前景時註冊；不在時為 null。 */
    public static volatile Listener listener;
    public static volatile boolean running;
    public static volatile float lastProgress;
    /** 匯出結束時若沒有 listener，保留結果等 Activity 回來顯示。 */
    public static volatile String lastMessage;

    private static Job pending;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** @return false 表示已有匯出在進行。 */
    public static synchronized boolean start(Context c, Job job) {
        if (running) return false;
        running = true;
        lastProgress = 0;
        lastMessage = null;
        pending = job;
        c.startService(new Intent(c, ExportService.class));
        return true;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final Job job;
        synchronized (ExportService.class) { job = pending; pending = null; }
        if (job == null) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        AppLog.init(this);
        createChannel();
        startForeground(NOTIF_RUN, notification("匯出中", "準備中…", 0, true));
        new Thread(new Runnable() {
            @Override public void run() { work(job); }
        }, "export").start();
        return START_NOT_STICKY;
    }

    private void work(Job job) {
        PowerManager.WakeLock wl = ((PowerManager) getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "my-cutter:export");
        wl.acquire(6 * 60 * 60 * 1000L);
        boolean webm = job.info.containerMime.contains("webm");
        File tmp = new File(getCacheDir(), "out_" + System.currentTimeMillis() + (webm ? ".webm" : ".mp4"));
        String msg;
        boolean ok = false;
        try {
            AppLog.i("EXPORT", "服務開始 片段數=" + job.segments.size() + " 音軌=" + job.audio);
            final int[] lastPct = {-1};
            Cutter.Result r = Cutter.cut(this, job.uri, job.info, job.segments, job.audio, tmp, new Cutter.Progress() {
                @Override public void onProgress(final float f) {
                    lastProgress = f;
                    int pct = (int) (f * 100);
                    if (pct != lastPct[0]) {
                        lastPct[0] = pct;
                        postNotification(NOTIF_RUN, notification("匯出中", pct + "%", pct, true));
                        main.post(new Runnable() {
                            @Override public void run() {
                                Listener l = listener;
                                if (l != null) l.onProgress(f);
                            }
                        });
                    }
                }
            });
            String where = publish(this, tmp, webm);
            msg = String.format(Locale.US, "完成：%s\n原檔 %s → 輸出 %s（約省 %d%%）",
                    where, MainActivity.size(job.info.sizeBytes), MainActivity.size(r.outBytes),
                    job.info.sizeBytes > 0 ? (int) (100 - r.outBytes * 100.0 / job.info.sizeBytes) : 0);
            AppLog.i("EXPORT", msg);
            ok = true;
        } catch (Throwable e) {
            AppLog.e("EXPORT", "匯出失敗", e);
            msg = "匯出失敗：" + e + "\n（到右上角「設定」可複製日誌）";
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            if (wl.isHeld()) wl.release();
        }
        finish(ok, msg);
    }

    private void finish(final boolean ok, final String msg) {
        running = false;
        stopForeground(true);
        postNotification(NOTIF_DONE, notification(ok ? "匯出完成" : "匯出失敗", msg.split("\n")[0], 0, false));
        main.post(new Runnable() {
            @Override public void run() {
                Listener l = listener;
                if (l != null) {
                    lastMessage = null;
                    if (ok) l.onDone(msg); else l.onError(msg);
                } else {
                    lastMessage = msg;
                }
                stopSelf();
            }
        });
    }

    // ------------------------------------------------------------ notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try { // 編譯用的 android.jar 為 API 23，API 26 的類別以反射呼叫
            Class<?> cc = Class.forName("android.app.NotificationChannel");
            Object ch = cc.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, "匯出進度", 2 /* IMPORTANCE_LOW */);
            Object nm = getSystemService(Context.NOTIFICATION_SERVICE);
            nm.getClass().getMethod("createNotificationChannel", cc).invoke(nm, ch);
        } catch (Throwable t) {
            AppLog.e("EXPORT", "建立通知頻道失敗", t);
        }
    }

    private Notification notification(String title, String text, int pct, boolean ongoing) {
        Notification.Builder b = new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 26) {
            try { Notification.Builder.class.getMethod("setChannelId", String.class).invoke(b, CHANNEL); }
            catch (Throwable ignored) { }
        }
        b.setSmallIcon(R.drawable.ic_stat_cut).setContentTitle(title).setContentText(text)
                .setOngoing(ongoing).setOnlyAlertOnce(true).setAutoCancel(!ongoing);
        if (ongoing) b.setProgress(100, pct, false);
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setContentIntent(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* FLAG_IMMUTABLE */));
        return b.build();
    }

    private void postNotification(int id, Notification n) {
        try {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, n);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ output

    /** 把暫存檔複製到公開的 Movies/MyCutter（Android 10+ 走 MediaStore），回傳位置描述。 */
    static String publish(Context ctx, File tmp, boolean webm) throws Exception {
        String name = "cut_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date())
                + (webm ? ".webm" : ".mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, webm ? "video/webm" : "video/mp4");
            cv.put("relative_path", Environment.DIRECTORY_MOVIES + "/MyCutter");
            cv.put("is_pending", 1);
            Uri out = ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (out == null) throw new IllegalStateException("無法建立輸出檔");
            try (InputStream in = new FileInputStream(tmp); OutputStream os = ctx.getContentResolver().openOutputStream(out)) {
                copy(in, os);
            }
            ContentValues done = new ContentValues();
            done.put("is_pending", 0);
            ctx.getContentResolver().update(out, done, null, null);
            return "Movies/MyCutter/" + name;
        }
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) throw new IllegalStateException("無可用儲存空間");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dst = new File(dir, name);
        try (InputStream in = new FileInputStream(tmp); OutputStream os = new FileOutputStream(dst)) {
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
}
