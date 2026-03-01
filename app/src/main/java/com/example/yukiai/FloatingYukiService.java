package com.example.yukiai;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
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

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import android.util.Base64;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;



public class FloatingYukiService extends Service {

    private GeminiClient npcAI;
    private android.widget.TextView yukiMessage;

    boolean isPlayingBlack = true; // Поставь true, если играешь за черных

    private WindowManager windowManager;
    private View floatingView;

    // Добавь это в начало класса FloatingYukiService
// --- ГЛОБАЛЬНЫЕ ПЕРЕМЕННЫЕ ДЛЯ СТРЕЛКИ ---
    private boolean lastWasBlack = false;
    private double lastStepX = 0;
    private double lastStepY = 0;
    private double lastMinX = 0;
    private double lastMinY = 0;
    private int lastCropY = 0;
    // (lastSquareSize и lastVerticalOffset можно удалить, они больше не нужны)

    private int lastVerticalOffset = 0;

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

        // 1. Создаем вьюшку ОДИН РАЗ
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_yuki, null);

        // Инициализируем сообщение
        yukiMessage = floatingView.findViewById(R.id.yuki_message);

        // 2. Настройки окна
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.addView(floatingView, params);

        // 3. Настраиваем ПЕРСОНАЖА (анимация и внешний вид)
        ImageView yukiHead = floatingView.findViewById(R.id.yuki_head);

        // Запускаем анимацию "парения"
        Animation floatAnim = AnimationUtils.loadAnimation(this, R.anim.float_anim);
        yukiHead.startAnimation(floatAnim);

        // 4. Кнопка закрытия
        ImageView btnClose = floatingView.findViewById(R.id.btn_close_floating);
        btnClose.setOnClickListener(v -> stopSelf());

        // 5. Логика перетаскивания и клика (Screenshot)
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

                        // Если палец почти не двигался — это клик!
                        if (Math.abs(diffX) < 10 && Math.abs(diffY) < 10) {
                            takeScreenshot();
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


    private void getChessPiecesFromRoboflow(Bitmap bitmap) {
        showYukiMessage("Сканирую доску... 👁️");

        new Thread(() -> {
            try {
                // 1. УМНАЯ ОБРЕЗКА (Настраиваем прицел)
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                int size = width;

// Находим стандартную точку по центру экрана
                int baseStartY = (height - size) / 2;

// --- НАСТРОЙКА ВЕРХА ---
// Увеличь это число (например: 50, 100, 150), чтобы отрезать больше пикселей СВЕРХУ.
                int cropTop = 80;
                int startY = baseStartY + cropTop;

                if (startY < 0) startY = 0; // Защита от выхода за экран

// --- НАСТРОЙКА НИЗА ---
// Твое идеальное значение, оставляем как есть!
                int cropBottom = -100;

// Итоговая высота = (исходный размер) минус (то что отрезали сверху) минус (то что отрезали снизу)
                int finalHeight = size - cropTop - cropBottom;

// Защита, чтобы не улететь за нижний край экрана
                if (startY + finalHeight > height) {
                    finalHeight = height - startY;
                }

                this.lastCropY = startY;

// Вырезаем идеальный кусок: начинаем ниже (startY) и делаем картинку короче (finalHeight)
                Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, startY, size, finalHeight);

//// Не забудь проверить результат в галерее!
//                saveBitmapToGallery(croppedBitmap);


// 2. Сжимаем уже ОБРЕЗАННУЮ картинку и переводим в текст
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] imageBytes = baos.toByteArray();
                String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                // 3. Формируем URL прямой конечной точки
                String apiKey = "gmyaW6cnsiQZ0OgZHVTg";
// Меняем только центральную часть на chess-tsb0d
                String url = "https://detect.roboflow.com/chess-tsb0d/1?api_key=" + apiKey;

                // 3. Отправляем Base64 строку в правильном формате
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build();

                // ВАЖНО: Указываем серверу, что мы шлем закодированный текст
                MediaType mediaType = MediaType.parse("application/x-www-form-urlencoded");

                // Если Android Studio подчеркнет RequestBody.create,
                // поменяй местами аргументы: RequestBody.create(mediaType, base64Image)
                RequestBody body = RequestBody.create(base64Image, mediaType);

                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .build();

                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        Log.e("YukiChess", "Ошибка сети Roboflow: " + e.getMessage());
                        showYukiMessage("Связь с глазами потеряна 😵");
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        if (response.isSuccessful() && response.body() != null) {
                            String responseData = response.body().string();
                            Log.d("YukiChess", "Ответ Roboflow: " + responseData);
                            showYukiMessage("Фигуры вижу! Математика... 🧮");
                            parseRoboflowToFEN(responseData);
                        } else {
                            String err = response.body() != null ? response.body().string() : "Unknown";
                            Log.e("YukiChess", "Ошибка распознавания: " + err);
                            showYukiMessage("Не могу разобрать доску 😔");
                        }
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }


    private void showVisualMove(String move) {
        // ЗАМЕНА ЗДЕСЬ: используем lastStepX вместо lastSquareSize
        if (move == null || move.length() < 4 || lastStepX == 0) return;

        // 1. Разбираем ход (например, "e2e4")
        String from = move.substring(0, 2);
        String to = move.substring(2, 4);

        // 2. Считаем экранные координаты
        float startX = calculateX(from);
        float startY = calculateY(from);
        float endX = calculateX(to);
        float endY = calculateY(to);

        // 3. Создаем оверлей на весь экран
        MoveOverlayView arrowView = new MoveOverlayView(this, startX, startY, endX, endY);

        WindowManager.LayoutParams arrowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // ВОТ ИСПРАВЛЕНИЕ: Добавляем FLAG_LAYOUT_NO_LIMITS и FLAG_LAYOUT_IN_SCREEN
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        new Handler(Looper.getMainLooper()).post(() -> {
            windowManager.addView(arrowView, arrowParams);

            // Удаляем стрелку через 4 секунды, чтобы не мозолила глаза
            new Handler().postDelayed(() -> {
                try { windowManager.removeView(arrowView); } catch (Exception ignored) {}
            }, 4000);
        });
    }

//    // Математика перевода клетки в пиксели
//    private float calculateX(String cell) {
//        int col = cell.charAt(0) - 'a';
//        if (lastWasBlack) col = 7 - col; // Переворачиваем, если играем черными
//        return (float) (col * lastSquareSize + lastSquareSize / 2);
//    }
//
//    private float calculateY(String cell) {
//        int row = 8 - (cell.charAt(1) - '0');
//        if (lastWasBlack) row = 7 - row; // Переворачиваем, если играем черными
//        return (float) (row * lastSquareSize + lastSquareSize / 2 + lastVerticalOffset);
//    }

    private void parseRoboflowToFEN(String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);
            JSONArray predictions = json.getJSONArray("predictions");

            SharedPreferences prefs = getSharedPreferences("npc_settings", Context.MODE_PRIVATE);
            boolean isPlayingBlack = prefs.getBoolean("playing_as_black", false);
            this.lastWasBlack = isPlayingBlack;

            if (predictions.length() == 0) {
                showYukiMessage("Юки ничего не видит... 🦎");
                return;
            }

            // --- ЖЕСТКАЯ МАТЕМАТИЧЕСКАЯ СЕТКА ---
            // Берем точный размер картинки из ответа нейросети (обычно 1280)
            int imageWidth = json.getJSONObject("image").getInt("width");
            double squareSize = imageWidth / 8.0; // Идеальный размер одной клетки

            // Сохраняем для неоновой стрелки
            this.lastStepX = squareSize;
            this.lastStepY = squareSize;
            this.lastMinX = 0; // Сетка начинается ровно с левого края картинки
            this.lastMinY = 0; // Сетка начинается ровно с верхнего края картинки

            // Создаем чистую доску
            char[][] board = new char[8][8];
            double[][] confMap = new double[8][8];

            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) board[r][c] = '1';
            }

            // Расставляем фигуры
            for (int i = 0; i < predictions.length(); i++) {
                JSONObject obj = predictions.getJSONObject(i);
                String className = obj.getString("class");
                double conf = obj.getDouble("confidence");

                if (className.equals("board") || className.equals("eb") || className.equals("ew") || conf < 0.5) {
                    continue;
                }

                double x = obj.getDouble("x");
                double y = obj.getDouble("y");

                // Просто делим координату на размер клетки
                int col = (int) (x / squareSize);
                int row = (int) (y / squareSize);

                // ИДЕАЛЬНАЯ ЗАЩИТА:
                // Если съеденная пешка валяется внизу, ее row будет 8 или 9.
                // Условие ниже просто проигнорирует её!
                if (row >= 0 && row < 8 && col >= 0 && col < 8) {
                    if (conf > confMap[row][col]) {
                        board[row][col] = getPieceChar(className);
                        confMap[row][col] = conf;
                    }
                }
            }

            // --- ВИЗУАЛЬНАЯ ПРОВЕРКА ---
            StringBuilder debugBoard = new StringBuilder("\n--- ФИНАЛЬНАЯ ПРОВЕРКА ДОСКИ ---\n");
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) debugBoard.append(board[r][c]).append(" ");
                debugBoard.append("\n");
            }
            Log.d("YukiChess", debugBoard.toString());

