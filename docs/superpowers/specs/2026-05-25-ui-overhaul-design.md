# GixxerBridge — UI overhaul design

**Date:** 2026-05-25
**Author:** Arjun KR (with Claude)
**Scope:** End-to-end visual / interaction overhaul of the GixxerBridge Android app. Replaces the assembled-by-24-parallel-agents look-and-feel from the May 24 build sprint with a cohesive design system + redesigned key screens.
**Status:** Approved direction. Implementation plan to follow via `writing-plans`.

## Background

GixxerBridge currently works end-to-end (handshake, telemetry, nav passthrough, ride logging) but its visuals were assembled in ~24 parallel agent sessions during the May 24 build sprint. The UI audit (`/tmp/ui-audit-current-state.md`) found:

- 30 files hardcode Tailwind hex (`Color(0xFF94A3B8)`) instead of going through `MaterialTheme.colorScheme`, so the user-pickable accent picker only repaints buttons.
- Four distinct reds (`BrandRed`, `colorScheme.error`, `#7F1D1D`, `#B00020`) in circulation.
- Home has 9 cards before the start button.
- Dashboard shows 4 copies of "Start the service…" when disconnected.
- Settings is a 14-section LazyColumn dumping ground.
- Emoji usage (`🔥` on ride streak) that the redesign should kill.

Three research streams informed this design:
1. Internal audit — `/tmp/ui-audit-current-state.md`
2. Design inspiration (Airbnb, Linear, Strava, Apple Fitness + moto-app competitive scan) — `/tmp/ui-research-inspiration.md`
3. Compose implementation patterns — `/tmp/ui-research-implementation.md`

## North star

**Premium and Fast.** Two words, both load-bearing.

- **Premium** — feels expensive, considered, no childish elements. Type system has rhythm. Motion has weight, not bounce. Color is restrained, not noisy. References: Airbnb 2025, Linear, Things 3.
- **Fast** — 120 Hz smooth on Arjun's K20 Pro (6 GB RAM). Every interaction lands under 100 ms. Animations complete under 300 ms. Cold start under 1 s.

Hard rule: **icons only, no emojis**. Every emoji in the codebase becomes a Material Symbol.

## Visual system

### Color tokens

Foundation: **Linear-style warm-neutral dark stack**. Single source of truth at `ui/theme/GixxerTokens.kt`; every screen references it via `MaterialTheme.colorScheme` or `GixxerTokens.*`. Lint rule forbids `Color(0xFF...)` outside `ui/theme/`.

| Token | Hex | Use |
|---|---|---|
| `bg` | `#0A0A0A` | Body background |
| `surface` | `#161616` | Card surface |
| `surfaceElevated` | `#222222` | Sheets, modals, raised cards |
| `border` | `rgba(255,255,255,0.08)` | Hairline dividers |
| `textPrimary` | `#FAFAFA` | All headlines + body |
| `textMuted` | `#A1A1AA` | Single muted grey for captions, labels, secondary text |

Brand accent — **two-tier red** (intensity reserved for rare moments):

| Token | Hex | Use |
|---|---|---|
| `accent` | `#B91C1C` | Everyday accent: active tab indicator, connected dot, button outlines, link text. Cherry-deep, ~1-3% surface coverage. |
| `accentHero` | `#D93B25` | Suzuki brand red. **One use only**: the speed-display underline ticker that fills 0→100 % across each 5 s BLE poll interval. Earns its intensity by being rare. |

Semantic colors — **locked, not interchangeable** with accents:

| Token | Hex | Use |
|---|---|---|
| `success` | `#22C55E` | Connected, ride complete |
| `warning` | `#F59E0B` | Degraded connection, service due soon |
| `danger` | `#EF4444` | Overdue service, crash detected, errors |

Migration path: every file in `ui/` with a `Color(0xFF...)` literal gets reviewed and either token-swapped or moved into `GixxerTokens.kt` as a new semantic name. Lint rule added to CI to prevent regressions.

### Typography

Fonts: **Inter** for chrome + **Geist Mono** for all numerics. Both via Google Fonts downloadable provider (saves ~400 KB bundle vs bundled `.ttf`). Bundled fallback for first-paint.

Type scale — opinionated weights (Airbnb 2025 "modest 500/600, not heavy 700/800" except hero):

