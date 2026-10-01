package com.autoclicker;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Estimates how many monsters are on screen from their name tags: pure red text on a dark
 * translucent box (players, pets and items are white, green or yellow). In a crowd the tags pile
 * on top of each other, so it counts red tag pixels and divides by what one name averages.
 * Good for "none / a few / a big pull", not an exact count (a pile of ~12 reads 9-10).
 */
final class MobCounter {

    // Every 2nd pixel each way: a quarter of the work, and names are ~30 px tall anyway.
    private static final int STEP = 2;
    // Red tag pixels in one name ("Skating Boy" ~1750, "Skating Master" ~2200), full resolution.
    private static final int PIXELS_PER_NAME = 1900;
    // A letter pixel has the dark label box within this many pixels above or below it.
    private static final int REACH = 12;
    private static final int MIN_DARK = 3;

    private static int[] row = new int[0];

    private MobCounter() {
    }

    /** Rough number of monsters on screen, or -1 if shot isn't the whole screen. */
    static int count(Bitmap shot, int screenW, int screenH) {
        int w = shot.getWidth();
        int h = shot.getHeight();
        if (w < screenW || h < screenH) return -1;
        if (row.length < w) row = new int[w];
        long redPixels = 0;
        for (int y = REACH; y < h - REACH; y += STEP) {
            shot.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += STEP) {
                if (!isTagRed(row[x]) || hud(x, y, w, h)) continue;
                if (onDarkBox(shot, x, y)) redPixels += STEP * STEP;
            }
        }
        return Math.round(redPixels / (float) PIXELS_PER_NAME);
    }

    private static boolean isTagRed(int c) {
        int r = Color.red(c);
        int g = Color.green(c);
        int b = Color.blue(c);
        return r > 130 && g < 45 && b < 45 && r - Math.max(g, b) > 110;
    }

    /** Red graffiti and effects sit on bright, busy art; a name tag's letters sit on a dark box. */
    private static boolean onDarkBox(Bitmap shot, int x, int y) {
        int dark = 0;
        for (int dy = -REACH; dy <= REACH; dy += STEP) {
            int c = shot.getPixel(x, y + dy);
            if (isTagRed(c)) continue;
            int lum = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10;
            if (lum < 60 && ++dark >= MIN_DARK) return true;
        }
        return false;
    }

    /**
     * HUD parts with red in them: HP panel and team list (red HP bars), chat (red system lines),
     * top menu, the selected target's bar, minimap, joystick, and the skill buttons with our red
     * rings.
     */
    private static boolean hud(int x, int y, int w, int h) {
        float fx = x / (float) w;
        float fy = y / (float) h;
        return (fx < 0.25f && fy < 0.42f)
                || (fx > 0.31f && fx < 0.69f && fy > 0.72f)
                || (fx > 0.65f && fy < 0.08f)
                || (fx > 0.28f && fx < 0.72f && fy < 0.09f) // selected target's name and HP bar
                || (fx > 0.78f && fy > 0.08f && fy < 0.25f)
                || (fx < 0.20f && fy > 0.60f)
                || (fx > 0.78f && fy > 0.52f)
                || (fx > 0.64f && fy > 0.62f);
    }
}