// 4. Сборка FEN с учетом стороны игрока
            StringBuilder fenBuilder = new StringBuilder();

// Если играем за белых: идем от ряда 0 до 7
// Если за черных: идем от ряда 7 до 0 (переворачиваем доску)
            int startRow = isPlayingBlack ? 7 : 0;
            int endRow = isPlayingBlack ? -1 : 8;
            int step = isPlayingBlack ? -1 : 1;

            for (int r = startRow; r != endRow; r += step) {
                int emptyCount = 0;

                // Колонки тоже переворачиваем, если играем за черных
                int startCol = isPlayingBlack ? 7 : 0;
                int endCol = isPlayingBlack ? -1 : 8;
                int colStep = isPlayingBlack ? -1 : 1;

                for (int c = startCol; c != endCol; c += colStep) {
                    if (board[r][c] == '1') {
                        emptyCount++;
                    } else {
                        if (emptyCount > 0) {
                            fenBuilder.append(emptyCount);
                            emptyCount = 0;
                        }
                        fenBuilder.append(board[r][c]);
                    }
                }
                if (emptyCount > 0) fenBuilder.append(emptyCount);
                if (r != (endRow - step)) fenBuilder.append("/");
            }

// 5. Финальная строка: меняем 'w' на 'b', если ход черных
            String turn = isPlayingBlack ? "b" : "w";

