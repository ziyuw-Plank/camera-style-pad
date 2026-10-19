package io.github.ziyuw_plank.stylepad;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/**
 * v0.3: overlay is a child of the activity's android.R.id.content (added via decor.post, once), uses only plain View/TextView/LinearLayout drawn in code (no SeekBar/CheckBox/Button, no theme
 * attributes, no module resources), and every UI callback is wrapped in try/catch.
 */
final class Overlay {
    static final String PREFS = "cam_style_pad";
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static volatile Context appCtx;
    private static long lastToast;


    // ---------- error reporting ----------
    static void err(String where, Throwable t) {
        Hook.log(where + ": " + android.util.Log.getStackTraceString(t));
        final String msg = "StylePad " + where + ": " + t;
        MAIN.post(() -> {
            try {
                long now = System.currentTimeMillis();
                if (appCtx == null || now - lastToast < 3000) return;
                lastToast = now;
                Toast.makeText(appCtx, msg.length() > 200 ? msg.substring(0, 200) : msg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        });
    }

    static void loadPrefs(Context c) {
        try {
            appCtx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (sp.getInt("schema", 0) < 6) {
                sp.edit().putFloat("x", 0f).putFloat("y", 0f).putFloat("sat", 1f).putInt("mode", 3).putInt("stage", 3)
                        .remove("pending").putInt("schema", 6).commit();
                Hook.log("v0.6 first launch: pad reset to neutral, mode C");
            }
            Style.set(sp.getFloat("x", 0f), sp.getFloat("y", 0f), sp.getFloat("sat", 1f));
            Style.capture = sp.getBoolean("capture", false);
        } catch (Throwable t) { err("loadPrefs", t); }
    }

    static void save() {
        try {
            if (appCtx == null) return;
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putFloat("x", Style.x).putFloat("y", Style.y).putFloat("sat", Style.sat)
                    .putBoolean("capture", Style.capture).apply();
        } catch (Throwable t) { err("save", t); }
    }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    // ---------- crash capture: wrap default handler, write trace, then delegate (never swallow) ----------
    static final String CRASH_FILE = "cam_style_pad_crash.txt";
    static volatile Thread.UncaughtExceptionHandler ours;
    static void installCrashHandler(final Context c) {
        try {
            final Thread.UncaughtExceptionHandler orig = Thread.getDefaultUncaughtExceptionHandler();
            if (orig != null && orig == ours) return;
            Thread.setDefaultUncaughtExceptionHandler(ours = (th, ex) -> {
                try {
                    String trace = "thread=" + th.getName() + "\n" + android.util.Log.getStackTraceString(ex);
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(c.getFilesDir(), CRASH_FILE))) {
                        fo.write(trace.getBytes("UTF-8"));
                        fo.getFD().sync();
                    }
                    Hook.log("UNCAUGHT " + trace);
                } catch (Throwable ignored) {}
                if (orig != null) orig.uncaughtException(th, ex);
            });
            Hook.log("crash handler installed (wrapping " + (orig == null ? "null" : orig.getClass().getName()) + ")");
        } catch (Throwable t) { Hook.log("installCrashHandler: " + t); }
    }

    /** exception line + top 3 frames + up to 3 frames from our package */
    static String summarize(String trace) {
        StringBuilder sb = new StringBuilder();
        String[] lines = trace.split("\n");
        int frames = 0, ours = 0;
        for (String l : lines) {
            String t = l.trim();
            boolean isFrame = t.startsWith("at ");
            if (!isFrame && sb.length() == 0 && !t.startsWith("thread=")) { sb.append(t).append('\n'); continue; }
            if (t.startsWith("thread=")) { sb.append(t).append('\n'); continue; }
            if (t.startsWith("Caused by")) { sb.append(t).append('\n'); frames = 0; continue; }
            if (isFrame && (frames < 3 || (t.contains("io.github.ziyuw_plank") && ours < 3))) {
                sb.append(t).append('\n');
                if (t.contains("io.github.ziyuw_plank")) ours++;
                frames++;
            }
        }
        return sb.toString().trim();
    }

