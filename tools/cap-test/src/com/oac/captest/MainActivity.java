package com.oac.captest;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Prueba de la captura interna de TCL (com.tcl.snapshot.CaptureScreen).
 * Pregunta a responder: ¿devuelve la imagen REAL de lo que se ve (p.ej. YouTube),
 * o negro? Guarda los frames en getExternalFilesDir y escribe todo en logcat (tag CAPTEST).
 */
public class MainActivity extends Activity {

    static final String TAG = "CAPTEST";
    TextView log;
    Handler ui;
    int sw = 1920, sh = 1080;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Handler(Looper.getMainLooper());
        log = new TextView(this);
        log.setTextSize(13);
        log.setPadding(24, 24, 24, 24);
        ScrollView sv = new ScrollView(this);
        sv.addView(log);
        setContentView(sv);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        sw = dm.widthPixels;
        sh = dm.heightPixels;

        line("CapTest — SoC Realtek RTD2851M");
        line("pantalla " + sw + "x" + sh + "  salida: " + getExternalFilesDir(null));
        line("Lanzando prueba en 1s...");
        new Thread(new Runnable() { public void run() {
            try { Thread.sleep(1000); } catch (Exception e) {}
            runAll();
        }}).start();
    }

    void runAll() {
        Object cs;
        Class<?> csCls;
        try {
            csCls = Class.forName("com.tcl.snapshot.CaptureScreen");
            cs = csCls.getMethod("getInstance").invoke(null);
            line("CaptureScreen.getInstance() OK");
        } catch (Throwable t) {
            line("NO se pudo obtener CaptureScreen: " + t);
            line(">> probablemente hidden-API. Avisar para desactivarlo por adb.");
            return;
        }
        try {
            csCls.getMethod("setContext", android.content.Context.class).invoke(cs, this);
            line("setContext() OK");
        } catch (Throwable t) {
            line("setContext fallo (sigo igual): " + t);
        }

        for (int type = 0; type <= 4; type++) {
            fileCapture(csCls, cs, type);
            processCapture(csCls, cs, type);
        }
        line("=== FIN. Revisa los archivos y avisa. ===");
    }

    /** CapturePicture(type, Rect, path) — sincrono a PNG, con vigilante de 6s. */
    void fileCapture(final Class<?> csCls, final Object cs, final int type) {
        final File out = new File(getExternalFilesDir(null), "file_t" + type + ".png");
        out.delete();
        final AtomicReference<Object> ret = new AtomicReference<Object>();
        final AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        Thread w = new Thread(new Runnable() { public void run() {
            try {
                Rect r = new Rect(0, 0, sw, sh);
                Method m = csCls.getMethod("CapturePicture", int.class, Rect.class, String.class);
                ret.set(m.invoke(cs, type, r, out.getAbsolutePath()));
            } catch (Throwable t) { err.set(t); }
        }});
        w.setDaemon(true);
        w.start();
        try { w.join(6000); } catch (Exception e) {}
        if (w.isAlive()) { line("file t" + type + ": COLGADO (6s sin responder)"); return; }
        if (err.get() != null) { line("file t" + type + ": error " + err.get()); return; }
        long len = out.length();
        line("file t" + type + ": ret=" + ret.get() + " bytes=" + len);
        if (len > 0) describePng(out, "file t" + type);
    }

    /** CapturePictureProcess(type, arg, callback) — asincrono, callback entrega bytes RGB. */
    void processCapture(final Class<?> csCls, final Object cs, final int type) {
        Class<?> cbCls;
        try { cbCls = Class.forName("com.tcl.snapshot.CaptureScreen$CaptureCallback"); }
        catch (Throwable t) { return; }
        if (!cbCls.isInterface()) { return; } // si es clase abstracta, Proxy no sirve

        for (int arg = 0; arg <= 1; arg++) {
            final int a = arg;
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<byte[]> data = new AtomicReference<byte[]>();
            Object cb = Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls},
                new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] args) {
                        if (m.getName().contains("callback") && args != null && args.length > 0
                                && args[0] instanceof byte[]) {
                            data.set((byte[]) args[0]);
                            latch.countDown();
                        }
                        return null;
                    }
                });
            try {
                Method m = csCls.getMethod("CapturePictureProcess", int.class, int.class, cbCls);
                Object r = m.invoke(cs, type, a, cb);
                boolean fired = latch.await(4, TimeUnit.SECONDS);
                byte[] d = data.get();
                if (fired && d != null) {
                    describeBytes(d, "proc t" + type + " a" + a, "proc_t" + type + "_a" + a + ".bin");
                } else {
                    line("proc t" + type + " a" + a + ": sin callback (ret=" + r + ")");
                }
            } catch (Throwable t) {
                line("proc t" + type + " a" + a + ": error " + t);
            }
        }
    }

    void describePng(File f, String tag) {
        try {
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bm == null) { line(tag + ": PNG ilegible"); return; }
            long[] s = sampleBitmap(bm);
            line(tag + ": " + bm.getWidth() + "x" + bm.getHeight()
                    + " avgRGB(" + s[0] + "," + s[1] + "," + s[2] + ")"
                    + (s[0] + s[1] + s[2] == 0 ? "  -> NEGRO" : "  -> ¡IMAGEN!"));
        } catch (Throwable t) { line(tag + ": decode error " + t); }
    }

    long[] sampleBitmap(Bitmap bm) {
        long r = 0, g = 0, bl = 0; int n = 0;
        int W = bm.getWidth(), H = bm.getHeight();
        for (int y = 0; y < H; y += Math.max(1, H / 20))
            for (int x = 0; x < W; x += Math.max(1, W / 20)) {
                int c = bm.getPixel(x, y);
                r += (c >> 16) & 0xff; g += (c >> 8) & 0xff; bl += c & 0xff; n++;
            }
        if (n == 0) n = 1;
        return new long[]{r / n, g / n, bl / n};
    }

    void describeBytes(byte[] d, String tag, String fname) {
        long sum = 0; int mn = 255, mx = 0;
        for (byte x : d) { int v = x & 0xff; sum += v; if (v < mn) mn = v; if (v > mx) mx = v; }
        long avg = d.length == 0 ? 0 : sum / d.length;
        line(tag + ": len=" + d.length + " avg=" + avg + " min=" + mn + " max=" + mx
                + (mx == 0 ? "  -> NEGRO" : "  -> ¡DATOS!"));
        try {
            File out = new File(getExternalFilesDir(null), fname);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(d); fos.close();
        } catch (Throwable t) {}
    }

    void line(final String s) {
        Log.i(TAG, s);
        ui.post(new Runnable() { public void run() {
            log.append(s + "\n");
        }});
    }
}
