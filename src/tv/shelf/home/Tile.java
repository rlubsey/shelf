package tv.shelf.home;

import android.content.Context;
import android.graphics.Bitmap;
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

    // "Recently used" with now-playing info: artwork, title and subtitle, plus the app's badge
    private Bitmap media;
    private String mediaTitle, mediaSub;
    private final Paint mediaPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint();
    private final Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mSub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path badgeClip = new Path();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

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

        mTitle.setColor(0xFFFFFFFF);
        mTitle.setTypeface(Ui.MEDIUM);
        mTitle.setTextSize(Math.max(Ui.dp(c, 12), cardH * 0.13f));
        mTitle.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1), 0xB3000000);
        mSub.setColor(0xCCFFFFFF);
        mSub.setTypeface(Ui.MEDIUM);
        mSub.setTextSize(Math.max(Ui.dp(c, 10), cardH * 0.1f));
        mSub.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1), 0xB3000000);
        badgeBg.setColor(0xE61C1D22);

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

    /** Shows what last played in this app instead of the plain app banner. */
    void setMedia(Bitmap art, String title, String sub) {
        media = art;
        mediaTitle = title;
        mediaSub = sub == null || sub.isEmpty() ? null : sub;
        setContentDescription(title + (mediaSub == null ? "" : ", " + mediaSub) + ", in " + this.title);
        invalidate();
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
        fade.setShader(new LinearGradient(0, cardH * 0.35f, 0, cardH,
                new int[] {0x00000000, 0xE6000000}, null, Shader.TileMode.CLAMP));
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
        if (mediaTitle != null) {
            drawMedia(canvas, banner, icon);
        } else if (banner != null) {
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
        if (labelH > 0 && label && (banner != null || mediaTitle != null)) {
            drawLine(canvas, under, cardW / 2f, cardH + labelH - Ui.dp(getContext(), 7));
        }
    }

    private void drawMedia(Canvas canvas, Drawable banner, Drawable icon) {
        if (media != null) {
            // centre-crop the artwork into the card
            float scale = Math.max(cardW / (float) media.getWidth(), cardH / (float) media.getHeight());
            int sw = Math.round(cardW / scale), sh = Math.round(cardH / scale);
            int sx = (media.getWidth() - sw) / 2, sy = (media.getHeight() - sh) / 2;
            src.set(sx, sy, sx + sw, sy + sh);
            dst.set(0, 0, cardW, cardH);
            canvas.drawBitmap(media, src, dst, mediaPaint);
        } else if (banner != null) {
            banner.setBounds(0, 0, cardW, cardH);
            banner.setAlpha(110);
            banner.draw(canvas);
            banner.setAlpha(255);
        }
        canvas.drawRect(0, cardH * 0.35f, cardW, cardH, fade);

        // the app's badge, top left
        float m = Ui.dp(getContext(), 8);
        float bw = cardW * 0.3f, bh = bw * 9 / 16f;
        dst.set(m, m, m + bw, m + bh);
        float r = Ui.dp(getContext(), 5);
        canvas.drawRoundRect(dst, r, r, badgeBg);
        Drawable d = banner != null ? banner : icon;
        if (d != null) {
            canvas.save();
            badgeClip.reset();
            badgeClip.addRoundRect(dst, r, r, Path.Direction.CW);
            canvas.clipPath(badgeClip);
            if (banner != null) {
                d.setBounds(Math.round(dst.left), Math.round(dst.top), Math.round(dst.right), Math.round(dst.bottom));
            } else {
                int s = Math.round(bh * 0.8f), cx = Math.round(dst.centerX()), cy = Math.round(dst.centerY());
                d.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            }
            d.draw(canvas);
            canvas.restore();
        }

        // title and subtitle, bottom left
        float pad = Ui.dp(getContext(), 10), room = cardW - 2 * pad;
        float base = cardH - pad;
        if (mediaSub != null) {
            CharSequence s = TextUtils.ellipsize(mediaSub, mSub, room, TextUtils.TruncateAt.END);
            canvas.drawText(s, 0, s.length(), pad, base, mSub);
            base -= mSub.getTextSize() * 1.25f;
        }
        CharSequence t = TextUtils.ellipsize(mediaTitle, mTitle, room, TextUtils.TruncateAt.END);
        canvas.drawText(t, 0, t.length(), pad, base, mTitle);
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
