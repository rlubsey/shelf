package tv.shelf.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;

import java.io.File;

/** The wallpaper the home screen and Options share: the built-in photo or a pushed image file. */
final class Wall {
    /** The picture and its blurred copy (for glass). */
    static final class Pair {
        final String key;
        final Bitmap image, soft;

        Pair(String key, Bitmap image, Bitmap soft) {
            this.key = key;
            this.image = image;
            this.soft = soft;
        }
    }

    private Wall() {}

    /** Identifies the current wallpaper, or null when the background is a plain color. */
    static String key(Config cfg) {
        String mode = cfg.get(Config.BACKGROUND);
        File file = "image".equals(mode) ? cfg.wallpaper() : null;
        if (file != null) return file.getPath() + ":" + file.lastModified();
        return "photo".equals(mode) || "image".equals(mode) ? "photo" : null;
    }

    /** Decodes the wallpaper for this screen. Slow: call off the UI thread. */
    static Pair load(Context c, Config cfg) {
        String key = key(cfg);
        if (key == null) return null;
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        File file = "photo".equals(key) ? null : cfg.wallpaper();
        Bitmap b = file != null ? decode(file, m.widthPixels, m.heightPixels)
                : decodePhoto(c, m.widthPixels, m.heightPixels);
        return b == null ? null : new Pair(key, b, Blur.of(b));
    }

    private static int sample(BitmapFactory.Options o, int w, int h) {
        int s = 1;
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
        return s;
    }

    private static Bitmap decodePhoto(Context c, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(c.getResources(), R.drawable.wallpaper, o);
        o.inSampleSize = sample(o, w, h);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(c.getResources(), R.drawable.wallpaper, o);
    }

    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = sample(o, w, h);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }
}
