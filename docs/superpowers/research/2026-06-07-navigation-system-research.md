# Navigation-System Research — Gixxer SF 150 BLE App

Date: 2026-06-07
Scope: the full a531 navigation/cluster-display path in `android/app/.../gixxerbridge` — frame format, maneuver-ID translation, the (parked) Google Maps pipeline, the idle-tile producers, the BLE mux/write path, verification tooling, and the gaps that separate "ambient info display" from "turn-by-turn navigation."

Honesty note (per the project no-assumptions rule): claims below are tagged **[VERIFIED]**, **[REFUTED]**, or **[UNCERTAIN]** against the evidence ledger. On-cluster rendering of anything is treated as UNVERIFIED unless an on-bike capture exists. "Code-fact" means provable from source without the bike; it does NOT mean the bike behaves accordingly.

---

## 1. System overview + end-to-end data flow

The app drives the bike's TFT cluster by writing rigid 30-byte `a531` BLE frames to GATT char `0xFFF1`. There is **no free-text channel** — every visible value is squeezed into fixed-width ASCII slots inside that frame. The cluster is the renderer; the phone is the only intelligence.

### Frame envelope (`Frame.kt`, `NavFrame.kt`) — [VERIFIED]
- `byte0 = 0xA5` header, `byte1 = type` (ASCII `'1'`..`'7'`; NAV = `0x31`), `byte28 = checksum = sum(bytes[1..27]) & 0xFF`, `byte29 = 0x7F` terminator. `FRAME_LEN = 30`.
- a531 nav layout: `byte2` maneuver byte; `bytes4-7` distNext (4 ASCII); `byte8` distNextUnit; `bytes9-14` eta (6 ASCII); `bytes18-21` distTotal (4 ASCII); `byte22` distTotalUnit; `byte23` status; `byte24` continueFlag; `bytes 3,15-17,25-27` = `0xFF` padding the cluster ignores.

### BLE surface (`SuzukiGatt.kt`) — [VERIFIED, Phase 1 cross-checked against real UUIDs not Wireshark labels]
- One service `0xFFF0`; write `0xFFF1` (phone→bike); notify `0xFFF2` (bike→phone); CCC `0x2902`. Bluetooth-only; no SIM/cloud path for nav.

### Two paths, only one alive today

**INTENDED Maps path — PARKED 2026-06-04 (does not run):**
Google Maps posts a turn-by-turn notification → `NotificationCaptureService.onNotificationPosted` → `NotificationDispatcher.onPosted`. The `handleMaps` branch is **commented out**, so the chain below is dormant:
`GoogleMapsParser.parse` (reads `Notification.extras`: title=instruction, subText=eta/total, progress/progressMax meters, largeIcon turn-arrow bitmap; RemoteViews tree-walk fallback for old Maps) → builds distNext/eta/distTotal via `normalizeDistance`/`normalizeEta`, and a maneuver via `ManeuverClassifier.classify(bitmap, instruction)` → **Stage 1** `MapplsIdGuesser.fromText` (or bitmap aHash) yields a Mappls ID 0..75 → **Stage 2** `ManeuverMap.mapplsIdToClusterByte` (verbatim OEM `A0.C()` table) yields cluster byte 1..52 → `ParsedNavData.toNavFrame()` (hardcodes status='1', continueFlag='1') → `MapsNavSource.update()` sets a `StateFlow<NavFrame?>` armed with a 60s stale watchdog.

**ACTUAL path today — idle tiles only:**
`BikeBridgeService` builds an `idleProducer` flow ticking at 1Hz, rotating slots `[CLOCK, optional NOW_PLAYING, optional RANGE]` at 5s/slot (`CYCLE_SECONDS=5`). `NavMux(maps = flowOf(null), idle = idleProducer)` combines `maps ?: idle` then `distinctUntilChanged()`; since maps is constant-null, **idle always wins**. The nav-producer collector encodes `navMux.frame` and `frameWriter.enqueue(NAV, bytes)` only when the link is Ready (else emits a `nav-preview` TX event). On fresh Ready: URGENT identity (a536) + one-shot URGENT `WelcomeFrame`.

**Write path:** `FrameWriter` priority queue URGENT > HEARTBEAT(a533) > NAV(a531); NAV is content-deduped and capacity-4 DROP_OLDEST. Drain loop `take()` → wait ≤3s for Ready else drop → `BleClient.write()`: 30 bytes to `0xFFF1` via `WRITE_TYPE_DEFAULT` (write-with-response), serialized by `writeMutex`, retry-on-BUSY ~2s.

**Dev override:** `ManeuverSweepScreen` → `AppGraph.sendFrame` → URGENT "composer" → bypasses NavMux, bursting cluster bytes 1..53 at 1Hz for 5s each. This is the on-bike empirical surface for byte→glyph.

So today the cluster is an **ambient-info display**, not a navigation system. Any evaluation of turn rendering is evaluating dead code.

---

## 2. Proven vs ASSUMED facts (with verification verdicts)

