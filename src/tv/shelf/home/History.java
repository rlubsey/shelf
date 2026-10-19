package tv.shelf.home;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What last played in each app (title, subtitle, artwork), as reported by the apps' own
 * "now playing" media sessions. Stored only on the TV, in Shelf's private files:
 * history.json plus one small JPEG per app. "Clear watch history" in Options deletes it all.
 */
final class History {
    static final class Entry {
        final String pkg, title, subtitle;
        final long time;

        Entry(String pkg, String title, String subtitle, long time) {
            this.pkg = pkg;
            this.title = title;
            this.subtitle = subtitle;
            this.time = time;
        }
    }

    private static final String FILE = "history.json";
    private static final Object LOCK = new Object();
    /** The app's "open what's playing" intent, kept while Shelf's process lives. */
    private static final Map<String, PendingIntent> OPEN = new HashMap<>();
    private static volatile int version;

    private History() {}

    /** Changes whenever something new is recorded, so the home screen knows to redraw. */
    static int version() {
        return version;
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "history");
        if (!d.isDirectory() && !d.mkdirs()) Log.w("shelf", "could not create " + d);
        return d;
    }

    private static File art(Context c, String pkg) {
        return new File(dir(c), pkg + ".jpg");
    }

    private static JSONObject read(Context c) {
        File f = new File(dir(c), FILE);
        if (!f.isFile()) return new JSONObject();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; ) n += r;
            return new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private static void write(Context c, JSONObject o) {
        File f = new File(dir(c), FILE), tmp = new File(dir(c), FILE + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w("shelf", "could not save history", e);
            return;
        }
        if (!tmp.renameTo(f)) Log.w("shelf", "could not replace " + f);
    }

    /** Records what {@code pkg} is playing. Artwork, when given, replaces the stored picture. */
    static void record(Context c, String pkg, String title, String subtitle, Bitmap artwork, PendingIntent open) {
        if (title == null || title.trim().isEmpty()) return;
        synchronized (LOCK) {
            if (open != null) OPEN.put(pkg, open);
            JSONObject all = read(c);
            JSONObject prev = all.optJSONObject(pkg);
            boolean same = prev != null && title.equals(prev.optString("title"))
                    && (subtitle == null ? "" : subtitle).equals(prev.optString("subtitle"));
            try {
                JSONObject e = new JSONObject();
                e.put("title", title.trim());
                e.put("subtitle", subtitle == null ? "" : subtitle.trim());
                e.put("time", System.currentTimeMillis());
                all.put(pkg, e);
            } catch (JSONException ignored) {
                return;
            }
            write(c, all);
            if (artwork != null) saveArt(c, pkg, artwork);
            else if (!same) {
                File a = art(c, pkg); // a new title without artwork: don't show the old picture
                if (a.isFile() && !a.delete()) Log.w("shelf", "could not delete " + a);
            }
            version++;
        }
    }

    private static void saveArt(Context c, String pkg, Bitmap b) {
        int w = Math.min(640, b.getWidth());
        int h = Math.max(1, Math.round(b.getHeight() * (w / (float) b.getWidth())));
        Bitmap small = w == b.getWidth() ? b : Bitmap.createScaledBitmap(b, w, h, true);
        try (OutputStream out = new FileOutputStream(art(c, pkg))) {
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            Log.w("shelf", "could not save artwork", e);
        }
    }

    static Entry get(Context c, String pkg) {
        synchronized (LOCK) {
            JSONObject e = read(c).optJSONObject(pkg);
            if (e == null || e.optString("title").isEmpty()) return null;
            return new Entry(pkg, e.optString("title"), e.optString("subtitle"), e.optLong("time"));
        }
    }

    /** Most recent play time per app, for ordering the row. */
    static Map<String, Long> times(Context c) {
        Map<String, Long> out = new HashMap<>();
        synchronized (LOCK) {
            JSONObject all = read(c);
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONObject e = all.optJSONObject(pkg);
                if (e != null) out.put(pkg, e.optLong("time"));
            }
        }
        return out;
    }

    /** The stored artwork, decoded to about {@code width} pixels wide. Slow: off the UI thread. */
    static Bitmap artwork(Context c, String pkg, int width) {
        File f = art(c, pkg);
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    static PendingIntent opener(String pkg) {
        synchronized (LOCK) {
            return OPEN.get(pkg);
        }
    }

    static void clear(Context c) {
        synchronized (LOCK) {
            OPEN.clear();
            File[] files = dir(c).listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.delete()) Log.w("shelf", "could not delete " + f);
                }
            }
            version++;
        }
    }
}
