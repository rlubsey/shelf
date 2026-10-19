package tv.shelf.home;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

/**
 * Keeps the HOME button working. Android does not reconnect an accessibility service whose
 * process died (a crash, or Fire OS reclaiming memory during a long video); the service stays
 * "enabled" in settings but nothing is listening, so HOME lands on Amazon's screen. Toggling
 * the setting off and on is what rebinds it, and that is what this does.
 *
 * Runs every few minutes (a repeating alarm, which also wakes a killed process), after boot,
 * and whenever Shelf's home screen comes up. It only revives a service that is still listed as
 * enabled, so {@code ./install.sh --undo} stays undone. Changing secure settings needs
 * WRITE_SECURE_SETTINGS, which install.sh grants over ADB; without it this does nothing.
 */
public final class Watchdog extends BroadcastReceiver {
    private static final String ENABLED = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    private static final String ALARM_CONSTANTS = "alarm_manager_constants";
    private static final long EVERY_MS = 5 * 60_000L;

    @Override
    public void onReceive(Context c, Intent i) {
        PendingResult pending = goAsync();
        new Thread(Ui.safe(() -> {
            try {
                schedule(c);
                ensure(c);
            } finally {
                pending.finish();
            }
        }), "shelf-watchdog").start();
    }

    static void schedule(Context c) {
        Intent i = new Intent(c, Watchdog.class);
        PendingIntent p = PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + EVERY_MS, EVERY_MS, p);
    }

    static boolean canHeal(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String guardName(Context c) {
        return new ComponentName(c, Guard.class).flattenToString();
    }

    /** True when Android has the Guard service connected (not merely switched on). */
    static boolean guardRunning(Context c) {
        AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        for (AccessibilityServiceInfo info
                : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ServiceInfo s = info.getResolveInfo() == null ? null : info.getResolveInfo().serviceInfo;
            if (s != null && c.getPackageName().equals(s.packageName) && Guard.class.getName().equals(s.name)) {
                return true;
            }
        }
        return false;
    }

    /** Blocking (sleeps about a second when it repairs): call off the UI thread. */
    static void ensure(Context c) {
        if (!canHeal(c)) return;
        ContentResolver cr = c.getContentResolver();
        String enabled = Settings.Secure.getString(cr, ENABLED);
        if (enabled == null || !enabled.contains(guardName(c))) return; // switched off on purpose
        keepInstantSwitch(cr);
        if (guardRunning(c)) return;
        Log.i("shelf", "HOME service enabled but not running; reconnecting it");
        Settings.Secure.putString(cr, ENABLED, "");
        SystemClock.sleep(1000);
        Settings.Secure.putString(cr, ENABLED, enabled);
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    /** Without min_futurity=0 Android holds launches for 5 s and Amazon's home shows meanwhile. */
    private static void keepInstantSwitch(ContentResolver cr) {
        String cur = Settings.Global.getString(cr, ALARM_CONSTANTS);
        if (cur == null || "null".equals(cur)) cur = "";
        StringBuilder next = new StringBuilder();
        for (String part : cur.split(",")) {
            String p = part.trim();
            if (p.equals("min_futurity=0")) return;
            if (p.isEmpty() || p.startsWith("min_futurity=")) continue;
            next.append(p).append(',');
        }
        next.append("min_futurity=0");
        Settings.Global.putString(cr, ALARM_CONSTANTS, next.toString());
    }
}
