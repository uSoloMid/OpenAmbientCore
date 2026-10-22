import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Sonda de solo lectura para TCL (Realtek RTD2851M / tosapi).
 * Corre con app_process desde adb shell; usa reflexion para no depender de stubs.
 *
 *   probe pixel X Y W H [stage]   -> TvVideoApi.getPixelInfo(rect)
 *   probe apl                     -> TvVideoApi.getPanelBlockNumAplArray()
 *   probe hist MODE N             -> TvVideoApi.getVideoHistogramDistribution(mode, n)
 *   probe grid COLS ROWS W H      -> getPixelInfo en una cuadricula, imprime RGB promedio
 *   probe bench N                 -> mide latencia de getPixelInfo
 */
public class Probe {
    static Class<?> apiCls, rectCls, infoCls;
    static Object api;

    public static void main(String[] a) throws Throwable {
        if (a.length > 0 && a[0].equals("rtkhist")) { rtkHist(i(a, 1, 1), i(a, 2, 0)); return; }
        if (a.length > 0 && a[0].equals("cap")) { capture(a); return; }
        System.load("/product/lib/libcom_tcl_tv_jni.so");
        apiCls = Class.forName("com.tcl.tosapi.atv.TvVideoApi");
        rectCls = Class.forName("com.tcl.tosapi.model.VideoWindowRect");
        infoCls = Class.forName("com.tcl.tosapi.model.ScreenPixelInfo");
        api = apiCls.getMethod("getInstance").invoke(null);
        String cmd = a.length > 0 ? a[0] : "pixel";
        switch (cmd) {
            case "pixel": {
                int x = i(a, 1, 0), y = i(a, 2, 0), w = i(a, 3, 1920), h = i(a, 4, 1080);
                Object info = pixel(x, y, w, h, a.length > 5 ? Integer.parseInt(a[5]) : -1);
                dump(info);
                break;
            }
            case "grid": {
                int cols = i(a, 1, 4), rows = i(a, 2, 3), w = i(a, 3, 1920), h = i(a, 4, 1080);
                for (int r = 0; r < rows; r++) {
                    StringBuilder sb = new StringBuilder();
                    for (int c = 0; c < cols; c++) {
                        int x0 = c * w / cols, y0 = r * h / rows;
                        Object info = pixel(x0, y0, w / cols, h / rows, -1);
                        sb.append(avg(info)).append("  ");
                    }
                    System.out.println(sb);
                }
                break;
            }
            case "apl": {
                Object p = apiCls.getMethod("getPanelBlockNumAplArray").invoke(api);
                dump(p);
                Object raw = call("getPanelBlockNumAplArray_native");
                System.out.println("native: " + arr(raw));
                break;
            }
            case "hist": {
                Method m = apiCls.getMethod("getVideoHistogramDistribution", int.class, int.class);
                System.out.println(arr(m.invoke(api, i(a, 1, 0), i(a, 2, 32))));
                break;
            }
            case "bench": {
                int n = i(a, 1, 50);
                long t0 = System.nanoTime();
                for (int k = 0; k < n; k++) pixel(0, 0, 192, 108, -1);
                System.out.printf("%d llamadas, %.2f ms/llamada%n", n, (System.nanoTime() - t0) / 1e6 / n);
                break;
            }
        }
    }

    /** Realtek: com.realtek.tv.PQ.getBrightnessHistogram(), n muestras cada ms milisegundos. */
    static void rtkHist(int n, int ms) throws Throwable {
        try { System.load("/apex/com.android.art/lib/libnativehelper.so"); } catch (Throwable t) { System.out.println("nativehelper: " + t); }
        try { System.loadLibrary("rtk_framework_jni"); } catch (Throwable t) { System.out.println("loadLibrary: " + t); }
        Class<?> pq = Class.forName("com.realtek.tv.PQ");
        Object o = pq.getConstructor().newInstance();
        Method h = pq.getMethod("getBrightnessHistogram");
        for (int k = 0; k < n; k++) {
            long t0 = System.nanoTime();
            Object r = h.invoke(o);
            System.out.printf("%.1fms %s%n", (System.nanoTime() - t0) / 1e6, r);
            if (ms > 0) Thread.sleep(ms);
        }
    }

