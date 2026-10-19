package io.github.ziyuw_plank.stylepad;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.os.Handler;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Capture grading for Camera 6.4.000530.0.
 *
 * Evidence:
 * <ul>
 *   <li>{@code ImageReceiverExecutor}: if {@code MIVISDKConfig.isSupportMIVI3OutputJpeg()} then
 *       {@code ImageProcessor}/{@code S9.i} FilterProcessor is SKIPPED — JPEG comes from HAL/reprocess.
 *       That is the most likely reason v0.10's FilterProcessor-only hook never graded.</li>
 *   <li>{@code S9.q.a(int,byte[])} log "fillJpegData" stores the final JPEG (type 0) / other planes
 *       into ParallelTaskData before save — fires on every still regardless of MIVI3.</li>
 *   <li>{@code ResultOutputData.addOutputData(byte[],...)} receives reprocess output bytes.</li>
 * </ul>
 * Primary path: CPU 3D-LUT on JPEG bytes in {@code fillJpegData} / {@code addOutputData}.
 * Secondary: FilterProcessor + CandySDK on GL (when that path runs).
 */
final class Capture {
    private static final String PIPE = "CopyInput@CubeLutEffect;cube_strength=1.0;lut_type=0;";
    private static final String CANDY = "com.xiaomi.milab.filtersdk.CandySDK";
    static volatile String stCapture = "未挂钩";
    private static int jpegLogs, filterLogs;

    static void hook(ClassLoader cl) {
        boolean any = false;
        any |= hookFillJpeg(cl);
        any |= hookAddOutputData(cl);
        any |= hookFilterProcessor(cl);
        logMivi3(cl);
        if (!any) {
            stCapture = "全部挂钩失败";
            toast("StylePad 成片挂钩失败");
        } else {
            stCapture = "已挂钩成片路径";
            Hook.log("capture: hooks installed (fillJpeg/addOutput/filterProcessor)");
        }
    }

    private static void logMivi3(ClassLoader cl) {
        try {
            Class<?> cfg = Class.forName("com.xiaomi.camera.mivi.MIVISDKConfig", false, cl);
            Object inst = XposedHelpers.callStaticMethod(cfg, "getInstance");
            boolean mivi3 = (Boolean) XposedHelpers.callMethod(inst, "isSupportMIVI3OutputJpeg");
            Hook.log("capture: isSupportMIVI3OutputJpeg=" + mivi3
                    + " (true => FilterProcessor skipped; rely on fillJpegData)");
        } catch (Throwable t) {
            Hook.log("capture: MIVI3 flag read failed: " + t);
        }
    }

