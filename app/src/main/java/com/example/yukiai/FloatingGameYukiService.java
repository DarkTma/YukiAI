package com.example.yukiai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
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
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import android.media.MediaPlayer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;



import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

public class FloatingGameYukiService extends Service {


    // Добавь это к остальным переменным:
    private MediaPlayer mediaPlayer;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Runnable typeWriterRunnable; // Для эффекта печатной машинки

    // --- МОЗГИ ЮКИ ---
    private GeminiClient geminiClient;
    private GameYuki gameYuki;

    // --- АВТО-СКРИНШОТЫ ---
    private Handler autoCaptureHandler = new Handler(Looper.getMainLooper());
    private Runnable autoCaptureRunnable;
    private final int AUTO_CAPTURE_INTERVAL = 30000; // 10 секунд (в миллисекундах)

    // --- UI ЭЛЕМЕНТЫ ---
    private WindowManager windowManager;
    private View floatingView;
    private TextView yukiMessage;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable hideTextRunnable;

    // --- ЗАХВАТ ЭКРАНА ---
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private boolean isScreenshotRequested = false;
    private int screenWidth, screenHeight, screenDensity;

    // --- ДЛЯ ТЕКСТОВОГО ВВОДА ---
    private View inputLayout;
    private android.widget.EditText yukiInput;
    private ImageView btnSend;
    private boolean isTyping = false; // Флаг: печатаем ли мы сейчас текст?
    private String currentPrompt = ""; // То, что мы написали

