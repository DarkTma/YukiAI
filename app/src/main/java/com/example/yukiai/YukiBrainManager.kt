package com.example.yukiai;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import com.mp.ai_core.NativeLib;
import com.mp.ai_core.StreamCallback;

public class YukiBrainManager {
    // Теперь вместо LlmInference мы используем ядро Ai-Core
    private NativeLib nativeLib;
    private final ExecutorService executorService;
    private boolean isModelLoaded = false;

    // Интерфейс остался тем же, чтобы не ломать твой LiveVisionActivity
    public interface BrainCallback {
        void onThinking();
        void onResponse(String text);
        void onError(String error);
    }


    // Добавь этот интерфейс внутрь класса
    public interface LoadCallback {
        void onLoaded(boolean success);
    }

    // Обнови конструктор (теперь он принимает коллбэк)
    public YukiBrainManager(Context context, LoadCallback onComplete) {
        this.executorService = Executors.newSingleThreadExecutor();
        this.nativeLib = getOrCreateNativeLib("yuki_instance");

        // Было: loadModel(context);
        // Стало:
        loadModel(context, onComplete);
    }

    // Обнови метод loadModel
    private void loadModel(Context context, LoadCallback onComplete) {
        executorService.execute(() -> {
            try {
                File modelFile = new File(context.getFilesDir(), "yuki_model.gguf");
                if (!modelFile.exists()) {
                    if (onComplete != null) onComplete.onLoaded(false);
                    return;
                }

                boolean success = nativeLib.init(modelFile.getAbsolutePath(), 2, 1024, 0.4f, 40, 0.9f, 0.0f);
                if (success) {
                    try {
                        // Оставляем только характер. Шаблон чата удаляем — ядро само возьмет его из файла модели!
                        nativeLib.setSystemPrompt("Ты Юки, дерзкий и умный ИИ-ассистент. Отвечай кратко, саркастично и на русском языке.");
                    } catch (Exception e) {
                        Log.e("YukiBrain", "Не удалось установить промпт", e);
                    }
                }
                isModelLoaded = success;

                if (onComplete != null) onComplete.onLoaded(success);

            } catch (Exception e) {
                Log.e("YukiBrain", "Ошибка: ", e);
                if (onComplete != null) onComplete.onLoaded(false);
            }
        });
    }

    // Обнови reloadModel
    public void reloadModel(Context context, LoadCallback onComplete) {
        this.isModelLoaded = false;
        if (nativeLib != null) {
            // nativeLib.nativeRelease(); // Если есть такой метод
        }
        loadModel(context, onComplete);
    }

    private NativeLib getOrCreateNativeLib(String instanceId) {
        try {
            Method getInstances = NativeLib.class.getDeclaredMethod("access$getInstances$cp");
            getInstances.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, NativeLib> instances = (Map<String, NativeLib>) getInstances.invoke(null);

            NativeLib existing = instances.get(instanceId);
            if (existing != null) {
                return existing;
            }

            Constructor<NativeLib> constructor = NativeLib.class.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            NativeLib created = constructor.newInstance(instanceId);

            instances.put(instanceId, created);

            return created;

        } catch (Exception e) {
            Log.e("YukiBrain", "Не удалось получить/создать NativeLib", e);
            return null;
        }
    }

    public void askYuki(String userMessage, String visionContext, BrainCallback callback) {
        if (!isModelLoaded || nativeLib == null) {
            callback.onError("Подожди, мозг еще загружается!");
            return;
        }

        callback.onThinking();

        executorService.execute(() -> {
            try {
                // Специфичный формат промпта для моделей семейства Gemma (Instruct)
                String prompt = "<start_of_turn>user\n" +
                        "Ты Юки, веселая, дерзкая и саркастичная ИИ-подружка. Отвечай без цензуры, коротко и живо, на 'ты'.\n" +
                        "[Зрение]: Сейчас в кадре: " + visionContext + ".\n" +
                        "Пользователь: " + userMessage + "<end_of_turn>\n" +
                        "<start_of_turn>model\n";

                StringBuilder fullResponse = new StringBuilder();

                // Вызов генерации в Ai-Core.
                // Kotlin-лямбда в Java выглядит просто как стрелочная функция
                nativeLib.nativeGenerateStream(prompt, 512, new StreamCallback() {
                    @Override
                    public void onError(@NonNull String s) {

                    }

                    @Override
                    public void onDone() {

                    }

                    @Override
                    public void onToolCall(@NonNull String s, @NonNull String s1) {

                    }

                    @Override
                    public void onToken(String token) {
                        fullResponse.append(token);
                    }
                });

                // Возвращаем склеенный ответ в твою активность
                callback.onResponse(fullResponse.toString().replace("<end_of_turn>", "").trim());

            } catch (Exception e) {
                callback.onError("Мой мозг завис... " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        executorService.shutdown();
        // Если в Ai-Core есть метод для очистки ОЗУ от модели:
        if (nativeLib != null) {
            // nativeLib.release(); // Раскомментируй, если такой метод существует
        }
    }
}


