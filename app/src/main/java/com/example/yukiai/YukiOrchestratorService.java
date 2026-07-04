package com.example.yukiai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class YukiOrchestratorService extends Service {
    private static final String CHANNEL_ID = "YukiServerChannel";
    private YukiServer server;
    private YukiBrainManager brain;
    private YukiLocalVision vision;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // 1. Инициализируем зрение
        vision = new YukiLocalVision(this);

        // 2. Инициализируем мозг
        brain = new YukiBrainManager(this, success -> {
            if (success) {
                // 3. Запускаем сервер на порту 8080, когда мозг готов
                try {
                    server = new YukiServer(8080, brain, vision);
                    server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Yuki AI Server")
                .setContentText("Юки слушает на порту 8080...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();

        startForeground(1, notification);
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID, "Yuki AI Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(serviceChannel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        if (brain != null) brain.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}