    /** ParallelTaskData.fill JPEG here — mutate args[1] before the method stores it. */
    private static boolean hookFillJpeg(ClassLoader cl) {
        try {
            Class<?> q = Class.forName("S9.q", false, cl);
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : q.getDeclaredMethods()) {
                if (!"a".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == int.class && p[1] == byte[].class) { target = m; break; }
            }
            if (target == null) { Hook.log("capture: S9.q.a(int,byte[]) not found"); return false; }
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { onJpegBytes(p, "fillJpeg"); } catch (Throwable t) { Overlay.err("fillJpeg", t); }
                }
            });
            Hook.log("capture: hooked S9.q#a fillJpegData");
            return true;
        } catch (Throwable t) {
            Hook.log("capture: fillJpeg hook fail: " + t);
            return false;
        }
    }

    private static boolean hookAddOutputData(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.xiaomi.camera.mivi.qcom.bean.ResultOutputData", false, cl);
            XposedHelpers.findAndHookMethod(c, "addOutputData",
                    byte[].class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try { onJpegBytes(p, "addOutput"); } catch (Throwable t) { Overlay.err("addOutput", t); }
                        }
                    });
            Hook.log("capture: hooked ResultOutputData.addOutputData");
            return true;
        } catch (Throwable t) {
            Hook.log("capture: addOutputData hook fail: " + t);
            return false;
        }
    }

    private static boolean hookFilterProcessor(ClassLoader cl) {
        try {
            Class<?> fp = Class.forName("S9.i", false, cl);
            for (java.lang.reflect.Method m : fp.getDeclaredMethods()) {
                if (!"a".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 5 || p[1] != Image.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (filterLogs++ < 5)
                            Hook.log("capture: FilterProcessor.a ENTER image=" + param.args[1]
                                    + " type=" + param.args[2] + " mode=" + Hook.modeName()
                                    + " neutral=" + Style.neutral());
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try { afterFilter(param); } catch (Throwable t) { Overlay.err("captureFP", t); }
                    }
                });
                Hook.log("capture: hooked S9.i#a FilterProcessor");
                return true;
            }
            Hook.log("capture: S9.i#a signature not matched");
            return false;
        } catch (Throwable t) {
            Hook.log("capture: FilterProcessor hook fail: " + t);
            return false;
        }
    }

    /** Shared JPEG-bytes mutator for fillJpegData / addOutputData. */
    private static void onJpegBytes(XC_MethodHook.MethodHookParam p, String tag) {
        if (Hook.MODE != 3) { stCapture = "模式" + Hook.modeName(); return; }
        if (Style.neutral()) { stCapture = "中性跳过"; return; }
        Object arg0 = p.args[0];
        byte[] data;
        int imageType = -1;
        if ("fillJpeg".equals(tag)) {
            imageType = (Integer) arg0;
            data = (byte[]) p.args[1];
            // type 0 = main JPEG; skip raw/depth/quickview
            if (imageType != 0) {
                if (jpegLogs < 8) Hook.log("capture: " + tag + " skip imageType=" + imageType);
                return;
            }
        } else {
            data = (byte[]) p.args[0];
            // format arg: often JPEG=256 or HEIC; only touch JPEG magic
        }
        if (data == null || data.length < 4) return;
        boolean jpeg = (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
        if (!jpeg) {
            if (jpegLogs++ < 10)
                Hook.log("capture: " + tag + " non-JPEG magic "
                        + Integer.toHexString(data[0] & 0xFF) + Integer.toHexString(data[1] & 0xFF)
                        + " len=" + data.length);
            return;
        }
        float px = Style.x, py = Style.y, ps = Style.sat;
        long t0 = android.os.SystemClock.uptimeMillis();
        byte[] out = gradeJpegCpu(data, px, py, ps);
        long dt = android.os.SystemClock.uptimeMillis() - t0;
        if (out == null || out == data) {
            stCapture = "CPU失败 " + dt + "ms";
            Hook.log("capture: " + tag + " CPU FAILED " + dt + "ms len=" + data.length
                    + " pad=(" + px + "," + py + "," + ps + ")");
            toast("StylePad 成片处理失败");
            return;
        }
        if ("fillJpeg".equals(tag)) p.args[1] = out;
        else p.args[0] = out;
        stCapture = "pad-only " + dt + "ms";
        if (jpegLogs++ < 20)
            Hook.log("capture: " + tag + " " + Style.lastCaptureRecipe
                    + " | previewWas: " + Style.lastRecipe
                    + " | " + data.length + "->" + out.length + "B " + dt + "ms");
        toast("StylePad 成片=预览同款T " + dt + "ms");
    }

    static byte[] gradeJpegCpu(byte[] jpeg, float px, float py, float ps) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inMutable = true;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        if (bmp == null) return null;
        try {
            if (!bmp.isMutable()) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true);
            Style.applyPadToBitmap(bmp, px, py, ps);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(jpeg.length);
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos)) return null;
            return bos.toByteArray();
        } finally {
            try { if (!bmp.isRecycled()) bmp.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static void afterFilter(XC_MethodHook.MethodHookParam p) {
        if (Hook.MODE != 3 || Style.neutral()) return;
        final Image image = (Image) p.args[1];
        final Object yuv = p.args[4];
        if (image == null || yuv == null) return;
        HardwareBuffer hb;
        try { hb = image.getHardwareBuffer(); } catch (Throwable t) { return; }
        if (hb == null) return;
        Handler gl = glHandler(yuv);
        if (gl == null) {
            Hook.log("capture: FilterProcessor after — no GL handler (Candy skip)");
            return;
        }
        final int w = hb.getWidth(), h = hb.getHeight();
        final float px = Style.x, py = Style.y, ps = Style.sat;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>("pending");
        Runnable job = () -> {
            try { candyOnGl(hb, w, h, px, py, ps, result); }
            catch (Throwable t) { result.set("candyFail:" + t.getClass().getSimpleName()); Overlay.err("candyGL", t); }
            finally { done.countDown(); }
        };
        try {
            if (gl.getLooper() == android.os.Looper.myLooper()) job.run();
            else {
                if (!gl.post(job)) return;
                done.await(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        Hook.log("capture: FilterProcessor Candy result=" + result.get() + " " + w + "x" + h);
    }

    private static Handler glHandler(Object yuvProcessor) {
        try {
            Object engine = XposedHelpers.callMethod(yuvProcessor, "a");
            if (engine == null) return null;
            Object glThread = XposedHelpers.getObjectField(engine, "b");
            if (glThread == null) return null;
            return (Handler) XposedHelpers.getObjectField(glThread, "b");
        } catch (Throwable t) { return null; }
    }

    private static void candyOnGl(HardwareBuffer hb, int w, int h, float px, float py, float ps,
                                  AtomicReference<String> result) {
        Bitmap lut = null; Object sdk = null;
        try {
            lut = Style.build(null, 1f, px, py, ps);
            if (lut == null) { result.set("lutNull"); return; }
            Class<?> candy = Class.forName(CANDY, false, Hook.hostCl);
            sdk = XposedHelpers.newInstance(candy, 9);
            XposedHelpers.callMethod(sdk, "a", PIPE);
            int[] idx = (int[]) XposedHelpers.callMethod(sdk, "b", PIPE);
            if (idx != null && idx.length > 0) XposedHelpers.callMethod(sdk, "f", idx[0], lut);
            else XposedHelpers.callMethod(sdk, "g", lut);
            XposedHelpers.callMethod(sdk, "c", hb, new float[]{0f, 0f, (float) w, (float) h});
            result.set("candyOK");
        } catch (Throwable t) {
            result.set("candyEx");
            throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        } finally {
            try { if (sdk != null) XposedHelpers.callMethod(sdk, "e"); } catch (Throwable ignored) {}
            try { if (lut != null && !lut.isRecycled()) lut.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static void toast(String msg) {
        Overlay.MAIN.post(() -> {
            try {
                if (Overlay.appCtx != null)
                    Toast.makeText(Overlay.appCtx, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }
}
