package com.autoclicker;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings the service back when Android kills it.
 *
 * When the tablet runs out of memory it kills this app, and Android then marks the accessibility
 * service "crashed" and never restarts it. The switch in Accessibility settings stays on, so
 * nothing looks wrong, but there is no bar and no tapping until the switch is turned off and on.
 * An alarm wakes this receiver every CHECK_EVERY_MS; if the service is switched on but not
 * connected, it turns it off and on by itself.
 *
 * Changing the switch needs WRITE_SECURE_SETTINGS, granted once over adb:
 *   adb shell pm grant com.autoclicker android.permission.WRITE_SECURE_SETTINGS
 */
public class Watchdog extends BroadcastReceiver {

    private static final String TAG = "AutoClicker";
    static final String ACTION_CHECK = "com.autoclicker.CHECK";
    static final String ACTION_REVIVE = "com.autoclicker.REVIVE";
    static final String EXTRA_REASON = "reason";
    // Set when you press Enable service: restart even if the watchdog restarted it moments ago.
    static final String EXTRA_NOW = "now";
    private static final int CHECK_EVERY_MS = 20_000;
    // Right after the switch goes on the service can take a moment to connect; look twice.
    private static final int RECHECK_MS = 2500;
    // Off long enough for Android to forget the "crashed" mark. Switching back on sooner leaves
    // the service half connected: the bar can't be drawn and every tap is refused.
    private static final int OFF_FOR_MS = 3000;
    // Time for the service to connect before the second round of a restart.
    private static final int CONNECT_MS = 2000;
    private static final int MIN_REVIVE_GAP_MS = 20_000;
    private static final String KEY_REVIVING_SINCE = "reviving_since";
    private static final String KEY_LAST_REVIVE = "last_revive";

    private static final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        schedule(app);
        String action = intent.getAction();
        boolean force = ACTION_REVIVE.equals(action);
        String reason = force ? intent.getStringExtra(EXTRA_REASON) : "not connected";

        SharedPreferences prefs = prefs(app);
        if (!isSwitchedOn(app)) {
            // Off: either you turned it off (leave it), or the app died halfway through a restart.
            long since = prefs.getLong(KEY_REVIVING_SINCE, 0);
            if (since > 0 && System.currentTimeMillis() - since < 60_000 && canRestart(app)) {
                Log.i(TAG, "watchdog: finishing an interrupted restart");
                setSwitch(app, true);
            }
            prefs.edit().remove(KEY_REVIVING_SINCE).apply();
            return;
        }
        if (!force && isConnected(app)) return;

        PendingResult pending = goAsync();
        boolean now = intent.getBooleanExtra(EXTRA_NOW, false);
        Runnable restart = () -> revive(app, reason, now, pending);
        if (force) {
            restart.run();
        } else {
            handler.postDelayed(() -> {
                if (isConnected(app)) {
                    pending.finish();
                } else {
                    restart.run();
                }
            }, RECHECK_MS);
        }
    }

    private static void revive(Context app, String reason, boolean ignoreGap, PendingResult pending) {
        SharedPreferences prefs = prefs(app);
        long now = System.currentTimeMillis();
        if (!canRestart(app)) {
            Log.w(TAG, "watchdog: service is " + reason + " but can't restart it without WRITE_SECURE_SETTINGS");
            pending.finish();
            return;
        }
        if (!ignoreGap && now - prefs.getLong(KEY_LAST_REVIVE, 0) < MIN_REVIVE_GAP_MS) {
            pending.finish();
            return;
        }
        prefs.edit().putLong(KEY_REVIVING_SINCE, now).putLong(KEY_LAST_REVIVE, now).commit();
        Log.i(TAG, "watchdog: service " + reason + ", turning it off and on");
        // The first off-and-on after a crash only half connects the service (seen every time on
        // the Xiaomi Pad 5: bar can't be drawn, taps refused); a second one right after fixes it.
        cycle(app, 2, () -> {
            prefs.edit().remove(KEY_REVIVING_SINCE).apply();
            Log.i(TAG, "watchdog: service switched back on");
            pending.finish();
        });
    }

    /** Switch off, wait, switch on; `times` rounds, letting the service connect in between. */
    private static void cycle(Context app, int times, Runnable done) {
        setSwitch(app, false);
        handler.postDelayed(() -> {
            setSwitch(app, true);
            if (times <= 1) {
                done.run();
            } else {
                handler.postDelayed(() -> cycle(app, times - 1, done), CONNECT_MS);
            }
        }, OFF_FOR_MS);
    }

    /** Arms the next check. Safe to call often: it replaces the pending alarm. */
    static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        Intent intent = new Intent(context, Watchdog.class).setAction(ACTION_CHECK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long at = SystemClock.elapsedRealtime() + CHECK_EVERY_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
    }

    /** Asks for an immediate off-and-on, e.g. when the service sees its taps being refused. */
    static void requestRevive(Context context, String reason) {
        requestRevive(context, reason, false);
    }

    static void requestRevive(Context context, String reason, boolean now) {
        context.sendBroadcast(new Intent(context, Watchdog.class)
                .setAction(ACTION_REVIVE).putExtra(EXTRA_REASON, reason).putExtra(EXTRA_NOW, now));
    }

    static boolean canRestart(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** The switch in Accessibility settings. */
    static boolean isSwitchedOn(Context context) {
        ComponentName me = new ComponentName(context, ClickService.class);
        for (String s : enabledList(context)) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    /** Whether Android has the service actually connected (a crashed one is switched on but not listed). */
    static boolean isConnected(Context context) {
        AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
        if (am == null) return false;
        ComponentName me = new ComponentName(context, ClickService.class);
        for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            // The id is the component in short form, e.g. "com.autoclicker/.ClickService".
            if (info.getId() != null && me.equals(ComponentName.unflattenFromString(info.getId()))) return true;
        }
        return false;
    }

    private static List<String> enabledList(Context context) {
        String value = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<String> list = new ArrayList<>();
        if (value == null) return list;
        for (String s : value.split(":")) if (!s.isEmpty()) list.add(s);
        return list;
    }

    private static void setSwitch(Context context, boolean on) {
        ComponentName me = new ComponentName(context, ClickService.class);
        List<String> list = new ArrayList<>();
        for (String s : enabledList(context)) {
            if (!me.equals(ComponentName.unflattenFromString(s))) list.add(s);
        }
        if (on) list.add(me.flattenToString());
        try {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", list));
            if (on) Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
        } catch (SecurityException e) {
            Log.w(TAG, "watchdog: not allowed to change the switch: " + e.getMessage());
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(ClickService.PREFS, Context.MODE_PRIVATE);
    }
}
