package com.example.yukiai;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.VideoView;

public class CenterCropVideoView extends VideoView {
    public CenterCropVideoView(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getDefaultSize(0, widthMeasureSpec);
        int height = getDefaultSize(0, heightMeasureSpec);

        // Эти значения должны прийти от видео.
        // Если они еще не загружены, используем стандарт.
        if (mVideoWidth > 0 && mVideoHeight > 0) {
            if (mVideoWidth * height > width * mVideoHeight) {
                // Видео шире экрана — расширяем View по горизонтали за края
                setMeasuredDimension(mVideoWidth * height / mVideoHeight, height);
            } else {
                // Видео выше экрана — расширяем по вертикали
                setMeasuredDimension(width, mVideoHeight * width / mVideoWidth);
            }
        } else {
            setMeasuredDimension(width, height);
        }
    }

    // Нам нужно вытащить размеры видео из MediaPlayer
    private int mVideoWidth;
    private int mVideoHeight;

    public void setVideoSize(int width, int height) {
        this.mVideoWidth = width;
        this.mVideoHeight = height;
        requestLayout(); // Пересчитать размеры
    }
}