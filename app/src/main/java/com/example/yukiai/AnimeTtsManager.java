package com.example.yukiai;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
public class AnimeTtsManager {
    private OfflineTts tts;
    private final int sampleRate = 22050; // Стандарт для VITS

    public AnimeTtsManager(Context context) {
        try {
            // 1. Создаем пустой конфиг для VITS и заполняем только нужное
            OfflineTtsVitsModelConfig vitsConfig = new OfflineTtsVitsModelConfig();
            vitsConfig.setModel("model.onnx");
            vitsConfig.setLexicon("lexicon.txt"); // Если у модели нет словаря, оставь ""
            vitsConfig.setTokens("tokens.txt");
            vitsConfig.setNoiseScale(0.667f);
            vitsConfig.setNoiseScaleW(0.8f);
            vitsConfig.setLengthScale(1.0f);

            // 2. Создаем пустой конфиг модели и кладем туда VITS
            OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
            modelConfig.setVits(vitsConfig);
            modelConfig.setNumThreads(4);     // 4 потока оптимально для телефона
            modelConfig.setProvider("cpu");   // "cpu" работает стабильно везде

            // 3. Создаем финальный конфиг TTS
            OfflineTtsConfig config = new OfflineTtsConfig();
            config.setModel(modelConfig);

            // 4. Инициализируем нейросеть
            tts = new OfflineTts(context.getAssets(), config);
            Log.d("AnimeTTS", "VITS Модель успешно загружена!");

        } catch (Exception e) {
            Log.e("AnimeTTS", "Ошибка загрузки модели. Проверь файлы в assets!", e);
        }
    }

    public byte[] synthesizeToWavBytes(String text, int speakerId) {
        if (tts == null) return null;

        // Генерация аудио (скорость 1.0f)
        GeneratedAudio audio = tts.generate(text, speakerId, 1.0f);
        float[] samples = audio.getSamples();

        return createWavFile(samples, sampleRate);
    }

    // ... (Здесь остается метод createWavFile без изменений) ...
    private byte[] createWavFile(float[] samples, int sampleRate) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int numSamples = samples.length;
            int numBytes = numSamples * 2; // 16-bit PCM

            writeString(out, "RIFF");
            writeInt(out, 36 + numBytes);
            writeString(out, "WAVE");
            writeString(out, "fmt ");
            writeInt(out, 16);
            writeShort(out, (short) 1);
            writeShort(out, (short) 1);
            writeInt(out, sampleRate);
            writeInt(out, sampleRate * 2);
            writeShort(out, (short) 2);
            writeShort(out, (short) 16);
            writeString(out, "data");
            writeInt(out, numBytes);

            ByteBuffer buffer = ByteBuffer.allocate(numBytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            for (float sample : samples) {
                float s = Math.max(-1.0f, Math.min(1.0f, sample));
                short val = (short) (s * 32767.0f);
                buffer.putShort(val);
            }
            out.write(buffer.array());
            return out.toByteArray();
        } catch (IOException e) {
            Log.e("AnimeTTS", "Ошибка сборки WAV", e);
            return null;
        }
    }

    private void writeInt(ByteArrayOutputStream out, int val) throws IOException {
        out.write(val & 0xFF); out.write((val >> 8) & 0xFF);
        out.write((val >> 16) & 0xFF); out.write((val >> 24) & 0xFF);
    }
    private void writeShort(ByteArrayOutputStream out, short val) throws IOException {
        out.write(val & 0xFF); out.write((val >> 8) & 0xFF);
    }
    private void writeString(ByteArrayOutputStream out, String val) throws IOException {
        for (int i = 0; i < val.length(); i++) out.write(val.charAt(i));
    }

    public void release() {
        if (tts != null) tts.release();
    }
}