### Proven (code-fact and/or capture-confirmed)
- **[VERIFIED]** a531 is a rigid 30-byte frame, fixed-width ASCII slots, no free-text channel (`NavFrame.kt:40-64`, OEM `A0.D()/A0.C()`).
- **[VERIFIED]** Frame envelope header/type/checksum/terminator (`Frame.kt`).
- **[VERIFIED]** BLE surface = service `0xFFF0` / write `0xFFF1` / notify `0xFFF2` (`SuzukiGatt.kt`); real UUIDs, not dissector labels.
- **[VERIFIED]** Mappls maneuver ID ≠ cluster byte. OEM `A0.C()` (`A0.java:458`) translates Mappls `aVar.f` → `e0` before frame assembly; ported into `ManeuverMap.mapplsIdToClusterByte`. *Caveat:* the e0→byte2 hop crosses a call edge jadx could not recover, so it is a strong inference, not a single traced assignment; and that the cluster ROM keys glyphs by the translated byte (vs the raw Mappls ID) is **UNVERIFIED on the bike**.
- **[VERIFIED]** Status byte (`byte23`) gates rendering: `'1'/'3'/'5'` = good (real arrow); `'0'/'2'/'4'/'6'` = degraded (OEM forces `byte2 → 0x2e`, "Searching for network"). Source-derived from `A0.D()` plus WITH-SIM vs NO-SIM captures. *Caveat:* the literal "Searching for network" text is empirically observed only for the `'0'` case; `'2'/'4'/'6'` degraded text is source-branch-derived (bike-only to confirm).
- **[VERIFIED]** Cluster byte 8 renders a literal up-arrow on-bike (confirmed 2026-06-06), disproving the earlier "friendly default arrow" assumption. WelcomeFrame therefore uses `NO_MANEUVER_BYTE(46)`.
- **[VERIFIED]** Maps notification-scrape nav pipeline is PARKED (`NotificationDispatcher` Maps branch commented out; `NavMux` maps slot = `flowOf(null)`).
- **[VERIFIED]** FrameWriter priority + NAV content-dedup + DROP_OLDEST; nav writes only when Ready.
- **[VERIFIED]** Writes use `WRITE_TYPE_DEFAULT` (with response), serialized, retry-on-BUSY; handshake `requestMtu(65)` + 500ms before CCC.
- **[VERIFIED]** Coarse 6-bar fuel: a537 `byte24` ASCII `'1'`-`'6'` feeds the RANGE idle slot.
- **[VERIFIED]** `distTotalUnit` (byte22) is the app-internal field-reuse discriminator: `'C'`=idle clock, `'M'/'k'/'m'`=real nav, `'*'`(+distNextUnit `'@'`)=now-playing — keyed by `ClusterPreview.NavFrame.mode()`.
- **[VERIFIED]** Unit labels: `byte8`/`byte22` are `'K'`=km / `'M'`=metres. *Caveat:* the OEM's own `o0` (byte22) computation has a likely inverted copy-paste check (`A0.java:696`) — meaning unchanged, assignment logic suspect; the app does NOT reproduce the bug. Which convention the cluster firmware expects is bike-only.

### Refuted (was stated/assumed, evidence overturned it)
- **[REFUTED]** "`continueFlag='0'` (byte24) terminates navigation / cluster exits nav view." `NOTES.md:184` says this but it is **stale and retracted**. Ride pcap (`DISCOVERIES.md:1382-1384`) shows the OEM fires `cf='0'` 2-3 frames *before every BLE drop* and recants with `cf='1'` milliseconds later, including 6 frames paired with `status='1'` normal nav. `tools/protocol.py:239-246` documents it as a **phone-side BLE-graceful-disconnect / one-shot teardown hint, NOT "user terminated nav."** Treat any plan that sends `cf='0'` as a "clean nav-exit" as resting on a refuted premise; the exact cluster reaction to a lone `cf='0'` is bike-only.
- **[REFUTED]** "`DISTANCE_REGEX` captures km|mi|ft|m **and the number is used verbatim**." The regex captures the units, but the number is NOT verbatim: `ParsedNavData.kt:78` does `,`→`.`, then `normalizeDistance` zero-pads/clamps/reformats to a fixed 4-char string (`%04d`, `%02d.%s`), dropping fractions on overflow. Tests prove the transform (`"220 m"`→`"0220"`, `"1.2 km"`→`"01.2"`). Verbatim passthrough is structurally impossible given the 4-char slot.
- **[REFUTED]** "`'exit'` branch returns 75 for any 'exit'; roundabout always 72." There are THREE exit branches: a LEFT exit hits `MapplsIdGuesser.kt:30` → **73**, not 75; and a "roundabout … exit" string matches the earlier roundabout branch (`:29` → 72) and never reaches an exit branch. (Roundabout-always-72 within this function IS correct; the "any exit → 75" half is false.)

### Uncertain (assumed, not yet verified — must verify before relying)
- **[UNCERTAIN, bike-only]** Cluster renders arbitrary printable ASCII text in distNext/eta/distTotal as legible text — the entire idle-clock / now-playing / range / welcome creative-text approach. Only loose raw-ASCII acceptance was shown by `forge_display.py`; the specific layouts (clock/temp/`PLAYNG`/`RANGE`/`WELCOM`) are NOT confirmed.
- **[UNCERTAIN, bike-only]** `NO_MANEUVER_BYTE(46/0x2e)` renders blank rather than a literal `.` glyph (flagged hypothesis at `ManeuverMap.kt:25-28`). This is the mirror of the byte-8 mistake already disproven once.
- **[UNCERTAIN, bike-only]** Cluster bytes 1..52 map to distinct glyphs per `docs/cluster-byte-glyphs.md` — that table is unpopulated.
- **[UNCERTAIN]** Gixxer's stored `vehicle_name` is NOT in {e-ACCESS, Access-TFT, Burgman Street-TFT, Access} and BTID lacks `SBS51`, so it uses the default `A0.C()` branch. `vehicleModel` is hardcoded null; needs one frida hook to confirm. If wrong, Mappls IDs 58 and 74 translate to the wrong byte.
- **[UNCERTAIN]** Stage-1 `MapplsIdGuesser` text→ID accuracy (hand-rolled English keyword matcher; never measured against real Maps strings/locales).
- **[UNCERTAIN]** Bitmap aHash + Hamming tolerance 5 disambiguates Maps icons (never measured against real captures).
- **[UNCERTAIN, bike-only]** The cluster "nav-mode latch" minimum burst window — whether unchanging nav frames must be periodically re-sent to stay on-screen — is unmeasured.
- **[UNCERTAIN]** `RangeEstimator.FALLBACK_KM_PER_BAR (~50)` is anecdotal until ride history exists.

