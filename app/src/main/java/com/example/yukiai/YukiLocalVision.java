package com.example.yukiai;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;
import com.google.mediapipe.tasks.components.containers.Detection;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.ArrayList;
import java.util.List;

public class YukiLocalVision implements ImageAnalysis.Analyzer {

    private static final String TAG = "YukiVision";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ObjectDetector objectDetector;
    private volatile List<String> currentObjects = new ArrayList<>();
    private long lastAnalyzeTime = 0;

    public YukiLocalVision(Context context) {
        // Запускаем тяжелую загрузку модели в фоне!
        executor.execute(() -> {
            try {
                BaseOptions baseOptions = BaseOptions.builder()
                        .setModelAssetPath("models/efficientdet_lite0.tflite")
                        .build();

                ObjectDetector.ObjectDetectorOptions options = ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setResultListener(this::onResults)
                        .setErrorListener(this::onError)
                        .setMaxResults(3)
                        .setScoreThreshold(0.5f)
                        .build();

                this.objectDetector = ObjectDetector.createFromOptions(context, options);
                Log.d(TAG, "ObjectDetector успешно создан в фоне!");

            } catch (Exception e) {
                Log.e(TAG, "Ошибка загрузки модели", e);
            }
        });
    }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAnalyzeTime < 500 || objectDetector == null) {
            imageProxy.close();
            return;
        }
        lastAnalyzeTime = currentTime;

        try {
            Bitmap bitmap = imageProxy.toBitmap();
            MPImage mpImage = new BitmapImageBuilder(bitmap).build();

            long frameTimestamp = imageProxy.getImageInfo().getTimestamp();
            objectDetector.detectAsync(mpImage, frameTimestamp);

        } catch (Exception e) {
            Log.e(TAG, "Ошибка анализа кадра", e);
        } finally {
            imageProxy.close();
        }
    }

    private void onResults(ObjectDetectorResult result, MPImage input) {
        List<String> detectedNames = new ArrayList<>();
        for (Detection detection : result.detections()) {
            detectedNames.add(detection.categories().get(0).categoryName());
        }
        currentObjects = detectedNames;
    }

    private void onError(RuntimeException error) {
        Log.e(TAG, "Ошибка детекции объектов", error);
    }

    public List<String> getCurrentObjects() {
        return currentObjects;
    }
}