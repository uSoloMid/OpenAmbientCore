package com.openambientcore;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.nio.ByteBuffer;

public class AmbilightService extends Service {
    public static final String ACTION_START = "com.openambientcore.START";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_LEDS = "leds";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_BRIGHTNESS = "brightness";

    private static final String CHANNEL_ID = "ambilight_capture";
    private static final int NOTIFICATION_ID = 1001;
    private static final int CAPTURE_WIDTH = 96;
    private static final int CAPTURE_HEIGHT = 54;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private WledUdpClient wled;
    private long lastFrameMs;
    private int ledCount = 150;
    private int frameIntervalMs = 66;
    private int brightness = 45;
    private int[][] smoothed;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        thread = new HandlerThread("AmbilightCapture");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("Capturando pantalla"));

        String ip = intent.getStringExtra(EXTRA_IP);
        ledCount = Math.max(1, intent.getIntExtra(EXTRA_LEDS, 150));
        int fps = Math.max(1, Math.min(30, intent.getIntExtra(EXTRA_FPS, 15)));
        brightness = Math.max(1, Math.min(100, intent.getIntExtra(EXTRA_BRIGHTNESS, 45)));
        frameIntervalMs = 1000 / fps;
        smoothed = new int[ledCount][3];
        wled = new WledUdpClient(ip);

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        startProjection(resultCode, resultData);
        return START_STICKY;
    }

    private void startProjection(int resultCode, Intent resultData) {
        stopProjection();
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        reader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImageAvailable, handler);
        display = projection.createVirtualDisplay(
                "OpenAmbientCore",
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                handler
        );
    }

    private void onImageAvailable(ImageReader imageReader) {
        long now = System.currentTimeMillis();
        if (now - lastFrameMs < frameIntervalMs) {
            Image skipped = imageReader.acquireLatestImage();
            if (skipped != null) skipped.close();
            return;
        }
        lastFrameMs = now;

        Image image = imageReader.acquireLatestImage();
        if (image == null) return;
        try {
            int[][] frame = sampleFrame(image);
            smooth(frame);
            wled.sendDrgb(smoothed);
        } finally {
            image.close();
        }
    }

    private int[][] sampleFrame(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int[][] out = new int[ledCount][3];

        for (int i = 0; i < ledCount; i++) {
            Rect rect = rectForLed(i);
            long r = 0;
            long g = 0;
            long b = 0;
            int samples = 0;
            for (int y = rect.top; y < rect.bottom; y += 2) {
                for (int x = rect.left; x < rect.right; x += 2) {
                    int offset = y * rowStride + x * pixelStride;
                    if (offset + 2 >= buffer.limit()) continue;
                    r += buffer.get(offset) & 0xff;
                    g += buffer.get(offset + 1) & 0xff;
                    b += buffer.get(offset + 2) & 0xff;
                    samples++;
                }
            }
            if (samples == 0) samples = 1;
            out[i][0] = (int) (r / samples) * brightness / 100;
            out[i][1] = (int) (g / samples) * brightness / 100;
            out[i][2] = (int) (b / samples) * brightness / 100;
        }
        return out;
    }

    private Rect rectForLed(int index) {
        int topCount = Math.max(1, Math.round(ledCount * 0.29f));
        int rightCount = Math.max(1, Math.round(ledCount * 0.21f));
        int bottomCount = Math.max(1, Math.round(ledCount * 0.29f));
        int leftCount = Math.max(1, ledCount - topCount - rightCount - bottomCount);
        int band = 7;

        if (index < topCount) {
            int x0 = index * CAPTURE_WIDTH / topCount;
            int x1 = Math.max(x0 + 1, (index + 1) * CAPTURE_WIDTH / topCount);
            return new Rect(x0, 0, x1, band);
        }
        index -= topCount;
        if (index < rightCount) {
            int y0 = index * CAPTURE_HEIGHT / rightCount;
            int y1 = Math.max(y0 + 1, (index + 1) * CAPTURE_HEIGHT / rightCount);
            return new Rect(CAPTURE_WIDTH - band, y0, CAPTURE_WIDTH, y1);
        }
        index -= rightCount;
        if (index < bottomCount) {
            int x1 = CAPTURE_WIDTH - index * CAPTURE_WIDTH / bottomCount;
            int x0 = Math.min(x1 - 1, CAPTURE_WIDTH - (index + 1) * CAPTURE_WIDTH / bottomCount);
            return new Rect(Math.max(0, x0), CAPTURE_HEIGHT - band, Math.min(CAPTURE_WIDTH, x1), CAPTURE_HEIGHT);
        }
        index -= bottomCount;
        int y1 = CAPTURE_HEIGHT - index * CAPTURE_HEIGHT / leftCount;
        int y0 = Math.min(y1 - 1, CAPTURE_HEIGHT - (index + 1) * CAPTURE_HEIGHT / leftCount);
        return new Rect(0, Math.max(0, y0), band, Math.min(CAPTURE_HEIGHT, y1));
    }

    private void smooth(int[][] frame) {
        int keep = 60;
        int take = 40;
        for (int i = 0; i < ledCount; i++) {
            smoothed[i][0] = (smoothed[i][0] * keep + frame[i][0] * take) / 100;
            smoothed[i][1] = (smoothed[i][1] * keep + frame[i][1] * take) / 100;
            smoothed[i][2] = (smoothed[i][2] * keep + frame[i][2] * take) / 100;
        }
    }

    private void stopProjection() {
        if (display != null) {
            display.release();
            display = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
    }

    @Override
    public void onDestroy() {
        stopProjection();
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Ambilight capture",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("OpenAmbientCore")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    private static class Rect {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Rect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
