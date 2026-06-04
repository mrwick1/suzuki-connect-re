# REDLINE PRESS — GixxerBridge Design System (Design Spec)

> Date: 2026-06-04
> Status: design approved in brainstorm; pending written-spec review
> Supersedes: `docs/superpowers/specs/2026-05-25-ui-overhaul-design.md` (the "Wave 1"
> Linear-dark + red + Inter system, judged generic / "AI-made" and discarded)

## 1. Context & goal

GixxerBridge's current UI (dark `#0A0A0A` + single red accent + Inter + Geist Mono +
rounded grey cards) reads as templated / AI-generated. The owner's mandate: a
**from-scratch** visual language that **keeps nothing**, kills all four "AI tells"
(the palette, card-soup sameness, dead motion, generic type & icons), feels
**expressive & alive** (Instagram / Duolingo / Robinhood energy), and is
**showpiece-first** (most use is at-rest browsing of rides/stats).

This direction — **REDLINE PRESS** — was chosen from three researched candidates
(ECSTAR ARCADE, REDLINE INSTRUMENT, REDLINE PRESS) produced by an 11-agent design
research workflow (7 research lenses → 3 direction designers → 1 adversarial critic).
Raw research and the full three directions are archived at
`.superpowers/_research.md`, `.superpowers/_directions.json`, `.superpowers/_critique.json`.

**The metaphor:** your motorcycle life as a *motorsport magazine that publishes a
fresh issue every ride.* A living print object, not a dashboard. This is what makes it
inherently un-templatable and is the structural answer to "every screen looks the same."

## 2. Decisions locked (brainstorm, 2026-06-04)

1. **Scope of redesign:** burn it down, keep nothing. Red/matte-black not preserved.
2. **North star:** expressive & alive; showpiece-first.
3. **Primary direction:** REDLINE PRESS.
4. **The Sweep** (from REDLINE INSTRUMENT) is adopted as a **system-wide gauge
   primitive**, not a one-off — the coherence thread across all 40+ screens.
5. **Living soul-bike** is a **core v1 element**, but **built as a hand-authored
   layered Compose vector illustration** (not commissioned art, not live 3D), so its
   quality is code we control. Moods are gated by data provenance (§7).
6. **This spec covers:** the design system + the **3 signature screens** (Home,
   Post-ride, Cluster). The remaining ~37 screens migrate later via a documented
   pattern (§9) and a **separate per-screen analysis/research workstream** (§13).
