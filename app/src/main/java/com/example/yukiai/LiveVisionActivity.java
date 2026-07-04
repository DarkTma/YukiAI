package com.example.yukiai;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.common.util.concurrent.ListenableFuture;

import android.database.Cursor;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class LiveVisionActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 100;

    private final ActivityResultLauncher<String> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    importModelFile(uri);
                }
            }
    );

    private PreviewView viewFinder;
    private TextView yukiSpeechText;
    private EditText userInputText;
    private Switch modelSwitch;
    private Button btnSend;
    private Button btnImportModel; // Добавили кнопку

    private YukiLocalVision yukiLocalVision;
    private YukiBrainManager yukiBrain;

    private long lastSpeechEndTime = 0;
    private boolean isYukiSpeaking = false;
    private List<String> previousObjects = new ArrayList<>();

    private LinearLayout loadingOverlay;
    private ProgressBar progressBar;
    private TextView loadingText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_vision);

        viewFinder = findViewById(R.id.viewFinder);
        yukiSpeechText = findViewById(R.id.yukiSpeechText);
        userInputText = findViewById(R.id.userInputText);
        modelSwitch = findViewById(R.id.modelSwitch);
        btnSend = findViewById(R.id.btnSend);
        btnImportModel = findViewById(R.id.btnImportModel); // Инициализировали кнопку

        yukiLocalVision = new YukiLocalVision(this);
