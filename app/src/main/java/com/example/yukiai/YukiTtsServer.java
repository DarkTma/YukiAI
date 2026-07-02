package com.example.yukiai;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.json.JSONObject;

import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.nio.file.Files;
import java.net.InetSocketAddress;

public class YukiTtsServer extends WebSocketServer {

    private HomeActivity activity;
    private TextToSpeech tts; // Добавляем переменную для TTS

    // Обновляем конструктор: теперь он принимает еще и tts
    public YukiTtsServer(int port, HomeActivity activity, TextToSpeech tts) {
        super(new InetSocketAddress(port));
        this.activity = activity;
        this.tts = tts;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Log.d("YukiServer", "Новое подключение от ПК: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.d("YukiServer", "Отключение ПК: " + conn.getRemoteSocketAddress());
    }

    // Оставляем только ОДИН объединенный метод onMessage
    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JSONObject json = new JSONObject(message);
            String text = json.getString("text");
            Log.d("YukiServer", "Текст для озвучки: " + text);

            // 1. Создаем временный файл, используя activity.getCacheDir()
            File tempAudioFile = File.createTempFile("yuki_mobile_", ".wav", activity.getCacheDir());
            String utteranceId = "yuki_tts_" + System.currentTimeMillis();

            // 2. Вешаем слушатель, чтобы узнать, когда файл запишется
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {}

                @Override
                public void onDone(String utteranceId) {
                    try {
                        // 3. Читаем файл в массив байтов
                        byte[] audioBytes = null;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            audioBytes = Files.readAllBytes(tempAudioFile.toPath());
                        }

                        // 4. Отправляем бинарные данные обратно на ПК
                        conn.send(audioBytes);
                        Log.d("YukiServer", "Аудио отправлено на ПК! Размер: " + audioBytes.length + " байт");

                        // Удаляем временный файл
                        tempAudioFile.delete();
                    } catch (Exception e) {
                        Log.e("YukiServer", "Ошибка отправки аудио", e);
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    Log.e("YukiServer", "Ошибка генерации TTS");
                }
            });

            // Запускаем синтез в файл
            Bundle params = new Bundle();
            tts.synthesizeToFile(text, params, tempAudioFile, utteranceId);

        } catch (Exception e) {
            Log.e("YukiServer", "Ошибка обработки сообщения: " + e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.e("YukiServer", "Ошибка сервера: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        Log.d("YukiServer", "Сервер Юки успешно запущен на порту " + getPort());
    }
}