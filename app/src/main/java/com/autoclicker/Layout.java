package com.autoclicker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where the game's screen pieces are on a screen that isn't the tablet's 2560x1600 (the user's
 * REDMI phone, 2026-10-06). Every fixed spot in the bot was measured on the tablet. On another
 * screen the game shrinks its interface to the screen height and pins each piece to an edge or
 * to its own panel, a little differently each (the phone: 0.80 down, ~0.85 across on the right,
 * 0.95 across for the chat box). So a few always-there pieces - landmarks, cut from a tablet
 * screenshot into assets/layout - are found once on the new screen, and every tablet spot is
 * moved with its nearest landmark: device = landmark + (spot - landmark's tablet spot) * scale.
 * On the tablet itself this is never used (active stays false), so it changes nothing there.
 */
final class Layout {

    static final int REF_W = 2560, REF_H = 1600;

    /** A landmark: its centre and size on the tablet, where to look for it (share of the screen), its group. */
    static final class Lm {
        final String name, group;
        final int tx, ty;
        final float l, t, r, b, minScore;

        Lm(String name, String group, int tx, int ty, float l, float t, float r, float b, float minScore) {
            this.name = name;
            this.group = group;
            this.tx = tx;
            this.ty = ty;
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
            this.minScore = minScore;
        }
    }

    static final String RIGHT = "right", CHAT = "chat", LEFT = "left";

    // Measured on the tablet (e0.png, 2026-10-06) and found again on the phone's screenshots.
    static final Lm[] LANDMARKS = {
            new Lm("X", RIGHT, 2340, 42, 0.70f, 0f, 1f, 0.12f, 0.6f),
            new Lm("menu", RIGHT, 2180, 45, 0.65f, 0f, 1f, 0.12f, 0.6f),
            new Lm("paw", RIGHT, 1830, 45, 0.50f, 0f, 0.95f, 0.12f, 0.55f),
            new Lm("chatbub", RIGHT, 1952, 506, 0.55f, 0.15f, 0.95f, 0.55f, 0.6f),
            new Lm("Z", RIGHT, 1891, 915, 0.55f, 0.40f, 0.95f, 0.75f, 0.6f),
            new Lm("fist", RIGHT, 2362, 1389, 0.75f, 0.70f, 1f, 1f, 0.6f),
            new Lm("f1", RIGHT, 1843, 1519, 0.50f, 0.80f, 0.85f, 1f, 0.55f),
            new Lm("hp", LEFT, 235, 26, 0f, 0f, 0.30f, 0.10f, 0.6f),
            new Lm("joystick", LEFT, 243, 1261, 0f, 0.55f, 0.30f, 1f, 0.45f),
            new Lm("Q", LEFT, 544, 1478, 0.10f, 0.82f, 0.45f, 1f, 0.6f),
            new Lm("chatAll", CHAT, 846, 1184, 0.20f, 0.60f, 0.60f, 0.90f, 0.6f),
            new Lm("chatExpand", CHAT, 1476, 1184, 0.40f, 0.60f, 0.85f, 0.90f, 0.55f),
    };

    static final class Found {
        final float x, y, scale, score;

        Found(float x, float y, float scale, float score) {
            this.x = x;
            this.y = y;
            this.scale = scale;
            this.score = score;
        }
    }

    private static final Map<String, Found> found = new HashMap<>();
    private static final Map<String, Lm> byName = new HashMap<>();
    private static boolean active;
    private static int W = REF_W, H = REF_H;
    private static float sy = 1f, sxRight = 1f, sxChat = 1f, sxLeft = 1f;

    static {
        for (Lm m : LANDMARKS) byName.put(m.name, m);
    }

    private Layout() {
    }

    /** True when a measured layout for a non-tablet screen is in use. */
    static boolean active() {
        return active;
    }

    static boolean isRefScreen(int w, int h) {
        return Math.max(w, h) == REF_W && Math.min(w, h) == REF_H;
    }

    private static String key(int w, int h) {
        return "layout_" + Math.max(w, h) + "x" + Math.min(w, h);
    }

    /** Loads the saved layout for this screen size; false if there's none (or it's the tablet). */
    static boolean load(Context c, int w, int h) {
        active = false;
        found.clear();
        if (isRefScreen(w, h)) return false;
        String s = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(key(w, h), null);
        if (s == null) return false;
        for (String e : s.split(";")) {
            String[] p = e.split(",");
            if (p.length != 5) continue;
            try {
                found.put(p[0], new Found(Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                        Float.parseFloat(p[3]), Float.parseFloat(p[4])));
            } catch (NumberFormatException ignored) {
            }
        }
        return finish(w, h);
    }

    static void forget(Context c, int w, int h) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove(key(w, h)).apply();
        active = false;
        found.clear();
    }

    /** What measure() found; adopt() it on the main thread. */
    static final class Result {
        final Map<String, Found> got;
        final String summary;
        final boolean ok;
        final int w, h;

        Result(Map<String, Found> got, String summary, boolean ok, int w, int h) {
            this.got = got;
            this.summary = summary;
            this.ok = ok;
            this.w = w;
            this.h = h;
        }
    }

    /** Saves a good measurement and switches the layout on (main thread). */
    static void adopt(Context c, Result r) {
        if (!r.ok) return;
        found.clear();
        found.putAll(r.got);
        StringBuilder save = new StringBuilder();
        for (Map.Entry<String, Found> e : r.got.entrySet()) {
            Found f = e.getValue();
            if (save.length() > 0) save.append(';');
            save.append(e.getKey()).append(',').append(f.x).append(',').append(f.y).append(',')
                    .append(f.scale).append(',').append(f.score);
        }
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(key(r.w, r.h), save.toString()).apply();
        finish(r.w, r.h);
    }

    /**
     * Finds the landmarks on a full screenshot of the game (HUD showing) - slow, run it off the main
     * thread. Changes nothing: adopt() the result.
     */
    static Result measure(Context c, Bitmap shot) {
        int w = shot.getWidth(), h = shot.getHeight();
        if (isRefScreen(w, h)) return new Result(new HashMap<>(), "tablet screen, nothing to measure", false, w, h);
        float s = h / (float) REF_H;
        int[] gray = grayOf(shot);
        Map<String, Found> got = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (Lm m : LANDMARKS) {
            Found f = find(c, m, gray, w, h, s);
            if (f != null && f.score >= m.minScore) got.put(m.name, f);
            sb.append(m.name).append(f == null ? " -" : String.format(Locale.ROOT, " %.0f,%.0f %.2f", f.x, f.y, f.score))
                    .append(f != null && f.score >= m.minScore ? "" : "(no)").append("; ");
        }
        // Enough to place things: the right side, the chat box and the left side.
        boolean right = got.containsKey("X") || got.containsKey("menu") || got.containsKey("paw");
        boolean right2 = got.containsKey("Z") || got.containsKey("fist");
        boolean chat = got.containsKey("chatAll") || got.containsKey("chatExpand");
        boolean left = got.containsKey("hp");
        boolean ok = right && right2 && chat && left && got.size() >= 7;
        return new Result(got, (ok ? "" : "not enough: ") + got.size() + "/" + LANDMARKS.length + " found: " + sb, ok, w, h);
    }

    /** Group scales from the found landmarks: across from pairs in a group, down from the height. */
    private static boolean finish(int w, int h) {
        W = Math.max(w, h);
        H = Math.min(w, h);
        sy = H / (float) REF_H;
        sxRight = pairScale(new String[][]{{"X", "paw"}, {"Z", "fist"}, {"menu", "paw"}, {"X", "Z"}}, sy);
        sxChat = pairScale(new String[][]{{"chatAll", "chatExpand"}}, sy);
        // The left pieces aren't pinned alike (hp to the corner, Q to the chat box): a pair of them
        // gave 2.06 on the phone. Each moves with its own landmark at the height scale.
        sxLeft = sy;
        active = !found.isEmpty();
        return active;
    }

    private static float pairScale(String[][] pairs, float fallback) {
        float sum = 0;
        int n = 0;
        for (String[] p : pairs) {
            Found a = found.get(p[0]), b = found.get(p[1]);
            Lm la = byName.get(p[0]), lb = byName.get(p[1]);
            if (a == null || b == null || Math.abs(la.tx - lb.tx) < 200) continue;
            float k = Math.abs(a.x - b.x) / Math.abs(la.tx - lb.tx);
            if (k > 0.3f && k < 2.5f) {
                sum += k;
                n++;
            }
        }
        return n > 0 ? sum / n : fallback;
    }

    private static float sxOf(String group) {
        switch (group) {
            case RIGHT:
                return sxRight;
            case CHAT:
                return sxChat;
            default:
                return sxLeft;
        }
    }

    /**
     * A tablet spot (pixels on 2560x1600) on this screen, moved with landmark lm (or, if that one
     * wasn't found, the nearest found one of its group; with none, by the screen's edge/centre).
     * "center" places it from the screen's centre (dialogs, the target bar).
     */
    static float[] pt(String lm, float tx, float ty) {
        if (!active) return new float[]{tx / REF_W * W, ty / REF_H * H};
        // Windows in the middle scale like the right side across (the phone's target bar: 0.85 wide,
        // its X 27 px off at the height scale, 23:22) and like everything else down.
        if (lm.equals("center")) return new float[]{W / 2f + (tx - REF_W / 2f) * sxRight, H / 2f + (ty - REF_H / 2f) * sy};
        Lm m = byName.get(lm);
        Found f = found.get(lm);
        if (f == null && m != null) {
            float best = Float.MAX_VALUE;
            for (Lm o : LANDMARKS) {
                Found of = found.get(o.name);
                if (of == null || !o.group.equals(m.group)) continue;
                float d = (float) Math.hypot(o.tx - tx, o.ty - ty);
                if (d < best) {
                    best = d;
                    m = o;
                    f = of;
                }
            }
        }
        if (f == null || m == null) {
            // No landmark at all on that side: pin by the edge the tablet spot is nearer.
            float x = tx > REF_W * 0.6f ? W - (REF_W - tx) * sy : tx < REF_W * 0.4f ? tx * sy : W / 2f + (tx - REF_W / 2f) * sy;
            return new float[]{x, ty * sy};
        }
        return new float[]{f.x + (tx - m.tx) * sxOf(m.group), f.y + (ty - m.ty) * sy};
    }

    /** As pt(), as shares of this screen (what the bot's position constants hold). */
    static float fx(String lm, float tx, float ty) {
        return pt(lm, tx, ty)[0] / W;
    }

    static float fy(String lm, float tx, float ty) {
        return pt(lm, tx, ty)[1] / H;
    }

    /** Down scale (pixel sizes that shrink with the screen height). */
    static float sy() {
        return active ? sy : 1f;
    }

    /** Across scale of a group (pixel widths). */
    static float sx(String group) {
        return active ? sxOf(group) : 1f;
    }

    static String summary() {
        return String.format(Locale.ROOT, "%d landmarks, down %.3f, across right %.3f / chat %.3f / left %.3f",
                found.size(), sy, sxRight, sxChat, sxLeft);
    }

    private static final Map<String, int[]> nearTpl = new HashMap<>();   // scaled gray templates: w, h, pixels...

    /**
     * How well landmark lm matches within radius px of where it belongs (best NCC, -1 if it can't be
     * checked there). bmp's top-left is at ox,oy on screen; works on the tablet too (scale 1).
     */
    static float near(Context c, String lm, Bitmap bmp, int ox, int oy, int radius) {
        Lm m = byName.get(lm);
        if (m == null) return -1;
        Found f = found.get(lm);
        float sc = active && f != null ? f.scale : sy();
        String key = lm + "@" + Math.round(sc * 1000);
        int[] t = nearTpl.get(key);
        if (t == null) {
            Bitmap tpl = template(c, lm);
            if (tpl == null) return -1;
            int tw = Math.max(8, Math.round(tpl.getWidth() * sc)), th = Math.max(8, Math.round(tpl.getHeight() * sc));
            Bitmap s = Bitmap.createScaledBitmap(tpl, tw, th, true);
            t = new int[2 + tw * th];
            t[0] = tw;
            t[1] = th;
            s.getPixels(t, 2, tw, 0, 0, tw, th);
            if (s != tpl) s.recycle();
            tpl.recycle();
            for (int i = 2; i < t.length; i++) t[i] = Color.red(t[i]);
            nearTpl.put(key, t);
        }
        int tw = t[0], th = t[1];
        float[] p = pt(lm, m.tx, m.ty);
        int ax = Math.round(p[0] - tw / 2f) - ox - radius, ay = Math.round(p[1] - th / 2f) - oy - radius;
        int bx = ax + 2 * radius + tw, by = ay + 2 * radius + th;
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        ax = Math.max(0, ax);
        ay = Math.max(0, ay);
        bx = Math.min(bw, bx);
        by = Math.min(bh, by);
        if (bx - ax <= tw || by - ay <= th) return -1;
        int w = bx - ax, h = by - ay;
        int[] g = new int[w * h];
        bmp.getPixels(g, 0, w, ax, ay, w, h);
        for (int i = 0; i < g.length; i++) {
            int col = g[i];
            g[i] = (Color.red(col) * 299 + Color.green(col) * 587 + Color.blue(col) * 114) / 1000;
        }
        int[] tp = new int[tw * th];
        System.arraycopy(t, 2, tp, 0, tp.length);
        float[] r = scan(g, w, tp, tw, th, 0, 0, w - tw, h - th, 2, 2);
        return r == null ? -1 : r[0];
    }

    // ---------- finding a landmark ----------

    private static int[] grayOf(Bitmap shot) {
        int w = shot.getWidth(), h = shot.getHeight();
        int[] px = new int[w * h];
        shot.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
        }
        return px;
    }

    private static Bitmap template(Context c, String name) {
        try (InputStream in = c.getAssets().open("layout/" + name + ".png")) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** Best normalised-correlation match of the landmark at scales s x 0.95..1.25 inside its search box. */
    private static Found find(Context c, Lm m, int[] gray, int w, int h, float s) {
        Bitmap tpl = template(c, m.name);
        if (tpl == null) return null;
        int x0 = Math.round(m.l * w), y0 = Math.round(m.t * h), x1 = Math.round(m.r * w), y1 = Math.round(m.b * h);
        Found best = null;
        for (float k = 0.95f; k <= 1.251f; k += 0.05f) {
            float sc = s * k;
            int tw = Math.max(8, Math.round(tpl.getWidth() * sc)), th = Math.max(8, Math.round(tpl.getHeight() * sc));
            if (tw >= x1 - x0 || th >= y1 - y0) continue;
            Bitmap t = Bitmap.createScaledBitmap(tpl, tw, th, true);
            int[] tp = new int[tw * th];
            t.getPixels(tp, 0, tw, 0, 0, tw, th);
            if (t != tpl) t.recycle();
            for (int i = 0; i < tp.length; i++) tp[i] = Color.red(tp[i]);   // gray already
            // Coarse: every 2nd position, every 2nd template pixel.
            float[] r = scan(gray, w, tp, tw, th, x0, y0, x1 - tw, y1 - th, 2, 2);
            if (r == null) continue;
            // Fine: every position within 2 px, every template pixel.
            float[] f = scan(gray, w, tp, tw, th, Math.max(x0, (int) r[1] - 2), Math.max(y0, (int) r[2] - 2),
                    Math.min(x1 - tw, (int) r[1] + 2), Math.min(y1 - th, (int) r[2] + 2), 1, 1);
            if (f == null) f = r;
            if (best == null || f[0] > best.score) best = new Found(f[1] + tw / 2f, f[2] + th / 2f, sc, f[0]);
        }
        tpl.recycle();
        return best;
    }

    /** {score, x, y} of the best match with the top-left corner in [ax..bx] x [ay..by], or null. */
    private static float[] scan(int[] g, int w, int[] tp, int tw, int th, int ax, int ay, int bx, int by,
                                int posStep, int tplStep) {
        // Template samples, mean-removed.
        int n = 0;
        for (int y = 0; y < th; y += tplStep) for (int x = 0; x < tw; x += tplStep) n++;
        int[] ox = new int[n];
        float[] tv = new float[n];
        float tm = 0;
        int i = 0;
        for (int y = 0; y < th; y += tplStep) {
            for (int x = 0; x < tw; x += tplStep) {
                ox[i] = y * w + x;
                tv[i] = tp[y * tw + x];
                tm += tv[i];
                i++;
            }
        }
        tm /= n;
        double tn = 0;
        for (int k = 0; k < n; k++) {
            tv[k] -= tm;
            tn += tv[k] * tv[k];
        }
        tn = Math.sqrt(tn);
        if (tn < 1) return null;
        float bestS = -2, bestX = -1, bestY = -1;
        for (int y = ay; y <= by; y += posStep) {
            for (int x = ax; x <= bx; x += posStep) {
                int base = y * w + x;
                double s1 = 0, s2 = 0, sp = 0;
                for (int k = 0; k < n; k++) {
                    int v = g[base + ox[k]];
                    s1 += v;
                    s2 += v * v;
                    sp += v * tv[k];
                }
                double var = s2 - s1 * s1 / n;
                if (var < n * 64.0) continue;                   // plain colour: no landmark there
                float sc = (float) (sp / (Math.sqrt(var) * tn));
                if (sc > bestS) {
                    bestS = sc;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return bestX < 0 ? null : new float[]{bestS, bestX, bestY};
    }
}
