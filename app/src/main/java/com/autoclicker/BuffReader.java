package com.autoclicker;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the game's buff row (top left, under the portrait): every buff icon with its light
 * grey square frame, and how full the timer bar to its right still is. Icons move around as
 * buffs come and go, so a buff is recognised by its look, not its position.
 */
final class BuffReader {

    static final class Icon {
        int x;
        int y;
        int size;
        int[] sig;  // colour signature, see signature()
        float fill; // 0..1, share of the timer bar still left
    }

    private static final int GRID = 6;
    // Buff icons are squares of this many pixels (about 57 on the Xiaomi Pad 5).
    private static final int MIN_ICON = 36;
    private static final int MAX_ICON = 100;
    // Average colour difference per channel (0-255) below which two icons count as the same buff.
    // The same icon scores 0-8; Fortify vs Inspire (both light-on-dark art) score about 37-40.
    private static final int MATCH_LIMIT = 20;
    // After the game redraws the HUD at another size (62 px -> 57 px), the same icon scores up to
    // about 20; the closest stranger in the row (someone else's red buff vs Fortify) scored 35.
    private static final int LOOSE_LIMIT = 28;
    // A row of the bar counts as filled when it has at least this many coloured pixels side by
    // side. The bar is about 8 px wide; its thin red border line must not count as "full".
    private static final int MIN_BAR_RUN = 3;

    private static int[] px = new int[0];

    private BuffReader() {
    }

    /** Finds every framed icon in the top-left part of the screen. */
    static List<Icon> scan(Bitmap shot, int screenW, int screenH) {
        // shot may be the whole screen or just its top-left corner.
        int w = Math.min(shot.getWidth(), screenW * 45 / 100);
        int h = Math.min(shot.getHeight(), screenH * 35 / 100);
        if (px.length < w * h) px = new int[w * h];
        shot.getPixels(px, 0, w, 0, 0, w, h);

        List<Icon> icons = new ArrayList<>();
        // Start below the character panel, whose HP/MP label boxes also look like framed icons.
        for (int y = screenH * 12 / 100; y < h - MIN_ICON; y++) {
            int run = 0;
            for (int x = 0; x <= w; x++) {
                boolean grey = x < w && isFrame(px[y * w + x]);
                if (grey) {
                    run++;
                    continue;
                }
                int len = run;
                int left = x - len;
                run = 0;
                // A top edge of the right length, with matching left and right edges below it.
                if (len < MIN_ICON || len > MAX_ICON || y + len >= h || left + len + len / 3 >= w) continue;
                // Both sides framed, or one clean side with the other partly there: a bright icon can
                // tint its own frame (the Brawler's golden buff turned the right edge yellow, 156,134,90,
                // so only 15 of 57 px read grey and the whole row was lost, 2026-10-04).
                int leftEdge = edgeCount(w, left, y, len), rightEdge = edgeCount(w, left + len - 1, y, len);
                int full = len * 65 / 100, part = len * 20 / 100;
                if (!(leftEdge >= full && rightEdge >= part) && !(rightEdge >= full && leftEdge >= part)) continue;
                if (overlaps(icons, left, y)) continue;
                Icon icon = new Icon();
                icon.x = left;
                icon.y = y;
                icon.size = len;
                icon.sig = signature(w, left, y, len);
                icon.fill = barFill(w, left, y, len);
                icons.add(icon);
            }
        }
        return buffRow(icons, screenW);
    }

    /**
     * Name tags and pet labels out in the game world also look like framed boxes. The buff row
     * always starts at the left edge under the portrait and runs right in one line, so keep only
     * the icons lined up with the one at the left edge.
     */
    private static List<Icon> buffRow(List<Icon> all, int screenWidth) {
        Icon first = null;
        for (Icon i : all) {
            if (i.x < screenWidth * 3 / 100 && (first == null || i.y < first.y)) first = i;
        }
        List<Icon> row = new ArrayList<>();
        if (first == null) return row; // no buffs active
        for (Icon i : all) {
            if (Math.abs(i.y - first.y) <= 4 && Math.abs(i.size - first.size) <= 5) row.add(i);
        }
        return row;
    }

    /**
     * The buff's icon with the most time left, or null if it isn't showing. The same buff can
     * appear twice (a party member cast Inspire too); the freshest one is what counts.
     */
    static Icon freshest(List<Icon> icons, int[] sig) {
        Icon best = null;
        for (Icon icon : icons) {
            if (diff(icon.sig, sig) < MATCH_LIMIT && (best == null || icon.fill > best.fill)) best = icon;
        }
        return best;
    }

    /**
     * Like freshest(), but if nothing matches closely (the HUD was redrawn at another size), takes
     * the icon that looks most like sig within a looser limit, unless it looks even more like one
     * of the other learned buffs in others.
     */
    static Icon find(List<Icon> icons, int[] sig, List<int[]> others) {
        Icon icon = freshest(icons, sig);
        if (icon != null) return icon;
        int bestDiff = LOOSE_LIMIT;
        for (Icon i : icons) {
            int d = diff(i.sig, sig);
            if (d >= bestDiff || closerToOther(i, d, others)) continue;
            bestDiff = d;
            icon = i;
        }
        return icon;
    }

    private static boolean closerToOther(Icon icon, int d, List<int[]> others) {
        for (int[] o : others) {
            if (diff(icon.sig, o) <= d) return true;
        }
        return false;
    }

    /** The icon in the list that looks most like sig, or null if the buff isn't showing. */
    static Icon match(List<Icon> icons, int[] sig) {
        Icon best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (Icon icon : icons) {
            int d = diff(icon.sig, sig);
            if (d < bestDiff) {
                bestDiff = d;
                best = icon;
            }
        }
        return bestDiff < MATCH_LIMIT ? best : null;
    }

