package com.khafaji.translator.t7db9413c;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.khafaji.translator.t7db9413c.overlay.CapturePermissionActivity;
import com.khafaji.translator.t7db9413c.overlay.TranslatorOverlayService;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_MIC = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btnStart).setOnClickListener(v -> start());
        findViewById(R.id.btnStop).setOnClickListener(v ->
                stopService(new Intent(this, TranslatorOverlayService.class)));
    }

    private void start() {
        // 1) إذن الميكروفون للمحادثة الصوتية
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        // 2) إذن العرض فوق التطبيقات للفقاعة
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "امنح صلاحية العرض فوق التطبيقات ثم اضغط ابدأ", Toast.LENGTH_LONG).show();
            return;
        }
        // 3) إذن التقاط الشاشة ثم تشغيل السحابة
        startActivity(new Intent(this, CapturePermissionActivity.class));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                start();
            } else {
                Toast.makeText(this, "يلزم إذن الميكروفون لتعمل المحادثة", Toast.LENGTH_LONG).show();
            }
        }
    }
}
