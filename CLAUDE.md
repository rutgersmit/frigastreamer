# CLAUDE.md — working on FrigaStreamer

FrigaStreamer is an Android tablet app that shows live Frigate NVR camera streams (go2rtc RTSP
restreams) fullscreen, one camera at a time, with swiping. It is meant to run permanently as a
camera monitor. **Reliability and automatic recovery come before features.**

Details live in [README.md](README.md) (setup, Frigate/go2rtc, reconnect strategy, troubleshooting) and
[TESTING.md](TESTING.md) (manual checklist on the tablet). Do not duplicate them here; update them.

## Ground rules

- **Public repository.** Never commit personal data: no real IP addresses, hostnames, camera names,
  credentials or fragments of anyone's Frigate config. Use generic examples only
  (`192.168.1.10`, `front_door`, `front_door_sub`, `driveway`, `garden`).
- **English** for code, comments, KDoc, log messages, test names, commit messages and all documentation.
- **UI strings only in resources:** `app/src/main/res/values/strings.xml` (English, default) and a
  complete Dutch translation in `values-nl/strings.xml`. No hard-coded user-visible text in Compose or
  Kotlin. `StringResourcesTest` enforces key parity. Exceptions: the debug overlay (developer
  diagnostics, English only) and technical error details from the data layer (English only).
- **applicationId `com.rutgersmit.frigastreamer` must never change.** Android and Obtainium would
  treat a new id as a different app and updates would break.
- MIT licensed. No Google Play Services, no analytics/trackers/ads, no cloud services in the app.
- Credentials may be stored in DataStore, but must **never** be logged. Use `Redact.url` / `Redact.text`.

## Build and verification

- **CI (GitHub Actions) is the source of truth.** A change is done only when the workflow is green.
- Locally (if a JDK + Android SDK are available): `./gradlew testDebugUnitTest assembleDebug`.
- Without a local SDK: push and follow CI with `gh run watch` / `gh run view --log-failed`, or poll
  `https://api.github.com/repos/rutgersmit/frigastreamer/actions/runs`.
- **Never claim something builds or is tested without having seen it** (locally or in CI). Say what
  could not be verified, e.g. anything that needs a real tablet (see TESTING.md).

## Workflow security (`.github/workflows/build.yml`)

- Never use `pull_request_target`.
- `permissions: contents: read` at workflow level; `contents: write` only in the `release` job.
- Signing secrets are only used for non-PR events in `rutgersmit/frigastreamer`; PRs and forks build an
  unsigned debug APK. A tag build without secrets must fail, never fall back to debug signing.
- Pin all actions to a full commit SHA (with the version in a comment).
- Never create, commit or print a keystore or secret. The signing secrets are managed by the owner.

## Releases

- Push a tag `vX.Y.Z` → the `release` job creates a GitHub Release with **exactly one** asset,
  `FrigaStreamer-<versionName>.apk`, signed with the fixed release keystore.
- `versionCode` = `GITHUB_RUN_NUMBER`; `versionName` = tag without `v` (else `0.0.0-dev.<run>`).
- Users install and update via Obtainium (public repo, no token needed).

## Architecture map (`app/src/main/java/com/rutgersmit/frigastreamer/`)

- `data/` Frigate API client (internal :5000 / authenticated :8971 with JWT cookie), JSON parsing,
  `CameraRepository` (camera list + cache of the last known list).
- `domain/` pure logic: `Backoff`, `PlayerWindow`, `ErrorClassifier`/`ErrorCategory`, `StreamMapper`
  (go2rtc streams ↔ Frigate cameras), `CameraListBuilder`, models.
- `player/` `StreamPlayer` interface + `ExoStreamPlayer` (Media3 RTSP, always TCP).
- `stream/` `StreamController` (one per camera: state machine, reconnect, watchdog, offline threshold,
  error classification) and `StreamManager` (sliding player window).
- `network/` network callbacks and Frigate/RTSP reachability. `settings/` DataStore. `ui/` Compose.
- States: `Idle`, `Connecting`, `Playing`, `Reconnecting`, `Offline`, `Error`.
- **The UI never owns reconnect logic or RTSP connections.** It only renders state and forwards intents.
- All stream logic runs on the main thread; old sessions are ignored via a session number.

## Invariants the tests must protect

- Never more than **3** active ExoPlayer instances; never two players for the same camera.
- The player window is based on **camera index**, not pager page (wrap-around, 1 and 2 cameras).
- Players outside the window are released before new ones are created.
- At most one reconnect timer per camera; no duplicate reconnects; backoff 1 → 30 s, reset after stable video.
- The watchdog never restarts during connection setup; it detects stalls via rendered frames, not `currentPosition`.
- Permanent errors (unsupported video codec, RTSP auth, repeated decoder failures) never loop.
- A decoder failure on a preloaded player never affects the active camera.
- Credentials never appear in logs.

## Testing

1. **Unit tests** (`app/src/test/`): fakes for player, server, clock and network live in
   `app/src/test/java/com/rutgersmit/frigastreamer/fakes/Fakes.kt`; use `kotlinx-coroutines-test`
   virtual time. Real-world scenarios are in `stream/ScenarioTest.kt`. Add a test with every behaviour change.
2. **Test environment** (`testenv/`): `docker compose up -d` starts MediaMTX + test streams + a Frigate API
   mock. Opt-in integration test: `FS_TESTENV_HOST=127.0.0.1 ./gradlew testDebugUnitTest --tests '*TestEnvIntegrationTest*'`.
   Failure simulation commands are in the README.
3. **On the tablet:** TESTING.md (done by the owner; report what still needs it).

Keep this file up to date when a rule, the architecture or the build process changes. It describes how to
work on the project, not a changelog.
