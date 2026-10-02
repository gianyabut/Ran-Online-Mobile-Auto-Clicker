package com.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Floating bar (start/stop, add target) plus any number of draggable targets.
 * Each target taps on its own timer, one tap at a time. A target can optionally
 * wait until the game button under it no longer shows its dimmed cooldown overlay.
 */
public class ClickService extends AccessibilityService {

    private static final String TAG = "AutoClicker";
    static final String PREFS = "settings";
    private static final String KEY_TARGETS = "targets";
    private static final int DEFAULT_INTERVAL = 500;
    private static final int MIN_INTERVAL = 50;
    private static final int MAX_INTERVAL = 600_000;
    private static final int TAP_MS = 40;
    // Pause after every tap before the next one. Games often ignore every skill for a moment
    // after any cast (Ran Pinas: about 2.6 s after Heaven's Treatment), so taps sent sooner are wasted.
    static final String KEY_TAP_GAP = "tap_gap_ms";
    static final int DEFAULT_TAP_GAP_MS = 3000;

    // Smart buff: recast when the buff's icon is gone or its timer bar is at or below this share.
    // 40%: each buff cast costs about one heal, and even the shortest buff (Confusion Strike, ~2 min)
    // still has ~48 s left at 40%.
    private static final float SMART_RECAST_AT = 0.4f;
    // Settings saved before 40% became the default had 50% and an extra wait inflated by misread
    // ignored taps; bring them over once.
    private static final String KEY_TUNED_40 = "tuned_40";
    // After a smart-buff tap, give the game time to refill the bar before trying again.
    // The refreshed bar shows up 2-7 s after the cast (Inspire is slowest); buffs recast at 70% have
    // plenty of time left, so wait long enough never to cast the same buff twice.
    private static final int SMART_RETRY_MS = 6000;
    private static final int SMART_RECHECK_MS = 300;
    // How long after a learning cast to look at the buff row again (the bar refreshes ~2.6 s after).
    private static final int LEARN_AFTER_MS = 3000;
    // A just-cast buff whose icon scores at least this far from its saved look (the HUD was redrawn
    // at another size) has its look saved again, so it doesn't drift out of reach.
    private static final int RELEARN_DIFF = 10;
    private int tapGapMs = DEFAULT_TAP_GAP_MS;

    // Whether you left it running, and for which app, so it can resume after Android kills it.
    private static final String KEY_RUNNING = "running";
    private static final String KEY_GAME = "game_package";
    // Until ▶ has been pressed in a game, the bar shows for Ran Online.
    private static final String DEFAULT_GAME = "com.ranpinas.client";
    private boolean overlaysHidden;
    private static final int VISIBILITY_RECHECK_MS = 1000;
    private String gamePackage;
    private String lastForeground;
    private boolean pausedForOtherApp;
    private boolean pausedForKeyboard;
    private boolean pausedForPortrait;

    // Manual mode (✋): no tapping and no rings in the way, so you play the game yourself.
    // Remembered so a restart after Android kills the service doesn't bring the rings back.
    private static final String KEY_MANUAL = "manual";

    // Full support modes (EG/LL button on the bar):
    // - End Game FS: party farming in waves. Buffs only go out in full buffs (FB, or automatically
    //   once a lured wave is cleared); every other slot is a heal.
    // - Low Level FS: each buff is recast on its own when it drops to its recast % (40%).
    private static final String KEY_END_GAME = "end_game";
    private boolean endGame = true;
    private boolean manual;
    private int refusedInARow;
    // The connected service, for the watchdog's health check (it runs in this same process).
    private static volatile ClickService instance;

    // Cooldown check. Android allows roughly one accessibility screenshot per 333 ms.
    private static final int SCREENSHOT_EVERY_MS = 350;
    private static final int BUFF_SCAN_EVERY_MS = 2000;
    // Waves (see updateWave). How big a lure gets depends on the party: 8 members bring 6-13 (on
    // screen; the count runs low in a crowd, ~12 reads 9-10), 3 members bring 2-4 and leave 0-1.
    // Big party: 6+ is a wave, fewer than 6 (nearly) cleared, the user's call. Small party: 3+ is
    // a wave, 2 or fewer for 3 scans cleared ("FB once it's 3 or less", the count reading ~1 low).
    // "1 or fewer" kept healing for 38 s after a wave: one leftover name flickered 1-2.
    private static final int BIG_PARTY = 5;
    private static final int WAVE_START_MOBS = 6;
    private static final int WAVE_END_MOBS = 5;
    private static final int SMALL_WAVE_START_MOBS = 3;
    private static final int SMALL_WAVE_END_MOBS = 2;
    private int partySize = -1; // from the team list; -1 until seen
    // Scans in a row below the end count before a wave counts as cleared (a dip mid-fight in a big
    // party lasted 3 scans; small parties want the buff sooner and a returning wave stops it anyway).
    private static final int WAVE_END_SCANS = 4;
    private static final int SMALL_WAVE_END_SCANS = 3;
    private static final int MIN_WAVE_MS = 15_000;
    private int lastMobCount = -1;
    private boolean inWave;
    // A full buff was stopped because the wave came back; the next clear buffs regardless.
    private boolean fullBuffOwed;
    // Readings at the wave level in a row while a full buff is going out.
    private int backScans;
    // ▶ in End Game: full buff on the first monster count if fewer than this are on screen,
    // otherwise heal first and buff once they're down (the user's call).
    private boolean startBuffPending;
    private static final int START_BUFF_MAX_MOBS = 5;
    // A player or monster is selected (bar at the top centre), as of the latest screenshot.
    private boolean targetSelected;
    // After tapping its ✕, give the game a moment before the buff goes out.
    private static final int DESELECT_SETTLE_MS = 300;
    private long waveStartedAt;
    private int clearScans;
    private long lastMobCountAt;
    private static final int COOLDOWN_RECHECK_MS = 150;
    // Sample grid inside the ring: rows top to bottom, because the game's dimming clears top-down.
    private static final int SAMPLE_ROWS = 7;
    private static final int SAMPLE_COLS = 3;
    private static final float SAMPLE_SPREAD = 0.55f;
    // A point counts as dimmed when it is below this share of its ready brightness.
    private static final float DIM_RATIO = 0.75f;
    // Points darker than this when ready can't show dimming, so they are ignored.
    private static final int MIN_USEFUL_LUMA = 45;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Target> targets = new ArrayList<>();
    // Targets waiting for their turn to tap. Each target is in here at most once.
    private final List<Target> pending = new ArrayList<>();
    private final Runnable pump = this::pumpQueue;
    private WindowManager wm;
    private View touchWatcher;
    // Your last touch on the game: no taps until USER_TOUCH_PAUSE_MS after it. A skill you cast by
    // hand locks the others for up to ~4 s, so a tap any sooner would just be ignored.
    private long userTouchAt;
    private static final int USER_TOUCH_PAUSE_MS = 4000;
    // Touches before this are our own tap arriving.
    private long ownTapUntil;
    private static final int OWN_TAP_SLACK_MS = 250;
    private LinearLayout bar;
    private WindowManager.LayoutParams barParams;
    private TextView toggle;
    private TextView add;
    private TextView fullBuffButton;
    private TextView manualButton;
    private TextView modeButton;
    private View editor;
    private boolean running;
    private long busyUntil;
    private long lastAnyTapAt;
    private long lastPriorityTapAt;
    // Last buff tap, so the next buff waits BUFF_SPACING_MS and the three don't bunch up.
    private long lastBuffTapAt;
    private Target lastBuffRing;
    // One heal cycle between buffs: with 4+ buffs (one lasting ~1 min) 20 s let them drain below 50%.
    private static final int BUFF_SPACING_MS = 6000;
    // After a buff reads full, ignore "low" readings this long (fastest buff needs ~30 s to 50%).
    private static final int JUST_FULL_MS = 15_000;
    // Below this a buff skips the spacing wait so it never runs out.
    private static final float SMART_URGENT_AT = 0.4f;
    private static final int MAX_EXTRA_GAP_MS = 3000;
    // Each buff that takes on the first tap lowers its extra wait by this much, down to the minimum,
    // so a few ignored taps (or misreads) don't cost heal time forever.
    private static final int MIN_EXTRA_GAP_MS = 1000;
    private static final int EXTRA_GAP_DECAY_MS = 250;
    // Full buff (FB button).
    private long lastFullBuffAt;
    private static final int FULL_BUFF_COOLDOWN_MS = 15_000;
    private static final int FORCED_AFTER_HEAL_MS = 4000;
    // The refreshed bar shows up 2-7 s after a cast.
    private static final int FORCED_CHECK_MS = 7000;
    // After a buff the heal waits 3 s: 2.5 s worked out of a fight but got the heal ignored in one.
    private static final int AFTER_BUFF_GAP_MS = 3000;
    // A buff may start up to this long after its slot opens (the heal's lock ending).
    private static final int SLOT_WINDOW_MS = 1000;
    // Fallback if the heal ring isn't tapping at all.
    private static final int MAX_SLOT_WAIT_MS = 15_000;
    // The buff row as of the latest screenshot, for learning which icon a ring's buff is.
    private List<BuffReader.Icon> lastScan;
    private long lastRowSeenAt;
    private int screenW;
    private int screenH;
    private static final int ROW_GONE_BELIEVE_MS = 8000;
    private long lastScanAt;

