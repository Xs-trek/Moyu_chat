package com.moyu.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Short-lived foreground data-transfer service used only while a user-started model turn is active. */
public final class ChatTurnService extends Service {
    private static final String CHANNEL_ID = "moyu_chat_generation";
    private static final String RESULT_CHANNEL_ID = "moyu_chat_results";
    private static final int NOTIFICATION_ID = 2411;
    /** Intent extra on a result notification: the session to open. */
    static final String EXTRA_SESSION = "com.moyu.chat.extra.SESSION";
    /** Safety cap; normal release happens much earlier when the last active user turn finishes. */
    static final long WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 60L * 1000L;
    private PowerManager.WakeLock wakeLock;

    /** Returns false when Android refuses the foreground-service start; callers must not silently assume a lease exists. */
    static boolean start(Context context) {
        try { return context.startForegroundService(new Intent(context, ChatTurnService.class)) != null; }
        catch (Exception ignored) { return false; }
    }

    /**
     * Tells the user a turn finished (or failed) while the app was in the background; tapping opens the session.
     * One notification per session: a newer result replaces the older one. Without the Android 13+ permission
     * nothing is shown.
     */
    static void notifySettled(Context context, String sessionId, String title, boolean failed) {
        if (sessionId == null || sessionId.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(RESULT_CHANNEL_ID, "回答结果", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("应用在后台时，回答完成或失败的提醒");
            manager.createNotificationChannel(channel);
            Intent open = new Intent(context, MainActivity.class).putExtra(EXTRA_SESSION, sessionId)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int id = sessionId.hashCode();
            PendingIntent content = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, RESULT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_chat_status)
                    .setContentTitle(failed ? "回答失败" : "回答完成")
                    .setContentText(title == null || title.trim().isEmpty() ? "新会话" : title.trim())
                    .setCategory(failed ? Notification.CATEGORY_ERROR : Notification.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(content)
                    .build();
            manager.notify("result", id, notification);
        } catch (RuntimeException ignored) { }
    }

    static void stop(Context context) {
        try { context.stopService(new Intent(context, ChatTurnService.class)); }
        catch (Exception ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "后台生成", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("应用在后台时保持正在生成的回答继续运行");
        manager.createNotificationChannel(channel);

        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chat_status)
                .setContentTitle("Moyu Chat")
                .setContentText("正在生成回复")
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID, notification);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":chat-generation");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        PowerManager.WakeLock held = wakeLock;
        wakeLock = null;
        if (held != null && held.isHeld()) try { held.release(); } catch (RuntimeException ignored) { }
        super.onDestroy();
    }
}
