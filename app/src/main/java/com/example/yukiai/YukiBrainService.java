package com.example.yukiai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

public class YukiBrainService extends LifecycleService {

    private static final String TAG = "YukiBrainService";
    private static final String CHANNEL_ID = "yuki_brain_channel";
    private static final int NOTIF_ID = 1;

    // client -> service
    public static final int MSG_REGISTER_CLIENT = 1;
    public static final int MSG_ASK = 2;          // data: "text", "vision"
    public static final int MSG_RELOAD_MODEL = 3; // модель уже скопирована на диск, просто перезагрузить

    // service -> client
    public static final int MSG_THINKING = 10;
    public static final int MSG_RESPONSE = 11;
    public static final int MSG_ERROR = 12;
    public static final int MSG_MODEL_LOADED = 13; // ok/fail после init или reload

    private Boolean lastModelLoadedState = null;

    private final Messenger inboundMessenger = new Messenger(new IncomingHandler());
    private Messenger clientMessenger;

    private YukiBrainManager brainManager;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();

        Log.e("YukiBrainService", "=== СЕРВИС СТАРТУЕТ ===");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YukiAI:brain");
        wakeLock.acquire();

        brainManager = new YukiBrainManager(getApplicationContext(), success -> {
            lastModelLoadedState = success;
            Log.e("YukiBrainService", "=== 6: model loaded: " + success + " ===");
            sendToClient(MSG_MODEL_LOADED, success ? "1" : "0");
        });

        Log.e("YukiBrainService", "=== 7: YukiBrainManager created ===");


    }

    private class IncomingHandler extends Handler {
        IncomingHandler() { super(Looper.getMainLooper()); }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_CLIENT:
                    clientMessenger = msg.replyTo;
                    // Если результат загрузки/перезагрузки уже готов — отдаём сразу
                    if (lastModelLoadedState != null) {
                        sendToClient(MSG_MODEL_LOADED, lastModelLoadedState ? "1" : "0");
                    }
                    break;

                case MSG_ASK: {
                    Bundle data = msg.getData();
                    String text = data.getString("text");
                    byte[] imageBytes = data.getByteArray("image");
                    Bitmap image = imageBytes != null
                            ? BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length)
                            : null;
                    handleAsk(text, image);
                    break;
                }

                case MSG_RELOAD_MODEL:
                    handleReload();
                    break;
            }
        }
    }

    private void handleAsk(String text, @Nullable Bitmap image) {
        brainManager.askYuki(text, image, new YukiBrainManager.BrainCallback() {
            @Override public void onThinking() { sendToClient(MSG_THINKING, null); }
            @Override public void onResponse(String t) { sendToClient(MSG_RESPONSE, t); }
            @Override public void onError(String error) { sendToClient(MSG_ERROR, error); }
        });
    }

    private void handleReload() {
        lastModelLoadedState = null; // на время перезагрузки статус снова "неизвестен"
        brainManager.reloadModel(getApplicationContext(), success -> {
            lastModelLoadedState = success;
            sendToClient(MSG_MODEL_LOADED, success ? "1" : "0");
        });
    }

    private void sendToClient(int what, @Nullable String payload) {
        if (clientMessenger == null) return;
        try {
            Message msg = Message.obtain(null, what);
            Bundle b = new Bundle();
            b.putString("text", payload);
            msg.setData(b);
            clientMessenger.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "Client dead", e);
            clientMessenger = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return inboundMessenger.getBinder();
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Yuki AI Brain", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Юки думает")
                .setContentText("Мозг загружен и готов отвечать")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (brainManager != null) brainManager.shutdown();
        super.onDestroy();
    }
}