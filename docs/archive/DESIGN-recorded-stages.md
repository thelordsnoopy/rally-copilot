# Rally Copilot — Design & Architecture Spec

**Status:** draft for approval · **Date:** 2026-08-26
**Platform:** Android native, Kotlin + Jetpack Compose, offline-first
**Scope:** (1) GPS-synced pacenote co-driver, (2) TSD / regularity rally computer

---

## 1. What this thing is

Two features that look unrelated but are the same machine wearing two hats:

| | Pacenotes | TSD |
|---|---|---|
| Input | Recorded stage track + notes at distances | Route instructions at mileages |
| Runtime question | "Where am I along the route?" | "Where am I along the route?" |
| Output | Speak the note ~2–3 s before the corner | Show seconds early/late vs. perfect time |

Both need: a GPS fix pipeline, a calibrated odometer, a way to turn a lat/lon into
*distance along a known route*, and a scheduler that fires events at distances.
Build that core once, skin it twice.

### Non-goals for v1
- Offline vector maps (see §7 — deliberately deferred)
- Cloud sync, accounts, sharing
- iOS
- Voice *recognition* for note dictation

---

## 2. The three hard problems

Everything else is CRUD. These are what the design actually has to solve.

### 2.1 GPS is late and coarse

At 120 km/h you cover **33 m per second**. A 1 Hz fix that arrives 300 ms after its
epoch means your believed position is ~43 m stale — most of a corner. Consumer GPS
also drifts laterally by 3–10 m.

**Mitigations:**

- **Snap to route, do not trust raw position.** Lateral drift is irrelevant if we
  project each fix onto the known route polyline and only care about the scalar
  `distanceAlongRoute`. This is the single most important modelling decision in the
  whole app.
- **Dead-reckon forward.** Between fixes, advance `distanceAlongRoute` by
  `speed × timeSinceFix`, using the fix's own speed and bearing. Re-anchor on each new
  fix by blending over ~300 ms rather than jumping, to avoid audible stutter.
- **Request the highest rate the device will give.** `FusedLocationProviderClient` at a
  200 ms interval; many devices cap at 1 Hz, some manage 5–10 Hz. Handle variable rate.
- **Reject bad fixes.** Drop fixes with accuracy worse than 25 m, or implausible jumps.

### 2.2 Text-to-speech is too slow to call live

Android TTS has 100–400 ms of startup latency and it is not deterministic. You cannot
call `speak()` at the trigger point and expect the note on time.

**Mitigation: pre-synthesise at stage-load time.**

- On loading a stage, walk every note and render `spokenText` via
  `TextToSpeech.synthesizeToFile()` into a per-stage audio cache, keyed by a hash of
  (voice, rate, pitch, spokenText).
- **Measure and store each clip's duration.** The scheduler needs it — see §2.3.
- Play through a pre-warmed low-latency player (ExoPlayer with the clip preloaded, or
  `SoundPool` for short clips). Target under 30 ms from trigger to first sample.
- The cache invalidates when voice, rate, or pitch changes. Show a progress bar; it is
  a few seconds for a 20 km stage.

### 2.3 "Call it early" is a *time* problem, not a distance problem

A fixed 50 m lead is far too late at 140 km/h and absurdly early at 40 km/h. Worse, a
long note — "left four tightens, do not cut, into crest" — takes ~2.5 s to say. If you
*start* it at a fixed lead, you *finish* it too late.

**Model: anchor the END of the utterance, not the start.**

```
startDistance = noteDistance
              − speed × (endLeadSeconds + clipDurationSeconds)
              − fixedOffsetMeters
```

- `endLeadSeconds` — how long before the corner the driver must have heard the *whole*
  note. Default 1.5 s, tunable 0.5–4.0 s. This is the main "feel" knob.
- `fixedOffsetMeters` — trims constant system latency. Default 0, tunable ±30 m.
- `speed` — the smoothed, predicted forward speed, not the raw fix value.

**Chaining.** Real co-drivers run notes together in one breath. If note N+1's start
distance falls before note N's utterance ends, merge them into a single queued burst
rather than queueing and progressively falling behind. If a burst would exceed ~4 s,
drop the link-distance callout ("one hundred") and keep the corners — degrade
gracefully, never lag.

