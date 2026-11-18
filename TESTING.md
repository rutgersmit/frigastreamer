# Handmatige verificatie op de tablet

Deze checklist dekt wat alleen op een echt apparaat te testen is. Wat al automatisch getest
wordt, staat onderaan.

## Voorbereiding

1. Installeer de nieuwste release (Obtainium of `./scripts/install.sh`).
2. Instellingen → *Overig* → **Debug-overlay tonen** aan.
3. Verbind ADB (USB of wireless, zie README) en laat in een terminal de logs meelopen:

   ```bash
   adb logcat -v time | grep " FS\."
   ```

**De debug-overlay** (linksboven) toont:

```
FrigaStreamer 0.1.0 (12)
spelers: 2/3 (max gezien 3)  preload: 1 (ingesteld 1)
netwerk: ok [wifi]  frigate api=OK rtsp=OK
actief: 3/12  gestart=true  mute=true  t=...
• voordeur [ACTIVE] MAIN p#7 Playing frame=40ms video/avc 1920x1080 n=1
• tuin [PRELOAD] SUB p#8 Playing frame=120ms avc 640x360 n=1
```

per camera: rol, stream (MAIN/SUB), spelerinstantie (`p#`), toestand (+ pogingnummer en oorzaak),
tijd sinds laatste frame, backoff en tijd tot volgende poging, codec/resolutie, aantal verbindingen (`n=`).

Noteer per scenario: ✅/❌ en bijzonderheden.

---

## 1. Wifi uit en weer aan

1. Laat een camera spelen. Zet wifi uit (snelle instellingen).
2. Wacht ~30 s, zet wifi weer aan.

| Verwacht in de overlay | Verwacht in logcat |
|---|---|
| `netwerk: WEG`; camera's: `Reconnecting`/`Offline ... NETWORK_UNAVAILABLE`, **geen** oplopend `n=` zolang wifi uit is | `FS.Net event=network_lost`, per camera `event=network_lost action=pause_reconnects`, `event=waiting_for_network` |
| Na 20 s offline-scherm met *Volgende poging: zodra het netwerk terug is* | `event=offline` |
| Na wifi aan: actieve camera direct `Connecting`, binnen enkele seconden `Playing`; daarna de buur | `event=network_restored action=reconnect_now`, eerst de actieve camera `connect_start`, dan `connect_success` |

Als de tablet het netwerk eerder ziet dan Frigate bereikbaar is: een paar `reconnect_scheduled ... delay_ms=1000/2000/...` en daarna herstel.

## 2. Scherm uit/aan en terugkeer uit standby

1. Druk op de aan/uit-knop (scherm uit), wacht 1 minuut, zet het scherm weer aan (en ontgrendel).
2. Herhaal met een langere standby (≥ 10 min).

| Verwacht | logcat |
|---|---|
| Scherm uit: `spelers: 0/3` (alle RTSP-verbindingen dicht) | `FS.App event=lifecycle state=ON_STOP`, `FS.Manager event=stop releasing_players=N`, per speler `player_released` |
| Scherm aan: dezelfde camera als ervoor, binnen enkele seconden beeld, `spelers` weer gelijk aan het venster | `ON_START`, `event=start`, `event=attach role=ACTIVE`, `connect_success` |
| Nooit meer spelers dan het venster (`max gezien` ≤ 3) | geen dubbele `player_created` voor dezelfde camera |

## 3. App naar de achtergrond en terug

1. Druk op home (als de app geen home-app is) of open een andere app; keer terug via recente apps.
2. Doe dit 10× snel achter elkaar.

Verwacht: hetzelfde als bij 2. Na afloop `spelers` = venstergrootte, `max gezien` ≤ 3, per camera in de
overlay `n=` laag (geen reconnect-stormen). In logcat per `ON_STOP` evenveel `player_released` als er
spelers waren.

## 4. Echte Frigate-herstart

```bash
docker restart frigate
```

| Verwacht | logcat |
|---|---|
| Eerst kort *Verbinden...* over het laatste beeld | `connection_lost category=NETWORK` of `STREAM_ENDED`, of `watchdog_triggered` |
| Duurt de herstart > 20 s: offline-scherm met oorzaak (*Frigate is niet bereikbaar* / *RTSP-server (go2rtc) niet*) en aftellen | `reconnect_scheduled attempt=1 delay_ms=1000`, `attempt=2 delay_ms=2000`, … max 30000 |
| Na de herstart herstel zonder ingrijpen; cameralijst blijft gelijk | `connect_success ... recovered=true`, later `backoff_reset` |
| Settings → Cameralijst: *Frigate-API: bereikbaar* | `FS.Health event=frigate_health api=OK rtsp=OK` |

