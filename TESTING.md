# Manual verification on the tablet

This checklist covers what can only be tested on a real device. What is already tested automatically
is listed at the bottom.

## Preparation

1. Install the latest release (Obtainium or `./scripts/install.sh`).
2. Settings → *Other* → turn on **Show debug overlay**.
3. Connect ADB (USB or wireless, see README) and follow the logs in a terminal:

   ```bash
   adb logcat -v time | grep " FS\."
   ```

**The debug overlay** (top left) shows:

```
FrigaStreamer 0.1.0 (12)
players: 2/3 (max seen 3)  preload: 1 (configured 1)
network: ok [wifi]  frigate api=OK rtsp=OK
active: 3/12  started=true  mute=true  t=...
• front_door [ACTIVE] MAIN p#7 Playing frame=40ms avc 1920x1080 n=1
• garden [PRELOAD] SUB p#8 Playing frame=120ms avc 640x360 n=1
```

Per camera: role, stream (MAIN/SUB), player instance (`p#`), state (+ attempt number and cause),
time since the last frame, backoff and time until the next attempt, codec/resolution, number of connections (`n=`).

Note per scenario: ✅/❌ and details.

---

## 1. Wi-Fi off and on again

1. Let a camera play. Turn Wi-Fi off (quick settings).
2. Wait ~30 s, turn Wi-Fi back on.

| Expected in the overlay | Expected in logcat |
|---|---|
| `network: DOWN`; cameras: `Reconnecting`/`Offline ... NETWORK_UNAVAILABLE`, **no** increasing `n=` while Wi-Fi is off | `FS.Net event=network_lost`, per camera `event=network_lost action=pause_reconnects`, `event=waiting_for_network` |
| After 20 s the offline screen with *Next attempt: as soon as the network is back* | `event=offline` |
| After Wi-Fi on: active camera immediately `Connecting`, `Playing` within a few seconds; then the neighbour | `event=network_restored action=reconnect_now`, first the active camera `connect_start`, then `connect_success` |

If the tablet sees the network before Frigate is reachable: a few `reconnect_scheduled ... delay_ms=1000/2000/...` and then recovery.

## 2. Screen off/on and returning from standby

1. Press the power button (screen off), wait 1 minute, turn the screen back on (and unlock).
2. Repeat with a longer standby (≥ 10 min).

| Expected | logcat |
|---|---|
| Screen off: `players: 0/3` (all RTSP connections closed) | `FS.App event=lifecycle state=ON_STOP`, `FS.Manager event=stop releasing_players=N`, per player `player_released` |
| Screen on: the same camera as before, video within a few seconds, `players` equal to the window again | `ON_START`, `event=start`, `event=attach role=ACTIVE`, `connect_success` |
| Never more players than the window (`max seen` ≤ 3) | no duplicate `player_created` for the same camera |

## 3. App to the background and back

1. Press home (if the app is not the home app) or open another app; return via recent apps.
2. Do this 10× in quick succession.

Expected: the same as in 2. Afterwards `players` = window size, `max seen` ≤ 3, per camera in the
overlay a low `n=` (no reconnect storms). In logcat, per `ON_STOP` as many `player_released` as there
were players.

## 4. Real Frigate restart

```bash
docker restart frigate
```

| Expected | logcat |
|---|---|
| First a brief *Connecting…* over the last frame | `connection_lost category=NETWORK` or `STREAM_ENDED`, or `watchdog_triggered` |
| If the restart takes > 20 s: offline screen with cause (*Frigate is not reachable* / *the RTSP server (go2rtc) is not*) and countdown | `reconnect_scheduled attempt=1 delay_ms=1000`, `attempt=2 delay_ms=2000`, … max 30000 |
| After the restart, recovery without intervention; the camera list stays the same | `connect_success ... recovered=true`, later `backoff_reset` |
| Settings → Camera list: *Frigate API: reachable* | `FS.Health event=frigate_health api=OK rtsp=OK` |

## 5. Fast swiping with the real cameras

1. Preload window at 2. Swipe back and forth ~20× as fast as possible (including the wrap-around from last to first).
2. Let go and wait 5 s.

Expected: `players` **never above 3** and `max seen` ≤ 3; after letting go the active camera is `Playing`
first, then the neighbours (`[PRELOAD] SUB`). No frozen UI. In logcat no `player_limit_exceeded`;
`player_created` and `player_released` keep pace.

## 6. Tablet decoder limits

1. Preload window at 2, and the main stream for all cameras (if needed, temporarily clear the substream URL
   of neighbours or turn *Substream when active* off; neighbours use the substream if one exists).
2. Swipe to the camera with the highest resolution / H.265.

Expected if the tablet has too few decoders:
- The active camera stays (or comes) on screen normally.
- Overlay: `preload: 1 (configured 2, AUTO-REDUCED)` or `0`.
- logcat: `codec_error category=DECODER_INIT`, `preload_window_reduced new_size=…`, and for the active camera
  possibly `release_preloads_for_active` + `decoder_retry`.
- Only if the camera also fails to start without other players: error screen *Video decoder could not start*.

## 7. Auto-start after reboot (home app)

1. Settings → *Set / restore default home app* → choose FrigaStreamer.
2. `adb reboot` (or restart via the power button).

Expected: after booting (and unlocking, if needed) FrigaStreamer opens automatically on the last viewed
camera. logcat: `FS.App event=app_start`, `ON_START`. Undo: same button → choose another launcher.

## 8. Long-running and memory

1. Let the app run for a few hours (preferably a night) with preload 1, screen on.
2. Measure at the start, after 1 hour and at the end:

   ```bash
   adb shell dumpsys meminfo io.github.rutgersmit.frigastreamer | grep -E "TOTAL PSS|TOTAL RSS|Native Heap|Java Heap|Views:|Activities:"
   ```

Expected: after the warm-up phase no steady growth of *TOTAL PSS* / *Native Heap* (a few MB of fluctuation
is normal), `Activities: 1`, a stable number of *Views*. In the overlay `players` stays constant; `n=` per
camera only increases on real outages. Optionally cause a few failures in between (4) and measure again.

---

## Extra checks

- **Codecs:** swipe to an H.265 camera / the Reolink doorbell with sound on. Expected: video, or a clear
  message (*Video codec not supported by this device* without a reconnect loop: `n=1` stays) or
  *No sound: audio codec not supported* with continuing video.
- **One camera without power:** unplug one camera. Only that camera goes via *Connecting…* to
  *Camera offline* (*Frigate is reachable, but this camera is not*); the rest keeps playing.
- **Orientation:** rotate the tablet / change the setting; video scales along, no new players (`p#` unchanged).
- **Mute:** sound on, swipe → the sound follows the active camera, never two cameras at once.
- **Language:** switch the device language between English and Dutch; all app texts (except the debug
  overlay) follow.
- **Credentials not in logs:** `adb logcat -d | grep -i -E "<your-password>|frigate_token="` returns nothing.

## Already tested automatically (no tablet needed)

| Layer | What | How |
|---|---|---|
| 1 | State machine, backoff + reset, watchdog, offline threshold, error classification, window (1/2/10+ cameras), decoder handling, stream↔camera mapping, EN/NL string parity, 7 real-world scenarios | `./gradlew testDebugUnitTest` (runs in CI) |
| 2 | API client against the mock (internal + login + token expiry), mapping with a real go2rtc-like stream list, RTSP streams and failures at protocol level | `testenv/` + `TestEnvIntegrationTest` + `check-streams.sh` |
| 3 | Real ExoPlayer/decoder, network callbacks, lifecycle, memory, home app | this checklist |