**Never speak a note whose corner is already behind you.** Skip it and log the skip.

---

## 3. Architecture

Gradle multi-module. The rule: **`:core:engine` is pure Kotlin, has no Android
dependencies, and is driven entirely by injected interfaces.**

```
:app                  navigation, DI wiring, theme
:core:model           pure domain types (Stage, Note, Fix, TsdInstruction…)
:core:geo             haversine, polyline, snap-to-route, cumulative distance, calibration
:core:engine          fix pipeline, odometer, predictor, trigger scheduler  ← pure, fully unit tested
:core:data            Room DB, repositories, import/export
:core:audio           TTS pre-synth, clip cache, low-latency playback, focus/routing
:feature:recce        record track + drop notes live
:feature:editor       edit notes against a recorded track
:feature:stage        live pacenote HUD
:feature:tsd          TSD route setup + live TSD HUD
:feature:replay       run log browser + replay scrubber
```

### 3.1 The decision that makes this project tractable

```kotlin
interface FixSource {
    val fixes: Flow<Fix>
}
```

Two implementations:

- `FusedFixSource` — the real GPS.
- `ReplayFixSource` — replays a recorded run log at 1×, 10×, or as fast as it can.

Because the engine is pure and its clock is injected, **you can develop and tune the
entire app at your desk.** Record one real stage once, then iterate on lead time,
chaining, and dead-reckoning against that exact run, deterministically, in a unit test.

Without this you are debugging by driving — slow, expensive, and unsafe. With it, the
in-car sessions are for *collecting data*, not for *finding bugs*.

### 3.2 Runtime pipeline

```
FusedLocationProvider
    → FixValidator        drop accuracy > 25 m, implausible jumps
    → RouteSnapper        project onto polyline → distanceAlongRoute      [:core:geo]
    → MotionPredictor     dead-reckon between fixes, blend on re-anchor
    → Odometer            × calibrationFactor, ± manual nudge
    → TriggerScheduler    fires NoteDue / InstructionDue / CheckpointDue
    → { AudioEngine, HudState, RunLogger }
```

`RunLogger` writes **every** fix and **every** event to disk. This is non-negotiable —
it is what feeds `ReplayFixSource`, and what lets you answer "why was that note late?"

---

## 4. Data model

### 4.1 Pacenotes

```
Stage(id, name, createdAt, lengthMeters, calibrationFactor, noteSystemId)

TrackPoint(stageId, seq, lat, lon, elevationM?,
           cumulativeDistanceM, recordedAtMs, accuracyM, speedMps)

Note(id, stageId,
     distanceM,          // metres from stage start — the anchor for everything
     rawText,            // compact notation, e.g. "L4 tightens 100 R2 !"
     spokenText,         // TTS-expanded, user-overridable
     audioCacheKey,      // hash → cached clip
     clipDurationMs,     // measured at synth time
     isCaution)          // drives red HUD styling and optionally an earlier lead
```

Notes anchor to **distance along the route**, never to a lat/lon. Re-editing the track
recomputes cumulative distances; note distances are preserved.

### 4.2 Pacenote notation

A small grammar, stored as `rawText` and expanded into `spokenText`:

- **Corner** — direction `L` / `R` plus severity `1`–`6`, or named: `HP` hairpin,
  `SQ` square, `K` kink, `FL` flat
- **Modifiers** — `tightens`, `opens`, `long`, `short`, `double`, `into`, `cut`,
  `dont cut`, `keep in`, `keep out`, `late`
- **Standalone** — `crest`, `jump`, `bump`, `dip`, `narrows`, `junction`, `bridge`,
  `gate`, `caution` (`!`), `danger` (`!!`), `finish`
- **Link** — a bare number means metres to the next note

**Severity direction is configurable per note system** — some crews use 1 = hairpin,
others 1 = flat out. Getting this backwards is dangerous, so it is an explicit,
prominent setting with a confirm-on-change dialog, not a buried preference. The active
scale is also shown on the HUD.

Also configurable: word order ("left four" vs. "four left"), and whether link distances
are spoken at all.

### 4.3 TSD

