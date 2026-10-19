package tv.shelf.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;

import java.util.Date;
import java.util.Map;

/**
 * A slim glass pill for the top bar: teams and score on the first line, league and status
 * (live minute, FT, or kickoff time) on the second. Soccer lists the home team first; US
 * sports list the away team first, the way each sport writes its scores.
 */
final class ScoreCard extends Glass {
    static final int WIDTH_DP = 212, HEIGHT_DP = 52;

    private final Sports.Game game;
    private final String message;
    private final Map<String, Bitmap> logos;
    private final int w, h, pad, logo;
    private final TextPaint teamPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint scorePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint live = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    ScoreCard(Context c, Sports.Game game, String message, Map<String, Bitmap> logos) {
        super(c, 14);
        this.game = game;
        this.message = message;
        this.logos = logos;
        w = Ui.dp(c, WIDTH_DP);
        h = Ui.dp(c, HEIGHT_DP);
        pad = Ui.dp(c, 12);
        logo = Ui.dp(c, 16);
        teamPaint.setTextSize(Ui.dp(c, 14));
        teamPaint.setTypeface(Ui.MEDIUM);
        teamPaint.setColor(Ui.TEXT);
        scorePaint.setTextSize(Ui.dp(c, 16));
        scorePaint.setTypeface(Ui.MEDIUM);
        scorePaint.setColor(Ui.TEXT);
        scorePaint.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(Ui.dp(c, 11));
        small.setTypeface(Ui.MEDIUM);
        small.setColor(Ui.MUTED);
        small.setTextAlign(Paint.Align.CENTER);
        live.setColor(0xFFFF453A);
        setContentDescription(game == null ? message : describe(game));
    }

    static boolean homeFirst(Sports.Game g) {
        return "soccer".equals(g.league.sport);
    }

    static String describe(Sports.Game g) {
        Sports.Side a = homeFirst(g) ? g.home : g.away, b = homeFirst(g) ? g.away : g.home;
        boolean scores = g.live() || g.done();
        return label(a) + (scores ? " " + a.score + " – " + b.score + " " : " vs ") + label(b);
    }

    static String label(Sports.Side s) {
        return s.abbr.isEmpty() ? s.name : s.abbr;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (game == null) {
            CharSequence s = TextUtils.ellipsize(message, small, w - 2 * pad, TextUtils.TruncateAt.END);
            canvas.drawText(s, 0, s.length(), w / 2f, h / 2f - (small.ascent() + small.descent()) / 2, small);
            return;
        }
        Sports.Side left = homeFirst(game) ? game.home : game.away;
        Sports.Side right = homeFirst(game) ? game.away : game.home;
        boolean scores = game.live() || game.done();

        float line1 = Ui.dp(getContext(), 24);
        float mid = line1 + (teamPaint.ascent() + teamPaint.descent()) / 2;
        String center = scores ? left.score + " – " + right.score : "vs";
        canvas.drawText(center, w / 2f, line1, scorePaint);
        float centerHalf = scorePaint.measureText(center) / 2 + Ui.dp(getContext(), 8);

        drawLogo(canvas, left, pad, mid);
        teamPaint.setTextAlign(Paint.Align.LEFT);
        drawName(canvas, left, pad + logo + Ui.dp(getContext(), 6), w / 2f - centerHalf, line1, false);
        drawLogo(canvas, right, w - pad - logo, mid);
        drawName(canvas, right, w / 2f + centerHalf, w - pad - logo - Ui.dp(getContext(), 6), line1, true);

        String status = game.league.abbr + "  ·  " + status();
        float line2 = h - Ui.dp(getContext(), 9);
        if (game.live()) {
            small.setColor(Ui.TEXT);
            float half = small.measureText(status) / 2;
            float r = Ui.dp(getContext(), 3);
            canvas.drawCircle(w / 2f - half - r * 2.4f, line2 + (small.ascent() + small.descent()) / 2, r, live);
        }
        canvas.drawText(status, w / 2f, line2, small);
        small.setColor(Ui.MUTED);
    }

    private void drawLogo(Canvas canvas, Sports.Side side, float x, float mid) {
        Bitmap b = side.logo.isEmpty() ? null : logos.get(side.logo);
        if (b == null) return;
        box.set(x, mid - logo / 2f, x + logo, mid + logo / 2f);
        canvas.drawBitmap(b, null, box, logoPaint);
    }

    private void drawName(Canvas canvas, Sports.Side side, float from, float to, float baseline, boolean right) {
        CharSequence s = TextUtils.ellipsize(label(side), teamPaint, Math.max(0, to - from), TextUtils.TruncateAt.END);
        teamPaint.setTextAlign(right ? Paint.Align.RIGHT : Paint.Align.LEFT);
        canvas.drawText(s, 0, s.length(), right ? to : from, baseline, teamPaint);
        teamPaint.setTextAlign(Paint.Align.LEFT);
    }

    private String status() {
        if (game.live() || game.done()) return game.detail.isEmpty() ? (game.done() ? "FT" : "LIVE") : game.detail;
        if (game.start <= 0) return game.detail;
        Context c = getContext();
        String time = DateFormat.getTimeFormat(c).format(new Date(game.start));
        if (DateUtils.isToday(game.start)) return time;
        return DateFormat.format("EEE", game.start) + " " + time;
    }
}