    // --- ДЛЯ ДВОЙНОГО КЛИКА ---
    private long lastClickTime = 0;
    private Runnable singleTapRunnable;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String channelId = "game_yuki_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Game Yuki", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Юки (Геймер)")
                .setContentText("Смотрю твою игру 🎮")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION); // Используем ID 2, чтобы не конфликтовать с шахматами
        }

        if (intent != null) {
            int resultCode = intent.getIntExtra("code", -1);
            Intent data = intent.getParcelableExtra("data");

            if (resultCode == -1 && data != null) {
                MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mediaProjection = projectionManager.getMediaProjection(resultCode, data);

                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        mediaProjection = null;
                    }
                }, new Handler(Looper.getMainLooper()));

                setupScanner();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 2. Создаем интерфейс (используем тот же layout, что и у шахматной)
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_yuki, null);

        // 1. Инициализируем мозги Юки
        geminiClient = new GeminiClient(BuildConfig.GEMINI_API_KEY);
        gameYuki = new GameYuki(geminiClient, new NpcCallback() {
            @Override
            public void onUpdate(String partialText) {
                // Больше НЕ выводим текст по буквам от сети.
                // Просто показываем статус, чтобы ты знал, что она не зависла.
                updateYukiMessageUI("Юки придумывает ответ... 💭", true);
            }

            @Override
            public void onComplete(String finalText) {
                // Текст готов! Но мы его пока не показываем.
                // Меняем статус и отправляем текст на сервер озвучки.
                updateYukiMessageUI("Записываю голосовуху... 🎙️", true);
                speakCoquiAndShowText(finalText);
            }

            @Override
            public void onError(String errorMsg) {
                updateYukiMessageUI("Ошибка: " + errorMsg, false);
            }
        });

        // Инициализируем сообщение
        yukiMessage = floatingView.findViewById(R.id.yuki_message);

        // ДОБАВЛЯЕМ ЭТУ СТРОКУ, чтобы текст реагировал на свайпы пальцем:
        yukiMessage.setMovementMethod(new android.text.method.ScrollingMovementMethod());

        // --- ДОБАВИТЬ ВОТ ЭТОТ БЛОК ---
        inputLayout = floatingView.findViewById(R.id.input_layout);
        yukiInput = floatingView.findViewById(R.id.yuki_input);
        btnSend = floatingView.findViewById(R.id.btn_send);

        // Логика кнопки "Отправить"
        btnSend.setOnClickListener(v -> {
            String text = yukiInput.getText().toString().trim();
            yukiInput.setText(""); // Очищаем поле
            hideInput(); // Прячем клавиатуру и поле ввода

            if (!text.isEmpty()) {
                takeScreenshot(text); // Делаем скриншот с нашим текстом
            }
        });
        // --------------------------------


        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 250; // Сместим чуть ниже при старте

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.addView(floatingView, params);

        ImageView yukiHead = floatingView.findViewById(R.id.yuki_head);
        Animation floatAnim = AnimationUtils.loadAnimation(this, R.anim.float_anim);
        yukiHead.startAnimation(floatAnim);

        ImageView btnClose = floatingView.findViewById(R.id.btn_close_floating);
        btnClose.setOnClickListener(v -> stopSelf());

        // 4. Логика перетаскивания и клика
        yukiHead.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private long touchStartTime; // Время, когда коснулись экрана

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchStartTime = System.currentTimeMillis(); // Засекаем время старта
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;

                    case MotionEvent.ACTION_UP:
                        int diffX = (int) (event.getRawX() - initialTouchX);
                        int diffY = (int) (event.getRawY() - initialTouchY);
                        long touchDuration = System.currentTimeMillis() - touchStartTime;

                        if (Math.abs(diffX) < 20 && Math.abs(diffY) < 20 && touchDuration < 200) {
                            long clickTime = System.currentTimeMillis();

                            // Если между кликами прошло меньше 300 мс — это ДВОЙНОЙ КЛИК
                            if (clickTime - lastClickTime < 300) {
                                mainHandler.removeCallbacks(singleTapRunnable); // Отменяем обычный скриншот
                                showInput(); // Открываем чат
                            } else {
                                // Это ОДИНАРНЫЙ КЛИК. Ждем 300 мс, вдруг будет второй
                                singleTapRunnable = () -> {
                                    if (!gameYuki.isBusy() && !isTyping) {
                                        takeScreenshot(""); // Обычный комментарий (без текста)
                                    } else if (gameYuki.isBusy()) {
                                        updateYukiMessageUI("Погоди, я еще думаю... ⏳", false);
                                    }
                                };
                                mainHandler.postDelayed(singleTapRunnable, 300);
                            }
                            lastClickTime = clickTime;
                        }
                        return true;
                }
                return false;
            }
        });

        // Пробуждаем Юки
        gameYuki.wakeUp();

        // --- ЗАПУСК АВТО-ТАЙМЕРА ---
        autoCaptureRunnable = new Runnable() {
            @Override
            public void run() {
                // Если Юки свободна И мы сейчас не печатаем ей текст
                if (mediaProjection != null && gameYuki != null && !gameYuki.isBusy() && !isTyping) {
                    takeScreenshot("");
                }
                autoCaptureHandler.postDelayed(this, AUTO_CAPTURE_INTERVAL);
            }
        };
        // Даем небольшую задержку перед первым автоматическим скрином (например, 5 секунд)
        autoCaptureHandler.postDelayed(autoCaptureRunnable, 5000);
    }

    private void takeScreenshot(String prompt) {
        if (mediaProjection == null) return;

        this.currentPrompt = prompt; // Сохраняем текст пользователя

        // Сбрасываем таймер авто-скриншота
        if (autoCaptureHandler != null && autoCaptureRunnable != null) {
            autoCaptureHandler.removeCallbacks(autoCaptureRunnable);
            autoCaptureHandler.postDelayed(autoCaptureRunnable, AUTO_CAPTURE_INTERVAL);
        }

        floatingView.setVisibility(View.INVISIBLE);
        mainHandler.postDelayed(() -> isScreenshotRequested = true, 100);
    }

    private void showInput() {
        isTyping = true;

        // ОСТАНАВЛИВАЕМ авто-таймер, чтобы Юки не фоткала, пока мы пишем
        if (autoCaptureHandler != null && autoCaptureRunnable != null) {
            autoCaptureHandler.removeCallbacks(autoCaptureRunnable);
        }

        inputLayout.setVisibility(View.VISIBLE);

        // МАГИЯ: Убираем флаг FLAG_NOT_FOCUSABLE, чтобы Android разрешил открыть клавиатуру
        WindowManager.LayoutParams currentParams = (WindowManager.LayoutParams) floatingView.getLayoutParams();
        currentParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windowManager.updateViewLayout(floatingView, currentParams);

        yukiInput.requestFocus();
    }

    private void hideInput() {
        isTyping = false;
        inputLayout.setVisibility(View.GONE);

        // МАГИЯ: Возвращаем флаг FLAG_NOT_FOCUSABLE, чтобы окно не мешало играть
        WindowManager.LayoutParams currentParams = (WindowManager.LayoutParams) floatingView.getLayoutParams();
        currentParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windowManager.updateViewLayout(floatingView, currentParams);

        // ВОЗОБНОВЛЯЕМ авто-таймер с нуля
        if (autoCaptureHandler != null && autoCaptureRunnable != null) {
            autoCaptureHandler.removeCallbacks(autoCaptureRunnable);
            autoCaptureHandler.postDelayed(autoCaptureRunnable, AUTO_CAPTURE_INTERVAL);
        }
    }


    private void speakCoquiAndShowText(String text) {
        Log.d("YukiVoice", "🎙️ Начинаю процесс озвучки текста: " + text);

        executor.execute(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(180, TimeUnit.SECONDS).build();

                JSONObject json = new JSONObject();
                json.put("text", text);
                json.put("language", "ru");
                json.put("speaker_wav", "voices/roxy.wav");
                json.put("speed", 1.1);

                Log.d("YukiVoice", "🌐 Отправляю POST-запрос на сервер Coqui...");
                RequestBody body = RequestBody.create(json.toString().getBytes(StandardCharsets.UTF_8),
                        MediaType.parse("application/json; charset=utf-8"));
                Request request = new Request.Builder().url("http://91.205.196.207:5002/api/tts").post(body).build();

                Response response = client.newCall(request).execute();

                if (!response.isSuccessful() || response.body() == null) {
                    Log.e("YukiVoice", "❌ Ошибка сервера! Код ответа: " + response.code());
                    // В случае ошибки выводим текст без голоса
                    mainHandler.post(() -> updateYukiMessageUI(text, false));
                    return;
                }

                Log.d("YukiVoice", "✅ Ответ получен. Сохраняю аудиофайл...");
                File tempFile = File.createTempFile("yuki_voice", ".wav", getCacheDir());
                try (InputStream is = response.body().byteStream(); FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                }
                Log.d("YukiVoice", "💾 Файл успешно сохранен: " + tempFile.getAbsolutePath());

                // Запускаем печатную машинку и звук
                mainHandler.post(() -> {
                    Log.d("YukiVoice", "▶️ Запускаю плеер и анимацию текста");
                    playAudioAndTypeWriter(tempFile.getAbsolutePath(), text);
                });

            } catch (Exception e) {
                Log.e("YukiVoice", "💥 Критическая ошибка при работе с сетью/аудио: " + e.getMessage(), e);
                mainHandler.post(() -> updateYukiMessageUI(text, false));
            }
        });
    }

    private void playAudioAndTypeWriter(String audioPath, String fullText) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release(); // Очищаем старый плеер
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(audioPath);
            mediaPlayer.prepare();

            // Сбрасываем старые таймеры
            if (hideTextRunnable != null) mainHandler.removeCallbacks(hideTextRunnable);
            if (typeWriterRunnable != null) mainHandler.removeCallbacks(typeWriterRunnable);

            // Показываем пустое облачко
            yukiMessage.setText("");
            if (yukiMessage.getVisibility() != View.VISIBLE) {
                yukiMessage.setAlpha(0f);
                yukiMessage.setVisibility(View.VISIBLE);
                yukiMessage.animate().alpha(1f).setDuration(200).start();
            }

            // Вычисляем скорость печати (Длительность аудио / Количество символов)
            long durationMs = mediaPlayer.getDuration();
            long delayPerChar = durationMs / Math.max(fullText.length(), 1);

            mediaPlayer.start(); // ЗАПУСКАЕМ ГОЛОС

            // ЗАПУСКАЕМ ПЕЧАТЬ ТЕКСТА
            typeWriterRunnable = new Runnable() {
                int index = 0;
                @Override
                public void run() {
                    if (index <= fullText.length()) {
                        yukiMessage.setText(fullText.substring(0, index));
                        index++;
                        // Запускаем следующую букву
                        mainHandler.postDelayed(this, delayPerChar);
                    } else {
                        // Текст дописан (и аудио как раз закончилось).
                        // Запускаем таймер на скрытие облачка через 8 секунд.
                        hideTextRunnable = () -> yukiMessage.animate().alpha(0f).setDuration(300).withEndAction(() -> yukiMessage.setVisibility(View.GONE)).start();
                        mainHandler.postDelayed(hideTextRunnable, 8000);
                    }
                }
            };
            mainHandler.post(typeWriterRunnable);

        } catch (Exception e) {
            e.printStackTrace();
            updateYukiMessageUI(fullText, false);
        }
    }


    private void setupScanner() {
        if (mediaProjection == null) return;

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "GameYukiCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null
        );

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null && isScreenshotRequested) {
                    isScreenshotRequested = false;

                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * screenWidth;

                    Bitmap tempBitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
                    tempBitmap.copyPixelsFromBuffer(buffer);
                    Bitmap finalBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, screenWidth, screenHeight);

                    mainHandler.post(() -> floatingView.setVisibility(View.VISIBLE));

                    // Отдаем картинку мозгам GameYuki
