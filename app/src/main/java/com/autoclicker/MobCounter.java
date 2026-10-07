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

    // Spots below are shares of the screen, measured on the tablet (2560x1600). On another screen
    // ClickService.applyLayout() moves them with Layout's landmarks; S scales pixel sizes.
    static float S = 1f;
    static float TEAM_Y0 = 0.2456f, TEAM_DY = 0.02906f, TEAM_X0 = 0.07f, TEAM_X1 = 0.172f;
    static float XB_L = 0.697f, XB_R = 0.715f, XB_T = 0.069f, XB_B = 0.097f;           // the target bar's ✕
    static float RED_L = 0.32f, RED_R = 0.38f, RED_T = 0.074f, RED_B = 0.092f;        // its HP bar's left end
    static float BAR_L = 0.31f, BAR_R = 0.69f, BAR_T = 0.076f, BAR_B = 0.090f;        // the whole HP bar
    static float HAND_L = 0.656f, HAND_R = 0.707f, HAND_T = 0.856f, HAND_B = 0.931f;  // the loot hand
    static float HPRED_L = 0.121f, HPRED_R = 0.23f, HPRED_T = 0.011f, HPRED_B = 0.025f; // our HP bar
    static float GLYPH1_X = 0.7375f, GLYPH1_Y = 0.572f, GLYPH1_R = 0.0176f;           // the Z button
    static float GLYPH2_X = 0.956f, GLYPH2_Y = 0.389f, GLYPH2_R = 0.0156f;

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
        return Math.round(redPixels / (PIXELS_PER_NAME * S * S));
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
            int c = (int) (screenH * (TEAM_Y0 + k * TEAM_DY));
            int good = 0;
            int total = 0;
            int a = Math.round(27 * S), b = Math.round(17 * S), d = Math.round(16 * S), e = Math.round(26 * S), m = Math.max(1, Math.round(3 * S));
            for (int x = (int) (screenW * TEAM_X0); x < screenW * TEAM_X1; x += Math.max(1, (int) (screenW * 0.006f))) {
                total++;
                if (darkest(shot, x, c - a, c - b) < 45 && darkest(shot, x, c + d, c + e) < 45
                        && barHighlight(shot, x, c - d, c + d) && neutralOrRed(shot, x, c - m, c + m)) {
                    good++;
                }
            }
            if (good * 10 < total * 6) break;
            n++;
        }
        return n;
    }

    /**
     * Party row k's HP (0-1) from the Team list bar (row 0 is the top one). Read from the right,
     * and only right of our own stop button, which covers the left end of row 3 (red too).
     * Measured on the tablet 2026-10-07: 0.99 / 0.66 / 0 for full / ian_ / a dead mate.
     */
    static float memberHp(Bitmap shot, int screenW, int screenH, int k) {
        int c = (int) (screenH * (0.2456f + k * 0.02906f));
        int x0 = Math.round(screenW * (62 / 2560f)), x1 = Math.round(screenW * (452 / 2560f));
        int xMin = Math.round(screenW * (130 / 2560f));
        int y0 = c - Math.round(screenH * 0.005f), y1 = c - Math.round(screenH * 0.0025f);
        if (y0 < 0 || x1 >= shot.getWidth() || y1 >= shot.getHeight()) return -1f;
        for (int x = x1; x >= xMin; x -= 2) {
            if (hpRed(shot.getPixel(x, y0)) || hpRed(shot.getPixel(x, y1))) {
                return Math.min(1f, (x - x0) / (float) (x1 - x0));
            }
        }
        return 0f;
    }

    private static boolean hpRed(int p) {
        return Color.red(p) > 140 && Color.green(p) < 90 && Color.blue(p) < 90;
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
    static float CLOSE_X = 0.7055f;
    static float CLOSE_Y = 0.0825f;

    // How far right of its usual spot the target bar sits, in px. A status icon on its left pushes
    // the whole bar ~30 px right; the fixed check missed the moved X and the bot saw "target none"
    // for minutes, walking off mid-fight every 20 s (2026-10-07 00:44, the tablet).
    static int barShift;
    static final float BAR_SHIFT_MAX = 0.035f;

    /** Where the target bar's X is now (screen px across). */
    static float closeX(int screenW) {
        return screenW * CLOSE_X + barShift;
    }

    /**
     * Whether a target (a player or a monster) is selected. Buffs go to a selected player instead
     * of us, so our own timers never refresh and a full buff keeps "retrying".
     */
    static boolean targetSelected(Bitmap shot, int screenW, int screenH) {
        if (shot.getWidth() < screenW || shot.getHeight() < screenH) return false;
        // The white X: ~10% bright pixels in its box with a target, 0% without. Looked for from its
        // usual box to BAR_SHIFT_MAX further right; the box-wide window with the most wins.
        int x0 = (int) (screenW * XB_L), bw = Math.max(4, (int) (screenW * (XB_R - XB_L)));
        int x1 = Math.min(screenW, x0 + bw + (int) (screenW * BAR_SHIFT_MAX));
        int nc = (x1 - x0) / 2;
        if (nc <= 0) return false;
        int[] col = new int[nc];
        int rows = 0;
        for (int y = (int) (screenH * XB_T); y < screenH * XB_B; y += 2) {
            rows++;
            for (int i = 0; i < nc; i++) {
                int c = shot.getPixel(x0 + 2 * i, y);
                if (Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c))) > 200) col[i]++;
            }
        }
        int k = Math.max(1, bw / 2), best = -1, bestAt = 0, run = 0;
        for (int i = 0; i < nc; i++) {
            run += col[i];
            if (i >= k) run -= col[i - k];
            if (i >= k - 1 && run > best) {
                best = run;
                bestAt = i - k + 1;
            }
        }
        if (rows == 0 || best * 100 < k * rows * 4) return false;
        long sx = 0;
        for (int i = bestAt; i < bestAt + k && i < nc; i++) sx += (long) col[i] * (x0 + 2 * i);
        int shift = Math.max(0, Math.round(sx / (float) best - screenW * CLOSE_X));
        // And the red HP bar's left end (always red unless the target is nearly dead), moved alike.
        if (barRedAt(shot, screenW, screenH, shift)) {
            barShift = shift;
            return true;
        }
        // Party members' icons at the bar's right end can outshine the X (petyes.png): the usual
        // spot then, as before.
        int home = 0;
        for (int i = 0; i < k && i < nc; i++) home += col[i];
        if (shift == 0 || home * 100 < k * rows * 4 || !barRedAt(shot, screenW, screenH, 0)) return false;
        barShift = 0;
        return true;
    }

    private static boolean barRedAt(Bitmap shot, int screenW, int screenH, int shift) {
        int red = 0, total = 0;
        for (int y = (int) (screenH * RED_T); y < screenH * RED_B; y += 2) {
            for (int x = (int) (screenW * RED_L) + shift; x < screenW * RED_R + shift; x += 3) {
                int c = shot.getPixel(Math.min(screenW - 1, x), y);
                total++;
                if (Color.red(c) > 150 && Color.green(c) < 70 && Color.blue(c) < 70) red++;
            }
        }
        return total > 0 && red * 100 >= total * 25;
    }

    /**
     * How full the selected target's HP bar is (0..1), or -1 if none is selected. The bar runs
     * from ~0.31W to ~0.69W at 0.0825H (plus barShift): red for the HP left, grey for what's gone.
     */
    static float targetHp(Bitmap shot, int screenW, int screenH) {
        if (!targetSelected(shot, screenW, screenH)) return -1;
        int left = (int) (screenW * BAR_L) + barShift, right = Math.min(screenW, (int) (screenW * BAR_R) + barShift);
        int lastRed = -1;
        for (int x = left; x < right; x += 2) {
            int red = 0;
            for (int y = (int) (screenH * BAR_T); y < screenH * BAR_B; y += 3) {
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
        return lootHandIn(shot, 0, 0, screenW, screenH);
    }

    /** As lootHandShowing, on a crop whose top-left is at ox,oy on the screen. */
    static boolean lootHandIn(Bitmap crop, int ox, int oy, int screenW, int screenH) {
        int tan = 0;
        int total = 0;
        for (int y = (int) (screenH * HAND_T); y < screenH * HAND_B; y += 3) {
            for (int x = (int) (screenW * HAND_L); x < screenW * HAND_R; x += 3) {
                int cx = x - ox, cy = y - oy;
                if (cx < 0 || cy < 0 || cx >= crop.getWidth() || cy >= crop.getHeight()) continue;
                int c = crop.getPixel(cx, cy);
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
        // A real panel leaves our red HP bar at the top left (~40% red under the map and the
        // Server List); a black/broken game screen or the login screen has none (0%) - no X there
        // (23:30: the game failed to redraw after Messenger and X was tapped on a broken screen).
        int red = 0, n = 0;
        for (int y = (int) (screenH * HPRED_T); y < screenH * HPRED_B; y += 2) {
            for (int x = (int) (screenW * HPRED_L); x < screenW * HPRED_R; x += 4) {
                int c = shot.getPixel(x, y);
                n++;
                if (Color.red(c) > 150 && Color.green(c) < 80 && Color.blue(c) < 80) red++;
            }
        }
        if (n == 0 || red * 100 < n * 15) return false;
        return brightShare(shot, screenW * GLYPH1_X, screenH * GLYPH1_Y, screenW * GLYPH1_R) < 0.03f
                && brightShare(shot, screenW * GLYPH2_X, screenH * GLYPH2_Y, screenW * GLYPH2_R) < 0.02f;
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
