package com.anqu.ssh;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

final class UiIcon extends Drawable {
    enum Kind { BACK, FORWARD, SETTINGS, MORE, FOLDER, FILE, LINK, SELECT, CHECK, PLUS, UPLOAD, DOWNLOAD, UP, REFRESH, TRASH, EDIT, TERMINAL, CLOSE }

    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    UiIcon(Kind kind, int color) {
        this.kind = kind;
        paint.setColor(color);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
    }

    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        switch (kind) {
            case BACK -> { line(canvas, 11, 6, 5, 12, 11, 18); line(canvas, 5, 12, 20, 12); }
            case FORWARD -> line(canvas, 9, 6, 15, 12, 9, 18);
            case SETTINGS -> {
                line(canvas, 4, 6, 20, 6);
                line(canvas, 4, 12, 20, 12);
                line(canvas, 4, 18, 20, 18);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(8, 6, 2.4f, paint);
                canvas.drawCircle(15, 12, 2.4f, paint);
                canvas.drawCircle(10, 18, 2.4f, paint);
                paint.setStyle(Paint.Style.STROKE);
            }
            case MORE -> { dot(canvas, 6); dot(canvas, 12); dot(canvas, 18); }
            case FOLDER -> line(canvas, 3, 7, 3, 19, 21, 19, 21, 8, 12, 8, 10, 5, 3, 5, 3, 7);
            case FILE -> {
                line(canvas, 6, 3, 14, 3, 19, 8, 19, 21, 6, 21, 6, 3);
                line(canvas, 14, 3, 14, 8, 19, 8);
                line(canvas, 10, 15, 15, 15);
            }
            case LINK -> {
                line(canvas, 12, 7, 17, 7, 20, 10, 20, 14, 17, 17, 12, 17);
                line(canvas, 12, 7, 7, 7, 4, 10, 4, 14, 7, 17, 12, 17);
                line(canvas, 9, 12, 15, 12);
            }
            case SELECT -> {
                canvas.drawRoundRect(4, 4, 20, 20, 4, 4, paint);
                line(canvas, 8, 12, 11, 15, 16, 9);
            }
            case CHECK -> line(canvas, 6, 12, 10, 16, 18, 8);
            case PLUS -> { line(canvas, 12, 5, 12, 19); line(canvas, 5, 12, 19, 12); }
            case UPLOAD -> {
                line(canvas, 7, 9, 12, 4, 17, 9);
                line(canvas, 12, 4, 12, 16);
                line(canvas, 5, 16, 5, 20, 19, 20, 19, 16);
            }
            case DOWNLOAD -> {
                line(canvas, 7, 11, 12, 16, 17, 11);
                line(canvas, 12, 4, 12, 16);
                line(canvas, 5, 16, 5, 20, 19, 20, 19, 16);
            }
            case UP -> {
                line(canvas, 6, 11, 12, 5, 18, 11);
                line(canvas, 12, 5, 12, 20);
            }
            case REFRESH -> {
                canvas.drawArc(5, 5, 19, 19, -25, 298, false, paint);
                line(canvas, 15, 5, 19, 5, 19, 9);
            }
            case TRASH -> {
                line(canvas, 4, 6, 20, 6);
                line(canvas, 9, 6, 9, 3, 15, 3, 15, 6);
                line(canvas, 6, 6, 7, 21, 17, 21, 18, 6);
                line(canvas, 10, 10, 10, 17); line(canvas, 14, 10, 14, 17);
            }
            case EDIT -> line(canvas, 5, 15, 16, 4, 20, 8, 9, 19, 4, 20, 5, 15);
            case TERMINAL -> {
                line(canvas, 5, 7, 10, 12, 5, 17); line(canvas, 12, 17, 19, 17);
            }
            case CLOSE -> { line(canvas, 6, 6, 18, 18); line(canvas, 18, 6, 6, 18); }
        }
        canvas.restoreToCount(save);
    }

    private void dot(Canvas canvas, int x) {
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x, 12, 1.4f, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    private void line(Canvas canvas, float... points) {
        path.reset();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        canvas.drawPath(path, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 24; }
    @Override public int getIntrinsicHeight() { return 24; }
}
