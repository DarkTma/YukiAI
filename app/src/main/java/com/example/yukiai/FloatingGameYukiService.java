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
import android.widget.RelativeLayout;
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

    private boolean isTextOnLeft = false;
    private int headAnchorX = 0;   // Истинная позиция головы по X
    private int headAnchorY = 250; // Истинная позиция головы по Y
    private int textBubbleWidthPx = 0;

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
    private boolean isDocked = false;

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
                // --- ДОБАВЛЕН ФЛАГ FLAG_LAYOUT_NO_LIMITS ---
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 250; // Сместим чуть ниже при старте

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.addView(floatingView, params);

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;


        ImageView yukiHead = floatingView.findViewById(R.id.yuki_head);
        yukiHead.setImageResource(R.drawable.yuki_chibi);
        Animation floatAnim = AnimationUtils.loadAnimation(this, R.anim.float_anim);
        yukiHead.startAnimation(floatAnim);

        ImageView btnClose = floatingView.findViewById(R.id.btn_close_floating);
        btnClose.setOnClickListener(v -> stopSelf());

        // Вычисляем ширину текстового блока в пикселях (250dp -> px)
        textBubbleWidthPx = (int) (250 * getResources().getDisplayMetrics().density);



        textBubbleWidthPx = (int) (220 * getResources().getDisplayMetrics().density);
        headAnchorX = 0;
        headAnchorY = 250;

        yukiHead.setOnTouchListener(new View.OnTouchListener() {
            private int initialHeadX, initialHeadY;
            private float initialTouchX, initialTouchY;
            private long touchStartTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialHeadX = headAnchorX; // Берем за основу наш якорь
                        initialHeadY = headAnchorY;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchStartTime = System.currentTimeMillis();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int diffX = (int) (event.getRawX() - initialTouchX);
                        int diffY = (int) (event.getRawY() - initialTouchY);

                        if (isDocked && (Math.abs(diffX) > 20 || Math.abs(diffY) > 20)) {
                            undockYuki();
                        }

                        // Сохраняем новую позицию в Якорь
                        headAnchorX = initialHeadX + diffX;
                        headAnchorY = initialHeadY + diffY;
                        refreshWindowPosition(); // Перерисовываем
                        return true;

                    case MotionEvent.ACTION_UP:
                        int upDiffX = (int) (event.getRawX() - initialTouchX);
                        int upDiffY = (int) (event.getRawY() - initialTouchY);
                        long touchDuration = System.currentTimeMillis() - touchStartTime;

                        // --- КЛИК ---
                        if (Math.abs(upDiffX) < 20 && Math.abs(upDiffY) < 20 && touchDuration < 200) {
                            long clickTime = System.currentTimeMillis();
                            if (clickTime - lastClickTime < 300) {
                                mainHandler.removeCallbacks(singleTapRunnable);
                                undockYuki();
                                showInput();
                            } else {
                                singleTapRunnable = () -> {
                                    if (!gameYuki.isBusy() && !isTyping) {
                                        takeScreenshot("");
                                    } else if (gameYuki.isBusy()) {
                                        updateYukiMessageUI("Погоди, я еще думаю... ⏳", false);
                                    }
                                };
                                mainHandler.postDelayed(singleTapRunnable, 300);
                            }
                            lastClickTime = clickTime;
                        }
                        // --- ПРИЛИПАНИЕ ---
                        else {
                            float finalFingerX = event.getRawX();
                            int edgeMargin = 120;
                            int headWidth = yukiHead.getWidth() > 0 ? yukiHead.getWidth() : (int)(80 * getResources().getDisplayMetrics().density);

                            if (finalFingerX < edgeMargin) {
                                dockYuki(true);
                                headAnchorX = 0;
                            } else if (finalFingerX > screenWidth - edgeMargin) {
                                dockYuki(false);
                                headAnchorX = screenWidth - headWidth;
                            } else {
                                undockYuki();
                            }
                            refreshWindowPosition(); // Окончательная отрисовка
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

    private void dockYuki(boolean isLeft) {
        if (!isDocked) {
            isDocked = true;
            ImageView yukiHead = floatingView.findViewById(R.id.yuki_head);
            yukiHead.setImageResource(R.drawable.yuki_chibi_peeking); // Твоя картинка

            // --- МАГИЯ ПОВОРОТА ---
            // Если Юки слева, макушка должна смотреть в стену (или наоборот, зависит от исходника картинки).
            // Попробуй 90f и -90f. Если она повернется шеей в центр экрана, просто поменяй их местами!
            yukiHead.setRotation(isLeft ? 90f : -90f);

            // Прячем текст моментально
            yukiMessage.setVisibility(View.GONE);
        }
    }

    private void undockYuki() {
        if (isDocked) {
            isDocked = false;
            ImageView yukiHead = floatingView.findViewById(R.id.yuki_head);
            // Возвращаем стандартную картинку
            yukiHead.setImageResource(R.drawable.yuki_chibi);

            // --- СБРАСЫВАЕМ ПОВОРОТ ---
            yukiHead.setRotation(0f);
        }
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
        if (autoCaptureHandler != null && autoCaptureRunnable != null) {
            autoCaptureHandler.removeCallbacks(autoCaptureRunnable);
        }

        inputLayout.setVisibility(View.VISIBLE);
        refreshWindowPosition();

        WindowManager.LayoutParams currentParams = (WindowManager.LayoutParams) floatingView.getLayoutParams();
        currentParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        currentParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS; // <--- ДОБАВИЛИ ЗДЕСЬ
        windowManager.updateViewLayout(floatingView, currentParams);
        yukiInput.requestFocus();
    }

    private void hideInput() {
        isTyping = false;
        inputLayout.setVisibility(View.GONE);
        refreshWindowPosition();

        WindowManager.LayoutParams currentParams = (WindowManager.LayoutParams) floatingView.getLayoutParams();
        currentParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        currentParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS; // <--- ДОБАВИЛИ ЗДЕСЬ
        windowManager.updateViewLayout(floatingView, currentParams);

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

            // --- ВЕРНУЛИ ЭТИ ДВЕ СТРОЧКИ ---
            // Вычисляем скорость печати (Длительность аудио / Количество символов)
            long durationMs = mediaPlayer.getDuration();
            long delayPerChar = durationMs / Math.max(fullText.length(), 1);
            // --------------------------------

            // Сбрасываем старые таймеры
            if (hideTextRunnable != null) mainHandler.removeCallbacks(hideTextRunnable);
            if (typeWriterRunnable != null) mainHandler.removeCallbacks(typeWriterRunnable);

            mediaPlayer.start(); // ЗАПУСКАЕМ ГОЛОС

            // --- ПРОВЕРКА НА ПРИЛИПАНИЕ К КРАЮ ---
            if (!isDocked) {
                // Показываем пустое облачко только если Юки не прилипла к краю
                yukiMessage.setText("");
                if (yukiMessage.getVisibility() != View.VISIBLE) {
                    yukiMessage.setAlpha(0f);
                    yukiMessage.setVisibility(View.VISIBLE);
                    refreshWindowPosition();
                    yukiMessage.animate().alpha(1f).setDuration(200).start();
                }

                // ЗАПУСКАЕМ ПЕЧАТЬ ТЕКСТА
                typeWriterRunnable = new Runnable() {
                    int index = 0;
                    @Override
                    public void run() {
                        if (index <= fullText.length()) {
                            yukiMessage.setText(fullText.substring(0, index));
                            index++;
                            mainHandler.postDelayed(this, delayPerChar);
                        } else {
                            hideTextRunnable = () -> yukiMessage.animate().alpha(0f).setDuration(300).withEndAction(() -> yukiMessage.setVisibility(View.GONE)).start();
                            mainHandler.postDelayed(hideTextRunnable, 8000);
                        }
                    }
                };
                mainHandler.post(typeWriterRunnable);
            }

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

        // 1. Создаем новую "пленку" (ImageReader) под новые размеры экрана
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        // 2. УМНОЕ СОЗДАНИЕ/ОБНОВЛЕНИЕ
        if (virtualDisplay == null) {
            // Если камеры еще нет (при первом запуске) — создаем
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "GameYukiCapture",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null
            );
        } else {
            // Если камера уже есть (при повороте экрана) — просто меняем её настройки!
            virtualDisplay.resize(screenWidth, screenHeight, screenDensity);
            virtualDisplay.setSurface(imageReader.getSurface());
        }

        // 3. Подключаем слушатель кадров
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

                    // Берем реальные размеры текущего кадра, чтобы не вылезти за пределы!
                    int imgWidth = image.getWidth();
                    int imgHeight = image.getHeight();
                    int rowPadding = rowStride - pixelStride * imgWidth;

                    Bitmap tempBitmap = Bitmap.createBitmap(imgWidth + rowPadding / pixelStride, imgHeight, Bitmap.Config.ARGB_8888);
                    tempBitmap.copyPixelsFromBuffer(buffer);
                    Bitmap finalBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, imgWidth, imgHeight);

                    mainHandler.post(() -> floatingView.setVisibility(View.VISIBLE));

                    // Отдаем картинку мозгам
                    gameYuki.lookAtScreen(finalBitmap, currentPrompt);
                    currentPrompt = "";
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (image != null) image.close();
            }
        }, mainHandler);
    }

    private void updateYukiMessageUI(String text, boolean isStreaming) {

        if (isDocked) return;

        mainHandler.post(() -> {
            if (hideTextRunnable != null) {
                mainHandler.removeCallbacks(hideTextRunnable);
            }

            yukiMessage.setText(text);
            if (yukiMessage.getVisibility() != View.VISIBLE) {
                yukiMessage.setAlpha(0f);
                yukiMessage.setVisibility(View.VISIBLE);
                refreshWindowPosition(); // <--- ДОБАВИТЬ СЮДА
                yukiMessage.animate().alpha(1f).setDuration(200).start();
            } else {
                refreshWindowPosition(); // <--- ДОБАВИТЬ СЮДА ТОЖЕ (если текст просто обновился)
            }

            if (!isStreaming) {
                hideTextRunnable = () -> {
                    yukiMessage.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                        yukiMessage.setVisibility(View.GONE);
                        refreshWindowPosition(); // <--- ДОБАВИТЬ СЮДА (после исчезновения)
                    }).start();
                };
                mainHandler.postDelayed(hideTextRunnable, 8000);
            }
        });
    }

    private void updateLayoutOrientation(boolean textOnLeft, boolean isTextVisible) {
        isTextOnLeft = textOnLeft;

        ImageView yukiHead = floatingView.findViewById(R.id.yuki_head);
        RelativeLayout.LayoutParams headParams = (RelativeLayout.LayoutParams) yukiHead.getLayoutParams();
        RelativeLayout.LayoutParams messageParams = (RelativeLayout.LayoutParams) yukiMessage.getLayoutParams();
        RelativeLayout.LayoutParams inputParams = (RelativeLayout.LayoutParams) inputLayout.getLayoutParams();

        // 1. Очищаем ВСЕ старые правила (чтобы ничего не конфликтовало)
        headParams.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        headParams.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
        messageParams.removeRule(RelativeLayout.LEFT_OF);
        messageParams.removeRule(RelativeLayout.RIGHT_OF);
        messageParams.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
        inputParams.removeRule(RelativeLayout.LEFT_OF);
        inputParams.removeRule(RelativeLayout.RIGHT_OF);
        inputParams.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);

        float density = getResources().getDisplayMetrics().density;
        int padLarge = (int) (45 * density);
        int padNormal = (int) (12 * density);
        int overlapPx = (int) (30 * density); // Нахлест облачка на голову (30dp)

        if (textOnLeft) {
            // ЮКИ СПРАВА, ТЕКСТ СЛЕВА
            messageParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE);
            inputParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE);

            // МАГИЯ: Вместо выравнивания вправо, мы просто толкаем Юки отступом слева!
            if (isTextVisible) {
                headParams.leftMargin = textBubbleWidthPx - overlapPx;
            } else {
                headParams.leftMargin = 0;
            }

            messageParams.setMargins(0, 0, -overlapPx, 10);
            yukiMessage.setPadding(padNormal, padNormal, padLarge, padNormal);
            inputLayout.setPadding(padNormal, padNormal, padLarge, padNormal);
        } else {
            // ЮКИ СЛЕВА, ТЕКСТ СПРАВА
            headParams.leftMargin = 0; // Сбрасываем отступ

            messageParams.addRule(RelativeLayout.RIGHT_OF, R.id.yuki_head);
            inputParams.addRule(RelativeLayout.RIGHT_OF, R.id.yuki_head);

            messageParams.setMargins(-overlapPx, 0, 0, 10);
            yukiMessage.setPadding(padLarge, padNormal, padNormal, padNormal);
            inputLayout.setPadding(padLarge, padNormal, padNormal, padNormal);
        }

        yukiHead.setLayoutParams(headParams);
        yukiMessage.setLayoutParams(messageParams);
        inputLayout.setLayoutParams(inputParams);
        floatingView.requestLayout(); // Просим Android применить изменения
    }

    private void refreshWindowPosition() {
        if (floatingView == null || windowManager == null) return;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) floatingView.getLayoutParams();

        boolean isTextVisible = yukiMessage.getVisibility() == View.VISIBLE || inputLayout.getVisibility() == View.VISIBLE;

        // 1. Решаем, с какой стороны рисовать текст
        boolean shouldBeLeft = headAnchorX > (screenWidth / 2);

        // 2. Переворачиваем макет (передаем видимость!)
        updateLayoutOrientation(shouldBeLeft, isTextVisible);

        // 3. Вычисляем точную позицию X
        int targetX = headAnchorX;
        float density = getResources().getDisplayMetrics().density;
        int overlapPx = (int) (30 * density);

        if (isTextVisible && shouldBeLeft) {
            // Если текст слева, сдвигаем окно левее, чтобы голова осталась на месте
            targetX = headAnchorX - (textBubbleWidthPx - overlapPx);
        }

        // 4. УМНЫЙ АВТО-СДВИГ (ЗАЩИТА ОТ ВЫХОДА ЗА ЭКРАН)
        ImageView yukiHead = floatingView.findViewById(R.id.yuki_head);
        int headWidthPx = yukiHead.getWidth() > 0 ? yukiHead.getWidth() : (int) (80 * density);

        // Считаем реальную ширину окна с учетом нахлеста
        int currentTotalWidth = isTextVisible ? (textBubbleWidthPx + headWidthPx - overlapPx) : headWidthPx;
        int margin = 20;

        if (!isDocked) {
            // Если Юки в свободном полете — держим её в 20 пикселях от края
            if (targetX < margin) {
                targetX = margin;
            } else if (targetX + currentTotalWidth > screenWidth - margin) {
                targetX = screenWidth - currentTotalWidth - margin;
            }
        } else {
            // Если Юки прилеплена к стене (docked) — разрешаем стоять вплотную (0 пикселей)
            if (targetX < 0) {
                targetX = 0;
            } else if (targetX + currentTotalWidth > screenWidth) {
                targetX = screenWidth - currentTotalWidth;
            }
        }

        // 5. Применяем координаты
        params.x = targetX;
        params.y = headAnchorY;
        windowManager.updateViewLayout(floatingView, params);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (windowManager != null && floatingView != null) {
            // 1. Заново замеряем новые размеры экрана
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;

            // --- ИСПРАВЛЕННЫЙ БЛОК ---
            // Мы больше не убиваем virtualDisplay! Только меняем ImageReader.
            if (imageReader != null) {
                imageReader.setOnImageAvailableListener(null, null);
                imageReader.close();
            }
            setupScanner();
            // --------------------------

            // Находим ширину головы для расчетов
            ImageView yukiHead = floatingView.findViewById(R.id.yuki_head);
            int headWidthPx = yukiHead.getWidth() > 0 ? yukiHead.getWidth() : (int) (80 * getResources().getDisplayMetrics().density);

            // 2. Спасаем Юки, если она потерялась при повороте!
            if (isDocked) {
                // Если она была прилеплена, проверяем к какому краю.
                // Если якорь больше 0 (значит была справа) — перелепляем к НОВОМУ правому краю.
                if (headAnchorX > 0) {
                    headAnchorX = screenWidth - headWidthPx;
                }
            } else {
                // Если она просто висела в воздухе, проверяем, не оказалась ли она ЗА границами нового экрана
                if (headAnchorX > screenWidth - headWidthPx) {
                    headAnchorX = screenWidth - headWidthPx;
                }
                if (headAnchorY > screenHeight - headWidthPx) {
                    headAnchorY = screenHeight - headWidthPx;
                }
            }

            // 3. Заставляем систему перерисовать окно на новых координатах
            refreshWindowPosition();
        }
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