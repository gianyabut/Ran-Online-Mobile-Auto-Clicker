package com.autoclicker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The pet's card beside the chat box: it shows only while the pet is out (the user, 2026-10-07:
 * "if the pet is summoned there is a pet icon beside the chat box"). Found by its picture, not at a
 * fixed spot - it sits elsewhere on every device, and moved ~34 px on the tablet too. Two looks:
 * the usual cyan card and the red one.
 */
final class PetCard {

    private static final String[] TEMPLATES = {"pet/card.png", "pet/card_red.png"};
    private static final int STEP = 3;
    /** A match this good is the card (0.66-1.0 with it, at most 0.57 without, 230 tablet screenshots). */
    static final float MIN_SCORE = 0.6f;

    /** A template at one scale, sampled every STEP px, mean removed. */
    private static final class Tpl {
        final int w, h, n;
        final int[] dx, dy;
        final float[] v;
        final float norm;

        Tpl(int w, int h, int n, int[] dx, int[] dy, float[] v, float norm) {
            this.w = w;
            this.h = h;
            this.n = n;
            this.dx = dx;
            this.dy = dy;
            this.v = v;
            this.norm = norm;
        }
    }

    private static final Map<String, Tpl> cache = new HashMap<>();

    private PetCard() {
    }

    /**
     * Best match of the card with its top-left corner in [x0..x1] x [y0..y1] (screen px), at scale s
     * (1 on the tablet): {score, left, top, width, height}, or null.
     */
    static float[] find(Context c, Bitmap shot, int x0, int y0, int x1, int y1, float s) {
        float[] best = null;
        for (String name : TEMPLATES) {
            Tpl t = template(c, name, s);
            if (t == null) continue;
            int ax = Math.max(0, x0), ay = Math.max(0, y0);
            int bx = Math.min(shot.getWidth() - t.w, x1), by = Math.min(shot.getHeight() - t.h, y1);
            if (bx < ax || by < ay) continue;
            int w = bx - ax + t.w, h = by - ay + t.h;
            int[] g = new int[w * h];
            shot.getPixels(g, 0, w, ax, ay, w, h);
            for (int i = 0; i < g.length; i++) {
                int col = g[i];
                g[i] = (Color.red(col) * 299 + Color.green(col) * 587 + Color.blue(col) * 114) / 1000;
            }
            int step = Math.max(1, Math.round(STEP * s));
            for (int y = 0; y + ay <= by; y += step) {
                for (int x = 0; x + ax <= bx; x += step) {
                    double s1 = 0, s2 = 0, sp = 0;
                    for (int k = 0; k < t.n; k++) {
                        int p = g[(y + t.dy[k]) * w + x + t.dx[k]];
                        s1 += p;
                        s2 += p * p;
                        sp += p * t.v[k];
                    }
                    double var = s2 - s1 * s1 / t.n;
                    if (var < t.n * 16.0) continue;                 // plain ground
                    float sc = (float) (sp / (Math.sqrt(var) * t.norm));
                    if (best == null || sc > best[0]) best = new float[]{sc, x + ax, y + ay, t.w, t.h};
                }
            }
        }
        return best;
    }

    private static Tpl template(Context c, String name, float s) {
        String key = name + "@" + Math.round(s * 1000);
        Tpl t = cache.get(key);
        if (t != null) return t;
        Bitmap src;
        try (InputStream in = c.getAssets().open(name)) {
            src = BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
        if (src == null) return null;
        int w = Math.max(8, Math.round(src.getWidth() * s)), h = Math.max(8, Math.round(src.getHeight() * s));
        Bitmap b = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        if (b != src) b.recycle();
        src.recycle();
        int step = Math.max(1, Math.round(STEP * s)), n = 0;
        for (int y = 0; y < h; y += step) for (int x = 0; x < w; x += step) n++;
        int[] dx = new int[n], dy = new int[n];
        float[] v = new float[n];
        float mean = 0;
        int i = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int col = px[y * w + x];
                dx[i] = x;
                dy[i] = y;
                v[i] = (Color.red(col) * 299 + Color.green(col) * 587 + Color.blue(col) * 114) / 1000f;
                mean += v[i];
                i++;
            }
        }
        mean /= n;
        double norm = 0;
        for (int k = 0; k < n; k++) {
            v[k] -= mean;
            norm += v[k] * v[k];
        }
        t = new Tpl(w, h, n, dx, dy, v, (float) Math.sqrt(norm));
        cache.put(key, t);
        return t;
    }
}
