package tn.eluea.kgpt.core.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.ui.chat.AiChatActivity;
import tn.eluea.kgpt.util.Logger;
import tn.eluea.kgpt.util.RootShell;

/**
 * Floating overlay button: tap to take a root screenshot and open the in-app AI chat
 * with the screenshot pre-attached.
 */
public class FloatingAskAiService extends Service {

    public static final String ACTION_START = "tn.eluea.kgpt.action.FLOAT_ASKAI_START";
    public static final String ACTION_STOP = "tn.eluea.kgpt.action.FLOAT_ASKAI_STOP";

    private static final String CHANNEL_ID = "kgpt_float_askai";
    private static final int NOTIF_ID = 22341;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams lp;

    // touch state
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private long downTime;
    private boolean moved;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try { SPManager.init(this); } catch (Throwable ignored) {}
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(getApplicationContext())) {
                // No permission: stop silently.
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        // Keep service alive (best-effort). A small ongoing notification.
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable t) {
            Logger.error("FloatingAskAiService: startForeground failed: " + t);
            stopSelf();
            return START_NOT_STICKY;
        }

        main.post(this::showBubbleIfNeeded);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        main.post(this::hideBubble);
    }

    private void showBubbleIfNeeded() {
        try {
            if (bubble != null && bubble.getParent() != null) return;
            if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;

            bubble = buildBubbleView();

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            lp = new WindowManager.LayoutParams(
                    dp(52),
                    dp(52),
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

            // restore position
            int x = -1;
            int y = -1;
            try {
                SPManager sp = SPManager.getInstance();
                x = sp.getFloatingScreenshotAskX();
                y = sp.getFloatingScreenshotAskY();
            } catch (Throwable ignored) {}

            if (x < 0 || y < 0) {
                // default: right-middle
                int sw = getResources().getDisplayMetrics().widthPixels;
                int sh = getResources().getDisplayMetrics().heightPixels;
                x = Math.max(0, sw - dp(60));
                y = Math.max(0, sh / 2 - dp(60));
            }
            lp.x = x;
            lp.y = y;

            wm.addView(bubble, lp);
        } catch (Throwable t) {
            Logger.error("FloatingAskAiService: showBubble failed: " + t);
            Logger.log(t);
        }
    }

    private void hideBubble() {
        try {
            if (wm != null && bubble != null && bubble.getParent() != null) {
                wm.removeViewImmediate(bubble);
            }
        } catch (Throwable ignored) {}
        bubble = null;
    }

    private View buildBubbleView() {
        FrameLayout container = new FrameLayout(this);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xEE1E88E5); // blue-ish
        bg.setStroke(dp(2), 0x33FFFFFF);
        container.setBackground(bg);
        container.setElevation(dp(12));

        TextView tv = new TextView(this);
        tv.setText("AI");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        container.addView(tv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        container.setOnTouchListener((v, ev) -> {
            if (lp == null || wm == null) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downTime = System.currentTimeMillis();
                    moved = false;
                    downRawX = ev.getRawX();
                    downRawY = ev.getRawY();
                    downX = lp.x;
                    downY = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - downRawX;
                    float dy = ev.getRawY() - downRawY;
                    if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) moved = true;
                    lp.x = downX + (int) dx;
                    lp.y = downY + (int) dy;
                    try { wm.updateViewLayout(bubble, lp); } catch (Throwable ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    long dt = System.currentTimeMillis() - downTime;
                    // Save position
                    try {
                        SPManager.getInstance().setFloatingScreenshotAskPosition(lp.x, lp.y);
                    } catch (Throwable ignored) {}

                    // Click
                    if (!moved && dt < 250) {
                        onBubbleClicked();
                    }
                    return true;
                default:
                    return false;
            }
        });

        container.setOnLongClickListener(v -> {
            // Long press: open settings page
            try {
                Intent i = new Intent();
                i.setClassName(getPackageName(), "tn.eluea.kgpt.ui.lab.screenshotask.ScreenshotAskActivity");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        });

        return container;
    }

    private void onBubbleClicked() {
        // Avoid double-taps
        main.post(() -> {
            try {
                if (bubble != null) bubble.setAlpha(0f);
            } catch (Throwable ignored) {}
        });

        new Thread(() -> {
            try {
                // Give UI a tiny moment to apply alpha before screencap
                try { Thread.sleep(120L); } catch (Throwable ignored) {}

                File dir = new File(getCacheDir(), "ask_image");
                if (!dir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File out = new File(dir, "ask_" + ts + ".png");

                String path = out.getAbsolutePath();
                int code = RootShell.execSu("/system/bin/screencap -p " + shellQuote(path));

                if (code != 0 || !out.exists() || out.length() <= 0) {
                    Logger.error("FloatingAskAiService: screencap failed code=" + code + " path=" + path);
                    restoreBubbleAlpha();
                    return;
                }

                Uri uri = FileProvider.getUriForFile(
                        getApplicationContext(),
                        getPackageName() + ".fileprovider",
                        out
                );

                Intent chat = new Intent(getApplicationContext(), AiChatActivity.class);
                chat.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                chat.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                chat.putExtra(AiChatActivity.EXTRA_PRELOAD_IMAGE_URI, uri.toString());

                // Let the user type the question manually.
                startActivity(chat);
            } catch (Throwable t) {
                Logger.error("FloatingAskAiService: click flow failed: " + t);
                Logger.log(t);
            } finally {
                restoreBubbleAlpha();
            }
        }).start();
    }

    private void restoreBubbleAlpha() {
        main.post(() -> {
            try {
                if (bubble != null) bubble.setAlpha(1f);
            } catch (Throwable ignored) {}
        });
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ui_float_askai_channel_name),
                    NotificationManager.IMPORTANCE_MIN
            );
            ch.setDescription(getString(R.string.ui_float_askai_channel_desc));
            ch.enableLights(false);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {}
    }

    private Notification buildNotification() {
        Intent open = new Intent(getApplicationContext(), tn.eluea.kgpt.ui.main.MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piOpen = PendingIntent.getActivity(
                this,
                100,
                open,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                        : PendingIntent.FLAG_UPDATE_CURRENT)
        );

        Intent stop = new Intent(getApplicationContext(), FloatingAskAiService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(
                this,
                101,
                stop,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                        : PendingIntent.FLAG_UPDATE_CURRENT)
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.ui_float_askai_notif_title))
                .setContentText(getString(R.string.ui_float_askai_notif_text))
                .setContentIntent(piOpen)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .addAction(new NotificationCompat.Action(0, getString(R.string.ui_float_askai_stop), piStop))
                .build();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static String shellQuote(String s) {
        // Wrap in single quotes and escape internal single quotes
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