// Убираем рокировки (заменяем на '-'), чтобы Stockfish не ругался на позиции
            String finalFen = fenBuilder.toString() + " " + turn + " - - 0 1";

            Log.d("YukiChess", "⚡ ИТОГОВЫЙ FEN (" + (isPlayingBlack ? "Черные" : "Белые") + "): " + finalFen);



            getBestMoveFromStockfish(finalFen);

        } catch (Exception e) {
            Log.e("YukiChess", "Ошибка: " + e.getMessage());
        }
    }

    private float calculateX(String cell) {
        int col = cell.charAt(0) - 'a';
        if (lastWasBlack) col = 7 - col;

        // Прицел: левый край + (номер колонки * размер клетки) + ПОЛОВИНА КЛЕТКИ
        return (float) (lastMinX + (col * lastStepX) + (lastStepX / 2));
    }

    private float calculateY(String cell) {
        int row = 8 - (cell.charAt(1) - '0');
        if (lastWasBlack) row = 7 - row;

        // Тот самый калибратор статус-бара
        float arrowOffsetUp = 130f;

        // Прицел: верхний край + (номер ряда * размер клетки) + ПОЛОВИНА КЛЕТКИ + смещение кропа
        return (float) (lastMinY + (row * lastStepY) + (lastStepY / 2) + lastCropY) - arrowOffsetUp;
    }

    private char getPieceChar(String className) {
        if (className == null) return '1';

        switch (className) {
            // Черные фигуры (строчные буквы для FEN)
            case "bP": return 'p'; // Пешка
            case "bR": return 'r'; // Ладья
            case "bN": return 'n'; // Конь
            case "bB": return 'b'; // Слон
            case "bQ": return 'q'; // Ферзь
            case "bK": return 'k'; // Король

            // Белые фигуры (заглавные буквы для FEN)
            case "wP": return 'P'; // Пешка
            case "wR": return 'R'; // Ладья
            case "wN": return 'N'; // Конь
            case "wB": return 'B'; // Слон
            case "wQ": return 'Q'; // Ферзь
            case "wK": return 'K'; // Король

            // На случай, если прилетит рамка доски или мусор
            default: return '1';
        }
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

                                // Выводим финальный результат на экран через Handler!
                                new Handler(Looper.getMainLooper()).post(() -> {
                                    showYukiMessage("✨ Лучший ход: " + bestMove + " ✨");
                                    showVisualMove(bestMove);
                                });
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
        Log.d("YukiVision", "Снимок готов, отправляю в Roboflow...");

        // Запускаем наше новое 100% точное зрение
        getChessPiecesFromRoboflow(bitmap);
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