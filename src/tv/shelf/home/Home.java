package tv.shelf.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * tvOS-style home: the wallpaper fills the top shelf, the first row of apps sits on a frosted
 * glass dock, the rest of the apps form a grid you scroll down into (the wallpaper dims as you
 * go), and HDMI inputs close the page.
 */
public final class Home extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final float SHELF = 0.47f;
    private static final float MAX_SCROLL_DIM = 0.62f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Ui.worker("shelf-io");
    private final List<TextView> chips = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed;
    private FrameLayout root, dockFrame;
    private ImageView wall;
    private View shade;
    private Glass glass, inputGlass;
    private FrameLayout shelfFrame;
    private LinearLayout scoresStrip, bar;
    private TextView updateChip;
    private Updater.Release update;
    private int scoresPage, scoresFit;
    private static final long PAGE_MS = 8000;
    private final Runnable nextPage = new Runnable() {
        @Override
        public void run() {
            if (scoresFit > 0 && games.size() > scoresFit) {
                scoresPage++;
                renderScores();
                scoresStrip.setAlpha(0f);
                scoresStrip.animate().alpha(1f).setDuration(400).start();
            }
            ui.postDelayed(this, PAGE_MS);
        }
    };
    private final List<Glass> gridGlasses = new ArrayList<>();
    private final List<Glass> scoreCards = new ArrayList<>();
    private final List<LinearLayout> gridStrips = new ArrayList<>();
    private Bitmap soft;
    private ScrollView scroll;
    private TextView clock, gridTitle, inputTitle;
    private LinearLayout dockStrip, grid, inputStrip;
    private HorizontalScrollView inputScroll;
    private List<Item> appsAll = new ArrayList<>(), inputsAll = new ArrayList<>();
    private String movingId, movingKind, wallKey;
    private float userDim;

    private static final long LIVE_REFRESH_MS = 60_000, IDLE_REFRESH_MS = 10 * 60_000;
    private final ExecutorService net = Ui.worker("shelf-net");
    private final Map<String, Bitmap> logos = new ConcurrentHashMap<>();
    private List<Sports.Game> games = new ArrayList<>();
    private long lastSports;
    private String sportsKey = "";
    private boolean sportsLoading, sportsLoaded;
    private final Runnable sportsTick = new Runnable() {
        @Override
        public void run() {
            refreshSports(false);
            ui.postDelayed(this, LIVE_REFRESH_MS);
        }
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            loadApps(data == null ? null : data.getSchemeSpecificPart());
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String id) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String id) {
            loadInputs();
        }

        @Override
        public void onInputUpdated(String id) {
            loadInputs();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        setContentView(build());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        if (tv != null) tv.registerCallback(inputWatch, ui);
        loadApps(null);
        loadInputs();
        Watchdog.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        cfg.refresh();
        if (cfg.version() != applied) apply();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
        ui.removeCallbacks(sportsTick);
        ui.post(sportsTick);
        ui.removeCallbacks(nextPage);
        ui.postDelayed(nextPage, PAGE_MS);
        dailyUpdateCheck();
        net.execute(() -> Watchdog.ensure(getApplicationContext()));
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        unregisterReceiver(time);
        ui.removeCallbacks(sportsTick);
        ui.removeCallbacks(nextPage);
        if (movingId != null) stopMoving();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    /** Drops the hidden window's GPU context while an app plays (see dumbtv's notes). */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (movingId != null) stopMoving();
        if (resumed) focusFirst();
    }

    @Override
    public void onBackPressed() {
        focusFirst();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (movingId == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int rowStep = Catalog.APPS.equals(movingKind) ? cols() : 0;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shift(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shift(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (rowStep > 0) shift(-rowStep);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (rowStep > 0) shift(rowStep);
                    break;
                default:
                    stopMoving();
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) menu((Tile) f);
            else startActivity(new Intent(this, Options.class));
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---------------------------------------------------------------- layout

    private View build() {
        root = new FrameLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                if (direction == View.FOCUS_DOWN && chips.contains(focused)) {
                    View below = nearest(dockStrip, focused);
                    if (below != null) return below;
                }
                return super.focusSearch(focused, direction);
            }
        };

        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, new FrameLayout.LayoutParams(MATCH, MATCH));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xE6000000, 0x8C000000, 0x00000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));

        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x73000000, 0x00000000}));
        root.addView(top, new FrameLayout.LayoutParams(MATCH, dp(140)));

        shade = new View(this);
        shade.setBackgroundColor(Color.BLACK);
        shade.setAlpha(0f);
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            updateShade();
            for (Glass g : allGlasses()) g.invalidate();
        });
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(0, 0, 0, dp(56));
        scroll.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));

        shelfFrame = new FrameLayout(this);
        column.addView(shelfFrame, new LinearLayout.LayoutParams(MATCH,
                Math.round(getResources().getDisplayMetrics().heightPixels * SHELF)));

        dockFrame = new FrameLayout(this);
        dockFrame.setClipChildren(false);
        dockFrame.setClipToPadding(false);
        glass = new Glass(this);
        dockFrame.addView(glass, new FrameLayout.LayoutParams(MATCH, dp(120)));
        dockStrip = strip();
        dockFrame.addView(dockStrip, new FrameLayout.LayoutParams(MATCH, WRAP));
        column.addView(dockFrame, new LinearLayout.LayoutParams(MATCH, WRAP));

        gridTitle = section("Apps");
        column.addView(gridTitle);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        column.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));

        inputTitle = section("Inputs");
        column.addView(inputTitle);
        inputStrip = strip();
        inputScroll = new HorizontalScrollView(this);
        inputScroll.setFocusable(false);
        inputScroll.setHorizontalScrollBarEnabled(false);
        inputScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        inputScroll.setClipChildren(false);
        inputScroll.setClipToPadding(false);
        inputScroll.addView(inputStrip, new FrameLayout.LayoutParams(WRAP, WRAP));
        inputGlass = new Glass(this);
        column.addView(glassRow(inputScroll, inputGlass, dp(60)), new LinearLayout.LayoutParams(MATCH, WRAP));

        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(30), dp(edgeDp()), 0);
        updateChip = chip("Update", v -> {
            if (update != null) Updater.offer(this, update);
        });
        updateChip.setVisibility(View.GONE);
        bar.addView(updateChip);
        bar.addView(chip("Options", v -> startActivity(new Intent(this, Options.class))));
        bar.addView(chip("Settings", v -> openSettings()));
        clock = Ui.text(this, 26, Ui.TEXT, Ui.LIGHT);
        clock.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(WRAP, WRAP);
        clp.leftMargin = dp(22);
        bar.addView(clock, clp);
        root.addView(bar, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END));
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) ui.post(this::renderScores);
        });

        // Score cards share the top line with Options, Settings and the clock, aligned left
        // and centred on that line. They stay put while the page scrolls, like the clock.
        scoresStrip = new LinearLayout(this);
        scoresStrip.setClipChildren(false);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        slp.leftMargin = edge();
        slp.topMargin = dp(47) - dp(ScoreCard.HEIGHT_DP) / 2;
        root.addView(scoresStrip, slp);

        root.getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> follow(now));
        return root;
    }

    private LinearLayout strip() {
        LinearLayout s = new LinearLayout(this);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setPadding(edge(), dp(20), edge(), dp(4));
        return s;
    }

    private TextView section(String name) {
        TextView t = Ui.text(this, 13, Ui.MUTED, Ui.MEDIUM);
        t.setText(name.toUpperCase(Locale.ROOT));
        t.setLetterSpacing(0.14f);
        t.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        t.setPadding(edge(), dp(26), edge(), 0);
        return t;
    }

    private TextView chip(String label, View.OnClickListener click) {
        TextView v = Ui.text(this, 14, Ui.TEXT, Ui.MEDIUM);
        v.setText(label);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(16), dp(8), dp(16), dp(8));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) ->
                view.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(140).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        v.setLayoutParams(lp);
        chips.add(v);
        return v;
    }

    /** The child of {@code strip} horizontally closest to {@code from}, or null when empty. */
    private static View nearest(LinearLayout strip, View from) {
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < strip.getChildCount(); i++) {
            View c = strip.getChildAt(i);
            c.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    /** Dock and chips snap back to the top shelf; grid rows glide to the upper third. */
    private void follow(View now) {
        if (now == null) return;
        if (chips.contains(now) || now.getParent() == dockStrip) {
            scroll.smoothScrollTo(0, 0);
            return;
        }
        View row = (View) now.getParent();
        if (row == null) return;
        int y;
        View frame = row.getParent() instanceof View ? (View) row.getParent() : null;
        if (gridStrips.contains(row) && frame != null) {
            y = grid.getTop() + frame.getTop();
        } else if (row == inputStrip) {
            y = ((View) inputScroll.getParent()).getTop();
        } else {
            return;
        }
        int target = Math.max(0, y - Math.round(scroll.getHeight() * 0.28f));
        scroll.smoothScrollTo(0, target);
    }

    private void updateShade() {
        int h = Math.max(1, scroll.getHeight());
        float t = Math.min(1f, scroll.getScrollY() / (h * 0.4f));
        shade.setAlpha(userDim + (Math.max(userDim, MAX_SCROLL_DIM) - userDim) * t);
    }

    // ---------------------------------------------------------------- data

    private void apply() {
        applied = cfg.version();
        int accent = cfg.accent();
        for (TextView c : chips) Ui.focusStyle(c, accent, 0x2EFFFFFF, dp(17));
        renderApps();
        renderInputs();
        renderScores();
        background();
        tick();
    }

    /** Rebuilds the app list, keeping loaded art for every package except {@code changed}. */
    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(() -> {
            List<Item> list = Catalog.apps(this);
            ui.post(() -> {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : appsAll) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.artRequested = true;
                        }
                    }
                }
                appsAll = list;
                renderApps();
            });
        });
    }

    private void loadInputs() {
        inputsAll = Catalog.inputs(this, tv);
        renderInputs();
    }

    private String focusedId(String kind) {
        View f = getCurrentFocus();
        return f instanceof Tile && ((Tile) f).kind.equals(kind) ? ((Tile) f).item.id : null;
    }

    private Tile tile(Item it, String kind, int w, int h) {
        Tile t = new Tile(this, it, kind, Catalog.label(cfg, it), w, h, cfg.get(Config.LABELS));
        t.setOnClickListener(v -> open((Tile) v));
        t.setOnLongClickListener(v -> {
            menu((Tile) v);
            return true;
        });
        if (it.id.equals(movingId) && kind.equals(movingKind)) t.setMoving(true);
        return t;
    }

    private LinearLayout.LayoutParams gapped() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.rightMargin = gap();
        return lp;
    }

    private void renderApps() {
        String keep = focusedId(Catalog.APPS);
        dockStrip.removeAllViews();
        grid.removeAllViews();
        gridStrips.clear();
        gridGlasses.clear();

        List<Item> shown = Catalog.visible(cfg, Catalog.APPS, appsAll);
        int c = cols();
        int[] size = cardSize();
        FrameLayout.LayoutParams glp = (FrameLayout.LayoutParams) glass.getLayoutParams();
        glp.height = size[1] + dp(40);
        glp.leftMargin = glp.rightMargin = edge() - dp(22);
        glass.setLayoutParams(glp);

        Tile restore = null;
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            Item it = shown.get(i);
            Tile t = tile(it, Catalog.APPS, size[0], size[1]);
            if (i < c) {
                dockStrip.addView(t, gapped());
            } else {
                if ((i - c) % c == 0) {
                    row = strip();
                    Glass g = new Glass(this);
                    g.setSource(soft);
                    gridGlasses.add(g);
                    gridStrips.add(row);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH, WRAP);
                    rlp.topMargin = dp(12);
                    grid.addView(glassRow(row, g, size[1] + dp(40)), rlp);
                }
                row.addView(t, gapped());
            }
            if (it.id.equals(keep)) restore = t;
            if (!it.artRequested) loadArt(it);
        }
        dockFrame.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        gridTitle.setVisibility(shown.size() > c ? View.VISIBLE : View.GONE);
        settle(restore, keep);
    }

    private void renderInputs() {
        String keep = focusedId(Catalog.INPUTS);
        inputStrip.removeAllViews();
        boolean enabled = "on".equals(cfg.get(Config.INPUTS));
        List<Item> shown = enabled ? Catalog.visible(cfg, Catalog.INPUTS, inputsAll) : new ArrayList<>();
        int[] size = cardSize();
        int h = Math.round(size[1] * 0.5f);
        Tile restore = null;
        for (Item it : shown) {
            Tile t = tile(it, Catalog.INPUTS, size[0], h);
            inputStrip.addView(t, gapped());
            if (it.id.equals(keep)) restore = t;
        }
        int v = shown.isEmpty() ? View.GONE : View.VISIBLE;
        inputTitle.setVisibility(v);
        View frame = (View) inputScroll.getParent();
        frame.setVisibility(v);
        ViewGroup.LayoutParams ilp = inputGlass.getLayoutParams();
        ilp.height = h + dp(40);
        inputGlass.setLayoutParams(ilp);
        settle(restore, keep);
    }

    private void settle(Tile restore, String keep) {
        if (restore != null) {
            restore.requestFocus();
        } else if (keep != null || getCurrentFocus() == null || !landed) {
            focusFirst();
        }
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        io.execute(() -> {
            Catalog.art(getPackageManager(), it);
            ui.post(this::invalidateTiles);
        });
    }

    private void invalidateTiles() {
        for (Tile t : tiles()) t.invalidate();
    }

    private List<Tile> tiles() {
        List<Tile> out = new ArrayList<>();
        collect(dockStrip, out);
        for (LinearLayout s : gridStrips) collect(s, out);
        collect(inputStrip, out);
        return out;
    }

    private static void collect(ViewGroup g, List<Tile> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof Tile) out.add((Tile) v);
        }
    }

    private int cols() {
        switch (cfg.get(Config.TILES)) {
            case "small":
                return 6;
            case "large":
                return 4;
            default:
                return 5;
        }
    }

    private int edgeDp() {
        return 64;
    }

    private int edge() {
        return dp(edgeDp());
    }

    private int gap() {
        return dp(26);
    }

    /** Cards fill the row exactly: {@link #cols()} per row, 16:9 like TV app banners. */
    private int[] cardSize() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int c = cols();
        int w = (width - 2 * edge() - gap() * (c - 1)) / c;
        return new int[] {w, w * 9 / 16};
    }

    private void focusFirst() {
        scroll.scrollTo(0, 0);
        inputScroll.scrollTo(0, 0);
        View first = dockStrip.getChildAt(0);
        if (first == null) first = inputStrip.getChildAt(0);
        landed = dockStrip.getChildCount() > 0;
        if (first == null) first = chips.get(0);
        first.requestFocus();
    }

    private void tick() {
        String mode = cfg.get(Config.CLOCK);
        boolean h24 = "24h".equals(mode) || "auto".equals(mode) && DateFormat.is24HourFormat(this);
        Date now = new Date();
        if (h24) {
            clock.setText(DateFormat.format("HH:mm", now));
        } else {
            SpannableString half = new SpannableString(" " + DateFormat.format("a", now));
            half.setSpan(new RelativeSizeSpan(0.5f), 0, half.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(DateFormat.format("h:mm", now));
            clock.append(half);
        }
    }

    // ---------------------------------------------------------------- wallpaper

    private void background() {
        String mode = cfg.get(Config.BACKGROUND);
        File file = "image".equals(mode) ? cfg.wallpaper() : null;
        boolean photo = "photo".equals(mode) || "image".equals(mode) && file == null;
        root.setBackgroundColor(cfg.background());
        userDim = file != null || photo ? Integer.parseInt(cfg.get(Config.DIM)) / 100f : 0f;
        updateShade();

        String key = file != null ? file.getPath() + ":" + file.lastModified() : photo ? "photo" : null;
        if (key == null ? wallKey == null : key.equals(wallKey)) return;
        wallKey = key;
        if (key == null) {
            wall.setImageDrawable(null);
            setSoft(null);
            return;
        }
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        io.execute(() -> {
            Bitmap b = file != null ? decode(file, w, h) : decodePhoto(w, h);
            Bitmap blurred = b == null ? null : Blur.of(b);
            ui.post(() -> {
                if (!key.equals(wallKey)) return;
                wall.setImageBitmap(b);
                setSoft(blurred);
            });
        });
    }

    private static int sample(BitmapFactory.Options o, int w, int h) {
        int s = 1;
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
        return s;
    }

    private Bitmap decodePhoto(int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), R.drawable.wallpaper, o);
        o.inSampleSize = sample(o, w, h);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(getResources(), R.drawable.wallpaper, o);
    }

    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = sample(o, w, h);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    // ---------------------------------------------------------------- actions

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home, which
     * Guard then replaces with ours. The TV settings entry point opens the real settings screen.
     */
    private void openSettings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        boolean found = tvSettings.resolveActivity(getPackageManager()) != null;
        launch(found ? tvSettings : new Intent(Settings.ACTION_SETTINGS), null);
    }

    private void open(Tile t) {
        launch(new Intent(t.item.intent), Catalog.APPS.equals(t.kind) ? t.item.id : null);
    }

    private void launch(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(Tile t) {
        boolean app = Catalog.APPS.equals(t.kind);
        String[] actions = app
                ? new String[] {"Move", "Rename", "Hide", "App info"}
                : new String[] {"Move", "Rename", "Hide"};
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, t.item))
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            movingId = t.item.id;
                            movingKind = t.kind;
                            t.setMoving(true);
                            Toast.makeText(this, app
                                    ? "Move with ◀ ▶ ▲ ▼ · press OK to drop it here"
                                    : "Move with ◀ ▶ · press OK to drop it here", Toast.LENGTH_LONG).show();
                            break;
                        case 1:
                            rename(t);
                            break;
                        case 2:
                            Catalog.setShown(cfg, t.kind, t.item, false);
                            applied = cfg.version();
                            if (app) renderApps();
                            else renderInputs();
                            Toast.makeText(this, "Hidden. Bring it back from Options.", Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", t.item.id, null)), null);
                    }
                })
                .show();
    }

    private void rename(Tile t) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, t.item));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        String key = "name." + t.item.id;
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Rename")
                .setView(box)
                .setPositiveButton("Save", (dialog, w) -> {
                    String name = field.getText().toString().trim();
                    cfg.set(key, name.equals(t.item.label.toString()) ? null : name);
                    apply();
                })
                .setNeutralButton("Original", (dialog, w) -> {
                    cfg.set(key, null);
                    apply();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    /** Moves the tile being rearranged {@code by} places in the saved order (a row = cols()). */
    private void shift(int by) {
        boolean app = Catalog.APPS.equals(movingKind);
        List<Item> shown = Catalog.visible(cfg, movingKind, app ? appsAll : inputsAll);
        List<String> ids = new ArrayList<>();
        for (Item it : shown) ids.add(it.id);
        int from = ids.indexOf(movingId), to = from + by;
        if (from < 0 || to < 0 || to >= ids.size()) return;
        ids.remove(from);
        ids.add(to, movingId);
        Catalog.saveOrder(cfg, movingKind, ids);
        applied = cfg.version();
        if (app) renderApps();
        else renderInputs();
    }

    private void stopMoving() {
        movingId = null;
        movingKind = null;
        for (Tile t : tiles()) {
            if (t.isMoving()) t.setMoving(false);
        }
    }

    // ---------------------------------------------------------------- glass rows

    /** {@code content} on a frosted panel {@code glassH} tall, inset like the dock. */
    private FrameLayout glassRow(View content, Glass g, int glassH) {
        FrameLayout f = new FrameLayout(this);
        f.setClipChildren(false);
        f.setClipToPadding(false);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(MATCH, glassH);
        glp.leftMargin = glp.rightMargin = edge() - dp(22);
        f.addView(g, glp);
        f.addView(content, new FrameLayout.LayoutParams(MATCH, WRAP));
        return f;
    }

    private List<Glass> allGlasses() {
        List<Glass> out = new ArrayList<>();
        out.add(glass);
        out.add(inputGlass);
        out.addAll(gridGlasses);
        out.addAll(scoreCards);
        return out;
    }

    private void setSoft(Bitmap b) {
        soft = b;
        for (Glass g : allGlasses()) g.setSource(b);
    }

    // ---------------------------------------------------------------- sports

    /** Fetches scores when stale: every minute while a game is live, every ten minutes otherwise. */
    private void refreshSports(boolean force) {
        if (!"on".equals(cfg.get(Config.SPORTS)) || sportsLoading) return;
        List<Sports.League> leagues = Sports.chosen(cfg);
        Set<String> favorites = Sports.favorites(cfg);
        String key = cfg.get(Sports.LEAGUES_KEY, Sports.DEFAULT_LEAGUES) + "|" + favorites;
        if (!key.equals(sportsKey)) {
            sportsKey = key;
            force = true;
        }
        if (leagues.isEmpty()) return;
        boolean anyLive = false;
        for (Sports.Game g : games) anyLive |= g.live();
        long age = System.currentTimeMillis() - lastSports;
        if (!force && age < (anyLive ? LIVE_REFRESH_MS : IDLE_REFRESH_MS)) return;
        sportsLoading = true;
        net.execute(() -> {
            List<Sports.Game> fresh = Sports.load(leagues, favorites, 12);
            for (Sports.Game g : fresh) {
                for (Sports.Side s : new Sports.Side[] {g.home, g.away}) {
                    if (s.logo.isEmpty() || logos.containsKey(s.logo)) continue;
                    Bitmap b = Sports.logo(s.logo);
                    if (b != null) logos.put(s.logo, b);
                }
            }
            ui.post(() -> {
                sportsLoading = false;
                if (isDestroyed()) return;
                games = fresh;
                lastSports = System.currentTimeMillis();
                sportsLoaded = true;
                renderScores();
            });
        });
    }

    private void renderScores() {
        scoresStrip.removeAllViews();
        scoreCards.clear();
        if (!"on".equals(cfg.get(Config.SPORTS))) {
            scoresStrip.setVisibility(View.GONE);
            return;
        }
        scoresStrip.setVisibility(View.VISIBLE);
        int barW = bar.getWidth() > 0 ? bar.getWidth() : dp(430);
        int width = getResources().getDisplayMetrics().widthPixels - edge() - barW - dp(24);
        int cardW = dp(ScoreCard.WIDTH_DP), space = dp(12);
        int fit = Math.max(1, (width + space) / (cardW + space));
        scoresFit = fit;

        List<ScoreCard> cards = new ArrayList<>();
        if (Sports.chosen(cfg).isEmpty()) {
            cards.add(new ScoreCard(this, null, "Pick leagues in Options", logos));
        } else if (games.isEmpty()) {
            cards.add(new ScoreCard(this, null, sportsLoaded ? "No matches right now" : "Loading scores…", logos));
        } else {
            // More games than fit: show them a page at a time, turning every few seconds.
            int pages = (games.size() + fit - 1) / fit;
            int from = (scoresPage % pages) * fit;
            for (int i = from; i < Math.min(games.size(), from + fit); i++) {
                cards.add(new ScoreCard(this, games.get(i), null, logos));
            }
        }
        for (ScoreCard card : cards) {
            card.setSource(soft);
            scoreCards.add(card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.rightMargin = space;
            scoresStrip.addView(card, lp);
        }
        refreshSports(false);
    }

    // ---------------------------------------------------------------- updates

    /** Once a day, asks GitHub for a newer build; if there is one, an Update pill appears. */
    private void dailyUpdateCheck() {
        long last;
        try {
            last = Long.parseLong(cfg.get(Updater.CHECKED_KEY, "0"));
        } catch (NumberFormatException e) {
            last = 0;
        }
        long now = System.currentTimeMillis();
        if (now - last < Updater.DAY_MS) return;
        cfg.set(Updater.CHECKED_KEY, Long.toString(now));
        applied = cfg.version();
        net.execute(() -> {
            Updater.Release r = Updater.newer(this);
            ui.post(() -> {
                if (isDestroyed()) return;
                update = r;
                updateChip.setVisibility(r == null ? View.GONE : View.VISIBLE);
            });
        });
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
