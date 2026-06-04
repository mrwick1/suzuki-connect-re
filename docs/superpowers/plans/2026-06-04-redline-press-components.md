# REDLINE PRESS — Component Kit (plan 2 of 4) Implementation Plan

> **Execution:** Built inline by the controller (no subagents, per owner). TDD + Roborazzi screenshot verification per component; commit per component; the controller views every screenshot before moving on. Taste-critical components (soul-bike, glyph set) are done with the owner in the loop via the visual companion.

**Goal:** Build the reusable REDLINE PRESS component kit on top of the merged foundation (tokens/type/motion/schemes), so the 3 signature screens (plan 3) can be assembled from it.

**Architecture:** Each component is a small, focused composable in `ui/components/` (or a domain folder), reading only `GixxerTokens`/`MaterialTheme.colorScheme`/`GixxerMono`/`Motion`. Light-capable components use `colorScheme.secondary` for the accent (theme-aware), not the fixed `GixxerBrand.accent`. Gauge/morph components add `androidx.graphics:graphics-shapes`.

**Tech Stack:** Compose (BOM 2026.05.00), Material3 1.3.1, `androidx.graphics:graphics-shapes` (new), Roborazzi, the foundation theme.

Spec: `docs/superpowers/specs/2026-06-04-redline-press-design-system-design.md` (§6.3 iconography, §7 soul-bike, §9 The Sweep, §10 component list, §14 feasibility).

---

## Sequencing (by dependency / reuse)

1. **Task 1 — Add `graphics-shapes` dependency.** Needed by HealthRing + any MaterialShapes morph. Verify it resolves.
2. **Task 2 — `Sweep`** (the system primitive). `Sweep(progress, zones, modifier, trackColor, content)` — ~270° arc, cached `sweepGradient` (telemetry spectrum), draw-phase via `drawWithCache`, optional center slot. Ignition-sweep entry via `Animatable` + `Motion.SpringSweep`. Shader-free glow baseline (layered translucent stroke). Unit test: zone→color mapping helper. Screenshot: 3 fill levels (cool/mid/hot).
3. **Task 3 — `OdometerNumber`** — per-digit roll via `AnimatedContent` (slide direction keyed to ↑/↓), `tnum, zero`, directional color + optional haptic on rollover. Unit test: digit decomposition; Screenshot: a value mid-roll.
4. **Task 4 — `HeroNumeral`** — oversized condensed numeral (Saira Condensed) that bleeds off-edge via `BasicText` + `TextAutoSize.StepBased`. Screenshot at ~200sp, lush-green.
5. **Task 5 — `BentoGrid` + `BentoTile`** — `LazyVerticalGrid` + `GridItemSpan` six-span system (Hero/Wide/Half/Tall/Quad/FullBleed); Reveal Stagger-rise entry (`graphicsLayer` translationY+alpha, 50ms/item). Enforce one Hero. Screenshot: a sample bento wall.
6. **Task 6 — `HealthRing`** — `RoundedPolygon`/`Morph` (graphics-shapes): Cookie9Sided→circle (all good) vs spikier (fault), `animateFloat` in `graphicsLayer`. Screenshot: good + fault states.
7. **Task 7 — `BreathingDot`** — connection-state pulse (gated `rememberInfiniteTransition`, paused off-screen). Reconcile with existing `ConnectionDot`. Screenshot: connected/connecting/disconnected.
8. **Task 8 — `GixxerIcons` glyph set** *(taste-heavy)* — bespoke 24dp / 2dp-stroke moto glyphs as `ImageVector`: maneuver arrows (slight/sharp L/R, U-turn, roundabout-exit, merge, fork, destination), fuel-drop, lean-wedge, bike-health badge, BLE chevron, telemetry micro-glyphs. Build incrementally; screenshot a glyph sheet; **review with owner.**
9. **Task 9 — `SoulBike`** *(taste-heavy, owner-in-loop)* — hand-authored layered Compose vector of the Gixxer SF 150 in Glass Sparkle Black + Metallic Lush Green, traced from a real reference photo (sourced live). States: asleep/cold ↔ awake/breathing (rim-light), rev on long-press. Built iteratively in the **visual companion** with owner feedback; tune the exact green to the photo. Code-only fallback documented (numeral + route-art + health ring) if quality bar isn't met. Screenshot states.
10. **Task 10 — `TraceChart`** — self-drawing path via `PathMeasure.getSegment` + `Animatable`; long-press `Scrub` (value stick + haptic at markers); lean-ribbon hue variant. Screenshot: static + drawn.
11. **Task 11 — `ShareScene`** — wrapper capturing a composable to a bitmap via `rememberGraphicsLayer` + `toImageBitmap()` (one-shot) for share. Smoke test.
12. **Task 12 — Component gallery screen** (dev-only, under Developer settings) rendering every component for visual regression + on-device review. Screenshot (dark + TARMAC).

**Verification per task:** TDD where there's logic (zone mapping, digit decomposition, span math); Roborazzi screenshot for every visual; controller views each PNG; `:app:testDebugUnitTest` stays green; Konsist hex-lint respected (no raw `Color(0x…)` outside `ui/theme/`).

**Deferred to later:** wiring components into real screens (plan 3); AGSL shader glow enhancements (progressive, API-33+); variable-font weight-pulse animation (hero-only, profile on device).

**Open risks:** soul-bike quality (mitigated by reference + owner review + fallback); graphics-shapes API surface on Compose BOM 2026.05.00 (verify in Task 1); `TextAutoSize.StepBased` availability (verify in Task 4; fallback to manual sizing).
