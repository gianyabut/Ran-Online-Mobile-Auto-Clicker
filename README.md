# Ran Online Mobile Auto Clicker

An Android auto clicker built for Ran Online Mobile (Ran Pinas), made to keep a healer
healing and buffing. It runs as an accessibility service that shows a floating bar and
draggable tap targets over the game. It needs no root.

Built and tuned on a Xiaomi Pad 5 (Android 11, MIUI).

## Features

- **Tap targets.** Drag a numbered ring over any skill button and give it its own interval.
  Taps go out one at a time, never together, because the game ignores simultaneous touches.
- **Priority (heal first).** A priority ring always goes before the others. Buffs only take
  the slot right after a heal, so the heal is never pushed back for long.
- **Smart buff.** The ring watches its buff's icon and timer bar in the game's buff row
  (top left). It recasts when the buff is missing or its timer drops to a set level
  (50% by default, adjustable per ring). It learns which icon belongs to it from the first cast.
- **Wait for cooldown.** A ring can skip taps while its skill button is dimmed.
- **FB (full buff).** Casts every buff back to back, even buffs that are still up.
- **✋ Manual mode.** Stops tapping and hides the rings so you can play normally. Tap
  **AUTO** to show the rings and start auto clicking again.
- **Game only.** It only taps while the game is in front, and pauses while the keyboard is open.
- **Survives memory kills.** If Android kills the app to free memory, a watchdog restarts
  the service within about 20 seconds and it carries on where it left off.

## Requirements

- Android 7.0 or newer. Cooldown checks and Smart buff need **Android 11 or newer**,
  because they read the screen.
- To build it: the Android SDK and JDK 17 or newer (Android Studio's bundled JDK works).
- Optional but recommended: a computer with `adb`, for the one-time permission that turns
  on automatic restart.

## Build and install

```sh
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If `JAVA_HOME` isn't set, point it at a JDK, e.g. Android Studio's
`C:\Program Files\Android\Android Studio\jbr`.

## Setup

1. Open **Auto Clicker** and press **Enable service**. Turn on Auto Clicker in
   Accessibility settings.
2. Turn on automatic restart (once, from a computer with USB debugging on):
   ```sh
   adb shell pm grant com.autoclicker android.permission.WRITE_SECURE_SETTINGS
   ```
   This lets the app turn its own service off and on after Android kills it. Without the
   grant, you'd have to do that by hand in Accessibility settings. The grant survives
   updates but not an uninstall. The app screen tells you if it's missing.
3. On Xiaomi / MIUI, to make memory kills rarer:
   - Settings → Apps → Auto Clicker → **Autostart**: on
   - Battery saver → **No restrictions**
   - Lock Auto Clicker in Recents (long-press its card → lock), so "clean all" skips it

## Using it

The floating bar, from top to bottom:

| Button | What it does |
|---|---|
| ▶ / ■ | Start / stop tapping |
| + | Add a tap target |
| FB | Full buff: cast every buff now |
| ✋ / AUTO | Manual mode on / back to auto |

Drag a ring to move it. Tap a ring to change it:

- **Interval**: how often it taps.
- **Priority**: turn it on for your heal.
- **Smart buff**: for buffs. It recasts from the buff's timer instead of the interval.
  **Recast at %** sets when. **Relearn icon** makes it learn again if it picked the wrong buff.
- **Wait for cooldown**: while the skill is ready, press **Remember ready look**. From
  then on it skips taps while the button is dimmed.

**Pause after each tap** (on the app screen, 3000 ms by default) is how long it waits after
every tap. The game ignores all skills for about 2.6 s after a heal (longer in a fight), so
earlier taps would be wasted.

Targets and settings are saved on the device.

## Troubleshooting

Everything is logged under the `AutoClicker` tag:

```sh
adb logcat -s AutoClicker
```

- **No bar after the app was killed.** Wait about 20 seconds for the watchdog. Or open the
  app and press **Enable service**, which restarts it directly. Look for `watchdog:` lines.
- **`tap refused by Android`.** The service is half connected. It restarts itself after 3
  refused taps.
- **Taps are sent but nothing casts.** The game itself is ignoring touches. Leave the game
  and come back (Home, then reopen it).

## Disclaimer

Automating gameplay may be against the game's terms of service. Use at your own risk.