| Style | Size | Font | Weight | Features | Use |
|---|---|---|---|---|---|
| `display` | 144 sp | Geist Mono | 700 | `tnum` | Live speed only |
| `headline` | 48 sp | Inter | 600 | — | Screen titles, post-ride summary numbers |
| `title` | 20 sp | Inter | 600 | — | Card titles, section headers |
| `body` | 14 sp | Inter | 400 | — | Most text |
| `caption` | 12 sp | Inter | 500 | — | Labels, sub-text |
| `monoBody` | 14 sp | Geist Mono | 400 | `tnum` | Odo, trip km, fuel range — any digit that ticks |

**Tabular numerics mandatory** (`fontFeatureSettings = "tnum"`) on every ticking digit. Current implementation jitters layout on every frame change.

### Spacing & shape

- Spacing scale (only these values): `4 / 8 / 12 / 16 / 24 / 32 / 48 / 64`. No `10dp`, no `14dp`, no `18dp`.
- Corner radii: cards `16dp`, chips `8dp`, sheets `28dp`, full-bleed dialogs `0dp`. One scale, consistent.
- Card elevation: **0 by default** (no shadow). `Modifier.dropShadow` (Compose 1.9+) only on the one hero card per screen.

### Motion

**Material 3 Expressive spring physics. Never duration-based tween.** Two named springs cover ~all UI state:

```kotlin
val SpringStandard = spring<Float>(dampingRatio = 0.85f, stiffness = 400f)
val SpringSoft = spring<Float>(dampingRatio = 0.75f, stiffness = 200f)
```

- `SpringStandard` — most state transitions (color, size, position, alpha)
- `SpringSoft` — sheet open, big number reveals, post-ride summary

**Hero motion reserved for one moment per session**: the post-ride summary sequence. Spring number reveals + Canvas micro-animations + haptic confirmations. Everywhere else: *motion communicates state, not decoration*.

Forbidden in implementation:
- `tween()` for any UI state — replace with named springs
- `infiniteRepeatable` for any non-loading purpose
- `Crossfade` (use `AnimatedContent` with spring)

### Icons

**Material Symbols only.** Google's icon set, free, M3-native, three weights × two fills.

- Weight 400 default, weight 700 for selected nav items
- Outlined fill default, filled fill for selected/active states
- 24 dp default, 20 dp inline-with-text, 64 dp for empty-state hero icons

Library: `androidx.compose.material:material-icons-extended` already in `app/build.gradle.kts`. Direct usage: `Icons.Filled.Speed`, `Icons.Outlined.LocalGasStation`, etc.

**Zero emojis in source code.** Audit found `🔥` on the ride-streak text; all instances replaced with a Material Symbol (e.g. `Icons.Outlined.LocalFireDepartment`). Lint rule forbids emoji codepoints in string resources.

## Hero design moves

The six moves that, executed well, make GixxerBridge unmistakably distinct from "another moto app":

### 1. Speed display

Single tabular figure at 144 sp Geist Mono weight 700 on `#0A0A0A`. **No pill, no gradient, no glow.** A 4 px `accentHero` (`#D93B25`) underline ticker fills 0→100 % across the 5 s BLE poll interval as a silent freshness indicator — the rider sees at a glance whether the value is stale.

State variants:
- Connected, value present: tabular number + ticker animating
- Connecting: number greys to `textMuted`, ticker freezes
- Disconnected: `—` placeholder, ticker hidden

### 2. Active-ride layout

When the accelerometer reports sustained motion (> 5 km/h equivalent for > 3 s), the app auto-enters **active-ride mode**:

- All chrome hides: bottom nav, status bar, screen title
- Speed dominates the upper two-thirds of the screen
- One rider-chosen contextual metric below on pure `#000`: choose from Trip A km, fuel bars, ETA to next maneuver, current road type
- Single tap dismisses back to normal mode; auto-exits 30 s after motion stops

Requires `BodySensors` permission. Add to manifest + onboarding flow ask. Single state machine in a new `ui/active/ActiveRideController.kt`.

### 3. Connection state — 12 dp shape-morphing dot

Replaces every OEM app's verbose "Connected via Bluetooth · MAC: 74:..." status row. Lives in the AppShell top-bar slot.

