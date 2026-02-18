package com.example.yukiai;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        VideoView videoView = findViewById(R.id.videoView);

        // Указываем путь к видео в папке raw
        // Замените "intro_video" на имя вашего файла
        String videoPath = "android.resource://" + getPackageName() + "/" + R.raw.opening;
        Uri uri = Uri.parse(videoPath);
        videoView.setVideoURI(uri);

        // Слушатель окончания видео
        videoView.setOnCompletionListener(mp -> {
            // Когда видео закончилось, переходим на HomeActivity
            startNextActivity();
        });

        // Запускаем видео
        videoView.start();
    }

    private void startNextActivity() {
        if (isFinishing()) return;

        Intent intent = new Intent(MainActivity.this, HomeActivity.class);
        startActivity(intent);

        // Обязательно вызываем finish(), чтобы пользователь не мог
        // вернуться на видео-заставку кнопкой "Назад"
        finish();
    }
}