# Rally Copilot — Design & Architecture Spec

**Status:** draft for approval · **Date:** 2026-08-26 · **Supersedes:** [archive/DESIGN-recorded-stages.md](archive/DESIGN-recorded-stages.md)
**Platform:** Android native, Kotlin + Jetpack Compose
**Device:** the user's daily phone · **Audio:** Bluetooth A2DP to car head unit
**Map data:** offline, precomputed regional files · **First cut:** Stroud + 30 mile radius

---

## 1. What this thing is

A live co-driver. No recce, no pre-authored notes, no stage files. The app:

1. Works out **where you are on the road network** (map matching)
2. Predicts **which way you are going** for the next ~1 km (most-probable path)
3. Reads the **geometry of the road ahead** and finds the bends
4. Converts each bend's radius into a **pacenote severity** and a **suggested speed**,
   using a model of **how you personally take corners**
5. **Says it out loud**, timed so you hear it before you need it
6. **Learns from the drive afterwards** and moves the model

Everything else in this document exists to serve those six steps.

### Set expectations correctly

This will not match a human co-driver's recce notes, and it should not be sold to
yourself as if it will. OpenStreetMap road geometry is digitised from aerial imagery and
GPS traces; on major roads it is excellent, on rural lanes it is sometimes coarse enough
that a real hairpin reads as a gentle curve. What this app can genuinely be is a very
good **spirited-road-driving co-pilot** — the thing that tells you a decreasing-radius
third-gear left is coming over the next crest on a road you have never driven, at a speed
that matches how *you* actually drive. That is a worthwhile product. It is not a
competition pacenote system.

**Prior art, so you know the technique works:** openpilot derives turn-speed control from
OSM curvature; Adam Franco's `curvature` project ranks roads by exactly this maths;
production cars (BMW, Ford) use map curvature for predictive shifting and cornering
lights. The quality ceiling is set by the map data, not by the idea.

### Non-goals for v1
- **TSD / regularity timing** — dropped
- Cloud sync, accounts, sharing
- iOS
- Rendered basemap tiles (we need map *geometry*, not a pretty map — see §7)
- Camera / vision-based corner detection

---

## 2. The hard problems

### 2.1 Knowing which way you are going

This is the central problem, and it has no perfect solution. There is no stage file
telling us your route, so at every junction the app must guess your continuation. Guess
wrong and it calls corners from a road you are not on.

**Most-probable-path (MPP), walked forward from the current edge.** At each junction,
score every outgoing edge by:

- same `ref` or `name` as the current road — strong signal
- same or higher functional class (`highway=` tag)
- smallest deflection angle from current heading
- not a U-turn, not a service road or driveway

Produce a probability per continuation, prune below threshold, and keep the MPP as a
single path with a **confidence that decays at every junction**.

**And then be honest about it.** Confidence gating is a headline design principle:

> **A wrong note is worse than no note.** When confidence drops, the app goes quiet.

Concretely: announce corners only while path confidence exceeds a threshold. Approaching
an ambiguous junction, call up to the junction and then stop until the match resolves.
Never guess loudly.

### 2.2 Trusting the geometry

Raw OSM ways are polylines of hand-placed nodes. Naive curvature — the angle between
consecutive segments — is extremely noisy. Two nodes 60 m apart around a real bend badly
under-read it; three nodes clustered by a careless mapper manufacture a corner that does
not exist.

**Pipeline, run offline at data-build time (§3.3):**

1. **Smooth** the way geometry with a cubic B-spline / Chaikin pass, weighted by node
   spacing.
2. **Resample** to uniform ~5 m spacing.
3. **Compute curvature** κ at each point via the circumscribed circle of (p−1, p, p+1);
   radius `R = 1/κ`.
4. **Segment into corners** — runs of consecutive points where `R` is below the
   straight-road threshold and the sign of κ is consistent. Merge runs separated by short
   straights; split where the sign flips.
5. **Score confidence** per corner from original node density and geometry
   self-consistency. Low-confidence corners get suppressed or softened at runtime rather
   than called with false precision.

Store the result. Never do spline fitting on the phone in real time.

### 2.3 Turning a radius into a note

