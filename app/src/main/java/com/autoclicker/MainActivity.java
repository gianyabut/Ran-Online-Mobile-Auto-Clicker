package com.autoclicker;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setTextSize(16);
        help.setText("1. Press \"Enable service\" and turn on Auto Clicker.\n"
                + "2. Open your game. Press + on the floating bar to add tap targets.\n"
                + "3. Drag each numbered circle to where you want taps.\n"
                + "4. Tap a circle to change its interval or delete it.\n"
                + "5. For skills: while the skill is ready, tap its circle and press "
                + "\"Remember ready look\". It then skips taps while the skill is dimmed (Android 11+).\n"
                + "   Turn on Priority for your heal so it goes before everything else.\n"
                + "   For buffs, set the interval to 1m or 2m, and turn on Wait for cooldown too.\n"
                + "6. Press ▶ to start, ■ to stop. Each circle taps at its own speed.\n\n"
                + "Targets are saved. To hide the overlay, turn the service off again.\n"
                + "If Android kills Auto Clicker to free memory, it restarts itself within about 20 s.");
        root.addView(help);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(12), 0, dp(4));
        root.addView(status);

        Button enable = new Button(this);
        enable.setText("Enable service");
        enable.setOnClickListener(v -> {
            // Android leaves a killed service switched on but dead; opening settings would just
            // show the switch already on. Restart it directly instead.
            if (Watchdog.isSwitchedOn(this) && !Watchdog.isConnected(this) && Watchdog.canRestart(this)) {
                Watchdog.requestRevive(this, "restarted from the app", true);
                Toast.makeText(this, "Restarting Auto Clicker, the bar comes back in a few seconds", Toast.LENGTH_LONG).show();
                status.postDelayed(this::showStatus, 5000);
            } else {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(enable);

        SharedPreferences prefs = getSharedPreferences(ClickService.PREFS, MODE_PRIVATE);

        TextView gapLabel = new TextView(this);
        gapLabel.setTextSize(14);
        gapLabel.setText("\nPause after each tap (milliseconds)\n"
                + "Your game ignores all skills for about 2.6 s after a heal, so 3000 avoids wasted taps. "
                + "Use 0 for plain fast clicking. Takes effect the next time you press ▶.");
        root.addView(gapLabel);

        EditText gap = new EditText(this);
        gap.setInputType(InputType.TYPE_CLASS_NUMBER);
        gap.setText(String.valueOf(prefs.getInt(ClickService.KEY_TAP_GAP, ClickService.DEFAULT_TAP_GAP_MS)));
        root.addView(gap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button save = new Button(this);
        save.setText("Save pause");
        save.setOnClickListener(v -> {
            int ms;
            try {
                ms = Math.max(0, Math.min(60_000, Integer.parseInt(gap.getText().toString())));
            } catch (NumberFormatException e) {
                ms = ClickService.DEFAULT_TAP_GAP_MS;
            }
            gap.setText(String.valueOf(ms));
            prefs.edit().putInt(ClickService.KEY_TAP_GAP, ms).apply();
            Toast.makeText(this, "Saved: " + ms + " ms", Toast.LENGTH_SHORT).show();
        });
        root.addView(save);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        Watchdog.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showStatus();
    }

    private void showStatus() {
        String text;
        if (!Watchdog.isSwitchedOn(this)) {
            text = "Service: off. Press Enable service and turn on Auto Clicker.";
        } else if (Watchdog.isConnected(this)) {
            text = "Service: running.";
        } else {
            text = "Service: stopped by Android. Press Enable service to restart it.";
        }
        if (!Watchdog.canRestart(this)) {
            text += "\nAutomatic restart is off (needs a one-time permission from a computer).";
        }
        status.setText(text);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