| State | Shape | Color | Motion |
|---|---|---|---|
| Idle | hollow circle | `textMuted` | static |
| Connecting | circle | `accent` | pulse (spring) |
| Discovering | rounded square | `warning` | shape-morph from circle |
| Ready | filled circle | `success` | shape-morph to circle |
| Disconnected | circle | `textMuted` | one slow pulse then static |
| Failed | filled circle | `danger` | brief shake (spring) |

Hand-rolled with `Animatable` — do not pin M3 Expressive alpha just for this.

### 4. Post-ride summary

The one place motion goes big. After every saved ride: a Spotify-Wrapped-style 4-card sequence on a full-screen sheet.

Cards:
1. **Distance + duration** — distance ticks up with `SpringSoft` over 800 ms
2. **Speed** — avg and max, with a sparkline of the per-second speed samples
3. **Fuel & range** — bars used, km/L, range remaining
4. **Map + share** — GPS polyline (when GPS data available) + PNG share button

Spring number reveals. Haptic confirmation on each card transition. Shareable as PNG via FileProvider.

### 5. Bike health — single 96 dp arc

Replaces the current three-sub-score concentric rings. **One arc**, color shifts on a `success → warning → danger` gradient. Tap to expand into the per-service breakdown (the existing per-item editor from the May 25 `feat(maintenance)` commit).

Computation unchanged — uses `ServiceSchedule.healthFor()`. UI representation simplifies to one arc + one number + one caption (worst item name + km/days left).

### 6. Empty states — single tasteful CTA

Current Dashboard shows 4 copies of "Start the service…" when disconnected. New rule: **one empty-state component per screen**:

- 64 dp Material Symbol weight 200 (centered, `textMuted`)
- One line of body copy explaining what the screen will show
- One outlined button — the action that fills it

Same pattern in: Dashboard (no telemetry), Trips (no rides), Stats (no data), Diagnostics (no log entries).

## Screen priority — rollout shape

**Home as a vertical-slice prototype** (decision locked). The tokens come into existence as part of redesigning Home, not in a separate token-only PR. Visible win ships fast; the tokens get refined under live use; small rework cost is acceptable.

### Wave 1 — vertical slice (Home end-to-end)

Sprint 1, ~2-3 days of work:

- New `ui/theme/GixxerTokens.kt` — all tokens defined
- New `ui/theme/Type.kt` — Inter + Geist Mono via Google Fonts
- New `ui/theme/Motion.kt` — `SpringStandard` + `SpringSoft`
- **Home rebuild**: rip the 9 cards. New 3-zone layout:
  1. **Top zone** — shape-morphing connection dot + rider name + accent-ticker if a ride is active
  2. **Today zone** — single hero card: today's km / streak / next service due (one card, three caption lines, no separate sub-cards)
  3. **Quick actions** — single row of 3 outlined icon buttons: Start ride / Open nav / Pair
- Apply tokens to `ClusterPreview` (kept structurally; retokenized colors)
- Speed display component implemented (used by Home preview + Dashboard + Active-ride mode)

Exit gates for wave 1:
- Token swap rule enforceable (lint rule live)
- Home screen renders end-to-end at 60+ fps on the K20 Pro
- No `tween()` calls left in Home
- No hardcoded hex in Home
- Snapshot test for Home (golden image checked into repo)

### Wave 2 — Dashboard + Active-ride

Sprint 2:
- Speed-first Dashboard (collapses disconnected state to single empty state)
- Active-ride layout + accelerometer hook + `BodySensors` permission
- One metric chooser (Settings → Cluster → "Active-ride bottom metric")

### Wave 3 — data screens

Sprint 3:
- Stats (chart family retokenized; layout mostly survives)
- Trips list + Trip detail
- Post-ride summary sheet (the hero motion moment)

### Wave 4 — system & settings

Sprint 4:
- Settings split: the 14-section dumping ground becomes 5 sub-routes — `Bike`, `Cluster`, `Notifications`, `Maintenance`, `Developer`. Each is its own composable.
- Pairing (already 80 % there) — token swap only
- Diagnostics (fine as-is) — token swap only
- Onboarding StepScaffold — token swap, structure unchanged

### Wave 5 — SOS + edge cases

