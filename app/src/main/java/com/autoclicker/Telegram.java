package com.autoclicker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Sends a message to your own Telegram bot, so an alert reaches your phone wherever you are.
 * The bot token and chat id live only in this app's private settings on the tablet (set on the
 * app screen), never in the code: the repo is public.
 */
final class Telegram {

    static final String KEY_TOKEN = "tg_token";
    static final String KEY_CHAT = "tg_chat";
    static final String KEY_NAME = "bot_name";
    private static final String TAG = "AutoClicker";

    // Messages wait here while Telegram can't be reached and go out in order once it can: on
    // 2026-10-08 api.telegram.org timed out from the home network (PC too) from 18:39, and every
    // farm report in between was lost. One sender thread; a failed send stays at the front.
    private static final int QUEUE_MAX = 50;                    // ~4 h of farm reports
    private static final long RETRY_MIN_MS = 30_000, RETRY_MAX_MS = 5 * 60_000;
    private static final long LATE_MS = 60_000;                 // later than this: say when it was made
    private static final long GAP_MS = 1100;                    // between queued sends (Telegram's 1/s per chat)
    private static final long MAX_AGE_MS = 6 * 60 * 60_000L;    // older than this: not worth sending
    private static final Object WAKE = new Object();            // a new message cuts a retry wait short
    private static long queued;                                 // messages ever queued (under WAKE)
    private static final LinkedBlockingDeque<Pending> queue = new LinkedBlockingDeque<>();
    private static Thread sender;

    private static final class Pending {
        final String token, chat, name, text;
        final long madeAt = System.currentTimeMillis();

        Pending(String token, String chat, String name, String text) {
            this.token = token;
            this.chat = chat;
            this.name = name;
            this.text = text;
        }
    }

    private enum Result { SENT, RETRY, DROP }

    private Telegram() {
    }

    static boolean configured(Context context) {
        SharedPreferences p = context.getSharedPreferences(ClickService.PREFS, Context.MODE_PRIVATE);
        return !p.getString(KEY_TOKEN, "").isEmpty() && !p.getString(KEY_CHAT, "").isEmpty();
    }

    /**
     * This bot's name, shown first in every message: with two or more bots in one channel nobody
     * could tell whose log it was (the user, 2026-10-07). Set on the app screen; by default the
     * device kind (Tablet / Phone).
     */
    static String botName(Context context) {
        String n = context.getSharedPreferences(ClickService.PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, "").trim();
        if (!n.isEmpty()) return n;
        android.util.DisplayMetrics m = context.getResources().getDisplayMetrics();
        float shortDp = Math.min(m.widthPixels, m.heightPixels) / m.density;
        return shortDp >= 600 ? "Tablet" : "Phone";
    }

    /** Queues text to send in the background, in order; does nothing if no bot is set up. */
    static void send(Context context, String text) {
        SharedPreferences p = context.getSharedPreferences(ClickService.PREFS, Context.MODE_PRIVATE);
        String token = p.getString(KEY_TOKEN, "").trim();
        String chat = p.getString(KEY_CHAT, "").trim();
        if (token.isEmpty() || chat.isEmpty()) return;
        synchronized (queue) {
            while (queue.size() >= QUEUE_MAX) {
                Pending old = queue.pollFirst();
                if (old != null) Log.w(TAG, "telegram: " + QUEUE_MAX + " waiting, dropped the one from " + hhmm(old.madeAt));
            }
            queue.offerLast(new Pending(token, chat, botName(context), text));
        }
        startSender();
        // Retry now rather than after the back-off: an urgent alert ("tap Move within ~25 s") must
        // not wait up to 5 min behind a farm report that failed (pre-install audit).
        synchronized (WAKE) {
            queued++;
            WAKE.notifyAll();
        }
    }

    private static synchronized void startSender() {
        if (sender != null && sender.isAlive()) return;
        sender = new Thread(Telegram::sendLoop, "telegram");
        sender.setDaemon(true);
        sender.start();
    }

    /** Sends the queue front first; a failed send waits there, retried after 30 s, 1, 2, 4, then every 5 min. */
    private static void sendLoop() {
        long wait = RETRY_MIN_MS;
        while (true) {
            Pending m;
            try {
                m = queue.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            if (System.currentTimeMillis() - m.madeAt > MAX_AGE_MS) {
                Log.w(TAG, "telegram: dropped the one from " + hhmm(m.madeAt) + ", too old");
                continue;
            }
            long seen;
            synchronized (WAKE) {
                seen = queued;
            }
            Result r = post(m);
            if (r != Result.RETRY) {
                wait = RETRY_MIN_MS;
                if (!queue.isEmpty() && !pause(GAP_MS)) return;
                continue;
            }
            queue.offerFirst(m);
            Log.w(TAG, "telegram: " + queue.size() + " waiting, trying again in " + wait / 1000 + " s");
            // Or sooner, when a message came since this try started - also one that arrived while
            // it was posting, before this wait (a notify then would be missed).
            long deadline = System.nanoTime() + wait * 1_000_000L;
            try {
                synchronized (WAKE) {
                    long left;
                    while (queued == seen && (left = (deadline - System.nanoTime()) / 1_000_000L) > 0) {
                        WAKE.wait(left);
                    }
                }
            } catch (InterruptedException e) {
                return;
            }
            wait = Math.min(wait * 2, RETRY_MAX_MS);
        }
    }

    private static Result post(Pending m) {
        long late = System.currentTimeMillis() - m.madeAt;
        String msg = "[" + m.name + "] " + (late > LATE_MS ? "\u23F3 from " + hhmm(m.madeAt) + " \u00B7 " : "") + m.text;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.telegram.org/bot" + m.token + "/sendMessage").openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = ("chat_id=" + URLEncoder.encode(m.chat, "UTF-8")
                    + "&text=" + URLEncoder.encode(msg, "UTF-8")).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            if (code == 200) {
                Log.i(TAG, late > LATE_MS ? "telegram: sent (" + late / 60_000 + " min late)" : "telegram: sent");
                return Result.SENT;
            }
            // Too many requests or Telegram's own trouble: later. Anything else (a wrong token or
            // chat id, a bad message) won't get better by retrying.
            if (code == 429 || code >= 500) {
                Log.w(TAG, "telegram: failed, HTTP " + code);
                return Result.RETRY;
            }
            Log.w(TAG, "telegram: failed, HTTP " + code + ", dropped");
            return Result.DROP;
        } catch (MalformedURLException | IllegalArgumentException e) {
            // A token or chat id that can't make a request: retrying won't help, and it would hold
            // up everything behind it.
            Log.w(TAG, "telegram: failed, " + e + ", dropped");
            return Result.DROP;
        } catch (Exception e) {
            Log.w(TAG, "telegram: failed, " + e);
            return Result.RETRY;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static String hhmm(long at) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(at));
    }
}