## 5. Snel swipen met de echte camera's

1. Preload-venster op 2. Swipe ~20× zo snel mogelijk heen en weer (ook over de wrap-around van laatste naar eerste).
2. Laat los en wacht 5 s.

Verwacht: `spelers` **nooit boven 3** en `max gezien` ≤ 3; na het loslaten de actieve camera als eerste
`Playing`, daarna de buren (`[PRELOAD] SUB`). Geen vastgelopen UI. In logcat geen `player_limit_exceeded`;
`player_created` en `player_released` lopen gelijk op.

## 6. Decoderlimieten van de tablet

1. Preload-venster op 2, en voor alle camera's de main stream (zet bij buren desnoods tijdelijk de
   substream-URL leeg of kies *Substream als actief* uit; buren gebruiken de substream als die bestaat).
2. Swipe naar de camera met de hoogste resolutie / H.265.

Verwacht als de tablet te weinig decoders heeft:
- De actieve camera blijft (of komt) gewoon in beeld.
- Overlay: `preload: 1 (ingesteld 2, AUTO VERKLEIND)` of `0`.
- logcat: `codec_error category=DECODER_INIT`, `preload_window_reduced new_size=…`, en bij de actieve camera
  eventueel `release_preloads_for_active` + `decoder_retry`.
- Alleen als de camera óók zonder andere spelers niet start: foutscherm *Videodecoder kon niet starten*.

## 7. Automatisch starten na reboot (home-app)

1. Instellingen → *Standaard home-app instellen* → kies FrigaStreamer.
2. `adb reboot` (of herstart via de aan/uit-knop).

Verwacht: na het opstarten (en eventueel ontgrendelen) opent FrigaStreamer automatisch op de laatst bekeken
camera. logcat: `FS.App event=app_start`, `ON_START`. Terugzetten: zelfde knop → andere launcher kiezen.

## 8. Langdurig draaien en geheugen

1. Laat de app een paar uur (liefst een nacht) draaien met preload 1, scherm aan.
2. Meet aan het begin, na 1 uur en aan het eind:

   ```bash
   adb shell dumpsys meminfo io.github.rutgersmit.frigastreamer | grep -E "TOTAL PSS|TOTAL RSS|Native Heap|Java Heap|Views:|Activities:"
   ```

Verwacht: na de opwarmfase geen gestage groei van *TOTAL PSS* / *Native Heap* (enkele MB schommeling is
normaal), `Activities: 1`, aantal *Views* stabiel. In de overlay `spelers` constant; `n=` per camera alleen
hoger bij echte storingen. Optioneel tussendoor een paar storingen veroorzaken (4) en opnieuw meten.

---

## Extra controles

- **Codecs:** swipe naar een H.265-camera / de Reolink-deurbel met geluid aan. Verwacht: beeld, óf een
  duidelijke melding (*Videocodec niet ondersteund* zonder reconnect-loop: `n=1` blijft staan) of
  *Geen geluid: audiocodec niet ondersteund* met doorlopend beeld.
- **Eén camera stroomloos:** trek de stekker uit één camera. Alleen die camera gaat via *Verbinden...* naar
  *Camera offline* (*Frigate is bereikbaar, maar deze camera niet*); de rest speelt door.
- **Oriëntatie:** draai de tablet / wissel de instelling; video schaalt mee, geen nieuwe spelers (`p#` gelijk).
- **Mute:** geluid aan, swipe → het geluid volgt de actieve camera, nooit twee camera's tegelijk.
- **Credentials niet in logs:** `adb logcat -d | grep -i -E "<jouw-wachtwoord>|frigate_token="` geeft niets.

## Al automatisch getest (geen tablet nodig)

| Laag | Wat | Hoe |
|---|---|---|
| 1 | State-machine, backoff + reset, watchdog, offline-drempel, foutclassificatie, venster (1/2/10+ camera's), decoder-afhandeling, koppeling streams↔camera's, 7 praktijkscenario's | `./gradlew testDebugUnitTest` (draait in CI) |
| 2 | API-client tegen de mock (intern + login + token verlopen), koppeling met echte go2rtc-achtige streamlijst, RTSP-streams en storingen op protocolniveau | `testenv/` + `TestEnvIntegrationTest` + `check-streams.sh` |
| 3 | Echte ExoPlayer/decoder, netwerkcallbacks, lifecycle, geheugen, home-app | deze checklist |
