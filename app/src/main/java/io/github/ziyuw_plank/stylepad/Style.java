package io.github.ziyuw_plank.stylepad;

import android.graphics.Bitmap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared colour math for preview LUT + capture JPEG.
 *
 * <p><b>Preview (CandySDK CubeLutEffect)</b>: camera outputs {@code lerp(in, C[in], s)}.
 * We build C so that on the identity lattice {@code lerp(id, C, s) == T(lerp(id, F, s))}
 * where F is the stock filter LUT and T is {@link #applyToRgb} (the pad).
 *
 * <p><b>Capture on MIVI3</b>: HAL JPEG already includes the stock filter ≈ {@code F_s(scene)}.
 * We want {@code T(F_s(scene))}, so we apply {@link #applyToRgb} directly to each JPEG pixel
 * (same T). Do <em>not</em> re-apply F. Do <em>not</em> nearest-sample a 64³ LUT (that was the
 * v0.11 mismatch vs GPU trilinear / continuous T).
 */
public final class Style {
    public static volatile float x = 0f;   // -1 cool .. +1 warm
    public static volatile float y = 0f;   // -1 darker/contrasty .. +1 brighter/flatter
    public static volatile float sat = 1f; // 0 .. 2 color intensity
    public static volatile boolean capture = false;
    public static volatile int version = 0;

    /** Last recipe string for logs / status (preview or capture). */
    public static volatile String lastRecipe = "—";
    public static volatile String lastCaptureRecipe = "—";

    public static boolean neutral() {
        return Math.abs(x) < 0.01f && Math.abs(y) < 0.01f && Math.abs(sat - 1f) < 0.01f;
    }

    public static void set(float nx, float ny, float ns) {
        x = nx; y = ny; sat = ns; version++;
    }

    static boolean supported(int w, int h) { return w == h && (w == 512 || w == 64); }

    /**
     * Pad transform T — single source of truth for preview LUT lattice and capture JPEG pixels.
     * Operates in the same 0..1 channel space as the PNG LUT samples (camera treats them as
     * display-referred; we do not linearize — matches CubeLutEffect sampling).
     */
    public static void applyToRgb(float[] c, float px, float py, float ps) {
        float r = c[0] * (1f + 0.14f * px), g = c[1] * (1f + 0.03f * px), b = c[2] * (1f - 0.14f * px);
        float gamma = (float) Math.pow(2.0, -0.6 * py), con = 1f - 0.35f * py;
        r = tone(r, gamma, con); g = tone(g, gamma, con); b = tone(b, gamma, con);
        float l = 0.299f * r + 0.587f * g + 0.114f * b;
        c[0] = clamp(l + (r - l) * ps);
        c[1] = clamp(l + (g - l) * ps);
        c[2] = clamp(l + (b - l) * ps);
    }

    /** @deprecated use {@link #applyToRgb} */
    static void transform(float[] c, float px, float py, float ps) { applyToRgb(c, px, py, ps); }

    static float tone(float v, float gamma, float con) {
        v = (float) Math.pow(clamp(v), gamma);
        return clamp(0.5f + (v - 0.5f) * con);
    }
    static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    static int identityAt(int px, int py, int n, int tiles) {
        int ir = px % n, ig = py % n, ib = (py / n) * tiles + px / n;
        return 0xFF000000 | (q(ir, n) << 16) | (q(ig, n) << 8) | q(ib, n);
    }
    static int q(int i, int n) { return Math.round(i * 255f / (n - 1)); }

    /**
     * Preview composite LUT C. {@code orig == null} → pad-only ({@code T(id)} on lattice, strength ignored).
     * With stock filter F and strength s: lattice satisfies {@code lerp(id,C,s) = T(lerp(id,F,s))}.
     */
    static Bitmap build(Bitmap orig, float strength, float px, float py, float ps) {
        int W = 512;
        int[] src = null;
        if (orig != null) {
            if (orig.isRecycled() || !supported(orig.getWidth(), orig.getHeight())) return null;
            W = orig.getWidth();
            src = new int[W * W];
            orig.getPixels(src, 0, W, 0, 0, W, W);
        }
        int n = W == 512 ? 64 : 16, tiles = W == 512 ? 8 : 4;
        int[] out = new int[W * W];
        float[] c = new float[3];
        float sd = Math.max(strength, 0.05f);
        for (int yy = 0; yy < W; yy++) for (int xx = 0; xx < W; xx++) {
            int i = yy * W + xx, id = identityAt(xx, yy, n, tiles);
            float jr = ((id >> 16) & 255) / 255f, jg = ((id >> 8) & 255) / 255f, jb = (id & 255) / 255f;
            float ir = jr, ig = jg, ib = jb;
            if (src != null) {
                int o = src[i];
                ir += (((o >> 16) & 255) / 255f - ir) * strength;
                ig += (((o >> 8) & 255) / 255f - ig) * strength;
                ib += ((o & 255) / 255f - ib) * strength;
            }
            c[0] = ir; c[1] = ig; c[2] = ib;
            applyToRgb(c, px, py, ps);
            if (src != null) {
                c[0] = clamp(jr + (c[0] - jr) / sd);
                c[1] = clamp(jg + (c[1] - jg) / sd);
                c[2] = clamp(jb + (c[2] - jb) / sd);
            }
            out[i] = 0xFF000000
                    | (Math.round(c[0] * 255) << 16)
                    | (Math.round(c[1] * 255) << 8)
                    | Math.round(c[2] * 255);
        }
        lastRecipe = String.format(java.util.Locale.US,
                "previewLUT %s s=%.2f pad=(%.2f,%.2f,%.2f) v%d",
                orig == null ? "pad-only" : "F+pad", strength, px, py, ps, version);
        return Bitmap.createBitmap(out, W, W, Bitmap.Config.ARGB_8888);
    }

    /**
     * Capture path (MIVI3 default): JPEG already has stock filter → apply pad-only T per pixel
     * with the same {@link #applyToRgb} as the preview lattice (continuous, no 64³ quantize).
     */
    static void applyPadToBitmap(Bitmap bmp, float px, float py, float ps) {
        if (bmp == null || bmp.isRecycled()) return;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pxls = new int[w * h];
        bmp.getPixels(pxls, 0, w, 0, 0, w, h);
        float[] c = new float[3];
        for (int i = 0; i < pxls.length; i++) {
            int p = pxls[i];
            c[0] = ((p >> 16) & 255) / 255f;
            c[1] = ((p >> 8) & 255) / 255f;
            c[2] = (p & 255) / 255f;
            applyToRgb(c, px, py, ps);
            pxls[i] = ((p >>> 24) << 24)
                    | (Math.round(c[0] * 255) << 16)
                    | (Math.round(c[1] * 255) << 8)
                    | Math.round(c[2] * 255);
        }
        bmp.setPixels(pxls, 0, w, 0, 0, w, h);
        lastCaptureRecipe = String.format(java.util.Locale.US,
                "capture pad-only T(jpeg) pad=(%.2f,%.2f,%.2f) v%d %dx%d (assume HAL has F)",
                px, py, ps, version, w, h);
    }

    /** @deprecated name; use {@link #applyPadToBitmap} */
    static void applyToBitmap(Bitmap bmp, float px, float py, float ps) {
        applyPadToBitmap(bmp, px, py, ps);
    }

    // ---- async cache for preview composites ----
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    static final class Entry {
        Bitmap bmp; float strength; int ver = -1; boolean pending;
    }
    private static final java.util.IdentityHashMap<Bitmap, Entry> CACHE = new java.util.IdentityHashMap<>();
    static volatile String lastBuild = "无";
    static int buildLogs;

    static synchronized Bitmap readyFor(Bitmap orig, float strength) {
        Entry e = CACHE.get(orig);
        return (e != null && e.bmp != null && e.ver == version && Math.abs(e.strength - strength) < 1e-3f)
                ? e.bmp : null;
    }
    static String keyOf(Bitmap orig, float strength) {
        return "v" + version + "@" + System.identityHashCode(orig) + "/" + strength;
    }

    static synchronized Bitmap composite(final Bitmap orig, final float strength) {
        if (orig == null || !supported(orig.getWidth(), orig.getHeight())) return null;
        Bitmap r = readyFor(orig, strength);
        if (r != null) return r;
        Entry e = CACHE.get(orig);
        if (e == null) {
            if (CACHE.size() >= 4) CACHE.clear();
            e = new Entry(); CACHE.put(orig, e);
        }
        final int ver = version;
        if (e.pending) return null;
        e.pending = true;
        final Entry fe = e;
        final float px = x, py = y, ps = sat;
        EXEC.execute(() -> {
            long t0 = android.os.SystemClock.uptimeMillis();
            Bitmap b = null;
            try { b = build(orig, strength, px, py, ps); }
            catch (Throwable t) { Overlay.err("buildLUT", t); }
            long dt = android.os.SystemClock.uptimeMillis() - t0;
            synchronized (Style.class) {
                fe.pending = false;
                if (b != null) { fe.bmp = b; fe.ver = ver; fe.strength = strength; }
            }
            lastBuild = (b != null ? "完成 " : "失败 ") + orig.getWidth() + "px " + dt + "ms v" + ver;
            if (buildLogs++ < 20) Hook.log("LUT build " + lastBuild + " strength=" + strength + " | " + lastRecipe);
        });
        if (buildLogs < 20) Hook.log("LUT build start " + orig.getWidth() + "px v" + ver + " strength=" + strength);
        return null;
    }
}
