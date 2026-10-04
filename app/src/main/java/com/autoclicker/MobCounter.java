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

    /**
     * Members in the "Team" list at the top left (0 if it isn't showing). Each member is an HP bar
     * (red, or grey/black where HP is missing) with a near-black divider above and below; names
     * are written over the middle. Checked on 17 screenshots: parties of 3, 4 and 6-8 read right
     * (a member out of range, with a dark bar, can end the count one early).
     */
    static int partySize(Bitmap shot, int screenW, int screenH) {
        if (shot.getWidth() < screenW || shot.getHeight() < screenH) return 0;
        int n = 0;
        for (int k = 0; k < 8; k++) {
            int c = (int) (screenH * (0.2456f + k * 0.02906f));
            int good = 0;
            int total = 0;
            for (int x = (int) (screenW * 0.07f); x < screenW * 0.172f; x += Math.max(1, (int) (screenW * 0.006f))) {
                total++;
                if (darkest(shot, x, c - 27, c - 17) < 45 && darkest(shot, x, c + 16, c + 26) < 45
                        && barHighlight(shot, x, c - 16, c + 16) && neutralOrRed(shot, x, c - 3, c + 3)) {
                    good++;
                }
            }
            if (good * 10 < total * 6) break;
            n++;
        }
        return n;
    }

    /** The darkest pixel's brightest channel in a column stretch. */
    private static int darkest(Bitmap shot, int x, int y0, int y1) {
        int best = 255;
        for (int y = y0; y <= y1; y++) {
            int c = shot.getPixel(x, y);
            best = Math.min(best, Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c))));
        }
        return best;
    }

    /** A bar's bright stripe: grey (no HP) or pinkish red, never the olive/brown of the ground. */
    private static boolean barHighlight(Bitmap shot, int x, int y0, int y1) {
        for (int y = y0; y <= y1; y++) {
            int c = shot.getPixel(x, y);
            int g = Color.green(c);
            int b = Color.blue(c);
            if (Math.max(Color.red(c), Math.max(g, b)) > 140 && g >= b - 10 && Math.abs(g - b) < 16) return true;
        }
        return false;
    }

    private static boolean neutralOrRed(Bitmap shot, int x, int y0, int y1) {
        for (int y = y0; y <= y1; y += 3) {
            int c = shot.getPixel(x, y);
            if (Math.abs(Color.green(c) - Color.blue(c)) >= 16) return false;
        }
        return true;
    }

    // The selected target's bar at the top centre: its ✕ close button, and the start of its HP bar.
    static final float CLOSE_X = 0.7055f;
    static final float CLOSE_Y = 0.0825f;

    /**
     * Whether a target (a player or a monster) is selected. Buffs go to a selected player instead
     * of us, so our own timers never refresh and a full buff keeps "retrying".
     */
    static boolean targetSelected(Bitmap shot, int screenW, int screenH) {
        if (shot.getWidth() < screenW || shot.getHeight() < screenH) return false;
        // The white ✕: measured 10% bright pixels in this box with a target, 0% without.
        int bright = 0;
        int total = 0;
        for (int y = (int) (screenH * 0.069f); y < screenH * 0.097f; y += 2) {
            for (int x = (int) (screenW * 0.697f); x < screenW * 0.715f; x += 2) {
                int c = shot.getPixel(x, y);
                total++;
                if (Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c))) > 200) bright++;
            }
        }
        if (total == 0 || bright * 100 < total * 4) return false;
        // And the red HP bar's left end (always red unless the target is nearly dead).
        int red = 0;
        total = 0;
        for (int y = (int) (screenH * 0.074f); y < screenH * 0.092f; y += 2) {
            for (int x = (int) (screenW * 0.32f); x < screenW * 0.38f; x += 3) {
                int c = shot.getPixel(x, y);
                total++;
                if (Color.red(c) > 150 && Color.green(c) < 70 && Color.blue(c) < 70) red++;
            }
        }
        return red * 100 >= total * 25;
    }

    /**
     * How full the selected target's HP bar is (0..1), or -1 if none is selected. The bar runs
     * from ~0.31W to ~0.69W at 0.0825H: red for the HP left, grey for what's gone.
     */
    static float targetHp(Bitmap shot, int screenW, int screenH) {
        if (!targetSelected(shot, screenW, screenH)) return -1;
        int left = (int) (screenW * 0.31f), right = (int) (screenW * 0.69f);
        int lastRed = -1;
        for (int x = left; x < right; x += 2) {
            int red = 0;
            for (int y = (int) (screenH * 0.076f); y < screenH * 0.090f; y += 3) {
                int c = shot.getPixel(x, y);
                if (Color.red(c) > 150 && Color.green(c) < 70 && Color.blue(c) < 70) red++;
            }
            if (red >= 2) lastRed = x;
        }
        return lastRed < 0 ? 0f : (lastRed - left) / (float) (right - left);
    }

    /**
     * The loot hand beside F1, which only shows while an item lies nearby. It has tan/skin
     * highlights the grey floor never has: 29-61 such samples with the hand, 0 without, 10-17 when
     * the chat or a panel half covers it (25 screenshots, 2026-10-04).
     */
    static boolean lootHandShowing(Bitmap shot, int screenW, int screenH) {
        if (shot.getWidth() < screenW || shot.getHeight() < screenH) return false;
        int tan = 0;
        int total = 0;
        for (int y = (int) (screenH * 0.856f); y < screenH * 0.931f; y += 3) {
            for (int x = (int) (screenW * 0.656f); x < screenW * 0.707f; x += 3) {
                int c = shot.getPixel(x, y);
                total++;
                if (Color.red(c) > 150 && Color.red(c) - Color.blue(c) > 60 && Color.green(c) > 90) tan++;
            }
        }
        return total > 0 && tan * 1000 >= total * 14;
    }

    /**
     * A game panel (the map, the Server List menu, ...) is covering the screen: the game's own Z
     * button and Stop button are gone. ~11% / ~6% of those spots are white glyph pixels normally,
     * 0% under the map and under the Server List (2026-10-04).
     */
    static boolean hudHidden(Bitmap shot, int screenW, int screenH) {
        if (shot.getWidth() < screenW || shot.getHeight() < screenH) return false;
        return brightShare(shot, screenW * 0.7375f, screenH * 0.572f, screenW * 0.0176f) < 0.03f
                && brightShare(shot, screenW * 0.956f, screenH * 0.389f, screenW * 0.0156f) < 0.02f;
    }

    private static float brightShare(Bitmap shot, float cx, float cy, float r) {
        int bright = 0, n = 0;
        for (int y = (int) (cy - r); y < cy + r; y += 2) {
            for (int x = (int) (cx - r); x < cx + r; x += 2) {
                int c = shot.getPixel(x, y);
                int mn = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                int mx = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
                n++;
                if (mn > 190 && mx - mn < 30) bright++;
            }
        }
        return n > 0 ? bright / (float) n : 0f;
    }

    /**
     * A coarse picture of the play area (brightness of a grid of small patches) to tell whether a
     * walk moved the camera. The centre, where the character itself stands, is left out.
     */
    static int[] sceneThumb(Bitmap shot, int screenW, int screenH) {
        if (shot.getWidth() < screenW || shot.getHeight() < screenH) return null;
        final int cols = 20, rows = 12;
        int[] out = new int[cols * rows];
        for (int gy = 0; gy < rows; gy++) {
            for (int gx = 0; gx < cols; gx++) {
                float fx = 0.1f + 0.8f * (gx + 0.5f) / cols, fy = 0.15f + 0.55f * (gy + 0.5f) / rows;
                if (fx > 0.42f && fx < 0.58f && fy > 0.33f && fy < 0.67f) {
                    out[gy * cols + gx] = -1;
                    continue;
                }
                int cx = (int) (screenW * fx), cy = (int) (screenH * fy), sum = 0, n = 0;
                for (int dy = -6; dy <= 6; dy += 3) {
                    for (int dx = -6; dx <= 6; dx += 3) {
                        int c = shot.getPixel(cx + dx, cy + dy);
                        sum += (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10;
                        n++;
                    }
                }
                out[gy * cols + gx] = sum / n;
            }
        }
        return out;
    }

    /** Average brightness change per patch between two sceneThumb()s (0-255). */
    static int sceneDiff(int[] a, int[] b) {
        long sum = 0;
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] < 0) continue;
            sum += Math.abs(a[i] - b[i]);
            n++;
        }
        return n > 0 ? (int) (sum / n) : 0;
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
