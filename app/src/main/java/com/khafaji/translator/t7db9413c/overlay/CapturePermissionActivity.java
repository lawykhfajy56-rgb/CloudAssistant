package com.khafaji.translator.t7db9413c.overlay;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

public class CapturePermissionActivity extends Activity {
    private static final int REQ = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, TranslatorOverlayService.class)
                    .putExtra(TranslatorOverlayService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(TranslatorOverlayService.EXTRA_DATA, data);
            startForegroundService(svc);
        }
        finish();
    }
}
