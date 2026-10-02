package com.autoclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

/**
 * Loud notifications for things only you can do, like the game's math question: sound,
 * vibration, heads-up, and (with the phone linked to the tablet's account) on your phone too.
 * Tapping it opens the game. The auto clicker never answers the question itself.
 */
final class Alerts {

    private static final String CHANNEL = "needs_you";
    private static final int QUESTION_ID = 1;

    private Alerts() {
    }

    static void question(Context context, String game, String text) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannel(nm);
        Intent open = game != null ? context.getPackageManager().getLaunchIntentForPackage(game) : null;
        PendingIntent tap = open == null ? null
                : PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Ran Online needs you")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setAutoCancel(true);
        if (tap != null) b.setContentIntent(tap);
        nm.notify(QUESTION_ID, b.build());
    }

    static void clearQuestion(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(QUESTION_ID);
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Needs you", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("The game is waiting for you, e.g. its \"please click Confirm\" check");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[] {0, 600, 300, 600, 300, 600});
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }
}