// Инициализация мозга с коллбэком для управления экраном загрузки
        yukiBrain = new YukiBrainManager(this, success -> {
            runOnUiThread(() -> {
                hideLoading();
                if (success) {
                    Toast.makeText(this, "Мозг Юки успешно загружен!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Ошибка: не удалось загрузить модель.", Toast.LENGTH_LONG).show();
                }
            });
        });

        // Вешаем слушатель на кнопку импорта
        btnImportModel.setOnClickListener(v -> openFilePicker());

        // Запрашиваем разрешения и запускаем камеру
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }

        // ... внутри onCreate ...
        loadingOverlay = findViewById(R.id.loadingOverlay);
        progressBar = findViewById(R.id.progressBar);
        loadingText = findViewById(R.id.loadingText);

        // Показываем загрузку при старте приложения
        showLoading("Инициализация мозга...", true);
        yukiBrain = new YukiBrainManager(this, success -> {
            runOnUiThread(() -> {
                hideLoading();
                if (success) {
                    Toast.makeText(this, "Мозг Юки готов!", Toast.LENGTH_SHORT).show();
                }
            });
        });

        startSceneMonitor();

        btnSend.setOnClickListener(v -> {
            String text = userInputText.getText().toString();
            if (text.isEmpty()) return;

            Bitmap currentFrame = viewFinder.getBitmap();

            if (modelSwitch.isChecked()) {
                sendToLocalModel(text, currentFrame);
            } else {
                sendToGeminiCloud(text, currentFrame);
            }
            userInputText.setText("");
        });
    }

    private void importModelFile(Uri uri) {
        // Запускаем UI загрузки
        showLoading("Копирование файла: 0%", false);

        // Переносим копирование гигабайтов в фоновый поток!
        new Thread(() -> {
            try {
                long totalSize = getFileSize(uri);
                InputStream inputStream = getContentResolver().openInputStream(uri);
                File destinationFile = new File(getFilesDir(), "yuki_model.gguf");
                FileOutputStream outputStream = new FileOutputStream(destinationFile);

                byte[] buffer = new byte[8192];
                int length;
                long copied = 0;

                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                    copied += length;

                    if (totalSize > 0) {
                        int progress = (int) ((copied * 100) / totalSize);
                        updateProgress("Копирование файла: " + progress + "%", progress);
                    }
                }

                // ВАЖНО: Принудительно сбрасываем буфер на диск перед закрытием
                outputStream.flush();
                outputStream.getFD().sync(); // Заставляем Android физически записать байты

                outputStream.close();
                inputStream.close();

                // Логируем итоговый размер, чтобы убедиться, что это не пустышка
                long finalSizeMB = destinationFile.length() / (1024 * 1024);
                Log.d("YukiImport", "Файл успешно сохранен. Итоговый размер: " + finalSizeMB + " МБ");

                // Копирование завершено, запускаем загрузку в оперативную память
                runOnUiThread(() -> {
                    showLoading("Загрузка модели в ОЗУ...", true);
                    yukiBrain.reloadModel(LiveVisionActivity.this, success -> {
                        runOnUiThread(() -> {
                            hideLoading();
                            Toast.makeText(this, "Прошивка Юки обновлена!", Toast.LENGTH_SHORT).show();
                        });
                    });
                });

            } catch (Exception e) {
                Log.e("YukiImport", "Ошибка импорта: ", e);
                runOnUiThread(() -> {
                    hideLoading();
                    Toast.makeText(this, "Ошибка при копировании!", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // Вспомогательный метод для получения реального размера файла из Uri
    private long getFileSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                return cursor.getLong(sizeIndex);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    // --- Методы управления UI загрузки ---
    private void showLoading(String text, boolean isIndeterminate) {
        loadingOverlay.setVisibility(View.VISIBLE);
        loadingText.setText(text);
        progressBar.setIndeterminate(isIndeterminate);
        if (!isIndeterminate) {
            progressBar.setProgress(0);
        }
    }

    private void updateProgress(String text, int progress) {
        runOnUiThread(() -> {
            loadingText.setText(text);
            progressBar.setProgress(progress);
        });
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                // Передаем кадры в локальное зрение
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), yukiLocalVision);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e("CameraX", "Ошибка привязки камеры", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Юки нужны глаза (разрешение на камеру)!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startSceneMonitor() {
        new Thread(() -> {
            while (!isDestroyed()) {
                try {
                    Thread.sleep(1000);
                    if (isYukiSpeaking) continue;
                    if (System.currentTimeMillis() - lastSpeechEndTime < 5000) continue;

                    List<String> currentObjects = yukiLocalVision.getCurrentObjects();
                    boolean sceneChanged = hasSignificantChange(previousObjects, currentObjects);

                    if (sceneChanged) {
                        // Сохраняем новое состояние сцены
                        previousObjects = new ArrayList<>(currentObjects);

                        String prompt = "Ты только что заметила эти новые объекты в кадре: " + currentObjects.toString() + ". Коротко и эмоционально отреагируй на это.";

                        // Прыгаем в главный поток, чтобы легально забрать картинку с экрана
                        runOnUiThread(() -> {
                            Bitmap frame = viewFinder.getBitmap(); // Теперь Android не будет ругаться!
                            sendToLocalModel(prompt, frame);
                        });
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private boolean hasSignificantChange(List<String> oldList, List<String> newList) {
        if (newList.isEmpty()) return false;
        for (String item : newList) {
            if (!oldList.contains(item)) return true;
        }
        return false;
    }

    private void sendToLocalModel(String text, Bitmap frame) {
        startSpeaking();
        List<String> visibleItems = yukiLocalVision.getCurrentObjects();
        String contextString = visibleItems.isEmpty() ? "Ничего особенного" : String.join(", ", visibleItems);

        yukiBrain.askYuki(text, contextString, new YukiBrainManager.BrainCallback() {
            @Override
            public void onThinking() {
                runOnUiThread(() -> {
                    yukiSpeechText.setTextColor(android.graphics.Color.parseColor("#B388FF"));
                    yukiSpeechText.setText("Юки: Думаю...");
                });
            }

            @Override
            public void onResponse(String responseText) {
                runOnUiThread(() -> finishSpeaking(responseText));
                // TODO: Передать responseText в AnimeTtsManager
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> finishSpeaking("[Системная ошибка]: " + error));
            }
        });
    }

    private void sendToGeminiCloud(String text, Bitmap frame) {
        startSpeaking();
        yukiSpeechText.setText("Юки (Gemini Flash): Отправляю в облако...");
        // TODO: Твой вызов Gemini API
        viewFinder.postDelayed(() -> finishSpeaking("Это выглядит потрясающе, бро!"), 3000);
    }

    private void startSpeaking() {
        isYukiSpeaking = true;
        btnSend.setEnabled(false);
    }

    private void finishSpeaking(String text) {
        yukiSpeechText.setText("Юки: " + text);
        isYukiSpeaking = false;
        lastSpeechEndTime = System.currentTimeMillis();
        btnSend.setEnabled(true);
    }

    private void openFilePicker() {
        filePickerLauncher.launch("*/*");
    }
}