    private class Target {
        final LinearLayout root;
        final View ring;
        final TextView badge;
        final WindowManager.LayoutParams params;
        int interval;
        boolean priority;
        boolean waitForCooldown;
        int[] readyLook; // brightness per sample point when the skill is ready, or null
        int lookX, lookY; // where the ring was when readyLook was saved
        boolean ready = true;

        // Smart buff: the buff's icon in the game's buff row, and what the last screenshot saw.
        boolean smartBuff;
        int[] buffIcon; // colour signature from BuffReader, or null until picked
        int buffY;
        int buffSize;
        boolean buffKnown;
        boolean buffFound;
        float buffFill;
        long lastTapAt;
        // While learning: the buff row just before this ring's last cast, and when to look again.
        List<BuffReader.Icon> learnBefore;
        long learnCheckAt;
        int neededStreak;
        // Extra wait after the previous skill before this buff taps, learned from ignored taps.
        // Starts at 1 s (4 s after the heal): in a fight, 3-3.5 s after the heal usually gets ignored.
        int extraGapMs = MIN_EXTRA_GAP_MS;
        // Whether a tap since the buff last read full was a retry after an ignored one.
        boolean retried;
        // Queued by the FB button: cast even if the buff is still up, without waiting its turn.
        boolean forced;
        int forcedRetries;
        // Goes after every other buff that's due (and last in a full buff).
        boolean castLast;
        // Dropped from a full buff that a returning wave stopped; cast when it resumes.
        boolean owed;
        long queuedAt;
        int recastsWithoutOk;
        // Recast when the buff's timer is at or below this share (70% default, per buff).
        float recastAt = SMART_RECAST_AT;
        long backoffUntil;
        long lastFullAt;

        boolean isSmart() {
            return smartBuff && buffIcon != null;
        }

