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

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import android.graphics.Bitmap;
import android.util.Base64;

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

    // Поле класса
    private JSONArray chatHistory = new JSONArray();

    public void clearMemory() {
        chatHistory = new JSONArray();
    }

    public void generateWithImage(String prompt, Bitmap bitmap, @NonNull NpcCallback callback) {
        new Thread(() -> {
            try {
                // 1. Оптимизируем историю: удаляем старые картинки перед добавлением новой
                for (int i = 0; i < chatHistory.length(); i++) {
                    JSONObject message = chatHistory.getJSONObject(i);
                    if ("user".equals(message.getString("role"))) {
                        JSONArray parts = message.getJSONArray("parts");
                        JSONArray textOnlyParts = new JSONArray();
                        boolean removedImage = false;

                        // Перебираем части старого сообщения
                        for (int j = 0; j < parts.length(); j++) {
                            JSONObject part = parts.getJSONObject(j);
                            if (part.has("text")) {
                                textOnlyParts.put(part); // Оставляем текст
                            } else if (part.has("inline_data")) {
                                removedImage = true; // Нашли и удаляем картинку
                            }
                        }

                        // Оставляем Юки "воспоминание" о том, что тут был скриншот
                        if (removedImage) {
                            textOnlyParts.put(new JSONObject().put("text", "[Скриншот экрана]"));
                        }
                        message.put("parts", textOnlyParts); // Перезаписываем части без Base64
                    }
                }

                // 2. Сжимаем НОВУЮ картинку
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] imageBytes = baos.toByteArray();
                String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                // 3. Формируем новое сообщение от пользователя
                JSONObject userContent = new JSONObject();
                userContent.put("role", "user");

                JSONArray newPartsArray = new JSONArray();
                if (prompt != null && !prompt.isEmpty()) {
                    newPartsArray.put(new JSONObject().put("text", prompt));
                }
                newPartsArray.put(new JSONObject().put("inline_data", new JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", base64Image)));

                userContent.put("parts", newPartsArray);
                chatHistory.put(userContent);

                // 4. Ограничиваем память (последние 12 пар = 24 сообщения)
                // Если сообщений больше 24, удаляем самое старое (индекс 0)
                while (chatHistory.length() > 24) {
                    // В Android API JSONArray.remove() доступен с API 19, что нам отлично подходит
                    chatHistory.remove(0);
                }

                // 5. Собираем итоговый JSON для отправки
                JSONObject jsonBody = new JSONObject();
                jsonBody.put("contents", chatHistory);

                // 6. Настраиваем характер Юки и просим отвечать коротко
                // 1. Меняем инструкцию (просим говорить развернуто)
                // 4. Задаем характер чилловой геймер-подружки
                // 4. Задаем характер милой и поддерживающей геймер-подружки
                JSONObject systemInstruction = new JSONObject();
                systemInstruction.put("parts", new JSONArray().put(new JSONObject().put("text",
                        "Твое имя Юки. Ты моя виртуальная подруга, мы сидим вместе и ты смотришь, как я играю. " +
                                "Веди себя расслабленно, мило и уютно, как девчонка-геймер. Используй современный сленг, но без перебора. " +
                                "ЗАПРЕЩЕНО: удивляться базовым вещам, задавать вопросы вроде 'Ого, что это?', быть излишне восторженной или грубо подкалывать. " +
                                "Радуйся моим победам, мило сопереживай неудачам (например: 'Ой, ну почти', 'Бывает, щас отыграемся') или просто давай забавные чилловые комментарии по игре. " +
                                "Твои ответы должны быть КОРОТКИМИ и емкими — строго 1-2 небольших предложения."
                )));
                jsonBody.put("system_instruction", systemInstruction);

                // 5. Настройки генерации (возвращаем лимит токенов обратно на небольшой, раз просим отвечать коротко)
                JSONObject generationConfig = new JSONObject();
                generationConfig.put("temperature", 0.75); // Оставляем креативность для хороших шуток
                generationConfig.put("maxOutputTokens", 1500); // Жестко режем возможность писать лонгриды
                jsonBody.put("generationConfig", generationConfig);

                // 7. Отправляем запрос с использованием твоего NpcCallback
                sendRequest(jsonBody.toString(), new NpcCallback() {

                    @Override
                    public void onUpdate(String partialText) {
                        // Просто пробрасываем частичный текст в UI для плавной анимации печати
                        callback.onUpdate(partialText);
                    }

                    @Override
                    public void onComplete(String finalText) {
                        try {
                            // Сохраняем ПОЛНЫЙ ответ Юки в память только после завершения генерации
                            JSONObject modelContent = new JSONObject();
                            modelContent.put("role", "model");
                            modelContent.put("parts", new JSONArray().put(new JSONObject().put("text", finalText)));
                            chatHistory.put(modelContent);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        // Передаем сигнал о завершении в UI
                        callback.onComplete(finalText);
                    }

                    @Override
                    public void onError(String errorMsg) {
                        // Если произошла ошибка, удаляем последний запрос (скриншот+текст),
                        // чтобы не сломать логику чередования ролей (user -> model)
                        if (chatHistory.length() > 0) {
                            chatHistory.remove(chatHistory.length() - 1);
                        }
                        callback.onError(errorMsg);
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Ошибка подготовки: " + e.getMessage()));
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