**Severity** comes from minimum radius. Defaults, user-adjustable:

| Min radius | Note |
|---|---|
| < 12 m | hairpin |
| 12–25 m | 1 |
| 25–40 m | 2 |
| 40–70 m | 3 |
| 70–120 m | 4 |
| 120–200 m | 5 |
| 200–400 m | 6 |
| > 400 m | flat — not called |

**Modifiers** come from comparing points within and between corners:

- `tightens` / `opens` — radius trend across the corner
- `long` — arc length above threshold
- `into` — gap to the next corner below threshold
- link distance — straight-line gap to the next called corner

### 2.4 Turning a radius into *your* speed

The naive version is a single constant: `vTarget = sqrt(aLat × R)`. That is wrong in two
ways. It ignores that drivers use different lateral g at different radii — typically less
in a tight first-gear hairpin and less again in a fast sweeper where vision and nerve bind
before grip does — and it ignores that your `aLat` is not the same as anyone else's.

**So the model is a learned curve, not a constant.** Store a target lateral acceleration
**per severity band** — reusing the §2.3 bands, so the model is interpretable:

```
vTarget(corner) = sqrt( aLatFor(band) × pushFactor × conditionsFactor × R )
                  clamped by tagged speed limit, user maximum, and the optional cap
```

- `aLatFor(band)` — learned from your own driving (§2.5)
- `pushFactor` — 0.85…1.15, moved by post-drive feedback
- `conditionsFactor` — 1.0 dry, 0.8 wet; a manual toggle, since we cannot detect it
- **optional cap** — an absolute ceiling on effective lateral g. **Off by default**, at
  your decision (§2.5). The mechanism stays in the code, so it can be switched on later
  without a rewrite

**Cold start:** no observations yet, so seed every band at a spirited **0.5 g** and blend
toward the learned value as samples accumulate:

```
aLatFor(band) = (n × learned + k × seed) / (n + k)          k ≈ 20 samples
```

This means it is useful on day one and gets personal over a few drives, without one
unusual corner yanking the model around.

**Braking point** — the most useful output, and the thing plain curve-warning apps get
wrong:

```
brakingDistance = (v² − vTarget²) / (2 × aBrake)      only when v > vTarget
triggerPoint    = cornerEntry − brakingDistance − (v × reactionSeconds)
```

The call arrives when you need to *act*, not at a fixed distance.

### 2.5 Learning how you drive

Every corner you go through is a labelled data point. From the map we know the minimum
radius `R`; from GPS we know your minimum speed through it. So:

```
aLatObserved = vMin² / R
```

Collect those, per severity band, and the driver profile falls out.

**Which observations count.** Most corners are worthless as training data, so filter hard:

- map confidence for that corner above threshold
- path confidence was high — we know it was really the road you were on
- **not constrained** — you must have been the limiting factor, not a car in front, not a
  junction, not a village. Heuristic: reject corners where speed was flat across entry and
  apex (following), where you stopped shortly after, or where `vMin` is far below the band
  median for no geometric reason.
- conditions tag is set and matches the profile being updated

**Explicit calibration (the Calibrate button).** Press it on a good road. At the end of the
drive the app takes the corner sequence and finds where you actually started driving
properly, then throws away everything before that:

> Compute `aLatObserved` per corner in sequence. Take a rolling median over a 5-corner
> window. Find the first window whose median reaches 80% of the session's p90 window
> median. That index is the **onset**; discard all corners before it.

This is deliberately simple and explainable rather than a clever changepoint algorithm —
you need to be able to look at the replay screen, see the marked onset, and agree with it.
If you don't, the parameters are tunable and the whole thing re-runs from the log.

From the surviving corners, per band, set the learned target to the **p80 of observed
lateral g** — not the mean (that is your average corner, and you want the good ones) and
not the max (that is one moment you got away with).

**Post-drive feedback.** When a drive ends, one sheet, three buttons: **Easy · Good ·
Hard.** The question is *how hard did that feel*, so:

| Answer | Meaning | Effect |
|---|---|---|
| **Easy** | You had margin in hand | `pushFactor` +4% |
| **Good** | Calibrated | no change; confirms the fit |
| **Hard** | It was pushing you | `pushFactor` −5% |

