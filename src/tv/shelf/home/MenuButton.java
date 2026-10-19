package tv.shelf.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/** The round ☰ button in the top bar: frosted when idle, a white disc with dark lines when focused. */
final class MenuButton extends View {
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bars = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    private boolean badge;

    MenuButton(Context c) {
        super(c);
        size = Ui.dp(c, 40);
        bars.setStrokeCap(Paint.Cap.ROUND);
        bars.setStrokeWidth(Ui.dp(c, 2.2f));
        dot.setColor(0xFFFF453A);
        setFocusable(true);
        setClickable(true);
        setContentDescription("Menu");
    }

    /** A red dot when there is something new in the menu (an update). */
    void setBadge(boolean on) {
        badge = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f).setDuration(140).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean f = isFocused();
        float r = size / 2f;
        disc.setColor(f ? 0xFFFFFFFF : 0x38FFFFFF);
        c.drawCircle(r, r, r, disc);
        bars.setColor(f ? 0xFF111114 : Ui.TEXT);
        float w = size * 0.2f;
        for (int i = -1; i <= 1; i++) {
            float y = r + i * size * 0.13f;
            c.drawLine(r - w, y, r + w, y, bars);
        }
        if (badge) c.drawCircle(size * 0.82f, size * 0.18f, size * 0.1f, dot);
    }
}