    // ---------- safe mode: a flag is committed before each risky UI step and cleared once it survived ----------
    static volatile boolean safeMode;
    static volatile String safeMsg;
    static volatile boolean infoOnly; // show info box but continue normally
    static void checkSafeMode(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String pending = sp.getString("pending", null);
            java.io.File cf = new java.io.File(c.getFilesDir(), CRASH_FILE);
            String crash = null;
            if (cf.exists()) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(cf)) {
                    byte[] b = new byte[(int) Math.min(cf.length(), 64 * 1024)];
                    int n = in.read(b);
                    crash = new String(b, 0, Math.max(n, 0), "UTF-8");
                }
                cf.renameTo(new java.io.File(c.getFilesDir(), CRASH_FILE + ".last"));
            }
            String tombInfo = null;
            try {
                java.io.File tf = Tomb.newest(c, Hook.hostCl);
                long seen = sp.getLong("lastTomb", 0L);
                if (tf != null && tf.lastModified() > seen) {
                    sp.edit().putLong("lastTomb", tf.lastModified()).commit();
                    String full = Tomb.readAll(tf, 200 * 1024);
                    Hook.log("xCrash tombstone " + tf.getAbsolutePath() + " (" + new java.util.Date(tf.lastModified()) + "):\n" + full);
                    tombInfo = "xCrash 记录 " + tf.getName() + ":\n" + Tomb.summarize(full);
                } else Hook.log("no new xCrash tombstone (newest=" + (tf == null ? "none" : tf.getAbsolutePath()) + ")");
            } catch (Throwable t) { Hook.log("tombstone scan: " + t); }
            if (tombInfo != null) {
                safeMsg = (pending != null ? "上次步骤=" + pending + "\n" : "") + tombInfo;
            } else if (crash != null) {
                Hook.log("previous crash trace:\n" + crash);
                safeMsg = "上次崩溃 (Java)" + (pending != null ? " 步骤=" + pending : "") + ":\n" + summarize(crash);
            } else if (pending != null) {
                safeMsg = "上次在步骤 " + pending + " 时进程死亡，但没有 Java 异常记录 → 很可能是原生崩溃 (SIGSEGV 等) 或被系统杀死";
            }
            if ("lut-push".equals(pending)) {
                sp.edit().putInt("mode", 2).commit();
                Hook.MODE = 2;
                safeMsg = "上次推送 LUT 后进程死亡 → 已自动回退到模式 B（只记录不推送）。长按◐可切回 C。\n" + (safeMsg == null ? "" : safeMsg);
                Hook.log("lut-push died last launch -> fell back to mode B");
            }
            if (pending != null || crash != null || tombInfo != null) {
                safeMode = pending != null && !"lut-push".equals(pending);   // overlay disabled for this launch only if a UI step was pending
                if (!safeMode) infoOnly = true;
                sp.edit().remove("pending").commit();
                Hook.log("SAFE/CRASH INFO: " + safeMsg);
                final String m = "StylePad " + (safeMode ? "安全模式\n" : "") + safeMsg;
                MAIN.postDelayed(() -> { try { if (appCtx != null) Toast.makeText(appCtx, m.length() > 400 ? m.substring(0, 400) : m, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {} }, 1500);
            }
        } catch (Throwable t) { Hook.log("checkSafeMode: " + t); }
    }
    /** called (on the GL thread) right before the first composite is handed to the renderer this launch */
    static void markPush() {
        mark("lut-push");
        MAIN.postDelayed(() -> {
            try { if (appCtx != null) { SharedPreferences sp = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); if ("lut-push".equals(sp.getString("pending", null))) sp.edit().remove("pending").commit(); } } catch (Throwable ignored) {}
        }, 4000);
    }
    static void mark(String op) {
        try { if (appCtx != null) appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pending", op).commit(); } catch (Throwable ignored) {}
    }
    static void clearLater(View v) {
        try {
            v.postDelayed(() -> {
                try { if (appCtx != null) appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pending").commit(); } catch (Throwable ignored) {}
            }, 2500);
        } catch (Throwable ignored) {}
    }

    // ---------- diagnostic stage (long-press ◐): 1 = plain box, 2 = pad only, 3 = full panel ----------
    static int stage() { try { return appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("stage", 3); } catch (Throwable t) { return 3; } }

    // ---------- attach as child of the window's DecorView (a FrameLayout) with our own FrameLayout root ----------
    static final String TAG_VIEW = "cam_style_pad_root";
    private static FrameLayout root;
    private static View panelView;
    private static TextView gear;
    static String gearText() { return "\u2699 " + Hook.modeName() + stage(); }

    static void onResumed(final Activity a) {
        // the camera may install its own handler after Application.onCreate -> re-wrap if ours was replaced
        if (Thread.getDefaultUncaughtExceptionHandler() != ours && appCtx != null) installCrashHandler(appCtx);
        try {
            final View decor = a.getWindow().getDecorView();
            decor.post(() -> {
                try { attach(a); } catch (Throwable t) { err("attach", t); }
            });
        } catch (Throwable t) { err("onResumed", t); }
    }

    private static ViewGroup host(Activity a) {
        // v0.4 used DecorView and died ~2s after attach; v0.1/v0.3 used content and launched fine -> content
        return a.findViewById(android.R.id.content);
    }

    private static void attach(Activity a) {
        if (a.isFinishing() || a.isDestroyed()) return;
        ViewGroup h = host(a);
        if (h == null) { Hook.log("no host view"); return; }
        if (h.findViewWithTag(TAG_VIEW) != null || h.findViewWithTag(TAG_VIEW + "_main") != null) return;   // already added
        FrameLayout r = new FrameLayout(a);
        r.setTag(TAG_VIEW);
        r.setFitsSystemWindows(false);
        if (safeMode || (infoOnly && safeMsg != null)) {
            // safe mode: only show the crash summary box, no ◐ / panel
            if (safeMsg == null) return;
            TextView box = text(a, safeMsg + "\n\n模式=" + Hook.modeName() + "  (点此关闭)", 10, Color.WHITE);
            box.setBackgroundColor(0xE0000000);
            int p = dp(a, 10);
            box.setPadding(p, p, p, p);
            box.setClickable(true);
            final FrameLayout infoRoot = r;
            box.setOnClickListener(v -> { try { ViewGroup pr = (ViewGroup) infoRoot.getParent(); if (pr != null) pr.removeView(infoRoot); } catch (Throwable e) { err("dismiss", e); } });
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            blp.leftMargin = blp.rightMargin = dp(a, 16);
            r.addView(box, blp);
            h.addView(r, layoutFor(h));
            Hook.log("info box attached (safeMode=" + safeMode + ")");
            if (safeMode) return;
            infoOnly = false;
            r = new FrameLayout(a);
            r.setTag(TAG_VIEW + "_main");
        }
        mark("attach");
        TextView t = new TextView(a);
        t.setText("\u25D0"); t.setTextSize(20); t.setTextColor(Color.WHITE); t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(0x80000000);
        t.setBackground(bg);
        t.setClickable(true);
        t.setOnClickListener(v -> { try { togglePanel(v.getContext()); } catch (Throwable e) { err("toggle", e); } });
        t.setOnLongClickListener(v -> {
            try {
                int m = Hook.mode() % 3 + 1;
                appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("mode", m).commit();
                Hook.MODE = m;
                if (gear != null) gear.setText(gearText());
                Toast.makeText(appCtx, "StylePad 模式 " + Hook.modeName() + (m == 1 ? "：仅UI，滤镜钩子全部直通" : m == 2 ? "：钩子记录但不推LUT" : "：完整") + "\n强制停止相机后生效", Toast.LENGTH_LONG).show();
                Hook.log("mode -> " + Hook.modeName());
            } catch (Throwable e) { err("mode", e); }
            return true;
        });
        final TextView g = chip(a, gearText());
        g.setOnClickListener(v -> {
            try {
                int st = stage() % 3 + 1;
                appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("stage", st).commit();
                if (panelView != null) { root.removeView(panelView); panelView = null; }
                g.setText(gearText());
                Toast.makeText(appCtx, "StylePad 诊断阶段 " + st + (st == 1 ? "：纯色方块" : st == 2 ? "：仅调节板" : "：完整面板"), Toast.LENGTH_SHORT).show();
                Hook.log("diagnostic stage -> " + st);
            } catch (Throwable e) { err("stage", e); }
        });
        gear = g;
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(a, 44), dp(a, 44), Gravity.END | Gravity.CENTER_VERTICAL);
        tlp.setMarginEnd(dp(a, 8));
        r.addView(t, tlp);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        glp.setMarginEnd(dp(a, 8));
        glp.topMargin = dp(a, 110);
        r.addView(g, glp);
        h.addView(r, layoutFor(h));
        root = r; panelView = null;
        clearLater(r);
        Hook.log("overlay attached to " + a.getClass().getName() + " host=" + h.getClass().getName());
    }

    /** match_parent params of the exact type the host expects (avoids ClassCastException in host onLayout) */
    private static ViewGroup.LayoutParams layoutFor(ViewGroup h) {
        if (h instanceof FrameLayout) return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        android.util.AttributeSet none = null;
        ViewGroup.LayoutParams p;
        try { p = h.generateLayoutParams(none); } catch (Throwable t) { p = null; }
        if (p == null) p = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        p.width = ViewGroup.LayoutParams.MATCH_PARENT; p.height = ViewGroup.LayoutParams.MATCH_PARENT;
        return p;
    }

    private static void togglePanel(Context c) {
        FrameLayout r = root;
        if (r == null) return;
        if (panelView != null) {
            panelView.setVisibility(panelView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return;
        }
        int st = stage();
        mark("panel-stage" + st);
        Hook.log("building panel, stage " + st);
        View p;
        if (st == 1) {
            p = new View(c);
            p.setBackgroundColor(0xC0FF4081);
            p.setClickable(true);
            p.setOnClickListener(v -> { try { togglePanel(v.getContext()); } catch (Throwable e) { err("close", e); } });
            r.addView(p, new FrameLayout.LayoutParams(dp(c, 160), dp(c, 160), Gravity.CENTER));
        } else if (st == 2) {
            PadView pv = new PadView(c);
            pv.listener = (px, py) -> Style.set(px, py, Style.sat);
            p = pv;
            r.addView(p, new FrameLayout.LayoutParams(dp(c, 220), dp(c, 220), Gravity.CENTER));
        } else {
            p = buildPanel(c);
            p.setClickable(true); // swallow touches so the camera doesn't tap-to-focus underneath
            r.addView(p, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        panelView = p;
        clearLater(p);
        Hook.log("panel shown, stage " + st);
    }

    // ---------- panel ----------
    private static View buildPanel(final Context c) {
        final LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 12);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xC0000000); bg.setCornerRadius(dp(c, 18));
        panel.setBackground(bg);

        final TextView label = text(c, "", 12, Color.WHITE);
        final PadView padView = new PadView(c);
        final SliderView slider = new SliderView(c);
        final Runnable refresh = () -> label.setText(String.format(Locale.US, "色彩 %+.2f   色调 %+.2f   强度 %.2f", Style.x, Style.y, Style.sat));
        padView.listener = (px, py) -> { Style.set(px, py, Style.sat); refresh.run(); };
        slider.listener = v -> { Style.set(Style.x, Style.y, v); refresh.run(); };

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView reset = chip(c, "重置");
        reset.setOnClickListener(v -> { try { Style.set(0f, 0f, 1f); padView.invalidate(); slider.invalidate(); refresh.run(); save(); } catch (Throwable e) { err("reset", e); } });
        final TextView cap = chip(c, capText());
        cap.setOnClickListener(v -> { try { Style.capture = !Style.capture; cap.setText(capText()); save(); } catch (Throwable e) { err("capture", e); } });
        TextView close = chip(c, "关闭");
        close.setOnClickListener(v -> { try { togglePanel(v.getContext()); } catch (Throwable e) { err("close", e); } });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMarginEnd(dp(c, 8));
        row.addView(reset, clp); row.addView(close, new LinearLayout.LayoutParams(clp));

        int side = dp(c, 220);
        panel.addView(label);
        panel.addView(padView, new LinearLayout.LayoutParams(side, side));
        panel.addView(text(c, "色彩强度", 12, Color.WHITE));
        panel.addView(slider, new LinearLayout.LayoutParams(side, dp(c, 32)));
        panel.addView(row);
        final TextView status = text(c, "", 10, 0xFF80FF80);
        panel.addView(status, new LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT));
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            try {
                status.setText("模式 " + Hook.modeName() + " | 渲染器: " + Hook.stRenderer + " | LUT: " + Hook.stLut + "\n原因: " + Hook.stReason + "\n构建: " + Style.lastBuild + "\n成片: " + Capture.stCapture + "\n" + Style.lastCaptureRecipe);
                if (status.isAttachedToWindow()) status.postDelayed(tick[0], 500);
            } catch (Throwable e) { err("status", e); }
        };
        status.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { try { v.removeCallbacks(tick[0]); v.post(tick[0]); } catch (Throwable ignored) {} }
            public void onViewDetachedFromWindow(View v) { try { v.removeCallbacks(tick[0]); } catch (Throwable ignored) {} }
        });
        TextView hint = text(c, "预览生效需先选中任意滤镜（无滤镜时相机不走滤镜渲染）", 10, 0xFFBBBBBB);
        panel.addView(hint, new LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT));
        refresh.run();
        return panel;
    }

    static String capText() { return Style.capture ? "成片(实验):开" : "成片(实验):关"; }

    static TextView text(Context c, String s, int sp, int color) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t;
    }

    static TextView chip(Context c, String s) {
        TextView t = text(c, s, 12, Color.WHITE);
        int h = dp(c, 10), v = dp(c, 6);
        t.setPadding(h, v, h, v);
        GradientDrawable g = new GradientDrawable(); g.setColor(0x40FFFFFF); g.setCornerRadius(dp(c, 14));
        t.setBackground(g);
        t.setClickable(true);
        return t;
    }

    interface PadListener { void onPad(float x, float y); }
    interface ValueListener { void onValue(float v); }

    static final class PadView extends View {
        PadListener listener;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        Shader hShader, vShader; int sw, sh;
        PadView(Context c) { super(c); }

        @Override protected void onDraw(Canvas cv) {
            try {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                if (w != sw || h != sh) {
                    sw = w; sh = h;
                    hShader = new LinearGradient(0, 0, w, 0, 0xFF3A6FD8, 0xFFE08A2E, Shader.TileMode.CLAMP);
                    vShader = new LinearGradient(0, 0, 0, h, 0x66FFFFFF, 0xAA000000, Shader.TileMode.CLAMP);
                }
                float r = h * 0.08f;
                rect.set(0, 0, w, h);
                p.setStyle(Paint.Style.FILL);
                p.setShader(hShader); cv.drawRoundRect(rect, r, r, p);
                p.setShader(vShader); cv.drawRoundRect(rect, r, r, p);
                p.setShader(null);
                p.setColor(0x55FFFFFF); p.setStrokeWidth(1);
                for (int i = 1; i < 8; i++) { cv.drawLine(w * i / 8f, 0, w * i / 8f, h, p); cv.drawLine(0, h * i / 8f, w, h * i / 8f, p); }
                float cx = (Style.x + 1f) / 2f * w, cy = (1f - (Style.y + 1f) / 2f) * h;
                p.setColor(Color.WHITE); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4);
                cv.drawCircle(cx, cy, h * 0.05f, p);
            } catch (Throwable t) { err("pad.draw", t); }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            try {
                if (getWidth() <= 0 || getHeight() <= 0) return true;
                float nx = Math.max(-1f, Math.min(1f, e.getX() / getWidth() * 2f - 1f));
                float ny = Math.max(-1f, Math.min(1f, 1f - e.getY() / getHeight() * 2f));
                if (Math.abs(nx) < 0.04f) nx = 0f;
                if (Math.abs(ny) < 0.04f) ny = 0f;
                if (listener != null) listener.onPad(nx, ny);
                invalidate();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) save();
            } catch (Throwable t) { err("pad.touch", t); }
            return true;
        }
    }

    /** Custom slider 0..2 (replaces framework SeekBar to avoid host theme drawables). */
    static final class SliderView extends View {
        ValueListener listener;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        SliderView(Context c) { super(c); }

        @Override protected void onDraw(Canvas cv) {
            try {
                float w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                float r = h * 0.3f, pos = r + (Style.sat / 2f) * (w - 2 * r);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x55FFFFFF); p.setStrokeWidth(h * 0.12f);
                cv.drawLine(r, h / 2, w - r, h / 2, p);
                p.setColor(0xFFFFFFFF);
                cv.drawLine(r, h / 2, pos, h / 2, p);
                cv.drawLine(r + (w - 2 * r) / 2, h * 0.25f, r + (w - 2 * r) / 2, h * 0.75f, p); // center tick (1.0)
                cv.drawCircle(pos, h / 2, r, p);
            } catch (Throwable t) { err("slider.draw", t); }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            try {
                float w = getWidth(), r = getHeight() * 0.3f;
                if (w <= 2 * r) return true;
                float v = Math.max(0f, Math.min(1f, (e.getX() - r) / (w - 2 * r))) * 2f;
                if (Math.abs(v - 1f) < 0.04f) v = 1f;
                if (listener != null) listener.onValue(v);
                invalidate();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) save();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            } catch (Throwable t) { err("slider.touch", t); }
            return true;
        }
    }
}