Down-adjustment is larger than up-adjustment on purpose — the model should get
enthusiastic slowly and back off quickly.

**Guardrails.** An app that raises its suggested speeds because you drove faster, which
then encourages you to drive faster, is a positive feedback loop. You have decided against
an absolute ceiling, so the loop is built without one. That is your call, and it makes the
remaining brakes load-bearing rather than belt-and-braces:

- **Per-session ratchet limit** — a single drive can move any band by at most ~5%. This is
  now the primary constraint on drift, so it is not user-adjustable.
- **Asymmetric feedback** — `pushFactor` rises 4% and falls 5%, clamped to 0.85…1.15.
- **Hard sample filtering** (above) — the model only learns from corners where you were
  demonstrably the limiting factor.
- **Wet scales the multiplier**, rather than learning a separate wet profile from three
  wet drives.
- **The profile is always visible and always resettable**, per band, in one tap. With no
  ceiling in place this is the real safety valve, so the Profile screen shows learned
  lateral g per band, sample counts, and the change from last drive — glanceable enough
  that drift is obvious before it is large.
- **Optional cap, off by default.** The clamp exists in code and can be switched on with a
  value at any time; nothing needs rewriting if you later decide you want one.

---

## 3. Architecture

Gradle multi-module. The rule: **`:core:*` logic is pure Kotlin, no Android dependencies,
driven by injected interfaces.**

```
:app                  navigation, DI wiring, theme
:core:model           pure domain types (Fix, MatchedPosition, Corner, Horizon, Profile…)
:core:geo             haversine, polyline, spline, curvature, snapping
:core:mapdata         road-graph store (SQLite), region download/management, spatial queries
:core:matcher         map matching — GPS → (edge, offset, direction, confidence)
:core:horizon         most-probable-path walk, corner extraction, confidence decay
:core:advisor         severity, speed, braking point, trigger scheduling
:core:profile         corner observations, onset detection, per-band learning, feedback
:core:audio           vocabulary pre-render, clip cache, concatenation, A2DP keep-alive
:feature:drive        the live HUD
:feature:profile      driver profile screen, calibration review, post-drive feedback
:feature:regions      map region download and management
:feature:replay       run log browser and replay scrubber
:tools:mapbuild       desktop/CI tool — OSM .pbf → app SQLite (§3.3)
```

### 3.1 The decision that makes this project tractable

```kotlin
interface FixSource { val fixes: Flow<Fix> }
```

`FusedFixSource` for the real GPS, `ReplayFixSource` for recorded run logs. With a pure,
clock-injected pipeline, a real drive replays deterministically in a unit test.

This matters more now than it did in the previous draft. You will spend most of this
project tuning numeric knobs — severity thresholds, MPP scoring weights, confidence
cutoffs, onset detection, the learning percentile — and every one of them can only
honestly be evaluated as "how did that feel on that road." **You need to re-run *that
road* on demand, at your desk, in a second.** Build the replay harness in Phase 0.

The learning system makes this sharper still: the driver profile must be **recomputable
from stored observations**. Change the percentile from p80 to p75 and you should be able
to re-derive the entire profile from history without driving anything.

`RunLogger` writes every fix, match, horizon, utterance and corner observation to disk.
Non-negotiable — it is the replay input, the training set, and the only way to answer
"why did it say that?"

### 3.2 Runtime pipeline

```
FusedLocationProvider
   → FixValidator      drop accuracy > 25 m, implausible jumps
   → MapMatcher        candidates within 30 m, scored by distance + heading
                       + transition plausibility; short-window Viterbi over top-K
                       ⇒ (edgeId, offsetAlongEdge, direction, confidence)
   → MotionPredictor   dead-reckon between fixes; blend on re-anchor (~300 ms)
   → HorizonBuilder    walk MPP forward ~1 km, decay confidence at each junction
   → CornerExtractor   read precomputed corners along the path within the horizon
   → Advisor           severity + vTarget(profile) + braking point ⇒ trigger distance
   → NoteComposer      severity + modifiers + link distance ⇒ clip sequence
   → { AudioEngine, HudState, RunLogger, ObservationCollector }
```

