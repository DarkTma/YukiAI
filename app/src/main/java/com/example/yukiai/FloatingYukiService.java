package com.example.yukiai;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import java.nio.ByteBuffer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import java.io.IOException;

import android.widget.Toast;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.OutputStream;
import android.util.Log;

public class FloatingYukiService extends Service {

    private GeminiClient npcAI;
    private android.widget.TextView yukiMessage;

    private WindowManager windowManager;
    private View floatingView;

    private MediaProjection mediaProjection;

    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private boolean isScreenshotRequested = false; // Флаг: "Юки, лови кадр!"
    private int screenWidth, screenHeight, screenDensity;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. Создаем обязательное уведомление
        String channelId = "yuki_eye_channel";
        NotificationChannel channel = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channel = new NotificationChannel(
                    channelId, "Yuki Vision", NotificationManager.IMPORTANCE_LOW);
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Юки")
                .setContentText("Смотрю на шахматную доску 👀")
                .setSmallIcon(android.R.drawable.ic_menu_camera) // Временная иконка
                .build();

        // Запускаем сервис в режиме Foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }

        // 2. Получаем доступ к экрану
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

                // ДОБАВИТЬ ЭТУ СТРОКУ:
                setupScanner();
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
        // Очищаем всё по очереди
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        npcAI = new GeminiClient(BuildConfig.GEMINI_API_KEY);

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_yuki, null);
        yukiMessage = floatingView.findViewById(R.id.yuki_message); // <--- Добавь эту строку

        // Настройки окна для отображения поверх других приложений
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // Обязательно для новых Android
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // Чтобы не перехватывать клавиатуру
                PixelFormat.TRANSLUCENT
        );

        // Позиция по умолчанию (например, справа по центру)
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.addView(floatingView, params);

        // Настраиваем закрытие сервиса
        ImageView btnClose = floatingView.findViewById(R.id.btn_close_floating);
        btnClose.setOnClickListener(v -> stopSelf());

        // Настраиваем перетаскивание (Drag & Drop) и клик
        ImageView yukiHead = floatingView.findViewById(R.id.yuki_head);
        yukiHead.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;

                    case MotionEvent.ACTION_UP:
                        int diffX = (int) (event.getRawX() - initialTouchX);
                        int diffY = (int) (event.getRawY() - initialTouchY);

                        // Если палец сдвинулся меньше чем на 10 пикселей, считаем это коротким тапом
                        if (Math.abs(diffX) < 10 && Math.abs(diffY) < 10) {
                            takeScreenshot(); // <--- ВОТ НАШ ВЫЗОВ
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void takeScreenshot() {
        if (mediaProjection == null) return;

        // Прячем Юки
        floatingView.setVisibility(View.INVISIBLE);

        // Даем ей 100 миллисекунд, чтобы полностью исчезнуть с экрана, и поднимаем флаг
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isScreenshotRequested = true;
        }, 100);
    }




    private void saveBitmapToGallery(Bitmap bitmap) {
        ContentValues values = new ContentValues();
        // Имя файла
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "Yuki_Vision_" + System.currentTimeMillis() + ".png");
        // Формат
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        // Папка, где появится картинка (Pictures/YukiVision)
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YukiVision");

        // Даем команду системе создать файл
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                // Записываем наш Bitmap в файл
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                Log.d("YukiVision", "Картинка успешно сохранена в Галерею!");
            } catch (Exception e) {
                e.printStackTrace();
                Log.e("YukiVision", "Ошибка сохранения: " + e.getMessage());
            }
        }
    }

    private void setupScanner() {
        if (mediaProjection == null) return;

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        // Цифра 2 означает, что в памяти хранится максимум 2 кадра одновременно, это безопасно
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "YukiScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null
        );

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                // Получаем свежий кадр
                image = reader.acquireLatestImage();
                if (image != null) {
                    // Если мы нажали на Юки, флаг станет true
                    if (isScreenshotRequested) {
                        isScreenshotRequested = false; // Сразу сбрасываем, чтобы не поймать лишнего

                        Image.Plane[] planes = image.getPlanes();
                        ByteBuffer buffer = planes[0].getBuffer();
                        int pixelStride = planes[0].getPixelStride();
                        int rowStride = planes[0].getRowStride();
                        int rowPadding = rowStride - pixelStride * screenWidth;

                        // Создаем Bitmap
                        Bitmap tempBitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
                        tempBitmap.copyPixelsFromBuffer(buffer);
                        Bitmap finalBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, screenWidth, screenHeight);

                        // Отправляем на проверку (наш Toast)
                        processScreenshot(finalBitmap);

                        // Возвращаем Юки на экран
                        new Handler(Looper.getMainLooper()).post(() -> {
                            floatingView.setVisibility(View.VISIBLE);
                        });
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // ВАЖНО: Закрываем кадр ВСЕГДА, даже если флаг false, чтобы не засорять память
                if (image != null) {
                    image.close();
                }
            }
        }, new Handler(Looper.getMainLooper()));
    }


    private void getBestMoveFromStockfish(String fen) {
        new Handler(Looper.getMainLooper()).post(() -> {
            showYukiMessage("Юки ищет лучший ход... 🧠");
        });

        OkHttpClient client = new OkHttpClient();
        // Кодируем пробелы в URL (важно для FEN)
        String encodedFen = fen.replace(" ", "%20");
        // API Stockfish (глубина просчета 13 - оптимально для скорости и силы)
        String url = "https://stockfish.online/api/s/v2.php?fen=" + encodedFen + "&depth=13";

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("YukiChess", "Ошибка связи со Stockfish: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(() -> {
                    showYukiMessage("Сервер шахмат не отвечает 😵");
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonResponse = response.body().string();
                    Log.d("YukiChess", "Ответ Stockfish: " + jsonResponse);

                    try {
                        JSONObject obj = new JSONObject(jsonResponse);
                        if (obj.getBoolean("success")) {
                            // Строка обычно выглядит как "bestmove e2e4 ponder d7d5"
                            String bestMoveFull = obj.getString("bestmove");
                            String[] parts = bestMoveFull.split(" ");

                            if (parts.length > 1) {
                                String bestMove = parts[1]; // Берем само движение, например "e2e4"

                                // Выводим финальный результат на экран!
                                showYukiMessage("✨ Лучший ход: " + bestMove + " ✨");
                            }
                        }
                    } catch (Exception e) {
                        Log.e("YukiChess", "Ошибка парсинга хода: " + e.getMessage());
                    }
                }
            }
        });
    }


    private void processScreenshot(Bitmap bitmap) {
        Log.d("YukiVision", "Снимок готов, отправляю Юки на анализ...");

        // Показываем Toast, чтобы понимать, что процесс пошел
        new Handler(Looper.getMainLooper()).post(() -> {
            showYukiMessage("Юки думает над позицией...");
        });

        // Очень строгий промпт. Просим только FEN, без лишних слов.
        String prompt = "Extract the FEN string from this chess board image.\n" +
                "Step 1: Write down the 8 ranks from top to bottom using FEN notation (e.g., 'rnbqkbnr', '8', '4p3', etc.).\n" +
                "Step 2: Combine them with '/'.\n" +
                "Step 3: End with exactly: 'RESULT_FEN: [your_combined_string] w - - 0 1'";

        npcAI.generateWithImage(prompt, bitmap, new NpcCallback() {
            @Override
            public void onUpdate(String partialText) {
                // Игнорируем, так как ждем полный ответ
            }

            @Override
            public void onComplete(String finalText) {
                Log.d("YukiChess", "Полный ответ нейросети:\n" + finalText);

                String fenResult = "";
                // Ищем строку, которая начинается с RESULT_FEN:
                String[] lines = finalText.split("\n");
                for (String line : lines) {
                    if (line.trim().startsWith("RESULT_FEN:")) { // <--- ИЗМЕНИЛИ ЗДЕСЬ
                        fenResult = line.replace("RESULT_FEN:", "").trim().replace("\"", "");
                        break;
                    }
                }

                if (!fenResult.isEmpty()) {
                    Log.d("YukiChess", "Вырезанный чистый FEN: " + fenResult);
                    String finalFen = fenResult; // Для передачи в UI поток
                    getBestMoveFromStockfish(finalFen);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(FloatingYukiService.this, "FEN: " + finalFen, Toast.LENGTH_LONG).show();
                    });
                } else {
                    // Если Юки всё равно не выдала маркер RESULT_FEN
                    new Handler(Looper.getMainLooper()).post(() -> {
                        showYukiMessage("Не удалось найти FEN в ответе...");
                    });
                }
            }

            @Override
            public void onError(String errorMsg) {
                Log.e("YukiChess", "Ошибка зрения Юки: " + errorMsg);
                new Handler(Looper.getMainLooper()).post(() -> {
                    showYukiMessage("Ой, я не смогла разглядеть доску");
                });
            }
        });
    }

    private void showYukiMessage(String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            yukiMessage.setText(text);
            yukiMessage.setAlpha(0f);
            yukiMessage.setVisibility(View.VISIBLE);

            // Плавное появление
            yukiMessage.animate().alpha(1f).setDuration(300).start();

            // Скрываем текст через 6 секунд
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                yukiMessage.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                    yukiMessage.setVisibility(View.GONE);
                }).start();
            }, 6000);
        });
    }
}