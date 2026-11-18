# FrigaStreamer

Android-tabletapp die live camerastreams uit [Frigate NVR](https://frigate.video) (via de
ingebouwde go2rtc-restream) fullscreen toont. Gebouwd als **permanente cameramonitor**:
snel swipen tussen camera's en zelfstandig herstellen van netwerkstoringen, vastgelopen
streams, Frigate-herstarts en standby. Betrouwbaarheid gaat vóór extra functionaliteit.

- Eén camera tegelijk, fullscreen, swipen met wrap-around.
- Automatische cameralijst uit `/api/go2rtc/streams` + `/api/config`, aangevuld met handmatige camera's.
- Per camera een reconnect-state-machine met exponential backoff (1 → 30 s), frame-watchdog en offline-scherm.
- Sliding window van maximaal 3 ExoPlayer-instanties; buren voorgeladen via de substream.
- Geen Google Play Services, geen analytics/trackers, geen cloud: alleen tablet ↔ Frigate op het LAN.
- Builds via GitHub Actions, installatie en updates via [Obtainium](https://github.com/ImranR98/Obtainium).

---

## Inhoud

1. [Projectstructuur](#projectstructuur)
2. [Technologieën en versies](#technologieën-en-versies)
3. [Eenmalige setup (keystore + GitHub Secrets)](#eenmalige-setup-keystore--github-secrets)
4. [GitHub Actions en een release maken](#github-actions-en-een-release-maken)
5. [Installatie en updates via Obtainium](#installatie-en-updates-via-obtainium)
6. [Installatie via ADB](#installatie-via-adb)
7. [Optioneel: lokaal bouwen](#optioneel-lokaal-bouwen)
8. [Configuratie van Frigate en go2rtc](#configuratie-van-frigate-en-go2rtc)
9. [Koppeling go2rtc-streams ↔ camera's](#koppeling-go2rtc-streams--cameras)
10. [Ondersteunde codecs, H.265 en G.711](#ondersteunde-codecs-h265-en-g711)
11. [Reconnect-strategie, watchdog en offline-status](#reconnect-strategie-watchdog-en-offline-status)
12. [Home-app-modus (automatisch starten na reboot)](#home-app-modus-automatisch-starten-na-reboot)
13. [Instellingen](#instellingen)
14. [Logs uitlezen via ADB](#logs-uitlezen-via-adb)
15. [Testomgeving (`testenv/`) en storingen simuleren](#testomgeving-testenv-en-storingen-simuleren)
16. [Tests](#tests)
17. [Bekende beperkingen](#bekende-beperkingen)
18. [Troubleshooting](#troubleshooting)

---

## Projectstructuur

```
.github/workflows/build.yml   CI: unit tests, (ondertekende) build, artifact, release bij tag v*
app/src/main/java/io/github/rutgersmit/frigastreamer/
  data/        Frigate-API-client (login/JWT), JSON-parsing, CameraRepository (cache)
  domain/      Modellen en pure logica: backoff, spelervenster, foutclassificatie,
               stream↔camera-koppeling, cameralijst samenstellen
  player/      StreamPlayer-abstractie + ExoStreamPlayer (Media3 RTSP over TCP)
  stream/      StreamController (state-machine per camera) en StreamManager (venster)
  network/     ConnectivityManager-netwerkdetectie, Frigate/RTSP-bereikbaarheid
  settings/    AppSettings + DataStore-opslag
  service/     Home-app-helper
  ui/          Compose: live-scherm, camerapagina, debug-overlay, instellingen
  util/        Logging (met redactie van credentials), klok-abstractie
app/src/test/  Unit tests (fakes + virtuele tijd) en opt-in integratietest tegen testenv/
testenv/       Docker-testomgeving: MediaMTX + ffmpeg-testpatronen + Frigate-API-mock
scripts/       install.sh / install.ps1 (ADB), set-signing-secrets.sh
TESTING.md     Checklist voor handmatige verificatie op de tablet
```

## Technologieën en versies

| Onderdeel | Versie |
|---|---|
| Kotlin | 2.4.21 (Android Gradle Plugin met ingebouwde Kotlin-ondersteuning) |
| Android Gradle Plugin | 9.5.1 |
| Gradle (wrapper) | 9.9.1 |
| JDK voor de build | 17 of nieuwer (CI: Temurin 21) |
| compileSdk / targetSdk | 37 (Android 17) |
| minSdk | 26 (Android 8.0) |
| Jetpack Compose BOM | 2026.09.00 |
| AndroidX Media3 (ExoPlayer, RTSP, UI) | 1.11.1 |
| DataStore Preferences | 1.3.0 |
| Kotlin Coroutines | 1.11.0 |
| kotlinx.serialization-json | 1.13.0 |

Alle versies staan in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Er zijn geen
andere runtime-dependencies (geen OkHttp/Retrofit: de API-client gebruikt `HttpURLConnection`).

## Eenmalige setup (keystore + GitHub Secrets)

Android accepteert een update alleen als die met **dezelfde sleutel** is ondertekend als de
geïnstalleerde versie. Daarom ondertekent CI elke release met één vaste keystore uit GitHub Secrets.

### 1. Keystore aanmaken

Heb je al een keystore gekregen (`frigastreamer-release.p12` + `signing-secrets.env`), sla dan
deze stap over. Anders, op een machine met een JDK:

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore frigastreamer-release.p12 \
  -alias frigastreamer -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=FrigaStreamer, O=Private"
```

Zonder JDK kan het ook met OpenSSL (PKCS12 wordt door Android geaccepteerd):

```bash
openssl req -x509 -newkey rsa:4096 -nodes -days 10950 -subj "/CN=FrigaStreamer/O=Private" -keyout key.pem -out cert.pem
```

```bash
openssl pkcs12 -export -inkey key.pem -in cert.pem -name frigastreamer -out frigastreamer-release.p12
```

(Bij OpenSSL zijn store- en key-wachtwoord gelijk: het export-wachtwoord. Verwijder daarna `key.pem`.)

### 2. GitHub Secrets zetten

| Secret | Inhoud |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | de keystore, base64-gecodeerd (één regel) |
| `SIGNING_STORE_PASSWORD` | wachtwoord van de keystore |
| `SIGNING_KEY_ALIAS` | alias, bv. `frigastreamer` |
| `SIGNING_KEY_PASSWORD` | wachtwoord van de sleutel |

Met de GitHub CLI (vanuit een checkout van deze repository):

```bash
./scripts/set-signing-secrets.sh frigastreamer-release.p12 signing-secrets.env
```

waarbij `signing-secrets.env` er zo uitziet:

```
SIGNING_STORE_PASSWORD=...
SIGNING_KEY_ALIAS=frigastreamer
SIGNING_KEY_PASSWORD=...
```

Via de website kan ook: *Settings → Secrets and variables → Actions → New repository secret*.
De base64-waarde maak je met `base64 -w0 frigastreamer-release.p12` (Linux),
`base64 -i frigastreamer-release.p12` (macOS) of in PowerShell
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("frigastreamer-release.p12"))`.

### 3. Back-up!

> **Bewaar de keystore én de wachtwoorden op een veilige plek** (bv. in je wachtwoordmanager).
> Raak je ze kwijt, dan kun je geen updates meer over de bestaande installatie heen zetten:
> je moet de app dan verwijderen (inclusief alle instellingen) en opnieuw installeren.
> Commit de keystore nooit; `.gitignore` sluit `*.p12`, `*.jks` en `*.keystore` uit.

## GitHub Actions en een release maken

Workflow: [`.github/workflows/build.yml`](.github/workflows/build.yml). Triggers: push naar `main`
(en `claude/**`-ontwikkelbranches), pull requests, tags `v*` en handmatig (`workflow_dispatch`).

Stappen: checkout → JDK 21 (Temurin) → Gradle-cache → versie bepalen → `./gradlew testDebugUnitTest`
(samenvatting in de job summary, rapporten als artifact `test-results`) → signing-secrets controleren →
`./gradlew assembleRelease` (ondertekend) → handtekening controleren met `apksigner` →
APK als workflow-artifact → bij een tag: GitHub Release met precies één APK.

- **Geen secrets?** Bij push/PR draaien de tests en wordt een debug-APK gebouwd (met een waarschuwing).
  Bij een **tag** faalt de workflow met een duidelijke foutmelding: een release wordt nooit stilletjes
  met een debug-sleutel ondertekend. Ook Gradle zelf weigert `assembleRelease` zonder signing-variabelen.
- **Versies:** `versionCode` = `GITHUB_RUN_NUMBER` (elke CI-build hoger dan de vorige; lokaal 1),
  `versionName` = de tag zonder `v` (bv. `0.3.0`), anders `0.0.0-dev.<run_number>`.
  De versie staat bovenaan het instellingenscherm en in de debug-overlay.
- **Release-asset:** precies één bestand, `frigate-viewer-<versionName>.apk`, met automatisch
  gegenereerde releasenotes (`--generate-notes`). Wordt een tag-workflow opnieuw gedraaid, dan wordt
  de asset vervangen (`--clobber`), zodat er nooit twee APK's aan een release hangen.

**Een release maken:**

```bash
git tag v0.1.0
```

```bash
git push origin v0.1.0
```

Of op GitHub: *Releases → Draft a new release → Choose a tag → `v0.1.0` (create new tag)* — de
workflow hangt de APK daarna aan die release.

R8/minify staat uit (`isMinifyEnabled = false`): de winst is klein voor een sideloaded LAN-app en het
risico op subtiele problemen met Media3 is het niet waard.

## Installatie en updates via Obtainium

1. Installeer Obtainium op de tablet: APK van de
   [Obtainium-releasepagina](https://github.com/ImranR98/Obtainium/releases) of via F-Droid.
2. Maak op GitHub een **fine-grained Personal Access Token**: *Settings → Developer settings →
   Personal access tokens → Fine-grained tokens → Generate new token*. Kies *Only select
   repositories* → deze repository, en bij *Repository permissions* alleen **Contents: Read-only**
   (Metadata: Read-only wordt automatisch toegevoegd). Kies een ruime vervaldatum.
3. Obtainium → *Settings* → *Source-specific* → **GitHub** → vul het token in bij
   *GitHub Personal Access Token* (nodig omdat de repository privé is).
4. Obtainium → *Add App* → plak de repository-URL (`https://github.com/<eigenaar>/frigastreamer`) → *Add*.
5. Sta bij de eerste installatie *Installeren uit onbekende bronnen* toe voor Obtainium
   (Android vraagt dit automatisch; anders: *Instellingen → Apps → Obtainium → Onbekende apps installeren*).
6. **Updates:** Obtainium controleert periodiek (instelbaar onder *Settings → Background update
   checking*) op nieuwe GitHub Releases en toont een melding. Tik op *Update* om te installeren.
   Omdat elke release met dezelfde sleutel is ondertekend en de `versionCode` steeds hoger is,
   gaat de update over de bestaande installatie heen en blijven de instellingen behouden.
   Er is per release maar één APK, dus er is geen APK-filter nodig.

## Installatie via ADB

Fallback en handig voor logs. Je hebt alleen
[Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb`) en de
[GitHub CLI](https://cli.github.com) (`gh`, ingelogd met `gh auth login`) nodig — geen SDK of compiler.

```bash
./scripts/install.sh            # nieuwste release, op alle verbonden apparaten
```

```bash
./scripts/install.sh v0.2.0     # specifieke release
```

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install.ps1
```

De scripts controleren `gh` en `adb`, downloaden de APK met `gh release download`, installeren met
`adb install -r` op alle verbonden apparaten, starten de app en geven duidelijke foutmeldingen
(o.a. bij `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: andere sleutel → eerst `adb uninstall io.github.rutgersmit.frigastreamer`).

**USB:** zet op de tablet *Ontwikkelaarsopties → USB-foutopsporing* aan (ontwikkelaarsopties
ontgrendel je door 7× op *Buildnummer* te tikken) en bevestig de vraag op het scherm.

**Wireless debugging (Android 11+):** *Ontwikkelaarsopties → Draadloze foutopsporing* aan →
*Apparaat koppelen met koppelingscode*. Dan op je computer:

```bash
adb pair 192.168.1.20:37123      # IP:poort en code uit het koppelingsvenster
```

```bash
adb connect 192.168.1.20:41234   # IP:poort van het hoofdscherm 'Draadloze foutopsporing'
```

## Optioneel: lokaal bouwen

Alleen voor wie wél een JDK (17+) en de Android SDK (platform 37, build-tools) heeft:

```bash
export ANDROID_HOME=/pad/naar/android-sdk
```

```bash
./gradlew testDebugUnitTest assembleDebug
```

De debug-APK (`app/build/outputs/apk/debug/app-debug.apk`) heeft package `io.github.rutgersmit.frigastreamer.debug`
en kan naast de release-versie bestaan. Een release-build lokaal vereist de signing-variabelen
`SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` en `SIGNING_KEY_PASSWORD`.

## Configuratie van Frigate en go2rtc

De app praat met Frigate op twee manieren:

| Wat | Endpoint | Gebruik |
|---|---|---|
| Streamlijst | `http://<ip>:5000/api/go2rtc/streams` | bron van waarheid: welke RTSP-streams bestaan |
| Configuratie | `http://<ip>:5000/api/config` | cameranamen en welke streams bij welke camera horen |
| Versie | `http://<ip>:5000/api/version` | bereikbaarheidscontrole |
| Video | `rtsp://<ip>:8554/<stream>` | go2rtc-restream, altijd **RTSP over TCP** |

Gebruik een **IP-adres** (bv. `192.168.1.10`), geen hostnaam: Android heeft op een LAN geen mDNS.

**API-modi** (instelbaar):

- **Intern** (standaard): poort 5000, geen authenticatie.
- **Geauthenticeerd**: poort 8971. De app logt in met `POST /api/login` (`{"user": ..., "password": ...}`)
  en gebruikt de JWT-cookie `frigate_token`. Bij een `401` logt de app automatisch opnieuw in.
  Frigate serveert 8971 standaard via **HTTPS met een self-signed certificaat**; zet in de app
  *HTTPS gebruiken* en *Self-signed certificaat accepteren* aan (alleen geaccepteerd voor de ingestelde
  host). Heb je in Frigate `tls: enabled: false` gezet, zet dan *HTTPS gebruiken* uit.

**RTSP-authenticatie** staat los van de API en wordt in go2rtc ingesteld:

```yaml
go2rtc:
  rtsp:
    username: "viewer"
    password: "geheim"
  streams:
    voordeur:
      - rtsp://admin:camerawachtwoord@192.168.1.50:554/h264Preview_01_main
    voordeur_sub:
      - rtsp://admin:camerawachtwoord@192.168.1.50:554/h264Preview_01_sub
```

Vul dezelfde gebruikersnaam/wachtwoord in bij *RTSP-gebruikersnaam/-wachtwoord* in de app. De app
geeft die aan Media3 mee via de RTSP-authenticatie; ze komen niet in de eigen logs (zie
[Bekende beperkingen](#bekende-beperkingen)).

Typische Frigate-camera die de go2rtc-restream gebruikt:

```yaml
cameras:
  voordeur:
    ffmpeg:
      inputs:
        - path: rtsp://127.0.0.1:8554/voordeur
          input_args: preset-rtsp-restream
          roles: [record]
        - path: rtsp://127.0.0.1:8554/voordeur_sub
          input_args: preset-rtsp-restream
          roles: [detect]
```

## Koppeling go2rtc-streams ↔ camera's

Een Frigate-camera is alleen via RTSP (8554) bereikbaar als er een go2rtc-stream bestaat. De app
gebruikt daarom `/api/go2rtc/streams` als bron en `/api/config` als aanvulling:

1. **Main stream** van camera *X*: de go2rtc-stream met exact de naam *X*. Anders: de eerste stream uit
   `live.streams` (Frigate 0.16+) of `live.stream_name`; anders de input met rol `record` die naar
   `rtsp://127.0.0.1:8554/<stream>` verwijst; anders een andere go2rtc-input.
2. **Substream**: de stream volgens het **substream-patroon** (standaard `<camera>_sub`, dus
   `voordeur_sub`); anders een go2rtc-input met rol `detect`; anders een andere `live`-stream.
3. **Losse streams** die bij geen camera horen, worden als aparte camera getoond (met hun eigen
   `<naam>_sub` als substream als die bestaat).
4. **Camera's zonder go2rtc-stream** worden niet getoond, maar wel gelogd (`event=camera_without_stream`)
   en in de instellingen vermeld met de reden.

Het patroon is instelbaar, bv. `<camera>-low` of `{camera}_lq` (`<camera>` en `{camera}` werken beide).
De lijst wordt opgehaald bij het starten, bij terugkeer naar de voorgrond, elke 5 minuten, wanneer
Frigate weer bereikbaar wordt en met de knop *Cameralijst opnieuw ophalen*. Nieuwe camera's verschijnen
en verwijderde verdwijnen automatisch. Is Frigate onbereikbaar (of meldt go2rtc tijdens een herstart
een lege lijst), dan blijft de **laatst bekende lijst** (opgeslagen in DataStore) in gebruik.
Handmatig toegevoegde camera's blijven altijd behouden.

## Ondersteunde codecs, H.265 en G.711

| Codec | Status |
|---|---|
| H.264 video | primair ondersteund (hardwaredecoder op vrijwel elke tablet) |
| AAC audio | primair ondersteund |
| H.265/HEVC video | speelt af als de tablet én Media3 RTSP het ondersteunen; **niet gegarandeerd** |
| G.711 (PCMA/PCMU), PCM (L16) audio | Media3 RTSP kan het uitpakken, maar decoderondersteuning verschilt per apparaat; **niet gegarandeerd** |

Gedrag van de app:

- **Niet-ondersteunde audio**: de app verbindt automatisch opnieuw zonder audiotrack; video blijft werken
  en bij ingeschakeld geluid zie je *"Geen geluid: audiocodec niet ondersteund"*.
- **Niet-ondersteunde video**: de camera gaat naar de foutstatus *"Videocodec niet ondersteund"*,
  **zonder** eindeloze reconnect-loop. Andere camera's blijven gewoon werken; *Opnieuw proberen*
  en terugkeer naar de voorgrond geven de camera een nieuwe kans.
- **Decoderfouten** (3× zonder enig beeld) worden ook een permanente codecfout.

**Oplossing: laat go2rtc transcoderen** naar H.264/AAC. Dat kost **extra CPU op de Frigate-host**
(zeker bij hoge resoluties; gebruik waar mogelijk hardware-acceleratie, bv. `#hardware`). De app
transcodeert nooit zelf.

```yaml
go2rtc:
  streams:
    garage_raw:
      - rtsp://admin:pw@192.168.1.51:554/h265stream
    # Wat de app (en Frigate) gebruikt: H.264 + AAC
    garage:
      - "ffmpeg:garage_raw#video=h264#audio=aac"
    # Reolink-deurbel: video is al H.264, alleen de G.711/PCM-audio omzetten
    deurbel:
      - rtsp://admin:pw@192.168.1.52:554/h264Preview_01_main
      - "ffmpeg:deurbel#audio=aac"
```

Alleen een substream transcoderen (goedkoper) kan ook: geef de app dan via het substream-patroon of per
camera (*Substream als actief*) de H.264-variant.

## Reconnect-strategie, watchdog en offline-status

Elke camera heeft een eigen `StreamController` met één centrale state-machine:

```
Idle ─attach→ Connecting ─eerste frame→ Playing
                 ▲   │                      │ fout / watchdog / netwerkverlies
                 │   └──────────────────────┤
                 └── backoff-timer ── Reconnecting ◀┘
 Connecting/Reconnecting langer dan de offline-drempel ⇒ Offline (reconnect loopt door)
 permanente fout (codec, RTSP-auth) ⇒ Error (geen automatische reconnects)
 detach (buiten venster / app naar achtergrond) ⇒ Idle (speler volledig vrijgegeven)
```

- **Backoff:** 1 s, 2 s, 4 s, 8 s, 16 s, 30 s, 30 s, … (min/max instelbaar). Er wordt onbeperkt opnieuw
  geprobeerd bij tijdelijke fouten, ook als de camera als offline wordt getoond. De backoff wordt pas
  gereset na **10 s stabiel beeld**, zodat een flappende verbinding niet elke seconde opnieuw verbindt.
- **Geen dubbele reconnects:** elke nieuwe poging annuleert eerst de vorige timer; callbacks van een
  oude speler-sessie worden via een sessienummer genegeerd. Alles loopt op de main thread.
- **Connect-time-out:** komt er binnen 20 s na het verbinden geen eerste frame, dan telt dat als mislukte
  poging (ruim genoeg voor camera's met een keyframe-interval van 10 s).
- **Frame-watchdog:** de speler telt gerenderde frames via Media3's `VideoFrameMetadataListener`
  (plus door de decoder verwerkte frames voor een voorgeladen speler zonder zichtbare surface).
  `currentPosition` wordt niet gebruikt. Komt er tijdens *Playing* 5 s (instelbaar) geen nieuw frame,
  dan wordt de stream als vastgelopen beschouwd en herstart. Tijdens het opbouwen van de verbinding is de
  watchdog niet actief (dan geldt de connect-time-out); tijdens *buffering* geldt de dubbele time-out.
  Bij een vrijgegeven speler stopt de watchdog.
- **Netwerk:** via `ConnectivityManager`-callbacks (zonder eis van internettoegang, een LAN zonder
  internet is prima). Bij netwerkverlies worden streams gestopt en reconnects gepauzeerd
  (*"Volgende poging: zodra het netwerk terug is"*). Bij herstel verbindt eerst de actieve camera
  direct, daarna de buren; is Frigate nog niet bereikbaar, dan neemt de normale backoff het over.
- **Offline-weergave:** korter dan de offline-drempel (standaard 20 s) blijft het laatste beeld staan met
  een subtiele *Verbinden...*-indicator. Daarna een duidelijk scherm: cameranaam, *Camera offline*,
  tijdstip van het laatste beeld, oorzaak en aftellen tot de volgende poging. Zodra er weer beeld is,
  verdwijnt het scherm direct.
- **Oorzaak** (waar mogelijk onderscheiden met een periodieke API- en RTSP-poortcontrole):
  *Frigate niet bereikbaar*, *Frigate bereikbaar maar RTSP-server (go2rtc) niet*, *Frigate bereikbaar
  maar deze camera niet*, *verbinding open maar geen nieuwe beelden*, *codec-/decoderprobleem*.

**Spelervenster:** maximaal 3 ExoPlayer-instanties (actief + 0, 1 of 2 buren, standaard 1 = de volgende).
Het venster is gebaseerd op de camera-index, niet op de pager-pagina, zodat bij 1 of 2 camera's nooit twee
spelers voor dezelfde camera ontstaan. Spelers buiten het venster worden **eerst** vrijgegeven, daarna pas
nieuwe aangemaakt. Buren worden pas voorgeladen als de actieve camera beeld heeft (of na 3 s) en gebruiken
de substream, gemute. Wordt een buur actief, dan schakelt die naar de voorkeursstream (zelfde speler).

**Decoderlimieten:** veel tablets hebben maar 2–4 hardwaredecoders. Faalt de decoder van een voorgeladen
speler, dan wordt die speler opgegeven en het preload-venster voor de rest van de sessie verkleind. Faalt
de actieve camera, dan worden eerst alle voorgeladen spelers vrijgegeven, wordt het venster verkleind en
de actieve camera direct opnieuw geprobeerd. Pas als het óók zonder andere spelers mislukt, wordt het als
codecfout getoond. Media3's decoder-fallback (bv. naar een softwaredecoder) staat aan.

**Lifecycle:** bij `ON_STOP` (scherm uit, standby, andere app) worden alle spelers en RTSP-verbindingen
vrijgegeven; bij `ON_START` wordt de laatst geselecteerde camera met het venster hersteld. Dit is
idempotent: herhaald wisselen levert geen dubbele spelers of reconnects op. `ON_PAUSE` stopt bewust
niets (in split-screen is de app gepauzeerd maar zichtbaar). Terwijl het instellingenscherm open is,
draaien er geen streams.

## Home-app-modus (automatisch starten na reboot)

Sinds Android 10 mag een app vanuit een `BOOT_COMPLETED`-receiver geen Activity meer starten. De
betrouwbare oplossing: de app kan als **home-app (launcher)** dienen. Kies hem eenmalig als standaard
home-app; na een reboot (of bij de home-knop) start Android hem dan altijd automatisch.

**Instellen:** druk op de home-knop en kies *FrigaStreamer* → *Altijd*. Of: app-instellingen → *Overig* →
*Standaard home-app instellen / terugzetten*, of Android *Instellingen → Apps → Standaard-apps → Startscherm-app*.

Via ADB:

```bash
adb shell cmd package set-home-activity io.github.rutgersmit.frigastreamer/.ui.MainActivity
```

**Terugdraaien:** dezelfde knop in de app-instellingen, of in Android *Startscherm-app* een andere
launcher kiezen. Via ADB (voorbeeld voor de Pixel-launcher; zoek je eigen launcher op met de eerste opdracht):

```bash
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME
```

```bash
adb shell cmd package set-home-activity com.google.android.apps.nexuslauncher/.NexusLauncherActivity
```

Kom je niet meer uit de app (instellingen bereik je altijd via lang indrukken of het tandwiel), dan
werkt bovenstaande ADB-opdracht ook, of verwijder de app met `adb uninstall io.github.rutgersmit.frigastreamer`.

**Waarom geen `BOOT_COMPLETED`-receiver?** Die zou alleen werken met de speciale permissie
`SYSTEM_ALERT_WINDOW` (via ADB toe te kennen), en het gedrag verschilt per fabrikant/Android-versie.
Omdat ik dat niet op de doel-tablet heb kunnen aantonen, zit er bewust geen receiver in de app; de
home-app-modus is de ondersteunde weg.

## Instellingen

Bereikbaar via het **tandwiel** (tik eerst op het scherm als de knoppen verborgen zijn) of door het
scherm **lang in te drukken**. Alles wordt opgeslagen in Jetpack DataStore.

- **Frigate:** IP-adres, API-modus (intern 5000 / geauthenticeerd 8971 met login), API-poort, HTTPS en
  self-signed (alleen geauthenticeerd), API-gebruiker/-wachtwoord, RTSP-poort (8554), RTSP-gebruiker/
  -wachtwoord (go2rtc), substream-patroon. *Opslaan* haalt de cameralijst direct opnieuw op.
- **Cameralijst:** status van de API, aantal streams, camera's zonder stream, *Cameralijst opnieuw ophalen*,
  *Camera handmatig toevoegen*. Per camera: zichtbaar/verborgen, volgorde (▲/▼), *Substream als actief*,
  *Bewerken* (naam, main-/sub-URL; bij een automatische camera alleen als je de URL echt wijzigt) en
  *Verwijderen* (handmatige camera's).
- **Herstel:** preload-venster (0/1/2), offline-drempel, frame-watchdog aan/uit en time-out, backoff min/max.
- **Overig:** altijd gemute starten, schermoriëntatie (liggend/staand/automatisch), debug-overlay,
  standaard home-app instellen/terugzetten, versie.

Geluid staat standaard uit; de mute-knop werkt voor alle camera's en de keuze blijft bewaard (met
*Altijd gemute starten* aan begint de app altijd gemute). Alleen de actieve camera speelt ooit geluid af.
Credentials staan gewoon in DataStore (LAN-app op een eigen apparaat; geen Keystore of het verouderde
`EncryptedSharedPreferences`), maar komen nooit in logs.

## Logs uitlezen via ADB

Alle regels zijn gestructureerd (`event=... key=value`). Tags:

| Tag | Inhoud |
|---|---|
| `FS.Cam.<camera>` | per camera: `connect_start`, `connect_success`, `connection_lost`, `connect_failed`, `watchdog_triggered`, `reconnect_scheduled`, `reconnect_execute`, `codec_error`, `stream_error`, `offline`, `player_created`, `player_released`, `network_lost`, `backoff_reset` |
| `FS.Manager` | venster, `preload_window_reduced`, `release_preloads_for_active`, `start`/`stop` |
| `FS.Net` | netwerk weg/terug |
| `FS.Api`, `FS.Health` | cameralijst, login, bereikbaarheid van Frigate/RTSP |
| `FS.App` | lifecycle (`ON_START`, `ON_STOP`), versie |

```bash
adb logcat -v time | grep " FS\."
```

```bash
adb logcat -v time '*:S' FS.Manager:V FS.Net:V 'FS.Cam.voordeur:V'
```

```bash
adb logcat -v time | grep -E "watchdog_triggered|reconnect_scheduled|codec_error|player_(created|released)"
```

Geheugen controleren: `adb shell dumpsys meminfo io.github.rutgersmit.frigastreamer`.

## Testomgeving (`testenv/`) en storingen simuleren

Een Docker-omgeving om de app zonder Frigate te testen:

```bash
cd testenv
```

```bash
docker compose up -d
```

| Service | Poort | Rol |
|---|---|---|
| `mediamtx` | 8554 (RTSP), 9997 (API) | speelt go2rtc: 13 testpatroonstreams (ffmpeg) |
| `cam-voordeur` | – | apart ffmpeg-proces voor `voordeur` + `voordeur_sub` (om te stoppen/pauzeren) |
| `frigate-mock` | 5000, 8971 | bootst `/api/go2rtc/streams`, `/api/config`, `/api/version`, `/api/login` na |

Streams: `voordeur`+`voordeur_sub` (H.264 720p + AAC), `tuin`+`tuin_sub` (H.264 + AAC), `oprit` (geen audio),
`garage` (**H.265**), `deurbel` (H.264 + **G.711 A-law**, in de config gekoppeld aan camera `voorkant_deurbel`),
`schuur` (H.264 + **PCM L16**), `snel_gop` (keyframe elke 0,5 s), `traag_gop` (keyframe elke **10 s**),
`achterdeur` (1080p), `carport`+`carport_sub` (niet in de config: losse camera). In de config staan ook
`zolder` (zonder go2rtc-stream) en `oude_camera` (uitgeschakeld) om het melden van ontbrekende camera's te testen.

In de app: host = IP van de computer met Docker, API-modus intern, poort 5000. Voor de geauthenticeerde
modus: poort 8971, gebruiker/wachtwoord `admin`/`admin`, **HTTPS uit** (de mock gebruikt HTTP; tokens
verlopen na `TOKEN_TTL` = 300 s, zodat automatisch opnieuw inloggen te testen is).

Alle streams controleren (vereist `ffprobe`): `./check-streams.sh [host]`.

**Storingen simuleren:**

| Scenario | Opdracht | Verwacht in de app |
|---|---|---|
| Eén camera offline | `docker compose stop cam-voordeur` … `docker compose start cam-voordeur` | alleen voordeur: *Verbinden...* → na 20 s *Camera offline* (RTSP 404); andere camera's spelen door; na start herstel |
| Verbinding open, geen frames | `docker compose pause cam-voordeur` … `docker compose unpause cam-voordeur` | na 5 s `watchdog_triggered`, reconnect |
| "Frigate" offline (60 s) | `docker compose stop mediamtx; sleep 60; docker compose start mediamtx` | alle camera's offline, backoff 1→30 s, herstel ≤ 30 s na start |
| API onbereikbaar | `docker compose stop frigate-mock` | streams spelen door; laatst bekende cameralijst blijft; *Frigate-API: onbereikbaar* |
| Herstart als `docker restart frigate` | `docker compose restart mediamtx frigate-mock` | korte *Verbinden...*, daarna herstel |
| Hoge keyframe-interval | swipe naar `traag_gop` | eerste beeld tot ~10 s, géén watchdog-herstart |
| Codecs | swipe naar `garage` / `deurbel` / `schuur` | afhankelijk van de tablet: beeld, of een duidelijke codecmelding / *geen geluid* |

Wifi-onderbrekingen simuleer je op de tablet zelf (wifi uit/aan).

## Tests

- **Unit tests** (`./gradlew testDebugUnitTest`): backoff (incl. reset), state-machine-overgangen,
  foutclassificatie, offline-drempel, watchdog (incl. geen herstart tijdens verbindingsopbouw), annuleren van
  reconnects en geen dubbele reconnects, spelervenster (wrap-around, 1/2/10+ camera's, nooit > 3 spelers),
  automatisch verkleinen bij decoderfouten, stream↔camera-koppeling, cameralijst, JSON-parsing, redactie van
  credentials, en de 7 praktijkscenario's uit de opdracht (`ScenarioTest`). De `StreamController` wordt getest
  met een fake speler, fake server, fake klok en `kotlinx-coroutines-test` (virtuele tijd).
- **Integratietest** tegen `testenv/` (opt-in):
  `FS_TESTENV_HOST=127.0.0.1 ./gradlew testDebugUnitTest --tests '*TestEnvIntegrationTest*'`
  (met `FS_TESTENV_TOKEN_TTL=<s>` ook het verlopen van de JWT; start de mock dan met dezelfde `TOKEN_TTL`).
- **Op de tablet:** zie [TESTING.md](TESTING.md).

## Bekende beperkingen

- **RTSP-credentials in de URI:** Media3 RTSP ondersteunt authenticatie alléén via de userinfo van de URI
  (`rtsp://user:pass@...`). De app voegt de go2rtc-credentials pas in de spelerlaag toe, logt zelf alleen
  geredacteerde URL's en zet de RTSP-debuglogging van Media3 uit. Media3 zelf haalt de userinfo uit de
  requests; in de eigen foutmeldingen van Media3 is ons geen URL-logging bekend, maar dat valt buiten de
  controle van de app.
- **Cleartext:** Android kan cleartext-verkeer niet beperken tot IP-bereiken (Network Security Config
  accepteert alleen hostnamen). Cleartext staat daarom app-breed aan; de app maakt alleen verbinding met de
  ingestelde Frigate-host en handmatig ingevoerde URL's.
- **H.265, G.711, PCM:** afhankelijk van de tablet; zie [codecs](#ondersteunde-codecs-h265-en-g711).
- **Decoderlimieten** verschillen per tablet; het venster verkleint zich automatisch, maar alleen voor de
  huidige sessie (na een herstart van de app wordt weer het ingestelde venster geprobeerd).
- **Laatste beeld bij offline:** het laatste beeld blijft staan zolang de camera een speler heeft (in het
  venster). Na swipen of terugkeer uit de achtergrond is er pas beeld na de eerste nieuwe frame.
- **Twee camera's:** links en rechts van de huidige camera staat dezelfde camera. De speler wordt aan de
  rechterpagina gekoppeld; tijdens het naar rechts swipen zie je links daarom kort een zwarte pagina met naam.
- **Geen automatische start na boot zonder home-app-modus** (zie hierboven).
- **Netwerkdetectie** ziet alleen of de tablet een netwerk heeft, niet of Frigate bereikbaar is; daarvoor
  zijn de reconnect-backoff en de periodieke bereikbaarheidscontrole.

## Troubleshooting

| Symptoom | Oplossing |
|---|---|
| Geen camera's | Klopt het IP-adres? `curl http://<ip>:5000/api/go2rtc/streams` vanaf een andere machine. In de instellingen staat waarom camera's ontbreken. |
| Camera ontbreekt | Er is geen go2rtc-stream voor die camera (zie *Camera's zonder go2rtc-stream* in de instellingen), of het substream-patroon klopt niet. |
| *RTSP-authenticatie mislukt* | Vul RTSP-gebruiker/-wachtwoord in zoals in `go2rtc: rtsp:` staat. |
| *Videocodec niet ondersteund* | Laat go2rtc naar H.264 transcoderen (zie boven). |
| Geen geluid | Ontmuten? Bij *audiocodec niet ondersteund*: `#audio=aac` in go2rtc. |
| Actieve camera start niet, buren wel | Decoderlimiet: zet het preload-venster op 0 of 1, of gebruik substreams. Debug-overlay toont *AUTO VERKLEIND*. |
| Inloggen mislukt (8971) | Controleer gebruiker/wachtwoord en de HTTPS-instelling. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | De geïnstalleerde app is met een andere sleutel ondertekend: eenmalig `adb uninstall io.github.rutgersmit.frigastreamer`. |
| Release-workflow faalt met *signing ontbreekt* | Zet de vier GitHub Secrets (zie [Eenmalige setup](#eenmalige-setup-keystore--github-secrets)). |
| Obtainium vindt geen release | Token met *Contents: Read-only* op deze repository ingevuld? Bestaat er een release (niet alleen een tag)? |

Zet bij twijfel de **debug-overlay** aan (instellingen → *Overig*) en lees de logs (`adb logcat | grep " FS\."`).