`ObservationCollector` runs *behind* the car — as each corner is exited, it records what
you actually did (§2.5). It never influences the current drive, only the profile.

Recomputing the horizon on every fix is wasteful: rebuild on **edge change or significant
heading change**, and otherwise just advance the offset.

### 3.3 Map data: the build tool

`:tools:mapbuild` is a desktop/CI JVM tool, not shipped in the app. Input: a Geofabrik
`.osm.pbf` extract, clipped to the area of interest.

**First cut — Stroud, 30 mile radius.** Bounding box approximately
`-2.92, 51.31, -1.52, 52.18` — 30 mi ≈ 48 km, which is ~0.44° of latitude and ~0.70° of
longitude at 51.7° N. That covers the Cotswold escarpment, the Forest of Dean, the Wye
valley and the roads down towards Bath: steep, technical, and varied enough to exercise
every band of the severity table. Clip it out of the England extract:

```
osmium extract --bbox -2.92,51.31,-1.52,52.18 \
    england-latest.osm.pbf -o stroud-30mi.osm.pbf
```

1. Filter to drivable ways:
   `highway=motorway|trunk|primary|secondary|tertiary|unclassified|residential` plus
   `_link` variants. Exclude `service`, `track`, `footway`, `path`.
2. Build a graph — junction nodes, edges carrying geometry and tags (`name`, `ref`,
   `highway`, `maxspeed`, `oneway`).
3. Run the §2.2 smoothing → resampling → curvature → segmentation → confidence pipeline
   over every edge.
4. Emit a **spatially tiled SQLite file**, indexed by geohash/quadkey cell, containing
   edges, junction topology, and precomputed corner records.

Doing the expensive geometry work here rather than on-device is what makes real-time
operation possible on a phone that is simultaneously running the screen at full
brightness.

**Size:** the Stroud cut should come out at a few MB — small enough to rebuild in seconds,
which is exactly why we start there. Great Britain in full, filtered to drivable geometry
with corners precomputed, should land around 100–200 MB; ship it as sub-region splits so
the first download is never the whole country.

---

## 4. Data model

### 4.1 Shipped map data (read-only, per region)

```
Region(id, name, bbox, osmSnapshotDate, schemaVersion, sizeBytes)

Edge(id, geohashCell, fromNodeId, toNodeId,
     geometry,          // resampled polyline, ~5 m spacing, packed
     lengthM, name?, ref?, highwayClass, maxspeedKph?, oneway)

Junction(nodeId, geohashCell, lat, lon, edgeIds)

Corner(id, edgeId,
       startOffsetM, apexOffsetM, endOffsetM,
       direction,                   // LEFT | RIGHT
       minRadiusM, entryRadiusM, exitRadiusM,
       arcLengthM,
       confidence)                  // 0..1 from node density and consistency
```

### 4.2 Driver profile and learning

```
DriverProfile(id, updatedAtMs,
              aLatByBand,           // Map<SeverityBand, Float> — the learned model
              sampleCountByBand,
              pushFactor,           // 0.85..1.15
              capALat?,             // optional ceiling — null (off) by default
              seedALat)             // 0.5 g spirited cold start

CornerObservation(id, runId, cornerId, tMs,
                  band, minRadiusM,
                  vEntryMps, vMinMps, vExitMps,
                  aLatObserved,     // vMin² / R
                  mapConfidence, pathConfidence,
                  wasConstrained,   // heuristic — following, junction, village
                  conditions)       // DRY | WET

CalibrationSession(id, runId, onsetCornerIndex, cornersUsed,
                   perBandResult, acceptedAtMs)

DriveFeedback(runId, answer, appliedDelta, answeredAtMs)   // EASY | GOOD | HARD
```

Observations are kept indefinitely and the profile is **derived**, never edited directly.
Changing a learning parameter re-derives from history.

### 4.3 Runtime state (not persisted)

```
MatchedPosition(edgeId, offsetM, direction, confidence, timestampMs)

Horizon(pathEdgeIds, totalLengthM, confidenceAtEnd, corners)

HorizonCorner(corner, distanceAheadM, pathConfidence,
              severity, vTargetMps, brakingPointM, triggerDistanceM, modifiers)
```

