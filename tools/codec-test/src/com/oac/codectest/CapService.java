package com.oac.codectest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * Metodo "Codec compatibility": pantalla -> encoder H.264 (por si el bloque de
 * hardware alcanza el plano de video que ImageReader no ve) -> decoder -> ImageReader,
 * y medimos luminancia/color para saber si sale NEGRO o IMAGEN. Resultados en
 * logcat (tag CODEC) y en getExternalFilesDir/resultado.txt.
 */
public class CapService extends Service {
    static final String TAG = "CODEC";
    int W = 480, H = 270;
    MediaProjection mp;
    VirtualDisplay vd;
    MediaCodec enc, dec;
    ImageReader reader;
    Surface decSurface;
    HandlerThread readerThread;
    final StringBuilder report = new StringBuilder();

    long frames = 0, sumEnc = 0, maxEnc = 0;        // tamanos de frames del encoder
    long decFrames = 0;
    long rSum = 0, gSum = 0, bSum = 0, pixN = 0;    // color medio de los frames decodificados
    int nonBlackFrames = 0;
    final int[][] zone = new int[4][3];             // RGB real del ultimo frame: 0=arriba 1=abajo 2=izq 3=der

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFg();
        final int rc = intent.getIntExtra("rc", -1);
        final Intent data = intent.getParcelableExtra("data");
        new Thread(new Runnable() { public void run() { runTest(rc, data); } }).start();
        return START_NOT_STICKY;
    }

    void runTest(int rc, Intent data) {
        try {
            // Dar tiempo a que el usuario vuelva a YouTube y le de PLAY (si no, capturamos la pausa).
            for (int s = 8; s > 0; s--) { line("captura en " + s + "s... vuelve a YT y dale PLAY"); try { Thread.sleep(1000); } catch (Throwable t) {} }
            DisplayMetrics dm = getResources().getDisplayMetrics();
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mp = mpm.getMediaProjection(rc, data);
            if (mp == null) { line("getMediaProjection NULL"); done(); return; }
            if (Build.VERSION.SDK_INT >= 34) {
                mp.registerCallback(new MediaProjection.Callback() {}, new Handler(Looper.getMainLooper()));
            }

            // Decoder -> ImageReader (leemos pixeles aqui)
            reader = ImageReader.newInstance(W, H, android.graphics.ImageFormat.YUV_420_888, 3);
            decSurface = reader.getSurface();
            // Leer los frames CUANDO llegan (asincrono), no justo tras renderizar -> evita leer vacio (negro).
            readerThread = new HandlerThread("reader");
            readerThread.start();
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader r) { sampleReader(); }
            }, new Handler(readerThread.getLooper()));
            // PRUEBA: decoder de HARDWARE (el que dio imagen brillante). Riesgo: pelea con el player.
            dec = MediaCodec.createDecoderByType("video/avc");
            line("decoder: hardware");

            // Encoder con Surface de entrada; el VirtualDisplay dibuja la pantalla ahi
            enc = MediaCodec.createEncoderByType("video/avc");
            MediaFormat ef = MediaFormat.createVideoFormat("video/avc", W, H);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface encIn = enc.createInputSurface();
            enc.start();

            vd = mp.createVirtualDisplay("codec", W, H, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, encIn, null, null);
            line("pipeline listo " + W + "x" + H + "; capturando 5s...");

            boolean decStarted = false;
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            long end = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < end) {
                int oi = enc.dequeueOutputBuffer(encInfo, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // arrancar decoder con el formato real del encoder (trae SPS/PPS)
                    dec.configure(enc.getOutputFormat(), decSurface, null, 0);
                    dec.start();
                    decStarted = true;
                    line("decoder iniciado");
                } else if (oi >= 0) {
                    ByteBuffer buf = enc.getOutputBuffer(oi);
                    int size = encInfo.size;
                    boolean cfg = (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!cfg && size > 0) {
                        frames++; sumEnc += size; if (size > maxEnc) maxEnc = size;
                    }
                    if (decStarted && buf != null) feedDecoder(buf, encInfo, cfg);
                    enc.releaseOutputBuffer(oi, false);
                    if (decStarted) drainDecoder();
                }
            }
            if (decStarted) { for (int k = 0; k < 20; k++) drainDecoder(); }
            try { Thread.sleep(400); } catch (Throwable t) {}  // dejar que el listener muestree los ultimos frames

            long avgEnc = frames == 0 ? 0 : sumEnc / frames;
            line("ENCODER: frames=" + frames + " avg=" + avgEnc + "B max=" + maxEnc + "B");
            if (pixN > 0) {
                long r = rSum / pixN, g = gSum / pixN, b = bSum / pixN;
                line("DECODER: frames=" + decFrames + " lumaMedia=" + r
                        + " noNegros=" + nonBlackFrames
                        + (nonBlackFrames == 0 ? "  -> NEGRO" : "  -> ¡IMAGEN!"));
                String[] nm = {"ARRIBA", "ABAJO", "IZQ", "DER"};
                for (int z = 0; z < 4; z++)
                    line("  borde " + nm[z] + " RGB(" + zone[z][0] + "," + zone[z][1] + "," + zone[z][2] + ")");
            } else {
                line("DECODER: sin frames leidos (decFrames=" + decFrames + ")");
            }
            line("VEREDICTO: " + (maxEnc > 15000 && frames > 10
                    ? "el encoder VE contenido con movimiento"
                    : "frames chicos -> probablemente negro/estatico"));
        } catch (Throwable t) {
            line("ERROR " + t);
            for (StackTraceElement e : t.getStackTrace()) line("  at " + e);
        }
        done();
    }

    void feedDecoder(ByteBuffer encData, MediaCodec.BufferInfo info, boolean cfg) {
        try {
            int ii = dec.dequeueInputBuffer(10000);
            if (ii < 0) return;
            ByteBuffer din = dec.getInputBuffer(ii);
            din.clear();
            encData.position(info.offset);
            encData.limit(info.offset + info.size);
            din.put(encData);
            dec.queueInputBuffer(ii, 0, info.size, info.presentationTimeUs,
                    cfg ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
        } catch (Throwable t) { /* ignore */ }
    }

    void drainDecoder() {
        try {
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            int di = dec.dequeueOutputBuffer(bi, 2000);
            while (di >= 0) {
                dec.releaseOutputBuffer(di, true); // render al ImageReader; el listener lo muestrea al llegar
                decFrames++;
                di = dec.dequeueOutputBuffer(bi, 0);
            }
        } catch (Throwable t) { /* ignore */ }
    }

    void sampleReader() {
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return;
            Image.Plane[] p = img.getPlanes();
            ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
            int yRow = p[0].getRowStride();
            int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
            int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
            long sum = 0; int n = 0, max = 0;
            // 4 zonas de borde; acumulamos RGB real (YUV->RGB) por zona
            long[][] acc = new long[4][3]; int[] cnt = new int[4];
            for (int row = 0; row < H; row += 8) {
                for (int col = 0; col < W; col += 8) {
                    int Y = yb.get(row * yRow + col) & 0xff;
                    int uvRow = (row / 2), uvCol = (col / 2);
                    int U = ub.get(uvRow * uRow + uvCol * uPix) & 0xff;
                    int V = vb.get(uvRow * vRow + uvCol * vPix) & 0xff;
                    int r = clamp((int) (Y + 1.402 * (V - 128)));
                    int g = clamp((int) (Y - 0.344 * (U - 128) - 0.714 * (V - 128)));
                    int b = clamp((int) (Y + 1.772 * (U - 128)));
                    sum += Y; n++; if (Y > max) max = Y;
                    int z = -1;
                    if (row < H / 6) z = 0; else if (row > 5 * H / 6) z = 1;
                    else if (col < W / 6) z = 2; else if (col > 5 * W / 6) z = 3;
                    if (z >= 0) { acc[z][0] += r; acc[z][1] += g; acc[z][2] += b; cnt[z]++; }
                }
            }
            if (n > 0) synchronized (this) {
                rSum += sum / n; gSum += sum / n; bSum += sum / n; pixN++;
                if (max > 16) nonBlackFrames++;
                for (int z = 0; z < 4; z++) if (cnt[z] > 0) {
                    zone[z][0] = (int) (acc[z][0] / cnt[z]);
                    zone[z][1] = (int) (acc[z][1] / cnt[z]);
                    zone[z][2] = (int) (acc[z][2] / cnt[z]);
                }
            }
        } catch (Throwable t) {
        } finally { if (img != null) img.close(); }
    }

    static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    void startFg() {
        String ch = "codec";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(ch, "codec",
                    NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, ch)
                    .setContentTitle("CodecTest").setSmallIcon(android.R.drawable.ic_menu_camera).build();
            startForeground(1, n);
        }
    }

    void line(String s) {
        Log.i(TAG, s);
        report.append(s).append("\n");
    }

    void done() {
        try {
            File out = new File(getExternalFilesDir(null), "resultado.txt");
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(report.toString().getBytes()); fos.close();
        } catch (Throwable t) {}
        try { if (vd != null) vd.release(); } catch (Throwable t) {}
        try { if (enc != null) { enc.stop(); enc.release(); } } catch (Throwable t) {}
        try { if (dec != null) { dec.stop(); dec.release(); } } catch (Throwable t) {}
        try { if (reader != null) reader.close(); } catch (Throwable t) {}
        try { if (readerThread != null) readerThread.quitSafely(); } catch (Throwable t) {}
        try { if (mp != null) mp.stop(); } catch (Throwable t) {}
        stopForeground(true);
        stopSelf();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
