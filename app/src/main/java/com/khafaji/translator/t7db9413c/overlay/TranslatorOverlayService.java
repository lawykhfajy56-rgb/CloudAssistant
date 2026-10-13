package com.khafaji.translator.t7db9413c.overlay;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TranslatorOverlayService extends Service {

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL_ID = "translator_overlay";
    private static final int NOTIF_ID = 1;

    private WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextRecognizer recognizer;
    private Translator translator;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Image pendingImage;

    private TextView bubble;
    private WindowManager.LayoutParams bubbleParams;
    private FrameLayout overlay;
    private volatile boolean busy = false;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();

        int code = intent == null ? Activity.RESULT_CANCELED
                : intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent == null ? null : getDataExtra(intent);

        if (code != Activity.RESULT_OK || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection == null) startCapture(code, data);
        if (bubble == null) addBubble();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private Intent getDataExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        return intent.getParcelableExtra(EXTRA_DATA);
    }

    private void startCapture(int code, Intent data) {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        MediaProjection mp = mpm.getMediaProjection(code, data);
        mp.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, main);

        DisplayMetrics m = getResources().getDisplayMetrics();
        ImageReader reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            if (pendingImage != null) pendingImage.close();
            pendingImage = img;
        }, main);

        virtualDisplay = mp.createVirtualDisplay("translator_capture",
                m.widthPixels, m.heightPixels, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);
        imageReader = reader;
        projection = mp;
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int rowPadding = plane.getRowStride() - plane.getPixelStride() * image.getWidth();
        Bitmap padded = Bitmap.createBitmap(
                image.getWidth() + rowPadding / plane.getPixelStride(),
                image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(plane.getBuffer());
        return Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
    }

    private void addBubble() {
        int size = dp(56);
        TextView tv = new TextView(this);
        tv.setText("ع");
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(20);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#CC1E88E5"));
        tv.setBackground(bg);

        bubbleParams = new WindowManager.LayoutParams(size, size, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 0;
        bubbleParams.y = dp(200);

        final float[] down = new float[2];
        final int[] start = new int[2];
        final boolean[] moved = {false};
        tv.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    start[0] = bubbleParams.x;
                    start[1] = bubbleParams.y;
                    moved[0] = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - down[0]);
                    int dy = (int) (e.getRawY() - down[1]);
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved[0] = true;
                    bubbleParams.x = start[0] + dx;
                    bubbleParams.y = start[1] + dy;
                    wm.updateViewLayout(v, bubbleParams);
                    break;
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) onBubbleTap();
                    break;
            }
            return true;
        });

        wm.addView(tv, bubbleParams);
        bubble = tv;
    }

    private void onBubbleTap() {
        if (overlay != null) {
            clearOverlay();
            return;
        }
        if (busy) return;

        Image img = pendingImage;
        if (img == null) {
            toast("حرّك الشاشة قليلاً ثم اضغط الفقاعة مرة أخرى");
            return;
        }
        pendingImage = null;
        busy = true;
        Bitmap bmp = imageToBitmap(img);
        img.close();
        translateBitmap(bmp);
    }

    private void translateBitmap(Bitmap bmp) {
        worker.execute(() -> {
            try {
                Tasks.await(translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()));
                Text ocr = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)));

                List<Rect> boxes = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                for (Text.TextBlock block : ocr.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        if (line.getBoundingBox() == null || line.getText().trim().isEmpty()) continue;
                        boxes.add(line.getBoundingBox());
                        texts.add(Tasks.await(translator.translate(line.getText())));
                    }
                }
                main.post(() -> showOverlay(boxes, texts));
            } catch (Exception e) {
                toast("تعذرت الترجمة: " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    private void showOverlay(List<Rect> boxes, List<String> texts) {
        clearOverlay();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setOnClickListener(v -> clearOverlay());

        for (int i = 0; i < boxes.size(); i++) {
            Rect box = boxes.get(i);
            TextView tv = new TextView(this);
            tv.setText(texts.get(i));
            tv.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            tv.setTextDirection(View.TEXT_DIRECTION_RTL);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(Color.BLACK);
            tv.setBackgroundColor(Color.argb(235, 255, 255, 255));
            tv.setPadding(4, 0, 4, 0);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(12f, box.height() * 0.6f));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(box.width(), box.height());
            lp.leftMargin = box.left;
            lp.topMargin = box.top;
            root.addView(tv, lp);
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wm.addView(root, params);
        overlay = root;
    }

    private void clearOverlay() {
        if (overlay != null) {
            wm.removeView(overlay);
            overlay = null;
        }
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "الترجمة الفورية", NotificationManager.IMPORTANCE_LOW));
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("الترجمة الفورية تعمل")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroy() {
        clearOverlay();
        if (bubble != null) wm.removeView(bubble);
        bubble = null;
        if (pendingImage != null) pendingImage.close();
        pendingImage = null;
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        projection = null;
        worker.shutdownNow();
        translator.close();
        recognizer.close();
        super.onDestroy();
    }
}
