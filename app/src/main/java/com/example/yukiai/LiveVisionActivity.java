package com.example.yukiai;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
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

import java.io.ByteArrayOutputStream;
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

    private Messenger brainService;
    private boolean brainBound = false;
    private boolean modelReady = false;

    private YukiLocalVision yukiLocalVision;

    private long lastSpeechEndTime = 0;
//    private boolean isYukiSpeaking = false;
    private List<String> previousObjects = new ArrayList<>();

    private LinearLayout loadingOverlay;
    private ProgressBar progressBar;
    private TextView loadingText;

    private volatile boolean isYukiSpeaking = false;

    private final Messenger clientMessenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            String text = msg.getData().getString("text");
            switch (msg.what) {
                case YukiBrainService.MSG_THINKING:
                    yukiSpeechText.setTextColor(android.graphics.Color.parseColor("#B388FF"));
                    yukiSpeechText.setText("Юки: Думаю...");
                    break;
                case YukiBrainService.MSG_RESPONSE:
                    finishSpeaking(text);
                    break;
                case YukiBrainService.MSG_ERROR:
                    finishSpeaking("[Системная ошибка]: " + text);
                    break;
                case YukiBrainService.MSG_MODEL_LOADED:
                    modelReady = "1".equals(text);
                    hideLoading();
                    Toast.makeText(LiveVisionActivity.this,
                            modelReady ? "Мозг Юки готов!" : "Ошибка загрузки модели",
                            Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    });

    private final ServiceConnection brainConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            brainService = new Messenger(binder);
            brainBound = true;
            try {
                Message msg = Message.obtain(null, YukiBrainService.MSG_REGISTER_CLIENT);
                msg.replyTo = clientMessenger;
                brainService.send(msg);
            } catch (RemoteException e) {
                Log.e("LiveVision", "Регистрация в сервисе не удалась", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            brainService = null;
            brainBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_vision);

        viewFinder = findViewById(R.id.viewFinder);
        yukiSpeechText = findViewById(R.id.yukiSpeechText);
        userInputText = findViewById(R.id.userInputText);
        modelSwitch = findViewById(R.id.modelSwitch);
        btnSend = findViewById(R.id.btnSend);
        btnImportModel = findViewById(R.id.btnImportModel);

        loadingOverlay = findViewById(R.id.loadingOverlay);
        progressBar = findViewById(R.id.progressBar);
        loadingText = findViewById(R.id.loadingText);

        yukiLocalVision = new YukiLocalVision(this);

        btnImportModel.setOnClickListener(v -> openFilePicker());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }

        showLoading("Инициализация мозга...", true);
        startSceneMonitor();

        btnSend.setOnClickListener(v -> {
            String text = userInputText.getText().toString();
            if (text.isEmpty()) return;

            if (modelSwitch.isChecked()) {
                sendToLocalModel(text);
            } else {
                sendToGeminiCloud(text, viewFinder.getBitmap());
            }
            userInputText.setText("");
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, YukiBrainService.class);
        startForegroundService(intent);
        bindService(intent, brainConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (brainBound) {
            unbindService(brainConnection);
            brainBound = false;
        }
        // сервис не останавливаем — пусть живёт в фоне
    }

    private void importModelFile(Uri uri) {
        // Запускаем UI загрузки
        showLoading("Копирование файла: 0%", false);

        // Переносим копирование гигабайтов в фоновый поток!
        new Thread(() -> {
            try {
                long totalSize = getFileSize(uri);
                InputStream inputStream = getContentResolver().openInputStream(uri);
                File destinationFile = new File(getFilesDir(), "gemma4_e4b.litertlm");
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
                    sendReloadModel();
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

    private void sendReloadModel() {
        if (!brainBound) return;
        try {
            brainService.send(Message.obtain(null, YukiBrainService.MSG_RELOAD_MODEL));
        } catch (RemoteException e) {
            Log.e("LiveVision", "reload failed", e);
        }
    }

    // вызывается ТОЛЬКО на UI-потоке (viewFinder.getBitmap() того требует)
    private byte[] captureCompressedFrame() {
        Bitmap frame = viewFinder.getBitmap();
        if (frame == null) return null;

        int targetSize = 768; // модель обычно даунсемплит сама, крупнее 768 не имеет смысла
        int w = frame.getWidth();
        int h = frame.getHeight();
        float scale = Math.min((float) targetSize / w, (float) targetSize / h);
        int newW = Math.round(w * scale);
        int newH = Math.round(h * scale);

        Bitmap scaled = Bitmap.createScaledBitmap(frame, newW, newH, true); // сохраняем пропорции

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, baos); // подняли качество 85→90
        return baos.toByteArray();
    }

    private void sendToLocalModel(String text) {
        if (!brainBound || !modelReady) {
            finishSpeaking("Мозг ещё не готов, подожди секунду");
            return;
        }
        startSpeaking();

        // getBitmap() требует UI-поток — обязательно прыгаем сюда,
        // даже если sendToLocalModel вызван из фонового startSceneMonitor
        runOnUiThread(() -> {
            byte[] imageBytes = captureCompressedFrame();
            dispatchAsk(text, imageBytes);
        });
    }

    private void dispatchAsk(String text, byte[] imageBytes) {
        Bundle data = new Bundle();
        data.putString("text", text);
        if (imageBytes != null) data.putByteArray("image", imageBytes);

        Message msg = Message.obtain(null, YukiBrainService.MSG_ASK);
        msg.setData(data);
        try {
            brainService.send(msg);
        } catch (RemoteException e) {
            finishSpeaking("[Ошибка связи с мозгом]: " + e.getMessage());
        }
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
                        previousObjects = new ArrayList<>(currentObjects);
                        String prompt = "Ты только что заметила эти новые объекты в кадре: " + currentObjects.toString() + ". Коротко и эмоционально отреагируй на это.";
                        sendToLocalModel(prompt);
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

    private void sendToGeminiCloud(String text, Bitmap frame) {
        startSpeaking();
        yukiSpeechText.setText("Юки (Gemini Flash): Отправляю в облако...");
        // TODO: Твой вызов Gemini API
        viewFinder.postDelayed(() -> finishSpeaking("Это выглядит потрясающе, бро!"), 3000);
    }

    private void startSpeaking() {
        runOnUiThread(() -> {
            isYukiSpeaking = true;
            btnSend.setEnabled(false);
        });
    }

    private void finishSpeaking(String text) {
        runOnUiThread(() -> {
            yukiSpeechText.setText("Юки: " + text);
            isYukiSpeaking = false;
            lastSpeechEndTime = System.currentTimeMillis();
            btnSend.setEnabled(true);
        });
    }

    private void openFilePicker() {
        filePickerLauncher.launch("*/*");
    }
}