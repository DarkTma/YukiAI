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

    private int textBubbleWidthPx = 0;    // Ширина облачка в пикселях

    // --- МОЗГИ ЮКИ ---
    private GeminiClient geminiClient;
    private GameYuki gameYuki;

    private boolean isTextOnLeft = false;
    private int headScreenX = 0; // Реальная координата головы X
    private int headScreenY = 250; // Реальная координата головы Y
    private int offsetPx = 0; // Размер сдвига окна

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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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



        // --- ВСТАВЬ ЭТО В onCreate ПЕРЕД yukiHead.setOnTouchListener ---
        // Считаем размер облачка (250dp - 30dp нахлест) в пикселях
        offsetPx = (int) (220 * getResources().getDisplayMetrics().density);
        int headWidthPx = (int) (80 * getResources().getDisplayMetrics().density);

        headScreenX = 0;
        headScreenY = 250;
        params.x = headScreenX;
        params.y = headScreenY;
        // -------------------------------------------------------------

        // 4. Логика перетаскивания и клика
        yukiHead.setOnTouchListener(new View.OnTouchListener() {
            private int initialHeadX, initialHeadY;
            private float initialTouchX, initialTouchY;
            private long touchStartTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialHeadX = headScreenX; // Берем за основу координаты головы
                        initialHeadY = headScreenY;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchStartTime = System.currentTimeMillis();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int currentDiffX = (int) (event.getRawX() - initialTouchX);
                        int currentDiffY = (int) (event.getRawY() - initialTouchY);

                        if (isDocked && (Math.abs(currentDiffX) > 20 || Math.abs(currentDiffY) > 20)) {
                            undockYuki();
                        }

                        // Обновляем координаты именно ГОЛОВЫ
                        headScreenX = initialHeadX + currentDiffX;
                        headScreenY = initialHeadY + currentDiffY;

                        // Проверяем, нужно ли перевернуть интерфейс
                        updateLayoutOrientation(headScreenX > screenWidth / 2);

                        // Двигаем само окно
                        updateWindowPosition();
                        return true;

                    case MotionEvent.ACTION_UP:
                        int diffX = (int) (event.getRawX() - initialTouchX);
                        int diffY = (int) (event.getRawY() - initialTouchY);
                        long touchDuration = System.currentTimeMillis() - touchStartTime;

                        if (Math.abs(diffX) < 20 && Math.abs(diffY) < 20 && touchDuration < 200) {
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
                        else {
                            // Логика прилипания
                            int edgeMargin = 120;
                            if (headScreenX < edgeMargin) {
                                dockYuki(true);
                                headScreenX = 0;
                            } else if (headScreenX > screenWidth - headWidthPx - edgeMargin) {
                                dockYuki(false);
                                headScreenX = screenWidth - headWidthPx;
                            } else {
                                undockYuki();
                            }
                            updateLayoutOrientation(headScreenX > screenWidth / 2);
                            updateWindowPosition();
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

            // Если у тебя разные картинки для левого и правого края:
            // yukiHead.setImageResource(isLeft ? R.drawable.yuki_peeking_left : R.drawable.yuki_peeking_right);

            // Если картинка одна:
            yukiHead.setImageResource(R.drawable.yuki_chibi_peeking); // ЗАМЕНИ НА СВОЕ ИМЯ КАРТИНКИ

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
        if (autoCaptureHandler != null && autoCaptureRunnable != null) autoCaptureHandler.removeCallbacks(autoCaptureRunnable);

        inputLayout.setVisibility(View.VISIBLE);
        updateWindowPosition(); // <--- ЭТО ВАЖНО

        WindowManager.LayoutParams currentParams = (WindowManager.LayoutParams) floatingView.getLayoutParams();
        currentParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windowManager.updateViewLayout(floatingView, currentParams);
        yukiInput.requestFocus();
    }

    private void hideInput() {
        isTyping = false;
        inputLayout.setVisibility(View.GONE);
        updateWindowPosition(); // <--- ЭТО ВАЖНО

        WindowManager.LayoutParams currentParams = (WindowManager.LayoutParams) floatingView.getLayoutParams();
        currentParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
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

        if (isDocked) return;

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

    private void updateLayoutOrientation(boolean textOnLeft) {
        if (isTextOnLeft == textOnLeft) return; // Ничего не делаем, если сторона не поменялась
        isTextOnLeft = textOnLeft;

        // Находим картинку Юки (чтобы менять её правила тоже)
        ImageView yukiHead = floatingView.findViewById(R.id.yuki_head);

        RelativeLayout.LayoutParams headParams = (RelativeLayout.LayoutParams) yukiHead.getLayoutParams();
        RelativeLayout.LayoutParams messageParams = (RelativeLayout.LayoutParams) yukiMessage.getLayoutParams();
        RelativeLayout.LayoutParams inputParams = (RelativeLayout.LayoutParams) inputLayout.getLayoutParams();

        // 1. Очищаем абсолютно ВСЕ старые правила позиционирования
        headParams.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
        headParams.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);

        messageParams.removeRule(RelativeLayout.LEFT_OF);
        messageParams.removeRule(RelativeLayout.RIGHT_OF);

        inputParams.removeRule(RelativeLayout.LEFT_OF);
        inputParams.removeRule(RelativeLayout.RIGHT_OF);
        inputParams.removeRule(RelativeLayout.ALIGN_LEFT);
        inputParams.removeRule(RelativeLayout.ALIGN_RIGHT);

        // Переводим dp в пиксели для красивых отступов (чтобы текст не лез под голову)
        float density = getResources().getDisplayMetrics().density;
        int padLarge = (int) (45 * density); // Большой отступ (пустое место под голову Юки)
        int padNormal = (int) (12 * density); // Обычный отступ для краев

        if (textOnLeft) {
            // ЮКИ СПРАВА, ТЕКСТ СЛЕВА
            // Жестко прибиваем Юки к правому краю контейнера (чтобы текст не обрезался)
            headParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, RelativeLayout.TRUE);

            messageParams.addRule(RelativeLayout.LEFT_OF, R.id.yuki_head);
            inputParams.addRule(RelativeLayout.LEFT_OF, R.id.yuki_head);

            messageParams.setMargins(0, 0, -30, 10);

            // МАГИЯ ОТСТУПОВ: Делаем правый край текста пустым, чтобы голова Юки его не закрывала
            yukiMessage.setPadding(padNormal, padNormal, padLarge, padNormal);
            inputLayout.setPadding(padNormal, padNormal, padLarge, padNormal);
        } else {
            // ЮКИ СЛЕВА, ТЕКСТ СПРАВА
            // Прибиваем Юки к левому краю контейнера
            headParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE);

            messageParams.addRule(RelativeLayout.RIGHT_OF, R.id.yuki_head);
            inputParams.addRule(RelativeLayout.RIGHT_OF, R.id.yuki_head);

            messageParams.setMargins(-30, 0, 0, 10);

            // МАГИЯ ОТСТУПОВ: Делаем левый край текста пустым
            yukiMessage.setPadding(padLarge, padNormal, padNormal, padNormal);
            inputLayout.setPadding(padLarge, padNormal, padNormal, padNormal);
        }

        // Применяем новые параметры
        yukiHead.setLayoutParams(headParams);
        yukiMessage.setLayoutParams(messageParams);
        inputLayout.setLayoutParams(inputParams);

        // Заставляем Android перерисовать окно с новыми размерами
        floatingView.requestLayout();
        updateWindowPosition();
    }

    private void updateWindowPosition() {
        if (floatingView == null || windowManager == null) return;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) floatingView.getLayoutParams();

        // Проверяем, видимо ли сейчас хоть что-то из текста
        boolean isTextVisible = yukiMessage.getVisibility() == View.VISIBLE || inputLayout.getVisibility() == View.VISIBLE;

        // Если текст СЛЕВА и он ВИДИМ, нам нужно сдвинуть левую границу окна левее,
        // чтобы освободить место для облачка, не сдвигая саму Юки.
        if (isTextOnLeft && isTextVisible) {
            params.x = headScreenX - offsetPx;
        } else {
            params.x = headScreenX; // Если текст справа или скрыт, окно начинается от Юки
        }
        params.y = headScreenY;

        windowManager.updateViewLayout(floatingView, params);
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