---

## 3. Findings by area

Effort: S = hours, M = a day-ish, L = multi-day / SDK integration. "file:line" gives the load-bearing evidence.

### A. Frame / rendering

**A1. RANGE km number renders 10×/100× too large — `"140"` → wire `"1400"`. [bug, S]**
What: `ClusterRangeFormatter.format` returns `km.toString()` unpadded (`ClusterRangeFormatter.kt:35`). `NavFrame.encode` writes distNext via `writeAsciiLeftPadZero` which — despite its name — **left-justifies and pads `'0'` on the RIGHT** (`FrameCodec.kt:92-99`, confirmed reading the body). So `"140"`→`"1400"`, `"95"`→`"9500"`. Only exact-4-digit values (≥1000 km, impossible here) and the `"----"` sentinel survive. Why: RANGE is 1 of only 3 live idle slots; this makes the headline number actively misleading. Feasibility: pure-JVM, `ClusterRangeFormatter.kt:35` + `FrameCodec.kt:92-99`; only leading-zero-vs-space cosmetics are bike-dependent, the 10× error is wire-fact. Risk: low.

**A2. distNext encoder name lies — `writeAsciiLeftPadZero` actually RIGHT-pads. [bug, S]**
What: the helper's KDoc says "right-justified … trailing bytes," but the body takes the FIRST `width` bytes and arraycopies to offset 0, padding `'0'` on the right (`FrameCodec.kt:92-99`). Today every `normalizeDistance` branch coincidentally returns exactly 4 chars so identity holds; a future/locale path returning `"1.2"` (3 chars) would be wire-encoded `"1.20"`. Why: a silent corruption landmine masked only by coincidence. Feasibility: rename to `writeAsciiRightPadZero` or assert width==4 in `normalizeDistance`. Risk: low.

**A3. distTotalUnit discriminator collision across the 3 live producers. [risk, S]**
What: the discriminator set is `{C, M/k/m, * (+@)}` (`NOTES.md ~435-443`, `ClusterPreview.mode()`), but RANGE sets `distTotalUnit=" "` (space, not in the set; `IdleClockGenerator.kt:124`) and WelcomeFrame reuses `"C"` with a greeting (not weather) in distNext (`WelcomeFrame.kt:79-82`). Phone-side preview will misclassify RANGE and may render Welcome as idle-clock. Why: byte22 is the chosen mode bit; inconsistent values undermine the preview and any per-layout cluster logic. Feasibility: tighten the contract / document every producer's byte22. On-cluster effect bike-only; the inconsistency is code-fact. Risk: low.

**A4. miles→`'K'` and feet→`'M'` folding preserves the unit letter but NOT the magnitude. [risk, S]**
What: `normalizeDistance` maps km|mi→`'K'`, m|ft→`'M'` with no `*1.609`/`*0.3048` conversion anywhere (`ParsedNavData.kt:81-85,88-107`; grep for conversion constants = 0 hits). So `"500 ft"`→`"0500" M` (shows 500 m, real ~150 m); `"0.3 mi"`→`"00.3" K`. Why: distance-to-turn is the most safety-relevant number; off by ~1.6× (mi) / ~3.3× (ft) for imperial riders. Feasibility: convert magnitude before formatting; pure-JVM, parked-path so not urgent. Risk: low (only bites on Maps revival in imperial locale).

**A5. ETA hardcoded 12h AM/PM; non-Latin locales emit `'?'` and fall back to wall-clock. [bug, M]**
What: both call sites pass `twelveHour=true` (`GoogleMapsParser.kt:80,185`); device 12/24h pref is never read. `encodeAscii` is US_ASCII → non-ASCII → `'?'` (`FrameCodec.kt:47`). Regexes are ASCII/English-only, so a localized clock string misses and `normalizeEta` silently returns `LocalTime.now()` (`ParsedNavData.kt:155-161`) — current time labeled as arrival. `status='1'` is hardcoded regardless, so the wrong number is presented as confident fact. Why: silent-wrong ETA on a safety-adjacent readout violates the project's honesty stance. Feasibility: read `DateFormat.is24HourFormat`, fail-closed (`----`) on parse miss; exact localized strings need a non-English capture. Risk: medium.

**A6. 24h ETA built as `"HHMM00"` (trailing zeros) but OEM/decoded spec says `"00HHMM"` (leading). [bug, S]**
What: `normalizeEta(twelveHour=false)` = `"%02d%02d00"` → `"173000"` (`ParsedNavData.kt:168`), but `NOTES.md:179`/`DISCOVERIES.md:594` decode the OEM 24h ETA as `"001730"`; the OEM space-overwrite of position 9 when `n0[0]=='0'` only makes sense for leading-pad. Why: latent 24h-locale garbage before Maps revival; isolated since idle clock is 12h. Feasibility: pure-JVM; *the exact wanted layout is decompiled, not captured — mark to-verify with a 24h-locale on-bike capture.* The app's form contradicting both project sources is a real inconsistency regardless. Risk: low.

