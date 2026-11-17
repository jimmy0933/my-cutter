package com.jimmy.cutter;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 簡易檔案日誌：寫在 app 私有目錄，可在「設定」頁複製，方便排查問題。 */
public final class AppLog {
    private static final String TAG = "MyCutter";
    private static final long MAX_BYTES = 240 * 1024, KEEP_BYTES = 120 * 1024;
    private static final Object LOCK = new Object();
    private static File file;

    /** 目前載入的影片摘要，會顯示在日誌標頭。 */
    public static volatile String videoSummary = "";

    private AppLog() {}

    public static void init(Context c) {
        synchronized (LOCK) {
            if (file == null) file = new File(c.getApplicationContext().getFilesDir(), "app.log");
        }
    }

    public static void i(String tag, String msg) { write("I", tag, msg); }

    public static void e(String tag, String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        write("E", tag, msg + (t != null ? "\n" + sw : ""));
    }

    private static void write(String level, String tag, String msg) {
        Log.println("E".equals(level) ? Log.ERROR : Log.INFO, TAG, tag + " " + msg);
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + level + "/" + tag + " " + msg + "\n";
        synchronized (LOCK) {
            if (file == null) return;
            try {
                if (file.length() > MAX_BYTES) trim();
                FileOutputStream os = new FileOutputStream(file, true);
                os.write(line.getBytes("UTF-8"));
                os.close();
            } catch (Throwable ignored) { }
        }
    }

    private static void trim() throws Exception {
        String all = readAll();
        int cut = Math.max(0, all.length() - (int) KEEP_BYTES);
        int nl = all.indexOf('\n', cut);
        String tail = nl >= 0 ? all.substring(nl + 1) : all.substring(cut);
        FileOutputStream os = new FileOutputStream(file, false);
        os.write(tail.getBytes("UTF-8"));
        os.close();
    }

    private static String readAll() {
        synchronized (LOCK) {
            if (file == null || !file.exists()) return "";
            try {
                FileInputStream in = new FileInputStream(file);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[32 * 1024];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                return bo.toString("UTF-8");
            } catch (Throwable t) {
                return "";
            }
        }
    }

    /** 完整日誌（時間順）。 */
    public static String read() { return readAll(); }

    /** 最新的在最上面，並限制長度，用於畫面顯示。 */
    public static String readNewestFirst(int maxChars) {
        String[] lines = readAll().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - 1; i >= 0 && sb.length() < maxChars; i--) {
            if (!lines[i].isEmpty()) sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    public static String header(Context c) {
        String ver = "?";
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            ver = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) { }
        return "my-cutter " + ver + "\n裝置：" + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + (videoSummary.isEmpty() ? "" : "\n影片：" + videoSummary);
    }
}