// Отдаем картинку мозгам GameYuki вместе с твоим текстом!
                    gameYuki.lookAtScreen(finalBitmap, currentPrompt);
                    currentPrompt = ""; // Очищаем после отправки
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (image != null) image.close();
            }
        }, mainHandler);
    }

    private void updateYukiMessageUI(String text, boolean isStreaming) {
        mainHandler.post(() -> {
            if (hideTextRunnable != null) {
                mainHandler.removeCallbacks(hideTextRunnable);
            }

            yukiMessage.setText(text);

            if (yukiMessage.getVisibility() != View.VISIBLE) {
                yukiMessage.setAlpha(0f);
                yukiMessage.setVisibility(View.VISIBLE);
                yukiMessage.animate().alpha(1f).setDuration(200).start();
            }

            if (!isStreaming) {
                hideTextRunnable = () -> {
                    yukiMessage.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                        yukiMessage.setVisibility(View.GONE);
                    }).start();
                };
                mainHandler.postDelayed(hideTextRunnable, 8000);
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Останавливаем таймер
        if (autoCaptureHandler != null && autoCaptureRunnable != null) {
            autoCaptureHandler.removeCallbacks(autoCaptureRunnable);
        }

        if (floatingView != null) windowManager.removeView(floatingView);
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
        }
        if (mediaProjection != null) mediaProjection.stop();
        if (gameYuki != null) gameYuki.sleep();

        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}