### B. Maneuver-ID (Stage 1 / Stage 2)

**B1. byte2 ground-truth contradiction: raw Mappls ID vs OEM-translated byte. [risk, M — highest correctness leverage]**
What: the whole rework rests on `A0.C()` being a Mappls→cluster-byte translation applied before byte2 (`DISCOVERIES.md:2063-2093`, spec `design.md:14-26`). But an earlier same-day entry (`DISCOVERIES.md:2024-2030`) traces `C0897z.java:81 getIdentifier("step_"+bVar.h)` and concludes the OPPOSITE — "no secondary lookup table," raw Mappls int. `C0897z` is the phone nav-strip widget; `A0` is the BLE frame assembler — they could legitimately differ, OR one trace is misread. Today the code applies the translation (`GoogleMapsParser.kt:95,:187`); if `A0.C()` does NOT actually gate byte2, every translated turn is wrong in a NEW way. Why: root determinant of whether ANY arrow is correct. Feasibility: one ManeuverSweep A/B (raw Mappls 3 vs translated byte 4 for "turn right," photograph cluster) settles it. Risk: high — currently resolved only by reading decompiled bytecode.

**B2. Stage-2 `null` ("leave previous glyph") is collapsed to `DEFAULT_CLUSTER_BYTE=8` (a real arrow) at every caller. [gap, M]**
What: `mapplsIdToClusterByte` returns null for unmapped IDs (ferry 36, etc.), documented "keep last glyph" matching OEM (`ManeuverMap.kt:192-194,:256`). Both call sites do `?: DEFAULT_CLUSTER_BYTE` (`GoogleMapsParser.kt:95-96,:187-188`), and byte 8 is a confirmed literal up-arrow. So ferry/unmapped-ramp segments flip the cluster to "straight ahead." Why: silently reintroduces wrong arrows for the long tail in the exact pipeline the rework meant to fix. Feasibility: honor null (skip the maneuver-byte update); null contract already exists. Risk: medium (Maps-revival only).

**B3. Stage-1 `MapplsIdGuesser` is English-only and lossy; ordering bug for "destination" containing "head". [gap/risk, S]**
What: hand-rolled English keyword matcher; non-en locales fall straight to `DEFAULT_MAPPLS_ID=7` (confident wrong "straight"). Concrete latent bug: `"head "` catch-all (`:55`) precedes `"arrive"/"destination"` (`:58`), so "Head to your destination" → straight, not arrival. NO `MapplsIdGuesserTest.kt` exists. *Note (was an open question):* the compass cases (`:45-52`) ARE correctly ordered before the `"head "` catch-all (verified — not dead code); prior commit `09b3051` fixed a related shadowing bug, proving the ordering-bug class is real here. Why: confidently-wrong arrows are the original ride symptom. Feasibility: 1-line reorder + a test table. Risk: low-medium. Strategic: the planned Mappls Navigation SDK would emit real Mappls IDs and delete this guesser — prefer that over hardening the heuristic.

**B4. Bitmap aHash nearest-neighbour has no ambiguity guard; self-train can poison silently. [risk, M]**
What: `fromBitmapHashNearest` tracks only the single global-min Hamming distance then thresholds at tolerance 5 (`ManeuverMap.kt:100-113`, `ManeuverClassifier.kt:36`), with no tie/margin check. Self-train registers `hash→text-derived id` (`ManeuverClassifier.kt:96-97`), so one wrong text guess writes a poison entry that wins future lookups — the "permanent poison" the design fears. The DISAGREE warn fires only on hash-vs-text mismatch, never hash-vs-hash ambiguity. Why: converts silent corruption into a detectable event before re-enabling. Feasibility: return null when 2+ entries within tolerance map to different ids / require a margin; tolerance is unmeasured against real icons (none captured). Risk: medium.

**B5. Bitmap self-train persistence is dead code — `initPersistence()` is never called from app code. [bug, S]**
What: `initPersistence` loads the TSV and arms append-on-disk (`ManeuverMap.kt:62-71`); `appendToDisk` no-ops when `persistFile==null` (`:150-151`). Grep across `android/` finds `initPersistence` only inside `ManeuverMap.kt` + a stale KSP cache — no `MainActivity`/`AppGraph`/`BikeBridgeService` call site. So nothing loads on start and trained entries die on process death. Masked because self-train is OFF and Maps is parked, but the feature is non-functional, not merely disabled, and the KDoc claims "survives process restarts." Why: decide (wire it or delete the dead claim) before reviving Maps. Feasibility: pure code audit. Risk: low.

### C. Maps pipeline (parked)

**C1. No "Maps is actually navigating" detection — any Maps notification is treated as nav. [risk, M — the parking root cause]**
What: `parse()` gates only on `packageName == com.google.android.apps.maps` + any non-blank title/subText/text (`GoogleMapsParser.kt:52,72`). Traffic alerts, "You arrived," location-sharing, downloads all pass and get force-fed through the classifier → bogus up-arrow (byte 8) + wall-clock ETA. No `category == CATEGORY_NAVIGATION` / `FLAG_ONGOING_EVENT` / `progressMax>0` check. Why: this is the single biggest source of the wrong arrows that got the path PARKED 2026-06-04; filtering to the genuine ongoing nav notification is a prerequisite to revival. Feasibility: gating is purely pkg+blank; the distinguishing field needs a notification dump to confirm. Risk: medium.

