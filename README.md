# FrigaStreamer

> **Get it on Obtainium:** in [Obtainium](https://github.com/ImranR98/Obtainium), tap *Add App* and enter
> `https://github.com/rutgersmit/frigastreamer`. Or download the APK from the
> [latest release](https://github.com/rutgersmit/frigastreamer/releases/latest).

Android tablet app that shows live camera streams from [Frigate NVR](https://frigate.video) (via its
built-in go2rtc restream) fullscreen. Built as a **permanent camera monitor**: fast swiping between
cameras and recovering on its own from network outages, frozen streams, Frigate restarts and standby.
Reliability comes before extra features.

- One camera at a time, fullscreen, swiping with wrap-around.
- Automatic camera list from `/api/go2rtc/streams` + `/api/config`, supplemented with manual cameras.
- A reconnect state machine per camera with exponential backoff (1 → 30 s), frame watchdog and offline screen.
- Sliding window of at most 3 ExoPlayer instances; neighbours preloaded via the substream.
- No Google Play Services, no analytics/trackers, no cloud: only tablet ↔ Frigate on the LAN.
- Builds via GitHub Actions, installs and updates via Obtainium.
- UI in English and Dutch (follows the device language).

> **Disclaimer:** FrigaStreamer is an independent project. It is not affiliated with, endorsed by or
> supported by Frigate or its authors.

<!-- Screenshots: add generic test-pattern screenshots here (never real camera footage). -->

---

## Contents

1. [Project structure](#project-structure)
2. [Technologies and versions](#technologies-and-versions)
3. [First launch](#first-launch)
4. [Installing and updating via Obtainium](#installing-and-updating-via-obtainium)
5. [Installing via ADB](#installing-via-adb)
6. [GitHub Actions and creating a release](#github-actions-and-creating-a-release)
7. [One-time setup: keystore and GitHub Secrets (also for forks)](#one-time-setup-keystore-and-github-secrets-also-for-forks)
8. [Optional: building locally](#optional-building-locally)
9. [Frigate and go2rtc configuration](#frigate-and-go2rtc-configuration)
10. [Mapping go2rtc streams to cameras](#mapping-go2rtc-streams-to-cameras)
11. [Supported codecs, H.265 and G.711](#supported-codecs-h265-and-g711)
12. [Reconnect strategy, watchdog and offline status](#reconnect-strategy-watchdog-and-offline-status)
13. [Home app mode (auto-start after reboot)](#home-app-mode-auto-start-after-reboot)
14. [Settings](#settings)
15. [Reading logs via ADB](#reading-logs-via-adb)
16. [Test environment (`testenv/`) and simulating failures](#test-environment-testenv-and-simulating-failures)
17. [Tests](#tests)
18. [Known limitations](#known-limitations)
19. [Troubleshooting](#troubleshooting)

---

## Project structure

```
.github/workflows/build.yml   CI: unit tests, (signed) build, artifact, release on tag v*
app/src/main/java/io/github/rutgersmit/frigastreamer/
  data/        Frigate API client (login/JWT), JSON parsing, CameraRepository (cache)
  domain/      Models and pure logic: backoff, player window, error classification,
               stream↔camera mapping, building the camera list
  player/      StreamPlayer abstraction + ExoStreamPlayer (Media3 RTSP over TCP)
  stream/      StreamController (state machine per camera) and StreamManager (window)
  network/     ConnectivityManager network detection, Frigate/RTSP reachability
  settings/    AppSettings + DataStore persistence
  service/     Home app helper
  ui/          Compose: live screen, camera page, debug overlay, settings
  util/        Logging (with credential redaction), clock abstraction
app/src/main/res/values/       English strings (default)
app/src/main/res/values-nl/    Dutch translation
app/src/test/  Unit tests (fakes + virtual time) and opt-in integration test against testenv/
testenv/       Docker test environment: MediaMTX + ffmpeg test patterns + Frigate API mock
scripts/       install.sh / install.ps1 (ADB), set-signing-secrets.sh
TESTING.md     Checklist for manual verification on the tablet
CLAUDE.md      Working rules for AI-assisted development in this repository
```

## Technologies and versions

| Component | Version |
|---|---|
| Kotlin | 2.4.21 (Android Gradle Plugin with built-in Kotlin support) |
| Android Gradle Plugin | 9.5.1 |
| Gradle (wrapper) | 9.9.1 |
| JDK for the build | 17 or newer (CI: Temurin 21) |
| compileSdk / targetSdk | 37 (Android 17) |
| minSdk | 26 (Android 8.0) |
| Jetpack Compose BOM | 2026.09.00 |
| AndroidX Media3 (ExoPlayer, RTSP, UI) | 1.11.1 |
| DataStore Preferences | 1.3.0 |
| Kotlin Coroutines | 1.11.0 |
| kotlinx.serialization-json | 1.13.0 |

All versions are in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). There are no other runtime
dependencies (no OkHttp/Retrofit: the API client uses `HttpURLConnection`).

## First launch

1. Install the app (see [Obtainium](#installing-and-updating-via-obtainium) or [ADB](#installing-via-adb)).
2. With no Frigate server configured, the app shows an empty screen with a **Settings** button
   (or long-press anywhere).
3. Under **Frigate**, enter the **IP address** of your Frigate host (e.g. `192.168.1.10`) and tap
   **Save and fetch camera list**. The *Camera list* section then shows whether the API is reachable,
   how many go2rtc streams and cameras were found, and which cameras have no stream.

What Frigate needs:

- **go2rtc restreaming** for every camera you want to see (a go2rtc stream with the camera's name,
  optionally a `<camera>_sub` substream). See [Frigate and go2rtc configuration](#frigate-and-go2rtc-configuration).
- Port **5000** (internal API) or **8971** (authenticated API) and port **8554** (RTSP) reachable from
  the tablet.

## Installing and updating via Obtainium

1. Install Obtainium on the tablet: APK from the
   [Obtainium releases page](https://github.com/ImranR98/Obtainium/releases) or via F-Droid.
2. Obtainium → *Add App* → enter `https://github.com/rutgersmit/frigastreamer` → *Add*.
   The repository is public, so **no token is needed**. Optionally, add a GitHub Personal Access Token
   under Obtainium *Settings → Source-specific → GitHub* to raise the GitHub API rate limit if you
   track many apps.
3. On the first install, allow *Install unknown apps* for Obtainium (Android asks automatically;
   otherwise: *Settings → Apps → Obtainium → Install unknown apps*).
4. **Updates:** Obtainium periodically checks for new GitHub Releases (configurable under
   *Settings → Background update checking*) and shows a notification. Tap *Update* to install.
   Because every release is signed with the same key and the `versionCode` always increases,
   the update installs over the existing installation and your settings are kept.
   Each release has exactly one APK, so no APK filter is needed.

## Installing via ADB

Fallback, and handy for logs. You only need
[Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb`) and the
[GitHub CLI](https://cli.github.com) (`gh`, logged in with `gh auth login`): no SDK or compiler.

```bash
./scripts/install.sh            # latest release, on all connected devices
```

```bash
./scripts/install.sh v0.2.0     # specific release
```

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install.ps1
```

The scripts check `gh` and `adb`, download the APK with `gh release download`, install it with
`adb install -r` on all connected devices, launch the app and print clear error messages
(e.g. on `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: different key → first `adb uninstall io.github.rutgersmit.frigastreamer`).
Outside a checkout they default to `rutgersmit/frigastreamer`; set `REPO=owner/repo` (or `-Repo`) for a fork.

**USB:** on the tablet, enable *Developer options → USB debugging* (unlock developer options by tapping
*Build number* 7 times) and confirm the prompt on the screen.

**Wireless debugging (Android 11+):** *Developer options → Wireless debugging* on →
*Pair device with pairing code*. Then on your computer:

```bash
adb pair 192.168.1.20:37123      # IP:port and code from the pairing dialog
```

```bash
adb connect 192.168.1.20:41234   # IP:port from the main 'Wireless debugging' screen
```

## GitHub Actions and creating a release

Workflow: [`.github/workflows/build.yml`](.github/workflows/build.yml). Triggers: push to `main`
(and `claude/**` development branches), pull requests, tags `v*` and manual (`workflow_dispatch`).

Steps: checkout → JDK 21 (Temurin) → Gradle cache → determine version → `./gradlew testDebugUnitTest`
(summary in the job summary, reports as artifact `test-results`) → check signing secrets →
`./gradlew assembleRelease` (signed) or `assembleDebug` → verify signature with `apksigner` →
APK as workflow artifact → on a tag: a separate `release` job creates the GitHub Release with exactly one APK.

- **Security:** the workflow runs with `contents: read`; only the `release` job gets `contents: write`.
  Signing secrets are only used for pushes and manual runs in `rutgersmit/frigastreamer`, never for pull
  requests (including PRs from forks). `pull_request_target` is not used.
- **No secrets?** On push/PR the tests run and a debug APK is built (with a warning). On a **tag** the
  workflow fails with a clear error: a release is never silently signed with a debug key. Gradle itself
  also refuses `assembleRelease` without signing variables.
- **Versions:** `versionCode` = `GITHUB_RUN_NUMBER` (every CI build higher than the previous one; 1 locally),
  `versionName` = the tag without `v` (e.g. `0.3.0`), otherwise `0.0.0-dev.<run_number>`.
  The version is shown at the top of the settings screen and in the debug overlay.
- **Release asset:** exactly one file, `FrigaStreamer-<versionName>.apk`, with automatically generated
  release notes (`--generate-notes`). If a tag workflow is re-run, the asset is replaced (`--clobber`),
  so a release never has two APKs.

**Creating a release:**

```bash
git tag v0.1.0
```

```bash
git push origin v0.1.0
```

Or on GitHub: *Releases → Draft a new release → Choose a tag → `v0.1.0` (create new tag)*; the workflow
then attaches the APK to that release.

R8/minify is off (`isMinifyEnabled = false`): the gain is small for a sideloaded LAN app and not worth the
risk of subtle problems with Media3.

## One-time setup: keystore and GitHub Secrets (also for forks)

Android only accepts an update if it is signed with **the same key** as the installed version. That is
why CI signs every release with one fixed keystore from GitHub Secrets. If you fork this repository and
want your own releases, you need your own keystore and secrets (and your fork's name in the
`github.repository` check in the workflow).

### 1. Create a keystore

On a machine with a JDK:

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore frigastreamer-release.p12 \
  -alias frigastreamer -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=FrigaStreamer, O=Private"
```

Without a local JDK, use Docker:

```bash
docker run --rm -it -v "$PWD":/out eclipse-temurin:21 keytool -genkeypair -v -storetype PKCS12 \
  -keystore /out/frigastreamer-release.p12 -alias frigastreamer -keyalg RSA -keysize 4096 \
  -validity 10950 -dname "CN=FrigaStreamer, O=Private"
```

Or with OpenSSL (Android accepts PKCS12):

```bash
openssl req -x509 -newkey rsa:4096 -nodes -days 10950 -subj "/CN=FrigaStreamer/O=Private" -keyout key.pem -out cert.pem
```

```bash
openssl pkcs12 -export -inkey key.pem -in cert.pem -name frigastreamer -out frigastreamer-release.p12
```

(With OpenSSL the store and key passwords are the same: the export password. Delete `key.pem` afterwards.)

### 2. Set the GitHub Secrets

| Secret | Contents |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the keystore, base64-encoded (single line) |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | alias, e.g. `frigastreamer` |
| `SIGNING_KEY_PASSWORD` | key password |

With the GitHub CLI (from a checkout of this repository):

```bash
./scripts/set-signing-secrets.sh frigastreamer-release.p12 signing-secrets.env
```

where `signing-secrets.env` looks like this:

```
SIGNING_STORE_PASSWORD=...
SIGNING_KEY_ALIAS=frigastreamer
SIGNING_KEY_PASSWORD=...
```

Via the website: *Settings → Secrets and variables → Actions → New repository secret*.
Create the base64 value with `base64 -w0 frigastreamer-release.p12` (Linux),
`base64 -i frigastreamer-release.p12` (macOS) or in PowerShell
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("frigastreamer-release.p12"))`.

### 3. Back up!

> **Keep the keystore and the passwords in a safe place** (e.g. your password manager).
> If you lose them, you can no longer install updates over the existing installation:
> you would have to uninstall the app (including all settings) and reinstall it.
> Never commit the keystore; `.gitignore` excludes `*.p12`, `*.jks` and `*.keystore`.

## Optional: building locally

Only for those who do have a JDK (17+) and the Android SDK (platform 37, build-tools):

```bash
export ANDROID_HOME=/path/to/android-sdk
```

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK (`app/build/outputs/apk/debug/app-debug.apk`) has package `io.github.rutgersmit.frigastreamer.debug`
and can be installed next to the release version. A local release build requires the signing variables
`SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.

## Frigate and go2rtc configuration

The app talks to Frigate in these ways:

| What | Endpoint | Use |
|---|---|---|
| Stream list | `http://<ip>:5000/api/go2rtc/streams` | source of truth: which RTSP streams exist |
| Configuration | `http://<ip>:5000/api/config` | camera names and which streams belong to which camera |
| Version | `http://<ip>:5000/api/version` | reachability check |
| Video | `rtsp://<ip>:8554/<stream>` | go2rtc restream, always **RTSP over TCP** |

Use an **IP address** (e.g. `192.168.1.10`), not a hostname: Android has no mDNS on a LAN.

**API modes** (configurable):

- **Internal** (default): port 5000, no authentication.
- **Authenticated**: port 8971. The app logs in with `POST /api/login` (`{"user": ..., "password": ...}`)
  and uses the JWT cookie `frigate_token`. On a `401` the app logs in again automatically.
  Frigate serves 8971 via **HTTPS with a self-signed certificate** by default; in the app, enable
  *Use HTTPS* and *Accept Frigate's self-signed certificate* (only accepted for the configured host).
  If you set `tls: enabled: false` in Frigate, turn *Use HTTPS* off.

**RTSP authentication** is separate from the API and is configured in go2rtc:

```yaml
go2rtc:
  rtsp:
    username: "viewer"
    password: "secret"
  streams:
    front_door:
      - rtsp://admin:camerapassword@192.168.1.50:554/h264Preview_01_main
    front_door_sub:
      - rtsp://admin:camerapassword@192.168.1.50:554/h264Preview_01_sub
```

Enter the same username/password under *RTSP username / RTSP password* in the app. They are never
written to the app's logs (see [Known limitations](#known-limitations)).

A typical Frigate camera using the go2rtc restream:

```yaml
cameras:
  front_door:
    ffmpeg:
      inputs:
        - path: rtsp://127.0.0.1:8554/front_door
          input_args: preset-rtsp-restream
          roles: [record]
        - path: rtsp://127.0.0.1:8554/front_door_sub
          input_args: preset-rtsp-restream
          roles: [detect]
```

## Mapping go2rtc streams to cameras

A Frigate camera is only reachable over RTSP (8554) if a go2rtc stream exists for it. The app therefore
uses `/api/go2rtc/streams` as the source and `/api/config` as a supplement:

1. **Main stream** of camera *X*: the go2rtc stream named exactly *X*. Otherwise: the first stream from
   `live.streams` (Frigate 0.16+) or `live.stream_name`; otherwise the input with role `record` that points
   to `rtsp://127.0.0.1:8554/<stream>`; otherwise another go2rtc input.
2. **Substream**: the stream matching the **substream pattern** (default `<camera>_sub`, so
   `front_door_sub`); otherwise a go2rtc input with role `detect`; otherwise another `live` stream.
3. **Standalone streams** that belong to no camera are shown as separate cameras (with their own
   `<name>_sub` as substream if it exists).
4. **Cameras without a go2rtc stream** are not shown, but are logged (`event=camera_without_stream`)
   and listed in the settings with the reason.

The pattern is configurable, e.g. `<camera>-low` or `{camera}_lq` (both `<camera>` and `{camera}` work).
The list is fetched on start, when returning to the foreground, every 5 minutes, when Frigate becomes
reachable again, and with the *Refresh camera list* button. New cameras appear and removed ones disappear
automatically. If Frigate is unreachable (or go2rtc reports an empty list during a restart), the
**last known list** (stored in DataStore) stays in use. Manually added cameras are always kept.

## Supported codecs, H.265 and G.711

| Codec | Status |
|---|---|
| H.264 video | primarily supported (hardware decoder on virtually every tablet) |
| AAC audio | primarily supported |
| H.265/HEVC video | plays if the tablet and Media3 RTSP support it; **not guaranteed** |
| G.711 (PCMA/PCMU), PCM (L16) audio | Media3 RTSP can depacketize it, but decoder support differs per device; **not guaranteed** |

App behaviour:

- **Unsupported audio**: the app reconnects automatically without the audio track; video keeps working,
  and with sound enabled you see *"No sound: audio codec not supported"*.
- **Unsupported video**: the camera goes to the error state *"Video codec not supported by this device"*,
  **without** an endless reconnect loop. Other cameras keep working; *Retry* and returning to the
  foreground give the camera another chance.
- **Decoder errors** (3× without any frame) also become a permanent codec error.

**Solution: let go2rtc transcode** to H.264/AAC. This costs **extra CPU on the Frigate host**
(especially at high resolutions; use hardware acceleration where possible, e.g. `#hardware`). The app
never transcodes itself.

```yaml
go2rtc:
  streams:
    garage_raw:
      - rtsp://admin:pw@192.168.1.51:554/h265stream
    # What the app (and Frigate) uses: H.264 + AAC
    garage:
      - "ffmpeg:garage_raw#video=h264#audio=aac"
    # Reolink doorbell: video is already H.264, only convert the G.711/PCM audio
    doorbell:
      - rtsp://admin:pw@192.168.1.52:554/h264Preview_01_main
      - "ffmpeg:doorbell#audio=aac"
```

Transcoding only a substream (cheaper) also works: then point the app to the H.264 variant via the
substream pattern or per camera (*Substream when active*).

## Reconnect strategy, watchdog and offline status

Every camera has its own `StreamController` with one central state machine:

```
Idle ─attach→ Connecting ─first frame→ Playing
                 ▲   │                      │ error / watchdog / network loss
                 │   └──────────────────────┤
                 └── backoff timer ── Reconnecting ◀┘
 Connecting/Reconnecting longer than the offline threshold ⇒ Offline (reconnect keeps running)
 permanent error (codec, RTSP auth) ⇒ Error (no automatic reconnects)
 detach (outside window / app to background) ⇒ Idle (player fully released)
```

- **Backoff:** 1 s, 2 s, 4 s, 8 s, 16 s, 30 s, 30 s, … (min/max configurable). Retries are unlimited on
  transient errors, also while the camera is shown as offline. The backoff is only reset after
  **10 s of stable video**, so a flapping connection does not reconnect every second.
- **No duplicate reconnects:** every new attempt first cancels the previous timer; callbacks from an
  old player session are ignored via a session number. Everything runs on the main thread.
- **Connect timeout:** if no first frame arrives within 20 s of connecting, it counts as a failed
  attempt (generous enough for cameras with a 10 s keyframe interval).
- **Frame watchdog:** the player counts rendered frames via Media3's `VideoFrameMetadataListener`
  (plus frames processed by the decoder for a preloaded player without a visible surface).
  `currentPosition` is not used. If no new frame arrives for 5 s (configurable) while *Playing*,
  the stream is considered stalled and restarted. During connection setup the watchdog is not active
  (the connect timeout applies then); during *buffering* the timeout is doubled. The watchdog stops
  when a player is released.
- **Network:** via `ConnectivityManager` callbacks (without requiring internet access; a LAN without
  internet is fine). On network loss, streams are stopped and reconnects paused
  (*"Next attempt: as soon as the network is back"*). On recovery the active camera connects
  immediately, then the neighbours; if Frigate is not reachable yet, the normal backoff takes over.
- **Offline display:** for less than the offline threshold (default 20 s) the last frame stays visible
  with a subtle *Connecting…* indicator. After that, a clear screen: camera name, *Camera offline*,
  time of the last frame, cause and a countdown to the next attempt. As soon as there is video again,
  the screen disappears immediately.
- **Cause** (distinguished where possible with a periodic API and RTSP port check):
  *Frigate is not reachable*, *Frigate is reachable, but the RTSP server (go2rtc) is not*,
  *Frigate is reachable, but this camera is not*, *connection open, but no new frames*,
  *codec or decoder problem*.

**Player window:** at most 3 ExoPlayer instances (active + 0, 1 or 2 neighbours, default 1 = the next one).
The window is based on the camera index, not the pager page, so with 1 or 2 cameras there are never two
players for the same camera. Players outside the window are released **first**; only then are new ones
created. Neighbours are only preloaded once the active camera has video (or after 3 s) and use the
substream, muted. When a neighbour becomes active, it switches to the preferred stream (same player).

**Decoder limits:** many tablets have only 2–4 hardware decoders. If the decoder of a preloaded player
fails, that player is given up and the preload window is reduced for the rest of the session. If the
active camera fails, all preloaded players are released first, the window is reduced and the active
camera is retried immediately. Only if it also fails without other players is it shown as a codec error.
Media3's decoder fallback (e.g. to a software decoder) is enabled.

**Lifecycle:** on `ON_STOP` (screen off, standby, other app) all players and RTSP connections are released;
on `ON_START` the last selected camera and its window are restored. This is idempotent: repeated switching
never causes duplicate players or reconnects. `ON_PAUSE` deliberately stops nothing (in split-screen the
app is paused but visible). While the settings screen is open, no streams are running.

## Home app mode (auto-start after reboot)

Since Android 10 an app may no longer start an Activity from a `BOOT_COMPLETED` receiver. The reliable
solution: the app can act as a **home app (launcher)**. Choose it once as the default home app; after a
reboot (or when pressing the home button) Android then always starts it automatically.

**Set up:** press the home button and choose *FrigaStreamer* → *Always*. Or: app settings → *Other* →
*Set / restore default home app*, or Android *Settings → Apps → Default apps → Home app*.

Via ADB:

```bash
adb shell cmd package set-home-activity io.github.rutgersmit.frigastreamer/.ui.MainActivity
```

**Undo:** the same button in the app settings, or choose another launcher under Android *Home app*.
Via ADB (example for the Pixel launcher; look up your own launcher with the first command):

```bash
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME
```

```bash
adb shell cmd package set-home-activity com.google.android.apps.nexuslauncher/.NexusLauncherActivity
```

If you can no longer get out of the app (settings are always reachable via long-press or the gear icon),
the ADB command above also works, or uninstall the app with `adb uninstall io.github.rutgersmit.frigastreamer`.

**Why no `BOOT_COMPLETED` receiver?** It would only work with the special permission
`SYSTEM_ALERT_WINDOW` (granted via ADB), and the behaviour differs per manufacturer and Android version.
Because this could not be demonstrated on a target tablet, the app deliberately has no such receiver;
home app mode is the supported route.

## Settings

Reachable via the **gear icon** (tap the screen first if the buttons are hidden) or by **long-pressing**
the screen. Everything is stored in Jetpack DataStore.

- **Frigate:** IP address, API mode (internal 5000 / authenticated 8971 with login), API port, HTTPS and
  self-signed (authenticated only), API username/password, RTSP port (8554), RTSP username/password
  (go2rtc), substream pattern. *Save and fetch camera list* refreshes the camera list immediately.
- **Camera list:** API status, number of streams, cameras without a stream, *Refresh camera list*,
  *Add camera manually*. Per camera: visible/hidden, order (▲/▼), *Substream when active*,
  *Edit* (name, main/sub URL; for an automatic camera only when you actually change the URL) and
  *Delete* (manual cameras).
- **Recovery:** preload window (0/1/2), offline threshold, frame watchdog on/off and timeout, backoff min/max.
- **Other:** always start muted, screen orientation (landscape/portrait/automatic), debug overlay,
  set/restore default home app, version.

Sound is off by default; the mute button applies to all cameras and the choice is remembered (with
*Always start muted* on, the app always starts muted). Only the active camera ever plays sound.
Credentials are stored plainly in DataStore (LAN app on a personal device; no Keystore or the deprecated
`EncryptedSharedPreferences`), but never end up in logs.

The UI language follows the device language: English by default, Dutch when the device is set to Dutch.
The debug overlay is developer diagnostics and is always English.

## Reading logs via ADB

All lines are structured (`event=... key=value`). Tags:

| Tag | Contents |
|---|---|
| `FS.Cam.<camera>` | per camera: `connect_start`, `connect_success`, `connection_lost`, `connect_failed`, `watchdog_triggered`, `reconnect_scheduled`, `reconnect_execute`, `codec_error`, `stream_error`, `offline`, `player_created`, `player_released`, `network_lost`, `backoff_reset` |
| `FS.Manager` | window, `preload_window_reduced`, `release_preloads_for_active`, `start`/`stop` |
| `FS.Net` | network down/up |
| `FS.Api`, `FS.Health` | camera list, login, Frigate/RTSP reachability |
| `FS.App` | lifecycle (`ON_START`, `ON_STOP`), version |

```bash
adb logcat -v time | grep " FS\."
```

```bash
adb logcat -v time '*:S' FS.Manager:V FS.Net:V 'FS.Cam.front_door:V'
```

```bash
adb logcat -v time | grep -E "watchdog_triggered|reconnect_scheduled|codec_error|player_(created|released)"
```

Check memory: `adb shell dumpsys meminfo io.github.rutgersmit.frigastreamer`.

## Test environment (`testenv/`) and simulating failures

A Docker environment to test the app without Frigate:

```bash
cd testenv
```

```bash
docker compose up -d
```

| Service | Port | Role |
|---|---|---|
| `mediamtx` | 8554 (RTSP), 9997 (API) | plays go2rtc: 13 test pattern streams (ffmpeg) |
| `cam-front-door` | – | separate ffmpeg process for `front_door` + `front_door_sub` (to stop/pause) |
| `frigate-mock` | 5000, 8971 | mimics `/api/go2rtc/streams`, `/api/config`, `/api/version`, `/api/login` |

Streams: `front_door`+`front_door_sub` (H.264 720p + AAC), `garden`+`garden_sub` (H.264 + AAC),
`driveway` (no audio), `garage` (**H.265**), `doorbell` (H.264 + **G.711 A-law**, mapped in the config to
camera `front_doorbell`), `shed` (H.264 + **PCM L16**), `fast_gop` (keyframe every 0.5 s), `slow_gop`
(keyframe every **10 s**), `back_door` (1080p), `carport`+`carport_sub` (not in the config: standalone camera).
The config also contains `attic` (no go2rtc stream) and `old_camera` (disabled) to test reporting of
missing cameras.

In the app: host = IP of the computer running Docker, API mode internal, port 5000. For authenticated mode:
port 8971, username/password `admin`/`admin`, **HTTPS off** (the mock uses HTTP; tokens expire after
`TOKEN_TTL` = 300 s, so automatically logging in again can be tested).

Check all streams (requires `ffprobe`): `./check-streams.sh [host]`.

**Simulating failures:**

| Scenario | Command | Expected in the app |
|---|---|---|
| One camera offline | `docker compose stop cam-front-door` … `docker compose start cam-front-door` | only front_door: *Connecting…* → after 20 s *Camera offline* (RTSP 404); other cameras keep playing; recovery after start |
| Connection open, no frames | `docker compose pause cam-front-door` … `docker compose unpause cam-front-door` | after 5 s `watchdog_triggered`, reconnect |
| "Frigate" offline (60 s) | `docker compose stop mediamtx; sleep 60; docker compose start mediamtx` | all cameras offline, backoff 1→30 s, recovery ≤ 30 s after start |
| API unreachable | `docker compose stop frigate-mock` | streams keep playing; last known camera list stays; *Frigate API: unreachable* |
| Restart like `docker restart frigate` | `docker compose restart mediamtx frigate-mock` | brief *Connecting…*, then recovery |
| Long keyframe interval | swipe to `slow_gop` | first frame up to ~10 s, **no** watchdog restart |
| Codecs | swipe to `garage` / `doorbell` / `shed` | depending on the tablet: video, or a clear codec message / *no sound* |

Simulate Wi-Fi interruptions on the tablet itself (Wi-Fi off/on).

## Tests

- **Unit tests** (`./gradlew testDebugUnitTest`): backoff (incl. reset), state machine transitions,
  error classification, offline threshold, watchdog (incl. no restart during connection setup), cancelling
  reconnects and no duplicate reconnects, player window (wrap-around, 1/2/10+ cameras, never > 3 players),
  automatic reduction on decoder errors, stream↔camera mapping, camera list, JSON parsing, credential
  redaction, EN/NL string resource parity, and the 7 real-world scenarios from the assignment (`ScenarioTest`).
  The `StreamController` is tested with a fake player, fake server, fake clock and `kotlinx-coroutines-test`
  (virtual time).
- **Integration test** against `testenv/` (opt-in):
  `FS_TESTENV_HOST=127.0.0.1 ./gradlew testDebugUnitTest --tests '*TestEnvIntegrationTest*'`
  (with `FS_TESTENV_TOKEN_TTL=<s>` also JWT expiry; then start the mock with the same `TOKEN_TTL`).
- **On the tablet:** see [TESTING.md](TESTING.md).

## Known limitations

- **RTSP credentials in the URI:** Media3 RTSP only supports authentication via the URI's userinfo
  (`rtsp://user:pass@...`). The app only adds the go2rtc credentials in the player layer, only logs
  redacted URLs itself and turns off Media3's RTSP debug logging. Media3 strips the userinfo from the
  requests; we are not aware of URL logging in Media3's own error messages, but that is outside the
  app's control.
- **Cleartext:** Android cannot restrict cleartext traffic to IP ranges (Network Security Config only
  accepts hostnames). Cleartext is therefore enabled app-wide; the app only connects to the configured
  Frigate host and manually entered URLs.
- **H.265, G.711, PCM:** depends on the tablet; see [codecs](#supported-codecs-h265-and-g711).
- **Decoder limits** differ per tablet; the window shrinks automatically, but only for the current
  session (after an app restart the configured window is tried again).
- **Last frame when offline:** the last frame stays visible as long as the camera has a player (inside
  the window). After swiping or returning from the background, there is only video after the first new frame.
- **Two cameras:** the same camera is to the left and to the right of the current one. The player is
  attached to the right page; while swiping right you therefore briefly see a black page with the name on the left.
- **No auto-start after boot without home app mode** (see above).
- **Network detection** only sees whether the tablet has a network, not whether Frigate is reachable;
  that is what the reconnect backoff and the periodic reachability check are for.
- **Error details** from the network layer (e.g. *"Frigate API unreachable: …"*) include a technical
  message that is always English.

## Troubleshooting

| Symptom | Solution |
|---|---|
| No cameras | Is the IP address correct? `curl http://<ip>:5000/api/go2rtc/streams` from another machine. The settings show why cameras are missing. |
| Camera missing | There is no go2rtc stream for that camera (see *Cameras without a go2rtc stream* in the settings), or the substream pattern does not match. |
| *RTSP authentication failed* | Enter the RTSP username/password as configured under `go2rtc: rtsp:`. |
| *Video codec not supported* | Let go2rtc transcode to H.264 (see above). |
| No sound | Unmuted? With *audio codec not supported*: `#audio=aac` in go2rtc. |
| Active camera does not start, neighbours do | Decoder limit: set the preload window to 0 or 1, or use substreams. The debug overlay shows *AUTO-REDUCED*. |
| Login fails (8971) | Check username/password and the HTTPS setting. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app is signed with a different key: once `adb uninstall io.github.rutgersmit.frigastreamer`. |
| Release workflow fails with *signing is missing* | Set the four GitHub Secrets (see [One-time setup](#one-time-setup-keystore-and-github-secrets-also-for-forks)). |
| Obtainium finds no release | Is there a release (not just a tag)? Hit the GitHub API rate limit? Add an optional token in Obtainium. |

When in doubt, turn on the **debug overlay** (settings → *Other*) and read the logs (`adb logcat | grep " FS\."`).
