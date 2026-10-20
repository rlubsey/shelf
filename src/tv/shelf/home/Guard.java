package tv.shelf.home;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS keeps HOME bound to Amazon's protected launcher, and Google TV sends its remote's HOME
 * button to Google's launcher whatever the default is. Both handle the key before any
 * accessibility filter sees it, so this service waits for the TV's own home window (the "stock"
 * launcher, see {@link #stock}) and replaces it.
 *
 * A plain startActivity right after a HOME press is held back by Android's five-second
 * app-switch delay. An alarm-fired PendingIntent is sent by system_server, which is exempt,
 * so {@link #home} launches through an alarm. That alarm only fires at once when the global
 * {@code alarm_manager_constants} has {@code min_futurity=0}; install.sh sets it.
 *
 * The stock home is covered with a plain overlay until ours has drawn, so it never shows.
 */
public final class Guard extends AccessibilityService {
    static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    static final String GOOGLE_TV = "com.google.android.apps.tv.launcherx";
    /** The launchers TVs ship with, in the order they are looked for. */
    private static final String[] STOCK = {
        AMAZON, GOOGLE_TV, "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
    };
    private static volatile String stock;
    private static volatile java.util.Set<String> stockHomes;
    private static final long COVER_MAX_MS = 1500;
    private static final long REPEAT_MS = 1000;
    /**
     * Fire OS 8's home screen opens Amazon's preview player (firebat's in-app playback) a moment
     * after it appears. Seen within this window after a HOME press, that player is part of
     * Amazon's home, not an app the user chose, so it must not cancel the switch to Shelf.
     */
    private static final long HOME_HELPER_MS = 4000;
    private static final String[] HOME_HELPERS = {"com.amazon.firebat"};

    private static volatile boolean paused;
    private static Guard running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    private View cover;
    private long lastLaunch;
    private Alerts alerts;
    /** When our own home window last appeared; Amazon's leftovers right after it are ignored. */
    private long lastOwn;
    private static final long SETTLE_MS = 1500;
    private static final String TAG = "shelf-guard";

    /** Lets the stock home stay on screen until the user leaves it. */
    static void pause() {
        paused = true;
    }

    static void home(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), launch(c));
    }

    /** Drops a queued launch, so it cannot pull the user back after they opened an app. */
    static void arrived(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.cancel(launch(c));
    }

    /** Called once our home window is on screen. */
    static void shown() {
        if (running != null) running.uncover();
    }

    private static PendingIntent launch(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(c, Home.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT,
                ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle());
    }

    /** The launcher this TV came with, or null on a TV without a known one. */
    static String stock(Context c) {
        String s = stock;
        if (s != null) return s.isEmpty() ? null : s;
        PackageManager pm = c.getPackageManager();
        s = "";
        for (String p : STOCK) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                if (ai.enabled) {
                    s = p;
                    break;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // not on this TV
            }
        }
        stock = s;
        return s.isEmpty() ? null : s;
    }

    static boolean isStock(Context c, String pkg) {
        return pkg != null && pkg.equals(stock(c));
    }

    /** "Amazon", "Google TV" or "Android TV": for labels like "Open Google TV's home screen". */
    static String stockName(Context c) {
        String s = stock(c);
        if (AMAZON.equals(s)) return "Amazon";
        if (GOOGLE_TV.equals(s)) return "Google TV";
        return "Android TV";
    }

    /** The stock launcher's HOME activities, by the class name its windows report. */
    private static java.util.Set<String> stockHomes(Context c, String pkg) {
        java.util.Set<String> set = stockHomes;
        if (set != null) return set;
        set = new java.util.HashSet<>();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            ActivityInfo a = r.activityInfo;
            if (a == null) continue;
            set.add(a.name);
            if (a.targetActivity != null) set.add(a.targetActivity);
        }
        stockHomes = set;
        android.util.Log.i(TAG, "stock home " + pkg + " " + set);
        return set;
    }

    static void openStock(Context c) {
        String pkg = stock(c);
        if (pkg == null) return;
        pause();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            paused = false;
        }
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        View view = new View(this);
        view.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, lp);
        cover = view;
        alerts = new Alerts(this);
        alerts.start();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(uncover);
        if (alerts != null) alerts.stop();
        if (cover != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            handle(e);
        } catch (RuntimeException ex) {
            android.util.Log.w("shelf", "home guard event failed", ex);
        }
    }

    private void handle(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        android.util.Log.i(TAG, "window " + pkg + "/" + cls + (paused ? " (paused)" : ""));
        if (getPackageName().contentEquals(pkg)) lastOwn = SystemClock.uptimeMillis();
        String stock = stock(this);
        if (stock == null) return;
        boolean amazon = AMAZON.equals(stock);
        if (!stock.contentEquals(pkg)) {
            if ("com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
            if (!paused && isHomeHelper(pkg) && SystemClock.uptimeMillis() - lastLaunch < HOME_HELPER_MS) {
                // Amazon's home just started its preview video: keep going to Shelf (and come back
                // to the front if that player opened on top of it).
                android.util.Log.i(TAG, "Amazon preview player after HOME: switching to Shelf");
                cover();
                home(this);
                return;
            }
            paused = false;
            if (!getPackageName().contentEquals(pkg)) arrived(this);
            return;
        }
        // Any Amazon launcher window counts: Fire OS 8 does not always report it as
        // HomeActivity_vNext, and without HOME the launcher is only reachable when paused
        // (Options › Open Amazon's home screen), which this check already respects.
        // Google's launchers also host other screens (the settings panel, profiles), so only
        // their HOME activity counts there.
        if (paused) return;
        boolean home = amazon ? isAmazonHome(cls) : isStockHome(stock, cls);
        if (!amazon && !home) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < REPEAT_MS) return;
        if (!home && now - lastOwn < SETTLE_MS) return; // leftovers behind Shelf
        lastLaunch = now;
        android.util.Log.i(TAG, "stock home (" + cls + "): switching to Shelf");
        cover();
        home(this);
    }

    /**
     * Fire OS 7 names the home activity {@code HomeActivity_vNext}; Fire OS 8 builds vary, so any
     * launcher activity whose class name contains "HomeActivity" counts.
     */
    private static boolean isHomeHelper(CharSequence pkg) {
        for (String h : HOME_HELPERS) {
            if (h.contentEquals(pkg)) return true;
        }
        return false;
    }

    private boolean isStockHome(String pkg, CharSequence cls) {
        if (cls == null) return false;
        String name = cls.toString();
        return stockHomes(this, pkg).contains(name) || name.endsWith(".HomeActivity");
    }

    private static boolean isAmazonHome(CharSequence cls) {
        if (cls == null) return false;
        String name = cls.toString();
        return AMAZON_HOME.equals(name) || name.contains("HomeActivity");
    }

    /** The overlay window exists from service start; a GONE root keeps its surface hidden. */
    private void cover() {
        if (cover == null) return;
        cover.setBackgroundColor(Config.get(this).background());
        cover.setVisibility(View.VISIBLE);
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, COVER_MAX_MS);
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        if (cover != null) cover.setVisibility(View.GONE);
    }

    @Override
    public void onInterrupt() {}
}
