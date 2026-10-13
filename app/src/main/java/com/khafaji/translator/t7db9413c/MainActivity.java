package com.khafaji.translator.t7db9413c;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.khafaji.translator.t7db9413c.overlay.CapturePermissionActivity;
import com.khafaji.translator.t7db9413c.overlay.TranslatorOverlayService;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btnStart).setOnClickListener(v -> start());
        findViewById(R.id.btnStop).setOnClickListener(v ->
                stopService(new Intent(this, TranslatorOverlayService.class)));
    }

    private void start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this,
                    "امنح صلاحية العرض فوق التطبيقات ثم اضغط ابدأ مرة أخرى",
                    Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, CapturePermissionActivity.class));
    }
}