Sprint 5:
- SOS screen redesign
- Crash-prompt redesign
- App-lock biometric flow polish

## What we keep

Audited as on-brand and worth preserving:
- `ClusterPreview` Canvas component (`ui/cluster/ClusterPreview.kt`) — Canvas maneuver arrows + pulsing dot. Retokenize colors, keep structure.
- `GixxerMono` numeric discipline — swap font (`GixxerMono` → Geist Mono via downloadable Google Fonts), keep the rule that all live numbers use mono.
- The 4-chart family (sparkline / bar / histogram / calendar heatmap) — shared 140 dp baseline grid is good. Token swap only.
- Pairing screen status overlay modal — best-built modal in the app.
- Onboarding StepScaffold structure — only the visual tokens change.

## What we kill

- Every hardcoded hex colour. ~30 files (audit identified them). Single sweep.
- Every emoji. `🔥` on Home today; the audit's full scan will surface others.
- Settings dumping-ground structure.
- 9-card Home stack.
- 4 reds in circulation → consolidate to `accent` + `accentHero` + `danger`.
- Decorative card shadows — only the hero card per screen keeps elevation.
- All `tween()` animations — replace with named springs.
- All `Crossfade` calls — replace with `AnimatedContent` + spring.

## Tooling additions

Per the implementation research report:

- Compose BOM: `2024.12.01 → 2026.05.00` (unlocks `Modifier.dropShadow` + mature `SharedTransitionLayout`)
- Add **Haze 1.6** (`dev.chrisbanes.haze:haze` + `haze-materials`) — backdrop blur for the status bar over scrolling content. ~200 KB.
- Add **Coil 3** (`io.coil-kt.coil3:coil-compose:3.0.x`) — async image loading for weather icons, future map tiles.
- Add **Google Fonts** downloadable provider (`androidx.compose.ui:ui-text-google-fonts`) for Inter + Geist Mono.
- **Defer Material 3 Expressive** (still alpha as of May 2026) — adopt its principles (spring physics, modest weights, shape-morph) without pinning the alpha library.

## Risks / unknowns

1. **M3 Expressive is alpha** — shape-morphing dot is hand-rolled with `Animatable` to avoid pinning the alpha.
2. **Geist Mono downloadable** — first-paint delay possible; mitigate with `provider` async + bundled `Roboto Mono` fallback for the first frame.
3. **Active-ride layout** — `BodySensors` permission must land in the onboarding flow gracefully; rejected permission must degrade to a manual toggle, not crash.
4. **Two-tier red interaction with semantic danger** — visual QA must mock `accent` + `accentHero` + `danger` on the same surface (e.g. ride-card with overdue-service banner) before locking.
5. **Suzuki red trademark** — `#D93B25` matches Suzuki's brand. This is a personal-use app on Arjun's own bike for educational reverse-engineering; not distributed. No trademark concern in scope.

## Out of scope (deferred to post-overhaul fix wave)

- Nav-arrow classification accuracy — Ride 11 (`/tmp/ride-2km/`) showed some arrows wrong. Needs classification-decision logging + per-maneuver review.
- GPS sample rate — Ride 11 only captured 2 GPS points; should be 1 / sec. Permission or `RideLocationTracker` config issue.
- Cluster MAC history JSONL hook — file was empty after 2 km ride. Name-match filter may not be firing.

These three fixes happen after the overhaul ships.

## Approval gate

Before invoking `writing-plans`:
1. Arjun reviews this spec end-to-end.
2. Confirms the visual tokens (especially the two-tier red intensity vs warning amber interaction).
3. Confirms the wave-1 scope is what he wants to ship first.

Once approved → `writing-plans` skill drafts the wave-1 implementation plan.

## Implementation deviations

Deviations from this spec discovered during the 2026-05-25 implementation:

- **Active-ride trigger**: the spec called for `BodySensors` permission + accelerometer-based motion detection (> 5 km/h equivalent for > 3 s). The shipped implementation uses **BLE-speed gating** instead — `telemetry.latest.speedKmh > 5` for 3 consecutive BLE poll intervals. Rationale: no extra permission needed, only fires when this specific bike is paired and moving (no false-trigger in a car/train), and automatically off when disconnected. `BodySensors` permission was not added to the manifest.
