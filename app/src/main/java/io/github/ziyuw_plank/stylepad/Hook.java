package io.github.ziyuw_plank.stylepad;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks (verified by static decompile of Xiaomi Camera 6.4.000530.0 and 6.5.000280.1; CandySDK is NOT obfuscated):
 *  com.xiaomi.milab.filtersdk.CandySDK: <init>(int), a(String)=configPipeline, i(String)=updatePipeline (per frame),
 *  g(Bitmap)=setLutBitmap(-1), b(String)=findEffectIndices(..,"CubeLutEffect"), f(int,Bitmap)=setLutBitmap(idx),
 *  c(Object,float[])=processBitmap/HardwareBuffer, e()=release.
 * The preview filter renderer (6.4: af.AbstractC1279g, 6.5: Km.AbstractC1331i, log tag FilterBasicRender) creates
 * new CandySDK(6), a(pipe), g(lutBitmap), then i(pipe) every frame where pipe contains "@CubeLutEffect;cube_strength=S;".
 * We recognise that instance by its pipeline string, so no obfuscated names are needed.
 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "CamStylePad";
    static final String CANDY = "com.xiaomi.milab.filtersdk.CandySDK";
    static final Pattern STRENGTH = Pattern.compile("cube_strength=([0-9.]+)");

    static void log(String s) { XposedBridge.log(TAG + ": " + s); }

    static final ThreadLocal<Boolean> SELF = ThreadLocal.withInitial(() -> false);
    static final Map<Object, Bitmap> ORIG = Collections.synchronizedMap(new WeakHashMap<>());     // sdk -> renderer's LUT
    static final Map<Object, Bitmap> APPLIED = Collections.synchronizedMap(new WeakHashMap<>());  // sdk -> LUT we last set
    static final Map<Object, String> PIPE = Collections.synchronizedMap(new WeakHashMap<>());
    static final Map<Object, Integer> TYPE = Collections.synchronizedMap(new WeakHashMap<>());
    static boolean loggedActive;
    static volatile ClassLoader hostCl;
    /** 1 = A: UI only, all CandySDK hooks pure passthrough; 2 = B: hooks record state but never push LUT/rewrite; 3 = C: full */
    static volatile int MODE = 1;
    static int mode() { return MODE; }
    static String modeName() { return MODE == 1 ? "A" : MODE == 2 ? "B" : "C"; }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.android.camera".equals(lpp.packageName)) return;
        if (lpp.processName != null && !"com.android.camera".equals(lpp.processName)) return; // main process only (skip :widgetProvider etc.)
        log("loaded in " + lpp.processName);
        try { init(lpp); } catch (Throwable t) { log("init failed: " + t); }
    }

    private void init(XC_LoadPackage.LoadPackageParam lpp) {
        ClassLoader cl = lpp.classLoader;
        hostCl = cl;
        try { hookUi(); } catch (Throwable t) { log("UI hook failed: " + t); }
    }

    /*
     * v0.6 design: NEVER call CandySDK natives ourselves and NEVER touch the pipeline string.
     * The filter renderer (6.4 af.AbstractC1279g.e) does, on the GL thread:
     *     if (sdk == null)            { sdk = new CandySDK(6); sdk.a(pipe); sdk.g(lutBitmap); N = false; }
     *     else if (lutChanged || N)   { sdk.e(); sdk = new CandySDK(6); sdk.a(pipe); sdk.g(lutBitmap); N = false; }
     *     sdk.i(pipe); sdk.d(...);
     * We (1) swap the Bitmap argument of that g() call for our composite (same 512x512 ARGB_8888 format), and
     * (2) for live updates set the renderer's boolean field N = true from inside its own render call (same thread),
     * so the camera itself tears down / rebuilds its CandySDK and pushes our LUT in its normal order.
     * The renderer instance is discovered from the caller frame of CandySDK.i (no hard-coded obfuscated class).
     */
    static volatile String stRenderer = "未找到", stLut = "原始", stReason = "等待滤镜帧";
    static int gLogs, reasonLogs;
    static void reason(String r) {
        if (!r.equals(stReason)) { stReason = r; if (reasonLogs++ < 30) log("state: " + r); }
    }
    /** FilterBasicRender REAL dex names (jadx had renamed them: "renamed from: af.g" / "renamed from: Km.i", case-insensitive FS):
     *  6.4.000530.0 af.g (jadx af.AbstractC1279g), final int e(Oe.h), boolean N, CandySDK field; 6.5.000280.1 Km.i. */
    static final String[] RENDERER_NAMES = {"af.g", "Km.i", "af.AbstractC1279g", "Km.AbstractC1331i"};
    static volatile java.lang.reflect.Field rendererSdkField;
    static volatile Object renderer;           // FilterBasicRender instance
    static volatile java.lang.reflect.Field reloadField;
    static volatile boolean rendererHooked;
    static volatile String pushedKey = "orig"; // what the renderer currently has: "orig" or composite key
    static long lastReload;
    static boolean pushMarked;

    private void hookCandy(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClass(CANDY, cl);
        XposedHelpers.findAndHookMethod(c, "a", String.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try { if (MODE != 1 && p.args[0] != null) PIPE.put(p.thisObject, (String) p.args[0]); } catch (Throwable ignored) {}
            }
        });
        XposedHelpers.findAndHookMethod(c, "g", Bitmap.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try { onSetLut(p); } catch (Throwable t) { Overlay.err("g", t); }
            }
        });
        XposedHelpers.findAndHookMethod(c, "i", String.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try { if (MODE != 1) onUpdatePipeline(p); } catch (Throwable t) { Overlay.err("preview", t); }
            }
        });
        log("CandySDK hooked (mode " + modeName() + ")");
        hookRendererByName(cl);
    }

    private static void hookRendererByName(ClassLoader cl) {
        for (String n : RENDERER_NAMES) {
            try {
                Class<?> rc = Class.forName(n, false, cl);
                java.lang.reflect.Method m = null;
                for (java.lang.reflect.Method mm : rc.getDeclaredMethods())
                    if (mm.getName().equals("e") && mm.getParameterTypes().length == 1 && mm.getReturnType() == int.class
                            && !java.lang.reflect.Modifier.isAbstract(mm.getModifiers())) { m = mm; break; }
                java.lang.reflect.Field f = null;
                try { f = rc.getDeclaredField("N"); if (f.getType() != boolean.class) f = null; } catch (NoSuchFieldException ignored) {}
                java.lang.reflect.Field sdkF = null;
                for (java.lang.reflect.Field ff : rc.getDeclaredFields()) if (ff.getType().getName().equals(CANDY)) sdkF = ff;
                if (m == null || sdkF == null) {
                    StringBuilder ms = new StringBuilder();
                    for (java.lang.reflect.Method mm : rc.getDeclaredMethods()) if (mm.getReturnType() == int.class && mm.getParameterTypes().length == 1) ms.append(mm.getName()).append(' ');
                    log("renderer candidate " + n + " FOUND but rejected: e(1)->int=" + (m != null) + ", CandySDK field=" + (sdkF != null) + ", N=" + (f != null) + ", int(1) methods: " + ms);
                    continue;
                }
                sdkF.setAccessible(true); rendererSdkField = sdkF;
                if (f != null) { f.setAccessible(true); reloadField = f; }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.thisObject == null) return;
                        Object prev = renderer;
                        renderer = p.thisObject;
                        if (prev == null) {
                            stRenderer = "OK";
                            log("renderer instance captured (once): " + p.thisObject.getClass().getName());
                        }
                    }
                });
                rendererHooked = true;
                stRenderer = "已挂钩,等待渲染";
                log("renderer hooked by name: " + n + "#" + m.getName() + ", reload field = " + (f == null ? "none" : "N"));
                return;
            } catch (ClassNotFoundException e) {
                log("renderer candidate " + n + ": ClassNotFound");
            } catch (Throwable t) { log("hook renderer " + n + ": " + t); }
        }
        log("no known renderer class matched; will try stack discovery at first filter frame");
    }

    /** true if this CandySDK is the one currently held by the captured filter renderer (or renderer unknown -> 512 LUTs only) */
    static boolean owned(Object sdk) {
        Object r = renderer; java.lang.reflect.Field f = rendererSdkField;
        if (r != null && f != null) { try { return f.get(r) == sdk; } catch (Throwable t) { return false; } }
        Bitmap o = ORIG.get(sdk);
        return o == null || o.getWidth() == 512;
    }

    static boolean isFilterPipe(String pipe) { return pipe != null && pipe.contains("CubeLutEffect") && !pipe.contains("CopyInput"); }

    static float strengthOf(String pipe) {
        float s = 1f;
        if (pipe == null) return s;
        Matcher m = STRENGTH.matcher(pipe);
        if (m.find()) try { s = Float.parseFloat(m.group(1)); } catch (NumberFormatException ignored) {}
        return s;
    }

    /** The renderer's own g(lutBitmap) call, right after its own a(pipe). Swap the argument only. */
    private static void onSetLut(XC_MethodHook.MethodHookParam p) {
        if (MODE == 1 || !(p.args[0] instanceof Bitmap)) return;
        Object sdk = p.thisObject;
        String pipe = PIPE.get(sdk);
        if (!isFilterPipe(pipe)) return;
        Bitmap orig = (Bitmap) p.args[0];
        if (!owned(sdk)) { if (gLogs++ < 12) log("g(): lut " + orig.getWidth() + "x" + orig.getHeight() + " not owned by filter renderer -> untouched"); return; }
        ORIG.put(sdk, orig);
        lastOrig = orig;
        float s = strengthOf(pipe);
        String why = null;
        Bitmap comp = null;
        if (MODE != 3) why = "模式" + modeName() + "不推送";
        else if (Style.neutral()) why = "调节板为中性";
        else if (!Style.supported(orig.getWidth(), orig.getHeight())) why = "LUT尺寸" + orig.getWidth() + "x" + orig.getHeight() + "不支持";
        else if ((comp = Style.readyFor(orig, s)) == null) { why = "合成LUT未就绪"; Style.composite(orig, s); }
        if (gLogs++ < 12) log("g(): lut " + orig.getWidth() + "x" + orig.getHeight() + " " + orig.getConfig() + " strength=" + s + " -> " + (why == null ? "SWAPPED" : "orig (" + why + ")"));
        if (why != null) { pushedKey = "orig"; stLut = "原始"; reason(why); return; }
        if (!pushMarked) { pushMarked = true; Overlay.markPush(); }
        p.args[0] = comp;
        pushedKey = Style.keyOf(orig, s);
        stLut = "已替换"; reason("生效中");
        if (!loggedActive) { loggedActive = true; log("composite LUT handed to renderer via its own g(), strength=" + s); }
    }
    static volatile Bitmap lastOrig;

    /** Every preview frame on the GL thread, inside the renderer's render call. Read-only + flag set. */
    private static void onUpdatePipeline(XC_MethodHook.MethodHookParam p) {
        String pipe = (String) p.args[0];
        if (!isFilterPipe(pipe)) return;
        if (!rendererHooked) discoverRenderer();
        if (!owned(p.thisObject)) return;
        if (MODE != 3) { reason("模式" + modeName() + "不推送"); return; }
        Bitmap orig = ORIG.get(p.thisObject);
        if (orig == null) { reason("未见到该滤镜的LUT(g)调用"); return; }
        String want;
        if (Style.neutral()) want = "orig";
        else {
            Bitmap comp = Style.composite(orig, strengthOf(pipe)); // schedules async build; null until ready
            if (comp == null) { reason("正在生成合成LUT"); return; }
            want = Style.keyOf(orig, strengthOf(pipe));
        }
        if (want.equals(pushedKey)) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastReload < 120) return;   // throttle renderer rebuilds while dragging
        Object r = renderer; java.lang.reflect.Field f = reloadField;
        if (r == null || f == null) { reason(r == null ? "渲染器实例未捕获,无法刷新" : "无刷新字段N"); return; }
        try { f.setBoolean(r, true); lastReload = now; reason("已请求刷新"); if (reasonLogs < 60) { reasonLogs++; log("reload requested -> " + want); } } catch (Throwable t) { Overlay.err("reloadFlag", t); }
    }

    /** Find the renderer class from the caller frame of CandySDK.i, hook its render method to capture the instance. */
    private static synchronized void discoverRenderer() {
        if (rendererHooked) return;
        rendererHooked = true;
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            String cls = null, meth = null;
            for (int k = 0; k < st.length - 1; k++) {
                if (CANDY.equals(st[k].getClassName()) && "i".equals(st[k].getMethodName())) {
                    // skip Xposed/LSPlant bridge frames
                    for (int j = k + 1; j < st.length; j++) {
                        String n = st[j].getClassName();
                        if (n.startsWith("de.robv") || n.startsWith("org.lsposed") || n.startsWith("LSPHooker") || n.startsWith("java.lang.reflect") || n.startsWith("io.github.ziyuw_plank")) continue;
                        cls = n; meth = st[j].getMethodName(); break;
                    }
                    break;
                }
            }
            if (cls == null) {
                StringBuilder sb = new StringBuilder("renderer caller not found in stack; first frames:");
                for (int k = 0; k < st.length; k++) sb.append("\n  ").append(st[k]);
                log(sb.toString());
                return;
            }
            final Class<?> rc = XposedHelpers.findClass(cls, hostCl);
            java.lang.reflect.Field f = null;
            for (Class<?> k = rc; k != null && f == null; k = k.getSuperclass()) {
                try { java.lang.reflect.Field ff = k.getDeclaredField("N"); if (ff.getType() == boolean.class) f = ff; } catch (NoSuchFieldException ignored) {}
            }
            if (f == null) { log("reload flag field N not found in " + cls + " -> live updates off (LUT applies on next filter change)"); }
            else { f.setAccessible(true); reloadField = f; }
            XposedBridge.hookAllMethods(rc, meth, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { if (p.thisObject != null) renderer = p.thisObject; }
            });
            stRenderer = "栈发现:" + cls;
            log("renderer = " + cls + "#" + meth + ", reload field = " + (f == null ? "none" : f.getName()));
        } catch (Throwable t) { Overlay.err("discoverRenderer", t); }
    }

    private void hookUi() {
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Application app = (Application) p.thisObject;
                    Overlay.loadPrefs(app);
                    Overlay.checkSafeMode(app);
                    try { MODE = app.getSharedPreferences(Overlay.PREFS, 0).getInt("mode", 3); } catch (Throwable ignored) {}
                    // mode A: don't even register CandySDK hooks (mode changes apply on next camera launch)
                    if (MODE != 1) { try { hookCandy(hostCl); } catch (Throwable t) { log("CandySDK hooks failed: " + t); } }
                    else log("mode A: CandySDK hooks NOT registered");
                    try { Capture.hook(hostCl); } catch (Throwable t) { log("Capture hook failed: " + t); }
                    Overlay.installCrashHandler(app);
                    app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                        public void onActivityResumed(Activity a) {
                            try { if (isCamera(a)) Overlay.onResumed(a); } catch (Throwable t) { Overlay.err("resume", t); }
                        }
                        public void onActivityCreated(Activity a, Bundle b) {}
                        public void onActivityStarted(Activity a) {}
                        public void onActivityPaused(Activity a) {}
                        public void onActivityStopped(Activity a) {}
                        public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                        public void onActivityDestroyed(Activity a) {}
                    });
                } catch (Throwable t) { Overlay.err("lifecycle", t); }
            }
        });
    }

    static boolean isCamera(Activity a) {
        for (Class<?> k = a.getClass(); k != null; k = k.getSuperclass())
            if ("com.android.camera.Camera".equals(k.getName())) return true;
        return false;
    }
}