        /** The game shows this ring's button dimmed: a tap now would just be ignored. */
        boolean onCooldown() {
            return waitForCooldown && readyLook != null && !ready;
        }

        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                if (isSmart()) {
                    boolean needed = buffNeeded(Target.this);
                    long now = SystemClock.uptimeMillis();
                    long sinceTap = now - lastTapAt;
                    // Keep the buffs spread out: right after another buff, wait before this one,
                    // unless this buff is missing or running low.
                    boolean urgent = !buffFound || buffFill <= Math.min(SMART_URGENT_AT, recastAt - 0.1f);
                    boolean tooSoonAfterOtherBuff = lastBuffRing != null && lastBuffRing != Target.this
                            && now - lastBuffTapAt < BUFF_SPACING_MS;
                    if (needed && !urgent && tooSoonAfterOtherBuff) {
                        handler.postDelayed(this, SMART_RECHECK_MS);
                        return;
                    }
                    if (needed && now < backoffUntil) {
                        handler.postDelayed(this, SMART_RECHECK_MS);
                        return;
                    }
                    // A long-cooldown buff (Massive Haste) can come due before it's castable again.
                    if (needed && onCooldown()) {
                        handler.postDelayed(this, SMART_RECHECK_MS);
                        return;
                    }
                    if (needed && sinceTap >= SMART_RETRY_MS) {
                        // Only when the buff can't be seen at all: a visible buff whose taps get
                        // ignored in a fight must keep retrying, or it runs out.
                        if (!buffFound && ++recastsWithoutOk > 3) {
                            // Cast 3 times and still not seen: probably a misread, not a missing
                            // buff. Stop wasting casts for a minute.
                            recastsWithoutOk = 0;
                            backoffUntil = now + 60_000;
                            Log.w(TAG, "buff target " + (targets.indexOf(Target.this) + 1)
                                    + ": recast 3 times but still can't see it, pausing it for 60 s");
                            handler.postDelayed(this, SMART_RECHECK_MS);
                            return;
                        }
                        // A skill you cast by hand locks the others too; that's no reason to wait longer.
                        boolean youCastBefore = lastTapAt - userTouchAt < USER_TOUCH_PAUSE_MS + 2000;
                        if (lastTapAt > 0 && sinceTap < SMART_RETRY_MS + 5000 && extraGapMs < MAX_EXTRA_GAP_MS
                                && !youCastBefore) {
                            // Still low right after our tap: the game ignored it, usually because the
                            // previous skill's lock lasts longer in a fight. Wait a bit longer next time.
                            extraGapMs += 500;
                            retried = true;
                            Log.i(TAG, "buff target " + (targets.indexOf(Target.this) + 1) + ": tap was ignored, now waiting "
                                    + (tapGapMs + extraGapMs) + "ms after the previous skill");
                            saveTargets();
                        }
                        queueTap(Target.this);
                    } else {
                        handler.postDelayed(this, SMART_RECHECK_MS);
                    }
                    return;
                }
                if (waitForCooldown && readyLook != null && !ready) {
                    handler.postDelayed(this, COOLDOWN_RECHECK_MS);
                    return;
                }
                // The next tick is scheduled when this tap actually goes out (see pumpQueue),
                // so a tap that waited in line doesn't make the following one come too early.
                queueTap(Target.this);
            }
        };

        Target(int x, int y, int interval) {
            this.interval = interval;

            root = new LinearLayout(ClickService.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);

            // Hollow ring so the game button underneath stays visible and readable.
            ring = new View(ClickService.this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(Color.TRANSPARENT);
            d.setStroke(dp(3), Color.rgb(230, 30, 30));
            ring.setBackground(d);
            root.addView(ring, new LinearLayout.LayoutParams(dp(56), dp(56)));

            badge = new TextView(ClickService.this);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(11);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setPadding(dp(6), dp(1), dp(6), dp(1));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(Color.argb(200, 180, 20, 20));
            pill.setCornerRadius(dp(8));
            badge.setBackground(pill);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeLp.topMargin = dp(2);
            root.addView(badge, badgeLp);

            params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            params.x = x;
            params.y = y;
            // The ring is centred in a window as wide as its label, so a label that grows or
            // shrinks would slide the ring sideways. Shift the window to keep the ring still.
            root.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                int oldWidth = oldR - oldL;
                int newWidth = r - l;
                if (oldWidth > 0 && newWidth != oldWidth) {
                    params.x += (oldWidth - newWidth) / 2;
                    safeUpdate(root, params);
                    saveTargets();
                }
            });
            makeDraggable(root, root, params, () -> openEditor(this), () -> {
                // The saved ready look belongs to the old spot, but a nudge while tapping the
                // ring to open its settings still sees the same button: only a real move clears it.
                if (Math.hypot(params.x - lookX, params.y - lookY) > ring.getWidth() / 3f) {
                    readyLook = null;
                    refreshLabel();
                }
            });
        }

        void refreshLabel() {
            String text = (priority ? "★" : "") + (targets.indexOf(this) + 1) + " · ";
            if (smartBuff) {
                text += buffIcon != null ? "smart buff" : formatInterval(interval) + " · learning";
                if (Math.round(recastAt * 100) != Math.round(SMART_RECAST_AT * 100)) text += " " + Math.round(recastAt * 100) + "%";
                if (waitForCooldown) text += readyLook != null ? " · CD" : " · CD?";
            } else {
                text += formatInterval(interval);
                if (waitForCooldown) text += readyLook != null ? " · CD" : " · CD?";
            }
            if (castLast) text += " · last";
            badge.setText(text);
        }

        void setTouchable(boolean touchable) {
            if (touchable) {
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            root.setAlpha(touchable ? 1f : 0.5f);
            safeUpdate(root, params);
        }

        /** Screen coordinates of the sample points, top row first. */
        int[][] samplePoints() {
            int[] loc = new int[2];
            ring.getLocationOnScreen(loc);
            float cx = loc[0] + ring.getWidth() / 2f;
            float cy = loc[1] + ring.getHeight() / 2f;
            float r = ring.getWidth() / 2f * SAMPLE_SPREAD;
            int[][] pts = new int[SAMPLE_ROWS * SAMPLE_COLS][];
            for (int row = 0; row < SAMPLE_ROWS; row++) {
                float y = cy - r + 2 * r * row / (SAMPLE_ROWS - 1);
                for (int col = 0; col < SAMPLE_COLS; col++) {
                    float x = cx - r / 2 + r * col / (SAMPLE_COLS - 1);
                    pts[row * SAMPLE_COLS + col] = new int[] {Math.round(x), Math.round(y)};
                }
            }
            return pts;
        }
    }

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Android can disconnect and reconnect this same service without destroying it.
        // Clear everything from the previous connection so bars and rings aren't duplicated.
        removeOverlays("connect");
        loadTargets();
        refusedInARow = 0;
        instance = this;
        Watchdog.schedule(this);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        toggle = roundButton("");
        add = roundButton("+");
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(48), dp(48));
        gap.topMargin = dp(8);
        bar.addView(toggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(add, gap);
        fullBuffButton = roundButton("FB");
        fullBuffButton.setTextSize(15);
        fullBuffButton.setTypeface(Typeface.DEFAULT_BOLD);
        fullBuffButton.setBackground(circle(Color.rgb(210, 120, 20)));
        LinearLayout.LayoutParams fbGap = new LinearLayout.LayoutParams(dp(48), dp(48));
        fbGap.topMargin = dp(8);
        bar.addView(fullBuffButton, fbGap);
        modeButton = roundButton("");
        modeButton.setTextSize(15);
        modeButton.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams modeGap = new LinearLayout.LayoutParams(dp(48), dp(48));
        modeGap.topMargin = dp(8);
        bar.addView(modeButton, modeGap);
        manualButton = roundButton("");
        LinearLayout.LayoutParams manualGap = new LinearLayout.LayoutParams(dp(48), dp(48));
        manualGap.topMargin = dp(8);
        bar.addView(manualButton, manualGap);
        barParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        barParams.x = dp(8);
        barParams.y = dp(200);
        makeDraggable(toggle, bar, barParams, () -> setRunning(!running, "button"), null);
        makeDraggable(add, bar, barParams, this::addTargetFromBar, null);
        makeDraggable(fullBuffButton, bar, barParams, this::fullBuff, null);
        makeDraggable(manualButton, bar, barParams, () -> setManual(!manual, "button"), null);
        makeDraggable(modeButton, bar, barParams, () -> setEndGame(!endGame, "button"), null);
        if (!safeAdd(bar, barParams)) {
            // Half connected (switched back on too soon after a crash): nothing will work until
            // the service is turned off and on again.
            Watchdog.requestRevive(this, "can't show its bar");
        }

        addTouchWatcher();
        setRunning(false, "connected");

        // Android kills background apps when the game uses most of the memory, then restarts
        // this service. If you had pressed ▶, carry on where it left off.
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setEndGame(prefs.getBoolean(KEY_END_GAME, true), "restored");
        if (prefs.getBoolean(KEY_MANUAL, false)) {
            setManual(true, "restored");
        } else {
            setManual(false, "connected");
        }
        if (!manual && prefs.getBoolean(KEY_RUNNING, false)) {
            gamePackage = prefs.getString(KEY_GAME, null);
            setRunning(true, "resumed after restart");
        }
        updateOverlayVisibility();
    }

    /**
     * A 1 px window that Android tells about every touch elsewhere on the screen (not where).
     * The game breaks on two touches at once: your finger on the heal button plus our tap left
     * it thinking heal was held down, ignoring every tap after. So while you touch, we hold off.
     */
    private void addTouchWatcher() {
        touchWatcher = new View(this);
        WindowManager.LayoutParams p = overlayParams(1, 1);
        p.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        touchWatcher.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() != MotionEvent.ACTION_OUTSIDE) return false;
            long now = SystemClock.uptimeMillis();
            // Our own taps reach the game as touches too.
            if (now < ownTapUntil) return false;
            userTouched(now);
            return false;
        });
        if (!safeAdd(touchWatcher, p)) touchWatcher = null;
    }

    private void userTouched(long now) {
        if (running && now - userTouchAt > USER_TOUCH_PAUSE_MS) Log.i(TAG, "you're touching the screen, holding taps");
        userTouchAt = now;
    }

    /** Stops tapping and removes every overlay window this service has open. */
    private void removeOverlays(String why) {
        Log.i(TAG, "overlays removed (" + why + ")" + (running ? ", was running" : ""));
        running = false;
        handler.removeCallbacksAndMessages(null);
        pending.clear();
        busyUntil = 0;
        closeEditor();
        for (Target t : targets) safeRemove(t.root);
        targets.clear();
        if (bar != null) safeRemove(bar);
        bar = null;
        overlaysHidden = false;
        if (touchWatcher != null) safeRemove(touchWatcher);
        touchWatcher = null;
    }

    /** Adds an overlay window; false while the service is between connections and Android refuses it. */
    private boolean safeAdd(View view, WindowManager.LayoutParams params) {
        try {
            wm.addView(view, params);
            return true;
        } catch (WindowManager.BadTokenException e) {
            Log.w(TAG, "overlay refused: " + e.getMessage());
            return false;
        }
    }

    private void safeRemove(View view) {
        try {
            wm.removeView(view);
        } catch (IllegalArgumentException ignored) {
            // already gone
        }
    }

    private void safeUpdate(View view, WindowManager.LayoutParams params) {
        try {
            wm.updateViewLayout(view, params);
        } catch (IllegalArgumentException ignored) {
            // not attached any more
        }
    }

    private void setRunning(boolean run, String why) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (run) {
            tapGapMs = Math.max(0, prefs.getInt(KEY_TAP_GAP, DEFAULT_TAP_GAP_MS));
        }
        if (why.equals("button")) {
            // Remember that you want it running, and which app it's for, so it can resume
            // after Android kills it and only ever taps that app.
            if (run) {
                // Keep the game we already know if Android can't tell us right now.
                String front = foregroundPackage();
                if (front != null) gamePackage = front;
                Log.i(TAG, "tapping only while " + gamePackage + " is in front");
            }
            prefs.edit().putBoolean(KEY_RUNNING, run).putString(KEY_GAME, gamePackage).apply();
        }
        pausedForOtherApp = false;
        Log.i(TAG, (run ? "start" : "stop") + " (" + why + "), " + targets.size() + " targets"
                + (run ? ", pause after tap " + tapGapMs + "ms" : ""));
        running = run;
        handler.removeCallbacksAndMessages(null);
        pending.clear();
        busyUntil = 0;
        lastAnyTapAt = 0;
        lastPriorityTapAt = 0;
        lastBuffTapAt = 0;
        lastBuffRing = null;
        // Right after ▶ the first screenshots can miss the buff row; give it the same grace period.
        lastRowSeenAt = SystemClock.uptimeMillis();
        // A wave in progress isn't carried over a stop: the screen may look nothing alike now.
        inWave = false;
        clearScans = 0;
        fullBuffOwed = false;
        backScans = 0;
        for (Target t : targets) t.owed = false;
        if (run) closeEditor();

        toggle.setText(run ? "■" : "▶");
        toggle.setBackground(circle(run ? Color.rgb(200, 40, 40) : Color.rgb(40, 150, 60)));
        add.setAlpha(run ? 0.4f : 1f);

        lastScan = null;
        // While running, taps must pass through the targets to reach the game underneath.
        for (Target t : targets) {
            t.ready = true;
            t.buffKnown = false;
            t.learnBefore = null;
            t.lastTapAt = 0;
            t.neededStreak = 0;
            t.forced = false;
            t.setTouchable(!run);
            if (run) handler.postDelayed(t.tick, 300);
        }
        if (run) handler.post(this::cooldownCheck);
        // Clearing the handler above also dropped the "game back yet?" check.
        updateOverlayVisibility();
        // End Game starts the cycle like the support does by hand: buff the party first, but only
        // if no fight is on (decided on the first monster count, see updateWave). Skipped when
        // this start came from a full buff (FB or the EG switch while stopped).
        startBuffPending = run && endGame && why.equals("button")
                && SystemClock.uptimeMillis() - lastFullBuffAt >= FULL_BUFF_COOLDOWN_MS;
    }

    /**
     * ✋ Manual mode: stops tapping, hides the rings (they'd block your own taps on the skills)
     * and shrinks the bar to one button. Tapping that button again shows the rings and starts
     * auto clicking straight away.
     */
    private void setManual(boolean on, String why) {
        boolean changed = manual != on;
        manual = on;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_MANUAL, on).apply();
        if (changed || on) Log.i(TAG, "manual mode " + (on ? "on" : "off") + " (" + why + ")");
        if (on) {
            closeEditor();
            if (running) setRunning(false, "button");
        }
        for (Target t : targets) {
            // Untouchable as well as hidden, so no invisible window swallows a tap meant for the game.
            t.setTouchable(!on);
            t.root.setVisibility(on || overlaysHidden ? View.GONE : View.VISIBLE);
        }
        int others = on ? View.GONE : View.VISIBLE;
        toggle.setVisibility(others);
        add.setVisibility(others);
        fullBuffButton.setVisibility(others);
        modeButton.setVisibility(others);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) manualButton.getLayoutParams();
        lp.topMargin = on ? 0 : dp(8);
        manualButton.setLayoutParams(lp);
        manualButton.setText(on ? "AUTO" : "✋");
        manualButton.setTextSize(on ? 12 : 20);
        manualButton.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        manualButton.setBackground(circle(on ? Color.rgb(40, 150, 60) : Color.rgb(120, 70, 170)));
        if (changed && why.equals("button")) {
            shake(manualButton);
            if (!on) setRunning(true, "button");
        }
    }

    /**
     * FB: cast every buff ring back to back, as fast as the game allows, even if the buffs are
     * still up (someone asked for a full buff). The heal keeps priority in between. Covers all
     * rings with Smart buff on, so new buffs are included once they are set up that way.
     */
    private void fullBuff() {
        List<Target> buffs = new ArrayList<>();
        for (Target t : targets) if (!t.priority && t.smartBuff) buffs.add(t);
        if (buffs.isEmpty()) {
            for (Target t : targets) if (!t.priority) buffs.add(t);
        }
        if (buffs.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastFullBuffAt < FULL_BUFF_COOLDOWN_MS) {
            Log.i(TAG, "full buff already in progress");
            shake(fullBuffButton);
            return;
        }
        lastFullBuffAt = now;
        // A full buff stopped by a returning wave resumes with the buffs it hadn't cast yet:
        // the ones that went out a moment ago don't need casting again.
        if (fullBuffOwed) {
            List<Target> rest = new ArrayList<>();
            for (Target t : buffs) if (t.owed) rest.add(t);
            if (!rest.isEmpty()) buffs = rest;
        }
        fullBuffOwed = false;
        for (Target t : targets) t.owed = false;
        // Start first: starting clears all timers, including the one that resets the button.
        if (!running) setRunning(true, "button");
        showFullBuffActive();
        for (int i = buffs.size() - 1; i >= 0; i--) {
            Target t = buffs.get(i);
            if (t.onCooldown()) {
                Log.i(TAG, "full buff: skipping target " + (targets.indexOf(t) + 1) + ", still on cooldown");
                buffs.remove(i);
            }
        }
        Log.i(TAG, "full buff: casting " + buffs.size() + " buffs back to back");
        for (Target t : buffs) {
            t.forced = true;
            t.forcedRetries = 0;
            queueTap(t);
        }
    }

    /** Press feedback: a quick squeeze and a tick, then green "…" until the full buff is done. */
    private void showFullBuffActive() {
        View b = fullBuffButton;
        b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        b.animate().scaleX(0.75f).scaleY(0.75f).setDuration(80)
                .withEndAction(() -> b.animate().scaleX(1f).scaleY(1f).setDuration(160).start())
                .start();
        fullBuffButton.setText("…");
        fullBuffButton.setBackground(circle(Color.rgb(40, 170, 70)));
        handler.removeCallbacks(resetFullBuffButton);
        handler.postDelayed(resetFullBuffButton, FULL_BUFF_COOLDOWN_MS);
    }

    private final Runnable resetFullBuffButton = () -> {
        if (fullBuffButton == null) return;
        fullBuffButton.setText("FB");
        fullBuffButton.setBackground(circle(Color.rgb(210, 120, 20)));
    };

    /** "Already busy": a short side-to-side shake. */
    private void shake(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        v.animate().translationX(dp(6)).setDuration(50).withEndAction(() ->
                v.animate().translationX(-dp(6)).setDuration(70).withEndAction(() ->
                        v.animate().translationX(0).setDuration(50).start()).start()).start();
    }

    /**
     * A few seconds after a full-buff tap: if the buff's bar didn't refresh, the game ignored the
     * tap (usually the skill lock after the heal), so try that buff again, waiting a bit longer.
     */
    private void checkForcedBuff(Target t) {
        // End Game: a full buff casts every buff once, in order, through the last one. Judging by
        // our own buff row made it retry (e.g. buffs that went to a selected player) and the
        // cast-last buff (Massive Haste) never got its turn.
        if (!running || !t.isSmart() || endGame) return;
        // Read full at some point since the tap. Not "is it still full": a short buff (Massive
        // Haste) has drained below 90% by the time this runs, and was cast again for nothing.
        boolean took = t.lastFullAt >= t.lastTapAt;
        if (took || t.forcedRetries >= 2) return;
        if (t.onCooldown()) {
            // Ignored because it's still cooling down, not because of the lock: retrying can't help.
            Log.i(TAG, "full buff: target " + (targets.indexOf(t) + 1) + " didn't take, still on cooldown");
            return;
        }
        t.forcedRetries++;
        t.extraGapMs = Math.min(MAX_EXTRA_GAP_MS, t.extraGapMs + 500);
        t.retried = true;
        Log.i(TAG, "full buff: target " + (targets.indexOf(t) + 1) + " didn't take, retrying");
        t.forced = true;
        queueTap(t);
    }

    private void addTargetFromBar() {
        if (running) return;
        DisplayMetrics m = getResources().getDisplayMetrics();
        int offset = (targets.size() % 5) * dp(30);
        addTarget(m.widthPixels / 2 - dp(30) + offset, m.heightPixels / 2 - dp(30) + offset, DEFAULT_INTERVAL);
        saveTargets();
    }

    private Target addTarget(int x, int y, int interval) {
        Target t = new Target(x, y, interval);
        if (!safeAdd(t.root, t.params)) return null;
        targets.add(t);
        t.refreshLabel();
        return t;
    }

    private void removeTarget(Target t) {
        safeRemove(t.root);
        targets.remove(t);
        pending.remove(t);
        for (Target other : targets) other.refreshLabel();
        saveTargets();
    }

    /**
     * Android cancels an in-progress gesture when a new one is dispatched, and the game
     * doesn't accept simultaneous taps anyway, so taps are lined up one after another.
     * Priority targets jump to the front of the line.
     */
    private void queueTap(Target t) {
        if (!pending.contains(t)) {
            pending.add(t);
            t.queuedAt = SystemClock.uptimeMillis();
        }
        pumpQueue();
    }

    private void pumpQueue() {
        if (!running || pending.isEmpty()) return;
        // While the game isn't in front (or the keyboard is up) hold everything as it is,
        // so no tap is sent, counted, or mistaken for one the game ignored.
        if (!gameInFront()) {
            schedulePump(500);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now < busyUntil) {
            schedulePump(busyUntil - now);
            return;
        }
        if (now - userTouchAt < USER_TOUCH_PAUSE_MS) {
            schedulePump(userTouchAt + USER_TOUCH_PAUSE_MS - now);
            return;
        }
        // Buffs that came due together must still go ~20 s apart: send back to waiting any buff
        // that isn't urgent while another buff was tapped too recently. Its tick re-queues it.
        for (int i = pending.size() - 1; i >= 0; i--) {
            Target t = pending.get(i);
            if (t.isSmart() && !t.forced && !buffUrgent(t) && lastBuffRing != null && lastBuffRing != t
                    && now - lastBuffTapAt < BUFF_SPACING_MS) {
                pending.remove(i);
                handler.removeCallbacks(t.tick);
                handler.postDelayed(t.tick, SMART_RECHECK_MS);
            }
        }
        if (pending.isEmpty()) return;
        Target next = pickNext(now);
        // A buff that waited in line may have been refreshed meanwhile; don't waste the slot.
        while (next.isSmart() && !next.forced && !buffNeeded(next)) {
            pending.remove(next);
            handler.removeCallbacks(next.tick);
            handler.postDelayed(next.tick, SMART_RECHECK_MS);
            if (pending.isEmpty()) return;
            next = pickNext(now);
        }
        if (waitsForOthers(next)) {
            // Safety net: pickNext found nothing else, yet another buff is still in line.
            schedulePump(SMART_RECHECK_MS);
            return;
        }
        if (!next.priority) {
            long waitMs = buffWait(next, now);
            if (waitMs > 0) {
                schedulePump(waitMs);
                return;
            }
        }
        if (next.smartBuff && targetSelected && gameInFront()) {
            // With a player selected, the game casts buffs on that player instead of us and the
            // party. Close the selection (the ✕ by its name) first; the buff goes next.
            targetSelected = false;
            Log.i(TAG, "a target is selected, so buffs would go to it: deselecting first");
            busyUntil = now + TAP_MS + DESELECT_SETTLE_MS;
            tapAt(screenW * MobCounter.CLOSE_X, screenH * MobCounter.CLOSE_Y, "deselect");
            schedulePump(TAP_MS + DESELECT_SETTLE_MS);
            return;
        }
        pending.remove(next);
        // The game locks all skills for a while after a cast: ~2.6-4 s after the heal (longer in a
        // fight), but only ~2.5 s after a buff. Wait just that long so the heal isn't held up.
        busyUntil = now + TAP_MS + (next.priority ? tapGapMs : AFTER_BUFF_GAP_MS);
        lastAnyTapAt = now;
        if (next.priority) lastPriorityTapAt = now;
        if (next.isSmart()) {
            lastBuffTapAt = now;
            lastBuffRing = next;
        }
        tap(next);
        if (next.forced) {
            next.forced = false;
            Target forcedTarget = next;
            handler.postDelayed(() -> checkForcedBuff(forcedTarget), FORCED_CHECK_MS);
        }
        // Assume the skill went on cooldown until the next screenshot says otherwise,
        // and count this ring's interval from the moment it really tapped.
        next.ready = false;
        next.lastTapAt = now;
        if (next.smartBuff && next.buffIcon == null && lastScan != null && now - lastScanAt < 2500) {
            // Learning: compare the buff row from just before this cast with one a moment after.
            next.learnBefore = lastScan;
            next.learnCheckAt = now + LEARN_AFTER_MS;
        }
        handler.removeCallbacks(next.tick);
        handler.postDelayed(next.tick, next.isSmart() ? SMART_RECHECK_MS : next.interval);
        if (!pending.isEmpty()) schedulePump(TAP_MS + tapGapMs);
    }

    /**
     * How long a non-priority ring (a buff) must still wait, or 0 if it may tap now. With a heal
     * ring present, buffs only go in the slot right after a heal, once that heal's lock is over:
     * tapping a buff just before the heal is due would push the heal back further.
     */
    private long buffWait(Target t, long now) {
        if (t.forced) {
            // Full buff: go as soon as the previous skill's lock is over (busyUntil). Right after
            // the heal the lock can last ~4 s in a fight, so wait at least that long then.
            boolean afterHeal = lastPriorityTapAt > 0 && lastPriorityTapAt == lastAnyTapAt;
            if (!afterHeal) return 0;
            // End Game: a fixed 4 s, so the first buff goes before the next heal is due (4.17 s).
            // A learned wait (up to 3 s extra) let a heal in first and started the wait over: the
            // full buff began 10 s after the wave cleared.
            long wait = endGame ? FORCED_AFTER_HEAL_MS : Math.max(tapGapMs + t.extraGapMs, FORCED_AFTER_HEAL_MS);
            return Math.max(0, lastAnyTapAt + TAP_MS + wait - now);
        }
        boolean anyPriority = false;
        for (Target o : targets) if (o.priority) anyPriority = true;
        if (!anyPriority) return Math.max(0, lastAnyTapAt + TAP_MS + tapGapMs + t.extraGapMs - now);

        long slotStart = lastPriorityTapAt + TAP_MS + tapGapMs + t.extraGapMs;
        if (now < slotStart) return slotStart - now;
        // Its turn (a heal already went ahead of it): go now even if the slot window has passed.
        if (now <= slotStart + SLOT_WINDOW_MS || t.queuedAt < lastPriorityTapAt) return 0;
        // Missed this heal's slot: wait for the next heal, unless the heal seems stuck.
        long stuckAt = t.queuedAt + MAX_SLOT_WAIT_MS;
        return now >= stuckAt ? 0 : stuckAt - now;
    }

    private static boolean buffUrgent(Target t) {
        return t.buffKnown && (!t.buffFound || t.buffFill <= SMART_URGENT_AT);
    }

    /**
     * Party farming in waves: the party lures a crowd onto the support, who only heals until it's
     * nearly dead, then full-buffs everyone for the next lure.
     * A wave starts at waveStartMobs() on screen and ends once waveEndMobs() or fewer show for
     * WAVE_END_SCANS scans in a row (spell effects can hide names for a moment mid-fight).
     * Nothing is cast between waves: the party is away luring.
     */
    /** Party size from the team list; 0 (list hidden by a menu, or solo) keeps the last one seen. */
    private void updateParty(int members) {
        if (members <= 0 || members == partySize) return;
        boolean first = partySize < 0;
        boolean wasBig = bigParty();
        partySize = members;
        // A member walking out of range can flicker the count by one; only say so when it matters.
        if (first || bigParty() != wasBig) {
            Log.i(TAG, "party of " + members + ": wave at " + waveStartMobs() + "+ monsters, cleared at "
                    + waveEndMobs() + " or fewer");
        }
    }

    /** Big until a small party has been seen, so it behaves as before until the list is read. */
    private boolean bigParty() {
        return partySize < 0 || partySize >= BIG_PARTY;
    }

    private int waveStartMobs() {
        return bigParty() ? WAVE_START_MOBS : SMALL_WAVE_START_MOBS;
    }

    private int waveEndMobs() {
        return bigParty() ? WAVE_END_MOBS : SMALL_WAVE_END_MOBS;
    }

    private void updateWave(int mobs) {
        if (mobs < 0) return;
        long now = SystemClock.uptimeMillis();
        if (mobs != lastMobCount) {
            // For tuning: what the counter read whenever it changes.
            Log.d(TAG, "monsters: ~" + mobs);
            lastMobCount = mobs;
        }
        if (startBuffPending) {
            startBuffPending = false;
            if (mobs < START_BUFF_MAX_MOBS) {
                Log.i(TAG, "start: ~" + mobs + " monsters, full buff");
                fullBuff();
                return;
            }
            // A fight is on: heal first, and buff as soon as it's cleared, however short.
            inWave = true;
            waveStartedAt = now;
            clearScans = 0;
            fullBuffOwed = true;
            Log.i(TAG, "start: ~" + mobs + " monsters, healing first, full buff once they're down");
            return;
        }
        if (mobs >= waveStartMobs()) {
            clearScans = 0;
            if (!inWave && fullBuffGoingOut() && mobs == waveStartMobs() && ++backScans < 2) {
                // Mid full buff, one reading right at the wave level is often a flicker (small
                // party: 2-3-2): only stop the buffs once the wave is clearly back.
                return;
            }
            backScans = 0;
            if (!inWave) {
                inWave = true;
                waveStartedAt = now;
                Log.i(TAG, "wave: ~" + mobs + " monsters, heal only");
                stopFullBuff();
            }
            return;
        }
        backScans = 0;
        if (!inWave) return;
        clearScans = mobs <= waveEndMobs() ? clearScans + 1 : 0;
        if (clearScans < (bigParty() ? WAVE_END_SCANS : SMALL_WAVE_END_SCANS)) return;
        inWave = false;
        clearScans = 0;
        long lasted = now - waveStartedAt;
        // At a busy spot another group's crowd can pass through for a few seconds; that's no
        // reason to spend ~20 s of heals on a full buff. Unless a full buff was stopped because
        // the wave came back: this short wave is the rest of the real one, and the party is owed.
        if (lasted < MIN_WAVE_MS && !fullBuffOwed) {
            Log.i(TAG, "wave over after " + lasted / 1000 + " s (~" + mobs + " left), too short for a full buff");
            return;
        }
        // No full buff between waves even when a buff runs low: the party is away luring then, and
        // a buff cast now would miss them. They get buffed when they bring the next wave down.
        Log.i(TAG, "wave cleared after " + lasted / 1000 + " s (~" + mobs + " left), full buff"
                + (fullBuffOwed ? " (the one stopped when the wave came back)" : ""));
        fullBuffOwed = false;
        fullBuff();
    }

    /**
     * A wave came back while a full buff was going out (the count dipped below 6 mid-fight): drop
     * the buffs still in line so the heal gets its slots again. The next clear buffs again.
     */
    private void stopFullBuff() {
        int dropped = 0;
        for (int i = pending.size() - 1; i >= 0; i--) {
            Target t = pending.get(i);
            if (!t.forced) continue;
            t.forced = false;
            t.owed = true;
            pending.remove(i);
            handler.removeCallbacks(t.tick);
            handler.postDelayed(t.tick, SMART_RECHECK_MS);
            dropped++;
        }
        if (dropped == 0) return;
        // Let the next clear start a full buff right away, not "already in progress", however
        // short the rest of the wave turns out to be.
        lastFullBuffAt = 0;
        fullBuffOwed = true;
        handler.removeCallbacks(resetFullBuffButton);
        resetFullBuffButton.run();
        Log.i(TAG, "wave is back: full buff stopped (" + dropped + " buffs not cast), healing");
        schedulePump(0);
    }

    /** Buffs of a full buff are still waiting to go out. */
    private boolean fullBuffGoingOut() {
        for (Target t : pending) if (t.forced) return true;
        return false;
    }

    private static boolean buffBelowRecast(Target t) {
        return t.buffKnown && (!t.buffFound || t.buffFill <= t.recastAt);
    }

    /** EG/LL button. */
    private void setEndGame(boolean on, String why) {
        boolean changed = endGame != on;
        endGame = on;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_END_GAME, on).apply();
        if (changed || !why.equals("restored")) {
            Log.i(TAG, (on ? "End Game FS mode: buffs only in full buffs, auto full buff after each wave"
                    : "Low Level FS mode: each buff recast at its %") + " (" + why + ")");
        }
        inWave = false;
        clearScans = 0;
        modeButton.setText(on ? "EG" : "LL");
        modeButton.setBackground(circle(on ? Color.rgb(170, 40, 40) : Color.rgb(40, 130, 130)));
        for (Target t : targets) t.refreshLabel();
        if (changed && why.equals("button")) {
            shake(modeButton);
            // Switching to End Game starts the cycle like the support does by hand: buff the
            // party first, then they go lure. (Starts tapping too, like the FB button.)
            if (on) fullBuff();
        }
    }

    private boolean buffNeeded(Target t) {
        // End Game: buffs only go out in full buffs; every other slot is a heal.
        if (!t.forced && endGame) return false;
        return buffBelowRecast(t);
    }

    /**
     * Priority rings go first; everything else in the order it came due. A buff that a heal
     * already went ahead of gets the next turn, and the heal waits for it once, otherwise a buff
     * needing a long wait after the heal would never fit between two heals.
     */
    private Target pickNext(long now) {
        // Full buff in progress: its buffs go back to back and the heal waits until the last one
        // is out (~12 s). It comes when the wave is cleared, right after a heal, and the party
        // gets every buff ~4 s sooner, before heading off to lure.
        for (Target t : pending) {
            if (t.forced && !t.priority && !waitsForOthers(t)) return t;
        }
        for (Target t : pending) {
            if (!t.priority && lastPriorityTapAt > 0 && t.queuedAt < lastPriorityTapAt && !waitsForOthers(t)) return t;
        }
        for (Target t : pending) if (t.priority) return t;
        for (Target t : pending) if (!waitsForOthers(t)) return t;
        return pending.get(0);
    }

    /**
     * A "cast last" ring lets every other waiting buff go first. It doesn't wait for the others'
     * "did it take?" checks (7 s): that let a heal in between and put it ~9 s after the rest.
     */
    private boolean waitsForOthers(Target t) {
        if (!t.castLast) return false;
        for (Target o : targets) {
            if (o == t || o.priority || o.castLast) continue;
            // A retry of a buff that "didn't take" doesn't hold the last buff back.
            if (pending.contains(o) && o.forcedRetries == 0) return true;
        }
        return false;
    }

    private void schedulePump(long delayMs) {
        handler.removeCallbacks(pump);
        handler.postDelayed(pump, delayMs);
    }

    /** 500ms, 1.5s, 1m, 1m 30s */
    private static String formatInterval(int ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) {
            String s = String.valueOf(ms / 1000.0);
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            return s + "s";
        }
        int min = ms / 60_000;
        int sec = (ms % 60_000) / 1000;
        return sec == 0 ? min + "m" : min + "m " + sec + "s";
    }

    /** Package of the app whose window is in front, or null if Android won't say right now. */
    private String foregroundPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                // Sometimes there's no "active" window right after a restart; fall back to the
                // top-most app window (windows are listed top first).
                for (AccessibilityWindowInfo w : getWindows()) {
                    if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    root = w.getRoot();
                    if (root != null) break;
                }
            }
            if (root == null) return lastForeground;
            CharSequence pkg = root.getPackageName();
            if (pkg != null) lastForeground = pkg.toString();
        } catch (RuntimeException ignored) {
            // the window went away while asking
        }
        return lastForeground;
    }

    /** True while an on-screen keyboard is up, e.g. while typing in the game's chat. */
    private boolean keyboardShowing() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (RuntimeException ignored) {
            // windows changed while asking
        }
        return false;
    }

    /**
     * Only tap the app that was in front when ▶ was pressed (the game), never home or other apps,
     * and never while the keyboard is up: the rings would land on its keys (backspace, enter...).
     */
    private boolean gameInFront() {
        if (keyboardShowing()) {
            if (!pausedForKeyboard) Log.i(TAG, "paused: keyboard is open");
            pausedForKeyboard = true;
            return false;
        }
        if (pausedForKeyboard) {
            Log.i(TAG, "keyboard closed, tapping again");
            pausedForKeyboard = false;
        }
        // The game is landscape only. MIUI's "install via USB" screen turns the display to portrait
        // while the game still counts as the active window; rings tapped then hit that screen.
        DisplayMetrics real = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(real);
        boolean portrait = real.widthPixels < real.heightPixels;
        if (portrait != pausedForPortrait) {
            pausedForPortrait = portrait;
            Log.i(TAG, portrait ? "paused: screen turned to portrait" : "screen back to landscape, tapping again");
        }
        if (portrait) return false;
        if (gamePackage == null) return true;
        String front = foregroundPackage();
        // If Android won't say which app is in front, keep tapping rather than pause forever.
        if (front == null) return true;
        boolean inFront = gamePackage.equals(front);
        if (inFront == pausedForOtherApp) {
            pausedForOtherApp = !inFront;
            Log.i(TAG, inFront ? "game back in front, tapping again"
                    : "paused: " + lastForeground + " is in front, not " + gamePackage);
        }
        return inFront;
    }

    private void tap(Target t) {
        if (!running || !gameInFront()) return;
        int[] loc = new int[2];
        t.ring.getLocationOnScreen(loc);
        float x = loc[0] + t.ring.getWidth() / 2f;
        float y = loc[1] + t.ring.getHeight() / 2f;
        tapAt(x, y, "target " + (targets.indexOf(t) + 1) + " at " + Math.round(x) + "," + Math.round(y));
    }

    private void tapAt(float x, float y, String which) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, TAP_MS))
                .build();
        ownTapUntil = SystemClock.uptimeMillis() + TAP_MS + OWN_TAP_SLACK_MS;
        boolean sent = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                Log.d(TAG, "tap " + which);
            }

            @Override
            public void onCancelled(GestureDescription g) {
                // Android cancels our tap when a finger lands on the screen during it.
                Log.w(TAG, "tap cancelled: " + which);
                userTouched(SystemClock.uptimeMillis());
            }
        }, null);
        if (sent) {
            refusedInARow = 0;
        } else {
            Log.w(TAG, "tap refused by Android: " + which);
            // Android only refuses taps when the service is half connected; a restart fixes it.
            if (++refusedInARow >= 3) {
                refusedInARow = 0;
                Watchdog.requestRevive(this, "has its taps refused");
            }
        }
    }

    // ---------- cooldown detection ----------

    private static boolean canReadScreen() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /** Repeats while running: one screenshot updates the ready state of every watching target. */
    private void cooldownCheck() {
        if (!running) return;
        boolean anyCooldown = false;
        boolean anySmart = false;
        for (Target t : targets) {
            if (t.smartBuff) anySmart = true;
            // A smart buff's cooldown lasts far longer than a scan; only plain rings need 0.35 s.
            if (t.waitForCooldown && t.readyLook != null && !t.smartBuff) anyCooldown = true;
        }
        boolean anyWatching = anyCooldown || anySmart;
        if (anyWatching && canReadScreen()) {
            boolean scanBuffs = anySmart;
            // The whole screen, for the monster count. Copying just the buff row saved nothing:
            // Android makes a full copy internally to crop a screenshot (measured ~18 MB either way).
            boolean cropped = false;
            captureScreen(shot -> {
                if (!running) return;
                // The chat window, keyboard or another app hide the buff row; don't mistake that
                // for buffs running out.
                if (keyboardShowing() || (gamePackage != null && !gamePackage.equals(foregroundPackage()))) return;
                List<BuffReader.Icon> icons = scanBuffs ? BuffReader.scan(shot, screenW, screenH) : null;
                long now = SystemClock.uptimeMillis();
                // Screenshots come every 0.35 s while a ring watches a cooldown; counting once
                // per BUFF_SCAN_EVERY_MS is plenty.
                if (endGame && scanBuffs && now - lastMobCountAt >= BUFF_SCAN_EVERY_MS - 100) {
                    lastMobCountAt = now;
                    updateParty(MobCounter.partySize(shot, screenW, screenH));
                    updateWave(MobCounter.count(shot, screenW, screenH));
                }
                if (scanBuffs) targetSelected = MobCounter.targetSelected(shot, screenW, screenH);
                // The whole row vanishing at once means something covered it (a menu, an effect),
                // not that every buff ran out in the same second. Only believe it after a while.
                // Judge by our own learned buffs: if none of them can be seen at once, the row is
                // hidden or partly covered, even if some other box still looks like an icon.
                if (icons != null) {
                    boolean anyLearned = false;
                    boolean anySeen = false;
                    for (Target t : targets) {
                        if (!t.isSmart()) continue;
                        anyLearned = true;
                        if (findBuff(t, icons) != null) anySeen = true;
                    }
                    if (!anyLearned || anySeen) {
                        lastRowSeenAt = now;
                    } else if (now - lastRowSeenAt < ROW_GONE_BELIEVE_MS) {
                        return;
                    }
                }
                for (Target t : targets) {
                    if (t.waitForCooldown && t.readyLook != null) t.ready = !isDimmed(shot, t);
                    if (t.smartBuff) updateBuff(t, icons, now);
                }
                lastScan = icons;
                lastScanAt = now;
            }, cropped);
        }
        // Each screenshot is a full-screen copy (~16 MB). Buff timers change slowly, so smart
        // buffs only need one a second; the fast rate is for rings watching a cooldown shade.
        handler.postDelayed(this::cooldownCheck, anyCooldown ? SCREENSHOT_EVERY_MS : BUFF_SCAN_EVERY_MS);
    }

    /** For the log: each icon as x,y size fill%. */
    private static String describe(List<BuffReader.Icon> icons) {
        if (icons == null) return "[no scan]";
        StringBuilder sb = new StringBuilder("[");
        for (BuffReader.Icon i : icons) {
            if (sb.length() > 1) sb.append(' ');
            sb.append(i.x).append(',').append(i.y).append(' ').append(i.size).append(' ')
                    .append(Math.round(i.fill * 100)).append('%');
        }
        return sb.append(']').toString();
    }

    /** Reads a learned buff's timer, or learns which icon is this ring's buff. */
    private void updateBuff(Target t, List<BuffReader.Icon> icons, long now) {
        int n = targets.indexOf(t) + 1;
        if (t.buffIcon == null) {
            if (t.learnBefore == null || now < t.learnCheckAt) return;
            BuffReader.Icon mine = BuffReader.refreshedIcon(t.learnBefore, icons);
            String seen = "before " + describe(t.learnBefore) + " after " + describe(icons);
            t.learnBefore = null;
            if (mine == null) {
                Log.i(TAG, "buff target " + n + ": couldn't tell which icon is mine, will try again next cast; " + seen);
                return;
            }
            Log.i(TAG, "buff target " + n + ": " + seen);
            t.buffIcon = mine.sig;
            t.buffY = mine.y;
            t.buffSize = mine.size;
            Log.i(TAG, "buff target " + n + ": learned its icon at " + mine.x + "," + mine.y + ", smart buff active");
            t.refreshLabel();
            saveTargets();
        }

        BuffReader.Icon icon = findBuff(t, icons);
        if (icon != null && icon.fill >= 0.9f) {
            int d = BuffReader.diff(icon.sig, t.buffIcon);
            if (d >= RELEARN_DIFF) {
                Log.i(TAG, "buff target " + n + ": icon now " + icon.size + " px and " + d
                        + " off its saved look, saving the new look");
                t.buffIcon = icon.sig;
                t.buffY = icon.y;
                t.buffSize = icon.size;
                saveTargets();
            }
        }
        boolean wasNeeded = t.buffKnown && (!t.buffFound || t.buffFill <= t.recastAt);
        boolean looksNeeded = icon == null || icon.fill <= t.recastAt;
        if (icon != null && icon.fill >= 0.9f) t.lastFullAt = now;
        // A buff can't drop from full to its threshold within seconds; right after it read full,
        // a low reading is a glitch (the row reshuffling after a recast, often "48%"). Throw the
        // reading away entirely and keep the last good one, so nothing acts on it.
        if (icon != null && looksNeeded && now - t.lastFullAt < JUST_FULL_MS) return;
        // One odd frame (an effect or a player walking over the row) shouldn't trigger a recast:
        // only believe "missing" or "low" once two scans in a row agree.
        t.neededStreak = looksNeeded ? t.neededStreak + 1 : 0;
        if (looksNeeded && t.neededStreak < 2) return;
        t.buffKnown = true;
        t.buffFound = icon != null;
        t.buffFill = icon != null ? icon.fill : 0f;
        boolean needed = looksNeeded;
        if (!needed) t.recastsWithoutOk = 0;
        if (needed != wasNeeded) {
            Log.i(TAG, "buff target " + n + ": "
                    + (icon != null ? Math.round(icon.fill * 100) + "% left" : "not active")
                    + (!needed ? ", ok" : endGame ? ", left for the next full buff" : ", recasting"));
        }
        if (wasNeeded && !needed && icon.fill >= 0.9f) {
            // Our tap took first time: the wait after the heal may be longer than it needs to be.
            if (!t.retried && t.extraGapMs > MIN_EXTRA_GAP_MS) {
                t.extraGapMs = Math.max(MIN_EXTRA_GAP_MS, t.extraGapMs - EXTRA_GAP_DECAY_MS);
                Log.i(TAG, "buff target " + n + ": took first tap, now waiting "
                        + (tapGapMs + t.extraGapMs) + "ms after the previous skill");
                saveTargets();
            }
            t.retried = false;
        }
    }

    /** This ring's buff in the row, told apart from the other rings' learned buffs. */
    private BuffReader.Icon findBuff(Target t, List<BuffReader.Icon> icons) {
        List<int[]> others = new ArrayList<>();
        for (Target o : targets) {
            if (o != t && o.isSmart()) others.add(o.buffIcon);
        }
        return BuffReader.find(icons, t.buffIcon, others);
    }

    private boolean isDimmed(Bitmap shot, Target t) {
        int[] now = readLook(shot, t);
        for (int row = 0; row < SAMPLE_ROWS; row++) {
            int useful = 0;
            int dimmed = 0;
            for (int col = 0; col < SAMPLE_COLS; col++) {
                int i = row * SAMPLE_COLS + col;
                if (t.readyLook[i] < MIN_USEFUL_LUMA || now[i] < 0) continue;
                useful++;
                if (now[i] < t.readyLook[i] * DIM_RATIO) dimmed++;
            }
            // Most of a row darker than when ready means the cooldown shade still covers it.
            if (useful > 0 && dimmed * 2 > useful) return true;
        }
        return false;
    }

    /** Brightness at each sample point, or -1 for points outside the screenshot. */
    private int[] readLook(Bitmap shot, Target t) {
        int[][] pts = t.samplePoints();
        int[] look = new int[pts.length];
        for (int i = 0; i < pts.length; i++) {
            int x = pts[i][0];
            int y = pts[i][1];
            if (x < 0 || y < 0 || x >= shot.getWidth() || y >= shot.getHeight()) {
                look[i] = -1;
                continue;
            }
            int c = shot.getPixel(x, y);
            look[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
        }
        return look;
    }

    private void captureScreen(Consumer<Bitmap> onShot) {
        captureScreen(onShot, false);
    }

    /**
     * Takes a screenshot and hands over a readable copy. With buffRowOnly, only the top-left
     * corner holding the buff row is copied (~2.5 MB instead of ~16 MB): the game uses most of
     * the tablet's memory, and Android kills whichever app it can spare when it runs out.
     */
    private void captureScreen(Consumer<Bitmap> onShot, boolean buffRowOnly) {
        if (!canReadScreen()) return;
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                buffer.close();
                if (hw == null) return;
                screenW = hw.getWidth();
                screenH = hw.getHeight();
                // Hardware bitmaps can't be read pixel by pixel, so make a readable copy.
                Bitmap shot = null;
                if (buffRowOnly) {
                    try {
                        Bitmap part = Bitmap.createBitmap(hw, 0, 0, screenW * 45 / 100, screenH * 35 / 100);
                        shot = part.copy(Bitmap.Config.ARGB_8888, false);
                        if (part != hw) part.recycle();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "couldn't copy just the buff row, copying the whole screen: " + e);
                    }
                }
                if (shot == null) shot = hw.copy(Bitmap.Config.ARGB_8888, false);
                hw.recycle();
                if (shot == null) return;
                onShot.accept(shot);
                shot.recycle();
            }

            @Override
            public void onFailure(int errorCode) {
                // Usually "too soon after the last screenshot"; the next check will retry.
            }
        });
    }

    /** Hides the editor so it isn't in the picture, then saves how the button looks when ready. */
    private void rememberReadyLook(Target t) {
        closeEditor();
        handler.postDelayed(() -> captureScreen(shot -> {
            t.readyLook = readLook(shot, t);
            t.lookX = t.params.x;
            t.lookY = t.params.y;
            t.waitForCooldown = true;
            t.refreshLabel();
            saveTargets();
            openEditor(t);
        }), 200);
    }

    // ---------- editor panel ----------

    /** Small panel for changing one target's interval, cooldown check, or deleting it. */
    private void openEditor(Target t) {
        closeEditor();
        int pad = dp(16);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 30, 30, 30));
        bg.setCornerRadius(dp(16));
        panel.setBackground(bg);

        TextView title = new TextView(this);
        title.setTextColor(Color.LTGRAY);
        title.setTextSize(15);
        title.setText("Target " + (targets.indexOf(t) + 1) + " taps every");
        title.setGravity(Gravity.CENTER);
        panel.addView(title, fullWidth());

        TextView value = new TextView(this);
        value.setTextColor(Color.WHITE);
        value.setTextSize(30);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setGravity(Gravity.CENTER);
        value.setText(formatInterval(t.interval));
        panel.addView(value, fullWidth());

        // Small steps for fast taps, big steps for buffs every minute or two.
        int[][] stepRows = {
                {-100, -10, 10, 100},
                {-60_000, -10_000, -1000, 1000, 10_000, 60_000},
        };
        for (int[] row : stepRows) {
            LinearLayout steps = new LinearLayout(this);
            for (int step : row) {
                String text = (step > 0 ? "+" : "−") + formatInterval(Math.abs(step));
                TextView b = pillButton(text, Color.rgb(70, 70, 70), () -> {
                    t.interval = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, t.interval + step));
                    value.setText(formatInterval(t.interval));
                    t.refreshLabel();
                    saveTargets();
                });
                b.setTextSize(14);
                b.setPadding(dp(4), dp(10), dp(4), dp(10));
                steps.addView(b, shared());
            }
            panel.addView(steps, fullWidth());
        }

        TextView priority = pillButton("", Color.rgb(70, 70, 70), null);
        Runnable showPriority = () -> priority.setText(t.priority
                ? "★ Priority: On (goes first when due)"
                : "Priority: Off");
        priority.setOnClickListener(v -> {
            t.priority = !t.priority;
            showPriority.run();
            t.refreshLabel();
            saveTargets();
        });
        showPriority.run();
        LinearLayout.LayoutParams priorityLp = fullWidth();
        priorityLp.topMargin = dp(16);
        panel.addView(priority, priorityLp);

        TextView castLast = pillButton("", Color.rgb(70, 70, 70), null);
        Runnable showCastLast = () -> castLast.setText(t.castLast
                ? "⤓ Cast last: On (after the other buffs)"
                : "Cast last: Off");
        castLast.setOnClickListener(v -> {
            t.castLast = !t.castLast;
            showCastLast.run();
            t.refreshLabel();
            saveTargets();
        });
        showCastLast.run();
        LinearLayout.LayoutParams castLastLp = fullWidth();
        castLastLp.topMargin = dp(8);
        panel.addView(castLast, castLastLp);

        if (canReadScreen()) {
            TextView smartNote = new TextView(this);
            smartNote.setTextColor(Color.LTGRAY);
            smartNote.setTextSize(13);
            smartNote.setGravity(Gravity.CENTER);
            smartNote.setPadding(0, dp(14), 0, 0);
            panel.addView(smartNote, fullWidth());

            LinearLayout smartRow = new LinearLayout(this);
            TextView smartToggle = pillButton("", Color.rgb(70, 70, 70), null);
            Runnable showSmart = () -> {
                smartToggle.setText(t.smartBuff ? "Smart buff: On" : "Smart buff: Off");
                if (!t.smartBuff) {
                    smartNote.setText("Smart buff watches this buff's timer in the top-left buff row.");
                } else if (t.buffIcon == null) {
                    smartNote.setText("Learning: taps on its interval until it sees which buff icon refreshes when it casts.");
                } else {
                    smartNote.setText("Casts when the buff is missing or its timer drops to the % below. Interval is ignored.");
                }
            };
            smartToggle.setOnClickListener(v -> {
                t.smartBuff = !t.smartBuff;
                showSmart.run();
                t.refreshLabel();
                saveTargets();
            });
            showSmart.run();
            smartRow.addView(smartToggle, shared());
            smartRow.addView(pillButton("Relearn icon", Color.rgb(40, 90, 180), () -> {
                t.buffIcon = null;
                t.buffKnown = false;
                showSmart.run();
                t.refreshLabel();
                saveTargets();
            }), shared());
            panel.addView(smartRow, fullWidth());

            LinearLayout recastRow = new LinearLayout(this);
            recastRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView recastLabel = new TextView(this);
            recastLabel.setTextColor(Color.WHITE);
            recastLabel.setTextSize(15);
            recastLabel.setGravity(Gravity.CENTER);
            Runnable showRecast = () -> recastLabel.setText("Recast at " + Math.round(t.recastAt * 100) + "%");
            showRecast.run();
            for (int step : new int[] {-10, 10}) {
                TextView b = pillButton((step > 0 ? "+" : "−") + Math.abs(step) + "%", Color.rgb(70, 70, 70), () -> {
                    int pct = Math.max(10, Math.min(90, Math.round(t.recastAt * 100) + step));
                    t.recastAt = pct / 100f;
                    showRecast.run();
                    t.refreshLabel();
                    saveTargets();
                });
                if (step < 0) {
                    recastRow.addView(b, shared());
                    recastRow.addView(recastLabel, shared());
                } else {
                    recastRow.addView(b, shared());
                }
            }
            panel.addView(recastRow, fullWidth());
        }

        TextView cdNote = new TextView(this);
        cdNote.setTextColor(Color.LTGRAY);
        cdNote.setTextSize(13);
        cdNote.setGravity(Gravity.CENTER);
        cdNote.setPadding(0, dp(14), 0, 0);
        panel.addView(cdNote, fullWidth());

        LinearLayout cdRow = new LinearLayout(this);
        if (canReadScreen()) {
            TextView cdToggle = pillButton("", Color.rgb(70, 70, 70), null);
            Runnable showCd = () -> {
                cdToggle.setText(t.waitForCooldown ? "Wait for cooldown: On" : "Wait for cooldown: Off");
                if (!t.waitForCooldown) {
                    cdNote.setText("Taps on its timer, even during cooldown.");
                } else if (t.readyLook == null) {
                    cdNote.setText("Ready look not saved yet.\nWhile the skill is ready, press Remember ready look.");
                } else {
                    cdNote.setText("Skips taps while the button is dimmed.");
                }
            };
            cdToggle.setOnClickListener(v -> {
                t.waitForCooldown = !t.waitForCooldown;
                showCd.run();
                t.refreshLabel();
                saveTargets();
            });
            showCd.run();
            cdRow.addView(cdToggle, shared());
            cdRow.addView(pillButton("Remember ready look", Color.rgb(40, 90, 180), () -> rememberReadyLook(t)), shared());
        } else {
            cdNote.setText("Cooldown check needs Android 11 or newer.");
        }
        panel.addView(cdRow, fullWidth());

        LinearLayout actions = new LinearLayout(this);
        actions.addView(pillButton("Delete", Color.rgb(170, 40, 40), () -> {
            closeEditor();
            removeTarget(t);
        }), shared());
        actions.addView(pillButton("Done", Color.rgb(40, 150, 60), this::closeEditor), shared());
        LinearLayout.LayoutParams actionsLp = fullWidth();
        actionsLp.topMargin = dp(10);
        panel.addView(actions, actionsLp);

        // Android squeezes auto-sized popups to phone-dialog width, so size the panel ourselves.
        DisplayMetrics m = getResources().getDisplayMetrics();
        int width = Math.min(m.widthPixels - dp(32), dp(520));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel, new ScrollView.LayoutParams(width, ScrollView.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams p = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.CENTER;
        if (safeAdd(scroll, p)) editor = scroll;
    }

    /** A child that spans the full panel width. */
    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, 0);
        return lp;
    }

    /** Buttons in a row share its width equally. */
    private LinearLayout.LayoutParams shared() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(8), dp(4), 0);
        return lp;
    }

    private void closeEditor() {
        if (editor != null) {
            safeRemove(editor);
            editor = null;
        }
    }

    // ---------- saving ----------

    /**
     * One target per ";": x,y,interval,waitForCooldown,readyLook,priority,smartBuff,buffY,buffSize,buffIcon
     * (readyLook and buffIcon are numbers joined by ".").
     */
    private void saveTargets() {
        StringBuilder sb = new StringBuilder();
        for (Target t : targets) {
            if (sb.length() > 0) sb.append(';');
            sb.append(t.params.x).append(',').append(t.params.y).append(',').append(t.interval)
                    .append(',').append(t.waitForCooldown ? 1 : 0).append(',');
            if (t.readyLook != null) {
                for (int i = 0; i < t.readyLook.length; i++) {
                    if (i > 0) sb.append('.');
                    sb.append(t.readyLook[i]);
                }
            }
            sb.append(',').append(t.priority ? 1 : 0);
            sb.append(',').append(t.smartBuff ? 1 : 0).append(',').append(t.buffY).append(',').append(t.buffSize).append(',');
            if (t.buffIcon != null) {
                for (int i = 0; i < t.buffIcon.length; i++) {
                    if (i > 0) sb.append('.');
                    sb.append(t.buffIcon[i]);
                }
            }
            sb.append(',').append(Math.round(t.recastAt * 100));
            sb.append(',').append(t.extraGapMs);
            sb.append(',').append(t.castLast ? 1 : 0);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TARGETS, sb.toString()).apply();
    }

    private void loadTargets() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_TARGETS, "");
        boolean retune = !prefs.getBoolean(KEY_TUNED_40, false);
        for (String entry : saved.split(";")) {
            String[] parts = entry.split(",", -1);
            if (parts.length < 3) continue;
            try {
                Target t = addTarget(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Math.max(MIN_INTERVAL, Integer.parseInt(parts[2])));
                if (t == null) continue;
                if (parts.length >= 5) {
                    t.waitForCooldown = parts[3].equals("1");
                    String[] look = parts[4].isEmpty() ? new String[0] : parts[4].split("\\.");
                    if (look.length == SAMPLE_ROWS * SAMPLE_COLS) {
                        t.readyLook = new int[look.length];
                        for (int i = 0; i < look.length; i++) t.readyLook[i] = Integer.parseInt(look[i]);
                        t.lookX = t.params.x;
                        t.lookY = t.params.y;
                    }
                }
                if (parts.length >= 6) t.priority = parts[5].equals("1");
                if (parts.length >= 13) t.castLast = parts[12].equals("1");
                if (parts.length >= 12 && !parts[11].isEmpty()) {
                    t.extraGapMs = Math.max(0, Math.min(MAX_EXTRA_GAP_MS, Integer.parseInt(parts[11])));
                }
                if (parts.length >= 11 && !parts[10].isEmpty()) {
                    t.recastAt = Math.max(10, Math.min(90, Integer.parseInt(parts[10]))) / 100f;
                }
                if (parts.length >= 10) {
                    t.smartBuff = parts[6].equals("1");
                    t.buffY = Integer.parseInt(parts[7]);
                    t.buffSize = Integer.parseInt(parts[8]);
                    if (!parts[9].isEmpty()) {
                        String[] icon = parts[9].split("\\.");
                        t.buffIcon = new int[icon.length];
                        for (int i = 0; i < icon.length; i++) t.buffIcon[i] = Integer.parseInt(icon[i]);
                    }
                }
                if (retune) {
                    if (Math.round(t.recastAt * 100) == 50) t.recastAt = SMART_RECAST_AT;
                    t.extraGapMs = MIN_EXTRA_GAP_MS;
                }
                t.refreshLabel();
            } catch (NumberFormatException ignored) {
            }
        }
        if (retune) {
            Log.i(TAG, "settings: buffs at 50% now recast at 40%, extra waits reset to " + MIN_EXTRA_GAP_MS + "ms");
            saveTargets();
            prefs.edit().putBoolean(KEY_TUNED_40, true).apply();
        }
    }

    // ---------- views ----------

    private WindowManager.LayoutParams overlayParams(int width, int height) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }

    /** Drag the handle to move the window; a touch that barely moves counts as a click. */
    private void makeDraggable(View handle, View window, WindowManager.LayoutParams params,
                               Runnable onClick, Runnable onMoved) {
        int slop = dp(8);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = params.x;
                        startY = params.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) dragging = true;
                        if (dragging) {
                            params.x = startX + (int) dx;
                            params.y = startY + (int) dy;
                            safeUpdate(window, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            if (onMoved != null) onMoved.run();
                            saveTargets();
                        } else {
                            v.performClick();
                            // That touch was on our own button, not the game: don't hold taps for it
                            // (pressing ▶ used to delay the first heal by the 4 s touch pause).
                            userTouchAt = 0;
                            onClick.run();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private TextView roundButton(String text) {
        TextView b = new TextView(this);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(22);
        b.setTextColor(Color.WHITE);
        b.setText(text);
        b.setBackground(circle(Color.rgb(40, 90, 180)));
        return b;
    }

    private TextView pillButton(String text, int color, Runnable action) {
        TextView b = new TextView(this);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setText(text);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        if (action != null) b.setOnClickListener(v -> action.run());
        return b;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(2), Color.WHITE);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) updateOverlayVisibility();
    }

    /**
     * The bar and rings only show while the game is in front; on the home screen or in another app
     * they'd just be in the way. Only an app window decides: our own settings screen and the
     * keyboard leave things as they are.
     */
    private void updateOverlayVisibility() {
        if (bar == null) return;
        handler.removeCallbacks(visibilityCheck);
        String front = foregroundPackage();
        String game = gamePackage != null ? gamePackage : DEFAULT_GAME;
        if (front != null && !front.equals(getPackageName())) {
            boolean show = front.equals(game);
            if (show == overlaysHidden) {
                overlaysHidden = !show;
                if (!show) closeEditor();
                bar.setVisibility(show ? View.VISIBLE : View.GONE);
                for (Target t : targets) t.root.setVisibility(show && !manual ? View.VISIBLE : View.GONE);
                Log.i(TAG, show ? "game in front: showing the bar" : "hidden while " + front + " is in front");
            }
        }
        // Not every way back to the game sends an event (e.g. closing the notification shade).
        if (overlaysHidden) handler.postDelayed(visibilityCheck, VISIBILITY_RECHECK_MS);
    }

    private final Runnable visibilityCheck = this::updateOverlayVisibility;

    @Override
    public void onInterrupt() {
        // Meant for screen readers ("stop talking"). Any app can send it at any time,
        // so it must not stop the clicker.
        Log.i(TAG, "interrupt ignored");
    }

    /**
     * Whether Android shows any of this service's overlay windows. False when the service is only
     * half connected, or when a restart took the bar away while leaving the service connected.
     */
    static boolean overlayShowing() {
        ClickService s = instance;
        if (s == null) return false;
        try {
            for (AccessibilityWindowInfo w : s.getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return true;
            }
        } catch (RuntimeException ignored) {
            // windows changed while asking
        }
        return false;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        if (wm != null) removeOverlays("unbind");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        if (wm != null) removeOverlays("destroy");
        super.onDestroy();
    }
}
