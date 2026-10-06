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
    // Follow mode (the user, 2026-10-05; the game has no follow of its own): no skills, just stay
    // close to the party master - the first row (M) of the Team list - by finding their name tag
    // on screen and walking toward it. Rides on BOOST mode (no rings, math answered, jiggle).
    private static final String KEY_FOLLOW = "follow";
    private boolean follow;
    private static final int FOLLOW_TICK_MS = 2000, FOLLOW_LOST_ALERT_MS = 60_000, FOLLOW_LOST_STEPS = 4;
    private static final float FOLLOW_NEAR_W = 0.12f;          // "close": ~3-4 character widths
    private final Runnable followTick = this::followTick;
    private String leaderKey, leaderShown;
    private long followHoldUntil, leaderSeenAt, lastFollowWalkAt;
    private float leaderDirX, leaderDirY, lastWalkLeaderDist;
    private int followLostSteps, followSideSign = 1;
    private boolean followLostAlerted, followNoPartyLogged;
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
    // Walls: the scene before a walk vs after it. If the camera hardly moved, the character walked
    // into a wall (the user, 15:19: lure walks pushed into one for a whole round), so turn.
    private int[] lastSceneThumb, walkStartThumb;
    private int wallsInARow;
    // A game panel covering the screen (a stray tap opened the map, 15:29): its X at the top right
    // closes it. With nothing open that X opens the Server List menu instead, so only tap it when
    // the HUD is gone twice in a row; a second tap closes that menu if it was opened by mistake.
    private int hudHiddenScans, panelCloseTries;
    private long hudHiddenSince, lastOcrResultAt;
    private static final float PANEL_X_X = 2340 / 2560f, PANEL_X_Y = 42 / 1600f;
    private static final int PANEL_MAX_TRIES = 3;
    // Luring with nothing in range: head for where monsters were last seen (names at the screen
    // edge, e.g. during the fight), else explore in straight lines - the spiral kept going back
    // over the same empty ground ("just random walks", the user, 15:28).
    private float seenDirX, seenDirY;
    private long monstersSeenFarAt;
    private int exploreSteps;
    private static final int LURE_SEEN_FRESH_MS = 15_000, LURE_EXPLORE_TURN_STEPS = 3;
    // Search steps are short: a 4 s leg toward a monster overshot it and ran into walls (the
    // user, 17:58). A step toward one only has to bring it into tappable space; a heading that
    // hit a wall is avoided for LURE_BLOCKED_MS.
    private static final int LURE_TOWARD_MS = 1500, LURE_EXPLORE_MS = 2500, LURE_BLOCKED_MS = 15_000;
    private static final int WALL_SCENE_DIFF_SHORT = 6;
    private float walkDirX, walkDirY, blockedDirX, blockedDirY;
    private long blockedUntil;
    private boolean lastWalkShort;
    private static final int WALL_SCENE_DIFF = 8;
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
    // 800 ms: the game accepted 62% of the taps (08:36, cooldowns/locks), each refusal costing 0.84 s.
    private static final int FARM_TAP_GAP_MS = 500;
    // Buffs in Farmer: hold attacks this long after a buff so its cast isn't cancelled; wait at most
    // FARM_BUFF_MAX_WAIT_MS before one; a cast that didn't take is retried after FARM_BUFF_RETRY_MS.
    private static final int FARM_AFTER_BUFF_MS = 1500, FARM_BUFF_MAX_WAIT_MS = 1500, FARM_BUFF_RETRY_MS = 15_000;
    private static final int FARM_BUFF_AFTER_BUFF_MS = 2500;
    // Loot: the hand button beside F1 picks up everything nearby (the user's pick, 2026-10-04,
    // after walking to gold labels kept stopping short and attacks pulled the character away).
    // While the hand shows, attacks pause until it's picked up (farmLootCheck).
    private static final float LOOT_HAND_X = 1735 / 2560f, LOOT_HAND_Y = 1430 / 1600f;
    private static final int LOOT_MAX_PAUSE_MS = 12_000, LOOT_RETAP_MS = 700, LOOT_IGNORE_MS = 8000;
    // Pickups take 3-4 s; an item the game won't hand over kept it tapping for 10 s with monsters
    // around (08:25:19-30, "stuck and didn't loot"). Give up LOOT_STALL_MS after the last pickup
    // (the chat says "Pick up item"/"Gained gold"; read every LOOT_CHAT_MS while looting).
    private static final int LOOT_STALL_MS = 5000, LOOT_CHAT_MS = 1500;
    private long lastPickupAt;
    // Not faster while looting: 1 s screenshots under memory pressure preceded Android's own
    // system process hanging and restarting (12:35-12:37, watchdog kill), as at 11:43.
    private static final int LOOT_SCAN_MS = 1000;
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
    private static final int KILL_SCAN_MS = 1000, POST_KILL_HOLD_MS = 6000;     // a cap; the drop labels decide earlier
    private long postKillUntil;
    // The drop lands where the monster died, sometimes just outside the hand's reach: the hand only
    // showed ~2.5 s after attacks resumed, as the character ran past it (07:01-07:07). So the
    // target's name tag is read near the end of a fight, and with no hand LOOT_STEP_AFTER_MS after
    // the kill the character steps to that spot first.
    private float killSpotX, killSpotY;
    private long killSpotAt, killAt;
    private boolean killStepDone;
    private long lastHandSeenAt;
    // Drops: after a kill, look for the drop's label on the ground (a gold amount like "367", or an
    // item name - learned from the loot chat, or a typical loot word). Seen: walk to it until the hand
    // shows. None by DROP_DECIDE_MS: nothing dropped, attack at once. Attacking "right away" walked
    // off from drops whose hand hadn't shown yet (the user, 07:34).
    private static final int DROP_DECIDE_MS = 1500, DROP_MAX_STEPS = 3;
    private static final float KILL_MAX_HP = 0.6f;
    private static final String KEY_LOOT_NAMES = "farm_loot_names";
    private static final String[] LOOT_WORDS = {"potion", "burr", "box", "scroll", "card", "ore", "stone",
            "gem", "crystal", "protection", "coin", "gold", "elixir", "pill", "ticket", "chest"};
    private final java.util.Set<String> knownLoot = new java.util.HashSet<>();
    private float dropX, dropY;
    // Screen text that only looked like a drop (the "10" on the A quick slot read as gold, 08:03:
    // three walks away from the monsters after every kill). A label whose distance doesn't change
    // while walking to it is part of the screen, not the ground: that spot is ignored from then on.
    private final List<Rect> staticLabels = new ArrayList<>();
    private Rect dropBox;
    private String dropText;
    private float dropFirstD;
    private long dropStepAt;
    private long dropSeenAt, postKillOcrAt;
    private int dropSteps;
    // Camera: one-finger drag across empty ground turns it (the user; a 400 px drag turned the view
    // ~60-90 deg, 07:23). Turned when buildings hide things: the target's name tag unreadable
    // CAMERA_TAG_MISSES reads in a row, or two walks in a row into walls.
    private static final float CAMERA_DRAG_W = 300 / 2560f, CAMERA_DRAG_Y = 0.26f, CAMERA_DRAG_X = 0.40f;
    private static final int CAMERA_DRAG_MS = 400, CAMERA_TURN_GAP_MS = 20_000, CAMERA_TAG_MISSES = 3;
    private long lastCameraTurnAt;
    private int tagMissStreak;
    private static final int LOOT_STEP_AFTER_MS = 1500, KILL_SPOT_FRESH_MS = 5000;
    // Loot report: the chat box prints "Pick up item 'X'." and "Gained 'N' gold."; read it from the
    // fight screenshot and send a Telegram summary every LOOT_REPORT_MS (the user, 2026-10-05).
    private static final float LOOT_CHAT_L = 0.255f, LOOT_CHAT_T = 0.745f, LOOT_CHAT_W = 0.38f, LOOT_CHAT_H = 0.225f;
    private static final int LOOT_REPORT_MS = 5 * 60_000;
    private final java.util.LinkedHashMap<String, Integer> lootItems = new java.util.LinkedHashMap<>();
    private long lootGold, lootReportFrom;
    private List<String> lastChatLines = new ArrayList<>();
    private boolean chatPrimed;
    private final Runnable lootReportTick = this::lootReportTick;
    private static final java.util.regex.Pattern PICKUP_LINE =
            java.util.regex.Pattern.compile("up\\s*item\\s*\\W*(.+?)\\W*$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern GOLD_LINE =
            java.util.regex.Pattern.compile("ined\\W*([\\d,.]+)\\W*gold", java.util.regex.Pattern.CASE_INSENSITIVE);
    // Buffs wait while fighting: a target bar on the last scan, a monster name close by in the
    // last NEAR_TAG_FIGHT_MS, a pickup or the post-kill pause. After FARM_BUFF_HOLD_MAX_MS of
    // fighting in a row they get a FARM_BUFF_WINDOW_MS window, so stragglers can't starve them.
    private static final int NEAR_TAG_FIGHT_MS = 5000, FARM_BUFF_HOLD_MAX_MS = 45_000, FARM_BUFF_WINDOW_MS = 12_000;
    private long buffsHeldSince, buffWindowUntil, nearTagAt;
    // When a fight ends: a full buff - every buff at or below FARM_TOPUP_AT (or gone) back to back,
    // so all of them start the next fight well above the 20% recast line (the user, 2026-10-05:
    // with buffs held mid-fight, the ~6 s spacing let only one go per break and the rest ran low).
    private static final float FARM_TOPUP_AT = 0.3f;    // 0.5 doubled the casts (13% of the time, 07:28-07:31)
    private static final int FARM_BUFF_GIVEUP_FAILS = 3, FARM_BUFF_GIVEUP_MS = 180_000;
    private static final int FARM_FULL_BUFF_GAP_MS = 20_000;
    private boolean wasFighting;
    private long lastFarmFullBuffAt;
    // Attacks going out but no target bar for this long: the game keeps aiming at a monster it
    // can't lock (every skill "auto -> 2:348", never a lock, 20 min standing still at 15:52). The
    // names around kept the idle walk from starting, so step away and let it pick another.
    private static final int FARM_NO_BAR_MS = 20_000;
    private long lastTargetBarAt;
    private final Runnable lootTapTick = this::lootTapTick;
    // Text reading (the anti-bot question) on every 2nd fight-check screenshot, from the play area.
    private static final int FARM_OCR_MS = 4000;
    private long lastFarmOcrAt;
    // From just under the HP bars at the top (so the target bar's name is in it) down to the chat.
    // Wide, for luring on sparse maps (a Skating Master at 0.87W was out of view); the HUD, ring
    // labels and minimap text in it never match a learned monster name.
    private static final float READ_L = 0.05f, READ_T = 0.02f, READ_W = 0.87f, READ_H = 0.72f;
    private static final float TARGET_NAME_MAX_Y = 0.07f;     // title ends ~0.064H
    // Monster names this far from the character (share of screen width) count as "a fight is on".
    private static final float MONSTER_NEAR_W = 0.3f;
    // Luring: gather LURE_COUNT within LURE_NEAR_W (share of screen width) of the character before
    // fighting; pull the nearest one out to LURE_FAR_W with the fist (FIST_X/Y), one every
    // LURE_PULL_GAP_MS, tapping LURE_BODY_BELOW tag-heights under its name to select it. A pull is
    // dropped once the target reads below LURE_HIT_HP or after LURE_PULL_MAX_MS. Luring starts
    // LURE_START_IDLE_MS after the last fight and gives up after LURE_MAX_MS.
    private static final int LURE_COUNT = 3;
    private static final float LURE_NEAR_W = 0.25f, LURE_FAR_W = 0.6f, LURE_BODY_BELOW = 2f, LURE_HIT_HP = 0.97f;
    private static final float FIST_X = 2362 / 2560f, FIST_Y = 1386 / 1600f;
    private static final int LURE_PULL_GAP_MS = 2500, LURE_SELECT_SETTLE_MS = 300, LURE_PULL_MAX_MS = 7000;
    // Run speed toward a pulled monster (screen px/s, as measured for walks) and one punch's time.
    private static final float LURE_RUN_PX_PER_S = 350f;
    private static final int LURE_ONE_PUNCH_MS = 450;
    private static final int LURE_START_IDLE_MS = 3000, LURE_MAX_MS = 30_000;   // incl. walking to find them
    private static final float HUD_TOP_H = 0.15f, HUD_LEFT_W = 0.25f, HUD_LEFT_H = 0.25f;
    private boolean luring, lureMode;
    private static final String KEY_LURE = "farm_lure";
    // Monster names learned from the target bar this session (see checkTargetName).
    private final java.util.Set<String> knownMonsters = new java.util.HashSet<>();
    private static final String KEY_MONSTERS = "farm_monsters";
    private long lureStartedAt, lastPullAt, pullingSince;
    private int lureHits;   // pulls that landed this round
    // The fight after a lure: how many were gathered, how many died since, when it began. The
    // next lure starts once the group is dead (monsters straggling in kept it fighting one at a
    // time and never luring again, 15:06), or after a real lull - not one scan without a bar,
    // which left a monster at 56% beating on the character for 20 s while it lured (15:07:33).
    private int lureGathered, fightKills, noTargetScans;
    private long lureFightStartedAt, lastLureDropAt, lastKillAt;
    private static final int LURE_FIGHT_MIN_MS = 8000;
    // After the group dies, wait this long for the loot hand to show before luring again.
    private static final int LURE_LOOT_LOOK_MS = 2500;
    // A monster this hurt that's already on us gets finished, not dropped (one at 44% was, 15:20).
    private static final float LURE_FINISH_HP = 0.5f;
    private static final String[] FARM_SKIP_NAMES = {"caloyski"};
    // Aggressive monsters chase whoever comes within their range, so no punch is needed: walk
    // toward one for LURE_AGGRO_WALK_SHARE of the run there (capped) and it follows (the user, 14:56).
    private static final String[] FARM_AGGRO_NAMES = {"skatingmaster", "skatingboy"};
    private static final float LURE_AGGRO_WALK_SHARE = 0.6f;
    private static final int LURE_AGGRO_MAX_WALK_MS = 2500;
    private final List<Rect> aggroTags = new ArrayList<>();   // this scan's aggressive name tags
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
        // Casts in a row that didn't take (Farmer): Lightspeed failing on low MP was retried every
        // 15 s and in every full buff, ~2.5 s each time (13% of the time went to buffs, 07:28-07:31).
        int failStreak;
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
        long castAt;                                           // Farmer: last time this buff was tapped

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
                        long wait = ++failStreak >= FARM_BUFF_GIVEUP_FAILS ? FARM_BUFF_GIVEUP_MS : FARM_BUFF_RETRY_MS;
                        backoffUntil = now + wait;
                        lastTapAt = 0;
                        Log.i(TAG, "buff target " + (targets.indexOf(Target.this) + 1) + ": cast didn't take"
                                + (failStreak >= FARM_BUFF_GIVEUP_FAILS ? " " + failStreak + " times in a row" : "")
                                + ", trying again in " + wait / 1000 + " s");
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
        follow = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FOLLOW, false);
        knownLoot.addAll(getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_LOOT_NAMES, java.util.Collections.emptySet()));
        lureMode = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LURE, false);
        knownMonsters.clear();
        knownMonsters.addAll(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(KEY_MONSTERS, java.util.Collections.emptySet()));
        // Drop junk learned before the filters: short OCR fragments.
        if (knownMonsters.removeIf(k -> !learnableMonster(k))) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putStringSet(KEY_MONSTERS, new java.util.HashSet<>(knownMonsters)).apply();
        }
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
        leashButton = roundButton("\u2693");
        leashButton.setTextSize(18);
        leashButton.setBackground(circle(Color.rgb(30, 130, 140)));
        LinearLayout.LayoutParams leashGap = new LinearLayout.LayoutParams(dp(48), dp(48));
        leashGap.topMargin = dp(8);
        bar.addView(leashButton, leashGap);
        barParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        barParams.x = dp(8);
        barParams.y = dp(200);
        makeDraggable(toggle, bar, barParams, this::onToggle, null);
        makeDraggable(add, bar, barParams, this::addTargetFromBar, null);
        makeDraggable(fullBuffButton, bar, barParams, this::onFullBuffButton, null);
        makeDraggable(manualButton, bar, barParams, this::onManualButton, null);
        makeDraggable(leashButton, bar, barParams, this::onSetLeash, null);
        loadHome();
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

    /**
     * The screen stays on while the bot runs: with nothing tapping (the 30 min rest in town, a
     * pause) the tablet went to sleep, and a sleeping screen takes no taps or screenshots.
     */
    private void keepScreenOn(boolean on) {
        if (bar == null || barParams == null || wm == null) return;
        int flags = on ? barParams.flags | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                : barParams.flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        if (flags == barParams.flags) return;
        barParams.flags = flags;
        try {
            wm.updateViewLayout(bar, barParams);
        } catch (RuntimeException ignored) {
            // bar not attached
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
        if (run && why.equals("button")) deathTimes.clear();
        if (run) partySize = -1;                                // a party that shrank while stopped is no drop
        keepScreenOn(run);
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
        deadUntil = 0;
        returning = false;
        returnGiveUpUntil = 0;
        lootIgnoreUntil = 0;                                    // a start always loots again
        lootFailStreak = 0;
        if (run) {
            if (why.equals("button")) {
                // A start of yours begins with the leash off (the user, 2026-10-06): tap the anchor
                // at the spot to set it. A resume after Android restarted the app keeps it.
                homeMap = null;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_HOME).apply();
                refreshLeashButton();
            } else {
                loadHome();                                     // the ⚓ spot; none set = roam (the user, 08:03)
            }
            calStage = 0;
            calValid = false;
            leashMisses = 0;
        }
        if (run && farmer) {
            lootGold = 0;
            lootItems.clear();
            lastChatLines = new ArrayList<>();
            chatPrimed = false;
            lootReportFrom = System.currentTimeMillis();
            handler.postDelayed(lootReportTick, LOOT_REPORT_MS);
            // A start of yours brings the pet out if it isn't (the user, 2026-10-06): the paw asks
            // "Summon your pet?" (Yes) or "Recall your pet?" (No, it's out already).
            if (why.equals("button")) {
                busyUntil = farmHoldUntil = SystemClock.uptimeMillis() + 3000;
                handler.postDelayed(() -> {
                    if (running && farmer && !manual) summonPet();
                }, 1000);
            }
        }
        if (run && follow) {
            leaderKey = null;
            leaderSeenAt = SystemClock.uptimeMillis();
            followLostSteps = 0;
            followLostAlerted = followNoPartyLogged = false;
            followHoldUntil = 0;
            handler.postDelayed(followTick, 1000);
        }
        // Clearing the handler above also dropped the "game back yet?" check.
        updateOverlayVisibility();
        // End Game starts the cycle like the support does by hand: buff the party first, but only
        // if no fight is on (decided on the first monster count, see updateWave). Skipped when
        // this start came from a full buff (FB or the EG switch while stopped).
        if (run) farmMobsSeenAt = farmProgressAt = lastTargetBarAt = SystemClock.uptimeMillis();   // a moment before walking
        luring = run && lureMode;                                  // LURE: start by gathering a group
        lureStartedAt = SystemClock.uptimeMillis();
        lureHits = 0;
        pullingSince = 0;
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
        modeButton.setVisibility(on || booster ? View.GONE : View.VISIBLE);   // EG/LL, or KILL/LURE in Farmer
        leashButton.setVisibility(!on && farmer ? View.VISIBLE : View.GONE);
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
        if (farmer && ++t.failStreak >= FARM_BUFF_GIVEUP_FAILS) {
            t.backoffUntil = SystemClock.uptimeMillis() + FARM_BUFF_GIVEUP_MS;
            Log.i(TAG, "full buff: target " + (targets.indexOf(t) + 1) + " didn't take " + t.failStreak
                    + " times in a row, leaving it " + FARM_BUFF_GIVEUP_MS / 1000 + " s");
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
        if (SystemClock.uptimeMillis() < deadUntil || returning) {   // dead / walking home: no skills
            schedulePump(1000);
            return;
        }
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
        if (farmer && farmBuffsHeld(now) && pending.stream().allMatch(t -> t.isSmart() && !t.forced)) {
            schedulePump(1000);                         // only buffs waiting, and a fight is on
            return;
        }
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
            next.castAt = now;
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
        // Farmer full buffs use Farmer's own spacing below: forced casts 1.5 s apart had Power Up
        // swallowed twice right after Blood Lust (00:14:26).
        if (t.forced && !farmer) {
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
        int before = partySize;
        partySize = members;
        if (!first) {
            Log.i(TAG, "party " + before + " -> " + members);
            // TEMPORARY (testing the Campus Return, the user 23:25 - remove 2026-10-06).
            Telegram.send(this, "👥 Ran Online: party " + before + " -> " + members);
        }
        if (before > CAMPUS_PARTY && members <= CAMPUS_PARTY) partyLeft(before, members);
        // Only say so when the numbers it goes by change.
        if (first || waveStartMobs() != oldStart || waveEndMobs() != oldEnd) {
            Log.i(TAG, "party of " + members + ": wave at " + waveStartMobs() + "+ monsters, cleared at "
                    + waveEndMobs() + " or fewer");
        }
    }

    // FS (the user, 23:10): the party down to 3 or fewer (for PARTY_SHRINK_MS, so not a misread)
    // -> the Campus Return card in quick slot D, then stop: there's nobody left to support.
    private static final int CAMPUS_PARTY = 3;
    private static final float CAMPUS_CARD_X = 2470 / 2560f, CAMPUS_CARD_Y = 756 / 1600f;
    private long lastPartyReadAt;

    /*
     * The quick bar has two pages: A/S/D (cards) and Q/W/E (pots), and the user flips it now and
     * then (23:10). The third slot's letter tells which is showing: "D" or "E", matched as white
     * pixels against assets/bar_label_d|e.png, shifted around (the letter sits ~10 px off on the
     * other page). A swipe left brings A/S/D back (right goes to Q/W/E, measured 23:13).
     */
    private static final float BAR_LBL_L = 2390 / 2560f, BAR_LBL_T = 690 / 1600f, BAR_LBL_W = 80 / 2560f, BAR_LBL_H = 70 / 1600f;
    private static final float BAR_SWIPE_Y = 760 / 1600f, BAR_SWIPE_HI = 2470 / 2560f, BAR_SWIPE_LO = 2130 / 2560f;
    private boolean[][] lblD, lblE;

    /** Runs then once the A/S/D page shows; flips the bar if it's on Q/W/E; never taps when unsure. */
    private void onCardPage(Runnable then, String what) {
        onCardPage(then, what, 4);
    }

    private void onCardPage(Runnable then, String what, int tries) {
        captureRegionForOcr(BAR_LBL_L, BAR_LBL_T, BAR_LBL_W, BAR_LBL_H, crop -> {
            char page = crop != null ? barPage(crop) : '?';
            if (crop != null) crop.recycle();
            if (page == 'D') {
                then.run();
                return;
            }
            if (tries <= 1) {
                Log.w(TAG, "quick bar: couldn't get the A/S/D page for the " + what + " card (" + page + ")");
                Telegram.send(this, "\u26A0 Ran Online: couldn't find the A/S/D quick bar page for the " + what
                        + " card - please check the quick bar.");
                return;
            }
            if (page == 'E') {
                // Left first; if that didn't flip it last time, right.
                boolean left = tries % 2 == 0;
                Log.i(TAG, "quick bar on Q/W/E - swiping " + (left ? "left" : "right") + " for the " + what + " card");
                swipeBar(left);
            }
            handler.postDelayed(() -> onCardPage(then, what, tries - 1), page == 'E' ? 900 : 1200);
        });
    }

    private void swipeBar(boolean left) {
        float y = screenH * BAR_SWIPE_Y, a = screenW * BAR_SWIPE_HI, b = screenW * BAR_SWIPE_LO;
        Path path = new Path();
        path.moveTo(left ? a : b, y);
        path.lineTo(left ? b : a, y);
        if (!gestureClear(300, () -> swipeBar(left))) return;
        ownTapUntil = Math.max(ownTapUntil, SystemClock.uptimeMillis() + 300 + OWN_TAP_SLACK_MS);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null);
    }

    /** 'D' (A/S/D page), 'E' (Q/W/E) or '?' (covered, unclear). */
    private char barPage(Bitmap crop) {
        if (lblD == null) {
            lblD = loadMask("bar_label_d.png");
            lblE = loadMask("bar_label_e.png");
            if (lblD == null || lblE == null) return '?';
        }
        int w = crop.getWidth(), h = crop.getHeight();
        boolean[][] m = new boolean[h][w];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            crop.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                m[y][x] = Math.min((c >> 16) & 0xff, Math.min((c >> 8) & 0xff, c & 0xff)) > 185;
            }
        }
        int dD = bestFit(m, lblD), dE = bestFit(m, lblE);
        if (dD < 40 && dD < dE * 0.6) return 'D';
        if (dE < 40 && dE < dD * 0.6) return 'E';
        return '?';
    }

    private static int bestFit(boolean[][] m, boolean[][] t) {
        int h = t.length, w = t[0].length, best = Integer.MAX_VALUE;
        for (int y = 0; y + h <= m.length; y++) {
            for (int x = 0; x + w <= m[0].length; x++) {
                int d = 0;
                for (int ty = 0; ty < h && d < best; ty++) {
                    for (int tx = 0; tx < w; tx++) if (m[y + ty][x + tx] != t[ty][tx]) d++;
                }
                best = Math.min(best, d);
            }
        }
        return best;
    }

    private boolean[][] loadMask(String asset) {
        try (java.io.InputStream in = getAssets().open(asset)) {
            Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
            boolean[][] t = new boolean[b.getHeight()][b.getWidth()];
            for (int y = 0; y < b.getHeight(); y++) {
                for (int x = 0; x < b.getWidth(); x++) t[y][x] = (b.getPixel(x, y) & 0xff) > 128;
            }
            b.recycle();
            return t;
        } catch (java.io.IOException | RuntimeException e) {
            Log.w(TAG, "quick bar: no " + asset + ": " + e);
            return null;
        }
    }

    // No party at all (the user, 23:26): the Team header is gone from the top left (collapsed it
    // still shows "Team") and no member rows read: Campus Return without the 60 s wait. Only
    // once a party was seen since the start, and only after NO_PARTY_MS of both (several reads).
    private static final int NO_PARTY_MS = 10_000;
    private long lastTeamSeenAt, lastTopReadAt, partyZeroSince, lastNoHudReadAt;

    /** From the FS question read (top 66% of the screen): is the "Team" header there? */
    private void noteTeamHeader(List<MathQuestion.Line> lines) {
        long now = SystemClock.uptimeMillis();
        lastTopReadAt = now;
        // Our own panel ("Lv. 106", "MMR") says it's the game screen: the login screen after a
        // disconnect has no Team list either and fired the Campus Return (23:44:53).
        boolean hud = false;
        for (MathQuestion.Line l : lines) {
            String t = l.text.trim().toLowerCase(java.util.Locale.ROOT);
            if (l.box.left < screenW * 0.2f && l.box.top < screenH * 0.12f && (t.contains("mmr") || t.startsWith("lv"))) hud = true;
        }
        if (!hud) lastNoHudReadAt = now;
        for (MathQuestion.Line l : lines) {
            if (l.box.left < screenW * 0.2f && l.box.top < screenH * 0.35f
                    && l.text.trim().toLowerCase(java.util.Locale.ROOT).startsWith("team")) {
                lastTeamSeenAt = now;
                return;
            }
        }
    }

    private void noPartyCheck(int members, long now) {
        if (members > 0) {
            partyZeroSince = 0;
            return;
        }
        if (partyZeroSince == 0) partyZeroSince = now;
        if (partySize <= 0 || !running || manual || !fsMode()) return;   // never had a party this run
        boolean headerGone = lastTopReadAt > lastTeamSeenAt && now - lastTeamSeenAt >= NO_PARTY_MS
                && now - lastTopReadAt < 5000
                && now - lastNoHudReadAt >= NO_PARTY_MS;                // our panel on every read meanwhile
        if (headerGone && now - partyZeroSince >= NO_PARTY_MS) {
            int before = partySize;
            partySize = 0;
            Log.i(TAG, "party " + before + " -> none (no Team list)");
            partyLeft(before, 0);
        }
    }

    private void partyLeft(int before, int members) {
        if (!running || manual || !fsMode()) return;
        Log.w(TAG, "party down from " + before + " to " + members + ": Campus Return card (slot D), then manual mode");
        // Stop only after the tap: stopping clears the handler, which would drop a pending bar flip.
        onCardPage(() -> {
            tapAt(screenW * CAMPUS_CARD_X, screenH * CAMPUS_CARD_Y, "campus return");
            Telegram.send(this, "\uD83C\uDFEB Ran Online: the party went from " + before + " to " + members
                    + " - used the Campus Return card (D), bot in manual mode.");
            // Manual mode afterwards (the user, 23:22).
            handler.postDelayed(() -> setManual(true, "campus return"), 1500);
        }, "Campus Return");
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
        if (farmer) {
            // Farmer: KILL fights whatever comes; LURE gathers LURE_COUNT first (the user, 14:44).
            modeButton.setText(lureMode ? "LURE" : "KILL");
            modeButton.setTextSize(11);
            modeButton.setBackground(circle(lureMode ? Color.rgb(60, 120, 40) : Color.rgb(170, 40, 40)));
        } else if (booster) {
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
        if (farmer) {
            setLureMode(!lureMode);
            return;
        }
        setEndGame(!endGame, "button");
    }

    private void setLureMode(boolean on) {
        lureMode = on;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LURE, on).apply();
        Log.i(TAG, "farmer: " + (on ? "LURE mode, gathering " + LURE_COUNT + " before each fight" : "KILL mode"));
        luring = on && running;
        lureStartedAt = SystemClock.uptimeMillis();
        lureHits = 0;
        pullingSince = 0;
        if (!on) busyUntil = SystemClock.uptimeMillis();
        refreshModeButton();
        shake(modeButton);
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
        TextView fol = roundButton("FOLLOW");
        fol.setTextSize(8);
        fol.setBackground(circle(Color.rgb(40, 90, 160)));
        TextView[] choices = {fs, boost, farm, fol};
        fs.setOnClickListener(v -> startInMode(false, false, false));
        boost.setOnClickListener(v -> startInMode(true, false, false));
        farm.setOnClickListener(v -> startInMode(false, true, false));
        fol.setOnClickListener(v -> startInMode(true, false, true));
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

    private void startInMode(boolean boostMode, boolean farmMode, boolean followMode) {
        closeModeChooser();
        setFollow(followMode, "button");
        setFarmer(farmMode, "button");              // swaps in that mode's rings
        setBooster(boostMode, "button");            // sets ring/FB/mode-button visibility for the mode
        if (manual) setManual(false, "chooser");
        setRunning(true, "button");
    }

    private void setFollow(boolean on, String why) {
        boolean changed = follow != on;
        follow = on;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FOLLOW, on).apply();
        if (changed || !why.equals("restored")) {
            Log.i(TAG, "follow mode " + (on ? "on: staying close to the party master, no skills" : "off") + " (" + why + ")");
        }
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
            modeButton.setVisibility(booster ? View.GONE : View.VISIBLE);
            leashButton.setVisibility(farmer ? View.VISIBLE : View.GONE);
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
            modeButton.setVisibility(booster ? View.GONE : View.VISIBLE);
            leashButton.setVisibility(farmer ? View.VISIBLE : View.GONE);
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

    // Farmer: the game took every Lightspeed tap (its log shows the cast 50 ms later), but the buff
    // row read it as missing or low right after, so it was recast 2.5 s later or "didn't take" and
    // backed off (13 of 22 failed buffs, 08:30-09:04) - each extra cast holds attacks ~2.5 s.
    private static final int BUFF_TRUST_MS = 30_000;

    private boolean buffTrusted(Target t) {
        return farmer && t.castAt > 0 && SystemClock.uptimeMillis() - t.castAt < BUFF_TRUST_MS;
    }

    private boolean buffNeeded(Target t) {
        // End Game: buffs only go out in full buffs; every other slot is a heal.
        if (!t.forced && eg()) return false;
        if (buffTrusted(t)) return false;
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
        // But not mid-fight: buffs wait until the monsters on us are dead (the user, 17:58 -
        // five buffs back to back stalled a fight ~15 s while the target healed).
        if (farmer) {
            if (farmBuffsHeld(now)) {
                // A full buff that started finishes even if the next fight began meanwhile: it held
                // Power Up back 50 s and it ran from 48% to 2% (00:10:29).
                for (Target t : pending) if (t.isSmart() && t.forced) return t;
                for (Target t : pending) if (!t.isSmart()) return t;
            } else {
                for (Target t : pending) if (t.isSmart()) return t;
            }
        }
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
            // Following already moves the character; no jiggle on top of a walk.
            if (follow && SystemClock.uptimeMillis() - lastFollowWalkAt < TEST_MOVE_EVERY_MS) return;
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
            if (chat == null) return;
            openChatIfHidden(chat, Math.round(screenW * CHAT_L), Math.round(screenH * CHAT_T));
            Ocr.read(chat, (lines, words) -> checkChatForBuffRequest(lines));
        });
    }

    private void checkChatForBuffRequest(List<MathQuestion.Line> lines) {
        if (!running) return;
        for (MathQuestion.Line line : lines) checkPkLine(line.text);
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
        lurePullCheck(targetHp, now);
        // A kill: the bar went away (or jumped to a fresh monster) right after reading low.
        if (farmTargetHp >= 0 && farmTargetHp <= 0.35f && (!target || targetHp - farmTargetHp > 0.4f)) {
            fightKills++;
            lastKillAt = now;
        }
        noTargetScans = target ? 0 : noTargetScans + 1;
        // Gather the next group once this one is dead, or after a real lull with nothing to fight.
        boolean groupDead = lureGathered > 0 && fightKills >= lureGathered && now - lastKillAt >= LURE_LOOT_LOOK_MS;
        boolean lull = noTargetScans >= 2 && now - farmMobsSeenAt >= LURE_START_IDLE_MS
                && now - lureFightStartedAt >= LURE_FIGHT_MIN_MS;
        if (lureMode && !luring && !target && lootStartedAt == 0 && (groupDead || lull)) {
            luring = true;
            lureStartedAt = now;
            lureHits = 0;
            Log.i(TAG, "farmer: " + (groupDead ? "group of " + lureGathered + " killed" : "fight over")
                    + ", luring the next " + LURE_COUNT);
        }
        // Luring, but a monster is already on us (the game auto-targeted it, or it was left from
        // the fight): drop it so it just follows, and count it as gathered.
        if (luring && target && pullingSince == 0 && targetHp < LURE_FINISH_HP) {
            luring = false;
            busyUntil = now;
            farmMobsSeenAt = farmProgressAt = now;
            lureGathered = Math.min(lureHits + 1, LURE_COUNT);
            fightKills = 0;
            lureFightStartedAt = now;
            Log.i(TAG, "farmer: lured " + lureGathered + ", one on us is at " + Math.round(targetHp * 100)
                    + "%, fighting");
            schedulePump(0);
        } else if (luring && target && pullingSince == 0 && now - lastLureDropAt >= 1500) {
            lastLureDropAt = now;
            boolean onUs = targetHp < LURE_HIT_HP;
            if (onUs) lureHits++;
            tapAt(screenW * MobCounter.CLOSE_X, screenH * MobCounter.CLOSE_Y, "lure drop");
            Log.i(TAG, "farmer: luring, dropped a selected monster at " + Math.round(targetHp * 100) + "%"
                    + (onUs ? " (on us, " + lureHits + " gathered)" : ""));
        }
        // A kill (the bar went away): hold attacks a moment and look again for the loot hand.
        // Attacking on straight away auto-targeted the next monster and ran off from the drop,
        // and the hand then walked the character all the way back (the user, 12:55).
        // Drops show their hand ~1 s or ~5 s after the kill (12 min of kills, 2026-10-05 06:45-06:57:
        // 0.9-1.0 s or 4.96-5.02 s), so wait up to POST_KILL_HOLD_MS; the hand ends it early.
        // Only a bar that went away reading low is a kill: skating monsters slide out of the selection
        // with HP left, and holding for their "drop" walked away from a live monster (the user, 08:05).
        // One punch takes ~40% (kills from 37-40% dropped loot, 08:07), so "low" is up to KILL_MAX_HP.
        if (!target && farmTargetHp > KILL_MAX_HP && lootStartedAt == 0) {
            Log.i(TAG, "farmer: target lost at " + Math.round(farmTargetHp * 100) + "%, not a kill - attacking on");
            schedulePump(0);
        }
        if (!target && farmTargetHp >= 0 && farmTargetHp <= KILL_MAX_HP && lootStartedAt == 0 && now >= lootIgnoreUntil) {
            postKillUntil = now + POST_KILL_HOLD_MS;
            busyUntil = Math.max(busyUntil, postKillUntil);
            killAt = now;
            killStepDone = false;
            dropSeenAt = 0;
            dropSteps = 0;
        }
        if (!target && now < postKillUntil && lootStartedAt == 0) {
            if (dropSeenAt > killAt) {
                // A drop lies there but the hand isn't up: walk to it (short steps).
                float dx = dropX - screenW * 0.5f, dy = dropY - screenH * 0.53f;
                float d = (float) Math.hypot(dx, dy);
                if (dropSteps == 0) dropFirstD = d;
                if (dropSteps > 0 && dropSeenAt > dropStepAt + 800 && Math.abs(d - dropFirstD) < screenW * 0.008f && dropBox != null
                        && canFarmMove(now)) {
                    // Walked toward it and it stayed put on the screen: screen text, not a drop.
                    if (staticLabels.size() >= 8) staticLabels.remove(0);
                    staticLabels.add(new Rect(dropBox));
                    Log.i(TAG, "farmer: \"" + dropText + "\" at " + dropBox.centerX() + "," + dropBox.centerY()
                            + " didn't move while walking - screen text, ignoring it; attacking");
                    dropSeenAt = 0;
                    postKillUntil = now;
                    busyUntil = now;
                    schedulePump(0);
                } else if (dropSteps < DROP_MAX_STEPS && now - killAt >= 900 && d > screenW * 0.03f && canFarmMove(now)) {
                    dropSteps++;
                    dropStepAt = now;
                    int ms = (int) Math.max(300, Math.min(1000, d * 900f / LURE_RUN_PX_PER_S));
                    float push = screenW * FARM_PUSH;
                    long window = FARM_PUSH_MS + ms;
                    ownTapUntil = now + window + OWN_TAP_SLACK_MS;
                    busyUntil = farmHoldUntil = now + window + FARM_WALK_SETTLE_MS;
                    postKillUntil = Math.max(postKillUntil, now + window + 1800);
                    busyUntil = Math.max(busyUntil, postKillUntil);
                    killStepDone = true;
                    Log.i(TAG, "farmer: drop " + Math.round(d) + " px away, no hand yet, walking " + ms + " ms to it ("
                            + dropSteps + "/" + DROP_MAX_STEPS + ")");
                    joystickHold(dx / d * push, dy / d * push, ms);
                }
            } else if (postKillOcrAt >= killAt + DROP_DECIDE_MS && now - lastHandSeenAt > 2000) {
                // Read the ground well after the kill and no drop label: nothing dropped.
                Log.i(TAG, "farmer: no drop after the kill, attacking");
                postKillUntil = now;
                busyUntil = now;
                schedulePump(0);
            }
        }
        // No hand yet: step to where the monster died, so the drop comes into reach.
        if (!target && now < postKillUntil && lootStartedAt == 0 && !killStepDone && postKillOcrAt < killAt
                && now - killAt >= LOOT_STEP_AFTER_MS && now - killSpotAt < KILL_SPOT_FRESH_MS && canFarmMove(now)) {
            killStepDone = true;
            float dx = killSpotX - screenW * 0.5f, dy = killSpotY - screenH * 0.53f;
            float d = (float) Math.hypot(dx, dy);
            if (d > screenW * 0.04f) {
                int ms = (int) Math.max(300, Math.min(1200, d * 1000f / LURE_RUN_PX_PER_S));
                float push = screenW * FARM_PUSH;
                long window = FARM_PUSH_MS + ms;
                ownTapUntil = now + window + OWN_TAP_SLACK_MS;
                busyUntil = farmHoldUntil = now + window + FARM_WALK_SETTLE_MS;
                postKillUntil = Math.max(postKillUntil, now + window + 1600);
                busyUntil = Math.max(busyUntil, postKillUntil);
                Log.i(TAG, "farmer: no loot hand yet, stepping " + ms + " ms to where the monster died");
                joystickHold(dx / d * push, dy / d * push, ms);
            }
        }
        if (!luring && returnHome(targetHp, now)) {
            farmTargetHp = targetHp;
            farmProgressAt = now;
            return;
        }
        // The game locked the next monster by itself during the pause: the character is off to it
        // already, so waiting for the drop gains nothing.
        if (target && farmTargetHp < 0 && now < postKillUntil && lootStartedAt == 0) {
            postKillUntil = now;
            busyUntil = now;
            schedulePump(0);
        }
        // Stuck: the game keeps going for a monster it can't reach (behind a wall: "no clear line
        // ... walking in", 2026-10-04 11:29), so its HP never drops. Any HP change, a new target or
        // no target at all counts as progress.
        if (!target || Math.abs(targetHp - farmTargetHp) > 0.01f) farmProgressAt = now;
        farmTargetHp = targetHp;
        Log.v(TAG, "farm scan: monsters ~" + mobs + ", target " + (target ? Math.round(targetHp * 100) + "%" : "none"));
        long stuckAfter = targetHp >= 0.97f ? FARM_STUCK_FULL_MS : FARM_STUCK_MS;
        if (target && !luring && now - farmProgressAt >= stuckAfter && canFarmMove(now)) {
            Log.i(TAG, "farmer: target HP stuck at " + Math.round(targetHp * 100) + "% for "
                    + (now - farmProgressAt) / 1000 + " s, dropping it and stepping away");
            dropTargetAndStep(now);
            return;
        }
        // Only a target bar counts as fighting. Red name tags here are Caloyski, whom the game
        // auto-attacks without selecting (no bar) and can't reach: counting it as "monsters near"
        // kept the character there for minutes (12:11). No bar for a while = walk a step.
        if (target) {
            farmMobsSeenAt = lastTargetBarAt = now;
            return;
        }
        if (lastTargetBarAt == 0 || lootStartedAt > 0 || luring) lastTargetBarAt = Math.max(lastTargetBarAt, now - FARM_NO_BAR_MS / 2);
        if (now - lastTargetBarAt >= FARM_NO_BAR_MS && now - lastAnyTapAt < 3000 && canFarmMove(now)) {
            Log.i(TAG, "farmer: attacking for " + (now - lastTargetBarAt) / 1000 + " s with no target bar,"
                    + " the game can't reach its pick; walking " + "ENWS".charAt(farmWalkStep));
            lastTargetBarAt = now;
            farmWalk(now);
            return;
        }
        if (now - Math.max(farmMobsSeenAt, lastBuffTapAt + 5000) < FARM_IDLE_MS || !canFarmMove(now)) return;
        // Searching while already halfway out: search back toward home.
        if (!luring && searchTowardHome(now)) return;
        Log.i(TAG, "farmer: no target for " + (now - farmMobsSeenAt) / 1000 + " s (monsters ~" + mobs
                + "), walking " + "ENWS".charAt(farmWalkStep));
        farmWalk(now);
    }

    /**
     * Monster name tags on screen (white text on a dark box, e.g. "Brute Punk"), in screen pixels.
     * The game often fights with no target bar (auto skills hit without selecting), so the bar
     * alone said "nothing here" and the character walked off mid-fight (13:36). Our own name and
     * item labels are yellow, the pet green, Caloyski red, the target bar's title is skipped.
     */
    private List<Rect> monsterTags(List<MathQuestion.Line> lines, Bitmap crop, int ox, int oy) {
        List<Rect> tags = new ArrayList<>();
        aggroTags.clear();
        for (MathQuestion.Line line : lines) {
            if (!isKnownMonster(line.text)) {
                continue;
            }
            // Not the HUD: the top strip (target bar title) and the top-left panel ("Lv. 148",
            // "MMR", the buff row) are white on dark too - the first lure kept pulling "531,195".
            if (line.box.bottom <= screenH * HUD_TOP_H
                    || (line.box.left < screenW * HUD_LEFT_W && line.box.top < screenH * HUD_LEFT_H)
                    || (line.box.right > screenW * 0.76f && line.box.top < screenH * 0.32f)) continue;   // minimap
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
            if (luring) {
                Log.d(TAG, "lure tag \"" + line.text.trim() + "\" at " + line.box.toShortString() + " white "
                        + (total > 0 ? white * 100 / total : 0) + "% dist " + Math.round(fromCharacter(line.box)));
            }
            // Any colour: weaker monsters' names are gray, not white (the user, 14:44), so a
            // colour test missed every monster here. Only learned names get this far anyway.
            Rect tag = new Rect(line.box);
            tags.add(tag);
            String key = monsterKey(line.text);
            if (java.util.Arrays.stream(FARM_AGGRO_NAMES).anyMatch(key::contains)) aggroTags.add(tag);
        }
        return tags;
    }

    /** Letters only, lower case: "Brute Punk" and an OCR "Brute Punk." match. */
    private static String monsterKey(String text) {
        // OCR mixes up look-alikes ("Lo0se Halogen"): read 0 as o, 1 as l, 5 as s before matching.
        // Accents too: OCR read "Skațing Boy" (14:58), whose ț would otherwise vanish.
        text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD);
        return text.toLowerCase(java.util.Locale.ROOT).replace('0', 'o').replace('1', 'l').replace('5', 's')
                .replaceAll("[^a-z]", "");
    }

    private boolean isKnownMonster(String text) {
        String key = monsterKey(text);
        if (key.length() < 4 || !learnableMonster(key)) return false;
        for (String m : knownMonsters) if (key.equals(m) || key.contains(m) || m.contains(key) && key.length() >= 6) return true;
        return false;
    }

    /**
     * A name worth learning/matching: long enough to be a real name ("Hodel" is 5), not an OCR
     * fragment like "hode", which matched any text containing it.
     */
    private static boolean learnableMonster(String key) {
        return key.length() >= 5;
    }

    /**
     * Where a lure tap is safe: open ground, not the minimap, the top buttons, the skill rings,
     * the joystick, our own button bar or the chat - taps there opened the map and other panels,
     * which stop everything until closed (the user, 15:29).
     */
    private boolean safeToTap(float x, float y) {
        float fx = x / screenW, fy = y / screenH;
        if (fx < 0.08f || fx > 0.74f || fy < 0.17f || fy > 0.80f) return false;
        if (fx < 0.22f && fy > 0.58f) return false;                      // joystick
        if (fx > 0.63f && fy > 0.44f) return false;                      // skill rings, Z, Q/W/E, Stop
        return true;
    }

    /** How far a name tag is from the character (screen pixels). */
    private float fromCharacter(Rect tag) {
        return (float) Math.hypot(tag.exactCenterX() - screenW * 0.5f, tag.exactCenterY() - screenH * 0.53f);
    }

    /**
     * Luring (the user's idea, 2026-10-04): the skills mostly hit an area and a monster hit once
     * chases you, so gather LURE_COUNT before fighting. Between fights attacks pause; every scan
     * the name tags close around the character are the followers, and while there are too few
     * the nearest monster further out is selected (a tap just under its name) and hit once with
     * ring 1, a long-range skill, so it comes over. Then the normal fight takes them all at once.
     */
    private void lureStep(List<Rect> tags, long now) {
        if (!luring || lootStartedAt > 0 || questionSeen) return;
        int followers = 0;
        Rect pull = null;
        float pullDist = Float.MAX_VALUE;
        for (Rect tag : tags) {
            float d = fromCharacter(tag);
            if (d <= screenW * LURE_NEAR_W) {
                followers++;
            } else if (d <= screenW * LURE_FAR_W && d < pullDist
                    && safeToTap(tag.exactCenterX(), tag.bottom + tag.height() * LURE_BODY_BELOW)) {
                pull = tag;
                pullDist = d;
            }
        }
        // A monster we hit chases us: count landed pulls too. Followers bunch up around the
        // character, where their tags overlap ours and the pet's and read as garbage ("Skating
        // BY iger"), so a round of 3 good pulls once ended "lured 0 (time up)" (14:47).
        followers = Math.max(followers, lureHits);
        boolean timeUp = now - lureStartedAt >= LURE_MAX_MS;
        if (followers >= LURE_COUNT || timeUp) {
            luring = false;
            busyUntil = now;
            farmMobsSeenAt = farmProgressAt = now;
            lureGathered = Math.min(followers, LURE_COUNT);
            fightKills = 0;
            lureFightStartedAt = now;
            Log.i(TAG, "farmer: lured " + followers + " (" + (followers >= LURE_COUNT ? "enough"
                    : timeUp ? "time up" : "no more in range") + "), fighting");
            schedulePump(0);
            return;
        }
        busyUntil = Math.max(busyUntil, now + FARM_SCAN_MS + 500);     // no attacks while luring
        // Nothing in pull range: go and find more (followers come along).
        if (pull == null && pullingSince == 0 && canFarmMove(now)) {
            lureSearchWalk(followers, now);
            return;
        }
        if (pull != null) farmMobsSeenAt = now;                         // monsters in range: no walking off
        if (pull == null || pullingSince > 0 || now - lastPullAt < LURE_PULL_GAP_MS || !canFarmMove(now)) return;
        if (aggroTags.contains(pull)) {
            // Aggressive: just get within its range and it comes (the user, 14:56). Walk toward it,
            // then count it as following.
            float dx = pull.exactCenterX() - screenW * 0.5f, dy = pull.exactCenterY() - screenH * 0.53f;
            float len = (float) Math.hypot(dx, dy), push = screenW * FARM_PUSH;
            int walkMs = (int) Math.min(LURE_AGGRO_MAX_WALK_MS, pullDist * 1000f / LURE_RUN_PX_PER_S * LURE_AGGRO_WALK_SHARE);
            lastPullAt = now;
            lureHits++;
            ownTapUntil = now + FARM_PUSH_MS + walkMs + OWN_TAP_SLACK_MS;
            busyUntil = farmHoldUntil = now + FARM_PUSH_MS + walkMs + FARM_WALK_SETTLE_MS;
            Log.i(TAG, "farmer: luring (" + followers + " following), walking " + walkMs
                    + " ms toward the aggressive one at " + pull.centerX() + "," + pull.centerY()
                    + " (" + lureHits + " pulled)");
            joystickHold(dx / len * push, dy / len * push, walkMs);
            return;
        }
        // The pull is the fist (basic attack, no cooldown; ring 1 is long range but its cooldown is
        // long - the user). Select the monster, punch; once it's hit, drop it (lurePullCheck) so
        // the punches stop and it just chases.
        lastPullAt = now;
        pullingSince = now;
        float tx = pull.exactCenterX(), ty = pull.bottom + pull.height() * LURE_BODY_BELOW;
        Log.i(TAG, "farmer: luring (" + followers + " following), pulling the one at "
                + Math.round(tx) + "," + Math.round(ty));
        tapAt(tx, ty, "lure select");
        handler.postDelayed(() -> {
            if (!running || !farmer) return;
            lastAnyTapAt = SystemClock.uptimeMillis();
            tapAt(screenW * FIST_X, screenH * FIST_Y, "lure punch");
        }, TAP_MS + LURE_SELECT_SETTLE_MS);
        // One punch is enough to pull it (the user, 14:52); the fist keeps auto-attacking until the
        // target is dropped, so drop it as soon as the run there plus one punch should be done
        // rather than at the next screenshot (2 s, several punches later).
        long dropAfter = TAP_MS + LURE_SELECT_SETTLE_MS + TAP_MS
                + Math.round(pullDist * 1000f / LURE_RUN_PX_PER_S) + LURE_ONE_PUNCH_MS;
        handler.postDelayed(() -> {
            if (!running || !farmer || pullingSince == 0) return;     // already dropped (seen hit)
            pullingSince = 0;
            lureHits++;
            lastLureDropAt = SystemClock.uptimeMillis();
            tapAt(screenW * MobCounter.CLOSE_X, screenH * MobCounter.CLOSE_Y, "lure drop");
            Log.i(TAG, "farmer: pull punched once (" + lureHits + " pulled), dropped the target");
        }, dropAfter);
    }

    /** While pulling: once the target's HP shows a hit (or it took too long), drop it. */
    private void lurePullCheck(float targetHp, long now) {
        if (pullingSince == 0) return;
        boolean hit = targetHp >= 0 && targetHp < LURE_HIT_HP;
        if (!hit && now - pullingSince < LURE_PULL_MAX_MS) return;
        pullingSince = 0;
        lastLureDropAt = now;
        if (targetHp >= 0) tapAt(screenW * MobCounter.CLOSE_X, screenH * MobCounter.CLOSE_Y, "lure drop");
        if (hit) lureHits++;
        Log.i(TAG, "farmer: pull " + (hit ? "hit it (" + lureHits + " pulled)" : "timed out") + ", dropped the target");
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
            // Learn monster names from the target bar: only monsters get targeted, so a name seen
            // here is a monster. Name tags elsewhere count only if learned (players and the pet's
            // owner label are white too and counted as "followers", 14:31).
            float mid = line.box.exactCenterX();
            String key = monsterKey(line.text);
            boolean skipped = java.util.Arrays.stream(FARM_SKIP_NAMES).anyMatch(key::contains);   // never lure Caloyski
            if (!skipped && mid > screenW * 0.3f && mid < screenW * 0.7f && learnableMonster(key) && knownMonsters.add(key)) {
                Log.i(TAG, "farmer: learned monster name \"" + line.text.trim() + "\"");
                // Kept across restarts and installs, so luring works from the first fight.
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putStringSet(KEY_MONSTERS, new java.util.HashSet<>(knownMonsters)).apply();
            }
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

    /** Farmer, a fight just ended: cast every low or missing buff back to back. */
    private void farmFullBuff() {
        long now = SystemClock.uptimeMillis();
        if (!running || !farmer || now - lastFarmFullBuffAt < FARM_FULL_BUFF_GAP_MS) return;
        List<Target> low = new ArrayList<>();
        for (Target t : targets) {
            if (!t.isSmart() || t.priority || t.onCooldown() || pending.contains(t) || now < t.backoffUntil) continue;
            // Only buffs actually read since the start: right after ▶ every reading is "unknown",
            // and all five went out, two of them at 95-98% (06:59:36). Missing ones still get their
            // own recast once read.
            if (t.buffKnown && (!t.buffFound || t.buffFill <= FARM_TOPUP_AT) && !buffTrusted(t)) low.add(t);
        }
        if (low.isEmpty()) return;
        lastFarmFullBuffAt = now;
        StringBuilder which = new StringBuilder();
        for (Target t : low) {
            which.append(which.length() > 0 ? "," : "").append(targets.indexOf(t) + 1)
                    .append(t.buffFound ? " " + Math.round(t.buffFill * 100) + "%" : " off");
            t.forced = true;
            t.forcedRetries = 0;
            queueTap(t);
        }
        Log.i(TAG, "farmer: fight over, full buff: " + low.size() + " at or below "
                + Math.round(FARM_TOPUP_AT * 100) + "% (targets " + which + ")");
    }

    /** Farmer: hold buff casts while a fight is on. */
    private boolean farmBuffsHeld(long now) {
        // Luring isn't fighting: attacks are paused and the followers only tag along, so that's
        // the best time to buff (the names around had held every buff for 45 s, 23:25).
        boolean fighting = running && farmer && (lootStartedAt > 0 || pullingSince > 0
                || now - lastHandSeenAt < FARM_SCAN_MS + 500 || (!luring
                && (farmTargetHp >= 0 || now - nearTagAt < NEAR_TAG_FIGHT_MS || now < postKillUntil)));
        if (!fighting) {
            buffsHeldSince = 0;
            if (wasFighting) {
                wasFighting = false;
                handler.post(this::farmFullBuff);           // not from inside the queue walk
            }
            return false;
        }
        wasFighting = true;
        if (now < buffWindowUntil) return false;
        if (buffsHeldSince == 0) buffsHeldSince = now;
        if (now - buffsHeldSince < FARM_BUFF_HOLD_MAX_MS) return true;
        buffsHeldSince = 0;
        buffWindowUntil = now + FARM_BUFF_WINDOW_MS;
        Log.i(TAG, "farmer: fighting for " + FARM_BUFF_HOLD_MAX_MS / 1000 + " s straight, letting buffs go");
        // Nonstop fights (a party on a busy map) never "end", so the window tops up every low
        // buff too - otherwise only the ones already at 20% went, one per window (00:06).
        handler.post(this::farmFullBuff);
        return false;
    }

    /** After a kill: drop labels on the ground near the character (gold amounts, item names). */
    private void noteDrops(List<MathQuestion.Line> lines) {
        long now = SystemClock.uptimeMillis();
        if (now >= postKillUntil || killAt == 0) return;
        Rect best = null;
        float bestD = screenW * 0.35f;
        for (MathQuestion.Line l : lines) {
            Rect b = l.box;
            if (b.bottom <= screenH * HUD_TOP_H || (b.right > screenW * 0.76f && b.top < screenH * 0.32f)) continue;
            if (b.left < screenW * HUD_LEFT_W && b.top < screenH * HUD_LEFT_H) continue;
            if (onGameControls(b) || isStaticLabel(b)) continue;
            if (!isDropLabel(l.text, b)) continue;
            float d = fromCharacter(b);
            if (d < bestD) {
                bestD = d;
                best = b;
                dropText = l.text.trim();
            }
        }
        postKillOcrAt = now;
        if (best == null) {
            if (now - killAt >= DROP_DECIDE_MS && dropSeenAt <= killAt && now - lastHandSeenAt > 2000
                    && lootStartedAt == 0 && now < postKillUntil && farmTargetHp < 0) {
                Log.i(TAG, "farmer: no drop after the kill, attacking (" + (now - killAt) + " ms)");
                postKillUntil = now;
                busyUntil = now;
                schedulePump(0);
            }
            return;
        }
        dropBox = new Rect(best);
        dropX = best.exactCenterX();
        dropY = best.bottom + best.height() * 1.2f;               // the item lies under its label
        if (dropSeenAt <= killAt) Log.i(TAG, "farmer: drop on the ground " + Math.round(bestD) + " px away (\"" + dropText
                + "\" at " + best.centerX() + "," + best.centerY() + ")");
        dropSeenAt = now;
    }

    /** The game's own controls: skills and quick slots (right), chat box, joystick, coordinates line. */
    private boolean onGameControls(Rect b) {
        float x = b.exactCenterX() / screenW, y = b.exactCenterY() / screenH;
        if (x > 0.70f && y > 0.18f) return true;
        if (x > 0.27f && x < 0.63f && y > 0.71f) return true;
        if (x < 0.20f && y > 0.60f) return true;
        return y > 0.95f;
    }

    private boolean isStaticLabel(Rect b) {
        for (Rect r : staticLabels) {
            if (Math.abs(r.centerX() - b.centerX()) < screenW * 0.015f && Math.abs(r.centerY() - b.centerY()) < screenH * 0.015f) {
                return true;
            }
        }
        return false;
    }

    private boolean isDropLabel(String text, Rect box) {
        String t = text.trim();
        // Gold: a plain amount in a small label (damage numbers are big, stylised digits).
        if (t.matches("\\d{2,7}") && box.height() >= 12 && box.height() <= 52) return true;
        String k = t.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        if (k.length() < 4 || isKnownMonster(t)) return false;
        for (String w : LOOT_WORDS) if (k.contains(w)) return true;
        for (String n : knownLoot) if (k.equals(n) || (k.length() >= 6 && (n.contains(k) || k.contains(n)))) return true;
        return false;
    }

    /** Teal when a home is set (leashed), grey when roaming. */
    private void refreshLeashButton() {
        if (leashButton != null) {
            leashButton.setBackground(circle(homeMap != null ? Color.rgb(30, 130, 140) : Color.rgb(110, 110, 110)));
            leashButton.setText(homeMap != null ? "\u2693\n" + leashR : "\u2693");
            leashButton.setTextSize(homeMap != null ? 12 : 18);
        }
    }

    private void loadHome() {
        homeMap = null;
        int r = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_LEASH_R, 10);
        leashR = r == 6 || r == 10 || r == 15 ? r : 10;
        refreshLeashButton();                                   // grey unless a home loads below
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HOME, null);
        if (saved == null) return;
        String[] p = saved.split(",");
        if (p.length != 3) return;
        try {
            homeX = Integer.parseInt(p[1]);
            homeY = Integer.parseInt(p[2]);
            homeMap = p[0];
        } catch (NumberFormatException ignored) {
        }
        refreshLeashButton();
    }

    /** ⚓: no home set -> make where the character stands home; home set -> clear it and roam. */
    private void onSetLeash() {
        shake(leashButton);
        if (homeMap != null && leashR < LEASH_RADII[LEASH_RADII.length - 1]) {
            // Home set: next radius, same home.
            for (int r : LEASH_RADII) {
                if (r > leashR) {
                    leashR = r;
                    break;
                }
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_LEASH_R, leashR).apply();
            refreshLeashButton();
            Log.i(TAG, "farmer: leash radius " + leashR + " (button)");
            android.widget.Toast.makeText(this, "Leash: within " + leashR + " of " + homeMap + "[" + homeX + "," + homeY + "]",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (homeMap != null) {
            homeMap = null;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_HOME).apply();
            refreshLeashButton();
            Log.i(TAG, "farmer: home cleared, roaming (button)");
            android.widget.Toast.makeText(this, "Leash off: roaming", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        homeCandMap = null;
        leashR = LEASH_RADII[0];                                    // a new home starts on the tightest leash
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_LEASH_R, leashR).apply();
        readHomeSpot(4);
    }

    /** Reads the coordinates for ⚓; the read misses about half the time (08:05), so try a few times. */
    private void readHomeSpot(int tries) {
        captureRegionForOcr(COORD_L, COORD_T, COORD_W, COORD_H, crop -> {
            if (crop == null) {
                homeReadFailed(tries);
                return;
            }
            Ocr.read(crop, (lines, words) -> {
                for (MathQuestion.Line l : lines) {
                    java.util.regex.Matcher m = COORD_TEXT.matcher(l.text);
                    if (!m.find()) continue;
                    String rm = m.group(1);
                    int rx = coordNumber(m.group(2)), ry = coordNumber(m.group(3));
                    // Two reads that agree: one read saved [124,1166] for [124,116] (19:13).
                    if (homeCandMap == null || !sameMap(rm, homeCandMap) || Math.abs(rx - homeCandX) > 2 || Math.abs(ry - homeCandY) > 2) {
                        homeCandMap = rm;
                        homeCandX = rx;
                        homeCandY = ry;
                        if (tries > 1) handler.postDelayed(() -> readHomeSpot(tries - 1), 400);
                        else homeReadFailed(1);
                        return;
                    }
                    homeCandMap = null;
                    homeMap = rm;
                    homeX = rx;
                    homeY = ry;
                    calValid = false;
                    calStage = 0;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_HOME, homeMap + "," + homeX + "," + homeY).apply();
                    refreshLeashButton();
                    String msg = "Home set: " + homeMap + "[" + homeX + "," + homeY + "], staying within " + leashR;
                    Log.i(TAG, "farmer: " + msg + " (button)");
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                homeReadFailed(tries);
            });
        });
    }

    /**
     * A coordinate as read: stray spaces out, and at most 3 digits - the map's own readout pads
     * them to 3 ("091 095"), and the closing "]" read as a digit made [124,117] into [124,1171].
     */
    private static int coordNumber(String digits) {
        String d = digits.replace(" ", "");
        if (d.length() > 3) d = d.substring(0, 3);
        return Integer.parseInt(d);
    }

    private String homeCandMap;
    private int homeCandX, homeCandY;

    private void homeReadFailed(int tries) {
        if (tries > 1) {
            handler.postDelayed(() -> readHomeSpot(tries - 1), 500);
            return;
        }
        if (posMap != null && SystemClock.uptimeMillis() - posAt < 15_000) {
            // The farming loop read the coordinates moments ago: use those.
            homeMap = posMap;
            homeX = posX;
            homeY = posY;
            calValid = false;
            calStage = 0;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HOME, homeMap + "," + homeX + "," + homeY).apply();
            refreshLeashButton();
            String msg = "Home set: " + homeMap + "[" + homeX + "," + homeY + "], staying within " + leashR;
            Log.i(TAG, "farmer: " + msg + " (button, last reading)");
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i(TAG, "farmer: couldn't read the coordinates for the home spot");
        android.widget.Toast.makeText(this, "Couldn't read the coordinates, tap again", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void farmCoordRead(Bitmap shot) {
        int x = Math.round(screenW * COORD_L), y = Math.round(screenH * COORD_T);
        int w = Math.min(Math.round(screenW * COORD_W), shot.getWidth() - x);
        int h = Math.min(Math.round(screenH * COORD_H), shot.getHeight() - y);
        if (w <= 0 || h <= 0) return;
        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(shot, x, y, w, h);
        } catch (RuntimeException | OutOfMemoryError e) {
            return;
        }
        Ocr.read(crop, (lines, words) -> {
            for (MathQuestion.Line l : lines) {
                java.util.regex.Matcher m = COORD_TEXT.matcher(l.text);
                if (!m.find()) continue;
                String map = m.group(1);
                int rx = coordNumber(m.group(2)), ry = coordNumber(m.group(3));
                long t = SystemClock.uptimeMillis();
                // A dropped digit ("[126,11" for [126,115], 19:05) looks like a 100-unit jump: the
                // character covers ~1-2 units a second, so take a big jump only when read twice.
                // Two reads that agree with each other win, though: a misread first read ([127,17],
                // 19:12) otherwise made every right one look like a jump, for good.
                // No recent good read (a start, a new map): it has to agree with the next one too
                // ([124,1166] for [124,116] got straight in after a restart, 19:13).
                boolean fits = posMap != null && sameMap(map, posMap) && t - posAt < 15_000
                        && Math.hypot(rx - posX, ry - posY) <= 6 + 3 * (t - posAt) / 1000.0;
                {
                    double jump = posMap != null ? Math.hypot(rx - posX, ry - posY) : -1;
                    boolean agrees = jumpAt > 0 && t - jumpAt < 20_000 && Math.hypot(rx - jumpX, ry - jumpY) <= 4 + 3 * (t - jumpAt) / 1000.0;
                    if (!fits && !agrees) {
                        jumpX = rx;
                        jumpY = ry;
                        jumpAt = t;
                        Log.d(TAG, "coordinates: [" + rx + "," + ry + "] unconfirmed (" + (jump < 0 ? "no earlier read" : Math.round(jump) + " from the last")
                                + "), waiting for a second read");
                        return;
                    }
                }
                jumpAt = 0;
                posMap = map;
                posX = rx;
                posY = ry;
                posAt = t;
                if (homeMap != null) {
                    Log.d(TAG, "position " + posMap + "[" + posX + "," + posY + "], "
                            + Math.round(Math.hypot(homeX - posX, homeY - posY)) + " from home");
                }
                return;
            }
            // Reads stopped matching for 80 s while walking home (18:57:13-18:58:39) with the line
            // plainly on screen: say what was read, now and then.
            long t = SystemClock.uptimeMillis();
            if (t - lastCoordMissLogAt > 30_000) {
                lastCoordMissLogAt = t;
                StringBuilder sb = new StringBuilder();
                for (MathQuestion.Line l : lines) sb.append(" | ").append(l.text);
                Log.d(TAG, "coordinates not read (" + w + "x" + h + "):" + sb);
            }
        });
    }

    /**
     * Walks back toward home when farther than LEASH_R (or LEASH_R/2 when searching anyway).
     * Returns true if it started a walk (or a calibration step).
     */
    // Walking home by the big map (the user, 19:08: "like in the follow"): tap the home spot on it
    // and the game walks there around walls. Our arrow sits mid-map; home is MAP_K px per unit
    // away (TradingHole 15.6, X right, Y up: measured 19:09 with the map's corner readout, which
    // shows the coordinates of the last spot touched). Each tap's readout corrects the scale.
    private static final float MAP_K_DEFAULT = 15.6f;
    private static final float MAP_RO_L = 0.86f, MAP_RO_T = 0.785f, MAP_RO_W = 0.11f, MAP_RO_H = 0.06f;
    private static final int MAP_HOME_GAP_MS = 4000;
    private static final java.util.regex.Pattern MAP_READOUT = java.util.regex.Pattern.compile("(\\d{1,4})\\s+(\\d{1,4})");
    private long lastMapHomeAt;

    private float mapScale(String map) {
        if (map == null) return MAP_K_DEFAULT;
        return getSharedPreferences(PREFS, MODE_PRIVATE).getFloat("map_k_" + mapKey(map), MAP_K_DEFAULT);
    }

    private static String mapKey(String map) {
        String k = map.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        return k.length() > 6 ? k.substring(0, 6) : k;
    }

    /** No monsters and halfway out already: search back toward home. */
    private boolean searchTowardHome(long now) {
        if (homeMap == null || posMap == null || !sameMap(posMap, homeMap) || now - posAt > 6000) return false;
        if (!canFarmMove(now) || now - lastMapHomeAt < MAP_HOME_GAP_MS * 2) return false;
        if (Math.hypot(homeX - posX, homeY - posY) <= leashR / 2f) return false;
        mapWalkHome(now);
        return true;
    }

    private void mapWalkHome(long now) {
        lastMapHomeAt = now;
        leashWalkEnd = now + 3000;                                  // next step after a fresh position
        busyUntil = farmHoldUntil = Math.max(busyUntil, now + MAP_OPEN_MS + 1500);
        final int hx = homeX, hy = homeY, px0 = posX, py0 = posY;
        final String map = posMap;
        tapAt(screenW * MINIMAP_X, screenH * MINIMAP_Y, "open map");
        handler.postDelayed(() -> captureHalfScreen(shot -> {
            if (shot == null) {
                Log.w(TAG, "farmer: couldn't take the map screenshot (walking home)");
                return;
            }
            boolean open = mapIsOpen(shot, 1f);
            int[] a = open ? mapCluster(shot, true) : null;
            shot.recycle();
            if (!open) {
                Log.i(TAG, "farmer: the map didn't open (walking home)");
                return;
            }
            if (a == null) {
                Log.i(TAG, "farmer: map open but our arrow isn't on it");
                closeMap();
                return;
            }
            float k = mapScale(map);
            float dx = (hx - px0) * k, dy = -(hy - py0) * k;
            // Home off the visible map: go as far as the map shows that way.
            float minX = screenW * 0.05f, maxX = screenW * 0.95f, minY = screenH * 0.15f, maxY = screenH * 0.82f, f = 1f;
            if (dx > 0 && a[0] + dx > maxX) f = Math.min(f, (maxX - a[0]) / dx);
            if (dx < 0 && a[0] + dx < minX) f = Math.min(f, (minX - a[0]) / dx);
            if (dy > 0 && a[1] + dy > maxY) f = Math.min(f, (maxY - a[1]) / dy);
            if (dy < 0 && a[1] + dy < minY) f = Math.min(f, (minY - a[1]) / dy);
            f = Math.max(0f, f);
            float tx = a[0] + dx * f, ty = a[1] + dy * f;
            Log.i(TAG, "farmer: walking home by the map: [" + px0 + "," + py0 + "] -> [" + hx + "," + hy + "], tapping "
                    + Math.round(tx) + "," + Math.round(ty) + (f < 1f ? " (map edge)" : ""));
            tapAt(tx, ty, "map home");
            busyUntil = farmHoldUntil = Math.max(busyUntil, SystemClock.uptimeMillis() + 1500);
            // The corner readout now shows where that tap is: correct the scale for this map.
            handler.postDelayed(() -> captureHalfScreen(s2 -> {
                if (s2 == null) {
                    closeMap();
                    return;
                }
                Bitmap ro = null;
                try {
                    ro = Bitmap.createBitmap(s2, Math.round(s2.getWidth() * MAP_RO_L), Math.round(s2.getHeight() * MAP_RO_T),
                            Math.round(s2.getWidth() * MAP_RO_W), Math.round(s2.getHeight() * MAP_RO_H));
                } catch (RuntimeException | OutOfMemoryError ignored) {
                }
                s2.recycle();
                closeMap();
                if (ro == null || map == null) return;
                Ocr.read(ro, (lines, words) -> {
                    for (MathQuestion.Line l : lines) {
                        java.util.regex.Matcher m = MAP_READOUT.matcher(l.text);
                        if (!m.find()) continue;
                        int rx = Integer.parseInt(m.group(1)), ry = Integer.parseInt(m.group(2));
                        float ux = rx - px0, uy = ry - py0, sum = 0;
                        int n = 0;
                        if (Math.abs(ux) >= 3) {
                            sum += (tx - a[0]) / ux;
                            n++;
                        }
                        if (Math.abs(uy) >= 3) {
                            sum += -(ty - a[1]) / uy;
                            n++;
                        }
                        if (n == 0) return;
                        float measured = sum / n;
                        if (measured < 8 || measured > 30) return;
                        // One tap is noisy (17.2 then 14.7 against 15.6, 19:21-19:24): move 30% of the way.
                        float nk = k + (measured - k) * 0.3f;
                        if (Math.abs(nk - k) > 0.2f) {
                            Log.i(TAG, "farmer: map scale for " + map + " " + k + " -> " + nk + " px per unit (tap read [" + rx + "," + ry + "])");
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("map_k_" + mapKey(map), nk).apply();
                        }
                        return;
                    }
                });
            }), 500);
        }), MAP_OPEN_MS);
    }

    /** The return-home state: true while it owns the character (walking home, nothing else). */
    private boolean returnHome(float targetHp, long now) {
        if (homeMap == null) {
            returning = false;
            return false;
        }
        boolean fresh = posMap != null && sameMap(posMap, homeMap) && now - posAt <= 6000;
        if (returning && now - returnStartedAt > RETURN_MAX_MS) {
            returning = false;
            returnGiveUpUntil = now + 60_000;
            Log.w(TAG, "farmer: couldn't get home in " + RETURN_MAX_MS / 1000 + " s (at " + posMap + "[" + posX + "," + posY
                    + "]), farming here a minute");
            Telegram.send(this, "\u26A0 Ran Online: couldn't walk back to the home spot " + homeMap + "[" + homeX + "," + homeY
                    + "], at [" + posX + "," + posY + "]. Farming there; trying again in a minute.");
            schedulePump(0);
            return false;
        }
        if (!fresh && returning && now - Math.max(posAt, returnStartedAt) > 15_000) {
            returning = false;
            returnGiveUpUntil = now + 60_000;
            Log.w(TAG, "farmer: no position read for 15 s while walking home, farming here a minute");
            schedulePump(0);
            return false;
        }
        if (!fresh) {
            if (returning && posMap != null && !sameMap(posMap, homeMap)) {
                returning = false;
                Log.i(TAG, "farmer: not on the home spot's map, attacking again");
                schedulePump(0);
            }
            return returning;
        }
        float dist = (float) Math.hypot(homeX - posX, homeY - posY);
        if (dist > HOME_MAX_DIST && homeX < 1000 && homeY < 1000) {
            // Most likely the position was misread, not the home (19:29): skip this reading.
            Log.w(TAG, "farmer: position [" + posX + "," + posY + "] is " + Math.round(dist) + " from home - ignoring that read");
            posAt = 0;
            return returning;
        }
        if (dist > HOME_MAX_DIST) {
            Log.w(TAG, "farmer: home " + homeMap + "[" + homeX + "," + homeY + "] is " + Math.round(dist) + " away - misread, clearing it");
            Telegram.send(this, "\u2693 Ran Online: the home spot " + homeMap + "[" + homeX + "," + homeY + "] looks misread ("
                    + Math.round(dist) + " away), so I cleared it - farming without a leash. Tap the anchor again at your spot.");
            homeMap = null;
            returning = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_HOME).apply();
            refreshLeashButton();
            schedulePump(0);
            return false;
        }
        if (!returning) {
            // The hand and a fresh kill's drop come first.
            if (dist <= leashR || lootStartedAt > 0 || now < postKillUntil || now < returnGiveUpUntil) return false;
            returning = true;
            returnStartedAt = now;
            leashMisses = 0;
            leashLastDist = 0;
            leashWalkEnd = 0;
            calStage = 0;
            Log.i(TAG, "farmer: " + Math.round(dist) + " from home " + homeMap + "[" + homeX + "," + homeY + "] at ["
                    + posX + "," + posY + "], no attacks until back");
            if (targetHp >= 0) tapAt(screenW * MobCounter.CLOSE_X, screenH * MobCounter.CLOSE_Y, "deselect (going home)");
            return true;
        }
        if (dist <= leashBackR()) {
            returning = false;
            Log.i(TAG, "farmer: back home (" + Math.round(dist) + " away, " + (now - returnStartedAt) / 1000 + " s), attacking again");
            farmMobsSeenAt = lastTargetBarAt = now;
            schedulePump(0);
            return false;
        }
        if (canFarmMove(now) && posAt > leashWalkEnd && now - lastMapHomeAt > MAP_HOME_GAP_MS) mapWalkHome(now);
        return true;
    }

    private boolean leashStep(long now, boolean searching) {
        if (homeMap == null || posMap == null || !sameMap(posMap, homeMap) || now - posAt > 6000) return false;
        if (!canFarmMove(now) || (!returning && farmTargetHp >= 0)) return false;
        float gx = homeX - posX, gy = homeY - posY;
        float dist = (float) Math.hypot(gx, gy);
        if (dist <= (searching ? leashR / 2f : returning ? leashBackR() : leashR)) {
            calStage = calValid ? calStage : 0;
            return false;
        }
        float push = screenW * FARM_PUSH;
        if (!calValid) {
            // Learn the mapping: push E, see the coordinates move; push N, same.
            if (calStage == 0) {
                calP0x = posX;
                calP0y = posY;
                calStage = 1;
                calWalkEnd = now + FARM_PUSH_MS + LEASH_PROBE_MS + 300;
                leashWalk(calSign * push, 0, LEASH_PROBE_MS, now);
                Log.i(TAG, "farmer: " + Math.round(dist) + " from home, learning the directions (" + (calSign > 0 ? "east" : "west") + ")");
                return true;
            }
            if (posAt < calWalkEnd) return true;                  // wait for a reading after the walk
            if (calStage == 1) {
                calEx = calSign * (posX - calP0x);                  // per push east
                calEy = calSign * (posY - calP0y);
                calP0x = posX;
                calP0y = posY;
                calStage = 2;
                calWalkEnd = now + FARM_PUSH_MS + LEASH_PROBE_MS + 300;
                leashWalk(0, -calSign * push, LEASH_PROBE_MS, now);
                Log.i(TAG, "farmer: learning the directions (" + (calSign > 0 ? "north" : "south") + ")");
                return true;
            }
            calNx = calSign * (posX - calP0x);                      // per push north
            calNy = calSign * (posY - calP0y);
            float det = calEx * calNy - calNx * calEy;
            calStage = 0;
            if (Math.abs(det) < 0.5f || Math.hypot(calEx, calEy) < 1 || Math.hypot(calNx, calNy) < 1) {
                calSign = -calSign;
                Log.i(TAG, "farmer: couldn't learn the directions (E=(" + calEx + "," + calEy + ") N=(" + calNx + "," + calNy
                        + "), blocked?), trying the other way");
                return false;
            }
            calValid = true;
            leashMisses = 0;
            leashLastDist = 0;
            Log.i(TAG, "farmer: directions learned: E=(" + calEx + "," + calEy + ") N=(" + calNx + "," + calNy + ")");
        }
        // Not getting closer after two walks: the mapping is stale (camera, slope) - learn it again.
        if (leashLastDist > 0 && dist > leashLastDist - 1 && ++leashMisses >= 2) {
            calValid = false;
            calStage = 0;
            leashMisses = 0;
            leashLastDist = 0;
            return true;
        }
        if (dist < leashLastDist - 1) leashMisses = 0;
        // Solve a*E + b*N = goal; the joystick push is (a, -b).
        float det = calEx * calNy - calNx * calEy;
        float a = (gx * calNy - calNx * gy) / det, b = (calEx * gy - gx * calEy) / det;
        float len = (float) Math.hypot(a, b);
        if (len < 1e-3f) return false;
        float step = (float) Math.hypot(calEx, calEy) + (float) Math.hypot(calNx, calNy);   // units per 2 probes
        int ms = (int) Math.max(600, Math.min(2500, LEASH_PROBE_MS * dist / Math.max(1f, step / 2f)));
        leashLastDist = dist;
        Log.i(TAG, "farmer: " + Math.round(dist) + " from home " + homeMap + "[" + homeX + "," + homeY + "] at ["
                + posX + "," + posY + "], walking back " + ms + " ms");
        leashWalk(a / len * push, -b / len * push, ms, now);
        return true;
    }

    /** Same map despite OCR slips ("TradingHole" / "TradingHolde"): first 6 letters, any case. */
    private static boolean sameMap(String a, String b) {
        String x = a.toLowerCase(java.util.Locale.ROOT), y = b.toLowerCase(java.util.Locale.ROOT);
        return x.regionMatches(0, y, 0, Math.min(6, Math.min(x.length(), y.length())));
    }

    private void leashWalk(float dx, float dy, int ms, long now) {
        leashWalkEnd = now + FARM_PUSH_MS + ms + 300;            // the next step waits for a reading after this
        walkStartThumb = lastSceneThumb;
        lastWalkShort = false;
        farmMobsSeenAt = now;
        long window = FARM_PUSH_MS + ms;
        ownTapUntil = now + window + OWN_TAP_SLACK_MS;
        busyUntil = farmHoldUntil = now + window + FARM_WALK_SETTLE_MS;
        joystickHold(dx, dy, ms);
    }

    /** One drag across empty ground (upper middle) turns the camera. */
    private void turnCamera(String why) {
        long now = SystemClock.uptimeMillis();
        if (!running || !farmer || returning || now - lastCameraTurnAt < CAMERA_TURN_GAP_MS || !canFarmMove(now)) return;
        lastCameraTurnAt = now;
        float x0 = screenW * CAMERA_DRAG_X, y = screenH * CAMERA_DRAG_Y;
        Path drag = new Path();
        drag.moveTo(x0, y);
        drag.lineTo(x0 + screenW * CAMERA_DRAG_W, y);
        ownTapUntil = now + CAMERA_DRAG_MS + OWN_TAP_SLACK_MS;
        busyUntil = farmHoldUntil = Math.max(farmHoldUntil, now + CAMERA_DRAG_MS + 300);
        Log.i(TAG, "farmer: turning the camera (" + why + ")");
        calValid = false;
        calStage = 0;
        cameraDrag(drag);
    }

    private void cameraDrag(Path drag) {
        if (!gestureClear(CAMERA_DRAG_MS, () -> cameraDrag(drag))) return;
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(drag, 0, CAMERA_DRAG_MS)).build(), null, null);
    }

    /** Where the selected target stands: its name tag (matching the target bar title), body below. */
    private void noteKillSpot(List<MathQuestion.Line> lines) {
        if (farmTargetHp < 0) return;
        String key = null;
        for (MathQuestion.Line l : lines) {
            float mid = l.box.exactCenterX();
            if (l.box.bottom <= screenH * TARGET_NAME_MAX_Y && mid > screenW * 0.3f && mid < screenW * 0.7f) {
                key = monsterKey(l.text);
                break;
            }
        }
        if (key == null || key.length() < 4) return;
        Rect best = null;
        float bestD = screenW * 0.4f;
        for (MathQuestion.Line l : lines) {
            if (l.box.bottom <= screenH * HUD_TOP_H) continue;
            String k = monsterKey(l.text);
            if (k.length() < 4 || !(k.equals(key) || k.contains(key) || key.contains(k) && k.length() >= 6)) continue;
            float d = fromCharacter(l.box);
            if (d < bestD) {
                bestD = d;
                best = l.box;
            }
        }
        if (best == null) {
            Log.d(TAG, "farmer: target \"" + key + "\" - its name tag not found on screen");
            // Only worth it when the fight isn't going anywhere: most misses are misread names
            // ("todel", "hoda") on a monster dying normally (6 turns in 7 min, 08:56-09:03).
            if (++tagMissStreak >= CAMERA_TAG_MISSES && SystemClock.uptimeMillis() - farmProgressAt > 5000) {
                tagMissStreak = 0;
                turnCamera("the target's name tag is hidden");
            }
            return;
        }
        tagMissStreak = 0;
        killSpotX = best.exactCenterX();
        killSpotY = best.bottom + best.height() * LURE_BODY_BELOW;
        killSpotAt = SystemClock.uptimeMillis();
    }

    /*
     * The chat box hides itself (a game setting, the user 2026-10-06). Hidden, it's a white "..."
     * speech bubble (body ~84x58 px, three dark dots) next to the loot button, and moves around;
     * a tap on it opens the chat again. Looked for in the chat reads of Farmer and FS.
     */
    private static final float CHATB_L = 0.28f, CHATB_T = 0.74f, CHATB_R = 0.76f, CHATB_B = 0.99f;
    private static final int CHAT_OPEN_GAP_MS = 15_000, CHAT_OPEN_TRIES = 4, CHAT_OPEN_BACKOFF_MS = 5 * 60_000;
    private long chatOpenTapAt;
    private int chatOpenTries;

    /** Taps the "..." bubble if bmp (whose top-left is at ox,oy on screen) shows it. */
    private void openChatIfHidden(Bitmap bmp, int ox, int oy) {
        long now = SystemClock.uptimeMillis();
        if (now - chatOpenTapAt < (chatOpenTries >= CHAT_OPEN_TRIES ? CHAT_OPEN_BACKOFF_MS : CHAT_OPEN_GAP_MS)) return;
        float[] c;
        try {
            c = chatBubble(bmp, ox, oy);
        } catch (RuntimeException | OutOfMemoryError e) {
            return;
        }
        if (c == null) {
            chatOpenTries = 0;
            return;
        }
        if (chatOpenTries >= CHAT_OPEN_TRIES) chatOpenTries = 0;   // after the back-off, try again
        chatOpenTapAt = now;
        chatOpenTries++;
        Log.i(TAG, "chat hidden: tapping the \"...\" bubble at " + Math.round(c[0]) + "," + Math.round(c[1])
                + " (try " + chatOpenTries + ")");
        tapAt(c[0], c[1], "chat open");
    }

    /** Centre of the "..." bubble in screen pixels, or null. */
    private float[] chatBubble(Bitmap bmp, int ox, int oy) {
        float s = screenW / 2560f;
        int x0 = Math.max(0, Math.round(screenW * CHATB_L) - ox), y0 = Math.max(0, Math.round(screenH * CHATB_T) - oy);
        int x1 = Math.min(bmp.getWidth(), Math.round(screenW * CHATB_R) - ox);
        int y1 = Math.min(bmp.getHeight(), Math.round(screenH * CHATB_B) - oy);
        int w = x1 - x0, h = y1 - y0, bw = Math.round(60 * s), bh = Math.round(40 * s);
        if (w <= bw || h <= bh) return null;
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, x0, y0, w, h);
        boolean[] white = new boolean[w * h];
        int[] sum = new int[(w + 1) * (h + 1)];                 // integral image of the white pixels
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                white[y * w + x] = Math.min((p >> 16) & 0xff, Math.min((p >> 8) & 0xff, p & 0xff)) > 235;
                sum[(y + 1) * (w + 1) + x + 1] = (white[y * w + x] ? 1 : 0) + sum[y * (w + 1) + x + 1]
                        + sum[(y + 1) * (w + 1) + x] - sum[y * (w + 1) + x];
            }
        }
        int need = Math.round(bw * bh * 0.9f), checks = 0;
        for (int y = 0; y + bh <= h; y += 2) {
            for (int x = 0; x + bw <= w; x += 2) {
                int n = sum[(y + bh) * (w + 1) + x + bw] - sum[y * (w + 1) + x + bw] - sum[(y + bh) * (w + 1) + x] + sum[y * (w + 1) + x];
                if (n < need) continue;
                float[] c = bubbleAt(px, white, w, h, x + bw / 2, y + bh / 2, s);
                if (c != null) return new float[]{c[0] + x0 + ox, c[1] + y0 + oy};
                if (++checks > 20) return null;                 // a big white area, not the bubble
                x += bw;
            }
        }
        return null;
    }

    /** The white blob around (cx,cy): bubble-sized with a row of dark dots inside? Its centre, else null. */
    private static float[] bubbleAt(int[] px, boolean[] white, int w, int h, int cx, int cy, float s) {
        // Edges: walk out while the column/row is mostly white, stepping over the dots (a short gap).
        int gap = Math.max(4, Math.round(12 * s));
        int l = bubbleEdge(white, w, h, cx, cy, -1, true, gap), r = bubbleEdge(white, w, h, cx, cy, 1, true, gap);
        int t = bubbleEdge(white, w, h, cx, cy, -1, false, gap), b = bubbleEdge(white, w, h, cx, cy, 1, false, gap);
        int bw = r - l + 1, bh = b - t + 1;
        if (bw < 66 * s || bw > 110 * s || bh < 44 * s || bh > 80 * s) return null;
        // The dots: dark pixels inside, all in one thin band.
        int dark = 0, top = Integer.MAX_VALUE, bottom = -1;
        for (int y = t + 2; y <= b - 2; y++) {
            for (int x = l + 2; x <= r - 2; x++) {
                int p = px[y * w + x];
                if (Math.max((p >> 16) & 0xff, Math.max((p >> 8) & 0xff, p & 0xff)) < 110) {
                    dark++;
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (dark < 20 * s * s || dark > 700 * s * s || bottom - top > 18 * s) return null;   // 52-59 measured
        return new float[]{(l + r) / 2f, (t + b) / 2f};
    }

    /** How far the white blob reaches from (cx,cy) going dir along x (horizontal) or y. */
    private static int bubbleEdge(boolean[] white, int w, int h, int cx, int cy, int dir, boolean horizontal, int gap) {
        int pos = horizontal ? cx : cy, lim = horizontal ? w - 1 : h - 1;
        while (true) {
            int next = -1;
            for (int k = 1; k <= gap; k++) {
                int q = pos + dir * k;
                if (q < 0 || q > lim) break;
                boolean ok = horizontal ? whiteRun(white, w, q, cy - 6, q, cy + 6) >= 9
                        : whiteRun(white, w, cx - 15, q, cx + 15, q) >= 24;
                if (ok) {
                    next = q;
                    break;
                }
            }
            if (next < 0) return pos;
            pos = next;
        }
    }

    private static int whiteRun(boolean[] white, int w, int xa, int ya, int xb, int yb) {
        int n = 0, h = white.length / w;
        for (int y = Math.max(0, ya); y <= Math.min(h - 1, yb); y++) {
            for (int x = Math.max(0, xa); x <= Math.min(w - 1, xb); x++) if (white[y * w + x]) n++;
        }
        return n;
    }

    // PK time (the user asked, 2026-10-06): the chat says "The PK Period among schools has began.
    // You can only attack students outside the campus." -> a Telegram (once per PK_ALERT_GAP_MS).
    private static final int PK_ALERT_GAP_MS = 30 * 60_000;
    private long pkAlertAt;

    private void checkPkLine(String text) {
        String k = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        if (!k.contains("pkperiod") || !(k.contains("began") || k.contains("begun") || k.contains("start"))) return;
        long now = SystemClock.uptimeMillis();
        if (pkAlertAt != 0 && now - pkAlertAt < PK_ALERT_GAP_MS) return;
        pkAlertAt = now;
        Log.w(TAG, "PK period started: \"" + text + "\"");
        Telegram.send(this, "⚔ Ran Online: PK time has started (\"" + text.trim() + "\").");
    }

    /** Reads the chat box for pickups and gold; each line counted once as the chat scrolls. */
    private void farmChatRead(Bitmap shot) {
        openChatIfHidden(shot, 0, 0);
        int x = Math.round(screenW * LOOT_CHAT_L), y = Math.round(screenH * LOOT_CHAT_T);
        int w = Math.min(Math.round(screenW * LOOT_CHAT_W), shot.getWidth() - x);
        int h = Math.min(Math.round(screenH * LOOT_CHAT_H), shot.getHeight() - y);
        if (w <= 0 || h <= 0) return;
        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(shot, x, y, w, h);
        } catch (RuntimeException | OutOfMemoryError e) {
            return;
        }
        Ocr.read(crop, (lines, words) -> {
            List<MathQuestion.Line> sorted = new ArrayList<>(lines);
            sorted.sort((a, b) -> Integer.compare(a.box.top, b.box.top));
            // Only the loot lines, as "gold 367" / "item big mp recovery potion": the red "Skill
            // cooldown time." spam half under the pet icon read differently every time, so matching
            // whole chat reads never lined up and one gold drop was counted 3 times (07:12:54-07:13:02).
            List<String> cur = new ArrayList<>(), curKeys = new ArrayList<>();
            for (MathQuestion.Line l : sorted) {
                checkPkLine(l.text);
                String entry = lootEntry(l.text);
                if (entry == null) continue;
                cur.add(entry);
                curKeys.add(entry.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", ""));   // OCR slips
            }
            boolean chatSeen = !sorted.isEmpty();
            if (!chatSeen) return;                           // chat hidden
            // New ones come after the longest overlap with the last read (old ones scroll off the top).
            int overlap = 0;
            for (int k = Math.min(lastChatLines.size(), curKeys.size()); k > 0; k--) {
                if (lastChatLines.subList(lastChatLines.size() - k, lastChatLines.size()).equals(curKeys.subList(0, k))) {
                    overlap = k;
                    break;
                }
            }
            boolean firstRead = !chatPrimed;
            chatPrimed = true;
            // Nothing in common although both reads had loot: the chat scrolled a lot; only count
            // what's new at the bottom rather than everything (no double counts).
            lastChatLines = curKeys;
            if (firstRead) return;                           // what's already there isn't ours to count
            if (overlap < cur.size()) lastPickupAt = SystemClock.uptimeMillis();
            for (int i = overlap; i < cur.size(); i++) countLootEntry(cur.get(i));
        });
    }

    /** "gold 367" or "item <name>" for a loot line, else null. */
    private static String lootEntry(String line) {
        java.util.regex.Matcher g = GOLD_LINE.matcher(line);
        if (g.find()) {
            String n = g.group(1).replaceAll("[^0-9]", "");
            return n.isEmpty() ? null : "gold " + n;
        }
        java.util.regex.Matcher m = PICKUP_LINE.matcher(line);
        if (!m.find()) return null;
        String item = m.group(1).trim();
        return item.length() < 3 ? null : "item " + item;
    }

    private void countLootEntry(String entry) {
        if (entry.startsWith("gold ")) {
            try {
                long n = Long.parseLong(entry.substring(5));
                if (n > 0 && n < 10_000_000) lootGold += n;
                Log.i(TAG, "loot: +" + n + " gold");
            } catch (NumberFormatException ignored) {
            }
            return;
        }
        String item = entry.substring(5);
        lootItems.merge(item, 1, Integer::sum);
        String lk = item.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        if (lk.length() >= 5 && knownLoot.add(lk)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_LOOT_NAMES, new java.util.HashSet<>(knownLoot)).apply();
        }
        Log.i(TAG, "loot: " + item);
    }

    /** Every LOOT_REPORT_MS while farming: what was picked up, to Telegram. */
    private void lootReportTick() {
        handler.postDelayed(lootReportTick, LOOT_REPORT_MS);
        if (!running || !farmer) return;
        long now = System.currentTimeMillis();
        if (lootGold == 0 && lootItems.isEmpty()) {
            lootReportFrom = now;
            return;
        }
        java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder("\uD83C\uDF92 Loot " + hm.format(new java.util.Date(lootReportFrom))
                + "-" + hm.format(new java.util.Date(now)) + "\nGold: " + String.format(java.util.Locale.ROOT, "%,d", lootGold));
        for (java.util.Map.Entry<String, Integer> e : lootItems.entrySet()) {
            sb.append("\n").append(e.getValue()).append(" x ").append(e.getKey());
        }
        Telegram.send(this, sb.toString());
        Log.i(TAG, "loot report sent: " + sb.toString().replace('\n', '|'));
        lootGold = 0;
        lootItems.clear();
        lootReportFrom = now;
    }

    private boolean canFarmMove(long now) {
        // Not mid-walk/loot, and not during an attack tap (a new gesture would cancel it). The
        // attack lock (busyUntil) is ignored: attacks come so often it would never let go.
        // Nor during a buff's cast: a walk right after the buff tap cancelled it (13:36).
        return !questionSeen && now >= deadUntil && now >= farmHoldUntil && now - lastAnyTapAt >= TAP_MS + 50
                && now - lastBuffTapAt >= TAP_MS + FARM_AFTER_BUFF_MS
                && now - userTouchAt >= USER_TOUCH_PAUSE_MS && boosterCanAct();
    }

    /** One step of the E, N, W, S walk; attacks hold off until it's done. */
    private void farmWalk(long now) {
        if (!running || !farmer) return;
        // Spiral outward (the maps are big, 13:34): legs of 1,1,2,2,3,3,4,4 steps, turning E N W S,
        // then start small again so it doesn't wander off for good.
        int[] dir = FARM_WALK_DIRS[farmWalkStep];
        walkStartThumb = lastSceneThumb;
        lastWalkShort = false;
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

    /** True while a panel covers the game: everything else waits until it's closed. */
    private boolean farmPanelCheck(boolean hidden, long now) {
        if (!hidden || questionSeen) {
            if (panelCloseTries > 0) Log.i(TAG, "farmer: panel closed, carrying on");
            hudHiddenScans = panelCloseTries = 0;
            return false;
        }
        busyUntil = farmHoldUntil = Math.max(farmHoldUntil, now + FARM_SCAN_MS + 500);
        if (hudHiddenScans++ == 0) hudHiddenSince = now;
        // Three scans in a row, and a text read since it appeared that found no question and no
        // Yes/No-style dialog: X doesn't close those ("Summon your pet?" got 3 X taps, 07:42) and
        // with nothing else open X brings up the Server List.
        if (now < panelQuietUntil || lastOcrHadDialog) return true;
        if (hudHiddenScans < 3 || lastOcrResultAt <= hudHiddenSince) return true;
        if (panelCloseTries >= PANEL_MAX_TRIES) {
            if (panelCloseTries++ == PANEL_MAX_TRIES) {
                Log.w(TAG, "farmer: a panel is still covering the game after " + PANEL_MAX_TRIES + " X taps");
                Telegram.send(this, "⚠️ Ran Online: a game panel is covering the screen and X didn't close it. Farmer is waiting.");
            }
            return true;
        }
        panelCloseTries++;
        hudHiddenScans = 0;
        Log.i(TAG, "farmer: a panel covers the game (skill buttons gone), tapping its X (try " + panelCloseTries + ")");
        tapAt(screenW * PANEL_X_X, screenH * PANEL_X_Y, "panel X");
        return true;
    }

    /** Remembers which way the nearest monster beyond the followers is, for lureSearchWalk. */
    private void noteMonstersSeen(List<Rect> tags, long now) {
        float best = Float.MAX_VALUE;
        for (Rect tag : tags) {
            float d = fromCharacter(tag);
            if (d <= screenW * LURE_NEAR_W || d >= best) continue;
            best = d;
            seenDirX = (tag.exactCenterX() - screenW * 0.5f) / d;
            seenDirY = (tag.exactCenterY() - screenH * 0.53f) / d;
        }
        if (best < Float.MAX_VALUE) monstersSeenFarAt = now;
    }

    /** Luring, none in range: walk toward the last monsters seen, or straight on to explore. */
    private void lureSearchWalk(int followers, long now) {
        float push = screenW * FARM_PUSH;
        boolean blocked = now < blockedUntil;
        boolean seenFresh = now - monstersSeenFarAt < LURE_SEEN_FRESH_MS
                && !(blocked && seenDirX * blockedDirX + seenDirY * blockedDirY > 0.7f);   // within ~45 deg
        float ux, uy;
        int walkMs;
        String where;
        if (seenFresh) {
            ux = seenDirX;
            uy = seenDirY;
            walkMs = LURE_TOWARD_MS;
            where = "toward monsters seen " + (now - monstersSeenFarAt) / 1000 + " s ago";
            monstersSeenFarAt = 0;                  // once; then explore if they're gone
        } else {
            if (++exploreSteps > LURE_EXPLORE_TURN_STEPS) {
                exploreSteps = 1;
                farmWalkStep = (farmWalkStep + 1) % FARM_WALK_DIRS.length;
            }
            int[] dir = FARM_WALK_DIRS[farmWalkStep];
            if (blocked && dir[0] * blockedDirX + dir[1] * blockedDirY > 0.7f) {
                farmWalkStep = (farmWalkStep + 1) % FARM_WALK_DIRS.length;     // not into that wall again
                exploreSteps = 1;
                dir = FARM_WALK_DIRS[farmWalkStep];
            }
            ux = dir[0];
            uy = dir[1];
            walkMs = LURE_EXPLORE_MS;
            where = "exploring " + "ENWS".charAt(farmWalkStep);
        }
        Log.i(TAG, "farmer: luring (" + followers + " following), none in range, walking " + where);
        walkStartThumb = lastSceneThumb;
        walkDirX = ux;
        walkDirY = uy;
        lastWalkShort = true;
        farmMobsSeenAt = now;
        long window = FARM_PUSH_MS + walkMs;
        ownTapUntil = now + window + OWN_TAP_SLACK_MS;
        busyUntil = farmHoldUntil = now + window + FARM_WALK_SETTLE_MS;
        joystickHold(ux * push, uy * push, walkMs);
    }

    /** After a walk: if the scene barely changed, a wall stopped it - turn (twice in a row: go back). */
    private void farmWallCheck(int diff) {
        boolean shortWalk = lastWalkShort;
        lastWalkShort = false;
        if (diff >= (shortWalk ? WALL_SCENE_DIFF_SHORT : WALL_SCENE_DIFF)) {
            Log.d(TAG, "farmer: walk moved the scene by " + diff);
            wallsInARow = 0;
            return;
        }
        wallsInARow++;
        if (wallsInARow >= 2) turnCamera("walls in the way");
        if (shortWalk) {                                // a lure step: avoid that heading a while
            blockedDirX = walkDirX;
            blockedDirY = walkDirY;
            blockedUntil = SystemClock.uptimeMillis() + LURE_BLOCKED_MS;
        }
        int turn = wallsInARow >= 2 ? 2 : 1;
        farmWalkStep = (farmWalkStep + turn) % FARM_WALK_DIRS.length;
        farmLegDone = 0;
        exploreSteps = 0;
        Log.i(TAG, "farmer: walk barely moved the scene (" + diff + "), a wall? turning to "
                + "ENWS".charAt(farmWalkStep));
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
                boolean dialog = false;
                for (MathQuestion.Line l : lines) {
                    String k = l.text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
                    if (k.equals("yes") || k.equals("no") || k.equals("revive") || k.equals("ok") || k.equals("cancel")
                            || k.equals("confirm") || k.contains("summonyourpet")) dialog = true;
                }
                lastOcrHadDialog = dialog;
                lastOcrResultAt = SystemClock.uptimeMillis();
                checkTargetName(lines);
                noteKillSpot(lines);
                noteDrops(lines);
                long seen = SystemClock.uptimeMillis();
                List<Rect> tags = monsterTags(lines, crop, x, y);
                noteMonstersSeen(tags, seen);
                for (Rect tag : tags) if (fromCharacter(tag) <= screenW * MONSTER_NEAR_W) farmMobsSeenAt = nearTagAt = seen;
                lureStep(tags, seen);
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
            boolean gaveUp = now - lootStartedAt >= LOOT_MAX_PAUSE_MS
                    || now - Math.max(lootStartedAt, lastPickupAt) >= LOOT_STALL_MS;
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
            Log.i(TAG, "farmer: " + (handShowing ? "couldn't pick it up in " + (now - lootStartedAt) / 1000
                    + " s, leaving it" : "picked up") + ", attacking again");
            if (handShowing) {
                lootIgnoreUntil = now + LOOT_IGNORE_MS;
                // A few failures in a row with nothing picked up: the bag is full (12:55, the user).
                // Stop pausing the fight for loot for a while, and say so. Only full-length tries
                // count: three quick 5 s give-ups while the leash pulled it about paused looting for
                // 5 min (08:49), and the user saw "not looting".
                if (now - lootStartedAt >= LOOT_MAX_PAUSE_MS && ++lootFailStreak >= LOOT_FAILS_TO_PAUSE) {
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
        if (handShowing && now >= lootIgnoreUntil && !returning) {
            lastHandSeenAt = now;
            // No skill while the hand shows, even if the pickup must wait for a cast to end: a full
            // buff (Power Kick, Blood Lust) and an attack went out with the hand up (07:13:57).
            busyUntil = Math.max(busyUntil, now + FARM_SCAN_MS + 500);
        }
        if (!handShowing || now < lootIgnoreUntil || questionSeen || !canFarmMove(now)) return;
        // The hand always wins, fight or not: no skill and no walking while it shows (the user's rule,
        // 12:15 and again 2026-10-05 07:10 - holding it back for the fight left drops behind).
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
        if (!gestureClear(FARM_PUSH_MS + holdMs + 100, () -> joystickHold(dx, dy, holdMs))) return;
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
        if (!gestureClear(MOVE_MS, () -> joystickPush(dx))) return;
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
    /** Follow: every FOLLOW_TICK_MS read the Team list and the play area, then step toward the leader. */
    private void followTick() {
        handler.postDelayed(followTick, FOLLOW_TICK_MS);
        long now = SystemClock.uptimeMillis();
        if (!running || !follow || manual || questionSeen || now < followHoldUntil || !boosterCanAct()) return;
        if (now - userTouchAt < USER_TOUCH_PAUSE_MS) return;
        String game = gamePackage != null ? gamePackage : DEFAULT_GAME;
        // One region from the top-left corner (OCR boxes are screen pixels): Team list + play area.
        captureRegionForOcr(0f, 0f, 0.92f, 0.74f, shot -> {
            if (shot == null) return;
            // A big map left open hides the Team list (and nothing else would close it, 13:00).
            if (mapIsOpen(shot, 0.74f)) {
                shot.recycle();
                // X doesn't close it while a portal's "Move to the area" is up (13:04, 10 tries).
                // Then tap the map a little off our arrow: walking off the portal drops the popup
                // (the user, 13:05), and X works again.
                if (++mapCloseTries % 2 == 1) {
                    Log.i(TAG, "follow: the big map is open, closing it");
                    closeMap();
                } else {
                    stepOffViaMap();
                }
                return;
            }
            mapCloseTries = 0;
            Ocr.read(shot, (lines, words) -> {
                checkForQuestion(game, lines);              // the math check, from the same read
                if (running && follow && !questionSeen) followStep(lines);
            });
        });
    }

    private void followStep(List<MathQuestion.Line> lines) {
        long now = SystemClock.uptimeMillis();
        // The party master: the first name under "Team" at the top left (the M row). Personal store
        // signs overlap that corner in town ("ELITE SET +5 SCROLLS REFINES..." was taken for the
        // master, 12:36), so rows hang under the "Team" header and must look like a player name.
        Rect header = null;
        for (MathQuestion.Line l : lines) {
            if (inTeamList(l.box) && l.text.trim().toLowerCase(java.util.Locale.ROOT).matches("team\\W*")) header = l.box;
        }
        MathQuestion.Line first = null;
        StringBuilder seen = new StringBuilder();
        for (MathQuestion.Line l : lines) {
            if (!inTeamList(l.box) || l.text.toLowerCase(java.util.Locale.ROOT).contains("team")) continue;
            if (header == null) continue;                     // Team list hidden (big map open)
            if (header != null && (l.box.top < header.top + header.height() / 2 || l.box.top > header.bottom + screenH * 0.12f
                    || l.box.left > header.left + screenW * 0.08f)) continue;
            seen.append(" | ").append(l.text.trim());
            if (!looksLikePlayerName(stripRowMark(l.text))) continue;
            if (first == null || l.box.top < first.box.top) first = l;
        }
        Rect moveBtn = null;
        boolean portalAsks = false;
        for (MathQuestion.Line l : lines) {
            String tl = l.text.toLowerCase(java.util.Locale.ROOT);
            if (tl.contains("move to the area")) portalAsks = true;
            else if (tl.replaceAll("[^a-z]", "").equals("move")) moveBtn = l.box;
        }
        if (portalAsks && !portalChase) {
            // Standing on a portal we don't want (e.g. the one we just came out of): its popup blocks
            // taps on the map, so step off it (the user, 13:01). Another direction each time.
            float[][] dirs = {{0, 1}, {1, 0}, {-1, 0}, {0, -1}};
            float[] d = dirs[portalStepDir++ % dirs.length];
            float push = screenW * FARM_PUSH;
            Log.i(TAG, "follow: on a portal's 'Move to the area' - stepping off it");
            followWalk(d[0] * push, d[1] * push, 800, now);
            return;
        }
        if (portalChase) {
            if (portalAsks && moveBtn != null) {
                Log.i(TAG, "follow: at the portal the master took - Move");
                tapAt(moveBtn.exactCenterX(), moveBtn.exactCenterY(), "portal move");
                portalChase = false;
                lastMapM = null;
                followHoldUntil = now + 6000;                   // the new map loads
                return;
            }
        }
        if (now - lastTeamDumpAt > 10_000) {
            lastTeamDumpAt = now;
            Log.d(TAG, "follow: team rows" + (header == null ? " (no header)" : "") + ":" + seen);
        }
        if (first != null) {
            String name = stripRowMark(first.text);
            String key = nameKey(name);
            if (leaderKey == null || !looseName(key, leaderKey)) {
                leaderKey = key;
                leaderShown = name;
                leaderSeenAt = 0;
                Log.i(TAG, "follow: party master is \"" + name + "\"");
            }
            followNoPartyLogged = false;
        } else if (leaderKey == null) {
            // Team list collapsed (13:19): no name to look for on screen, but the big map's M is
            // the party master all the same.
            if (!followNoPartyLogged) Log.i(TAG, "follow: no Team list on screen - following the M on the map");
            followNoPartyLogged = true;
            if (now - lastMapFollowAt >= MAP_FOLLOW_GAP_MS) mapFollow(now);
            return;
        }
        // Their name tag in the world (not the Team list, the top strip or the minimap).
        Rect tag = null;
        String tagKey = null;
        float tagDist = Float.MAX_VALUE;
        for (MathQuestion.Line l : lines) {
            if (inTeamList(l.box) || l.box.bottom <= screenH * HUD_TOP_H
                    || (l.box.right > screenW * 0.76f && l.box.top < screenH * 0.32f)) continue;
            String k = nameKey(l.text);
            if (!looseName(k, leaderKey) || !looksLikePlayerName(l.text)) continue;
            float d = fromCharacter(l.box);
            if (d < tagDist) {
                tag = l.box;
                tagDist = d;
                tagKey = k;
            }
        }
        if (tag != null && !tagKey.equals(leaderKey)) {
            // The name tag in the world reads cleanly (no HP bar behind it): learn that spelling.
            Log.i(TAG, "follow: party master's name tag reads \"" + tagKey + "\" (Team list read \"" + leaderKey + "\")");
            leaderKey = tagKey;
            leaderShown = tagKey;
        }
        float push = screenW * FARM_PUSH;
        if (tag != null) {
            leaderSeenAt = now;
            followLostSteps = 0;
            followLostAlerted = false;
            leaderDirX = (tag.exactCenterX() - screenW * 0.5f) / Math.max(1f, tagDist);
            leaderDirY = (tag.exactCenterY() - screenH * 0.53f) / Math.max(1f, tagDist);
            if (tagDist <= screenW * FOLLOW_NEAR_W) return;            // close enough (or it's us)
            // Didn't get closer on the last walk: something's in the way, step sideways a moment.
            boolean blocked = lastWalkLeaderDist > 0 && tagDist > lastWalkLeaderDist * 0.85f
                    && now - lastFollowWalkAt < FOLLOW_TICK_MS * 3;
            float dx = leaderDirX, dy = leaderDirY;
            int ms = (int) Math.max(400, Math.min(2000, tagDist * 1000f / LURE_RUN_PX_PER_S * 0.8f));
            if (blocked) {
                followSideSign = -followSideSign;
                dx = -leaderDirY * followSideSign;
                dy = leaderDirX * followSideSign;
                ms = 900;
            }
            lastWalkLeaderDist = blocked ? 0 : tagDist;
            Log.i(TAG, "follow: " + leaderShown + " is " + Math.round(tagDist) + " px away, "
                    + (blocked ? "blocked, stepping aside" : "walking " + ms + " ms toward them"));
            followWalk(dx * push, dy * push, ms, now);
            return;
        }
        // Not on screen: the big map shows the party master as an "M" icon, and tapping a spot on
        // it walks there by itself, around walls (the user's idea, 12:45).
        lastWalkLeaderDist = 0;
        if (now - lastMapFollowAt >= MAP_FOLLOW_GAP_MS) {
            mapFollow(now);
            return;
        }
        if (leaderSeenAt == 0) {
            if (!followWaitLogged) Log.i(TAG, "follow: waiting for " + leaderShown + " to come on screen");
            followWaitLogged = true;
            return;
        }
        followWaitLogged = false;
        if (followLostSteps < FOLLOW_LOST_STEPS && now - leaderSeenAt < FOLLOW_LOST_ALERT_MS) {
            followLostSteps++;
            Log.i(TAG, "follow: " + leaderShown + " not on screen, walking where they were last seen ("
                    + followLostSteps + "/" + FOLLOW_LOST_STEPS + ")");
            followWalk(leaderDirX * push, leaderDirY * push, 1500, now);
        } else if (now - leaderSeenAt >= FOLLOW_LOST_ALERT_MS && !followLostAlerted) {
            followLostAlerted = true;
            Log.w(TAG, "follow: lost " + leaderShown + " for " + (now - leaderSeenAt) / 1000 + " s");
            Telegram.send(this, "\uD83E\uDDED Ran Online: lost the party master (" + leaderShown
                    + ") for a minute. Follow is waiting where it is.");
        }
    }

    // The minimap (top right) opens the big map; X (top right) closes it.
    private static final float MINIMAP_X = 2300 / 2560f, MINIMAP_Y = 270 / 1600f;
    private static final int MAP_OPEN_MS = 1200, MAP_FOLLOW_GAP_MS = 5000, MAP_WALK_MS = 4000;
    private long lastMapFollowAt;
    // Through portals (the user, 12:53: "the bot has to follow it after the portal"): the M leaves
    // the map where the master stepped through, so walk to where it was last seen, then a little
    // past it the same way to step in. The new map shows the M again.
    private static final int LAST_M_KEEP_MS = 120_000, PORTAL_PUSHES = 3;
    private int[] lastMapM, lastMapArrow;
    private String lastMapMMap;
    private boolean portalChase;
    private int portalStepDir, mapCloseTries;
    private long lastMapMAt;
    private int portalPushes;

    private void mapFollow(long now) {
        lastMapFollowAt = now;
        followHoldUntil = now + MAP_OPEN_MS + 3500;             // room for a refused screenshot's retry
        tapAt(screenW * MINIMAP_X, screenH * MINIMAP_Y, "open map");
        handler.postDelayed(() -> captureHalfScreen(shot -> {
            if (shot == null) {
                Log.w(TAG, "follow: couldn't take the map screenshot");
                return;
            }
            boolean open = mapIsOpen(shot, 1f);
            int[] arrow = open ? mapCluster(shot, true) : null, m = open ? findMapM(shot) : null;
            Bitmap coord = null;
            try {
                int cx = Math.round(shot.getWidth() * COORD_L), cy = Math.round(shot.getHeight() * COORD_T);
                coord = Bitmap.createBitmap(shot, cx, cy, Math.round(shot.getWidth() * COORD_W),
                        Math.min(Math.round(shot.getHeight() * COORD_H), shot.getHeight() - cy));
            } catch (RuntimeException | OutOfMemoryError ignored) {
            }
            shot.recycle();
            if (coord == null) {
                mapDecide(open, arrow, m, null);
                return;
            }
            Ocr.read(coord, (lines, words) -> {
                String map = null;
                for (MathQuestion.Line l : lines) {
                    java.util.regex.Matcher mm = COORD_TEXT.matcher(l.text);
                    if (mm.find()) map = mm.group(1);
                }
                mapDecide(open, arrow, m, map);
            });
        }), MAP_OPEN_MS);
    }

    /** What to do with one look at the big map (map = the map's name from the coordinates line). */
    private void mapDecide(boolean open, int[] arrow, int[] m, String map) {
        {
            long t = SystemClock.uptimeMillis();
            if (!open) {
                // Skill buttons pass for the arrow and an orange pole for the M on the normal screen,
                // so only the map's own title bar says it's open. Don't press X (Server List).
                Log.i(TAG, "follow: the map didn't open");
                return;
            }
            // The last M spot only means something on the map it was seen on (not after a portal).
            boolean sameMapAsLastM = lastMapMMap == null || map == null || sameMap(map, lastMapMMap);
            if (m == null && arrow != null && lastMapM != null && t - lastMapMAt < LAST_M_KEEP_MS && sameMapAsLastM) {
                portalChase = true;
                float away = (float) Math.hypot(lastMapM[0] - arrow[0], lastMapM[1] - arrow[1]);
                if (away > screenW * 0.025f) {
                    Log.i(TAG, "follow: no M on this map - walking to where it was last seen " + lastMapM[0] + "," + lastMapM[1]
                            + " (a portal?)");
                    tapAt(lastMapM[0], lastMapM[1], "map last M");
                } else if (portalPushes < PORTAL_PUSHES) {
                    // There already: a bit further the way we came, to step into the portal.
                    float dx = lastMapM[0] - lastMapArrow[0], dy = lastMapM[1] - lastMapArrow[1];
                    float len = Math.max(1f, (float) Math.hypot(dx, dy));
                    float step = screenW * 0.02f * ++portalPushes;
                    float tx = lastMapM[0] + dx / len * step, ty = lastMapM[1] + dy / len * step;
                    Log.i(TAG, "follow: at the spot the M left from, stepping on toward " + Math.round(tx) + "," + Math.round(ty)
                            + " (" + portalPushes + "/" + PORTAL_PUSHES + ")");
                    tapAt(tx, ty, "map past last M");
                } else {
                    Log.i(TAG, "follow: no M on this map and the portal steps didn't take me through");
                    lastMapM = null;
                }
                followHoldUntil = t + MAP_WALK_MS;
                handler.postDelayed(this::closeMap, 600);
                return;
            }
            if (arrow == null || m == null) {
                Log.i(TAG, "follow: map open" + (map != null ? " (" + map + ")" : "") + ", "
                        + (m == null ? "no M on it (another map, or right under our arrow)" : "can't find our arrow"));
                closeMap();
                return;
            }
            // Seen on this map: remember where, and from where we were looking (the way in).
            if (lastMapM == null || Math.hypot(m[0] - lastMapM[0], m[1] - lastMapM[1]) > 4) lastMapArrow = arrow;
            lastMapM = m;
            lastMapMAt = t;
            lastMapMMap = map;
            portalPushes = 0;
            portalChase = false;
            float d = (float) Math.hypot(m[0] - arrow[0], m[1] - arrow[1]);
            if (d < screenW * 0.03f) {
                Log.i(TAG, "follow: map: the M is right by us");
                closeMap();
                return;
            }
            Log.i(TAG, "follow: map: M at " + m[0] + "," + m[1] + ", us at " + arrow[0] + "," + arrow[1]
                    + " (" + Math.round(d) + " px), tapping it");
            tapAt(m[0], m[1], "map M");
            followHoldUntil = t + MAP_WALK_MS;
            handler.postDelayed(this::closeMap, 600);
        }
    }

    // The party master's icon on the big map: an orange-edged grey square with a yellow M, 50 px
    // (assets/map_m_icon.png, 25 px at half size). By colour alone the dirt strips, sand and brick
    // paths were taken for it (12:49), so it's matched as a picture: mean RGB difference at half
    // size, 10 on the real icon vs 38+ anywhere else. Only windows holding about as much orange
    // and yellow as the icon get compared (~130 of 60,000).
    private static final int M_TPL = 25, M_MATCH_MAX = 22;
    private int[] mTemplate;

    private int[] findMapM(Bitmap shot) {
        if (mTemplate == null) {
            try (java.io.InputStream in = getAssets().open("map_m_icon.png")) {
                Bitmap t = android.graphics.BitmapFactory.decodeStream(in);
                mTemplate = new int[M_TPL * M_TPL];
                t.getPixels(mTemplate, 0, M_TPL, 0, 0, M_TPL, M_TPL);
                t.recycle();
            } catch (java.io.IOException | RuntimeException e) {
                Log.w(TAG, "follow: no M icon picture: " + e);
                return null;
            }
        }
        // shot is the half-size screenshot (captureHalfScreen): the template's scale.
        int w = shot.getWidth(), h = shot.getHeight();
        int[] px = new int[w * h];
        shot.getPixels(px, 0, w, 0, 0, w, h);
        // Integral images of "orange" and "yellow" pixels.
        int[] io = new int[(w + 1) * (h + 1)], iy = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            int ro = 0, ry = 0;
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x], r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                if (r > 200 && g > 100 && g < 200 && b < 110 && r - b > 100) ro++;
                if (r > 200 && g > 170 && b < 120 && r - b > 90) ry++;
                io[(y + 1) * (w + 1) + x + 1] = io[y * (w + 1) + x + 1] + ro;
                iy[(y + 1) * (w + 1) + x + 1] = iy[y * (w + 1) + x + 1] + ry;
            }
        }
        int y0 = Math.round(h * 0.09f), y1 = Math.round(h * 0.86f) - M_TPL, x0 = Math.round(w * 0.07f), x1 = Math.round(w * 0.97f) - M_TPL;
        double best = Double.MAX_VALUE;
        int bx = -1, by = -1;
        for (int y = y0; y <= y1; y += 2) {
            for (int x = x0; x <= x1; x += 2) {
                int o = box(io, w, x, y), yl = box(iy, w, x, y);
                if (o < 60 || o > 220 || yl < 25 || yl > 200) continue;
                double sc = mDiff(px, w, x, y, best);
                if (sc < best) {
                    best = sc;
                    bx = x;
                    by = y;
                }
            }
        }
        if (bx < 0) return null;
        // Refine around the best at 1 px.
        for (int y = Math.max(0, by - 2); y <= Math.min(h - M_TPL, by + 2); y++) {
            for (int x = Math.max(0, bx - 2); x <= Math.min(w - M_TPL, bx + 2); x++) {
                double sc = mDiff(px, w, x, y, best);
                if (sc < best) {
                    best = sc;
                    bx = x;
                    by = y;
                }
            }
        }
        Log.d(TAG, "follow: map M match " + Math.round(best) + " at " + (bx + M_TPL / 2) * 2 + "," + (by + M_TPL / 2) * 2);
        return best <= M_MATCH_MAX ? new int[]{(bx + M_TPL / 2) * 2, (by + M_TPL / 2) * 2} : null;
    }

    private static int box(int[] ii, int w, int x, int y) {
        int s = w + 1;
        return ii[(y + M_TPL) * s + x + M_TPL] - ii[y * s + x + M_TPL] - ii[(y + M_TPL) * s + x] + ii[y * s + x];
    }

    /** Mean absolute RGB difference to the template (gives up once past the best so far). */
    private double mDiff(int[] px, int w, int x, int y, double bestSoFar) {
        long sum = 0, cap = (long) (bestSoFar * M_TPL * M_TPL * 3);
        for (int ty = 0; ty < M_TPL; ty++) {
            int row = (y + ty) * w + x;
            for (int tx = 0; tx < M_TPL; tx++) {
                int a = px[row + tx], t = mTemplate[ty * M_TPL + tx];
                sum += Math.abs(((a >> 16) & 0xff) - ((t >> 16) & 0xff)) + Math.abs(((a >> 8) & 0xff) - ((t >> 8) & 0xff))
                        + Math.abs((a & 0xff) - (t & 0xff));
            }
            if (sum > cap) return Double.MAX_VALUE;
        }
        return sum / (double) (M_TPL * M_TPL * 3);
    }

    /** The big map has a light grey title bar right across the top (100% of a row vs <=51% otherwise). */
    private boolean mapIsOpen(Bitmap shot, float heightShare) {
        int w = shot.getWidth(), h = shot.getHeight();
        float fullH = h / heightShare;
        int[] row = new int[w];
        // Rows as a share of the screen: follow captures only the top 74%, which put 13% of the
        // capture just above the bar and the open map went unnoticed (13:02).
        for (int y = Math.round(fullH * 0.08f); y < Math.min(h, Math.round(fullH * 0.13f)); y++) {
            shot.getPixels(row, 0, w, 0, y, w, 1);
            int n = 0, tot = 0;
            for (int x = Math.round(w * 0.05f); x < Math.round(w * 0.95f); x += 4) {
                int c = row[x], r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                tot++;
                if (r > 165 && g > 165 && b > 165 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 25) n++;
            }
            if (n >= tot * 0.9f) return true;
        }
        return false;
    }

    /** Big map open and stuck: tap ~80 px from our arrow toward the map's middle to walk off a portal. */
    private void stepOffViaMap() {
        captureHalfScreen(shot -> {
            if (shot == null) return;
            int[] arrow = mapIsOpen(shot, 1f) ? mapCluster(shot, true) : null;
            shot.recycle();
            if (arrow == null) return;
            float cx = screenW * 0.5f, cy = screenH * 0.48f;
            float dx = cx - arrow[0], dy = cy - arrow[1], len = Math.max(1f, (float) Math.hypot(dx, dy));
            float step = screenW * 0.03f;
            float tx = arrow[0] + dx / len * step, ty = arrow[1] + dy / len * step;
            Log.i(TAG, "follow: map won't close (portal popup?) - tapping it at " + Math.round(tx) + "," + Math.round(ty)
                    + " to walk off");
            tapAt(tx, ty, "map step off");
            followHoldUntil = SystemClock.uptimeMillis() + 2500;
        });
    }

    private void closeMap() {
        tapAt(screenW * PANEL_X_X, screenH * PANEL_X_Y, "close map");
    }

    /**
     * Centre of the densest patch of the big map's party-master icon (orange border, arrow=false)
     * or our own arrow (teal ring, arrow=true), or null. Skips the bot's bar on the left, the HP
     * bars on top, and for the arrow the minimap corner (its own small arrow).
     */
    private int[] mapCluster(Bitmap shot, boolean arrow) {
        // shot is half size: bins of 16 px and every pixel = 32 px bins at every 2nd pixel full size.
        int w = shot.getWidth(), h = shot.getHeight();
        int x0 = Math.round(w * 0.07f), x1 = Math.round(w * 0.97f), y0 = Math.round(h * 0.09f), y1 = Math.round(h * 0.86f);
        final int bin = 16;
        int bw = w / bin + 1, bh = h / bin + 1;
        int[] counts = new int[bw * bh];
        long[] sx = new long[bw * bh], sy = new long[bw * bh];
        int[] row = new int[w];
        for (int y = y0; y < y1; y++) {
            shot.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = x0; x < x1; x++) {
                int c = row[x], r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                boolean hit = arrow
                        ? r < 110 && g > 100 && g < 165 && b > 130 && b > g + 5 && !(x > w * 0.78f && y < h * 0.3f)
                        : r > 215 && g > 120 && g < 185 && b < 100 && r - b > 130;
                if (!hit) continue;
                int k = (y / bin) * bw + x / bin;
                counts[k]++;
                sx[k] += x;
                sy[k] += y;
            }
        }
        int best = -1, bestSum = 0;
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int sum = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = bx + dx, ny = by + dy;
                        if (nx >= 0 && ny >= 0 && nx < bw && ny < bh) sum += counts[ny * bw + nx];
                    }
                }
                if (sum > bestSum) {
                    bestSum = sum;
                    best = by * bw + bx;
                }
            }
        }
        if (best < 0 || bestSum < 6) return null;
        int bx = best % bw, by = best / bw;
        long n = 0, ax = 0, ay = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = bx + dx, ny = by + dy;
                if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue;
                int k = ny * bw + nx;
                n += counts[k];
                ax += sx[k];
                ay += sy[k];
            }
        }
        return new int[]{(int) (ax / n) * 2, (int) (ay / n) * 2};
    }

    private void followWalk(float dx, float dy, int ms, long now) {
        lastFollowWalkAt = now;
        followHoldUntil = now + FARM_PUSH_MS + ms + 300;
        ownTapUntil = now + FARM_PUSH_MS + ms + OWN_TAP_SLACK_MS;
        joystickHold(dx, dy, ms);
    }

    /** The Team list at the top left: under the portrait/buffs, left of ~0.2 of the width. */
    private boolean inTeamList(Rect box) {
        return box.left < screenW * 0.2f && box.top > screenH * 0.17f && box.bottom < screenH * 0.42f;
    }

    private long lastTeamDumpAt;
    private boolean followWaitLogged;

    /** Like sameName but allowing ~a third of the letters misread (OCR over the Team row's HP bar). */
    private static boolean looseName(String a, String b) {
        if (sameName(a, b)) return true;
        if (a == null || b == null || a.length() < 4 || b.length() < 4) return false;
        int allowed = Math.max(2, b.length() / 3);
        return Math.abs(a.length() - b.length()) <= allowed && editDistance(a, b) <= allowed;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** One word of 3-16 letters/digits (dashes around it allowed): not a store sign or a sentence. */
    private static boolean looksLikePlayerName(String text) {
        String t = text.trim().replaceAll("^[-`'~_.]+|[-`'~_.]+$", "");
        return !t.contains(" ") && nameKey(t).length() >= 3 && t.length() <= 18;
    }

    /** "M kYjheLe26" / "2 VANGIELYNROSE" -> the name without the row mark. */
    private static String stripRowMark(String text) {
        return text.trim().replaceFirst("^(?:[Mm]|\\d{1,2})\\s+", "");
    }

    /** Lower-case letters and digits only: "-kYjheLe26-" and "kYjheLe26" are the same name. */
    private static String nameKey(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Same player name, allowing a couple of OCR slips on longer names. */
    private static boolean sameName(String a, String b) {
        if (a.length() < 3 || b == null || b.length() < 3) return false;
        if (a.equals(b)) return true;
        if (Math.min(a.length(), b.length()) >= 5 && (a.contains(b) || b.contains(a))) return true;
        int allowed = Math.max(1, b.length() / 5);
        if (Math.abs(a.length() - b.length()) > allowed) return false;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1,
                        prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()] <= allowed;
    }

    private void questionWatchTick() {
        watchHandler.postDelayed(questionWatchTick, QUESTION_WATCH_MS);
        if (running && (farmer || follow)) return;   // Farmer/Follow read it from their own screenshots
        // The scan loop's screenshots read it too (FS); a second screenshot would only be refused.
        if (running && SystemClock.uptimeMillis() - lastScanShotAt < QUESTION_WATCH_MS * 3) return;   // gaps of a few s happen
        if (!canReadScreen()) return;
        String game = gamePackage != null ? gamePackage : DEFAULT_GAME;
        if (!game.equals(foregroundPackage())) return;
        // From the top-left corner, so OCR boxes are already screen pixels.
        captureRegionForOcr(0f, 0f, 1f, QUESTION_SCAN_H, top -> {
            if (top != null) Ocr.read(top, (lines, words) -> checkForQuestion(game, lines));
            else if (SystemClock.uptimeMillis() - lastQuestionShotFailLogAt > 60_000) {
                lastQuestionShotFailLogAt = SystemClock.uptimeMillis();
                Log.w(TAG, "question watch: no screenshot");
            }
        });
    }

    private long lastScanShotAt, lastScanQuestionAt, lastQuestionShotFailLogAt;

    private long lastReviveAt;
    // After a death (the user, 07:38): no attacks in town; once revived, use the Back Point card in
    // quick slot S to return to the farming spot, then farm on. S = the middle of A/S/D (07:39).
    private static final float BACK_POINT_X = 2317 / 2560f, BACK_POINT_Y = 755 / 1600f;
    private static final int BACK_POINT_AFTER_MS = 7000, BACK_POINT_LOAD_MS = 12_000;
    private long deadUntil;
    private final Runnable useBackPoint = this::useBackPoint;
    private final Runnable backAtSpot = this::backAtSpot;
    // The pet stays behind after a death: the paw (top right) asks "Summon your pet?" Yes/No.
    private static final float PAW_X = 1828 / 2560f, PAW_Y = 42 / 1600f;
    private static final float PET_YES_X = 1357 / 2560f, PET_YES_Y = 947 / 1600f;   // measured 07:43
    private static final float PET_NO_X = 1756 / 2560f, PET_NO_Y = 950 / 1600f;     // "Recall your pet?" No, 10:07
    // Back Point by the map name (10:06: tapped 7 s after Revive while the town was still loading,
    // so it stayed in town): wait until the map is no longer the one it died on, then tap; check
    // it's back on that map after loading, else try once more.
    private static final int BACK_POINT_WAIT_MS = 2000, BACK_POINT_MAX_WAIT_MS = 30_000, BACK_POINT_TRIES = 2;
    private String deathMap;
    private long revivedAt;
    private int backPointTries;
    // Hunted (the user, 13:25): 3 deaths in DEATH_WINDOW_MS (10:06, 10:18, 10:20 - a player camping
    // the spot) -> stay in town DEATH_REST_MS before the Back Point, instead of walking into it again.
    private static final int DEATHS_TO_REST = 3;
    private static final long DEATH_WINDOW_MS = 20 * 60_000L, DEATH_REST_MS = 30 * 60_000L;
    private final java.util.ArrayDeque<Long> deathTimes = new java.util.ArrayDeque<>();
    private long panelQuietUntil;
    // Home spot (the user, 07:45): the game prints "TradingHole[124,114]" at the bottom left. Home is
    // where Farmer starts; drifting more than LEASH_R away, it walks back between fights. Which
    // joystick push moves which way in map coordinates is learned by two probe walks (E, N), and
    // learned again after a camera turn or when walking home stops getting closer.
    private static final float COORD_L = 0f, COORD_T = 0.95f, COORD_W = 0.35f, COORD_H = 0.05f;
    // 10 since walking home by the map works well (the user, 19:24).
    // The leash radius is the user's pick on the anchor button: 6, 10 or 15 (the user, 22:40).
    private static final int COORD_EVERY_MS = 6000, LEASH_PROBE_MS = 2500;
    private static final int[] LEASH_RADII = {6, 10, 15};
    private static final String KEY_LEASH_R = "farm_leash_r";
    private int leashR = 10;
    private static final java.util.regex.Pattern COORD_TEXT =
            // "[" is sometimes read as l, I, | or ( ("TradingHolel123,119]", 19:02): the name stops
            // as early as it can so the slipped bracket isn't taken as part of it.
            // A stray space inside a number too ("[1 31,118]", 19:04).
            java.util.regex.Pattern.compile("([\\p{L}_]{3,}?)\\s*[\\[(|lI]\\s*(\\d(?: ?\\d){0,3})\\s*[,.]\\s*(\\d(?: ?\\d){0,3})");
    private String homeMap, posMap;
    // ⚓ on the bar (Farmer): set home to where the character stands now; kept across restarts.
    private static final String KEY_HOME = "farm_home";
    private TextView leashButton;
    private int homeX, homeY, posX, posY;
    private long posAt, lastCoordReadAt, lastChatReadAt, lastCoordMissLogAt;
    private int jumpX = -1, jumpY = -1;
    private long jumpAt;
    private static final int CHAT_READ_MS = 4000;
    private int calStage, leashMisses;
    private boolean calValid;
    private float calEx, calEy, calNx, calNy, calP0x, calP0y, leashLastDist;
    // Past the leash: no attacks or buffs at all until back within leashBackR() (the user, 08:13:
    // "it wont attack until it goes near the leash position"). Attacking on the way made the game
    // run to the next monster, farther out, and spoiled the direction probes.
    private static final int HOME_MAX_DIST = 150;
    private static final int RETURN_MAX_MS = 90_000, RETURN_COORD_MS = 1500;

    /** Back home when this close: 3, or 2 on the tight 6 leash. */
    private int leashBackR() {
        return leashR <= 6 ? 2 : 3;
    }
    private boolean returning;
    private int calSign = 1;                                   // probe E/N, or W/S after a blocked try
    private long returnStartedAt, returnGiveUpUntil, leashWalkEnd;
    private long calWalkEnd;
    private boolean lastOcrHadDialog;

    /**
     * Death: "Do you wish to be revived?" over a Revive button (killed by a player, 2026-10-05
     * 07:37). Tap Revive whenever it shows, in any mode (the user's rule), and say so on Telegram.
     */
    private void checkRevive(List<MathQuestion.Line> lines) {
        if (!running || manual) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastReviveAt < 5000) return;
        MathQuestion.Line ask = null;
        for (MathQuestion.Line l : lines) {
            String t = l.text.toLowerCase(java.util.Locale.ROOT);
            if (t.contains("to be revived") || t.contains("wish to be reviv")) {
                ask = l;
                break;
            }
        }
        if (ask == null) return;
        Rect button = null;
        for (MathQuestion.Line l : lines) {
            String k = l.text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
            if (l != ask && k.equals("revive") && l.box.top > ask.box.bottom && l.box.top < ask.box.bottom + ask.box.height() * 6) {
                button = l.box;
                break;
            }
        }
        float x = button != null ? button.exactCenterX() : ask.box.exactCenterX();
        float y = button != null ? button.exactCenterY() : ask.box.bottom + ask.box.height() * 2.6f;   // measured 07:37
        lastReviveAt = now;
        Log.w(TAG, "died: \"" + ask.text.trim() + "\", tapping Revive at " + Math.round(x) + "," + Math.round(y));
        tapAt(x, y, "revive");
        deathMap = posMap;
        revivedAt = now;
        backPointTries = 0;
        deathTimes.addLast(now);
        while (!deathTimes.isEmpty() && now - deathTimes.peekFirst() > DEATH_WINDOW_MS) deathTimes.removeFirst();
        boolean rest = farmer && deathTimes.size() >= DEATHS_TO_REST;
        // No more attacks until back at the farming spot.
        long backPointIn = rest ? DEATH_REST_MS : BACK_POINT_AFTER_MS;
        deadUntil = now + backPointIn + BACK_POINT_LOAD_MS + 5000;
        busyUntil = farmHoldUntil = Math.max(busyUntil, deadUntil);
        handler.removeCallbacks(useBackPoint);
        handler.removeCallbacks(backAtSpot);
        if (rest) {
            long minutes = (now - deathTimes.peekFirst()) / 60_000;
            Log.w(TAG, "died: " + deathTimes.size() + " deaths in " + minutes + " min - staying in town "
                    + DEATH_REST_MS / 60_000 + " min before going back");
            deathTimes.clear();
            if (farmer) handler.postDelayed(useBackPoint, DEATH_REST_MS);
            Telegram.send(this, "\uD83D\uDC80 Ran Online: died " + DEATHS_TO_REST + " times in " + minutes
                    + " min (someone camping the spot?) - revived, staying safe in town for " + DEATH_REST_MS / 60_000
                    + " min, then the Back Point and farming again.");
            return;
        }
        if (farmer) handler.postDelayed(useBackPoint, BACK_POINT_AFTER_MS);
        Telegram.send(this, "\uD83D\uDC80 Ran Online: your character died - tapped Revive"
                + (farmer ? ", using the Back Point card (slot S) next (death " + deathTimes.size() + " of "
                + DEATHS_TO_REST + " before a " + DEATH_REST_MS / 60_000 + " min rest)." : "."));
    }

    /** Revived in town: the Back Point card (slot S) takes the character back to where it died. */
    /** Reads the map name from the coordinates line ("TradingHole[124,117]"), or null. */
    private void readMapName(Consumer<String> onMap) {
        captureRegionForOcr(COORD_L, COORD_T, COORD_W, COORD_H, crop -> {
            if (crop == null) {
                onMap.accept(null);
                return;
            }
            Ocr.read(crop, (lines, words) -> {
                for (MathQuestion.Line l : lines) {
                    java.util.regex.Matcher m = COORD_TEXT.matcher(l.text);
                    if (m.find()) {
                        onMap.accept(m.group(1));
                        return;
                    }
                }
                onMap.accept(null);
            });
        });
    }

    private void useBackPoint() {
        if (!running || !farmer || manual) return;
        long now = SystemClock.uptimeMillis();
        deadUntil = Math.max(deadUntil, now + BACK_POINT_LOAD_MS);
        busyUntil = farmHoldUntil = Math.max(busyUntil, deadUntil);
        readMapName(map -> {
            long t = SystemClock.uptimeMillis();
            boolean inTown = map != null && (deathMap == null || !sameMap(map, deathMap))
                    || t - revivedAt >= DEATH_REST_MS;
            if (!inTown && t - revivedAt < BACK_POINT_MAX_WAIT_MS) {
                Log.d(TAG, "died: map " + map + ", not in town yet - waiting");
                deadUntil = Math.max(deadUntil, t + BACK_POINT_LOAD_MS);
                busyUntil = farmHoldUntil = Math.max(busyUntil, deadUntil);
                handler.postDelayed(useBackPoint, BACK_POINT_WAIT_MS);
                return;
            }
            backPointTries++;
            Log.i(TAG, "died: in " + map + ", using the Back Point card (slot S) to return to " + deathMap
                    + " (try " + backPointTries + ")");
            onCardPage(() -> tapAt(screenW * BACK_POINT_X, screenH * BACK_POINT_Y, "back point"), "Back Point");
            deadUntil = t + BACK_POINT_LOAD_MS;
            busyUntil = farmHoldUntil = Math.max(busyUntil, deadUntil);
            handler.postDelayed(backAtSpot, BACK_POINT_LOAD_MS);
        });
    }

    private void backAtSpot() {
        if (!running || !farmer) return;
        readMapName(map -> {
            boolean back = deathMap == null || (map != null && sameMap(map, deathMap));
            if (back || map == null) {
                arrivedAtSpot();
                return;
            }
            long t = SystemClock.uptimeMillis();
            if (backPointTries < BACK_POINT_TRIES) {
                Log.w(TAG, "died: still in " + map + " after the Back Point, trying it again");
                handler.post(useBackPoint);
                return;
            }
            // Out of tries: don't farm in town. Hold everything and say so.
            deadUntil = busyUntil = farmHoldUntil = t + 10 * 60_000;
            Log.w(TAG, "died: still in " + map + " after " + backPointTries + " Back Point taps, holding");
            Telegram.send(this, "\u26A0 Ran Online: revived but still in " + map + " after " + backPointTries
                    + " Back Point taps (no card left?). Farming is on hold.");
        });
    }

    private void arrivedAtSpot() {
        if (!running || !farmer) return;
        long now = SystemClock.uptimeMillis();
        deadUntil = 0;
        busyUntil = farmHoldUntil = now;
        farmMobsSeenAt = farmProgressAt = lastTargetBarAt = now;
        Log.i(TAG, "died: back from the Back Point, summoning the pet, then farming again");
        summonPet();
        Telegram.send(this, "\u2705 Ran Online: Back Point used, back in " + posMap + ", farming again. Check how many Back Point cards are left.");
        handler.post(this::farmFullBuff);
        schedulePump(0);
    }

    /** Paw (top right) -> "Summon your pet?" -> Yes. The Yes button is read by OCR, else its measured spot. */
    private void summonPet() {
        long now = SystemClock.uptimeMillis();
        panelQuietUntil = now + 8000;                          // that dialog is ours, not a panel to X
        busyUntil = farmHoldUntil = Math.max(busyUntil, now + 4000);
        tapAt(screenW * PAW_X, screenH * PAW_Y, "pet paw");
        handler.postDelayed(() -> captureRegionForOcr(0f, 0f, 1f, 0.75f, shot -> {
            if (shot == null) return;
            Ocr.read(shot, (lines, words) -> {
                boolean asked = false, recall = false;
                Rect yes = null, no = null;
                for (MathQuestion.Line l : lines) {
                    String t = l.text.toLowerCase(java.util.Locale.ROOT);
                    if (t.contains("summon") && t.contains("pet")) asked = true;
                    if (t.contains("recall") && t.contains("pet")) recall = true;
                    String k = t.replaceAll("[^a-z]", "");
                    if (k.equals("yes")) yes = l.box;
                    if (k.equals("no")) no = l.box;
                }
                if (recall) {
                    // The pet is out already (Back Point brings it): never send it away.
                    float x = no != null ? no.exactCenterX() : screenW * PET_NO_X;
                    float y = no != null ? no.exactCenterY() : screenH * PET_NO_Y;
                    Log.i(TAG, "pet: \"Recall your pet?\" - it's out already, No at " + Math.round(x) + "," + Math.round(y));
                    tapAt(x, y, "pet no");
                    return;
                }
                if (!asked) {
                    Log.i(TAG, "pet: no \"Summon your pet?\" after the paw (already out?)");
                    return;
                }
                float x = yes != null ? yes.exactCenterX() : screenW * PET_YES_X;
                float y = yes != null ? yes.exactCenterY() : screenH * PET_YES_Y;
                Log.i(TAG, "pet: \"Summon your pet?\" -> Yes at " + Math.round(x) + "," + Math.round(y));
                tapAt(x, y, "pet yes");
            });
        }), 1500);
    }

    private void checkForQuestion(String game, List<MathQuestion.Line> lines) {
        checkRevive(lines);
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

    /*
     * The system crashes (6 watchdog kills, 2026-10-04/05) are one MIUI deadlock: an accessibility
     * screenshot holds the display lock and wants DisplayManagerGlobal's, while MIUI's gesture
     * listener (MiuiCvwGestureController), handling a touch, holds that one and wants the display
     * lock. So a screenshot and a touch must never overlap: screenshots wait for our gestures to
     * end (and for your finger), and gestures wait for a screenshot in flight.
     */
    private static final int GESTURE_SLACK_MS = 150, SHOT_MAX_MS = 1500, USER_TOUCH_SHOT_MS = 2000;
    private long gestureBusyUntil, shotStartedAt;
    private boolean shotInFlight;

    private void shoot(TakeScreenshotCallback cb) {
        shoot(cb, 0);
    }

    // Android refuses an accessibility screenshot taken too soon after the last one: the big map's
    // came right after follow's own read and failed every time (13:14).
    private static final int SHOT_RETRY_MS = 1100, SHOT_RETRIES = 2;

    private void shoot(TakeScreenshotCallback cb, int retry) {
        long now = SystemClock.uptimeMillis();
        long wait = Math.max(gestureBusyUntil - now, userTouchAt + USER_TOUCH_SHOT_MS - now);
        if (wait > 0) {
            handler.postDelayed(() -> shoot(cb, retry), wait + 20);
            return;
        }
        shotInFlight = true;
        shotStartedAt = now;
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                shotInFlight = false;
                cb.onSuccess(result);
            }

            @Override
            public void onFailure(int errorCode) {
                shotInFlight = false;
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && retry < SHOT_RETRIES) {
                    handler.postDelayed(() -> shoot(cb, retry + 1), SHOT_RETRY_MS);
                    return;
                }
                if (errorCode != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) Log.w(TAG, "screenshot failed: " + errorCode);
                cb.onFailure(errorCode);
            }
        });
    }

    /** False (and retries later) while a screenshot is being taken; else marks the gesture's time. */
    private boolean gestureClear(long durationMs, Runnable retry) {
        long now = SystemClock.uptimeMillis();
        if (shotInFlight && now - shotStartedAt < SHOT_MAX_MS) {
            handler.postDelayed(retry, 30);
            return false;
        }
        gestureBusyUntil = Math.max(gestureBusyUntil, now + durationMs + GESTURE_SLACK_MS);
        return true;
    }

    private void tapAt(float x, float y, String which) {
        if (!gestureClear(TAP_MS, () -> tapAt(x, y, which))) return;
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
                lastScanShotAt = now;
                // FS: the math question from this same screenshot. Its own screenshot, between the
                // 0.35 s cooldown ones, was refused as too soon and the question went unanswered
                // (20:23). Farmer reads it in farmOcr.
                if (!farmer && now - lastScanQuestionAt >= QUESTION_WATCH_MS - 100) {
                    lastScanQuestionAt = now;
                    String game = gamePackage != null ? gamePackage : DEFAULT_GAME;
                    Bitmap top = null;
                    try {
                        top = Bitmap.createBitmap(shot, 0, 0, shot.getWidth(),
                                Math.min(shot.getHeight(), Math.round(screenH * QUESTION_SCAN_H)));
                    } catch (RuntimeException | OutOfMemoryError ignored) {
                    }
                    if (top != null) Ocr.read(top, (lines, words) -> {
                        noteTeamHeader(lines);
                        checkForQuestion(game, lines);
                    });
                }
                // Screenshots come every 0.35 s while a ring watches a cooldown; counting once
                // per BUFF_SCAN_EVERY_MS is plenty.
                if (eg() && scanBuffs && now - lastMobCountAt >= BUFF_SCAN_EVERY_MS - 100) {
                    lastMobCountAt = now;
                    int members = MobCounter.partySize(shot, screenW, screenH);
                    updateParty(members);
                    noPartyCheck(members, now);
                    updateWave(MobCounter.count(shot, screenW, screenH));
                } else if (fsMode() && !eg() && now - lastPartyReadAt >= BUFF_SCAN_EVERY_MS - 100) {
                    lastPartyReadAt = now;
                    int members = MobCounter.partySize(shot, screenW, screenH);
                    updateParty(members);
                    noPartyCheck(members, now);
                }
                if (farmer && now - lastMobCountAt >= farmScanMs() - 100) {
                    lastMobCountAt = now;
                    // Loot first: a walk started by the "no target" rule left the drops behind (13:33).
                    int[] thumb = MobCounter.sceneThumb(shot, screenW, screenH);
                    if (walkStartThumb != null && thumb != null && now >= farmHoldUntil) {
                        farmWallCheck(MobCounter.sceneDiff(walkStartThumb, thumb));
                        walkStartThumb = null;
                    }
                    lastSceneThumb = thumb;
                    if (farmPanelCheck(MobCounter.hudHidden(shot, screenW, screenH), now)) {
                        // Keep reading text: the panel might be the anti-bot question, never X it.
                        if (now - lastFarmOcrAt >= FARM_SCAN_MS - 100) {
                            lastFarmOcrAt = now;
                            farmOcr(shot);
                        }
                        return;
                    }
                    farmLootCheck(MobCounter.lootHandShowing(shot, screenW, screenH), now);
                    farmCheck(MobCounter.count(shot, screenW, screenH),
                            MobCounter.targetHp(shot, screenW, screenH), now);
                    // Each text read costs memory and CPU on the Pad 5 (system froze again 07:49 with
                    // reads every scan): chat every CHAT_READ_MS, coordinates every COORD_EVERY_MS.
                    if (now - lastChatReadAt >= (lootStartedAt > 0 ? LOOT_CHAT_MS : CHAT_READ_MS) - 100) {
                        lastChatReadAt = now;
                        farmChatRead(shot);
                    }
                    if (now - lastCoordReadAt >= (returning ? RETURN_COORD_MS : COORD_EVERY_MS) - 100) {
                        lastCoordReadAt = now;
                        farmCoordRead(shot);
                    }
                    boolean nearKill = (farmTargetHp >= 0 && farmTargetHp <= KILL_SOON_HP)   // to see where it dies
                            || (now < postKillUntil && now - killAt < 2500);                  // and what it drops
                    if (luring || nearKill || now - lastFarmOcrAt >= FARM_OCR_MS - 100) {   // every scan while luring
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
        if (!needed) {
            t.recastsWithoutOk = 0;
            t.failStreak = 0;
        }
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
        shoot(new TakeScreenshotCallback() {
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
    /**
     * The whole screen at half size (~4 MB instead of 16): the full-size copy for the big map came
     * back empty again and again under memory pressure (13:11-13:12), and follow just stood there.
     */
    private void captureHalfScreen(Consumer<Bitmap> onShot) {
        if (!canReadScreen()) {
            onShot.accept(null);
            return;
        }
        shoot(new TakeScreenshotCallback() {
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
                Bitmap soft = null;
                try {
                    Bitmap scaled = Bitmap.createScaledBitmap(hw, screenW / 2, screenH / 2, true);
                    soft = scaled.copy(Bitmap.Config.ARGB_8888, false);
                    if (scaled != hw) scaled.recycle();
                } catch (RuntimeException | OutOfMemoryError e) {
                    Log.w(TAG, "couldn't copy the half screen: " + e);
                }
                hw.recycle();
                onShot.accept(soft);
            }

            @Override
            public void onFailure(int errorCode) {
                onShot.accept(null);
            }
        });
    }

    private void captureRegionForOcr(float fl, float ft, float fw, float fh, Consumer<Bitmap> onShot) {
        if (!canReadScreen()) {
            onShot.accept(null);
            return;
        }
        shoot(new TakeScreenshotCallback() {
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
        shoot(new TakeScreenshotCallback() {
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
        closeUsbPopup(event);
        checkGameDialog(event);
        updateOverlayVisibility();
    }

    /**
     * Disconnect and crash dialogs are plain Android dialogs, so their text can be read. The kick
     * after an unanswered presence check (2026-10-02 14:12) showed "DGames Mobile: The game had to
     * stop. Please close the app and reopen it." over the game; Android's own crash dialog says
     * "... keeps stopping" or "... has stopped".
     */
    /**
     * Android's "Use USB for ..." screen (Settings$UsbDetailsActivity) pops up over the game when
     * the cable reconnects, and the bot pauses behind it (the user, 2026-10-05). While running,
     * close just that screen with Back; other Settings screens you open yourself are left alone.
     */
    private void closeUsbPopup(AccessibilityEvent event) {
        if (!running || manual) return;
        CharSequence cls = event.getClassName(), pkg = event.getPackageName();
        String c = cls != null ? cls.toString() : "";
        // MtpConnectionActivity = the "USB connection error ... Got it" screen (07:51).
        // Never a permission/consent dialog: those are the user's call, not something to auto-close.
        if (c.contains("Permission") || c.contains("Debugging")) return;
        boolean usb = c.contains("UsbDetails") || c.contains("UsbModeChooser")
                || c.contains("MtpConnection") || c.contains("connecteddevice.usb");
        boolean infoOnly = c.contains("MtpConnection");
        if (!usb && pkg != null && (pkg.toString().equals("com.android.settings") || pkg.toString().equals("com.android.systemui"))) {
            String lower = windowText(event).toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("allow") || lower.contains("permission")) return;   // a consent dialog
            usb = lower.contains("usb") && (lower.contains("file transfer") || lower.contains("charging only")
                    || lower.contains("use usb") || lower.contains("usb preferences") || lower.contains("transfer files"));
        }
        if (!usb) return;
        String what = c.isEmpty() ? String.valueOf(pkg) : c;
        handler.postDelayed(() -> {
            String front = foregroundPackage();
            if (front != null && (front.equals("com.android.settings") || front.equals("com.android.systemui"))
                    && !activeWindowAsksConsent()) {
                // Its own button first ("Got it" / "OK" / "Cancel"), Back if there's none.
                // Only the informational "USB connection error" screen gets its button pressed.
                if (infoOnly && clickButton("got it", "close")) {
                    Log.i(TAG, "USB popup over the game (" + what + "), pressed its button");
                } else {
                    Log.i(TAG, "USB popup over the game (" + what + "), closing it with Back");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
            }
        }, 700);
    }

    /** The window in front asks to allow/permit something (USB debugging, device access): hands off. */
    private boolean activeWindowAsksConsent() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return true;                      // can't tell: leave it alone
            for (String w : new String[]{"allow", "permission", "debugging", "always"}) {
                if (!root.findAccessibilityNodeInfosByText(w).isEmpty()) return true;
            }
        } catch (RuntimeException e) {
            return true;
        }
        return false;
    }

    /** Clicks the first button in the active window whose text is one of names (any case). */
    private boolean clickButton(String... names) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            for (String n : names) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(n)) {
                    CharSequence t = node.getText();
                    if (t == null || !t.toString().trim().equalsIgnoreCase(n)) continue;
                    AccessibilityNodeInfo c = node;
                    while (c != null && !c.isClickable()) c = c.getParent();
                    if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                }
            }
        } catch (RuntimeException ignored) {
            // window changed while looking
        }
        return false;
    }

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
