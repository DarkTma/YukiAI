package com.example.yukiai;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
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

public class HomeActivity extends AppCompatActivity {

    // --- UI Элементы ---
    private VideoView bgVideoView;
    private ImageView bgImageView;
    private View touchLayer;
    private TextView textDialogue; // Оставляем ОДНУ переменную для текста
    private TextView speakerName;
    private ImageView scrollImage; // Добавил, так как использовалось в коде, но не было объявлено

    // --- Логика ---
    private GestureDetector gestureDetector;
    private static final int REQ_RECORD_AUDIO = 1001;
    private SpeechRecognizer speechRecognizer;

    // --- AI и Настройки ---
    private GeminiClient npcAI; // Не забудь инициализировать!
    private String teacherSettings =
            "SYSTEM INSTRUCTION:\n" +
                    "Ты — Юки. Ты — дружелюбный репетитор и собеседник для практики языка.\n" +
                    "Отвечай кратко (до 5 предложений), позитивно, исправляй ошибки пользователя в конце сообщения в скобках.";

    // --- Переменные для анимации текста ---
    private List<String> replyChunks = new ArrayList<>();
    private int currentChunkIndex = 0;
    private Handler handler = new Handler(Looper.getMainLooper());
    private int charIndex = 0;
    private String currentText = "";
    private boolean textFullyDisplayed = false;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // 1. Инициализация UI
        bgVideoView = findViewById(R.id.bgVideoView);
        bgImageView = findViewById(R.id.bgImageView);
        touchLayer = findViewById(R.id.touchLayer);
        speakerName = findViewById(R.id.speakerName); // Проверь ID в XML (обычно textName)
        textDialogue = findViewById(R.id.textDialogue);

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> openSettingsDialog());

//        findViewById(R.id.btnCheckGrammar).setOnClickListener(v -> {
//            checkGrammar();
//        });
//
//        ImageButton btnPlayVoice = findViewById(R.id.btnPlayVoice);
//
//        btnPlayVoice.setOnClickListener(v -> {
//            replayLastAudio();
//        });
//
//        findViewById(R.id.btnTranslateUI).setOnClickListener(v -> {
//            showTranslationMenu();
//        });

        // api_key
        String myApiKey = BuildConfig.GEMINI_API_KEY;

// Инициализируем клиент
        npcAI = new GeminiClient(myApiKey);

        // 2. ИНИЦИАЛИЗАЦИЯ AI (ВАЖНО!)
        // Замени "YOUR_API_KEY" на твой ключ или убери аргумент, если ключ внутри класса
        npcAI = new GeminiClient(myApiKey);

        // 3. Настройка жестов
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                openNpcDialog();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isDialogueActive) {
                    showNextChunk();
                }
                return true;
            }
        });

        touchLayer.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });

        // 4. Настройка видео фона
        bgVideoView.setOnPreparedListener(mp -> mp.setLooping(true));

        // Пример запуска фона (если нужно сразу)
        // setBackgroundVideo(R.raw.intro_video);
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
        String voiceVoxUrl = "http://192.168.1.8:50021";
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
                if (!queryResponse.isSuccessful() || queryResponse.body() == null) return;

                String queryResult = queryResponse.body().string();
                RequestBody synthBody = RequestBody.create(queryResult, MediaType.get("application/json; charset=utf-8"));
                Request synthRequest = new Request.Builder().url(voiceVoxUrl + "/synthesis?speaker=1").post(synthBody).build();
                Response synthResponse = client.newCall(synthRequest).execute();
                if (!synthResponse.isSuccessful() || synthResponse.body() == null) return;

                byte[] audioBytes = synthResponse.body().bytes();
                if (lastAudioPath != null) new File(lastAudioPath).delete();

                File tempFile = File.createTempFile("voicevox", ".wav", getCacheDir());
                try (FileOutputStream fos = new FileOutputStream(tempFile)) { fos.write(audioBytes); }
                lastAudioPath = tempFile.getAbsolutePath();

                callback.onAudioReady();

                MediaPlayer mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(tempFile.getAbsolutePath());
                mediaPlayer.prepare();
                mediaPlayer.start();
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    private void speakCoqui(String text, String language, VoiceVoxCallback callback) {
        String coquiUrl = "http://192.168.1.8:5002/api/tts";
        executor.execute(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(180, TimeUnit.SECONDS).build();

                JSONObject json = new JSONObject();
                String finalText = text;
                String voiceFile = "voices/mita.wav";

                if ("en".equals(language)) {
                    finalText = translateFromRu(text, "en");
                    voiceFile = "voices/raiden.wav";
                }
                json.put("text", finalText);
                json.put("language", language);
                json.put("speaker_wav", voiceFile);

                RequestBody body = RequestBody.create(json.toString().getBytes(StandardCharsets.UTF_8), MediaType.get("application/json; charset=utf-8"));
                Request request = new Request.Builder().url(coquiUrl).post(body).build();
                Response response = client.newCall(request).execute();

                if (!response.isSuccessful() || response.body() == null) {
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
                MediaPlayer mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(tempFile.getAbsolutePath());
                mediaPlayer.prepare();
                mediaPlayer.start();
                mediaPlayer.setOnCompletionListener(mp -> {
                    mp.release();
                    callback.onAudioReady();
                });

            } catch (Exception e) { callback.onError(e); }
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
            default: return "ru";
        }
    }

    private void openSettingsDialog() {
        // 1. Создаем View из нашего XML
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);

        // 2. Инициализируем элементы внутри View
        RadioGroup genderGroup = view.findViewById(R.id.voiceGenderGroup);
        RadioButton female = view.findViewById(R.id.voiceFemale);
//        RadioButton male = view.findViewById(R.id.voiceMale);
        Spinner languageSpinner = view.findViewById(R.id.languageSpinner);
        Button btnSave = view.findViewById(R.id.btnSave);
        Button btnCancel = view.findViewById(R.id.btnCancel);

        // 3. Настраиваем Спиннер с белым текстом
        // Важно: используем R.layout.spinner_item (который мы создали в Шаге 1)
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.tts_languages,
                R.layout.spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);

        // 4. Загружаем сохраненные настройки
        SharedPreferences prefs = getSharedPreferences("npc_settings", Context.MODE_PRIVATE);
        String gender = prefs.getString("voice_gender", "female");
        int language = prefs.getInt("voice_language", 0);

        if (gender.equals("female")) female.setChecked(true);
//        else male.setChecked(true);

        if (language < adapter.getCount()) {
            languageSpinner.setSelection(language);
        }

        // 5. Создаем и показываем диалог
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();

        // Делаем фон системного окна прозрачным, чтобы виден был только наш bg_dialogue_style
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // 6. Логика кнопок
        btnSave.setOnClickListener(v -> {
            String selectedGender =
                    genderGroup.getCheckedRadioButtonId() == R.id.voiceFemale
                            ? "female" : "male";

            int selectedLanguage = languageSpinner.getSelectedItemPosition();

            prefs.edit()
                    .putString("voice_gender", selectedGender)
                    .putInt("voice_language", selectedLanguage)
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