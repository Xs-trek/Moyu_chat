package com.moyu.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Short-lived foreground data-transfer service used only while a user-started model turn is active. */
public final class ChatTurnService extends Service {
    private static final String CHANNEL_ID = "moyu_chat_generation";
    private static final int NOTIFICATION_ID = 2411;

    /** Returns false when Android refuses the foreground-service start; callers must not silently assume a lease exists. */
    static boolean start(Context context) {
        try { return context.startForegroundService(new Intent(context, ChatTurnService.class)) != null; }
        catch (Exception ignored) { return false; }
    }

    static void stop(Context context) {
        try { context.stopService(new Intent(context, ChatTurnService.class)); }
        catch (Exception ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Moyu Chat generation", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps a user-started model response running while Moyu Chat is in the background.");
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
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
}
