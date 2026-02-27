package com.example.yukiai;

import android.graphics.Bitmap;

public class GameYuki {

    // Ссылки на клиента нейросети и коллбэк для общения с интерфейсом
    private GeminiClient geminiClient;
    private NpcCallback uiCallback;

    // Флаг состояния: Юки думает? (защита от спама запросами)
    private boolean isThinking = false;

    // Конструктор: при создании мы даем ей доступ к Gemini и интерфейсу
    public GameYuki(GeminiClient client, NpcCallback callback) {
        this.geminiClient = client;
        this.uiCallback = callback;
    }

    // Метод пробуждения (вызывается при старте сервиса)
    public void wakeUp() {
        geminiClient.clearMemory(); // Очищаем историю прошлых сессий
        isThinking = false;

        // Отправляем стартовое сообщение в интерфейс
        if (uiCallback != null) {
            uiCallback.onUpdate("Юки проснулась и готова смотреть игру! 🎮");
            uiCallback.onComplete("Юки проснулась и готова смотреть игру! 🎮");
        }
    }

    // Главный рабочий метод: передаем скриншот на анализ
    public void lookAtScreen(Bitmap screenshot, String optionalPrompt) {
        // Если она уже генерирует ответ на предыдущий скриншот — игнорируем новый
        if (isThinking) {
            return;
        }

        if (screenshot == null) {
            if (uiCallback != null) uiCallback.onError("Пустой скриншот.");
            return;
        }

        isThinking = true; // Ставим статус "Думаю"

        // Если ты не передал конкретный вопрос, используем базовый промпт подружки
        String prompt = (optionalPrompt == null || optionalPrompt.trim().isEmpty())
                ? "Прокомментируй коротко то, что видишь на экране."
                : optionalPrompt;

        // Отправляем в GeminiClient (тот самый, который мы обновили с историей чата)
        geminiClient.generateWithImage(prompt, screenshot, new NpcCallback() {
            @Override
            public void onUpdate(String partialText) {
                // Текст печатается по буквам — передаем в UI
                if (uiCallback != null) {
                    uiCallback.onUpdate(partialText);
                }
            }

            @Override
            public void onComplete(String finalText) {
                isThinking = false; // Мозг свободен для новых картинок
                if (uiCallback != null) {
                    uiCallback.onComplete(finalText);
                }
            }

            @Override
            public void onError(String errorMsg) {
                isThinking = false; // Произошла ошибка, сбрасываем статус
                if (uiCallback != null) {
                    uiCallback.onError(errorMsg);
                }
            }
        });
    }

    // Метод для усыпления (когда закрываем плавающее окно)
    public void sleep() {
        isThinking = false;
        geminiClient.clearMemory();
    }

    // Проверка статуса извне
    public boolean isBusy() {
        return isThinking;
    }
}