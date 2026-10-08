package com.autoclicker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Sends a Telegram test message from the tablet without leaving the game. Only adb can send it
 * (the receiver needs the DUMP permission, which the shell has and apps don't):
 * adb shell am broadcast -a com.autoclicker.TEST_TELEGRAM -n com.autoclicker/.TelegramTest
 * With --es msg "..." it sends that text instead (the PC's crash watcher: "the game went black").
 */
public class TelegramTest extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        if (!Telegram.configured(app)) {
            Log.w("AutoClicker", "telegram: test skipped, no bot token or chat id set");
            return;
        }
        String msg = intent.getStringExtra("msg");
        if (msg != null && !msg.trim().isEmpty()) {
            Log.i("AutoClicker", "telegram: sending from adb: " + msg);
            Telegram.send(app, msg);
            return;
        }
        Log.i("AutoClicker", "telegram: sending a test");
        Telegram.send(app, "✅ Auto Clicker: test message from the tablet. Alerts will arrive like this.");
    }
}
