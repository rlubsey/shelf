package tv.shelf.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** A small line-art weather symbol, drawn in code so it stays crisp at any size. */
final class WeatherIcon extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final int size;
    private Weather.Kind kind = Weather.Kind.CLEAR;
    private boolean day = true;

    WeatherIcon(Context c, int sizeDp) {
        super(c);
        size = Ui.dp(c, sizeDp);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(Ui.TEXT);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFE9EAEE);
        sun.setStyle(Paint.Style.FILL);
        sun.setColor(0xFFFFC94A);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // shadow layer on lines
        line.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1), 0x80000000);
    }

    void set(Weather.Kind kind, boolean day) {
        this.kind = kind;
        this.day = day;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = size;
        line.setStrokeWidth(Math.max(2f, s * 0.07f));
        switch (kind) {
            case CLEAR:
                orb(c, s * 0.5f, s * 0.5f, s * 0.2f);
                break;
            case PARTLY:
                orb(c, s * 0.38f, s * 0.36f, s * 0.15f);
                cloud(c, s * 0.18f, s * 0.42f, s * 0.78f);
                break;
            case CLOUDY:
                cloud(c, s * 0.08f, s * 0.28f, s * 0.86f);
                break;
            case FOG:
                cloud(c, s * 0.12f, s * 0.14f, s * 0.78f);
                for (int i = 0; i < 2; i++) {
                    float y = s * (0.74f + i * 0.14f);
                    c.drawLine(s * (0.18f + i * 0.1f), y, s * (0.82f - i * 0.04f), y, line);
                }
                break;
            case RAIN:
                cloud(c, s * 0.1f, s * 0.12f, s * 0.82f);
                for (int i = 0; i < 3; i++) {
                    float x = s * (0.32f + i * 0.18f);
                    c.drawLine(x, s * 0.72f, x - s * 0.06f, s * 0.9f, line);
                }
                break;
            case SNOW:
                cloud(c, s * 0.1f, s * 0.12f, s * 0.82f);
                for (int i = 0; i < 3; i++) c.drawCircle(s * (0.3f + i * 0.2f), s * (0.8f + (i % 2) * 0.08f), s * 0.04f, fill);
                break;
            case STORM:
                cloud(c, s * 0.1f, s * 0.1f, s * 0.82f);
                path.reset();
                path.moveTo(s * 0.54f, s * 0.6f);
                path.lineTo(s * 0.42f, s * 0.78f);
                path.lineTo(s * 0.54f, s * 0.78f);
                path.lineTo(s * 0.46f, s * 0.95f);
                c.drawPath(path, line);
                break;
        }
    }

    /** The sun by day, a crescent moon by night. */
    private void orb(Canvas c, float cx, float cy, float r) {
        if (day) {
            c.drawCircle(cx, cy, r, sun);
            Paint rays = new Paint(line);
            rays.setColor(0xFFFFC94A);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                float x1 = cx + (float) Math.cos(a) * r * 1.45f, y1 = cy + (float) Math.sin(a) * r * 1.45f;
                float x2 = cx + (float) Math.cos(a) * r * 1.9f, y2 = cy + (float) Math.sin(a) * r * 1.9f;
                c.drawLine(x1, y1, x2, y2, rays);
            }
        } else {
            path.reset();
            oval.set(cx - r * 1.3f, cy - r * 1.3f, cx + r * 1.3f, cy + r * 1.3f);
            path.addArc(oval, 60, 260);
            Path cut = new Path();
            oval.offset(r * 0.7f, -r * 0.35f);
            cut.addOval(oval, Path.Direction.CW);
            path.op(cut, Path.Op.DIFFERENCE);
            Paint moon = new Paint(sun);
            moon.setColor(0xFFE8E4D2);
            c.drawPath(path, moon);
        }
    }

    /** A cloud filling the box from (x, top) to (right, roughly top + width * 0.6). */
    private void cloud(Canvas c, float x, float top, float right) {
        float w = right - x, base = top + w * 0.62f;
        path.reset();
        path.addCircle(x + w * 0.3f, base - w * 0.2f, w * 0.2f, Path.Direction.CW);
        path.addCircle(x + w * 0.56f, base - w * 0.32f, w * 0.27f, Path.Direction.CW);
        path.addCircle(x + w * 0.8f, base - w * 0.17f, w * 0.17f, Path.Direction.CW);
        oval.set(x + w * 0.1f, base - w * 0.2f, x + w * 0.97f, base);
        path.addRoundRect(oval, w * 0.1f, w * 0.1f, Path.Direction.CW);
        c.drawPath(path, fill);
    }
}
