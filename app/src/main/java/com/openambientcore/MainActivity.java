package com.openambientcore;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 42;
    private static final int REQUEST_NOTIFICATIONS = 43;

    private EditText ipInput;
    private EditText ledInput;
    private EditText fpsInput;
    private EditText brightnessInput;
    private TextView status;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("openambientcore", MODE_PRIVATE);
        setContentView(buildUi());

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff101215);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(34, 34, 34, 34);
        scroll.addView(root);

        TextView title = text("OpenAmbientCore", 28, 0xffffffff);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = text("Prototipo: captura la pantalla, promedia colores de los bordes y los manda a WLED/ESP32.", 15, 0xff9ba7b4);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 8, 0, 26);
        root.addView(subtitle, matchWrap());

        ipInput = input("IP de WLED / ESP32", prefs.getString("ip", "192.168.1.50"));
        ledInput = input("Cantidad de LEDs", String.valueOf(prefs.getInt("leds", 150)));
        fpsInput = input("FPS", String.valueOf(prefs.getInt("fps", 15)));
        brightnessInput = input("Brillo 1-100", String.valueOf(prefs.getInt("brightness", 45)));

        root.addView(ipInput, matchWrap());
        root.addView(ledInput, matchWrap());
        root.addView(fpsInput, matchWrap());
        root.addView(brightnessInput, matchWrap());

        Button test = button("Probar colores WLED");
        test.setOnClickListener(v -> testWled());
        root.addView(test, matchWrap());

        Button start = button("Iniciar Ambilight");
        start.setOnClickListener(v -> requestCapture());
        root.addView(start, matchWrap());

        Button stop = button("Detener");
        stop.setOnClickListener(v -> stopService(new Intent(this, AmbilightService.class)));
        root.addView(stop, matchWrap());

        status = text("Estado: listo", 14, 0xffedf2f7);
        status.setPadding(0, 24, 0, 0);
        root.addView(status, matchWrap());

        TextView note = text("Nota: apps con DRM pueden verse negras para la captura. YouTube/juegos/VLC suelen funcionar mejor.", 13, 0xff9ba7b4);
        note.setPadding(0, 18, 0, 0);
        root.addView(note, matchWrap());

        return scroll;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(4, 1);
        return view;
    }

    private EditText input(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        input.setTextColor(0xffffffff);
        input.setHintTextColor(0xff7b8794);
        input.setPadding(18, 10, 18, 10);
        return input;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 8, 0, 8);
        return params;
    }

    private void requestCapture() {
        savePrefs();
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            status.setText("Estado: permiso de captura cancelado");
            return;
        }

        Intent intent = new Intent(this, AmbilightService.class);
        intent.setAction(AmbilightService.ACTION_START);
        intent.putExtra(AmbilightService.EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(AmbilightService.EXTRA_RESULT_DATA, data);
        intent.putExtra(AmbilightService.EXTRA_IP, ipInput.getText().toString().trim());
        intent.putExtra(AmbilightService.EXTRA_LEDS, parseInt(ledInput.getText().toString(), 150));
        intent.putExtra(AmbilightService.EXTRA_FPS, parseInt(fpsInput.getText().toString(), 15));
        intent.putExtra(AmbilightService.EXTRA_BRIGHTNESS, parseInt(brightnessInput.getText().toString(), 45));

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        status.setText("Estado: capturando en segundo plano");
    }

    private void testWled() {
        savePrefs();
        String ip = ipInput.getText().toString().trim();
        int leds = parseInt(ledInput.getText().toString(), 150);
        int brightness = parseInt(brightnessInput.getText().toString(), 45);
        status.setText("Estado: enviando prueba a WLED");
        new Thread(() -> {
            WledUdpClient client = new WledUdpClient(ip);
            int[][] colors = {
                    {255, 0, 0},
                    {0, 255, 0},
                    {0, 0, 255},
                    {255, 180, 40},
                    {0, 0, 0}
            };
            for (int[] color : colors) {
                int[][] frame = new int[leds][3];
                for (int i = 0; i < leds; i++) {
                    frame[i][0] = color[0] * brightness / 100;
                    frame[i][1] = color[1] * brightness / 100;
                    frame[i][2] = color[2] * brightness / 100;
                }
                client.sendDrgb(frame);
                sleep(700);
            }
            runOnUiThread(() -> status.setText("Estado: prueba enviada"));
        }).start();
    }

    private void savePrefs() {
        prefs.edit()
                .putString("ip", ipInput.getText().toString().trim())
                .putInt("leds", parseInt(ledInput.getText().toString(), 150))
                .putInt("fps", parseInt(fpsInput.getText().toString(), 15))
                .putInt("brightness", parseInt(brightnessInput.getText().toString(), 45))
                .apply();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
