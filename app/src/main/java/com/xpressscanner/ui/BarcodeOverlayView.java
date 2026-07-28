package com.xpressscanner.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

public class BarcodeOverlayView extends View {
    private final Paint bracketPaint;
    private final Paint boxPaint;
    private final RectF calculatedRect = new RectF();
    private boolean hasTarget = false;
    private final float padding = 40f;
    private final float bracketLength = 60f;

    public BarcodeOverlayView(Context context) {
        super(context);

        bracketPaint = new Paint();
        bracketPaint.setColor(AppColors.color("cardStroke"));
        bracketPaint.setStyle(Paint.Style.STROKE);
        bracketPaint.setStrokeWidth(12f);
        bracketPaint.setStrokeCap(Paint.Cap.ROUND);
        bracketPaint.setAntiAlias(true);

        boxPaint = new Paint();
        boxPaint.setColor(AppColors.color("accentGreen"));
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(8f);
        boxPaint.setAntiAlias(true);
    }

    public void updateBox(Rect rect, int imageWidth, int imageHeight) {
        float scaleX = (float) getWidth() / imageHeight;
        float scaleY = (float) getHeight() / imageWidth;

        calculatedRect.left = rect.left * scaleX;
        calculatedRect.right = rect.right * scaleX;
        calculatedRect.top = rect.top * scaleY;
        calculatedRect.bottom = rect.bottom * scaleY;

        hasTarget = true;
        postInvalidate();
    }

    public void clear() {
        if (hasTarget) {
            hasTarget = false;
            postInvalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();

        canvas.drawLine(padding, padding + bracketLength, padding, padding, bracketPaint);
        canvas.drawLine(padding, padding, padding + bracketLength, padding, bracketPaint);

        canvas.drawLine(w - padding - bracketLength, padding, w - padding, padding, bracketPaint);
        canvas.drawLine(w - padding, padding, w - padding, padding + bracketLength, bracketPaint);

        canvas.drawLine(padding, h - padding - bracketLength, padding, h - padding, bracketPaint);
        canvas.drawLine(padding, h - padding, padding + bracketLength, h - padding, bracketPaint);

        canvas.drawLine(w - padding - bracketLength, h - padding, w - padding, h - padding, bracketPaint);
        canvas.drawLine(w - padding, h - padding, w - padding, h - padding - bracketLength, bracketPaint);

        if (hasTarget) {
            canvas.drawRoundRect(calculatedRect, 16f, 16f, boxPaint);
        }
    }
}
