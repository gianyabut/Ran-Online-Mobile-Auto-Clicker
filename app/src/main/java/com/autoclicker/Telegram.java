package com.autoclicker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

    /** Sends text in the background; does nothing if no bot is set up. */
    static void send(Context context, String text) {
        text = "[" + botName(context) + "] " + text;
        SharedPreferences p = context.getSharedPreferences(ClickService.PREFS, Context.MODE_PRIVATE);
        String token = p.getString(KEY_TOKEN, "").trim();
        String chat = p.getString(KEY_CHAT, "").trim();
        if (token.isEmpty() || chat.isEmpty()) return;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://api.telegram.org/bot" + token + "/sendMessage").openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                byte[] body = ("chat_id=" + URLEncoder.encode(chat, "UTF-8")
                        + "&text=" + URLEncoder.encode(text, "UTF-8")).getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
                int code = c.getResponseCode();
                Log.i(TAG, code == 200 ? "telegram: sent" : "telegram: failed, HTTP " + code);
            } catch (Exception e) {
                Log.w(TAG, "telegram: failed, " + e);
            } finally {
                if (c != null) c.disconnect();
            }
        }, "telegram").start();
    }
}
