package com.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
    // Telegram when the game is gone (see checkGameGone, checkGameDialog).
    private static final int GAME_GONE_ALERT_MS = 60_000;
    private static final int GAME_ALERT_GAP_MS = 5 * 60_000;
    private long gameHiddenSince;
    private boolean gameGoneAlerted;
    private long lastGameAlertAt = -GAME_ALERT_GAP_MS;
    private String gamePackage;
    private String lastForeground;
    private boolean pausedForOtherApp;
    private boolean pausedForKeyboard;
    // The math question ("Please verify this simple questions, what is 10 + 1") opens the number
    // keyboard by itself (2026-10-02 20:13:50, no touch before it). A keyboard you didn't open
    // while the auto clicker runs is taken for it (see keyboardOpened).
    private static final int KEYBOARD_TOUCH_MS = 5000;
    private boolean keyboardAlerted;
    private boolean pausedForPortrait;
    // Answering the math question itself (on-device OCR). Let the panel settle before the
    // screenshot, stagger the key taps (the game rejects simultaneous touches), and don't re-try
    // the same question in a tight loop. mathBusy stops a second attempt while one is in flight.
    private static final int MATH_SETTLE_MS = 500;
    private static final int MATH_TAP_GAP_MS = 250;
    private static final int MATH_GAP_MS = 15_000;
    // A heads-up notification lands over the question text at the top and hides it for a second or
    // two; the question itself stays ~25 s. So if the first read fails, try a few more times a
    // short wait apart before giving up and alerting.
    private static final int MATH_MAX_ATTEMPTS = 4;
    private static final int MATH_RETRY_MS = 1500;
    private boolean mathBusy;
    private long lastMathAnswerAt = -MATH_GAP_MS;

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
    // Booster mode (BOOST on the mode button): the character just stands there to be carried. No
    // rings, heals, buffs, FB or chat-FB; the only two things it does are the left-right jiggle every
    // 3 min (to keep it from going idle) and answering the math question. Disconnect/crash/presence
    // alerts still run. Picked on the mode button, which cycles EG -> LL -> BOOST.
    private static final String KEY_BOOSTER = "booster";
    private boolean booster;
    // In booster there's no tap loop to notice the keyboard, so a light tick watches for it; and the
    // presence check (reused from cooldownCheck) runs slower since nothing else needs a screenshot.
    private static final int KEYBOARD_WATCH_MS = 800;
    private static final int BOOSTER_PRESENCE_MS = 3000;
    private final Runnable keyboardWatchTick = this::keyboardWatchTick;
    // Farmer mode (FARM on the start chooser, 2026-10-04): attacks monsters with rings you place for
    // it, kept as their own layout so the FS heal/buff rings stay put. Each ring taps on its own
    // interval/cooldown like Low Level FS; no waves, FB or chat-FB. The game picks the target for
    // attack skills itself. When no monster names show for FARM_IDLE_MS it walks a step to find
    // more, turning E, N, W, S so it circles its spot rather than drifting off.
    private static final String KEY_FARMER = "farmer";
    private static final String KEY_TARGETS_FARM = "targets_farm";
    private boolean farmer;
    // Kills leave gaps (a dead monster stays "selected" a few seconds), so wait well past them.
    private static final int FARM_IDLE_MS = 10_000;
    // One screenshot every 2 s does the fight check, and every 2nd one the loot + question reading
    // (separate screenshots failed when too close together and ran the tablet out of memory).
    private static final int FARM_SCAN_MS = 2000;
    private static final int FARM_WALK_MS = 4000;   // big maps: cover ground (and leave a stuck spot)
    private static final int FARM_WALK_SETTLE_MS = 400;
    private static final int FARM_PUSH_MS = 100;
    private static final float FARM_PUSH = 140 / 2560f;
    private static final int[][] FARM_WALK_DIRS = {{1, 0}, {0, -1}, {-1, 0}, {0, 1}};   // E N W S
    private int farmWalkStep;
    private static final int FARM_MAX_LEG = 4;
    private int farmLegLen = 1, farmLegDone, farmTurns;
    private long farmMobsSeenAt;
    // Target HP not dropping this long = stuck on a monster it can't reach.
    private static final int FARM_STUCK_MS = 15_000;   // tougher monsters take a few hits (13:37)
    // A full bar that stays full may be a new monster each check (fast kills: 3 in 15 s read 99%
    // every time), so a full bar has to stay full for longer.
    private static final int FARM_STUCK_FULL_MS = 45_000;   // 20 s still dropped fast kills (11:54)
    private long farmProgressAt;
    private float farmTargetHp = -1;
    // Until when a walk, a target drop or a trip to loot is still going (attacks hold off too).
    private long farmHoldUntil;
    // The shared "pause after tap" (3 s, set for heals) spaced attacks ~4 s apart.
    private static final int FARM_TAP_GAP_MS = 800;
    // Buffs in Farmer: hold attacks this long after a buff so its cast isn't cancelled; wait at most
    // FARM_BUFF_MAX_WAIT_MS before one; a cast that didn't take is retried after FARM_BUFF_RETRY_MS.
    private static final int FARM_AFTER_BUFF_MS = 1500, FARM_BUFF_MAX_WAIT_MS = 1500, FARM_BUFF_RETRY_MS = 15_000;
    private static final int FARM_BUFF_AFTER_BUFF_MS = 2500;
    // Loot: the hand button beside F1 picks up everything nearby (the user's pick, 2026-10-04,
    // after walking to gold labels kept stopping short and attacks pulled the character away).
    // While the hand shows, attacks pause until it's picked up (farmLootCheck).
    private static final float LOOT_HAND_X = 1735 / 2560f, LOOT_HAND_Y = 1430 / 1600f;
    private static final int LOOT_MAX_PAUSE_MS = 10_000, LOOT_RETAP_MS = 700, LOOT_IGNORE_MS = 8000;
    // Not faster while looting: 1 s screenshots under memory pressure preceded Android's own
    // system process hanging and restarting (12:35-12:37, watchdog kill), as at 11:43.
    private static final int LOOT_SCAN_MS = 2000;
    // A skill still animating ignores other input; give it this long after the last attack tap.
    private static final int LOOT_AFTER_SKILL_MS = 700;
    private long lootStartedAt, lootIgnoreUntil;
    // Taps allowed per screenshot that shows the hand (0.7 s apart, inside the 2 s scan).
    private static final int LOOT_TAPS_PER_LOOK = 2;
    private int lootTapsLeft;
    private static final int LOOT_FAILS_TO_PAUSE = 3, LOOT_FULL_PAUSE_MS = 5 * 60_000;
    private int lootFailStreak;
    // Near a kill: check every KILL_SCAN_MS once the target is at KILL_SOON_HP or below, and hold
    // attacks POST_KILL_HOLD_MS after it dies so the drop is looted before the next fight.
    private static final float KILL_SOON_HP = 0.4f;
    private static final int KILL_SCAN_MS = 1000, POST_KILL_HOLD_MS = 1300;
    private long postKillUntil;
    private final Runnable lootTapTick = this::lootTapTick;
    // Text reading (the anti-bot question) on every 2nd fight-check screenshot, from the play area.
    private static final int FARM_OCR_MS = 4000;
    private long lastFarmOcrAt;
    // From just under the HP bars at the top (so the target bar's name is in it) down to the chat.
    private static final float READ_L = 0.15f, READ_T = 0.02f, READ_W = 0.65f, READ_H = 0.72f;
    private static final float TARGET_NAME_MAX_Y = 0.07f;     // title ends ~0.064H
    // Monster names this far from the character (share of screen width) count as "a fight is on".
    private static final float MONSTER_NEAR_W = 0.3f;
    private static final String[] FARM_SKIP_NAMES = {"caloyski"};
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
    // A party of 5 brings ~5 at most: small-party clearing, but a wave only from 4 so the 1-3
    // flicker between lures doesn't keep starting short ones.
    private static final int BIG_PARTY = 6;
    private static final int WAVE_START_MOBS = 6;
    private static final int WAVE_END_MOBS = 5;
    private static final int PARTY_OF_5_WAVE_START_MOBS = 4;
    private static final int SMALL_WAVE_START_MOBS = 3;
    private static final int SMALL_WAVE_END_MOBS = 2;
    private int partySize = -1; // from the team list; -1 until seen
    private long partySeenAt; // last reading at least partySize
    private static final int PARTY_SHRINK_MS = 60_000;
    // Scans in a row below the end count before a wave counts as cleared (a dip mid-fight in a big
    // party lasted 3 scans; small parties want the buff sooner and a returning wave stops it anyway).
    private static final int WAVE_END_SCANS = 4;
    private static final int SMALL_WAVE_END_SCANS = 3;
    private static final int MIN_WAVE_MS = 15_000;
    private int lastMobCount = -1;
    private boolean inWave;
    // TEST: a tiny in-place joystick jiggle every 3 minutes while running. It goes just before the
    // next heal, never during a heal's ticks, a buff cast or a full buff. Each jiggle is out-and-back
    // (a push one way then the same push the other way). It's deliberately tiny: even out-and-back
    // leaves a small leftover when terrain/asymmetry stops one side, and over many 3-min cycles that
    // leftover added up and the character wandered off; a tiny push keeps the leftover negligible.
    private static final int TEST_MOVE_EVERY_MS = 3 * 60_000;
    // A brief push, long enough to register a clear left/right step. (50 ms / ~0.3 block was so small
    // it fell in the joystick deadzone and just twitched "forward".)
    private static final int MOVE_MS = 80;
    // Gap between the out push and the return push, so the game reads them as two separate nudges.
    private static final int MOVE_RETURN_GAP_MS = 60;
    private static final int MOVE_SETTLE_MS = 300;
    // Centre of the on-screen joystick. Found live by pushing at different heights: at y=1190 a
    // horizontal push goes due west/east, but lower down (1210/1258/1288) it carried a SOUTH
    // component (left -> SW, right -> SE), so left-then-right cancelled east/west yet kept adding
    // south and the character crept south every cycle. Centred on y=1190, left and right cancel and
    // it stays put.
    private static final float JOYSTICK_X = 250 / 2560f;
    private static final float JOYSTICK_Y = 1190 / 1600f;
    // ~120 px each way: clearly registers as a left/right step (40 px was below the deadzone). Being
    // centred, not small, is what stops the drift, so the step can be visible and still return.
    private static final float JOYSTICK_PUSH = 120 / 2560f;
    private boolean movePending;
    private final Runnable testMoveTick = this::testMoveTick;
    // Chat-triggered full buff: OCR the chat log; when a new message asks for buffs, cast FB. The
    // chat sits bottom-centre (below the All/Hide/Expand bar, above the timestamp); crop to that so
    // OCR is quick and the joystick/skill buttons don't get read. fullBuff() has its own 15 s
    // cooldown, so repeats while a request lingers are harmless.
    private static final int CHAT_SCAN_MS = 1000;
    private static final float CHAT_L = 0.28f, CHAT_T = 0.74f, CHAT_W = 0.44f, CHAT_H = 0.20f;
    // A message counts as a buff request if it contains any of these (plus "fb" as its own word).
    private static final String[] CHAT_FB_WORDS = {"full buff", "pa buff", "pabuff", "buffs"};
    private final Runnable chatScanTick = this::chatScanTick;
    // Fire FB once when a buff request first appears, not every scan it stays on screen. Re-arm only
    // after the request has been gone a few scans, so OCR missing a line for one frame doesn't
    // re-trigger it. (Comparing the OCR text between scans failed: the same line reads slightly
    // differently each second, so every scan looked "new" and it spammed FB every cooldown.)
    private static final int CHAT_REARM_SCANS = 3;
    private boolean chatArmed = true;
    private int chatAbsentScans;
    private boolean presenceCheckShown;
    // After a wave, buffs at or below this are recast (the user's call). With a clear every ~2 min
    // (93-214 s on 2026-10-02) the ~4.5-5 min buffs sit at ~55-60% after one wave and well below
    // after two: recast every 2nd wave. Confusion Strike (~2 min) and Massive Haste (~40 s) every
    // wave. 40% risked the long buffs running out when a short gap was followed by a long one.
    private static final float WAVE_BUFF_AT = 0.5f;
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
    // The anti-bot question: since 2026-10-04 four answer buttons ("what is 6 + 6", A) 18 B) 9 C) 12
    // D) 22) instead of a typed answer, so no keyboard opens and keyboardOpened never sees it. OCR
    // the screen every few seconds for the question (Farmer reads it from its own screenshots);
    // when it shows, tap the button with the answer (checkForQuestion), or alert you if unsure.
    // Its own handler: start/stop clears `handler`, and this runs whether or not ▶ is on.
    private final Handler watchHandler = new Handler(Looper.getMainLooper());
    // Off the 1 s / 2 s screenshot rhythm of the other checks, so it doesn't keep colliding.
    private static final int QUESTION_WATCH_MS = 3170;
    private static final float QUESTION_SCAN_H = 0.66f;     // down to the answer buttons
    // Not plain "verify": the loading screen says "Verifying~" (13:09, a false alert).
    private static final String[] QUESTION_WATCH_WORDS = {"simple question", "verify this"};
    private final Runnable questionWatchTick = this::questionWatchTick;
    private boolean questionSeen;
    private int questionAbsentScans;
    private long questionSeenAt;
    // Auto-answer (the user asked, 2026-10-04): tap the matching button, re-tap if the panel is
    // still up a few seconds later, give up and alert after a few.
    private static final int QUESTION_MAX_TAPS = 3;
    private static final int QUESTION_RETAP_MS = 3500;
    private static final int QUESTION_TAP_HOLD_MS = 1500;
    private int questionTaps;
    private long lastQuestionTapAt;
    private boolean questionAlerted;
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
    private View modeChooser;
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
    private static final int MISSING_GLITCH_MS = 20_000;
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
        // When and how full the buff was last actually seen (to spot a row misread as "gone").
        long lastSeenAt;
        float lastSeenFill;
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
                    if (needed && sinceTap >= SMART_RETRY_MS && farmer && lastTapAt > 0
                            && sinceTap < SMART_RETRY_MS + 5000) {
                        // Farmer: a buff the monsters keep interrupting must not starve the attacks
                        // (retried every ~10 s with waits around it, the character barely attacked,
                        // 13:32). Leave it a while; the attacks go on meanwhile.
                        backoffUntil = now + FARM_BUFF_RETRY_MS;
                        lastTapAt = 0;
                        Log.i(TAG, "buff target " + (targets.indexOf(Target.this) + 1) + ": cast didn't take, trying again in "
                                + FARM_BUFF_RETRY_MS / 1000 + " s");
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
                // Hidden rings (manual, game off screen, installs) lay out at width 0: that's not a
                // label change, and treating it as one pushed every ring half its width sideways
                // each time it was hidden (rings "kept moving after a restart", 2026-10-04).
                if (oldWidth > 0 && newWidth > 0 && newWidth != oldWidth) {
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
        // Farmer keeps its own rings: load the layout of the mode it was last in.
        farmer = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FARMER, false);
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
        makeDraggable(toggle, bar, barParams, this::onToggle, null);
        makeDraggable(add, bar, barParams, this::addTargetFromBar, null);
        makeDraggable(fullBuffButton, bar, barParams, this::onFullBuffButton, null);
        makeDraggable(manualButton, bar, barParams, this::onManualButton, null);
        makeDraggable(modeButton, bar, barParams, this::onModeButton, null);
        if (!safeAdd(bar, barParams)) {
            // Half connected (switched back on too soon after a crash): nothing will work until
            // the service is turned off and on again.
            Watchdog.requestRevive(this, "can't show its bar");
        }

        addTouchWatcher();
        setRunning(false, "connected");
        watchHandler.removeCallbacks(questionWatchTick);
        watchHandler.postDelayed(questionWatchTick, QUESTION_WATCH_MS);

        // Android kills background apps when the game uses most of the memory, then restarts
        // this service. If you had pressed ▶, carry on where it left off.
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setEndGame(prefs.getBoolean(KEY_END_GAME, true), "restored");
        setFarmer(farmer, "restored");
        setBooster(prefs.getBoolean(KEY_BOOSTER, false), "restored");
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
        closeModeChooser();
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
            // Attack rings go at a quick fixed pace; a tap during the game's skill lock is just ignored.
            tapGapMs = farmer ? FARM_TAP_GAP_MS : Math.max(0, prefs.getInt(KEY_TAP_GAP, DEFAULT_TAP_GAP_MS));
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
        movePending = false;
        chatArmed = true;
        chatAbsentScans = 0;
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
            if (run && !booster) handler.postDelayed(t.tick, 300);   // no target taps in booster
        }
        if (run) handler.post(this::cooldownCheck);                  // booster path does presence only
        if (run) handler.postDelayed(testMoveTick, TEST_MOVE_EVERY_MS);
        if (run && fsMode()) handler.postDelayed(chatScanTick, CHAT_SCAN_MS);   // chat-FB is FS only
        if (run && booster) handler.postDelayed(keyboardWatchTick, KEYBOARD_WATCH_MS);  // math only
        // Clearing the handler above also dropped the "game back yet?" check.
        updateOverlayVisibility();
        // End Game starts the cycle like the support does by hand: buff the party first, but only
        // if no fight is on (decided on the first monster count, see updateWave). Skipped when
        // this start came from a full buff (FB or the EG switch while stopped).
        if (run) farmMobsSeenAt = farmProgressAt = SystemClock.uptimeMillis();   // a moment before walking
        lootStartedAt = 0;                                          // its tap tick was cleared above
        startBuffPending = run && eg() && why.equals("button")
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
            t.root.setVisibility(on || overlaysHidden || booster ? View.GONE : View.VISIBLE);
        }
        int others = on ? View.GONE : View.VISIBLE;
        toggle.setVisibility(others);
        add.setVisibility(others);
        // FB and EG/LL only mean something in FS.
        fullBuffButton.setVisibility(on || !fsMode() ? View.GONE : View.VISIBLE);
        modeButton.setVisibility(on || !fsMode() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) manualButton.getLayoutParams();
        lp.topMargin = on ? 0 : dp(8);
        manualButton.setLayoutParams(lp);
        manualButton.setText(on ? "AUTO" : "✋");
        manualButton.setTextSize(on ? 12 : 20);
        manualButton.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        manualButton.setBackground(circle(on ? Color.rgb(40, 150, 60) : Color.rgb(120, 70, 170)));
        if (changed && why.equals("button")) {
            shake(manualButton);
        }
    }

    /**
     * FB: cast every buff ring back to back, as fast as the game allows, even if the buffs are
     * still up (someone asked for a full buff). The heal keeps priority in between. Covers all
     * rings with Smart buff on, so new buffs are included once they are set up that way.
     */
    private void fullBuff() {
        fullBuff(false);
    }

    /**
     * Casts the buffs back to back. With onlyLow (after a wave): the full set if any long buff is at
     * or below WAVE_BUFF_AT (50%), otherwise only the cast-last buff (Massive Haste). The party
     * clears a wave every ~2 min and the long buffs last ~4.5-5 min, so that's a full buff about
     * every 2nd wave and Massive Haste alone in between, instead of ~12-15 s without heals each wave.
     */
    private void fullBuff(boolean onlyLow) {
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
        } else if (onlyLow) {
            // Always one of two groups, never a single long buff on its own (the user's rule):
            // all of them if any long buff is low, otherwise just the cast-last one (Massive Haste,
            // ~40 s, gone after every wave). Not read yet (e.g. after a restart) counts as low.
            boolean anyLongLow = false;
            List<Target> lastOnly = new ArrayList<>();
            for (Target t : buffs) {
                if (t.castLast) {
                    lastOnly.add(t);
                } else if (!t.isSmart() || !t.buffKnown || !t.buffFound || t.buffFill <= WAVE_BUFF_AT) {
                    anyLongLow = true;
                }
            }
            if (!anyLongLow) {
                if (lastOnly.isEmpty()) {
                    Log.i(TAG, "wave buff: every buff is still above " + Math.round(WAVE_BUFF_AT * 100) + "%, nothing to cast");
                    lastFullBuffAt = 0;
                    return;
                }
                buffs = lastOnly;
            }
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
        StringBuilder which = new StringBuilder();
        for (Target t : buffs) which.append(which.length() > 0 ? "," : "").append(targets.indexOf(t) + 1);
        Log.i(TAG, "full buff: casting " + buffs.size() + " buffs back to back (targets " + which + ")");
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
        fullBuffButton.setText("✕"); // tap again to stop it
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
        if (!running || !t.isSmart() || eg()) return;
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
        if (pending.isEmpty()) return;
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
        if (movePending && next.priority && !fullBuffGoingOut()) {
            // The step waits for the moment the heal is due: the last heal's 3 ticks and any buff
            // cast are done by then, so moving cancels nothing. The heal goes right after it.
            movePending = false;
            testMove(now);
            schedulePump(busyUntil - now);
            return;
        }
        // Not in Farmer: there the selection is the monster being fought, and a self buff goes to
        // self anyway; dropping it would cancel the fight every time the buff is cast.
        if (next.smartBuff && targetSelected && !farmer && gameInFront()) {
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
        // Farmer: attack after attack at the quick pace, but after a buff let its cast finish - the
        // next attack 0.8 s later cancelled it (the user, 13:31).
        boolean quick = next.priority || (farmer && !next.isSmart());
        busyUntil = now + TAP_MS + (quick ? tapGapMs : farmer ? FARM_AFTER_BUFF_MS : AFTER_BUFF_GAP_MS);
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
            long wait = eg() ? FORCED_AFTER_HEAL_MS : Math.max(tapGapMs + t.extraGapMs, FORCED_AFTER_HEAL_MS);
            return Math.max(0, lastAnyTapAt + TAP_MS + wait - now);
        }
        boolean anyPriority = false;
        for (Target o : targets) if (o.priority) anyPriority = true;
        // Farmer: attacks keep the quick pace; a buff waits out the last skill's lock (its learned
        // extra wait), or it lands mid-animation and is ignored (12:32-12:33).
        if (farmer) {
            long extra = t.isSmart() ? Math.min(t.extraGapMs, FARM_BUFF_MAX_WAIT_MS) : 0;   // never stall attacks long
            // Right after another buff the game's lock is longer: Blood Lust 1.8 s after Power Kick
            // was swallowed twice (14:17-14:18).
            if (t.isSmart() && lastBuffTapAt > 0 && lastBuffTapAt == lastAnyTapAt) {
                extra = Math.max(extra, FARM_BUFF_AFTER_BUFF_MS - tapGapMs);
            }
            return Math.max(0, lastAnyTapAt + TAP_MS + tapGapMs + extra - now);
        }
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
        if (members <= 0) return;
        long now = SystemClock.uptimeMillis();
        if (members >= partySize) partySeenAt = now;
        if (members == partySize) return;
        // Joining counts at once. A lower reading only after it has lasted PARTY_SHRINK_MS: dead or
        // far-away members' bars can fail to read for a while (a party of 5 read 3 at 13:11:20).
        if (members < partySize && now - partySeenAt < PARTY_SHRINK_MS) return;
        partySeenAt = now;
        boolean first = partySize < 0;
        int oldStart = waveStartMobs();
        int oldEnd = waveEndMobs();
        partySize = members;
        // Only say so when the numbers it goes by change.
        if (first || waveStartMobs() != oldStart || waveEndMobs() != oldEnd) {
            Log.i(TAG, "party of " + members + ": wave at " + waveStartMobs() + "+ monsters, cleared at "
                    + waveEndMobs() + " or fewer");
        }
    }

    /** Big until a small party has been seen, so it behaves as before until the list is read. */
    private boolean bigParty() {
        return partySize < 0 || partySize >= BIG_PARTY;
    }

    private int waveStartMobs() {
        if (bigParty()) return WAVE_START_MOBS;
        return partySize == BIG_PARTY - 1 ? PARTY_OF_5_WAVE_START_MOBS : SMALL_WAVE_START_MOBS;
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
        Log.i(TAG, "wave cleared after " + lasted / 1000 + " s (~" + mobs + " left), "
                + (fullBuffOwed ? "resuming the full buff stopped when the wave came back" : "full buff if any long buff is low, else Massive Haste"));
        // fullBuff() reads and clears fullBuffOwed itself (a resume casts only the dropped buffs);
        // otherwise only the buffs that are low: Massive Haste every wave, the rest when they need it.
        fullBuff(true);
    }

    /**
     * A wave came back while a full buff was going out (the count dipped below 6 mid-fight): drop
     * the buffs still in line so the heal gets its slots again. The next clear buffs again.
     */
    private void stopFullBuff() {
        int dropped = dropFullBuff(true);
        if (dropped == 0) return;
        // Let the next clear start a full buff right away, not "already in progress", however
        // short the rest of the wave turns out to be.
        fullBuffOwed = true;
        Log.i(TAG, "wave is back: full buff stopped (" + dropped + " buffs not cast), healing");
    }

    /** FB button: starts a full buff, or stops the one going out (nothing is owed then). */
    private void onFullBuffButton() {
        if (!fullBuffGoingOut()) {
            fullBuff();
            return;
        }
        int dropped = dropFullBuff(false);
        fullBuffOwed = false;
        for (Target t : targets) t.owed = false;
        Log.i(TAG, "full buff stopped (button), " + dropped + " buffs not cast, healing");
    }

    /** Takes the full buff's buffs still in line out of it; returns how many. */
    private int dropFullBuff(boolean owe) {
        int dropped = 0;
        for (int i = pending.size() - 1; i >= 0; i--) {
            Target t = pending.get(i);
            if (!t.forced) continue;
            t.forced = false;
            if (owe) t.owed = true;
            pending.remove(i);
            handler.removeCallbacks(t.tick);
            handler.postDelayed(t.tick, SMART_RECHECK_MS);
            dropped++;
        }
        if (dropped == 0) return 0;
        lastFullBuffAt = 0;
        handler.removeCallbacks(resetFullBuffButton);
        resetFullBuffButton.run();
        schedulePump(0);
        return dropped;
    }

    /**
     * The game's "please click Confirm" panel: disconnects you if nobody answers in ~25 s. Alert
     * (sound, vibration, heads-up; on a linked phone too) the moment it shows, clear it once it's
     * gone. Answering is up to you.
     */
    private void updatePresenceCheck(boolean shown) {
        if (shown == presenceCheckShown) return;
        presenceCheckShown = shown;
        if (shown) {
            Log.w(TAG, "the game is asking if you're there (Move button): alerting you");
            Alerts.question(this, gamePackage != null ? gamePackage : DEFAULT_GAME,
                    "The game is checking if you're there. Tap Move within ~25 s or it disconnects you.");
            Telegram.send(this, "⚠️ Ran Online: the game is checking if you're there. "
                    + "Tap Move within ~25 s or it disconnects you.");
        } else {
            Log.i(TAG, "presence check gone");
            Alerts.clearQuestion(this);
        }
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
        refreshModeButton();
        for (Target t : targets) t.refreshLabel();
        if (changed && why.equals("button")) {
            shake(modeButton);
            // Switching to End Game starts the cycle like the support does by hand: buff the
            // party first, then they go lure. (Starts tapping too, like the FB button.)
            if (on) fullBuff();
        }
    }

    /** The mode button's label/colour for the current mode (EG, LL, or BOOST). */
    private void refreshModeButton() {
        if (booster) {
            modeButton.setText("BOOST");
            modeButton.setTextSize(11);
            modeButton.setBackground(circle(Color.rgb(150, 90, 30)));
        } else {
            modeButton.setText(endGame ? "EG" : "LL");
            modeButton.setTextSize(15);
            modeButton.setBackground(circle(endGame ? Color.rgb(170, 40, 40) : Color.rgb(40, 130, 130)));
        }
    }

    /** Mode button (FS only): toggle End Game / Low Level. BOOST is chosen on the ▶/AUTO chooser. */
    private void onModeButton() {
        setEndGame(!endGame, "button");
    }

    /** ▶/AUTO: when stopped, ask which mode to start in; when running, stop. */
    private void onToggle() {
        if (running) setRunning(false, "button");
        else showModeChooser(toggle);
    }

    /** ✋/AUTO: go manual, or (from manual) pick a mode to start in. */
    private void onManualButton() {
        if (manual) showModeChooser(manualButton);   // startInMode turns manual off and starts
        else setManual(true, "button");
    }

    /**
     * Small circles, FS, BOOST and FARM, right beside the button you tapped (on its left if the bar
     * sits at the right edge); the one tapped starts the clicker in that mode.
     */
    private void showModeChooser(View anchor) {
        closeModeChooser();
        closeEditor();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        int size = dp(40), gap = dp(6);
        TextView fs = roundButton("FS");
        fs.setTextSize(14);
        fs.setBackground(circle(Color.rgb(170, 40, 40)));
        TextView boost = roundButton("BOOST");
        boost.setTextSize(9);
        boost.setBackground(circle(Color.rgb(150, 90, 30)));
        TextView farm = roundButton("FARM");
        farm.setTextSize(10);
        farm.setBackground(circle(Color.rgb(60, 120, 40)));
        TextView[] choices = {fs, boost, farm};
        fs.setOnClickListener(v -> startInMode(false, false));
        boost.setOnClickListener(v -> startInMode(true, false));
        farm.setOnClickListener(v -> startInMode(false, true));
        for (int i = 0; i < choices.length; i++) {
            choices[i].setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.leftMargin = gap;
            panel.addView(choices[i], lp);
        }

        WindowManager.LayoutParams p = overlayParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int panelW = choices.length * size + (choices.length - 1) * gap;
        int right = at[0] + anchor.getWidth() + gap;
        p.x = right + panelW <= getResources().getDisplayMetrics().widthPixels
                ? right : at[0] - gap - panelW;
        p.y = at[1] + (anchor.getHeight() - size) / 2;
        p.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        panel.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) closeModeChooser();
            return false;
        });
        if (safeAdd(panel, p)) modeChooser = panel;
    }

    private void startInMode(boolean boostMode, boolean farmMode) {
        closeModeChooser();
        setFarmer(farmMode, "button");              // swaps in that mode's rings
        setBooster(boostMode, "button");            // sets ring/FB/mode-button visibility for the mode
        if (manual) setManual(false, "chooser");
        setRunning(true, "button");
    }

    private void closeModeChooser() {
        if (modeChooser != null) {
            safeRemove(modeChooser);
            modeChooser = null;
        }
    }

    /**
     * Booster mode on/off. Hides the rings and FB (no taps, no buffs), updates the button, and if
     * it's running re-starts the loop so the right ticks are scheduled for the new mode.
     */
    private void setBooster(boolean on, String why) {
        boolean changed = booster != on;
        booster = on;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_BOOSTER, on).apply();
        if (changed || !why.equals("restored")) {
            Log.i(TAG, "booster mode " + (on ? "on: jiggle + math only, no rings/buffs" : "off") + " (" + why + ")");
        }
        refreshModeButton();
        if (!manual) {
            fullBuffButton.setVisibility(fsMode() ? View.VISIBLE : View.GONE);
            modeButton.setVisibility(fsMode() ? View.VISIBLE : View.GONE);
        }
        for (Target t : targets) {
            t.root.setVisibility(on || manual || overlaysHidden ? View.GONE : View.VISIBLE);
        }
        if (changed && running) setRunning(true, "mode");   // reschedule ticks for the new mode
    }

    /**
     * Farmer mode on/off. Farmer has its own rings (attack skills), so switching saves the current
     * layout and loads the other one; the FS heal/buff rings come back untouched.
     */
    private void setFarmer(boolean on, String why) {
        boolean changed = farmer != on;
        if (changed) {
            closeEditor();
            if (running) setRunning(false, "mode");
            saveTargets();                          // under the outgoing mode's key
            for (Target t : targets) safeRemove(t.root);
            targets.clear();
            pending.clear();
            farmer = on;
            loadTargets();
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FARMER, on).apply();
        if (changed || !why.equals("restored")) {
            Log.i(TAG, "farmer mode " + (on ? "on: attack rings, walk when no monsters (" + targets.size()
                    + " rings)" : "off") + " (" + why + ")");
        }
        if (!manual) {
            fullBuffButton.setVisibility(fsMode() ? View.VISIBLE : View.GONE);
            modeButton.setVisibility(fsMode() ? View.VISIBLE : View.GONE);
        }
    }

    /** Full support (EG or LL): not booster, not farmer. FB, EG/LL and chat-FB only apply here. */
    private boolean fsMode() {
        return !booster && !farmer;
    }

    /** End Game FS rules in force (waves, full buffs only). Farmer runs its rings like Low Level. */
    private boolean eg() {
        return endGame && fsMode();
    }

    private String targetsKey() {
        return farmer ? KEY_TARGETS_FARM : KEY_TARGETS;
    }

    private boolean buffNeeded(Target t) {
        // End Game: buffs only go out in full buffs; every other slot is a heal.
        if (!t.forced && eg()) return false;
        return buffBelowRecast(t);
    }

    /**
     * Priority rings go first; everything else in the order it came due. A buff that a heal
     * already went ahead of gets the next turn, and the heal waits for it once, otherwise a buff
     * needing a long wait after the heal would never fit between two heals.
     */
    private Target pickNext(long now) {
        // Farmer: a buff that came due goes before the attacks waiting in line; queued behind six
        // attack rings it lost a whole rotation (~8 s) and the short eye buff ran out (12:35:28).
        if (farmer) for (Target t : pending) if (t.isSmart()) return t;
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

    private void testMoveTick() {
        handler.postDelayed(testMoveTick, TEST_MOVE_EVERY_MS);
        if (!running) return;
        if (booster) {
            // No heals to coordinate with: jiggle straight away, if the game's in front and the
            // keyboard isn't up (don't jiggle into the keyboard or another app).
            if (boosterCanAct()) testMove(SystemClock.uptimeMillis());
            return;
        }
        movePending = true;
        schedulePump(0);
    }

    /** In booster/farmer: the game is in front, in landscape, and no keyboard is up. */
    private boolean boosterCanAct() {
        if (keyboardShowing()) return false;
        DisplayMetrics real = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(real);
        if (real.widthPixels < real.heightPixels) return false;   // portrait (e.g. the install screen)
        if (gamePackage == null) return true;
        String front = foregroundPackage();
        return front == null || gamePackage.equals(front);
    }

    /**
     * Booster only: with no tap loop to notice the keyboard, watch for it here. When the game opens
     * it by itself (the math question), answer it; clear state when it closes.
     */
    private void keyboardWatchTick() {
        if (!running || !booster) return;
        handler.postDelayed(keyboardWatchTick, KEYBOARD_WATCH_MS);
        boolean kb = keyboardShowing();
        if (kb && !pausedForKeyboard) {
            pausedForKeyboard = true;
            Log.i(TAG, "booster: keyboard opened");
            keyboardOpened();
        } else if (!kb && pausedForKeyboard) {
            pausedForKeyboard = false;
            if (keyboardAlerted) Alerts.clearQuestion(this);
            keyboardAlerted = false;
            mathBusy = false;
        }
    }

    /** TEST: a tiny left-then-right jiggle, so the character ends where it started. */
    private void testMove(long now) {
        Log.i(TAG, "test move: left then right (in place), " + (now - lastPriorityTapAt)
                + " ms after the last heal, " + (now - lastAnyTapAt) + " ms after the last cast");
        float dx = screenW * JOYSTICK_PUSH;
        // Cover the whole left + gap + right window, so the heal waits for it and our own pushes
        // aren't mistaken for your touch.
        long window = 2L * MOVE_MS + MOVE_RETURN_GAP_MS;
        ownTapUntil = now + window + OWN_TAP_SLACK_MS;
        busyUntil = now + window + MOVE_SETTLE_MS;
        joystickPush(-dx);                                              // left
        handler.postDelayed(() -> joystickPush(dx), MOVE_MS + MOVE_RETURN_GAP_MS);  // right, back to the spot
    }

    /** Every few seconds: read the chat log; if a new message asks for buffs, cast a full buff. */
    private void chatScanTick() {
        if (!running) return;
        handler.postDelayed(chatScanTick, CHAT_SCAN_MS);
        if (manual || !canReadScreen() || keyboardShowing()) return;   // typing/math owns the screen
        if (gamePackage != null && !gamePackage.equals(foregroundPackage())) return;
        // Copy only the chat corner (~1.4 MB), not the whole 16 MB screen, so scanning often is cheap.
        captureRegionForOcr(CHAT_L, CHAT_T, CHAT_W, CHAT_H, chat -> {
            if (chat != null) Ocr.read(chat, (lines, words) -> checkChatForBuffRequest(lines));
        });
    }

    private void checkChatForBuffRequest(List<MathQuestion.Line> lines) {
        if (!running) return;
        String hit = null;
        for (MathQuestion.Line line : lines) {
            String norm = line.text.toLowerCase(java.util.Locale.ROOT).trim();
            if (!norm.isEmpty() && asksForBuffs(norm)) {
                hit = line.text;
                break;
            }
        }
        if (hit != null) {
            chatAbsentScans = 0;
            if (chatArmed) {                 // rising edge: a request just appeared
                chatArmed = false;
                Log.i(TAG, "chat asked for a full buff: \"" + hit + "\" -> FB");
                fullBuff();
            }
        } else if (++chatAbsentScans >= CHAT_REARM_SCANS) {
            chatArmed = true;                // the request has cleared; ready for the next one
        }
    }

    /** True if a chat line is asking for buffs: one of the phrases, or "fb" as its own word. */
    private static boolean asksForBuffs(String lower) {
        for (String w : CHAT_FB_WORDS) if (lower.contains(w)) return true;
        for (int i = lower.indexOf("fb"); i >= 0; i = lower.indexOf("fb", i + 1)) {
            boolean leftFree = i == 0 || !Character.isLetterOrDigit(lower.charAt(i - 1));
            boolean rightFree = i + 2 >= lower.length() || !Character.isLetterOrDigit(lower.charAt(i + 2));
            if (leftFree && rightFree) return true;
        }
        return false;
    }

    /**
     * Farmer: nothing to fight for FARM_IDLE_MS -> walk a step (E, N, W, S in turn) to find more.
     * "Fighting" is a target's HP bar showing at the top: monster names at some spots are white
     * (Brute Punk), which the red-name counter misses, so it walked away mid-fight.
     */
    private void farmCheck(int mobs, float targetHp, long now) {
        if (mobs < 0) return;
        if (mobs != lastMobCount) Log.d(TAG, "monsters: ~" + mobs);
        lastMobCount = mobs;
        boolean target = targetHp >= 0;
        // A kill (the bar went away): hold attacks a moment and look again for the loot hand.
        // Attacking on straight away auto-targeted the next monster and ran off from the drop,
        // and the hand then walked the character all the way back (the user, 12:55).
        if (!target && farmTargetHp >= 0 && lootStartedAt == 0 && now >= lootIgnoreUntil) {
            postKillUntil = now + POST_KILL_HOLD_MS;
            busyUntil = Math.max(busyUntil, postKillUntil);
        }
        // Stuck: the game keeps going for a monster it can't reach (behind a wall: "no clear line
        // ... walking in", 2026-10-04 11:29), so its HP never drops. Any HP change, a new target or
        // no target at all counts as progress.
        if (!target || Math.abs(targetHp - farmTargetHp) > 0.01f) farmProgressAt = now;
        farmTargetHp = targetHp;
        Log.v(TAG, "farm scan: monsters ~" + mobs + ", target " + (target ? Math.round(targetHp * 100) + "%" : "none"));
        long stuckAfter = targetHp >= 0.97f ? FARM_STUCK_FULL_MS : FARM_STUCK_MS;
        if (target && now - farmProgressAt >= stuckAfter && canFarmMove(now)) {
            Log.i(TAG, "farmer: target HP stuck at " + Math.round(targetHp * 100) + "% for "
                    + (now - farmProgressAt) / 1000 + " s, dropping it and stepping away");
            dropTargetAndStep(now);
            return;
        }
        // Only a target bar counts as fighting. Red name tags here are Caloyski, whom the game
        // auto-attacks without selecting (no bar) and can't reach: counting it as "monsters near"
        // kept the character there for minutes (12:11). No bar for a while = walk a step.
        if (target) {
            farmMobsSeenAt = now;
            return;
        }
        if (now - farmMobsSeenAt < FARM_IDLE_MS || !canFarmMove(now)) return;
        Log.i(TAG, "farmer: no target for " + (now - farmMobsSeenAt) / 1000 + " s (monsters ~" + mobs
                + "), walking " + "ENWS".charAt(farmWalkStep));
        farmWalk(now);
    }

    /**
     * A monster name tag (white text on its dark box, e.g. "Brute Punk") within reach of the
     * character. The game often fights with no target bar (auto skills hit without selecting), so
     * the bar alone said "nothing here" and the character walked off mid-fight (13:36). Our own
     * name and item labels are yellow, the pet green, Caloyski red: none of them count.
     */
    private boolean monsterNameNear(List<MathQuestion.Line> lines, Bitmap crop, int ox, int oy) {
        float cx = screenW * 0.5f, cy = screenH * 0.53f;
        float reach = screenW * MONSTER_NEAR_W;
        for (MathQuestion.Line line : lines) {
            if (line.text.trim().length() < 4) continue;
            if (Math.hypot(line.box.exactCenterX() - cx, line.box.exactCenterY() - cy) > reach) continue;
            int l = Math.max(0, line.box.left - ox), t = Math.max(0, line.box.top - oy);
            int r = Math.min(crop.getWidth(), line.box.right - ox), b = Math.min(crop.getHeight(), line.box.bottom - oy);
            int white = 0, total = 0;
            for (int yy = t; yy < b; yy += 2) {
                for (int xx = l; xx < r; xx += 2) {
                    int c = crop.getPixel(xx, yy);
                    int mn = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                    int mx = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
                    total++;
                    if (mn > 185 && mx - mn < 35) white++;
                }
            }
            if (total > 0 && white * 100 >= total * 6) return true;
        }
        return false;
    }

    /** Drop the selected target (its bar's ✕) and walk a step, so the game picks another monster. */
    private void dropTargetAndStep(long now) {
        farmProgressAt = now;
        tapAt(screenW * MobCounter.CLOSE_X, screenH * MobCounter.CLOSE_Y, "drop target");
        busyUntil = farmHoldUntil = now + TAP_MS + DESELECT_SETTLE_MS + FARM_PUSH_MS + FARM_WALK_MS
                + FARM_WALK_SETTLE_MS;
        handler.postDelayed(() -> farmWalk(SystemClock.uptimeMillis()), TAP_MS + DESELECT_SETTLE_MS);
    }

    /**
     * Monsters Farmer won't fight: Caloyski (red name) keeps the character "walking in" to it
     * behind walls for minutes (2026-10-04 11:29 and 12:08; the HP rule only caught it after 46 s).
     * Its name on the target bar is read with the anti-bot text, so it's dropped within seconds.
     */
    private void checkTargetName(List<MathQuestion.Line> lines) {
        if (!running || !farmer || farmTargetHp < 0) return;
        for (MathQuestion.Line line : lines) {
            if (line.box.bottom > screenH * TARGET_NAME_MAX_Y) continue;     // the target bar's title
            String name = line.text.toLowerCase(java.util.Locale.ROOT);
            for (String skip : FARM_SKIP_NAMES) {
                if (!name.contains(skip)) continue;
                long now = SystemClock.uptimeMillis();
                if (!canFarmMove(now)) return;
                Log.i(TAG, "farmer: target is " + line.text.trim() + " (skipped monster), dropping it");
                dropTargetAndStep(now);
                return;
            }
        }
    }

    private boolean canFarmMove(long now) {
        // Not mid-walk/loot, and not during an attack tap (a new gesture would cancel it). The
        // attack lock (busyUntil) is ignored: attacks come so often it would never let go.
        // Nor during a buff's cast: a walk right after the buff tap cancelled it (13:36).
        return !questionSeen && now >= farmHoldUntil && now - lastAnyTapAt >= TAP_MS + 50
                && now - lastBuffTapAt >= TAP_MS + FARM_AFTER_BUFF_MS
                && now - userTouchAt >= USER_TOUCH_PAUSE_MS && boosterCanAct();
    }

    /** One step of the E, N, W, S walk; attacks hold off until it's done. */
    private void farmWalk(long now) {
        if (!running || !farmer) return;
        // Spiral outward (the maps are big, 13:34): legs of 1,1,2,2,3,3,4,4 steps, turning E N W S,
        // then start small again so it doesn't wander off for good.
        int[] dir = FARM_WALK_DIRS[farmWalkStep];
        if (++farmLegDone >= farmLegLen) {
            farmLegDone = 0;
            farmWalkStep = (farmWalkStep + 1) % FARM_WALK_DIRS.length;
            if (++farmTurns % 2 == 0) farmLegLen = farmLegLen >= FARM_MAX_LEG ? 1 : farmLegLen + 1;
        }
        farmMobsSeenAt = now;                       // look again after the step
        long window = FARM_PUSH_MS + FARM_WALK_MS;
        ownTapUntil = now + window + OWN_TAP_SLACK_MS;
        busyUntil = farmHoldUntil = now + window + FARM_WALK_SETTLE_MS;
        float push = screenW * FARM_PUSH;
        joystickHold(dir[0] * push, dir[1] * push, FARM_WALK_MS);
    }

    /**
     * Farmer's text reading, from the screenshot the fight check already took (each screenshot is
     * ~18 MB inside Android; separate ones for loot and the question ran the tablet out of memory
     * and Android itself restarted, 2026-10-04 11:43). The play-area crop holds both the ground
     * labels and the anti-bot panel's question line.
     */
    private void farmOcr(Bitmap shot) {
        int x = Math.round(screenW * READ_L), y = Math.round(screenH * READ_T);
        int w = Math.min(Math.round(screenW * READ_W), shot.getWidth() - x);
        int h = Math.min(Math.round(screenH * READ_H), shot.getHeight() - y);
        if (w <= 0 || h <= 0) return;
        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(shot, x, y, w, h);   // its own pixels: shot is recycled after
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "farmer: couldn't crop for reading: " + e);
            return;
        }
        String game = gamePackage != null ? gamePackage : DEFAULT_GAME;
        Ocr.read(crop, (lines, words) -> {
            try {
                for (MathQuestion.Line l : lines) l.box.offset(x, y);   // crop pixels -> screen pixels
                checkForQuestion(game, lines);
                checkTargetName(lines);
                if (monsterNameNear(lines, crop, x, y)) farmMobsSeenAt = SystemClock.uptimeMillis();
            } finally {
                crop.recycle();
            }
        }, false);
    }

    /**
     * The loot hand shows (an item lies nearby): stop attacking until it's picked up. Tapped in
     * between attacks, the next skill always overrode the pickup (the user's call, 12:15). So hold
     * every skill, let the last one finish animating, tap the hand, re-tap while it still shows,
     * and attack again once it's gone - or after LOOT_MAX_PAUSE_MS, then leave that item a while.
     */
    private void farmLootCheck(boolean handShowing, long now) {
        if (lootStartedAt > 0) {                            // a pickup is under way
            boolean gaveUp = now - lootStartedAt >= LOOT_MAX_PAUSE_MS;
            if (handShowing && !gaveUp) {
                // Still there: two more taps, then wait for the next screenshot to say it's still
                // showing. Tapping on blind hit the ground once it was gone ("empty-ground tap ->
                // target cleared", five times in a minute, 12:41).
                if (lootTapsLeft <= 0) {
                    lootTapsLeft = LOOT_TAPS_PER_LOOK;
                    handler.removeCallbacks(lootTapTick);
                    handler.post(lootTapTick);
                }
                return;
            }
            Log.i(TAG, "farmer: " + (handShowing ? "couldn't pick it up in " + LOOT_MAX_PAUSE_MS / 1000
                    + " s, leaving it" : "picked up") + ", attacking again");
            if (handShowing) {
                lootIgnoreUntil = now + LOOT_IGNORE_MS;
                // A few failures in a row with nothing picked up: the bag is full (12:55, the user).
                // Stop pausing the fight for loot for a while, and say so.
                if (++lootFailStreak >= LOOT_FAILS_TO_PAUSE) {
                    lootFailStreak = 0;
                    lootIgnoreUntil = now + LOOT_FULL_PAUSE_MS;
                    Log.w(TAG, "farmer: " + LOOT_FAILS_TO_PAUSE + " pickups failed in a row, inventory full?"
                            + " Not looting for " + LOOT_FULL_PAUSE_MS / 60_000 + " min");
                    Telegram.send(this, "🎒 Ran Online: looting paused for " + LOOT_FULL_PAUSE_MS / 60_000
                            + " min, items aren't being picked up (inventory full?). Still fighting.");
                }
            } else {
                lootFailStreak = 0;
            }
            lootStartedAt = 0;
            handler.removeCallbacks(lootTapTick);
            busyUntil = farmHoldUntil = now;
            farmMobsSeenAt = farmProgressAt = now;          // standing still to loot isn't idling
            schedulePump(0);
            return;
        }
        if (!handShowing || now < lootIgnoreUntil || questionSeen || !canFarmMove(now)) return;
        lootStartedAt = now;
        busyUntil = farmHoldUntil = now + LOOT_MAX_PAUSE_MS;    // no attacks, no walking meanwhile
        long wait = Math.max(0, lastAnyTapAt + TAP_MS + LOOT_AFTER_SKILL_MS - now);
        Log.i(TAG, "farmer: item nearby, pausing attacks to loot it");
        lootTapsLeft = LOOT_TAPS_PER_LOOK;
        handler.removeCallbacks(lootTapTick);
        handler.postDelayed(lootTapTick, wait);
    }

    /** Every 2 s; every second near a kill (low target HP, or just after one) to catch the loot. */
    private int farmScanMs() {
        if (lootStartedAt > 0) return LOOT_SCAN_MS;
        long now = SystemClock.uptimeMillis();
        boolean nearKill = now < postKillUntil || (farmTargetHp >= 0 && farmTargetHp <= KILL_SOON_HP);
        return nearKill ? KILL_SCAN_MS : FARM_SCAN_MS;
    }

    /** Taps the hand, and again every LOOT_RETAP_MS while the pickup is under way. */
    private void lootTapTick() {
        if (!running || !farmer || lootStartedAt == 0 || lootTapsLeft <= 0) return;
        lootTapsLeft--;
        tapAt(screenW * LOOT_HAND_X, screenH * LOOT_HAND_Y, "loot hand");
        handler.postDelayed(lootTapTick, LOOT_RETAP_MS);
    }

    /** Push the joystick from the centre by (dx, dy) and hold it there for holdMs, then release. */
    private void joystickHold(float dx, float dy, int holdMs) {
        if (!running) return;
        float cx = screenW * JOYSTICK_X;
        float cy = screenH * JOYSTICK_Y;
        Path out = new Path();
        out.moveTo(cx, cy);
        out.lineTo(cx + dx, cy + dy);
        GestureDescription.StrokeDescription push =
                new GestureDescription.StrokeDescription(out, 0, FARM_PUSH_MS, true);
        dispatchGesture(new GestureDescription.Builder().addStroke(push).build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                Path hold = new Path();
                hold.moveTo(cx + dx, cy + dy);
                hold.lineTo(cx + dx + 1, cy + dy);
                dispatchGesture(new GestureDescription.Builder()
                        .addStroke(push.continueStroke(hold, 0, holdMs, false)).build(), null, null);
            }
        }, null);
    }

    /** One brief joystick push from the centre by dx pixels, then released. */
    private void joystickPush(float dx) {
        if (!running) return;
        float cx = screenW * JOYSTICK_X;
        float cy = screenH * JOYSTICK_Y;
        Path path = new Path();
        path.moveTo(cx, cy);
        path.lineTo(cx + dx, cy);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, MOVE_MS))
                .build();
        dispatchGesture(g, null, null);
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
            if (!pausedForKeyboard) {
                Log.i(TAG, "paused: keyboard is open");
                keyboardOpened();
            }
            pausedForKeyboard = true;
            return false;
        }
        if (pausedForKeyboard) {
            Log.i(TAG, "keyboard closed, tapping again");
            pausedForKeyboard = false;
            if (keyboardAlerted) Alerts.clearQuestion(this);
            keyboardAlerted = false;
            mathBusy = false;
            showRingsAfterMath();
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

    /**
     * The keyboard came up over the game. If you didn't touch the screen just before (so it isn't
     * the chat you opened), the game opened it itself: that's the math question. Try to answer it;
     * if the question can't be read with confidence, fall back to alerting you.
     */
    private void keyboardOpened() {
        if (!running || manual) return;
        String game = gamePackage != null ? gamePackage : DEFAULT_GAME;
        if (!game.equals(foregroundPackage())) return;
        // Always read it: a math question must be answered even while you're touching the screen
        // (e.g. chatting in town). Whether it's really a question is decided by the OCR, not by a
        // "did you touch recently" guess — that guard used to block real questions in booster.
        Log.i(TAG, "keyboard up over the game: checking for a math question");
        answerMathQuestion(game);
    }

    /**
     * Read the question with OCR, work out the answer, and tap it in. It only acts when it's sure:
     * a clear "A op B", every digit key found on the keyboard, and a submit button. Anything
     * missing and it just alerts you (as before), never typing a guess.
     */
    private void answerMathQuestion(String game) {
        long now = SystemClock.uptimeMillis();
        if (mathBusy || now - lastMathAnswerAt < MATH_GAP_MS || !canReadScreen()) {
            if (!canReadScreen()) alertMathQuestion(game);
            return;
        }
        Rect kb = keyboardBounds();
        if (kb == null) {
            alertMathQuestion(game);
            return;
        }
        mathBusy = true;
        tryReadMath(game, kb.top, 1);
    }

    /**
     * One read attempt. Hides our ring overlays (so OCR doesn't read their digit labels), waits for
     * the panel (and any notification banner over it) to settle, screenshots, and reads. On a clean
     * read it taps the answer; if it can't read it, it tries again a few times before alerting.
     */
    private void tryReadMath(String game, int keyboardTop, int attempt) {
        hideRingsForMath();
        long wait = attempt == 1 ? MATH_SETTLE_MS : MATH_RETRY_MS;
        handler.postDelayed(() -> captureForOcr(shot -> Ocr.read(shot, (lines, words) -> {
            showRingsAfterMath();
            if (!running || !keyboardShowing()) {           // you handled it, or it's gone
                mathBusy = false;
                return;
            }
            MathQuestion.Plan plan = MathQuestion.solve(lines, words, keyboardTop);
            if (plan == null) {
                // No question found. If you opened the keyboard yourself (touched recently), it's
                // your chat, not a math question: stop quietly, no retry, no alert. Otherwise the
                // game opened it, so a notification may be hiding the question - retry, then alert.
                if (SystemClock.uptimeMillis() - userTouchAt < KEYBOARD_TOUCH_MS) {
                    mathBusy = false;
                    Log.i(TAG, "keyboard up, no question, you touched recently - leaving it (your chat)");
                    return;
                }
                if (attempt < MATH_MAX_ATTEMPTS) {
                    Log.i(TAG, "math read " + attempt + " failed (" + describeWords(words) + "), retrying");
                    tryReadMath(game, keyboardTop, attempt + 1);
                } else {
                    mathBusy = false;
                    Log.w(TAG, "couldn't read the math question after " + attempt + " tries; alerting you");
                    alertMathQuestion(game);
                }
                return;
            }
            mathBusy = false;
            lastMathAnswerAt = SystemClock.uptimeMillis();
            Log.i(TAG, "answering the math question: " + plan.answer + " (read " + attempt + ")");
            typeAnswer(plan);
            Telegram.send(this, "🧮 Ran Online asked a math question; I answered " + plan.answer
                    + ". Double-check it if you can.");
        })), wait);
    }

    /** Taps each digit key in turn (never two at once), then the submit button. */
    private void typeAnswer(MathQuestion.Plan plan) {
        long delay = 0;
        for (Rect key : plan.digitKeys) {
            Rect k = key;
            handler.postDelayed(() -> tapAt(k.exactCenterX(), k.exactCenterY(), "math key"), delay);
            delay += MATH_TAP_GAP_MS;
        }
        Rect submit = plan.submit;
        handler.postDelayed(() -> tapAt(submit.exactCenterX(), submit.exactCenterY(), "math submit"),
                delay + MATH_TAP_GAP_MS);
    }

    /** Hide the target rings so OCR (and the math taps) don't catch their digit labels. */
    private void hideRingsForMath() {
        for (Target t : targets) if (t.root != null) t.root.setVisibility(View.GONE);
    }

    /** Bring the rings back after answering, unless we're in manual, booster, or off the game. */
    private void showRingsAfterMath() {
        if (manual || booster || overlaysHidden) return;
        for (Target t : targets) if (t.root != null) t.root.setVisibility(View.VISIBLE);
    }

    private void alertMathQuestion(String game) {
        keyboardAlerted = true;
        Log.w(TAG, "math question: alerting you");
        Alerts.question(this, game, "The game is asking a question (an answer box is open). Answer it in the game.");
        Telegram.send(this, "⚠️ Ran Online: a math question is waiting for you (an answer box opened). "
                + "Answer it in the game.");
    }

    /** Where the on-screen keyboard sits (screen pixels), or null if it isn't up. */
    private Rect keyboardBounds() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    return r;
                }
            }
        } catch (RuntimeException ignored) {
            // windows changed while asking
        }
        return null;
    }

    /** Every few seconds while the game is in front: is the (new, 4-button) math question up? */
    private void questionWatchTick() {
        watchHandler.postDelayed(questionWatchTick, QUESTION_WATCH_MS);
        if (running && farmer) return;           // Farmer reads it from its own screenshots
        if (!canReadScreen()) return;
        String game = gamePackage != null ? gamePackage : DEFAULT_GAME;
        if (!game.equals(foregroundPackage())) return;
        // From the top-left corner, so OCR boxes are already screen pixels.
        captureRegionForOcr(0f, 0f, 1f, QUESTION_SCAN_H, top -> {
            if (top != null) Ocr.read(top, (lines, words) -> checkForQuestion(game, lines));
        });
    }

    private void checkForQuestion(String game, List<MathQuestion.Line> lines) {
        List<MathQuestion.Line> hits = new ArrayList<>();
        for (MathQuestion.Line line : lines) {
            String norm = line.text.toLowerCase(java.util.Locale.ROOT);
            for (String w : QUESTION_WATCH_WORDS) {
                if (norm.contains(w)) {
                    hits.add(line);
                    break;
                }
            }
        }
        long now = SystemClock.uptimeMillis();
        if (hits.isEmpty()) {
            if (questionSeen && ++questionAbsentScans >= 2) {
                questionSeen = false;
                Log.i(TAG, "question watch: question gone after " + (now - questionSeenAt) / 1000 + " s");
                Alerts.clearQuestion(this);
            }
            return;
        }
        questionAbsentScans = 0;
        StringBuilder text = new StringBuilder();
        for (MathQuestion.Line l : hits) text.append(text.length() > 0 ? " / " : "").append(l.text);
        if (!questionSeen) {
            questionSeen = true;
            questionSeenAt = now;
            questionTaps = 0;
            questionAlerted = false;
            Log.i(TAG, "question watch: SEEN \"" + text + "\"");
            for (MathQuestion.Line l : lines) {
                Log.i(TAG, "question watch: line \"" + l.text + "\" at " + l.box.toShortString());
            }
            saveQuestionShot();
        }
        // Answer it: tap the one button showing the answer. Only while the clicker runs (in manual
        // you're playing), a few tries at most, and never a guess: unsure -> alert you instead.
        MathQuestion.Choice choice = running && !manual ? MathQuestion.solveChoice(lines) : null;
        if (choice != null && questionTaps < QUESTION_MAX_TAPS && now - lastQuestionTapAt >= QUESTION_RETAP_MS) {
            questionTaps++;
            lastQuestionTapAt = now;
            Log.i(TAG, "question watch: answering " + choice.answer + " -> \"" + choice.label + "\" at "
                    + choice.button.toShortString() + " (tap " + questionTaps + ")");
            busyUntil = Math.max(busyUntil, now + QUESTION_TAP_HOLD_MS);   // no attack tap in between
            long wait = Math.max(0, lastAnyTapAt + TAP_MS + 60 - now);      // not on top of one either
            Rect b = choice.button;
            handler.postDelayed(() -> tapAt(b.exactCenterX(), b.exactCenterY(), "question answer"), wait);
            if (questionTaps == 1) {
                Telegram.send(this, "🧮 Ran Online anti-bot check: \"" + text + "\" -> tapped "
                        + choice.label + ". Double-check it if you can.");
            }
            return;
        }
        if (!questionAlerted && (choice == null || questionTaps >= QUESTION_MAX_TAPS)) {
            questionAlerted = true;
            Log.w(TAG, "question watch: not answering (" + (choice == null ? "couldn't read it surely"
                    : "still up after " + questionTaps + " taps") + "), alerting you");
            Alerts.question(this, game, "The game is asking a question. Answer it in the game.");
            Telegram.send(this, "⚠️ Ran Online anti-bot check is waiting for you: \"" + text
                    + "\". Answer it in the game.");
        }
    }

    /** Full screenshot of the question to a PNG (app files dir), then every word on it to the log. */
    private void saveQuestionShot() {
        captureForOcr(shot -> {
            if (shot == null) return;
            java.io.File dir = getExternalFilesDir(null);
            String name = "question-" + new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.ROOT)
                    .format(new java.util.Date()) + ".png";
            new Thread(() -> {
                if (dir != null) {
                    java.io.File f = new java.io.File(dir, name);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                        shot.compress(Bitmap.CompressFormat.PNG, 100, out);
                        Log.i(TAG, "question watch: saved " + f);
                    } catch (java.io.IOException e) {
                        Log.w(TAG, "question watch: couldn't save the screenshot: " + e);
                    }
                }
                watchHandler.post(() -> Ocr.read(shot, (lines, words) -> {
                    for (MathQuestion.Word w : words) {
                        Log.i(TAG, "question watch: word \"" + w.text + "\" at " + w.box.toShortString());
                    }
                }));
            }, "question-shot").start();
        });
    }

    /** The words OCR read, for the log when a question couldn't be parsed. */
    private static String describeWords(List<MathQuestion.Word> words) {
        StringBuilder sb = new StringBuilder();
        for (MathQuestion.Word w : words) {
            sb.append(w.text).append('|');
            if (sb.length() > 200) break;
        }
        return sb.toString();
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
        // Never shorten it: a loot/walk gesture may have set it further out (a tap there used to
        // cut it short, so the joystick walk looked like your finger and paused Farmer 4 s).
        ownTapUntil = Math.max(ownTapUntil, SystemClock.uptimeMillis() + TAP_MS + OWN_TAP_SLACK_MS);
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
        if (booster) {
            // Booster does no buff/cooldown work; the only screenshot it needs is the presence check
            // (the "are you there? / Move" panel), and it can be slow.
            if (canReadScreen()) {
                captureScreen(shot -> {
                    if (!running || !booster) return;
                    if (keyboardShowing() || (gamePackage != null && !gamePackage.equals(foregroundPackage()))) return;
                    updatePresenceCheck(Prompts.presenceCheck(shot, screenW, screenH));
                });
            }
            handler.postDelayed(this::cooldownCheck, BOOSTER_PRESENCE_MS);
            return;
        }
        boolean anyCooldown = false;
        boolean anySmart = false;
        for (Target t : targets) {
            if (t.smartBuff) anySmart = true;
            // A smart buff's cooldown lasts far longer than a scan; only plain rings need 0.35 s.
            if (t.waitForCooldown && t.readyLook != null && !t.smartBuff) anyCooldown = true;
        }
        boolean anyWatching = anyCooldown || anySmart || farmer;   // farmer counts monsters
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
                if (eg() && scanBuffs && now - lastMobCountAt >= BUFF_SCAN_EVERY_MS - 100) {
                    lastMobCountAt = now;
                    updateParty(MobCounter.partySize(shot, screenW, screenH));
                    updateWave(MobCounter.count(shot, screenW, screenH));
                }
                if (farmer && now - lastMobCountAt >= farmScanMs() - 100) {
                    lastMobCountAt = now;
                    // Loot first: a walk started by the "no target" rule left the drops behind (13:33).
                    farmLootCheck(MobCounter.lootHandShowing(shot, screenW, screenH), now);
                    farmCheck(MobCounter.count(shot, screenW, screenH),
                            MobCounter.targetHp(shot, screenW, screenH), now);
                    if (now - lastFarmOcrAt >= FARM_OCR_MS - 100) {
                        lastFarmOcrAt = now;
                        farmOcr(shot);
                    }
                }
                if (scanBuffs) targetSelected = MobCounter.targetSelected(shot, screenW, screenH);
                updatePresenceCheck(Prompts.presenceCheck(shot, screenW, screenH));
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
        handler.postDelayed(this::cooldownCheck,
                // Farmer never goes faster, even with cooldown rings: screenshots every 0.35 s would
                // risk the memory freeze Android had at 11:43 and 12:37.
                farmer ? farmScanMs() : anyCooldown ? SCREENSHOT_EVERY_MS : BUFF_SCAN_EVERY_MS);
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
        // Same for "not found at all": a buff seen well above its threshold moments ago can't
        // be gone (rings 7-9 read 100/88/88% then all "not active" 10 s later and were recast at
        // ~80%, 13:43). The row was misread; keep the last good reading.
        if (icon == null && now - t.lastSeenAt < MISSING_GLITCH_MS && t.lastSeenFill >= t.recastAt + 0.25f) return;
        if (icon != null) {
            t.lastSeenAt = now;
            t.lastSeenFill = icon.fill;
        }
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
                    + (!needed ? ", ok" : eg() ? ", left for the next full buff" : ", recasting"));
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
     * A full-screen readable copy for OCR. Unlike captureScreen it does NOT recycle the bitmap:
     * reading is asynchronous, so ownership passes to the callback (Ocr recycles it when done).
     * onShot gets null if a screenshot couldn't be taken.
     */
    private void captureForOcr(Consumer<Bitmap> onShot) {
        if (!canReadScreen()) {
            onShot.accept(null);
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                buffer.close();
                if (hw == null) {
                    onShot.accept(null);
                    return;
                }
                screenW = hw.getWidth();
                screenH = hw.getHeight();
                Bitmap shot = hw.copy(Bitmap.Config.ARGB_8888, false);
                hw.recycle();
                onShot.accept(shot);
            }

            @Override
            public void onFailure(int errorCode) {
                onShot.accept(null);
            }
        });
    }

    /**
     * Like captureForOcr, but copies only a fractional region of the screen (l,t,w,h as 0..1), so a
     * small area like the chat log is cheap to grab often. The callback owns the bitmap (OCR recycles
     * it), or gets null if nothing could be read.
     */
    private void captureRegionForOcr(float fl, float ft, float fw, float fh, Consumer<Bitmap> onShot) {
        if (!canReadScreen()) {
            onShot.accept(null);
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                buffer.close();
                if (hw == null) {
                    onShot.accept(null);
                    return;
                }
                screenW = hw.getWidth();
                screenH = hw.getHeight();
                Bitmap shot = null;
                try {
                    int x = Math.round(screenW * fl), y = Math.round(screenH * ft);
                    int w = Math.min(Math.round(screenW * fw), screenW - x);
                    int h = Math.min(Math.round(screenH * fh), screenH - y);
                    if (x >= 0 && y >= 0 && w > 0 && h > 0) {
                        Bitmap part = Bitmap.createBitmap(hw, x, y, w, h);
                        shot = part.copy(Bitmap.Config.ARGB_8888, false);
                        if (part != hw) part.recycle();
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "couldn't copy the region: " + e);
                }
                hw.recycle();
                onShot.accept(shot);
            }

            @Override
            public void onFailure(int errorCode) {
                onShot.accept(null);
            }
        });
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
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(targetsKey(), sb.toString()).apply();
    }

    private void loadTargets() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(targetsKey(), "");
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
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        checkGameDialog(event);
        updateOverlayVisibility();
    }

    /**
     * Disconnect and crash dialogs are plain Android dialogs, so their text can be read. The kick
     * after an unanswered presence check (2026-10-02 14:12) showed "DGames Mobile: The game had to
     * stop. Please close the app and reopen it." over the game; Android's own crash dialog says
     * "... keeps stopping" or "... has stopped".
     */
    private void checkGameDialog(AccessibilityEvent event) {
        String game = gamePackage != null ? gamePackage : DEFAULT_GAME;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        boolean fromGame = pkg.toString().equals(game);
        // lastForeground is still the app in front before this window (updated right after).
        boolean overGame = fromGame || game.equals(lastForeground);
        if (!overGame) return;
        String text = windowText(event).trim();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String what;
        if (fromGame && (lower.contains("had to stop") || lower.contains("reopen") || lower.contains("disconnect"))) {
            what = "🔴 Ran Online disconnected: \"" + text + "\" Reopen it and log in again.";
        } else if (lower.contains("keeps stopping") || lower.contains("has stopped") || lower.contains("isn't responding")) {
            what = "🔴 Ran Online crashed: \"" + text + "\" Reopen it.";
        } else {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastGameAlertAt < GAME_ALERT_GAP_MS) return;
        lastGameAlertAt = now;
        Log.w(TAG, "game dialog (" + pkg + "): " + text + ", telling you on Telegram");
        Telegram.send(this, what);
    }

    /** The text of the window an event came from: its own text plus the first nodes of the window. */
    private String windowText(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        for (CharSequence c : event.getText()) sb.append(c).append(' ');
        try {
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) collectText(src, sb, new int[]{60});
        } catch (RuntimeException ignored) {
            // the window went away while reading it
        }
        return sb.toString();
    }

    private static void collectText(AccessibilityNodeInfo node, StringBuilder sb, int[] budget) {
        if (node == null || budget[0]-- <= 0) return;
        if (node.getText() != null) sb.append(node.getText()).append(' ');
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), sb, budget);
    }

    /**
     * The game closed, crashed or was switched away from while the auto clicker was running (not
     * in ✋ manual): after a minute without it on screen, say so on Telegram. Android doesn't tell
     * an app whether another app is closed or just in the background, so the message says both.
     */
    private void checkGameGone() {
        long now = SystemClock.uptimeMillis();
        if (!overlaysHidden || !running || manual) {
            gameHiddenSince = 0;
            gameGoneAlerted = false;
            return;
        }
        if (gameHiddenSince == 0) gameHiddenSince = now;
        if (gameGoneAlerted || now - gameHiddenSince < GAME_GONE_ALERT_MS) return;
        gameGoneAlerted = true;
        // A disconnect dialog was just reported: the game closing after it is no news.
        if (now - lastGameAlertAt < GAME_ALERT_GAP_MS) return;
        lastGameAlertAt = now;
        Log.w(TAG, "game gone for a minute (" + lastForeground + " in front), telling you on Telegram");
        Telegram.send(this, "🟠 Ran Online has been off the screen for 1 min (closed, crashed, or another app "
                + "is in front: " + lastForeground + "). The auto clicker is paused.");
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
                for (Target t : targets) t.root.setVisibility(show && !manual && !booster ? View.VISIBLE : View.GONE);
                Log.i(TAG, show ? "game in front: showing the bar" : "hidden while " + front + " is in front");
            }
        }
        checkGameGone();
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
        watchHandler.removeCallbacksAndMessages(null);
        if (wm != null) removeOverlays("unbind");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        watchHandler.removeCallbacksAndMessages(null);
        if (wm != null) removeOverlays("destroy");
        super.onDestroy();
    }
}