**C2. `extractInlineDistance` first-match-wins can grab the wrong distance. [bug, S]**
What: `Regex.find` (first match) over the whole title (`GoogleMapsParser.kt:114-118`); "In 200 m, turn left … toward 5 km marker" locks onto whichever numeric+unit appears first. Structured `distanceFromProgress` (android.progress/progressMax ints) is only the FALLBACK, not primary. Why: a wrong next-turn distance mis-times the maneuver. Feasibility: anchor to the "In X" idiom or prefer `distanceFromProgress` as primary; exact title grammar needs a real dump. Risk: low-medium.

**C3. `runBlocking` DataStore read on the notification callback thread. [bug, S]**
What: `parseFromExtras` does `runBlocking { settings.maneuverSelfTrainEnabled.first() }` on every Maps notification (~2Hz) on the listener binder thread (`GoogleMapsParser.kt:91-93`); the value is only used in a narrow self-train branch. ANR/latency risk + makes `parse()` untestable. `NotificationDispatcher` already caches the allowlist via a collected StateFlow (`:53-55`) — mirror that, or thread the bool as a param (`classify()` already accepts it). Why: removes per-notification blocking I/O on a latency-sensitive thread AND unlocks deterministic parse tests (currently zero direct coverage). Feasibility: mechanical refactor. Risk: low.

### D. Richer data / faithful ceilings

**D1. ETA slot carries arrival clock OR a duration, never both. [gap, S — documents the ceiling]**
What: one 6-char field. OEM puts the arrival clock time there (`HHMM00`/`HHMMAA`), and `normalizeEta` already derives an absolute clock from "N min" via `now()+N`. A duration countdown would require abandoning the OEM format (creative-text, unverified). Why: sets the honest ceiling — clock-time is the OEM-faithful, safer choice. Feasibility: both paths implemented; duration-as-text bike-only.

**D2. Distance-to-turn and total distance both carriable; precision boundary is sharp. [improvement, S]**
What: distNext + distTotal are independent 4-char fields with own unit bytes. The app can carry FINER precision than OEM (no round-to-10), but decimals cost 2 of 4 chars, so a decimal is viable only for 0-99 of a unit; ≥100 km falls to whole-integer. OEM rounds to nearest 10 and uses a decimal only sub-10km (`NOTES.md:177`). Why: confirms distance precision can match/beat OEM and pinpoints the ceiling. See D3 for the stability argument.

**D3. Distance-to-turn not rounded to the cluster's 10-unit design granularity; three divergent formatters. [bug, S]**
What: OEM rounds to nearest 10 (`NOTES.md:175`); `normalizeDistance` does not (`ParsedNavData.kt:88-107`), and `distanceFromProgress` uses a THIRD format (`%4.1f`, `GoogleMapsParser.kt:121-133`). So the same physical distance is formatted three ways, and un-rounded values flicker every metre on approach. Why: distance-to-turn legibility/stability is the core of trustworthy cluster nav; matching the display's tuned 10m quantization is the safe default. Feasibility: one shared round-to-10 helper; on-bike legibility of un-rounded values unverified. Risk: low.