### 4.4 Run logs

```
Run(id, startedAtMs, endedAtMs, appVersion, deviceModel, regionId,
    profileSnapshot, conditions, wasCalibrationRun)

RunFix(runId, tMs, lat, lon, speedMps, bearingDeg, accuracyM, wasPredicted)
RunMatch(runId, tMs, edgeId, offsetM, confidence)

RunEvent(runId, tMs, type, payload)
   // NOTE_SPOKEN, NOTE_SUPPRESSED_LOW_CONFIDENCE, NOTE_CHAINED,
   // HORIZON_REBUILT, MPP_AMBIGUOUS, MATCH_LOST, GPS_LOST, REGION_MISSING,
   // OBSERVATION_RECORDED, OBSERVATION_REJECTED
```

`NOTE_SUPPRESSED_LOW_CONFIDENCE` and `OBSERVATION_REJECTED` matter as much as their
positive counterparts — most tuning is about whether the app stayed quiet, and whether it
learned from the right corners.

---

## 5. Screen flow

```
Home ─┬─ ▶ DRIVE                    ← the app; live HUD, landscape
      │      └─ on finish → Easy / Good / Hard sheet
      ├─ Profile ───┬─ learned lateral g per band, sample counts, push factor
      │             ├─ calibration review (onset marked on the drive)
      │             └─ reset band / reset all
      ├─ Regions ──── download / update / delete map regions
      ├─ Run Logs ─── replay scrubber, per-corner "why did it say that?"
      └─ Settings ──┬─ Severity table (radius → note thresholds)
                    ├─ Voice (engine, rate, pitch) → re-renders vocabulary
                    ├─ Verbosity (everything ← default / 4-and-tighter / cautions only)
                    ├─ Conditions (dry / wet)
                    ├─ Audio latency calibration
                    └─ Speed-limit clamp, optional lateral-g cap (off by default)
```

There is no authoring surface at all. Almost all the work sits behind DRIVE and Profile.

### 5.1 HUD design rules

Read at speed, in daylight, by someone being shaken. Therefore:

- **Landscape, dark, maximum contrast.** No gradients, no thin fonts.
- **The current note is the biggest thing on screen** — 96 sp+, next note below at ~48 sp.
- **Suggested speed and distance-to-corner** beside it, clearly secondary.
- **One thumb-reachable Calibrate button**, and nothing else tappable during a run except
  mute and pause. Accidental taps are guaranteed; make them harmless.
- **Confidence is always visible.** The user must be able to tell at a glance whether
  silence means "straight road" or "I have no idea where you are."
- **Colour is a signal, not decoration.** Caution red, low-confidence dimmed — never
  colour alone, always paired with position or size.
- `FLAG_KEEP_SCREEN_ON`, plus optional force-max-brightness.

### 5.2 Saying it in time, over car Bluetooth

**Latency.** A2DP to a head unit adds 100–250 ms, and most head units let the stream idle
during silence then swallow the first 200–500 ms of the next clip — "left four" arrives as
"…four".

- **Keep the stream alive** with continuous near-silent audio between notes. This is the
  fix for the swallowed syllable.
- **Prepend a short silent lead-in** to every clip as a second line of defence.
- **Calibrate** latency once in settings; fold it into the trigger offset.

**Synthesis.** Notes are generated live, so there is no stage to pre-render — but the
vocabulary is closed and tiny, under 100 utterances:

```
"left one" … "left six", "right one" … "right six"       24
hairpin / square / kink × left / right                    6
tightens, opens, long, into, caution, junction, brake     ~8
50, 100, 150, 200, 250, 300, 400, 500, 600, 800, 1000     11
```

Render the whole vocabulary once via `TextToSpeech.synthesizeToFile()` at first run or
voice change, measure each clip's duration, and **concatenate clips at runtime**.
Trigger-to-sound becomes effectively zero.

**Timing** anchors the *end* of the phrase, since a long note takes longer to say:

```
startDistance = triggerPoint − speed × (clipDurationSeconds + a2dpLatencySeconds)
```

**Chaining.** If the next note would start before the current finishes, merge into one
burst and drop the link distance rather than falling behind. Never queue up a lag.

