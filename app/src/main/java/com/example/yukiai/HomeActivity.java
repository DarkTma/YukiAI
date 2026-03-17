package com.example.yukiai;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import android.provider.Settings;
import android.net.Uri;
import android.media.projection.MediaProjectionManager;




public class HomeActivity extends AppCompatActivity {

    // --- UI Элементы ---
    private VideoView bgVideoView;
    private MediaPlayer mediaPlayerBackground; // Отдельный плеер для видео фона
    private Surface videoSurface;
    private ImageView bgImageView;
    private View touchLayer;
    private TextView textDialogue; // Оставляем ОДНУ переменную для текста
    private TextView speakerName;
    private ImageView scrollImage; // Добавил, так как использовалось в коде, но не было объявлено

    // --- Логика ---
    private GestureDetector gestureDetector;
    private static final int REQ_RECORD_AUDIO = 1001;
    private SpeechRecognizer speechRecognizer;

    private static final int REQUEST_MEDIA_PROJECTION_CHESS = 1001;
    private static final int REQUEST_MEDIA_PROJECTION_GAME = 1002;

    // --- AI и Настройки ---
    private GeminiClient npcAI; // Не забудь инициализировать!
    private String teacherSettings =
            "SYSTEM INSTRUCTION:\n" +
                    "Ты — Юки. Ты — дружелюбный репетитор и собеседник для практики языка.\n" +
                    "Отвечай кратко (до 5 предложений), позитивно.";

    // --- Переменные для анимации текста ---
    private List<String> replyChunks = new ArrayList<>();
    private int currentChunkIndex = 0;
    private Handler handler = new Handler(Looper.getMainLooper());
    private int charIndex = 0;
    private String currentText = "";
    private boolean textFullyDisplayed = false;

    private int pendingProjectionCode = -1;

    private boolean isSpeaking = false;
    private int[] idleVideos = {R.raw.yawn};
    // ДОБАВИТЬ ЭТУ СТРОКУ:
    private int currentVideoResId = -1;

    // --- Аудио ---
    private String lastAudioPath = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // --- Память диалога ---
    private String lastPlayerText = "";
    private String lastText; // Для хранения последнего текста (fallback)
    private final Deque<DialoguePair> memory = new ArrayDeque<>();
    private static final int MEMORY_LIMIT = 5;
    private boolean isDialogueActive = false;
    private LinearLayout dialogueBox;

    private Handler idleHandler = new Handler(Looper.getMainLooper());
    private Runnable idleRunnable;
    private final long IDLE_DELAY = 20000; // 20 секунд


    private MediaProjectionManager projectionManager;
    private static final int REQUEST_MEDIA_PROJECTION = 1005;