    /**
     * Compares the buff row just before and just after a ring cast its buff. The one icon that
     * appeared, or whose timer jumped back to (nearly) full, is that ring's buff. Returns null if
     * there isn't exactly one, e.g. the tap was ignored or a teammate buffed at the same moment.
     */
    static Icon refreshedIcon(List<Icon> before, List<Icon> after) {
        // Both reads must be the same row: once the "before" row was other, smaller boxes (42 px at
        // y 210 vs the buff row's 62 px at y 238), so every real icon looked new and a ring learned
        // someone else's buff (2026-10-04 13:30).
        if (!before.isEmpty() && !after.isEmpty()
                && (Math.abs(before.get(0).size - after.get(0).size) > 6
                || Math.abs(before.get(0).y - after.get(0).y) > 6)) return null;
        Icon appeared = null;
        int appearedCount = 0;
        Icon best = null;
        float bestGain = 0f;
        float secondGain = 0f;
        for (Icon a : after) {
            Icon b = match(before, a.sig);
            if (b == null) {
                // A buff that was just cast shows a full bar; an empty "new icon" is some other box.
                if (a.fill < 0.85f) continue;
                appeared = a;
                appearedCount++;
                continue;
            }
            float gain = a.fill - b.fill;
            if (gain > bestGain) {
                secondGain = bestGain;
                bestGain = gain;
                best = a;
            } else if (gain > secondGain) {
                secondGain = gain;
            }
        }
        // A buff that wasn't active shows up as a new icon.
        if (appearedCount == 1) return appeared;
        if (appearedCount > 1) return null;
        // Recasting a nearly full buff only tops it up a little (94% -> 100%), but every other
        // bar drains meanwhile, so the one that rose to full while the rest didn't is ours.
        if (best != null && bestGain >= 0.04f && best.fill >= 0.9f && secondGain <= 0.015f) return best;
        return null;
    }

    /** Average colour difference per channel. */
    static int diff(int[] a, int[] b) {
        if (a.length != b.length) return Integer.MAX_VALUE;
        long sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
        return (int) (sum / a.length);
    }

    /** The frame's top edge: light neutral grey. */
    private static boolean isFrame(int c) {
        int r = Color.red(c);
        int g = Color.green(c);
        int b = Color.blue(c);
        int min = Math.min(r, Math.min(g, b));
        int max = Math.max(r, Math.max(g, b));
        return min > 115 && max - min < 35;
    }

    /** The frame is bevelled: its side edges can be light or dark, but always neutral grey. */
    private static boolean isGrey(int c) {
        int r = Color.red(c);
        int g = Color.green(c);
        int b = Color.blue(c);
        int max = Math.max(r, Math.max(g, b));
        return max - Math.min(r, Math.min(g, b)) < 30 && max > 55 && max < 215;
    }

    /**
     * How many of the len pixels down from y at column x are frame-grey (a vertical frame line).
     * Player names floating over the row can hide part of an edge, so 65% counts as framed.
     */
    private static int edgeCount(int w, int x, int y, int len) {
        int count = 0;
        for (int yy = y; yy < y + len; yy++) {
            if (isGrey(px[yy * w + x]) || isGrey(px[yy * w + Math.max(0, x - 1)]) || isGrey(px[yy * w + Math.min(w - 1, x + 1)])) {
                count++;
            }
        }
        return count;
    }

    private static boolean overlaps(List<Icon> icons, int x, int y) {
        for (Icon i : icons) {
            if (Math.abs(i.x - x) < i.size / 2 && Math.abs(i.y - y) < i.size / 2) return true;
        }
        return false;
    }

    /**
     * Average colours of GRID x GRID small patches inside the icon, skipping its frame.
     * Averaging a patch (not reading one pixel) keeps a detailed icon recognisable when the
     * game redraws the HUD a little bigger or smaller, e.g. after restarting (57 px -> 62 px).
     */
    private static int[] signature(int w, int x, int y, int size) {
        int[] sig = new int[GRID * GRID * 3];
        int r = Math.max(1, size / 24);
        int i = 0;
        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                int cx = (int) (x + size * (0.15f + 0.7f * (gx + 0.5f) / GRID));
                int cy = (int) (y + size * (0.15f + 0.7f * (gy + 0.5f) / GRID));
                int sr = 0, sg = 0, sb = 0, n = 0;
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int c = px[(cy + dy) * w + (cx + dx)];
                        sr += Color.red(c);
                        sg += Color.green(c);
                        sb += Color.blue(c);
                        n++;
                    }
                }
                sig[i++] = sr / n;
                sig[i++] = sg / n;
                sig[i++] = sb / n;
            }
        }
        return sig;
    }

    /** The timer bar sits just right of the icon and drains from the top. */
    private static float barFill(int w, int x, int y, int size) {
        float[] hsv = new float[3];
        int filledRows = 0;
        for (int yy = y; yy < y + size; yy++) {
            int run = 0;
            int longest = 0;
            for (int xx = x + size; xx < x + size + size / 3; xx++) {
                Color.colorToHSV(px[yy * w + xx], hsv);
                run = hsv[1] > 0.45f && hsv[2] > 0.5f ? run + 1 : 0;
                longest = Math.max(longest, run);
            }
            if (longest >= MIN_BAR_RUN) filledRows++;
        }
        // The bar runs the full height of the icon (at 62 px; assuming 90% read ~10% too high).
        return Math.min(1f, filledRows / (float) size);
    }
}
