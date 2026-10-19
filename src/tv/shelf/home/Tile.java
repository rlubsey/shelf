package tv.shelf.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;

/**
 * A tvOS-style card: rounded art with no border. Focus lifts it (scale, real elevation shadow)
 * and lays a soft specular sheen across the top, the way Apple TV icons catch the light.
 */
final class Tile extends View {
    private static final float FOCUS_SCALE = 1.14f;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    final Item item;
    final String kind;
    private final CharSequence title;
    private final int cardW, cardH, labelH;
    private final boolean labelAlways, labelOnFocus;
    private final float radius, lift;
    private final RectF card = new RectF();
    private final Path clip = new Path();
    private final Path arrow = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint inner = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint under = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean moving;
    private final boolean app;

    Tile(Context c, Item item, String kind, CharSequence title, int cardW, int cardH, String labels) {
        super(c);
        this.item = item;
        this.kind = kind;
        this.title = title;
        this.cardW = cardW;
        this.cardH = cardH;
        boolean app = Catalog.APPS.equals(kind) || Catalog.RECENT.equals(kind);
        this.app = app;
        labelH = app ? Ui.dp(c, 30) : 0;
        labelAlways = "always".equals(labels);
        labelOnFocus = "focus".equals(labels);
        radius = Math.max(Ui.dp(c, 10), cardH * 0.12f);
        lift = Ui.dp(c, 20);

        fill.setColor(app ? 0xF01E1F24 : 0xD91A1B20);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2.5f));
        ring.setColor(0xFFFFFFFF);
        ring.setPathEffect(new DashPathEffect(new float[] {Ui.dp(c, 8), Ui.dp(c, 5)}, 0));
        dot.setColor(0xFF34C759);
        inner.setColor(Ui.TEXT);
        inner.setTypeface(Ui.MEDIUM);
        inner.setTextAlign(Paint.Align.CENTER);
        inner.setTextSize(Ui.dp(c, app ? 13 : 16));
        under.setColor(Ui.TEXT);
        under.setTypeface(Ui.MEDIUM);
        under.setTextAlign(Paint.Align.CENTER);
        under.setTextSize(Ui.dp(c, 14));
        under.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 1), 0xB3000000);

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, Tile.this.cardW, Tile.this.cardH, radius);
            }
        });
        setElevation(Ui.dp(c, 2));
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setContentDescription(title);
    }

    void setMoving(boolean on) {
        moving = on;
        invalidate();
    }

    boolean isMoving() {
        return moving;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(cardW, cardH + labelH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        card.set(0, 0, cardW, cardH);
        clip.reset();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        sheen.setShader(new LinearGradient(0, 0, cardW * 0.55f, cardH * 0.9f,
                new int[] {0x47FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, new float[] {0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        setPivotX(cardW / 2f);
        setPivotY(cardH / 2f);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        float s = focused ? FOCUS_SCALE : 1f;
        animate().scaleX(s).scaleY(s).translationZ(focused ? lift : 0)
                .setInterpolator(EASE).setDuration(focused ? 180 : 140).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable banner = item.banner, icon = item.icon;
        boolean focused = isFocused();
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(card, fill);
        if (banner != null) {
            banner.setBounds(0, 0, cardW, cardH);
            banner.draw(canvas);
        } else if (icon != null) {
            int size = cardH * 50 / 100;
            int left = (cardW - size) / 2, top = cardH * 11 / 100;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
            drawLine(canvas, inner, cardW / 2f, cardH - cardH * 12 / 100f);
        } else if (!app) {
            drawLine(canvas, inner, cardW / 2f, cardH / 2f - (inner.ascent() + inner.descent()) / 2);
        }
        if (focused && !moving) canvas.drawRect(card, sheen);
        if (moving) canvas.drawColor(0x8C000000);
        canvas.restore();

        if (item.state == TvInputManager.INPUT_STATE_CONNECTED) {
            float r = Ui.dp(getContext(), 3.5f);
            canvas.drawCircle(cardW - r * 3, r * 3, r, dot);
        }
        if (moving) {
            float half = ring.getStrokeWidth() / 2;
            canvas.drawRoundRect(half, half, cardW - half, cardH - half, radius - half, radius - half, ring);
            drawArrows(canvas);
        }
        boolean label = labelAlways || labelOnFocus && focused;
        if (labelH > 0 && label && banner != null) {
            drawLine(canvas, under, cardW / 2f, cardH + labelH - Ui.dp(getContext(), 7));
        }
    }

    private void drawLine(Canvas canvas, TextPaint paint, float x, float baseline) {
        float room = cardW - Ui.dp(getContext(), 12);
        CharSequence s = TextUtils.ellipsize(title, paint, room, TextUtils.TruncateAt.END);
        canvas.drawText(s, 0, s.length(), x, baseline, paint);
    }

    private void drawArrows(Canvas canvas) {
        float h = cardH / 6f, w = h * 0.7f, mid = cardH / 2f, pad = h * 0.8f;
        inner.setStyle(Paint.Style.FILL);
        arrow.reset();
        arrow.moveTo(pad, mid);
        arrow.lineTo(pad + w, mid - h / 2);
        arrow.lineTo(pad + w, mid + h / 2);
        arrow.close();
        arrow.moveTo(cardW - pad, mid);
        arrow.lineTo(cardW - pad - w, mid - h / 2);
        arrow.lineTo(cardW - pad - w, mid + h / 2);
        arrow.close();
        canvas.drawPath(arrow, inner);
    }
}
