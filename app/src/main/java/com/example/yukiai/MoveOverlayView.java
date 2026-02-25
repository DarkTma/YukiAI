package com.example.yukiai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public class MoveOverlayView extends View {
    private Paint paint;
    private float startX, startY, endX, endY;

    public MoveOverlayView(Context context, float sx, float sy, float ex, float ey) {
        super(context);
        this.startX = sx; this.startY = sy;
        this.endX = ex; this.endY = ey;

        paint = new Paint();
        // Фиолетовый неон (цвет Широ)
        paint.setColor(Color.parseColor("#D187FF"));
        paint.setStrokeWidth(14f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setAntiAlias(true);

        // Добавляем свечение (ShadowLayer)
        paint.setShadowLayer(20, 0, 0, Color.parseColor("#AA00FF"));
        setLayerType(LAYER_TYPE_SOFTWARE, null); // Нужно для тени
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Рисуем саму линию
        canvas.drawLine(startX, startY, endX, endY, paint);

        // Рисуем маленькую точку в начале и большую в конце
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(startX, startY, 8, paint);
        canvas.drawCircle(endX, endY, 20, paint);
    }
}