    /** TCL CaptureScreen.CapturePicture(type, rect, path). cap [type] [path] [x y w h] */
    static void capture(String[] a) throws Throwable {
        int type = i(a, 1, 0);
        String path = a.length > 2 ? a[2] : "/data/local/tmp/cap.png";
        int x = i(a, 3, 0), y = i(a, 4, 0), w = i(a, 5, 1920), h = i(a, 6, 1080);
        android.os.Looper.prepareMainLooper();
        Object th = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Object ctx = th.getClass().getMethod("getSystemContext").invoke(th);
        Class<?> cs = Class.forName("com.tcl.snapshot.CaptureScreen");
        Object inst = cs.getMethod("getInstance").invoke(null);
        try { cs.getMethod("setContext", Class.forName("android.content.Context")).invoke(inst, ctx); }
        catch (Throwable t) { System.out.println("setContext: " + t); }
        Object rect = Class.forName("android.graphics.Rect")
                .getConstructor(int.class, int.class, int.class, int.class).newInstance(x, y, x + w, y + h);
        for (String m : new String[]{"CapturePicture"}) {
            Object ok = cs.getMethod(m, int.class, Class.forName("android.graphics.Rect"), String.class)
                    .invoke(inst, type, rect, path);
            System.out.println(m + "(type=" + type + ")=" + ok + " -> " + path);
        }
    }

    static Object pixel(int x, int y, int w, int h, int stage) throws Throwable {
        Object rect = rectCls.getConstructor(int.class, int.class, int.class, int.class).newInstance(x, y, w, h);
        if (stage < 0) return apiCls.getMethod("getPixelInfo", rectCls).invoke(api, rect);
        Object info = infoCls.getConstructor().newInstance();
        Class<?> st = Class.forName("com.tcl.tosapi.model.ScreenPixelInfo$EnTCLPixelRGBStage");
        setf(info, "enStage", st.getEnumConstants()[stage]);
        // Estilo MStar MApi_XC_ReportPixelInfo: el llamador declara version y sizeof(struct).
        setf(info, "u32ReportPixelInfo_Version", Integer.getInteger("ver", 1));
        setf(info, "u16ReportPixelInfo_Length", (short) (int) Integer.getInteger("len", 48));
        setf(info, "u16XStart", (short) x);
        setf(info, "u16YStart", (short) y);
        setf(info, "u16XEnd", (short) (x + w - 1));
        setf(info, "u16YEnd", (short) (y + h - 1));
        setf(info, "bShowRepWin", Boolean.getBoolean("show"));
        Method m = apiCls.getDeclaredMethod("getPixelInfo_native", rectCls, infoCls);
        m.setAccessible(true);
        Object ret = m.invoke(api, rect, info);
        System.out.println("ret=" + ret);
        return info;
    }

    static String avg(Object info) throws Throwable {
        if (info == null) return "null";
        long n = (long) Math.max(1, (num(info, "u16XEnd") - num(info, "u16XStart") + 1))
                * Math.max(1, (num(info, "u16YEnd") - num(info, "u16YStart") + 1));
        return String.format("(%d,%d,%d)", num(info, "u32RCrSum") / n, num(info, "u32GYSum") / n, num(info, "u32BCbSum") / n);
    }

    static Object call(String name) throws Throwable {
        Method m = apiCls.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(api);
    }

    static long num(Object o, String f) throws Throwable {
        Field fd = o.getClass().getDeclaredField(f);
        fd.setAccessible(true);
        Object v = fd.get(o);
        if (v instanceof Short) return ((Short) v) & 0xffff;
        if (v instanceof Integer) return ((Integer) v) & 0xffffffffL;
        return ((Number) v).longValue();
    }

    static void setf(Object o, String f, Object v) throws Throwable {
        Field fd = o.getClass().getDeclaredField(f);
        fd.setAccessible(true);
        fd.set(o, v);
    }

    static void dump(Object o) throws Throwable {
        if (o == null) { System.out.println("null"); return; }
        StringBuilder sb = new StringBuilder(o.getClass().getSimpleName()).append(" {");
        for (Field f : o.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object v = f.get(o);
            sb.append("\n  ").append(f.getName()).append(" = ")
              .append(v instanceof Short ? (((Short) v) & 0xffff) : v != null && v.getClass().isArray() ? arr(v) : v);
        }
        System.out.println(sb.append("\n}"));
    }

    static String arr(Object v) {
        if (v instanceof int[]) return java.util.Arrays.toString((int[]) v);
        return String.valueOf(v);
    }

    static int i(String[] a, int k, int d) { return a.length > k ? Integer.parseInt(a[k]) : d; }
}
