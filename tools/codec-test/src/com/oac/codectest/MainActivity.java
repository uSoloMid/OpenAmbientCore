package com.oac.codectest;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Pide permiso de captura (MediaProjection) y arranca CapService, que corre el
 * pipeline H.264 (metodo "Codec compatibility"). Si appops PROJECT_MEDIA=allow,
 * en algunos TCL el dialogo se auto-acepta; si no, el usuario acepta una vez.
 */
public class MainActivity extends Activity {
    static final int REQ = 1001;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ && res == RESULT_OK && data != null) {
            Intent svc = new Intent(this, CapService.class);
            svc.putExtra("rc", res);
            svc.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        }
        finish();
    }
}