**D4. NowPlaying carries only 8 title chars, no scroll. [gap, M]**
What: `upper.take(4) + upper.drop(4).take(4)` = 8 chars (`IdleClockGenerator.kt:80-82`); eta is spent on the static `PLAYNG` label. The frame has 14 usable ASCII slots and the 1Hz cadence + content-dedup already support a marquee that advances each second (different content per second won't be deduped). Why: 8 fixed chars is near-useless for real titles. Feasibility: pure-JVM tick/offset; the `PLAYNG/@/*` layout itself is UNVERIFIED on-bike. Risk: low.

**D5. Lane guidance and next-next-turn CANNOT be faithfully carried. [gap, S — hard ceiling, document it]**
What: exactly ONE maneuver byte; bytes 3,15-17,25-27 are hardwired `0xFF` and undecoded (`NavFrame.kt`). No lane bitmask, no second icon. Why: prevents a tempting false roadmap item — stop any plan to extract Maps/Mappls lane data, it has nowhere to land. Feasibility: structural, provable from layout alone.

**D6. Roundabout exit number is lost — all roundabouts → one ID. [gap, M]**
What: `MapplsIdGuesser.kt:30` maps any roundabout → 72 → byte 45 (`ManeuverMap.kt:252`); a531 has no exit-number field. Why: roundabouts are where ambiguous guidance most often causes missed maneuvers. *Hypothesis (unverified for this bike):* the OEM Mappls path may distinguish exit-count via different IDs remapped by bike-state `e.b0`; whether the cluster even has distinct roundabout-exit glyphs is bike-only (ManeuverSweep). Feasibility: investigate via sweep; full lane/exit guidance not feasible in-frame.

### E. Mux / lifecycle

**E1. App never signals degraded GPS or nav-end; `status`/`continueFlag` hardcoded `'1'`. [gap, M]**
What: every producer forces `status='1'`, `continueFlag='1'` (`ParsedNavData.kt:42-43`; idle/welcome producers likewise). With phone GPS as the only source, GPS-loss still shows a confident arrow + stale distance. *Note:* sending `continueFlag='0'` on nav-end is NOT a verified clean-exit (see §2 REFUTED — it is a BLE-disconnect hint); wiring `status` to GPS-fix quality is the more defensible half. Feasibility: encoding trivial; GPS-fix→status mapping needs a fix source (parked). Risk: medium.

**E2. Idle rotation index drifts when optional slots toggle mid-cycle. [bug, S]**
What: active slot = `slots[(tick/CYCLE_SECONDS) % slots.size]` with `tick` monotonic and `slots` rebuilt each tick from live toggles (`BikeBridgeService.kt:202-247`). A track starting/ending mid-cycle changes `slots.size`, making the displayed slot jump non-deterministically instead of finishing the 5s window. Feasibility: track current slot identity, advance only on boundary ticks; pure-JVM. Risk: low.

**E3. Idle producer reads 3 DataStore flows via `.first()` every 1Hz tick. [improvement, S]**
What: `idleClockEnabled`/`nowPlayingOnCluster`/`rangeOnCluster` each `.first()` per second forever while connected (`BikeBridgeService.kt:209,215,216`); weather tuple recomputed every tick. The file already uses `stateIn` for `kmPerBarFlow`. Feasibility: hoist to cached StateFlows; battery/CPU win on an always-on service. Risk: low.

**E4. Idle rotation is blind to riding state. [improvement, M]**
What: tiles cycle every 5s regardless of motion; a scrolling track title can surprise a rider at speed. Speed is already available (`TelemetryRepository.latest.speedKmh` / GPS, used by CrashDetector at `BikeBridgeService.kt:290-293`). Feasibility: gate rotation on motion (stable tile at speed, richer rotation parked); pure-logic. Risk: low.

**E5. WelcomeFrame can be superseded within the same second it is sent. [risk, S]**
What: welcome is a one-shot URGENT with no minimum dwell; the next idle tick (≤1s) overwrites it (`WelcomeFrame.kt:16` "lives for one heartbeat tick"). If cluster render-latency >~1s, the greeting may flash sub-second or never. Feasibility: hold idle for N seconds after welcome, or burst it; required dwell is bike-unmeasured. Risk: low.

**E6. NavMux has no anti-flap hysteresis on the maps→idle boundary. [gap, M]**
What: `NavMux` is stateless `maps ?: idle` (`NavMux.kt:20-22`); on revival, any single-tick null (missed parse, tunnel) flips arrow→clock→arrow. The 60s `STALE_AFTER_MS` only covers long silence. Feasibility: add a short grace/debounce on the maps slot; tuning window needs a real-ride capture. Risk: medium (revival-only).

**E7. `MapsNavSource` 60s stale window is long; watchdog runs off the BLE scope. [risk, S]**
What: `STALE_AFTER_MS=60_000` (`MapsNavSource.kt:30`) means up to 60s of a frozen arrow after Maps quietly stops; the watchdog runs on a process-wide `SupervisorJob+Dispatchers.Default` independent of the service, so a stale frame can survive a service onDestroy/onCreate. Feasibility: tighten toward the 2-5s refresh cadence (10-15s) and tie scope to lifecycle / clear on onDestroy. Risk: low-medium (revival-only).

**E8. Arrival/nav-end has no first-class handling. [gap, M]**
What: arrival is inferred only from text → Mappls 40; `onRemoved → MapsNavSource.clear()` is commented out (`NotificationDispatcher.kt:74-79`), so the arrow lingers up to 60s on the watchdog. Reroute overwrites implicitly with no debounce (transient empty-distance → momentary `0000`). Feasibility: wire onRemoved→clear; a true "arrived" state has no faithful frame representation (see §2 REFUTED on `cf='0'`). Risk: medium.

### F. Write reliability

**F1. `FrameWriter.take()` priority inversion under concurrent arrival. [bug, S]**
What: the eager poll is strict, but when all channels are empty it falls into a `select{}` over the three `onReceive` clauses, which resolves by arrival order, NOT declared priority (`FrameWriter.kt:50-61`). A NAV landing microseconds before an URGENT (call alert / identity / welcome) can be delivered first — a one-frame inversion on exactly the latency-critical frames. Feasibility: re-poll urgent (then heartbeat) after any select wakeup before returning NAV; local to `take()`. Risk: low.

**F2. Drain dequeues before the Ready check — a held NAV blocks URGENT up to 3s. [bug, M]**
What: drain calls `take()` first, then checks Ready; a NAV pulled just as the link drops sits in the ≤3s wait loop (`BikeBridgeService.kt:507-522`) holding the single drain coroutine, blocking an URGENT identity enqueued on the next Ready. `BleClient` notes the bike DROPS the link (~30s CONN_TIMEOUT) if identity is late (`BleClient.kt:201-207,268-274`) — so this directly risks the drop the handshake avoids. Feasibility: check Ready first; peek (not pop) or drain URGENT-only while non-Ready. Timing impact bike-measurable. Risk: medium.

**F3. NAV dedup state `_lastNav` never resets across disconnect/reconnect. [bug, S]**
What: `enqueue()` drops NAV bytes equal to `_lastNav`, which is instance-lived and never cleared on drop/Ready/stale-drop (`FrameWriter.kt:29-37`; stale-drop at `BikeBridgeService.kt:519-522` doesn't roll it back). After a reconnect, if content hasn't changed, the first frame is deduped → cluster shows native/last state an extra cycle. Worse for real nav: the same maneuver shown before a blip is suppressed on reconnect → blank/stale at the moment of the turn. Feasibility: reset `_lastNav=null` on non-Ready/Ready. Risk: low.

**F4. Serialized write-with-response (~4s worst case/frame) throttles the queue and sheds NAV silently. [risk, M]**
What: every frame under one `writeMutex`, `WRITE_TYPE_DEFAULT`, up to ~2s BUSY-retry + ~2s ack timeout (`BleClient.kt:187-256`); single drain coroutine. NAV/HEARTBEAT channels are cap-4 DROP_OLDEST. Effective throughput is bounded by ack RTT, not the 1Hz producer; under congestion frames pile up and drop with no backpressure. The KDoc claims with-response is "needed for 1Hz throughput" — but with-response is the SLOWER mode; if real nav goes to 3-5Hz (`FrameWriter.kt:14` target), most frames drop. Feasibility: measure ack RTT on-bike; consider `WRITE_TYPE_NO_RESPONSE` for NAV (idempotent/droppable), keep with-response for URGENT/identity. Risk: medium.

**F5. Triple dedup means a dropped frame is never re-asserted; no self-heal. [gap, M]**
What: `distinctUntilChanged` in `NavMux` (`:22`) AND the collector (`BikeBridgeService.kt:486`) AND content-dedup in `FrameWriter` (`:34-37`) → each value sent exactly once on change; on a failed write the drain only logs, no re-enqueue (`:524-527`). A single lost a531 → cluster stale until content changes (seconds for idle, the whole approach for a held maneuver). The heartbeat self-heals (time field changes/sec); nav does not. Feasibility: periodic re-assert of the latest nav frame (bypass dedup every N s) or re-enqueue on write failure. Risk: medium.

**F6. `NavFrame.equals` compares the raw byte array, defeating dedup on mixed-source revival. [risk, S]**
What: `equals` includes `raw` (`NavFrame.kt:89-101`); `decode()` sets `raw=frame.copyOf()` (`:83`) while constructor-built idle frames default `raw=null`. A maps frame and a logically-identical idle frame compare UNEQUAL purely on `raw`, so dedup over-sends. Latent today (idle all raw=null). Feasibility: drop `raw` from equals/hashCode (encode never reads it); add a round-trip equality test. Risk: low (revival-only).

### G. Verification / tooling

**G1. No cross-language parity test between Python `protocol.py` and Kotlin `*Frame.kt`. [gap, M — highest verification leverage]**
What: two independent encoders for one wire format; Kotlin claims to "mirror" Python (`FrameCodec.kt:88-99`) but nothing feeds the same inputs to both and asserts byte-identical output. Only the Kotlin path reaches the bike, yet the Python tools are what the docs reason about. Feasibility: Python emits a golden hex corpus per type → checked-in fixtures → one Kotlin JUnit + one pytest decode them and assert field equality. Offline. Risk: low.

**G2. Real ride captures (pcap/frames.log hex) are never replayed into the decoder by tests. [gap, M]**
What: `captures/` has real wire data (`m0-*.pcap`, `ride-20260524-1810.pcap`, `with-sim-nav-*.pcap`); `FrameStream.kt:82-87` mirrors TX/RX hex to disk; `validate_pcap.py` is a manual CLI, not a test; Kotlin has only two hardcoded hex literals (`NavFrameTest.kt:12-13`). *Distinction:* `ride-*.log` records frame TYPE strings ("TX nav type=0x31"), not hex — `frames.log` is the hex source. Feasibility: freeze distinct a531/a533/a536/a537 frames as golden fixtures; assert both decoders on every test run. Offline. Risk: low.

**G3. Cluster byte→glyph sweep TSV is captured but never asserted; `docs/cluster-byte-glyphs.md` unpopulated. [gap, M]**
What: `ManeuverSweepScreen` writes `cluster_byte_glyphs.tsv` (`:96-101`) but `ManeuverMapTest` green-lights Stage-2 translation whose downstream glyph target is unverified. Feasibility: once filled on-bike, pin confirmed byte→glyph rows as a fixture and assert `ManeuverMap` output only ever lands on confirmed-glyph bytes (kills the 2026-05-25 wrong-arrow class). Sweep is bike-gated; fixture step offline. Risk: low.

**G4. Frida `ride_capture.js` hooks OEM `A0.D()` inputs but nothing diffs them vs our encoder. [improvement, L — strongest oracle, unused]**
What: `ride_capture.js:270-279` emits `a531_build_inputs` (the 4 inputs the OEM feeds `A0.D()`) — the gold-standard "what bytes does the official app put on the wire for these inputs." No tool reconstructs the OEM frame and diffs against `NavFrame.encode()`/`ManeuverMap`. *Caveat:* obfuscated names drift per APK; the existing `ride-20260524-1810-frida.jsonl` has `hook_error`/ClassNotFoundException for `C0855q0`, so a fresh clean capture (zero hook_errors) is needed. Feasibility: capture bike+frida; diff tool offline. Would validate Stage1+Stage2+NavFrame against the manufacturer at once. Risk: low (analysis); the capture is the work.

**G5. No tool replays a recorded session to the bike; idle-text rendering remains the dominant unverified assumption. [risk, M]**
What: `forge_display.py` writes synthetic frames (its `text` subcommand sends exactly the IdleClock/Welcome byte layouts), `send_custom.py` replays one a531, `ManeuverSweepScreen` bursts bytes — but no tool replays a whole `frames.log`/pcap to reproduce an observed render, and the creative-text idle approach is flagged UNVERIFIED in source. Single-shot writes are known to not latch (DISCOVERIES.md). Feasibility: a `frames.log`→bike replay tool is offline; running the layout checklist (photo per layout, burst cadence) requires the physical bike with the OEM app disconnected. Risk: medium.

### H. Experience gaps

**H1. The core promise — turn-by-turn on the cluster — does not exist today. [gap, L]**
What: `NavMux(flowOf(null), idleProducer)` (`BikeBridgeService.kt:255`) + commented-out Maps branch ⇒ only idle tiles reach the bike. The system is an ambient-info display. Why: the single highest-leverage work is reviving a live maneuver source (Mappls Navigation SDK gives real Mappls IDs, no text-guessing). Until something calls `MapsNavSource.update()`, every other nav refinement is theoretical. Feasibility: SDK integration is net-new (L); re-enabling the notification-scrape path re-introduces the wrong-arrow risk (see C1).

**H2. NO_MANEUVER_BYTE=46 assumed blank, but byte 8 proved a literal arrow — idle/now-playing/welcome may all show a stray glyph 24/7. [risk, S]**
What: `IdleClockGenerator.build/buildNowPlaying` and `WelcomeFrame` all set maneuverId=46 to avoid a misleading arrow (`IdleClockGenerator.kt:49,86`; `WelcomeFrame.kt:29`), explicitly a HYPOTHESIS (`ManeuverMap.kt:25-28`). Same class of "renders nothing" assumption that already burned the project on byte 8. These frames are the ONLY thing the cluster receives today. Feasibility: one sweep row settles it (bike). Risk: medium.

**H3. `buildNowPlaying` KDoc re-asserts the disproven "byte 8 draws no arrow." [bug, S]**
What: KDoc (`IdleClockGenerator.kt:66`) and inline comment (`:84-85`) say maneuverId=8 / "cluster will not draw an arrow for 8," but the code sets `NO_MANEUVER_BYTE` (`:86`), and byte 8 is a confirmed up-arrow. Stale comment asserting a disproven fact — exactly how the project's two historical mistakes propagated. Feasibility: doc-only fix. Risk: low.

**H4. On-cluster legibility of ALL creative-text layouts is unverified, yet they are the only live output. [risk, M — gates every idle-tile feature]**
What: clock/`PLAYNG`/`RANGE`/`WELCOM` all repurpose numeric slots as ASCII text; all flagged ASSUMED in their own KDocs. Only generic raw-ASCII acceptance was loosely shown, not these layouts. If the cluster renders garbage/blanks/digits-only, the rider sees broken frames for the one live feature. Feasibility: a single ManeuverSweep/idle on-bike session confirms or invalidates the whole approach; bike-only. Risk: medium → potentially large rework if false.

---

## 4. Top picks (ranked by value-to-effort)

1. **A1 — Fix RANGE 10× bug (S).** Live, headline, misleading number; pure wire-fact fix. Highest value/effort.
2. **H2 + H3 — Settle NO_MANEUVER_BYTE=46 render (S) and fix the stale byte-8 KDoc (S).** One sweep row + a doc fix protect the ONLY thing on the cluster today and stop a known recurring mistake.
3. **B1 — A/B-test byte2 raw-Mappls vs translated (M).** Highest correctness leverage in the whole system; one ManeuverSweep validates or reverts the entire maneuver-id rework.
4. **C1 — Gate Maps parse to genuine ongoing-nav notifications (M).** Prerequisite to un-parking; directly addresses the wrong-arrow reason the path was shelved.
5. **F2 + F3 — Fix drain Ready-ordering (M) and reset `_lastNav` on reconnect (S).** Protects pairing (CONN_TIMEOUT) and self-heals the cluster after the routine BLE flaps a moving bike has.
6. **G1 + G2 — Golden-frame cross-language + capture-replay fixtures (M).** Turns "we believe the encoders agree / match captures" into CI-enforced fact, serving the no-assumptions rule cheaply and offline.
7. **A2 — Rename `writeAsciiLeftPadZero` / assert width (S).** Removes a silent corruption landmine masked only by coincidence.
8. **B3 + B5 — Add `MapplsIdGuesserTest` + fix head/destination order (S); decide on dead self-train persistence (S).**
9. **H4 — On-bike idle-text legibility session (M).** Gates every idle-tile feature; cheap to run, large implication if false.
10. **H1 — Revive a live maneuver source via Mappls SDK (L).** The actual product; everything else is polish until this lands.

---

## 5. On-bike verification needed (physical bike, OEM app disconnected)

1. **Creative-text legibility (H4):** photograph clock / `PLAYNG`+title / `RANGE`+km / `WELCOM`+name layouts via `forge_display.py text` or burst. Gates all idle tiles.
2. **NO_MANEUVER_BYTE=46 render (H2/H3):** does byte 46 show blank or a literal `.`? One sweep row.
3. **byte2 ground-truth A/B (B1):** raw Mappls 3 vs translated byte 4 for "turn right" — settles the rework's core hypothesis.
4. **Cluster byte→glyph table (G3):** populate `docs/cluster-byte-glyphs.md` via ManeuverSweep 1..53; re-confirm byte 8 (up-arrow) and 46 as anchors each session.
5. **Nav-mode latch / minimum burst (F4/F5):** does an unchanging a531 stay on-screen, or must it be re-sent? Measure ack RTT and whether `WRITE_TYPE_NO_RESPONSE` is accepted.
6. **`continueFlag='0'` real effect:** REFUTED as "terminate nav" (it is a disconnect hint); confirm the actual cluster reaction to a lone `cf='0'`.
7. **24h ETA layout (A6):** with a 24h locale, capture the OEM ETA bytes to confirm `"00HHMM"` vs the app's `"HHMM00"`.
8. **Degraded `status` text for `'2'/'4'/'6'` (only `'0'` empirically seen):** confirm "Searching for network" vs other states.
9. **Vehicle/BTID branch for `A0.C()` (frida, not the bike alone):** confirm Gixxer uses the default branch (affects Mappls IDs 58, 74).
10. **Clean frida `a531_build_inputs` capture (G4):** a ride with zero hook_errors to enable the manufacturer-truth encoder diff.
11. **Link-flap timing (F2):** confirm a held NAV does not starve URGENT identity past CONN_TIMEOUT.
