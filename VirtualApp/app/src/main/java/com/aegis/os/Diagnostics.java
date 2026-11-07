package com.aegis.os;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * On-device install/engine diagnostics.
 *
 * Field testers have no adb.  Every install attempt (and any engine failure
 * the UI catches) appends a timestamped line to a private log file which can
 * be read back in Aegis OS settings and copy/pasted into a bug report.
 */
public final class Diagnostics {

    private static final String FILE_NAME = "aegis_diagnostics.log";
    private static final long MAX_BYTES = 96L * 1024; // trim from the head

    private Diagnostics() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    public static synchronized void log(Context ctx, String message) {
        append(ctx, stamp() + " " + message);
    }

    public static synchronized void log(Context ctx, String message, Throwable t) {
        StringBuilder sb = new StringBuilder(stamp()).append(' ').append(message);
        if (t != null) {
            sb.append(" -- ").append(t.getClass().getName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            StackTraceElement[] st = t.getStackTrace();
            int frames = Math.min(st == null ? 0 : st.length, 8);
            for (int i = 0; i < frames; i++) {
                sb.append("\n    at ").append(st[i]);
            }
        }
        append(ctx, sb.toString());
    }

    /** Returns up to {@code maxLines} most recent lines, oldest first. */
    public static synchronized String readLast(Context ctx, int maxLines) {
        File f = file(ctx);
        if (!f.exists()) {
            return "(no diagnostics yet -- try importing or cloning an app)";
        }
        java.util.ArrayDeque<String> deque = new java.util.ArrayDeque<>(maxLines);
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                if (deque.size() == maxLines) {
                    deque.pollFirst();
                }
                deque.addLast(line);
            }
        } catch (Throwable ignored) {
            return "(diagnostics unreadable)";
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable ignored) {}
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : deque) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear(Context ctx) {
        File f = file(ctx);
        if (f.exists()) {
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static void append(Context ctx, String block) {
        File f = file(ctx);
        try {
            if (f.length() > MAX_BYTES) {
                trim(f);
            }
            BufferedWriter w = new BufferedWriter(new FileWriter(f, true));
            w.write(block);
            w.write('\n');
            w.close();
        } catch (Throwable ignored) {
            // diagnostics must never break the app
        }
    }

    /** Keeps the most recent half of the file. */
    private static void trim(File f) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            StringBuilder keep = new StringBuilder();
            long total = 0;
            String line;
            while ((line = r.readLine()) != null) {
                total += line.length() + 1;
            }
            r.close();
            long skip = total / 2;
            r = new BufferedReader(new FileReader(f));
            long pos = 0;
            while (pos < skip && (line = r.readLine()) != null) {
                pos += line.length() + 1;
            }
            while ((line = r.readLine()) != null) {
                keep.append(line).append('\n');
            }
            r.close();
            BufferedWriter w = new BufferedWriter(new FileWriter(f, false));
            w.write(keep.toString());
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
