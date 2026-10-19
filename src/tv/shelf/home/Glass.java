package tv.shelf.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The frosted dock. It draws the blurred wallpaper lined up with the real one behind it, so it
 * reads as glass: wherever the dock sits on screen (including mid-scroll), it shows a soft copy
 * of exactly what is underneath, plus a light tint and a hairline edge.
 */
class Glass extends View {
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint();
    private final Paint plain = new Paint();
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final Matrix matrix = new Matrix();
    private final int[] at = new int[2];
    private final float radius;
    private Bitmap source;

    Glass(Context c) {
        this(c, 26);
    }

    Glass(Context c, float radiusDp) {
        super(c);
        radius = Ui.dp(c, radiusDp);
        tint.setColor(0x2EFFFFFF);
        plain.setColor(0x24FFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1, Ui.dp(c, 1)));
        edge.setColor(0x26FFFFFF);
        setFocusable(false);
    }

    void setSource(Bitmap b) {
        source = b;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(clip);
        Bitmap b = source;
        View window = getRootView();
        if (b != null && window.getWidth() > 0) {
            int screenW = window.getWidth(), screenH = window.getHeight();
            float scale = Math.max(screenW / (float) b.getWidth(), screenH / (float) b.getHeight());
            float dx = (screenW - b.getWidth() * scale) / 2f;
            float dy = (screenH - b.getHeight() * scale) / 2f;
            getLocationInWindow(at);
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx - at[0], dy - at[1]);
            canvas.drawBitmap(b, matrix, image);
            canvas.drawRect(rect, tint);
        } else {
            canvas.drawRect(rect, plain);
        }
        canvas.restore();
        float half = edge.getStrokeWidth() / 2;
        canvas.drawRoundRect(half, half, rect.right - half, rect.bottom - half, radius - half, radius - half, edge);
    }
}
