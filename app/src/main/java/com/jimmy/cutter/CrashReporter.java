package com.jimmy.cutter;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/** 把未捕捉的例外寫入檔案，下次開啟 app 時顯示在畫面上，方便回報。 */
final class CrashReporter {
    private static final String FILE = "last_crash.txt";

    private CrashReporter() {}

    static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                save(ctx, e);
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    static void save(Context ctx, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            FileOutputStream os = new FileOutputStream(new File(ctx.getFilesDir(), FILE));
            os.write(sw.toString().getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) { }
    }

    static String consume(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) Math.min(f.length(), 4000)];
            int n = in.read(b);
            in.close();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return new String(b, 0, Math.max(n, 0), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
