package com.autoclicker;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Spots the game's "are you there?" panel ("Hello, please click "Confirm" button." with a Move
 * button above the chat). Left unanswered for ~25 s it disconnects you (2026-10-02 14:11:46 ->
 * 14:12:15). Only you answer it; this just lets the alert go off. Recognised by the Move button,
 * which sits in a fixed spot: the four screenshots with the panel matched exactly (0.0), one with
 * the panel still sliding in 18.9, and 20+ without it 28 or more.
 */
final class Prompts {

    // The Move button on a 2560x1600 screen, and its look: 3 x 8 patches, each plain grey.
    private static final float LEFT = 1257 / 2560f;
    private static final float TOP = 1115 / 1600f;
    private static final float WIDTH = 178 / 2560f;
    private static final float HEIGHT = 38 / 1600f;
    private static final int[] MOVE_BUTTON = {
            116, 115, 118, 118, 115, 115, 115, 116,
            67, 67, 117, 129, 105, 109, 70, 67,
            71, 68, 120, 120, 112, 118, 74, 70,
    };
    private static final int MATCH_LIMIT = 10;

    private Prompts() {
    }

    static boolean presenceCheck(Bitmap shot, int screenW, int screenH) {
        if (shot.getWidth() < screenW || shot.getHeight() < screenH) return false;
        float px = screenW * WIDTH / 178f;
        float py = screenH * HEIGHT / 38f;
        int x0 = Math.round(screenW * LEFT);
        int y0 = Math.round(screenH * TOP);
        long diff = 0;
        int n = 0;
        for (int gy = 0; gy < 3; gy++) {
            for (int gx = 0; gx < 8; gx++) {
                int want = MOVE_BUTTON[gy * 8 + gx];
                int r = 0, g = 0, b = 0, count = 0;
                // The same patches the fingerprint was taken from, sampled every 3rd pixel.
                for (int y = gy * 12 + 1; y < gy * 12 + 11; y += 3) {
                    for (int x = gx * 22 + 2; x < gx * 22 + 20; x += 3) {
                        int c = shot.getPixel(x0 + Math.round(x * px), y0 + Math.round(y * py));
                        r += Color.red(c);
                        g += Color.green(c);
                        b += Color.blue(c);
                        count++;
                    }
                }
                diff += Math.abs(r / count - want) + Math.abs(g / count - want) + Math.abs(b / count - want);
                n += 3;
            }
        }
        return diff / n < MATCH_LIMIT;
    }
}