```
TsdRoute(id, name, calibrationFactor, startTimeMs?)

TsdInstruction(id, routeId, seq, atDistanceM,
               type,            // CAST | TRANSIT | CHECKPOINT | PAUSE | RESET_ODO | FREE_ZONE
               speedKph?,       // for CAST
               pauseSeconds?,   // for PAUSE
               text)            // human-readable route book line
```

**Perfect time** at distance `d` = the sum over preceding segments of
`segmentLength / castSpeed`, plus any `PAUSE` durations. Then:

```
secondsEarlyLate = actualElapsed − perfectElapsed      // positive = late
```

That number is the single largest thing on the TSD screen.

Time source is **GPS time**, not the device clock — it is authoritative, available
offline, and does not drift.

### 4.4 Run logs (both modes)

```
Run(id, stageId?, routeId?, mode, startedAtMs, endedAtMs, appVersion, deviceModel,
    leadSecondsUsed, fixedOffsetUsed, calibrationUsed)

RunFix(runId, tMs, lat, lon, speedMps, bearingDeg, accuracyM, distAlongRouteM, wasPredicted)

RunEvent(runId, tMs, distAlongRouteM, type, payload)
    // NOTE_SPOKEN, NOTE_SKIPPED, NOTE_CHAINED, ODO_NUDGE,
    // CHECKPOINT, CAST_CHANGE, FIX_REJECTED, GPS_LOST
```

Room + SQLite. Export and import as a single zip per stage or route, so runs can be
backed up, shared, and hand-edited.

---

## 5. Screen flow

```
Home ─┬─ Stages ──────┬─ Recce (record track + drop notes)
      │               ├─ Note Editor (list + track scrubber)
      │               └─ ▶ STAGE HUD            ← live, landscape
      │
      ├─ TSD Routes ──┬─ Route Book Editor (CAST table)
      │               └─ ▶ TSD HUD              ← live, landscape
      │
      ├─ Run Logs ───── Replay scrubber (re-run any recorded stage at your desk)
      │
      └─ Settings ────┬─ Note system (severity direction, word order)
                      ├─ Voice (engine, rate, pitch) → triggers re-synth
                      ├─ Lead time / fixed offset
                      ├─ Odo calibration (measured-mile wizard)
                      └─ Audio routing (speaker / A2DP / BT SCO intercom)
```

### 5.1 HUD design rules

This screen is read at speed, in daylight, by someone being shaken, possibly gloved and
wearing a helmet. Therefore:

- **Landscape, dark, maximum contrast.** No gradients, no thin fonts.
- **The current note is the biggest thing on screen** — 96 sp or larger, with the next
  note below it at around 48 sp. In TSD mode the early/late seconds take that slot.
- **Two giant thumb targets in the bottom corners: odo −10 m and +10 m.** Every real
  rally computer has this. They must be reachable without looking. Long-press for ±100 m.
- **Nothing else is tappable during a run** except lock/unlock and pause. Accidental
  taps are guaranteed; make them harmless.
- **Colour is a signal, not decoration.** Caution red, on-time green, early blue, late
  amber — always paired with position or size, never colour alone.
- `FLAG_KEEP_SCREEN_ON`, plus an optional force-max-brightness mode.
- **Degraded GPS is loud.** A persistent, unmissable banner if accuracy drops or fixes
  stop arriving. Silently guessing is the worst possible failure mode for this app.

---

## 6. Android platform specifics