7. **Palette = the real bike.** The owner's Gixxer is **Glass Sparkle Black + Metallic
   Lush Green** (verified via Suzuki's official colorway list). So the signature accent
   is **lush green** (`#8FE03A` placeholder, tuned to a photo at build time), the base
   is black, and **Ecstar blue is demoted to a structural/secondary** brand color — not
   the research's default blue-primary + acid-yellow. The soul-bike render is
   black-with-lush-green to match.

## 3. Design principles

- **Kill the four tells at the root** (mapping in §4–§8).
- **One Hero per screen, enforced.** Tile size = importance. Never a uniform stack.
- **Color is data, not decoration.** A single telemetry spectrum means the same thing
  everywhere; the signature accent is rationed to one moment per screen.
- **Motion is physics, never fade.** Spring overshoot + velocity continuity. Interrupts
  redirect, never restart.
- **Two motion lanes (first-class contract):** `Expressive` for at-rest/showpiece
  screens; `Pure` (max-contrast, stripped, no decorative motion) for Active-ride, SOS,
  Diagnostics. Glanceability is a designed contract, not an afterthought.
- **No-assumptions honesty (project NON-NEGOTIABLE).** No UI copy asserts a
  bike-sourced channel as fact unless verified over BLE. Verified today (per
  `NOTES.md`): connection state, and a537 telemetry = speed, odometer, Trip A/B, fuel
  bars (1–6), fuel economy (km/L). NOT verified: lean angle, RPM, instantaneous fuel,
  any diagnostic/fault channel. Anything in those categories is labeled
  phone-IMU-derived / app-computed / "estimate," or hidden until a frame is captured.

## 4. Foundation — color tokens

Dark is the default (OLED savings + showpiece feel). A real **TARMAC** light mode
ships as a **sunlight-legibility safety feature** (the K20 Pro panel is dim outdoors).

### 4.1 Core palette (dark)

| Token | Hex | Role |
|---|---|---|
| `lushGreen` | `#8FE03A` | **Signature accent** — matches the bike's real Metallic Lush Green stripes. ONE rationed moment/screen (hero numeral, redline tip, record badge, active CTA). Placeholder hex; tuned to a photo of the actual bike when the soul-bike is authored. |
| `ecstarBlue` | `#0B5BD6` | **Structural / secondary brand.** Nav, primary-action surfaces, bike rim-light, M3 seed. Kept as a quiet second voice so green stays rare and loud — never competes with the green accent. |
| `inkBlack` | `#000308` | App background. Effectively OLED-true black, biased cool-blue so it reads intentional, not dead grey. |
| `cockpitSurface` | `#0A1424` | Surface plane 1 (bento tiles). |
| `cockpitSurface2` | `#13233D` | Surface plane 2 (raised tiles / sheets). |
| `liverySilver` | `#C7D0DC` | Chrome sheen, secondary text, gauge bezel, sparkline strokes. |
| `onSurface` | `#E8EEF6` | Primary text/numerals on dark. NOT pure `#FFF` (avoids AMOLED halation). |
| `onSurfaceDim` | `#9FB0C8` | Secondary/caption text on dark. |

### 4.2 Telemetry spectrum (color = data) — used by The Sweep and all intensity viz

| Token | Hex | Meaning |
|---|---|---|
| `zoneCool` | `#10D9C4` | cruise / eco / full range / healthy / low intensity |
| `zoneMid` | `#F5A524` | caution / transitional / service-due-soon / mid |
| `zoneHot` | `#FF2D78` | hot / strain / near-redline / low-fuel / max |
| `dangerWarm` | `#F2542D` | true faults / SOS / critical. Warmed orange-red — **never** `#B91C1C`. |

### 4.3 TARMAC light mode

| Token | Hex | Note |
|---|---|---|
| `paperBg` | `#F4F7FB` | Cool off-white (less glare than stark white). |
| `paperSurface` | `#FFFFFF` | Surfaces; tinted `#E8F0FC` for elevation. |
| `lushGreenLight` | `#3E7D14` | **Role-swap.** `#8FE03A` is illegible on white — darken to a forest green for AA. |

**Rules:**
- Every accent ships a role-swapped light variant (non-negotiable).
- `ecstarBlue` stays the structural brand in both modes; `lushGreen` role-swaps to `lushGreenLight`.
- Mode switching: **manual day/night toggle + an optional one-shot speed-threshold
  flip** (e.g. snap to TARMAC on first sustained ride). **No continuous
  ambient-sensor flipping** — it flickers on shadows/tunnels and reads as a bug.
- Validate Active-ride/cluster/SOS text with **APCA** (not only WCAG 2.x) at simulated
  high ambient.
- Both schemes generated from the `#0B5BD6` seed via `dynamicColorScheme` with
  `dynamicSchemeVariant = Vibrant/Expressive` (avoids washed-out `tonalSpot`).
  Brand-seed scheme is the default; Material You dynamic color is opt-in only.

**Anti-AI (palette):** steals **this exact bike's** real livery — Glass Sparkle Black +
Metallic Lush Green — instead of the dark+single-red trope, with Ecstar blue as a quiet
structural second voice. Impossible to mistake for a template when the accent IS the
owner's bike. Color carries data via the spectrum; blue-tinted near-blacks replace dead
grey cards and reclaim the OLED saving.

## 5. Foundation — typography

| Role | Family | Notes |
|---|---|---|
| Display / hero / numerals | **Saira** (variable, **bundled `.ttf`**) | 70s motorsport-poster heritage; 4-width × 9-weight grid = a whole type system from one animatable file. **Must be bundled** — Downloadable Google Fonts strip the variable axes. Condensed/SemiCondensed widths for heroes. |
| Body / lists / settings | **Hanken Grotesk** (Downloadable Google Font, static) | Warm humanist grotesque, deliberately NOT Inter. |
| Dev / Diagnostics / Frame log | **JetBrains Mono** (variable, bundled) | Slashed zero; makes the BLE frame log read as an instrument, not a debug textarea. |

**Numerals:** always `fontFeatureSettings = "tnum, zero"` (tabular + slashed zero) so
digits never jitter as they roll, and read as engineered instruments.

**Scale — brutal contrast (WHOOP rule: one huge figure, supporting stats tiny):**
- `numericHero` 64–96sp / W700 / tnum+zero (live speed, odo, range)
- `displayHero` 56–72sp / W800–900 / ~75% condensed width; masthead numerals bleed
  off-edge via `BasicText` + `TextAutoSize.StepBased(maxFontSize = 220.sp)`
- `headline` 28sp/700 · `title` 20sp/600 · `body` 16sp/1.45/400
- `label` 13sp UPPERCASE +0.06em tracking/600

**Anti-AI (type):** drops Inter+Geist for three characterful voices; numbers get a
racing face and *animate* (roll/weight-pulse).

## 6. Foundation — motion, shape, depth, iconography

### 6.1 Motion system

Named springs (replace the old two-spring `Motion.kt`), built on
`MaterialExpressiveTheme` `MotionScheme.expressive()`:

- `SpringSnap` — dampingRatio ~0.6, stiff — most state transitions/commits.
- `SpringSweep` — dampingRatio ~0.55, `StiffnessLow` — the ignition sweep, big reveals.
- `SpringBouncy` — `DampingRatioMediumBouncy` — rewards/overshoot (gesture rewards, records).

**Signature motions (the kit):**
1. **Ignition Sweep** — gauge sweeps 0→max→current with overshoot on app open / BLE
   connect / pull-to-refresh. `Animatable` read inside `Modifier.drawWithCache { onDrawBehind {} }`; brush allocated once.
2. **Odometer Roll** — per-digit `AnimatedContent` (slide direction keyed to ↑/↓),
   directional color + haptic tick on rollover; bouncy pop on a new record. Only
   changed digits recompose.
3. **Card → Cover Handoff** — the ONE shared-element transition, reused with the same
   key pattern everywhere (route thumbnail + headline numeral, ≤2 keys).
   `SharedTransitionLayout`; text uses `ScaleToBounds` (not `RemeasureToBounds`).
   Everything else uses cheap fade/slide so this stays special.
4. **Reveal Stagger Bloom** — tiles rise top-to-bottom ~50ms/item (cap ≤400ms),
   `translationY(8dp→0)+alpha` in `graphicsLayer` (draw-phase, never expand/shrink),
   closing on a `MaterialShapes` morph for celebrations.
5. **Scrub-the-Trace** — long-press a trace → value stick tracks finger, headline
   re-binds live, haptic ticks at km markers / corners; trace self-draws via
   `PathMeasure.getSegment`, then caches.
6. **Soul-Bike Breathing** — §7.

**Forbidden:** plain fade-in/out as the primary transition; `Crossfade`;
`infiniteRepeatable` for anything non-loading (gate ambient loops to visible+connected,
pause off-screen); tween() for UI state.

### 6.2 Shape, spacing, depth

- **Bento system:** `LazyVerticalGrid` + `GridItemSpan`, six fixed tile spans
  (Hero / Wide / Half / Tall / Quad / Full-bleed). Tile size encodes importance.
- **Corner radii:** chips 8 · tiles 18–22 · sheets 28.
- **4-plane depth:** Plane-0 atmosphere (gradient + faint route trace) → Plane-1 base
  surface → Plane-2 tiles/glass → Plane-3 floating (nav/sheets). Depth from ONE
  elevated surface + shadow-gradient + color contrast, **max 2–3 translucent layers**
  (Adreno 640 fill-rate).

### 6.3 Iconography

Bespoke motorcycle glyph language — **the deliverable, not restyled Material defaults.**
Contract: 24dp keyline grid, **locked 2dp stroke** (never mixed), shared keyplate of
primitives (circle/square/45° diagonal) so siblings read as a family. As `ImageVector`
/ `AnimatedVectorDrawable`. Five families:
1. **Maneuver arrows** — chunky hard-chamfered turn-by-turn set (slight/sharp left,
   U-turn, roundabout-exit-N, merge, fork, destination flag), path-morph on update.
   The most-seen glyphs → they ARE the brand. (Coordinate with the existing
   `ic_step_*` cluster maneuver IDs — these are the phone-side previews.)
2. **Lean/dynamics** — lean-angle wedge, braking-G, tilt-bike silhouette; wedge tilts
   live via `graphicsLayer{ rotationZ }`.
3. **Fuel/fluids** — custom fuel-drop, reserve, range-ring (never the stock pump).
4. **Bike-health** — a bike-SHAPED health badge (not a generic heart/shield),
   wrench-on-cog service glyph, BLE-frame chevrons for the dev log.
5. **Telemetry micro-glyphs** (16dp) — speed caret, tach-tick, temp, odo-segment.

Utility glyphs (back/share/settings) may be Material-derived but restroked to 2dp.

## 7. The soul-bike (built, not sourced)

The Home hero and emotional anchor. **Hand-authored layered vector illustration** in
Compose — stacked `ImageVector`/`Canvas` path layers (wheels, frame, tank, fairing,
rim-light), traced from a **real Suzuki Gixxer SF 150 reference photo** (sourced from
the web for accurate proportions) during implementation. **Livery matches the owner's
actual bike: Glass Sparkle Black bodywork with Metallic Lush Green stripes** — the
green stripe being the exact source of the `lushGreen` signature accent, so the app and
the bike share one color. NOT live 3D, NOT a commissioned/Lottie-only asset. Themeable
finish; animatable.

**State machine — moods gated strictly by data provenance:**

| Mood | Trigger | Provenance | Ship in v1? |
|---|---|---|---|
| `asleep / cold` | BLE disconnected | **Verified** (connection state) | Yes |
| `awake / breathing + rim-light` | BLE connected | **Verified** (connection state) | Yes |
| `revving` | long-press (haptic + shimmer + sound) | local interaction | Yes |
| `thrilled / record` | new top speed or longest trip | **Verified a537** (speed, Trip A/B) | Yes |
| `concerned` | service overdue / app-computed health flag | **App-computed** (never claimed as bike fault codes) | Yes (labeled as app-derived) |
| lean/RPM-driven expression | — | **Unverified over BLE** | **No** — designed but dark until a frame is captured |

Breathing = `rememberInfiniteTransition` on `graphicsLayer` scale, gated to
visible+connected, paused off-screen. If a Lottie/Rive runtime is later trialed for the
character, cap to 1–2 short `RenderMode.HARDWARE` instances, never in a scrolling list.

**Fallback (safety net):** if the authored illustration doesn't reach quality bar, the
Home hero degrades to a **code-only** treatment (bleeding hero numeral + route-as-art +
morphing health ring) with zero illustration dependency. The screen must be fully
functional and beautiful without the bike render.

**Anti-AI (the risk the critic raised):** because the bike is code we author from a real
reference (not a generic stock illustration) and its moods are driven by verified data
(not random/canned), it escapes the hollow-mascot failure mode.

## 8. Signature screens (fully designed here)

### 8.1 Home — "Living Cover"
A magazine cover, not a card stack. **Plane-0:** full-bleed time-of-day gradient
(`ecstarBlue → inkBlack`) with a faint lush-green route trace of today's ride animated
by ONE `rememberInfiniteTransition` draw-phase translate. **Hero:** the soul-bike
center-right (breathing if connected, asleep-cold if parked) with a live
"parked 4h 12m ago · 320m away" caption. **Off the LEFT edge**, bleeding past the
gutter, an oversized Saira-condensed-900 masthead numeral (~200sp, `TextAutoSize`) —
today's km or current range — in `lushGreen`, odometer-rolling on update. Below, a
4-column **bento wall** (Plane-2 tiles): Half tile = fuel/range as a glowing Sweep arc;
Half tile = bike-health as a `MaterialShapes` ring (Cookie9Sided→circle = all good,
spikier `RoundedPolygon` = flag); Quad tiles = quick actions (custom glyphs); Wide tile
= last-parked map strip. Connection = a slow-breathing BLE dot, not a chip. Blur only on
≤2 static glass tiles. Stagger-rise on entry; pull-to-refresh fires the Ignition Sweep.
**One Hero, enforced.**

### 8.2 Post-ride — "Ride Wrapped"
The highest-emotion, showpiece moment. `VerticalPager` of full-bleed share-ready scenes
(Strava-Flyover energy):
- **Scene 1 — Route as art:** the path drawn as a **lean-angle ribbon**, hue running
  `zoneCool` (left lean) → `liverySilver` (upright) → `zoneHot` (right lean), self-drawing
  via `PathMeasure` on entry, oversized Saira headline ("LAVASA LOOP · 38°") bleeding
  off-edge. *(Lean is phone-IMU-derived until a bike channel is verified — labeled as such.)*
- **Scene 2 — Top speed:** one giant `numericHero` odometer-rolling up to the value over
  a Sweep speed band, "+3 km/h vs your average" delta in directional color. *(speed =
  verified a537.)*
- **Scene 3 — The trace:** speed-vs-distance with auto-annotated corners (deepest dip =
  slowest corner) in `zoneHot`; long-press to **Scrub** with km-tick haptics.
Each scene staggers in with Reveal Stagger Bloom + a celebratory `MaterialShapes` morph,
and renders to a bitmap via `GraphicsLayer.toImageBitmap()` (one-shot) for one-tap share.
`lushGreen` rationed to the record badge only.

### 8.3 Cluster Preview / Active-ride — "The Sweep"
The most overtly automotive surface; mirrors what's bridged to the bike's stock cluster.
Full-bleed `inkBlack` instrument canvas, **no card** around the hero. **The Sweep:** a
~270° arc (`drawArc(Stroke(cap=Round))`) whose lit 0→current portion is a
`Brush.sweepGradient` over the telemetry spectrum (`zoneCool → zoneMid →
zoneHot/lushGreen` at redline), remainder a 12%-alpha track, `liverySilver` brushed
bezel; lush-green tip glows hotter near redline (baseline: layered translucent-stroke +
`BlurMaskFilter` fake-glow; AGSL bloom only as API-33+ progressive enhancement). Speed
numeral dead-center 64–80sp Saira tnum+zero, rolling. Thin RPM tick-bar "breathes" near
redline *(RPM unverified — phone-derived/hidden until confirmed)*; gear digit; the live
Google Maps turn-arrow as a custom maneuver glyph (AVD morph on change). Every BLE frame
written to the cluster fires a subtle **edge-flash scanline** — the bridge's felt
heartbeat. **Switches to the `Pure` motion lane** the instant the bike moves (max
contrast, no decorative motion). **Budgeted like a game loop:** every value read in the
`drawWithCache` draw-phase, zero per-frame recomposition, Brush/Path/Shader/PathMeasure
allocated once, repaint throttled to the actual low-Hz BLE cadence (not 60Hz).

## 9. The Sweep primitive & long-tail rollout pattern

**The Sweep is a reusable component**, not three bespoke gauges. One
`Sweep(progress, zones, modifier, style)` composable: cached `sweepGradient`,
draw-phase, optional center slot. It renders fuel/range, RPM, **service-interval
countdown**, weekly-distance goal, stats intensity — anywhere "how far along / how hot."

**Documented rollout pattern for the ~37 long-tail screens** (Settings, Maintenance,
Mileage, Diagnostics, Stats, Onboarding, Trips list, etc.) so they don't regress to
card-soup:
- **Bento, one Hero per screen** (the screen's most important value/object), everything
  else demoted to small tiles. Hierarchy by type-contrast, not by N identical cards.
- **The Sweep** for any progress/intensity value on the screen.
- **List rows** use the shared `RideRow`-style component with the Card→Cover handoff into
  detail.
- **Type-contrast hierarchy** + the icon family + telemetry-spectrum semantics applied
  uniformly. Diagnostics uses JetBrains Mono and the BLE-chevron glyphs (intentional, not
  a debug dump).
- Active-ride / SOS / Diagnostics run the **Pure** motion lane.

*(Exact per-screen designs are NOT in this spec — see §13 workstream.)*

## 10. Components to build (reusable kit)

`theme/` (rewritten): `GixxerColors` (dark+TARMAC schemes), `GixxerTypography`
(Saira/Hanken/JBMono), `GixxerShapes`, `Motion` (named springs + lanes),
`GixxerIcons` (glyph set). `ui/components/`: `Sweep`, `BentoGrid`/`BentoTile`,
`OdometerNumber`, `HeroNumeral` (bleeding), `TraceChart` (self-draw + scrub),
`HealthRing` (MaterialShapes morph), `SoulBike`, `BreathingDot`, `MotionLane`
(Expressive/Pure provider), `ShareScene` (toImageBitmap wrapper),
`ManeuverGlyph` (AVD). The existing `Skeleton` is restyled to the new tokens.

## 11. Migration & enforcement

- Rewrite `ui/theme/*` (`GixxerTokens`, `Theme`, `Motion`, `GixxerFonts`) to the new
  system. **Keep the Konsist `HardcodedHexLintTest`** rule (no raw `Color(0xFF…)`
  outside `ui/theme/`) — it already enforces single-source tokens.
- Bundle Saira + JetBrains Mono variable `.ttf` in `res/font/`; Hanken Grotesk stays
  Downloadable. Provide system fallbacks (no layout shift on font load).
- Three signature screens are rebuilt to the new system in this effort; existing
  long-tail screens keep working under the new theme (will look transitional) until
  their migration plan.

## 12. Testing & verification

- **Screenshot tests** (extend the existing `HomeScreenshotTest`) for the 3 signature
  screens, dark + TARMAC.
- **Motion tests** (extend `MotionTest`) for the named springs and lane switching.
- **APCA contrast** check for Active-ride/cluster/SOS text at simulated high ambient.
- **Device profiling on a real Snapdragon 855** before locking: the Card→Cover shared
  transition on a chart-heavy screen, the variable-font weight-pulse, the cluster
  game-loop frame budget, GPU overdraw (stay out of deep-red). Cheap fallbacks ready.
- **No-assumptions gate:** any telemetry-driven element (lean ribbon, RPM bar) ships
  behind a verified-frame check or an explicit "derived/estimate" label.

## 13. Deferred / out of scope (separate workstreams)

- **Per-screen analysis/research workstream** (owner-requested): screen-by-screen
  research on what each of the ~37 long-tail screens *should* show and how, then redesign
  — run **after** this spec ships, as its own effort (mirrors the design-research
  workflow but per-screen).
- The soul-bike's **lean/RPM-driven expressions** — until those channels are verified
  over BLE.
- **AGSL shader** glow/bloom/mesh enhancements — progressive enhancement on API-33+ ROMs
  only; never load-bearing.
- Full 40-screen visual migration (follows the §9 pattern, plan per cluster of screens).

## 14. Engineering constraints (Snapdragon 855 floor) — green/yellow/red

- 🟢 **Green:** saturated gradients; variable-font *static* rendering; draw-phase spring
  animation via `graphicsLayer`/`drawWithCache`; `drawArc` gauges; per-digit
  `AnimatedContent` number rolls; `MaterialShapes` morphs; stagger via `graphicsLayer`.
- 🟡 **Yellow (use sparingly, profile):** `SharedTransitionLayout` (ONE hero pattern,
  ≤2 keys, `ScaleToBounds`); variable-font *weight/width animation* (hero-only, throttle
  ~10–15fps or threshold-only); `Modifier.blur`/`RenderEffect` (API-31+, ≤2 small static
  surfaces, never scrolling, never animated radius); Lottie (≤1–2 short, HARDWARE, never
  in lists).
- 🔴 **Red (needs a shader-free fallback / avoid):** AGSL `RuntimeShader` (API-33+ only;
  K20 Pro stock floor is Android 11 — ship layered-stroke / `BlurMaskFilter` /
  pre-baked radial-halo fallbacks); live 3D render; repainting the cluster at 60Hz for
  ~4Hz data; reading fast-changing animated values directly in modifier params (isolate
  into small composables; throttle BLE updates to ~10–15Hz).
- All wow lands at **60fps / 16.6ms** (floor device is 60Hz). Quality comes from curve
  design (anticipation/overshoot/stagger/settle), not framerate.

## 15. Open risks

1. **Telemetry provenance** — lean/RPM unverified; range is a computed estimate. Honesty
   gating per §3/§7/§12. Resolve by capturing frames during lifecycle events.
2. **Illustration quality** — the soul-bike lives or dies on the authored vector;
   §7 fallback de-risks it.
3. **Sunlight legibility** — the "~600 nit" panel figure is a manufacturer spec, not
   measured; treat sunlight legibility as a validated requirement (APCA) regardless.
4. **Saira variable axes require bundling** — verify the bundled `.ttf` exposes wght/wdth
   on this device before committing weight-pulse motion.
