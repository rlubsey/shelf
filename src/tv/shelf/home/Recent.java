package tv.shelf.home;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The apps you used most recently, newest first. Fire OS keeps what you watched inside each
 * app private to that app, so this is per app, from Android's usage history. It needs usage
 * access, which install.sh grants over ADB ({@code appops set tv.shelf.home GET_USAGE_STATS allow}).
 */
final class Recent {
    static final String HIDE_KEY = "recent.hide";
    private static final long WINDOW_MS = 14L * 24 * 3600_000L;

    private Recent() {}

    static boolean allowed(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Whether Shelf may see other apps' now-playing info (granted by install.sh --setup). */
    static boolean mediaAllowed(Context c) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.isNotificationListenerAccessGranted(
                new android.content.ComponentName(c, MediaListener.class));
    }

    /** Up to {@code max} of {@code apps}, most recently opened first. Slow: off the UI thread. */
    static List<Item> pick(Context c, List<Item> apps, List<String> hidden, int max) {
        List<Item> out = new ArrayList<>();
        Map<String, Item> launchable = new HashMap<>();
        for (Item it : apps) launchable.put(it.id, it);
        long now = System.currentTimeMillis();
        Map<String, Long> last = new HashMap<>();
        UsageStatsManager usm = allowed(c) ? (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE) : null;
        UsageEvents events = usm == null ? null : usm.queryEvents(now - WINDOW_MS, now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
            String pkg = e.getPackageName();
            if (pkg == null || !launchable.containsKey(pkg) || hidden.contains(pkg)) continue;
            if (pkg.equals(c.getPackageName()) || pkg.equals(Guard.AMAZON)) continue;
            Long t = last.get(pkg);
            if (t == null || e.getTimeStamp() > t) last.put(pkg, e.getTimeStamp());
        }
        // What played (from the apps' own now-playing info) counts as use too.
        for (Map.Entry<String, Long> h : History.times(c).entrySet()) {
            String pkg = h.getKey();
            if (!launchable.containsKey(pkg) || hidden.contains(pkg)) continue;
            Long t = last.get(pkg);
            if (t == null || h.getValue() > t) last.put(pkg, h.getValue());
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(last.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> en : sorted) {
            if (out.size() == max) break;
            out.add(launchable.get(en.getKey()));
        }
        return out;
    }
}