    private void playBackgroundVideo(int videoResId, boolean isLooping) {
        runOnUiThread(() -> {
            // Запоминаем, какое видео сейчас включилось
            currentVideoResId = videoResId;

            bgVideoView.setAlpha(0f);
            bgVideoView.setVisibility(View.VISIBLE);

            Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + videoResId);
            bgVideoView.setVideoURI(uri);

            bgVideoView.setOnPreparedListener(mp -> {
                // Отключаем звук
                mp.setVolume(0f, 0f);

                float videoWidth = mp.getVideoWidth();
                float videoHeight = mp.getVideoHeight();

                // Берем абсолютные (реальные) размеры экрана телефона
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                float screenWidth = metrics.widthPixels;
                float screenHeight = metrics.heightPixels;

                // 1. Делаем саму "рамку" VideoView СТРОГО по размеру экрана
                android.view.ViewGroup.LayoutParams params = bgVideoView.getLayoutParams();
                params.width = (int) screenWidth;
                params.height = (int) screenHeight;
                bgVideoView.setLayoutParams(params);

                // Обязательно сбрасываем сдвиг (который мы делали в прошлой версии)
                bgVideoView.setTranslationX(0f);
                bgVideoView.setTranslationY(0f);

                // 2. Узнаем, как Android изначально попытался вписать видео (с черными полосами)
                float fitScale = Math.min(screenWidth / videoWidth, screenHeight / videoHeight);
                float drawnWidth = videoWidth * fitScale;
                float drawnHeight = videoHeight * fitScale;

                // 3. Вычисляем идеальный ЗУМ, чтобы видео покрыло весь экран без черных полос
                float scale = Math.max(screenWidth / drawnWidth, screenHeight / drawnHeight);

                // 4. Применяем зум. Так как точка масштабирования (pivot) по умолчанию находится
                // ровно в центре экрана, видео ИДЕАЛЬНО обрежется по бокам!
                bgVideoView.setScaleX(scale);
                bgVideoView.setScaleY(scale);

                mp.setLooping(isLooping);
                bgVideoView.start();

                // Плавное появление
                bgVideoView.animate()
                        .alpha(1f)
                        .setDuration(500)
                        .start();

                bgImageView.animate()
                        .alpha(0f)
                        .setDuration(500)
                        .withEndAction(() -> bgImageView.setVisibility(View.INVISIBLE))
                        .start();
            });
            bgVideoView.setOnCompletionListener(mp -> {
                if (!isLooping) {
                    switchToDefaultAnimation();
                    resetIdleTimer();
                }
            });

            bgVideoView.setOnErrorListener((mp, what, extra) -> {
                Log.e("YukiDebug", "Ошибка VideoView: " + what + ", " + extra);
                switchToDefaultAnimation();
                return true;
            });
        });
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // 1. Инициализация UI
        bgVideoView = findViewById(R.id.bgVideoView);
        bgImageView = findViewById(R.id.bgImageView);
        touchLayer = findViewById(R.id.touchLayer);
        dialogueBox = findViewById(R.id.dialogueBox);
        speakerName = findViewById(R.id.speakerName);
        textDialogue = findViewById(R.id.textDialogue);


        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // --- ДОБАВИТЬ ВОТ ЭТОТ БЛОК ---
        // Жестко фиксируем размер VideoView по реальным пикселям экрана
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);

        android.view.ViewGroup.LayoutParams params = bgVideoView.getLayoutParams();
        params.width = metrics.widthPixels;
        params.height = metrics.heightPixels;
        bgVideoView.setLayoutParams(params);

        // (Опционально) То же самое для bgImageView, чтобы картинка тоже не прыгала
        android.view.ViewGroup.LayoutParams imgParams = bgImageView.getLayoutParams();
        imgParams.width = metrics.widthPixels;
        imgParams.height = metrics.heightPixels;
        bgImageView.setLayoutParams(imgParams);
        // -------------------------------

        // 2. Инициализация AI и кнопок
        npcAI = new GeminiClient(BuildConfig.GEMINI_API_KEY);
        findViewById(R.id.btnSettings).setOnClickListener(v -> openSettingsDialog());
        findViewById(R.id.btnPlayVoice).setOnClickListener(v -> replayLastAudio());
        findViewById(R.id.btnTranslateUI).setOnClickListener(v -> showTranslationMenu());
        findViewById(R.id.btnCheckGrammar).setOnClickListener(v -> checkGrammar());
        findViewById(R.id.btnMinimize).setOnClickListener(v -> hideDialogue());

        // 3. Настройка жестов (Остается как было)
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                openNpcDialog();
                return true;
            }
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                handleGeneralTap();
                return true;
            }
        });

        touchLayer.setOnTouchListener((v, event) -> {
            resetIdleTimer();
            gestureDetector.onTouchEvent(event);
            return true;
        });

        dialogueBox.setOnClickListener(v -> {
            resetIdleTimer();
            handleGeneralTap();
        });

        // 4. Запуск таймера
        // Теперь нам не нужно ждать "готовности" поверхности.
        // VideoView сам разберется, когда начать играть после вызова playBackgroundVideo.
        idleRunnable = this::startIdleAnimation;
        switchToDefaultAnimation();
        resetIdleTimer();
    }







    // Запуск шахматной Юки
    private void startFloatingYuki() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 2002);
        } else {
            // Передаем код CHESS
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION_CHESS);
        }
    }

    // Запуск геймерской Юки
    private void startGameYuki() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            // Запрашиваем окно поверх других
            startActivityForResult(intent, 2002);
        } else {
            // Передаем код GAME
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION_GAME);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 1. Если только что разрешили рисовать поверх окон
        if (requestCode == 2002) {
            if (Settings.canDrawOverlays(this)) {
                // Если разрешение дали, запускаем запись экрана с нужным кодом!
                if (pendingProjectionCode != -1) {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), pendingProjectionCode);
                    pendingProjectionCode = -1; // Сбрасываем память
                }
            } else {
                animateText("Мне нужно разрешение, чтобы появляться поверх других окон! 🥺");
            }
        }

        // 2. Если разрешили запись экрана для ШАХМАТ
        if (requestCode == REQUEST_MEDIA_PROJECTION_CHESS) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, FloatingYukiService.class);
                serviceIntent.putExtra("code", resultCode);
                serviceIntent.putExtra("data", data);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } else {
                animateText("Без доступа к экрану я не смогу увидеть доску... ♟️");
            }
        }

        // 3. Если разрешили запись экрана для ИГР
        if (requestCode == REQUEST_MEDIA_PROJECTION_GAME) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, FloatingGameYukiService.class); // Наш новый сервис!
                serviceIntent.putExtra("code", resultCode);
                serviceIntent.putExtra("data", data);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } else {
                animateText("Без доступа к экрану я не смогу комментировать игру... 🎮");
            }
        }
    }



    private void startIdleAnimation() {
        if (isSpeaking) return;
        int randomVideo = idleVideos[new Random().nextInt(idleVideos.length)];
        playBackgroundVideo(randomVideo, false);
    }

    private void resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable);

        // ВАЖНОЕ ИЗМЕНЕНИЕ: Возвращаем дефолт ТОЛЬКО если сейчас играет НЕ он
        if (!isSpeaking && bgVideoView.getVisibility() == View.VISIBLE && currentVideoResId != R.raw.standartanimation) {
            switchToDefaultAnimation();
        }

        idleHandler.postDelayed(idleRunnable, IDLE_DELAY);
    }

    private void switchToVideoBackground() {
        playBackgroundVideo(R.raw.talanimation, true);
    }



    private void handleGeneralTap() {
        if (dialogueBox.getVisibility() == View.GONE) {
            // Если скрыто — показываем
            showDialogue();
        } else {
            // Если открыто — идем к следующему чанку
            showNextChunk();
        }
    }

    private void hideDialogue() {
        dialogueBox.animate()
                .alpha(0f)
                .translationY(50f) // Слегка уходит вниз
                .setDuration(250)
                .withEndAction(() -> dialogueBox.setVisibility(View.GONE))
                .start();
    }

    private void showDialogue() {
        dialogueBox.setVisibility(View.VISIBLE);
        dialogueBox.setAlpha(0f);
        dialogueBox.setTranslationY(50f);

        dialogueBox.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .start();
    }

    private void openNpcDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_input, null);

        EditText editTextInput = dialogView.findViewById(R.id.editTextInput);
        Button sayButton = dialogView.findViewById(R.id.sayButton);
        ImageButton voiceButton = dialogView.findViewById(R.id.voice_input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        sayButton.setOnClickListener(v -> {
            String playerText = editTextInput.getText().toString().trim();
            if (!playerText.isEmpty()) {
                dialog.dismiss();
                sendMessageToNpc(playerText);
            }
        });

        voiceButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            } else {
                startVoiceInput(editTextInput);
            }
        });

        dialog.show();
    }

    private void replayLastAudio() {
        if (lastAudioPath == null) {
            Log.w("TTS", "Нет сохраненного аудио для повтора");
            return;
        }

        File file = new File(lastAudioPath);
        if (!file.exists()) {
            Log.e("TTS", "Файл аудио больше не существует");
            return;
        }

        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(lastAudioPath);
            mediaPlayer.prepare();
            mediaPlayer.start();

            // Освобождаем память после проигрывания
            mediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void switchToDefaultAnimation() {
        runOnUiThread(() -> {
            // Убедимся, что ImageView скрыта, так как она нам больше не нужна как основной фон
            bgImageView.animate().alpha(0f).setDuration(400).withEndAction(() -> bgImageView.setVisibility(View.INVISIBLE)).start();

            // Запускаем стандартную анимацию в бесконечном цикле (isLooping = true)
            playBackgroundVideo(R.raw.standartanimation, true);
        });
    }

    private void checkGrammar() {
        if (lastPlayerText == null || lastPlayerText.isEmpty()) {
            animateText("Сначала напиши что-нибудь, чтобы я могла проверить! 😊");
            return;
        }

        animateText("Проверяю... 📝");

        // Формируем строгий промпт для проверки
        String grammarPrompt = "Ты — эксперт по лингвистике и учитель. Проверь следующее сообщение на ошибки в грамматике, " +
                "пунктуации и стиле: \"" + lastPlayerText + "\". \n\n" +
                "Твоя задача: \n" +
                "1. Найди ошибки, если они есть. \n" +
                "2. Объясни правила простыми словами. \n" +
                "3. Предложи правильный вариант. \n" +
                "КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО отвечать на само сообщение или продолжать диалог как персонаж. " +
                "Только сухая проверка и объяснение.";

        // Отправляем запрос
        npcAI.generate(grammarPrompt, "заботливый учитель", new NpcCallback() {
            @Override
            public void onUpdate(String partialText) { /* можно игнорировать */ }

            @Override
            public void onComplete(String finalText) {
                // Разделяем длинный ответ учителя на чанки и анимируем
                splitReplyIntoChunks(finalText, 120);
                if (!replyChunks.isEmpty()) {
                    animateText(replyChunks.get(0));
                }
                scrollImage.setOnClickListener(v -> showNextChunk());
            }

            @Override
            public void onError(String errorMsg) {
                animateText("Ой, не удалось проверить текст... 😵");
            }
        });
    }

    private void sendMessageToNpc(String playerText) {
        this.lastPlayerText = playerText;

        if (speakerName != null) speakerName.setText("Юки");
        animateText("Юки думает...");

        StringBuilder fullPrompt = new StringBuilder();

        if (!memory.isEmpty()) {
            fullPrompt.append("[История диалога]\n");
            for (DialoguePair pair : memory) {
                fullPrompt.append("User: ").append(pair.player).append("\n");
                fullPrompt.append("Model: ").append(pair.npc).append("\n");
            }
            fullPrompt.append("[Конец истории]\n\n");
        }

        fullPrompt.append("User: ").append(playerText).append("\n");
        fullPrompt.append("Model:");

        npcAI.generate(fullPrompt.toString(), teacherSettings, new NpcCallback() {
            @Override
            public void onUpdate(String partialText) {
                // Можно сделать стриминг текста сюда
            }

            @Override
            public void onComplete(String finalText) {
                String targetLang = getDefaultLenguage(HomeActivity.this);

                executor.execute(() -> {
                    String audioText;
                    String displayText;

                    if ("ja".equals(targetLang)) {
                        audioText = translateToJapanese(finalText);
                        displayText = translateFromRu(finalText, "ru");
                    } else {
                        String translated = translateFromRu(finalText, targetLang);
                        audioText = translated;
                        displayText = translated;
                    }

                    String finalAudioText = audioText;
                    String finalDisplayText = displayText;

                    mainHandler.post(() -> {
                        addToMemory(playerText, finalText);
                        playNpcReply(finalAudioText, finalDisplayText);
                    });
                });
            }

            @Override
            public void onError(String errorMsg) {
                runOnUiThread(() -> animateText("Ошибка сети... 😵"));
                Log.e("NpcAI", errorMsg);
            }
        });
    }

    private void showTranslationMenu() {
        // Варианты выбора
        String[] languages = {"Русский", "English"};
        String[] codes = {"ru", "en"};

        new AlertDialog.Builder(this)
                .setTitle("Перевести на:")
                .setItems(languages, (dialog, which) -> {
                    String targetLang = codes[which];
                    // Запускаем процесс перевода
                    translateCurrentDialogue(targetLang);
                })
                .show();
    }

    private void translateCurrentDialogue(String targetLang) {
        // Берем текст, который сейчас отображен в диалоге
        String textToTranslate = lastText;

        if (textToTranslate == null || textToTranslate.isEmpty()) return;

        executor.execute(() -> {
            // Используем твой метод translateFromRu, но он универсальный для Google API
            // так как Google сам определяет исходный язык (auto-detect)
            String translated = translateFromRu(textToTranslate, targetLang);

            mainHandler.post(() -> {
                // Останавливаем текущую анимацию и запускаем новую с переводом
                animateText(translated);
            });
        });
    }

    public void playNpcReply(String audioText, String displayText) {
        if (isFinishing() || isDestroyed()) return;

        String language = getDefaultLenguage(this);
        if (language == null || language.trim().isEmpty()) language = "en";
        String finalLanguage = language;

        executor.execute(() -> {
            if (isFinishing() || isDestroyed()) return;

            try {
                AtomicBoolean audioPlayed = new AtomicBoolean(false);

                Runnable fallback = () -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!audioPlayed.get()) {
                        audioPlayed.set(true);
                        lastText = displayText;
                        runOnUiThread(() -> animateTextWithoutVoice(displayText));
                    }
                };
                mainHandler.postDelayed(fallback, 4000);

                VoiceVoxCallback callback = new VoiceVoxCallback() {
                    @Override
                    public void onAudioReady() {
                        if (isFinishing() || isDestroyed()) return;
                        if (audioPlayed.get()) return;

                        audioPlayed.set(true);
                        mainHandler.removeCallbacks(fallback);

                        runOnUiThread(() -> {
                            splitReplyIntoChunks(displayText, 100);
                            currentChunkIndex = 0;
                            if (!replyChunks.isEmpty()) {
                                isDialogueActive = true;
                                animateText(replyChunks.get(0));
                            }
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e("TTS", "Error: " + e.getMessage());
                        if (isFinishing() || isDestroyed()) return;
                        if (!audioPlayed.get()) {
                            audioPlayed.set(true);
                            mainHandler.removeCallbacks(fallback);
                            runOnUiThread(() -> {
                                lastText = displayText;
                                animateTextWithoutVoice(displayText);
                            });
                        }
                    }
                };

                if ("ja".equals(finalLanguage.trim())) {
                    speakJapanese(audioText, callback);
                } else {
                    speakCoqui(audioText, finalLanguage, callback);
                }

            } catch (Exception e) {
                Log.e("TTS", "Exception: " + e.getMessage());
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> animateTextWithoutVoice(displayText));
                }
            }
        });
    }

    // --- TTS Methods (Оставил твои, они выглядят рабочими) ---
    private void speakJapanese(String text, VoiceVoxCallback callback) throws IOException {
        isSpeaking = true;
        idleHandler.removeCallbacks(idleRunnable); // Останавливаем таймер ожидания
        String voiceVoxUrl = "http://91.205.196.207:50021";
        String speaker = "1";
        executor.execute(() -> {
            try {
                String japaneseText = translateToJapanese(text);
                OkHttpClient client = new OkHttpClient();

                HttpUrl url = HttpUrl.parse(voiceVoxUrl + "/audio_query").newBuilder()
                        .addQueryParameter("speaker", speaker)
                        .addQueryParameter("text", japaneseText).build();

                Request queryRequest = new Request.Builder().url(url).post(RequestBody.create(new byte[0])).build();
                Response queryResponse = client.newCall(queryRequest).execute();
                if (!queryResponse.isSuccessful() || queryResponse.body() == null) {
                    isSpeaking = false;
                    return;
                }

                String queryResult = queryResponse.body().string();
                RequestBody synthBody = RequestBody.create(queryResult, MediaType.get("application/json; charset=utf-8"));
                Request synthRequest = new Request.Builder().url(voiceVoxUrl + "/synthesis?speaker=1").post(synthBody).build();
                Response synthResponse = client.newCall(synthRequest).execute();
                if (!synthResponse.isSuccessful() || synthResponse.body() == null) {
                    isSpeaking = false;
                    return;
                };

                byte[] audioBytes = synthResponse.body().bytes();
                if (lastAudioPath != null) new File(lastAudioPath).delete();

                File tempFile = File.createTempFile("voicevox", ".wav", getCacheDir());
                try (FileOutputStream fos = new FileOutputStream(tempFile)) { fos.write(audioBytes); }
                lastAudioPath = tempFile.getAbsolutePath();

                // В основном потоке обновляем UI, когда аудио готово
                runOnUiThread(() -> callback.onAudioReady());

                MediaPlayer mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(tempFile.getAbsolutePath());
                mediaPlayer.prepare();

                // 1. Устанавливаем слушатель завершения, чтобы вернуть фото после голоса
                mediaPlayer.setOnCompletionListener(mp -> {
                    isSpeaking = false;
                    switchToDefaultAnimation(); // <--- ДОБАВИТЬ (возврат к фото)
                    resetIdleTimer(); // <--- Снова запускаем отсчет 15 секунд после того, как речь закончилась
                    mp.release();
                });

                // 2. Включаем видео и запускаем звук
                switchToVideoBackground(); // <--- ДОБАВИТЬ (запуск видео)
                mediaPlayer.start();

            } catch (Exception e) {
                e.printStackTrace();
                switchToDefaultAnimation(); // На случай ошибки возвращаем фото
            }
        });
    }

    private void speakCoqui(String text, String language, VoiceVoxCallback callback) {
        isSpeaking = true;
        idleHandler.removeCallbacks(idleRunnable); // Останавливаем таймер ожидания
        String coquiUrl = "http://91.205.196.207:5002/api/tts";
//        String coquiUrl = "https://harris-subtorrid-chelsey.ngrok-free.dev";

        executor.execute(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(180, TimeUnit.SECONDS).build();

                JSONObject json = new JSONObject();
                String finalText = text;
                String voiceFile = "voices/roxy.wav"; // По умолчанию для RU

                // Логика выбора голоса и перевода
                if ("en".equals(language)) {
                    finalText = translateFromRu(text, "en");
                    voiceFile = "voices/raiden.wav";
                }
                else if ("fr".equals(language)) {
                    // ДОБАВЛЕНО: Французский язык
                    finalText = translateFromRu(text, "fr");
                    voiceFile = "voices/french.wav"; // Убедись, что этот файл есть на сервере!
                }

                json.put("text", finalText);
                json.put("language", language); // Код "fr" отправится на сервер
                json.put("speaker_wav", voiceFile);
                if ("fr".equals(language)) {
                    json.put("speed", 0.9); // Французский звучит лучше, если он чуть медленнее
                } else {
                    json.put("speed", 1.1);
                }

                RequestBody body = RequestBody.create(json.toString().getBytes(StandardCharsets.UTF_8),
                        MediaType.get("application/json; charset=utf-8"));
                Request request = new Request.Builder().url(coquiUrl).post(body).build();
                Response response = client.newCall(request).execute();

                if (!response.isSuccessful() || response.body() == null) {
                    isSpeaking = false;
                    callback.onError(new Exception("Server error"));
                    return;
                }

                if (lastAudioPath != null) new File(lastAudioPath).delete();
                File tempFile = File.createTempFile("coqui", ".wav", getCacheDir());
                try (InputStream is = response.body().byteStream(); FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = is.read(buffer)) != -1) fos.write(buffer, 0, read);
                }

                lastAudioPath = tempFile.getAbsolutePath();

                // Запуск аудио и видео в основном потоке
                runOnUiThread(() -> {
                    try {
                        MediaPlayer mediaPlayer = new MediaPlayer();
                        mediaPlayer.setDataSource(lastAudioPath);
                        mediaPlayer.prepare();

                        mediaPlayer.setOnCompletionListener(mp -> {
                            isSpeaking = false; // Не забываем сбрасывать флаг
                            switchToDefaultAnimation();
                            resetIdleTimer();
                            mp.release();
                        });

                        // Включаем видео-фон перед стартом
                        switchToVideoBackground();
                        mediaPlayer.start();

                        callback.onAudioReady();

                    } catch (IOException e) {
                        e.printStackTrace();
                        isSpeaking = false;
                    }
                });

            } catch (Exception e) {
                isSpeaking = false;
                callback.onError(e);
            }
        });
    }



    // --- Animation & Text Logic ---

    private void animateTextWithoutVoice(String text) {
        this.runOnUiThread(() -> {
            splitReplyIntoChunks(text, 100);
            currentChunkIndex = 0;
            if (!replyChunks.isEmpty()) {
                isDialogueActive = true;
                animateText(replyChunks.get(currentChunkIndex));
            }
        });
    }

    private void animateText(String text) {
        // ИСПОЛЬЗУЕМ ПРАВИЛЬНУЮ ПЕРЕМЕННУЮ
        textDialogue.setText("");
        charIndex = 0;
        currentText = text;
        textFullyDisplayed = false;

        handler.removeCallbacks(characterAdder);
        // Задержка перед началом печати
        handler.postDelayed(characterAdder, 50);
    }

    private final Runnable characterAdder = new Runnable() {
        @Override
        public void run() {
            if (charIndex < currentText.length()) {
                // ИСПОЛЬЗУЕМ ПРАВИЛЬНУЮ ПЕРЕМЕННУЮ
                textDialogue.append(String.valueOf(currentText.charAt(charIndex)));
                charIndex++;
                handler.postDelayed(this, 30); // Скорость печати
            } else {
                textFullyDisplayed = true;
            }
        }
    };

    private void showNextChunk() {
        if (textFullyDisplayed) {
            if (currentChunkIndex < replyChunks.size() - 1) {
                currentChunkIndex++;
                animateText(replyChunks.get(currentChunkIndex));
            } else {
                isDialogueActive = false; // Конец диалога
            }
        } else {
            // Пропуск анимации
            handler.removeCallbacks(characterAdder);
            textDialogue.setText(currentText);
            textFullyDisplayed = true;
        }
    }

    // --- Utils ---

    private void splitReplyIntoChunks(String fullText, int approxChunkSize) {
        replyChunks.clear();
        currentChunkIndex = 0;
        int length = fullText.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + approxChunkSize, length);
            int splitPoint = findNextSplitPoint(fullText, end);
            replyChunks.add(fullText.substring(start, splitPoint).trim());
            start = splitPoint;
        }
    }

    private int findNextSplitPoint(String text, int fromIndex) {
        int length = text.length();
        String punctuation = ".!?…";
        for (int i = fromIndex; i < length; i++) {
            if (punctuation.indexOf(text.charAt(i)) != -1) return i + 1;
        }
        return Math.min(fromIndex, length);
    }

    private void addToMemory(String player, String npc) {
        if (memory.size() >= MEMORY_LIMIT) memory.pollFirst();
        memory.addLast(new DialoguePair(player, npc));
    }

    // --- Translation ---
    // (Оставил твои методы без изменений, они безопасны)
    private String translateToJapanese(String text) {
        try {
            OkHttpClient client = new OkHttpClient();
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ja&dt=t&q=" + URLEncoder.encode(text, "UTF-8");
            Request request = new Request.Builder().url(url).get().build();
            Response response = client.newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) return text;
            JSONArray arr = new JSONArray(response.body().string());
            JSONArray translations = arr.getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < translations.length(); i++) sb.append(translations.getJSONArray(i).getString(0));
            return sb.toString();
        } catch (Exception e) { return text; }
    }

    private String translateFromRu(String text, String targetLang) {
        try {
            OkHttpClient client = new OkHttpClient();
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + targetLang + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8");
            Request request = new Request.Builder().url(url).get().header("User-Agent", "Mozilla/5.0").build();
            Response response = client.newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) return text;
            JSONArray arr = new JSONArray(response.body().string());
            JSONArray translations = arr.getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < translations.length(); i++) sb.append(translations.getJSONArray(i).getString(0));
            return sb.toString();
        } catch (Exception e) { return text; }
    }

    private String getDefaultLenguage(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("npc_settings", Context.MODE_PRIVATE);
        int lang = prefs.getInt("voice_language", 0);
        switch (lang) {
            case 1: return "en";
            case 2: return "ja";
            case 3: return "fr";
            default: return "ru";
        }
    }

    private void openSettingsDialog() {
        // 1. Создаем View из нашего XML
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);

        // 2. Инициализируем элементы внутри View
        RadioGroup genderGroup = view.findViewById(R.id.voiceGenderGroup);
        RadioButton female = view.findViewById(R.id.voiceFemale);
        RadioGroup colorGroup = view.findViewById(R.id.colorGroup);
        Spinner languageSpinner = view.findViewById(R.id.languageSpinner);
        Button btnSave = view.findViewById(R.id.btnSave);
        Button btnCancel = view.findViewById(R.id.btnCancel);

        RadioButton colorWhite = view.findViewById(R.id.colorWhite);
        RadioButton colorBlack = view.findViewById(R.id.colorBlack);

        Button btnStartHelper = view.findViewById(R.id.btnStartHelper);

        // ДОБАВЛЯЕМ НАШУ НОВУЮ КНОПКУ
        Button btnGameYuki = view.findViewById(R.id.btnGameYuki);

        // 3. Настраиваем Спиннер с белым текстом
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.tts_languages,
                R.layout.spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);

        // 4. Загружаем сохраненные настройки
        SharedPreferences prefs = getSharedPreferences("npc_settings", Context.MODE_PRIVATE);
        boolean isBlack = prefs.getBoolean("playing_as_black", false);

        if (isBlack) colorBlack.setChecked(true);
        else colorWhite.setChecked(true);

        String gender = prefs.getString("voice_gender", "female");
        int language = prefs.getInt("voice_language", 0);

        if (gender.equals("female")) female.setChecked(true);

        if (language < adapter.getCount()) {
            languageSpinner.setSelection(language);
        }

        // 5. Создаем и показываем диалог
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // Логика кнопки шахматной Юки
        btnStartHelper.setOnClickListener(v -> {
            boolean selectedAsBlack = colorGroup.getCheckedRadioButtonId() == R.id.colorBlack;
            prefs.edit().putBoolean("playing_as_black", selectedAsBlack).apply();
            dialog.dismiss();
            startFloatingYuki();
        });

        // ЛОГИКА НОВОЙ КНОПКИ ДЛЯ ГЕЙМЕРСКОЙ ЮКИ
        btnGameYuki.setOnClickListener(v -> {
            dialog.dismiss(); // Просто закрываем настройки
            startGameYuki();  // Вызываем метод запуска (его нужно будет создать)
        });

        // 6. Логика кнопок сохранения и отмены
        btnSave.setOnClickListener(v -> {
            boolean selectedAsBlack = colorGroup.getCheckedRadioButtonId() == R.id.colorBlack;
            String selectedGender = genderGroup.getCheckedRadioButtonId() == R.id.voiceFemale ? "female" : "male";
            int selectedLanguage = languageSpinner.getSelectedItemPosition();

            prefs.edit()
                    .putString("voice_gender", selectedGender)
                    .putInt("voice_language", selectedLanguage)
                    .putBoolean("playing_as_black", selectedAsBlack)
                    .apply();

            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void startVoiceInput(EditText editTextInput) {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) editTextInput.setText(matches.get(0));
                releaseSpeechRecognizer();
            }
            @Override public void onError(int error) { releaseSpeechRecognizer(); }
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizer.startListening(intent);
    }

    private void releaseSpeechRecognizer() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    // Вспомогательный класс
    private static class DialoguePair {
        String player, npc;
        DialoguePair(String p, String n) { player = p; npc = n; }
    }
}