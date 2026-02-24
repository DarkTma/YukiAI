package com.example.yukiai;


import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import android.graphics.Bitmap;
import android.util.Base64;
import java.io.ByteArrayOutputStream;

public class GeminiClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // 1. ИСПОЛЬЗУЕМ ОБЫЧНУЮ ГЕНЕРАЦИЮ (НЕ STREAM)
    // Это стабильнее и проще парсить
    private static final String MODEL_NAME = "gemini-3-flash-preview";
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL_NAME + ":generateContent";

    private final OkHttpClient client;
    private final String apiKey;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public GeminiClient(String apiKey) {
        this.apiKey = apiKey;
        // Увеличиваем таймаут, так как ждем весь ответ целиком
        this.client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public void generate(String playerText, String charPrompt, @NonNull NpcCallback callback) {
        // Мы НЕ используем executor здесь, так как OkHttp .enqueue сам создает поток

        JSONObject jsonBody = new JSONObject();
        try {
            JSONObject userPart = new JSONObject().put("text", playerText);
            JSONObject userContent = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(userPart));

            jsonBody.put("contents", new JSONArray().put(userContent));

            if (charPrompt != null && !charPrompt.isEmpty()) {
                JSONObject sysPart = new JSONObject().put("text", charPrompt);
                JSONObject sysContent = new JSONObject()
                        .put("parts", new JSONArray().put(sysPart));
                jsonBody.put("systemInstruction", sysContent);
            }

            // Настройки генерации
            JSONObject generationConfig = new JSONObject();
            generationConfig.put("temperature", 1.0);
            generationConfig.put("maxOutputTokens", 1000);
            jsonBody.put("generationConfig", generationConfig);

        } catch (JSONException e) {
            mainHandler.post(() -> callback.onError("JSON Error: " + e.getMessage()));
            return;
        }

        String finalUrl = BASE_URL + "?key=" + apiKey;

        Request request = new Request.Builder()
                .url(finalUrl)
                .post(RequestBody.create(jsonBody.toString(), JSON))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> callback.onError("Ошибка сети: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "Unknown";
                    mainHandler.post(() -> callback.onError("API Error " + response.code() + ": " + err));
                    return;
                }

                if (response.body() == null) {
                    mainHandler.post(() -> callback.onError("Пустой ответ от сервера"));
                    return;
                }

                try {
                    // 2. ЧИТАЕМ ВЕСЬ ОТВЕТ СРАЗУ (БЕЗ ЦИКЛОВ)
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);

                    // Парсим стандартный ответ Gemini
                    JSONArray candidates = json.optJSONArray("candidates");
                    if (candidates != null && candidates.length() > 0) {
                        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                        if (content != null) {
                            JSONArray parts = content.optJSONArray("parts");
                            if (parts != null && parts.length() > 0) {
                                String resultText = parts.getJSONObject(0).optString("text");

                                // 3. ОТПРАВЛЯЕМ РЕЗУЛЬТАТ
                                mainHandler.post(() -> callback.onComplete(resultText));
                                return;
                            }
                        }
                    }
                    // Если дошли сюда — значит формат ответа странный
                    mainHandler.post(() -> callback.onError("Не удалось найти текст в ответе"));

                } catch (JSONException e) {
                    mainHandler.post(() -> callback.onError("Ошибка парсинга: " + e.getMessage()));
                }
            }
        });
    }

    public void generateWithImage(String prompt, Bitmap bitmap, @NonNull NpcCallback callback) {
        // 1. Сжимаем картинку и переводим в текст (Base64)
        // Выполняем это в отдельном потоке, чтобы не тормозить интерфейс при конвертации
        new Thread(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                // 80% качества - отличный баланс: фигуры видно четко, а вес файла маленький
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] imageBytes = baos.toByteArray();

                // NO_WRAP очень важен, иначе переносы строк сломают JSON
                String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                // 2. Собираем JSON
                JSONObject jsonBody = new JSONObject();

                JSONObject textPart = new JSONObject().put("text", prompt);
                JSONObject imagePart = new JSONObject().put("inline_data", new JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", base64Image));

                // Кладем и текст, и картинку в один массив parts
                JSONArray partsArray = new JSONArray()
                        .put(textPart)
                        .put(imagePart);

                JSONObject userContent = new JSONObject()
                        .put("role", "user")
                        .put("parts", partsArray);

                jsonBody.put("contents", new JSONArray().put(userContent));

                // Настройки генерации (для шахмат лучше убрать креативность - temperature ближе к 0)
                JSONObject generationConfig = new JSONObject();
// Даем ей больше места для размышлений и чуть больше свободы
                generationConfig.put("temperature", 0.2);
                generationConfig.put("maxOutputTokens", 4000);
                jsonBody.put("generationConfig", generationConfig);

                // 3. Отправляем запрос
                sendRequest(jsonBody.toString(), callback);

            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Ошибка подготовки картинки: " + e.getMessage()));
            }
        }).start();
    }

    // Вспомогательный метод, чтобы не дублировать код отправки OkHttp
    private void sendRequest(String jsonPayload, @NonNull NpcCallback callback) {
        String finalUrl = BASE_URL + "?key=" + apiKey;

        Request request = new Request.Builder()
                .url(finalUrl)
                .post(RequestBody.create(jsonPayload, JSON))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> callback.onError("Ошибка сети: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "Unknown";
                    mainHandler.post(() -> callback.onError("API Error " + response.code() + ": " + err));
                    return;
                }
                if (response.body() == null) {
                    mainHandler.post(() -> callback.onError("Пустой ответ от сервера"));
                    return;
                }

                try {
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    JSONArray candidates = json.optJSONArray("candidates");

                    if (candidates != null && candidates.length() > 0) {
                        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                        if (content != null) {
                            JSONArray parts = content.optJSONArray("parts");
                            if (parts != null && parts.length() > 0) {
                                String resultText = parts.getJSONObject(0).optString("text");
                                mainHandler.post(() -> callback.onComplete(resultText));
                                return;
                            }
                        }
                    }
                    mainHandler.post(() -> callback.onError("Не удалось найти текст в ответе"));
                } catch (JSONException e) {
                    mainHandler.post(() -> callback.onError("Ошибка парсинга: " + e.getMessage()));
                }
            }
        });
    }
}