| Concern | Decision |
|---|---|
| Location | `FusedLocationProviderClient`, `PRIORITY_HIGH_ACCURACY`, 200 ms requested |
| Background | Foreground service, `foregroundServiceType="location"`, `FOREGROUND_SERVICE_LOCATION` (Android 14+) |
| Permissions | `ACCESS_FINE_LOCATION` — foreground-only is sufficient; the service runs while the app is open |
| Audio focus | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` — ducks music, coexists with nav |
| Intercom | Selectable output: speaker / A2DP / **BT SCO**. Crew intercoms are usually SCO — poor quality, but it is where the driver's ears actually are |
| Storage | Room; audio cache in `filesDir/stage-audio/<stageId>/` |
| DI | Hilt |
| Min SDK | 26 — covers cheap dedicated devices; target the latest |
| Testing | JUnit + Turbine over `:core:engine`; replay-log golden tests |

**Thermal and battery warning:** full brightness plus 1 Hz GPS plus a phone in the sun
on a windscreen is a thermal-throttling scenario. Budget for a "reduce brightness when
no note is due" mode, and tell the user to get airflow onto the device.

---

## 7. Deliberate omission: no maps in v1

Adding MapLibre plus offline MBTiles is days of work and a large binary. For the recce
and editor screens, **render the recorded track as a self-scaling polyline on a
`Canvas`** — auto-fit the bounds, draw note markers on it, let the user scrub. For the
live HUD a map is actively unhelpful; you want the note, huge.

If real usage proves a basemap is needed for placing notes, add MapLibre in v2 behind an
interface. Do not pay for it up front.

---

## 8. Build order

Each phase ends with something testable. Phases 0–2 are the risky part; everything after
is comparatively mechanical.

- **Phase 0 — Skeleton and harness.** Modules, Hilt, Room, the `FixSource` interface,
  `RunLogger`, `ReplayFixSource`. Record a raw GPS track on any drive and replay it.
  *Exit: a log replays deterministically in a unit test.*
- **Phase 1 — Geo core.** Polyline, cumulative distance, snap-to-route,
  `MotionPredictor`, `Odometer` with calibration. Golden tests against the recorded log.
  *Exit: distance-along-route is accurate to a few metres on replay.*
- **Phase 2 — Audio core.** TTS pre-synth, clip cache, duration measurement,
  low-latency playback, audio focus, chaining.
  *Exit: measured trigger-to-sound under 30 ms.*
- **Phase 3 — Pacenote MVP.** Notation parser, `Note` model, `TriggerScheduler` with the
  end-anchored lead model, Stage HUD.
  *Exit: a replayed stage calls its notes at plausible times.*
- **Phase 4 — Recce and editor.** Record track, tap-grid note entry, canvas track
  scrubber, edit/delete/nudge notes.
  *Exit: create a stage end-to-end, in the car.*
- **Phase 5 — First real drive and tuning.** Collect logs, tune `endLeadSeconds` and
  `fixedOffsetMeters` against replay.
  *Exit: it feels right to a real driver.*
- **Phase 6 — TSD.** Route book editor, perfect-time engine, TSD HUD, measured-mile
  calibration wizard.
  *Exit: run a real TSD route and score within a second.*
- **Phase 7 — Polish.** Import/export, settings, degraded-GPS handling, thermal mode.

---

## 9. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| GPS lag makes note timing feel wrong | **High** | Dead reckoning, end-anchored lead, replay-based tuning (§2.1, §2.3) |
| TTS latency and jitter | **High** | Pre-synthesis; never call `speak()` live (§2.2) |
| Severity scale inverted by the user | **High** (safety) | Explicit prominent setting, confirm-on-change, scale shown on HUD |
| Tuning requires driving | Medium | The replay harness — the entire point of §3.1 |
| Thermal throttling / battery drain | Medium | Brightness management, airflow guidance, test on the target device |
| BT SCO audio quality and routing | Medium | Selectable routing; test with a real intercom in Phase 2, not Phase 7 |
| Odo calibration wrong in TSD | Medium | Measured-mile wizard, per-route factor, live nudge |

---

## 10. Regulatory note

Many rally series restrict or prohibit GPS-triggered pacenote systems in competition,
and the rules differ by championship and by country. This design assumes use for
**testing, recce, practice, road and regularity events, and non-FIA events** — check the
regulations for any event you intend to use it in before relying on it.

---

## 11. Open questions

1. **Severity scale** — do you use 1 = hairpin, or 1 = flat out? Both will be supported;
   which is the default?
2. **Target device** — your daily phone, or a cheap dedicated one that lives in the car?
   Affects min SDK, GPS rate assumptions, and how hard we fight thermals.
3. **Intercom** — is there a Bluetooth crew intercom in the loop, or phone speaker? Worth
   testing in Phase 2, not Phase 7.
4. **TSD flavour** — traditional CAST route book, or GPS-waypoint regularity? Changes the
   route book editor substantially.
5. **Note entry during recce** — tap-grid only for v1 (my recommendation), or do you also
   need to record your voice as a fallback and transcribe later?
