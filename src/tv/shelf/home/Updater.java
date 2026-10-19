package tv.shelf.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Updates Shelf from the GitHub releases of {@link #REPO}. GitHub Actions publishes each build
 * as a release tagged {@code build-<versionCode>} with {@code shelf-home.apk} attached; Shelf
 * downloads the newest one and hands it to the system installer, which asks for one press on
 * the remote. Android only accepts the update when it is signed with the same key as the
 * installed app, so nothing but your own builds can replace Shelf this way.
 */
final class Updater {
    static final String REPO = "rlubsey/shelf";
    static final String CHECKED_KEY = "update.checked";
    static final long DAY_MS = 24 * 3600_000L;
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String TAG_PREFIX = "build-";
    private static final int TIMEOUT_MS = 15_000;

    static final class Release {
        final long code;
        final String name, url, notes;

        Release(long code, String name, String url, String notes) {
            this.code = code;
            this.name = name;
            this.url = url;
            this.notes = notes;
        }
    }

    private Updater() {}

    static long installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** The latest published release, or null when GitHub can't be reached. Network: off the UI thread. */
    static Release latest() {
        try {
            JSONObject o = new JSONObject(get(LATEST));
            String tag = o.optString("tag_name");
            if (!tag.startsWith(TAG_PREFIX)) return null;
            long code = Long.parseLong(tag.substring(TAG_PREFIX.length()));
            JSONArray assets = o.optJSONArray("assets");
            String url = null;
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a != null && a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url");
                    break;
                }
            }
            if (url == null || url.isEmpty()) return null;
            return new Release(code, o.optString("name", tag), url, o.optString("body"));
        } catch (IOException | JSONException | NumberFormatException e) {
            return null;
        }
    }

    /** A release newer than the installed app, or null. Network: off the UI thread. */
    static Release newer(Context c) {
        Release r = latest();
        return r != null && r.code > installedCode(c) ? r : null;
    }

    /** Options → Check for updates. */
    static void check(Activity a) {
        Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new Thread(Ui.safe(() -> {
            Release r = latest();
            long mine = installedCode(a);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (r == null) {
                    Toast.makeText(a, "Couldn't reach GitHub. Check the TV's internet connection.",
                            Toast.LENGTH_LONG).show();
                } else if (r.code <= mine) {
                    Toast.makeText(a, "Shelf is up to date (" + installedName(a) + ")", Toast.LENGTH_LONG).show();
                } else {
                    offer(a, r);
                }
            });
        }), "shelf-update-check").start();
    }

    static void offer(Activity a, Release r) {
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 400) notes = notes.substring(0, 400) + "…";
        new AlertDialog.Builder(a, Ui.DIALOG)
                .setTitle(r.name + " is available")
                .setMessage("You have " + installedName(a) + "." + (notes.isEmpty() ? "" : "\n\n" + notes))
                .setPositiveButton("Install", (d, w) -> install(a, r))
                .setNegativeButton("Later", null)
                .show();
    }

    static void install(Activity a, Release r) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow Shelf to install updates: Settings › My Fire TV › Developer Options"
                    + " › Install unknown apps › Shelf", Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Fire OS may not have this screen; the toast says where the switch is
            }
            return;
        }
        Toast.makeText(a, "Downloading " + r.name + "…", Toast.LENGTH_LONG).show();
        Context app = a.getApplicationContext();
        new Thread(Ui.safe(() -> {
            String error = null;
            try {
                File apk = download(app, r.url);
                commit(app, apk);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            String failed = error;
            if (failed != null) {
                a.runOnUiThread(() -> Toast.makeText(app, "Update failed: " + failed, Toast.LENGTH_LONG).show());
            }
        }), "shelf-update-install").start();
    }

    private static File download(Context c, String url) throws IOException {
        File out = new File(c.getCacheDir(), "shelf-update.apk");
        HttpURLConnection http = open(url);
        try (InputStream in = http.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
        } finally {
            http.disconnect();
        }
        return out;
    }

    /** Hands the APK to the system installer; {@link UpdateReceiver} shows the confirm screen. */
    private static void commit(Context c, File apk) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("shelf-home.apk", 0, apk.length())) {
                copy(in, out);
                session.fsync(out);
            }
            Intent done = new Intent(c, UpdateReceiver.class);
            PendingIntent pending = PendingIntent.getBroadcast(c, id, done, PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pending.getIntentSender());
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(TIMEOUT_MS);
        http.setReadTimeout(TIMEOUT_MS);
        http.setInstanceFollowRedirects(true);
        http.setRequestProperty("User-Agent", "Shelf-updater");
        http.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        int code = http.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            http.disconnect();
            throw new IOException("HTTP " + code);
        }
        return http;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection http = open(url);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            for (int n; (n = r.read(buf)) > 0; ) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            http.disconnect();
        }
    }
}
