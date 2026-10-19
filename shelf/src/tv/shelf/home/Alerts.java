package tv.shelf.home;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Score alerts over whatever is on screen (YouTube, Prime Video, an HDMI input). Runs inside
 * the HOME-button accessibility service, which may draw TYPE_ACCESSIBILITY_OVERLAY windows
 * without any extra permission. The banner is not focusable or touchable, so the remote keeps
 * controlling the app underneath.
 *
 * Only favorite teams' games are watched: every minute while one is live or about to start,
 * every ten minutes otherwise, and not at all when alerts are off or no team is followed.
 * The first look at a game never alerts, so starting the TV mid-match does not replay goals.
 */
final class Alerts {
    private static final long LIVE_MS = 60_000, IDLE_MS = 10 * 60_000, SOON_MS = 20 * 60_000;
    private static final long SHOW_MS = 7000;

    private static final class Seen {
        final String state, detail, home, away;

        Seen(Sports.Game g) {
            state = g.state;
            detail = g.detail;
            home = g.home.score;
            away = g.away.score;
        }
    }

    private final AccessibilityService host;
    private final WindowManager windows;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Map<String, Seen> seen = new HashMap<>();
    private final ArrayDeque<String[]> queue = new ArrayDeque<>();
    private final Runnable poll = this::poll;
    private View banner;
    private boolean busy, stopped;

    Alerts(AccessibilityService host) {
        this.host = host;
        windows = (WindowManager) host.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    void start() {
        ui.postDelayed(poll, 15_000);
    }

    void stop() {
        stopped = true;
        ui.removeCallbacksAndMessages(null);
        net.shutdownNow();
        hide();
    }

    private void poll() {
        if (stopped) return;
        Config cfg = Config.get(host);
        cfg.refresh();
        Set<String> favorites = Sports.favorites(cfg);
        List<Sports.League> leagues = new ArrayList<>();
        for (Sports.League l : Sports.chosen(cfg)) {
            for (String f : favorites) {
                if (f.startsWith(l.id + ":")) {
                    leagues.add(l);
                    break;
                }
            }
        }
        if (!"favorites".equals(cfg.get(Config.ALERTS)) || leagues.isEmpty()) {
            seen.clear();
            ui.postDelayed(poll, IDLE_MS);
            return;
        }
        net.execute(() -> {
            List<Sports.Game> games = new ArrayList<>();
            for (Sports.League l : leagues) {
                for (Sports.Game g : Sports.scoreboard(l, null)) {
                    if (g.involves(favorites)) games.add(g);
                }
            }
            ui.post(() -> {
                if (stopped) return;
                long next = IDLE_MS, now = System.currentTimeMillis();
                for (Sports.Game g : games) {
                    compare(g);
                    if (g.live() || !g.done() && g.start > 0 && g.start - now < SOON_MS) next = LIVE_MS;
                }
                ui.postDelayed(poll, next);
            });
        });
    }

    private void compare(Sports.Game g) {
        String id = g.league.id + "/" + g.id;
        Seen before = seen.put(id, new Seen(g));
        if (before == null) return;
        String score = ScoreCard.describe(g);
        String where = g.league.abbr + (g.detail.isEmpty() ? "" : "  ·  " + g.detail);
        if ("pre".equals(before.state) && g.live()) {
            show("KICK-OFF", score, g.league.abbr);
        } else if (g.done() && !"post".equals(before.state)) {
            show("FULL TIME", score, g.league.abbr);
        } else if (g.live() && (!before.home.equals(g.home.score) || !before.away.equals(g.away.score))) {
            boolean homeScored = number(g.home.score) > number(before.home);
            Sports.Side scorer = homeScored ? g.home : g.away;
            String word = "soccer".equals(g.league.sport) ? "GOAL" : "SCORE";
            show(word + "  ·  " + ScoreCard.label(scorer), score, where);
        } else if (g.live() && "HT".equalsIgnoreCase(g.detail) && !"HT".equalsIgnoreCase(before.detail)) {
            show("HALF TIME", score, g.league.abbr);
        }
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void show(String headline, String score, String detail) {
        queue.add(new String[] {headline, score, detail});
        if (!busy) next();
    }

    private void next() {
        String[] a = queue.poll();
        if (a == null) {
            busy = false;
            return;
        }
        busy = true;
        View v = build(a[0], a[1], a[2]);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Ui.dp(host, 40);
        lp.y = Ui.dp(host, 32);
        try {
            windows.addView(v, lp);
        } catch (RuntimeException e) {
            busy = false;
            return;
        }
        banner = v;
        v.setAlpha(0f);
        v.setTranslationX(Ui.dp(host, 40));
        v.animate().alpha(1f).translationX(0).setDuration(260).start();
        ui.postDelayed(() -> v.animate().alpha(0f).setDuration(260).withEndAction(() -> {
            hide();
            next();
        }).start(), SHOW_MS);
    }

    private void hide() {
        View v = banner;
        banner = null;
        if (v == null) return;
        try {
            windows.removeView(v);
        } catch (RuntimeException ignored) {
            // already gone with the service
        }
    }

    private View build(String headline, String score, String detail) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(host, 16);
        box.setPadding(pad, Ui.dp(host, 12), pad, Ui.dp(host, 12));
        GradientDrawable bg = Ui.round(0xEB1C1D22, Ui.dp(host, 16));
        bg.setStroke(Math.max(1, Ui.dp(host, 1)), 0x33FFFFFF);
        box.setBackground(bg);
        box.setMinimumWidth(Ui.dp(host, 240));

        TextView h = Ui.text(host, 12, 0xFFFF453A, Ui.MEDIUM);
        h.setLetterSpacing(0.08f);
        h.setText(headline);
        TextView s = Ui.text(host, 20, Ui.TEXT, Ui.MEDIUM);
        s.setText(score);
        s.setPadding(0, Ui.dp(host, 6), 0, Ui.dp(host, 4));
        TextView d = Ui.text(host, 12, Ui.MUTED, Ui.MEDIUM);
        d.setText(detail);
        box.addView(h);
        box.addView(s);
        box.addView(d);
        return box;
    }
}
