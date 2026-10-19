package tv.shelf.home;

import android.graphics.Bitmap;

/**
 * A cheap frosted-glass source: shrink the wallpaper to a thumbnail, box-blur it three times
 * (close to a Gaussian), and let the GPU scale it back up. Runs once per wallpaper, off the
 * UI thread, in a few milliseconds.
 */
final class Blur {
    private static final int WIDTH = 160;
    private static final int RADIUS = 5;
    private static final int PASSES = 3;

    private Blur() {}

    static Bitmap of(Bitmap in) {
        int w = WIDTH;
        int h = Math.max(1, Math.round(in.getHeight() * (w / (float) in.getWidth())));
        Bitmap small = Bitmap.createScaledBitmap(in, w, h, true);
        if (!small.isMutable() || small == in) small = small.copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < PASSES; i++) {
            horizontal(px, tmp, w, h, RADIUS);
            vertical(tmp, px, w, h, RADIUS);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : v > max ? max : v;
    }

    private static int pack(int a, int r, int g, int b, int div) {
        return ((a / div) << 24) | ((r / div) << 16) | ((g / div) << 8) | (b / div);
    }

    static void horizontal(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, rr = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[row + clamp(i, w - 1)];
                a += p >>> 24;
                rr += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = pack(a, rr, g, b, div);
                int out = src[row + clamp(x - r, w - 1)];
                int in = src[row + clamp(x + r + 1, w - 1)];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                g += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                b += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    static void vertical(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[x + clamp(i, h - 1) * w];
                a += p >>> 24;
                rr += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[x + y * w] = pack(a, rr, g, b, div);
                int out = src[x + clamp(y - r, h - 1) * w];
                int in = src[x + clamp(y + r + 1, h - 1) * w];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                g += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                b += (in & 0xFF) - (out & 0xFF);
            }
        }
    }
}