Chaining carries much more load now that the default verbosity is **call everything**,
down to a 6. On a dense Cotswold B-road a corner every 80 m at 90 km/h leaves ~3 s per
note, which a full "left four tightens, one hundred" nearly fills. Burst compression is
therefore a Phase 3 requirement, not a refinement: when a burst would overrun, drop link
distances first, then modifiers, and keep the corner calls. Losing "one hundred" is
survivable; arriving at the corner still talking about the last one is not.

---

## 6. Android platform specifics

| Concern | Decision |
|---|---|
| Location | `FusedLocationProviderClient`, `PRIORITY_HIGH_ACCURACY`, 200 ms requested |
| Background | Foreground service, `foregroundServiceType="location"`, `FOREGROUND_SERVICE_LOCATION` (Android 14+) |
| Permissions | `ACCESS_FINE_LOCATION`; `POST_NOTIFICATIONS` for the service notification |
| Audio focus | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` — ducks the radio, coexists with nav |
| Bluetooth | A2DP to head unit; keep-alive silence stream; latency calibration |
| Interruptions | It's your daily phone — prompt to enable Do Not Disturb when a drive starts |
| Map storage | Per-region SQLite in `filesDir/regions/`; resumable WorkManager downloads |
| DI | Hilt |
| Min SDK | 26; target the latest |
| Testing | JUnit + Turbine over `:core:*`; replay-log golden tests |

**Thermal and battery:** full brightness, 1 Hz GPS, continuous graph queries, and a phone
in the sun on a windscreen is a throttling scenario — and it is your daily phone, so it
matters more than it would on a dedicated unit. Budget for a dimmed idle mode between
corners, cache the horizon aggressively, and get airflow onto the device.

---

## 7. Deliberate omission: no rendered basemap

We need road **geometry**, which §3.3 ships. We do not need road **tiles**. On the DRIVE
screen a moving map is actively unhelpful — you want the note, huge — and MapLibre plus
offline tiles would add days of work and hundreds of megabytes.

Where a spatial view genuinely helps — the replay scrubber, and reviewing where
calibration decided you started driving properly — draw the matched edges and horizon as a
self-scaling polyline on a `Canvas`.

Revisit in v2 if real use proves otherwise.

---

## 8. Build order

Phase 3 is where you find out whether the idea works. Get there fast, with real data.

- **Phase 0 — Skeleton and harness.** Modules, Hilt, `FixSource`, `RunLogger`,
  `ReplayFixSource`. Record raw GPS on any drive and replay it.
  *Exit: a log replays deterministically in a unit test.*
- **Phase 1 — Map data.** `:tools:mapbuild` end to end: GB extract → smoothing →
  curvature → segmentation → tiled SQLite. Start with one county-sized cut covering roads
  you know well.
  *Exit: query "corners on this road" and the answers match your memory of it.*
- **Phase 2 — Matching and horizon.** Map matcher, MPP walk, confidence decay, replayed
  over a real drive.
  *Exit: matched position and predicted path are right for the whole drive, and confidence
  drops where it should.*
- **Phase 3 — Advisor and voice.** Severity table, speed and braking maths against the
  seeded 0.5 g profile, vocabulary pre-render, concatenation, A2DP keep-alive, HUD.
  *Exit: a replayed drive produces a plausible spoken track. **Go/no-go for the whole
  concept.***
- **Phase 4 — First real drives and tuning.** Collect logs; tune severity thresholds, MPP
  weights and confidence cutoffs against replay.
  *Exit: it feels right on a road you know.*
- **Phase 5 — Learning.** `ObservationCollector`, onset detection, per-band p80 learning,
  Calibrate button, post-drive Easy/Good/Hard sheet, Profile screen, guardrails.
  *Exit: after three good drives the suggested speeds are recognisably yours.*
- **Phase 6 — Regions and polish.** Region download/update UI, verbosity, wet/dry,
  degraded-GPS and missing-region handling, thermal mode, DND prompt.

Learning is Phase 5 rather than earlier on purpose: it needs a working advisor to produce
observations, and its own tuning is only meaningful once the geometry underneath is
trusted. The seeded 0.5 g spirited default carries Phases 3–4 on its own.

---

## 9. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| OSM geometry too coarse on rural roads → wrong severities | **High** | Per-corner confidence from node density; suppress or soften low-confidence calls; §1 expectation-setting |
| Wrong most-probable path at junctions → calls the wrong road | **High** | Confidence decay + hard gating; go quiet rather than guess (§2.1) |
| The concept doesn't feel good enough to use | **High** | Phase 3 is an explicit go/no-go on replayed real drives, before any polish |
| Learning loop ratchets speeds upward over time | **High** | No absolute ceiling by your decision, so the brakes are the per-session ratchet limit, asymmetric feedback, sample filtering, and a visible/resettable profile (§2.5) |
| Learning from corners where you were following traffic | Medium | `wasConstrained` filtering; reject flat-speed and post-stop corners |
| Call-everything verbosity is fatiguing → you stop listening | Medium | Burst compression (§5.2); verbosity is one setting away if Phase 4 shows it is too much |
| A2DP swallows the start of notes | Medium | Keep-alive silence stream, clip lead-in, latency calibration (§5.2) |
| GPS lag at speed | Medium | Dead reckoning + blended re-anchor + replay-based tuning |
| Thermal throttling on a daily phone | Medium | Dimmed idle mode, horizon caching, airflow |
| GB map file size / download UX | Low | Sub-region splits, resumable WorkManager downloads |

---

## 10. Safety and licensing notes

- **The speed output is advisory.** It is computed from third-party map geometry that can
  be coarse, out of date, or wrong, and it knows nothing about camber, surface, weather,
  traffic, or what is around the corner. It must never override what the driver can see,
  and the UI should say so rather than implying authority it does not have.
- **The learning loop has no absolute ceiling**, at your decision. What still bounds it is
  the per-session ratchet limit, the asymmetric Easy/Good/Hard response, and the fact that
  the profile is visible and resettable at any time. That makes the Profile screen worth
  glancing at periodically, rather than only after a drive that felt wrong — the failure
  mode of an uncapped loop is slow drift, which is exactly the kind you do not notice from
  inside it.
- **OpenStreetMap is ODbL.** Shipping derived map data carries attribution obligations, and
  share-alike obligations on the derived database if distributed. Worth ten minutes of
  reading before Phase 1 rather than after.

---

## 11. Decisions log

| Decision | Choice |
|---|---|
| Note source | Live prediction from map geometry — no recce, no authored notes |
| Platform | Android native, Kotlin + Compose, daily phone |
| Audio out | Bluetooth A2DP to car head unit |
| Map data | Offline, precomputed regional SQLite |
| First cut | Stroud, 30 mile radius (`-2.92, 51.31, -1.52, 52.18`) |
| Driving style | Learned per-band profile, seeded spirited at 0.5 g |
| Calibration | Onset detection + p80 per band; Easy/Good/Hard after each drive |
| Feedback sign | "Hard" = it was pushing me → back off |
| Lateral-g ceiling | **None.** Mechanism retained, off by default |
| Verbosity default | Call everything, down to a 6 |
| TSD / regularity | Dropped |

### Still open

Nothing blocking Phase 0. Two things to settle before their phase:

1. **Wet/dry handling** (Phase 6) — a manual toggle is specced. A rain-API lookup or a
   "it's wet" quick action from the notification would both be less friction; worth
   deciding once you have driven with it.
2. **Sub-region strategy for the full GB build** (Phase 6) — how to split, and whether
   regions auto-update when the OSM snapshot moves.

---

## 12. v1.1 feature addendum (agreed 2026-08-26)

### 12.1 OBD-II via ELM327 (Bluetooth)
All four feeds agreed. New module `:core:obd` — ELM327 protocol over BT SPP/BLE, polling
PIDs: vehicle speed (0x0D), RPM (0x0C), throttle (0x11), coolant temp (0x05), plus
battery voltage via ATRV. Graceful absence: everything works without the dongle.
- **Speed fusion** — OBD wheel speed preferred over GPS speed for dead reckoning and
  braking maths (faster update, no multipath).
- **Gear inference** — learn gear ratios from RPM/speed clustering; then notes gain an
  optional gear suffix: "left three, second".
- **Health watch** — coolant/voltage thresholds → calm spoken warning, never mid-note.
- **Telemetry logging** — RPM/throttle/speed into RunFix rows; graphs in replay.
- Throttle position sharpens `wasConstrained`: low throttle + low aLat = not pushing.

### 12.2 Drive report + self-leaderboard
Post-drive report: route trace, corners by severity, smoothness score (entry-speed vs
suggestion RMS), profile delta, and per-road personal bests ("best run: Slad Valley
road"). Roads identified by stable edge-chain hash so renames don't break history.

### 12.3 Road finder (twistiness + quiet)
Rank roads in-region by corner density × severity (precomputed — nearly free). Traffic
avoidance is learned, not live: `wasConstrained` observations accumulate into a per-road
"traffic encountered" score by time-of-day bucket, blended with road-class heuristics
(unclassified/tertiary quieter than A-roads). Browsable list + canvas preview.

### 12.4 Safety net
- **Incident detection** (opt-in, off by default): >4 g longitudinal spike followed by
  a stop → full-screen "Are you OK?" with 60 s countdown → SMS with location to a chosen
  contact if unanswered.
- **Hazard callouts** from OSM tags: junction, crossing, ford, cattle_grid,
  narrow bridge, gate, level crossing. Spoken like cautions, shown on HUD.

### 12.5 Voice: AI-generated British male pack
Vocabulary pre-generated at build time on desktop via neural TTS (edge-tts,
`en-GB-RyanNeural`), bundled as APK assets. Two intonation sets:
- **normal** — measured pace for severities 4–6, links, modifiers
- **urgent** — faster, clipped, for hairpin/1/2, "brake", cautions
Runtime stays pure playback + concatenation. `:tools:voicebuild` script generates and
normalises clips (loudness-matched, trimmed, 50 ms lead-in).

### 12.6 HUD: tilted live map + g-meter
The §7 "no rendered map" decision is superseded — but still **no tiles**: a custom
renderer draws our own road geometry (already 5 m resolution) as a dark roads-only
scene, camera 35° tilt, heading-up, following the car. Predicted route colour-coded:
confidence (bright→dim) and corner severity (green→amber→red segments). Note text
remains the dominant element; map sits behind/beside it. Plus live lateral-g dot and
current vs suggested speed.

### 12.7 Export
- **Video telemetry overlay** — render transparent-background MP4/WebM (speed, g, gear,
  corner calls as they fired) from the run log, sized for laying over GoPro/dashcam
  footage.
- **Shareable drive card** — single PNG per drive: route trace, stats, best corner.

### 12.8 Revised module additions
```
:core:obd             ELM327 client, PID polling, gear inference, speed fusion
:core:report          drive scoring, road hashing, personal bests, traffic score
:feature:roadfinder   twisty-and-quiet road browser
:feature:export       overlay video renderer, drive card renderer
:tools:voicebuild     desktop script — TTS vocabulary generation + normalisation
```

## 13. v0.6: mount self-alignment + camber (agreed 2026-08-26)

**MountAlignment** (`core/imu/`): finds car-forward in phone coordinates with no
calibration step. During firm accel/brake events (|dv/dt| ≥ 1.5 m/s² from GPS/OBD),
the horizontal accelerometer swing direction is accumulated (sign-flipped when
braking). Aligned when ≥25 events agree with coherence ≥0.75; a knocked mount loses
coherence and silently re-learns. The car body's DOWN axis is a slow gravity EMA
(~tens of seconds), so brief corner leans don't move the baseline — camber measured
against instantaneous gravity would read zero by construction.

**CamberEstimator**: gravity's lean along the car-left axis, sampled only when
|lateral accel| < 1.2 m/s² (mid-corner readings are camber+body-roll and are simply
skipped — camber belongs to the road, not the moment). Convention: positive =
road leans car-LEFT = helps left-handers.

**Effect**: camber EMA stored per 25 m knowledge bucket (≥5 samples before trusted).
Corners with ≥2° adverse camber gain an OFF CAMBER modifier (spoken + HUD) and a
speed trim of 3%/degree, floored at 0.85×.
