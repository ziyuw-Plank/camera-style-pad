package io.github.ziyuw_plank.stylepad;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds xCrash tombstones written by the camera itself. From the 6.4 decompile:
 *  - xcrash.XCrash default logDir = filesDir + "/tombstones" (XCrash.java ~L499), static field XCrash.g holds the dir;
 *  - com.xiaomi.onetrack.CrashAnalysis inits XCrash with setLogDir(getCrashPath()) = filesDir + "/tombstone"
 *    (com.xiaomi.onetrack.util.m: c = "tombstone").
 * Files: *.native.xcrash / *.java.xcrash / *.anr.xcrash. onetrack may upload+delete them, so this is best-effort.
 */
final class Tomb {
    static File newest(Context c, ClassLoader cl) {
        List<File> dirs = new ArrayList<>();
        try { dirs.add(new File(c.getFilesDir(), "tombstones")); dirs.add(new File(c.getFilesDir(), "tombstone")); } catch (Throwable ignored) {}
        try {
            Object g = Class.forName("xcrash.XCrash", false, cl).getField("g").get(null);
            if (g instanceof String) dirs.add(new File((String) g));
        } catch (Throwable ignored) {}
        try { File e = c.getExternalFilesDir(null); if (e != null) { dirs.add(new File(e, "tombstones")); dirs.add(new File(e, "tombstone")); } } catch (Throwable ignored) {}
        try { dirs.add(c.getFilesDir()); } catch (Throwable ignored) {}
        File best = null;
        for (File d : dirs) best = scan(d, 0, best);
        return best;
    }

    private static File scan(File d, int depth, File best) {
        File[] fs;
        try { fs = d.listFiles(); } catch (Throwable t) { return best; }
        if (fs == null) return best;
        for (File f : fs) {
            if (f.isDirectory()) { if (depth < 2) best = scan(f, depth + 1, best); continue; }
            String n = f.getName();
            if (!(n.endsWith(".xcrash") || n.contains("tombstone"))) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    static String readAll(File f, int max) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null && sb.length() < max) sb.append(l).append('\n');
        } catch (Throwable t) { sb.append("read failed: ").append(t); }
        return sb.toString();
    }

    /** signal line, abort message, java exception lines, top 12 backtrace frames */
    static String summarize(String s) {
        StringBuilder out = new StringBuilder();
        String[] lines = s.split("\n");
        boolean inBt = false; int frames = 0;
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("Crash type:") || t.startsWith("pid:") || t.startsWith("signal ") || t.startsWith("Abort message")
                    || t.startsWith("java.") || t.startsWith("Caused by") || t.contains("Exception:") || t.contains("Error:")) {
                if (out.length() < 1500) out.append(t).append('\n');
            }
            if (t.startsWith("backtrace:")) { inBt = true; out.append("backtrace:\n"); continue; }
            if (inBt) {
                if (t.startsWith("#") && frames < 12) {
                    // "#00 pc 000000000004d6b8  /system/lib64/libc.so (abort+164)" -> keep lib + symbol
                    String[] p = t.split("\\s+", 4);
                    out.append(p.length >= 4 ? p[0] + " " + p[3] : t).append('\n');
                    frames++;
                } else if (t.isEmpty() || frames >= 12) inBt = false;
            }
        }
        return out.length() == 0 ? s.substring(0, Math.min(800, s.length())) : out.toString().trim();
    }
}
