# Authoritative Mappls maneuver-id → Suzuki cluster icon table

Source: `apk/base.apk:res/drawable-nodpi-v4/ic_step_*.xml`. The Suzuki
Connect app's `C0897z.java:81` resolves `step_<N>` drawable at runtime
via `getIdentifier("step_" + bVar.h, "drawable", …)` where `bVar.h` is the
integer maneuver-id from the Mappls SDK. Whatever `ic_step_N.xml` exists in
the APK is exactly what the cluster renders — no other mapping layer.

Decoded with: androguard 4.1.3 (`androguard.core.axml.AXMLPrinter`).
Rendered with: rsvg-convert (librsvg) from hand-converted SVG.
PNG outputs at `/tmp/step-icons/png/ic_step_N.png` (not checked in).
Analysis date: 2026-05-25.

## Icon table

All icons use a 300×300 viewport, white-on-transparent strokes rendered on a
dark cluster background. "Direction" is described as if the vehicle is heading
upward on screen.

| ID | Visual description | Maneuver semantic | Confidence | Text-mapping recommendation |
|----|--------------------|-------------------|------------|-----------------------------|
| 0 | L-shaped arrow: stem goes right then turns up-left | Turn left (90°) | high | primary left turn |
| 1 | Diagonal arrow pointing to lower-left with hook at end | Slight left / bearing-left | high | "slight left", "bear left" |
| 2 | Diagonal arrow pointing upper-left, sharp corner | Sharp left / hard left | high | "sharp left" |
| 3 | L-shaped arrow: stem goes left then turns up-right | Turn right (90°) | high | primary right turn |
| 4 | Diagonal arrow pointing to lower-right with hook | Slight right / bearing-right | high | "slight right", "bear right" |
| 5 | Diagonal arrow pointing upper-right, sharp corner | Sharp right / hard right | high | "sharp right" |
| 6 | Downward U-loop curving through the left side | U-turn left (standard) | high | "u-turn" when turning left |
| 7 | Simple vertical up-arrow | Straight ahead / head / continue | high | "continue", "straight", "head" |
| 8 | Hollow circle (ring) — no direction | GPS position marker / generic | high | **GENERIC_ARROW fallback** — verified: NOT a forward arrow; it is a circle. See note below. |
| 10 | Two vertical lines (road lanes) + diagonal-right arrow from right lane | Fork right / take right branch on divided road | high | "take exit right", "take ramp right" |
| 11 | Horizontal left-pointing arrow + vertical line on right | Keep left (lane) | high | "keep left", "stay left" |
| 12 | Horizontal right-pointing arrow + vertical line on left | Keep right (lane) | high | "keep right", "stay right" |
| 13 | T-intersection: left-pointing arrow + T-bar | Turn left at T-junction | high | "turn left" at junction where only left is valid |
| 14 | T-intersection: right-pointing arrow + T-bar | Turn right at T-junction | high | "turn right" at junction where only right is valid |
| 15 | Y-fork icon, left branch highlighted | Fork / keep left at Y-split | high | "keep left", "fork left", "take the left fork" |
| 16 | Y-fork icon, right branch highlighted | Fork / keep right at Y-split | high | "keep right", "fork right", "take the right fork" |
| 17 | Diagonal lower-left arrow + vertical line on right | Exit left / off-ramp left | high | "exit left", "take exit on left" |
| 18 | Diagonal lower-right arrow + vertical line on left | Exit right / off-ramp right | high | "exit right", "take exit on right" |
| 19 | Diagonal upper-left merging into vertical line on right | Merge left onto road from ramp | high | "merge left", "on-ramp merging left" |
| 20 | Diagonal upper-right merging into vertical line on left | Merge right onto road from ramp | high | "merge right", "on-ramp merging right" |
| 21 | Up-arrow with horizontal crossbar at bottom | Straight ahead past an intersection / cross junction | high | "continue straight", "go straight at intersection" |
| 22 | Wide leftward U-curve with arrow at lower-left | U-turn left (wide / slow) | high | "make a u-turn" (alternative to 6 if turning left slowly) |
| 23 | Up-left diagonal with curved tail dipping right | Slight left with a drift-right at end — appears to be a specific ramp or fork variant | medium | "slight left" variant; prefer 1 for plain slight-left |
| 24 | Wide rightward U-curve with arrow at lower-right | U-turn right (wide / slow) | high | "make a u-turn" when direction is right |
| 25 | Up-right diagonal with curved tail dipping left | Slight right with a drift-left at end — ramp or fork variant | medium | "slight right" variant; prefer 4 for plain slight-right |
| 36 | Boat / ferry top-down silhouette with wave line | Ferry crossing | high | "take ferry", "board ferry" |
| 37 | Blue dome + white circle on blue field | Tunnel / underpass indicator | high | "enter tunnel", "go through tunnel" |
| 40 | Hollow white circle + blue horizontal line segment | Waypoint / via-point marker | high | intermediate waypoint arrival |
| 41 | Rightward U-loop curving through the left side | U-turn right (standard) | high | "u-turn" when turning right |
| 50 | Compass rose (8-point star) + straight up-arrow | Head north / depart going straight (compass-mode) | high | "head north", "depart" (straight) |
| 51 | Compass rose + diagonal NE arrow | Head north-east | high | "head northeast", "depart" (NE) |
| 52 | Compass rose + right-pointing arrow | Head east / depart going right | high | "head east", "depart" (east) |
| 53 | Compass rose + diagonal SE arrow | Head south-east | high | "head southeast" |
| 54 | Compass rose + down-pointing arrow | Head south | high | "head south" |
| 55 | Compass rose + diagonal SW arrow | Head south-west | high | "head southwest" |
| 56 | Compass rose + left-pointing arrow | Head west | high | "head west" |
| 57 | Compass rose + diagonal NW arrow | Head north-west | high | "head northwest" |
| 58 | Small ring (roundabout) + SE exit arrow, CW rotation | Roundabout: take exit to the right/SE | high | roundabout with 1st exit (right) |
| 59 | Small ring + right/E exit arrow, CW | Roundabout: take exit right | high | roundabout 1st exit (immediate right) |
| 60 | Small ring + NE exit arrow, CW, looping up | Roundabout: take exit upper-right (U-turn style) | high | roundabout last/far exit (near U-turn) |
| 61 | Small ring + up/N exit arrow, CW | Roundabout: go straight through | high | roundabout straight-through |
| 62 | Small ring + left/W exit arrow, CCW rotation | Roundabout: take exit left | high | CCW roundabout exit left |
| 63 | Small ring + SW exit arrow, CCW (identical to 64) | Roundabout: exit SW, CCW | high | CCW roundabout exit lower-left |
| 64 | Small ring + SW exit arrow, CCW (identical to 63) | Roundabout: exit SW, CCW (duplicate drawable) | high | same as 63; Mappls probably treats these as two exit counts |
| 65 | Small ring + SE exit arrow, CCW | Roundabout: exit SE, CCW | high | CCW roundabout first exit right |
| 66 | Small ring + left/W exit arrow, CCW | Roundabout: exit left, CCW | high | CCW roundabout exit left |
| 67 | Small ring + NE exit arrow, CCW | Roundabout: exit upper-right, CCW | high | CCW roundabout far-right exit |
| 68 | Small ring + up/N arrow, CCW | Roundabout: go straight, CCW | high | CCW roundabout straight-through |
| 69 | Small ring + NE exit arrow, CW, high arc (identical geometry to 60) | Roundabout: exit upper-right, CW (alternate count) | high | CW roundabout far exit |
| 70 | Small ring + right/E exit arrow, CCW | Roundabout: exit right, CCW | high | CCW roundabout 1st right exit |
| 71 | Small ring + SE exit arrow, CCW | Roundabout: exit lower-right, CCW | high | generic CCW roundabout exit — best single choice for "roundabout" fallback |
| 72 | Three curved arrows forming a clockwise loop | Roundabout generic symbol | high | "roundabout" with no exit information |
| 73 | Two vertical lines + diagonal upper-left arrow (identical to 74) | Left exit on dual-carriageway / motorway left | high | "take left exit", motorway left off-ramp |
| 74 | Two vertical lines + diagonal upper-left arrow (identical to 73) | Left exit on dual-carriageway (duplicate drawable) | high | same as 73 |
| 75 | Two vertical lines + diagonal upper-right arrow | Right exit on dual-carriageway / motorway right | high | "take right exit", motorway right off-ramp |

## Confidence summary

- High: 52 of 55
- Medium: 3 of 55 (IDs 8, 23, 25 — see notes)
- Low: 0

## IDs NOT in the APK (no cluster rendering)

IDs **9, 26–35, 38–39, 42–49** — these gaps have no drawable. Sending any of
these maneuver IDs causes the cluster to fall back to a generic icon or render
nothing. Do not use them in `ManeuverMap.kt`.

## Key notes and corrections to prior assumptions

### ID 8 — NOT a generic forward arrow (prior code was WRONG)

`GENERIC_ARROW = 8` in the old `ManeuverMap.kt` was assumed to be a "generic
forward arrow". It is actually a hollow circle — the GPS position marker /
waypoint dot. Visually it has no directional component at all.

The actual straight-ahead / forward arrow is **ID 7** (simple up-arrow, single
path from y=27 to y=280 + arrowhead).

Recommendation: change `GENERIC_ARROW` to 7.

### ID 6 vs 41 — U-turn left vs U-turn right

Prior code used 23 for u-turn. Both 6 (left U) and 41 (right U) are correct
u-turn icons. The old code used 23 which is a "slight left with ramp tail" —
wrong semantic.

### IDs 4 and 5 — Slight/Sharp swap confirmed

Prior code: `"sharp left" -> 4`, `"sharp right" -> 5`.
Rendered icons: ID 4 is a lower-right hook (slight-right bearing), ID 5 is
an upper-right diagonal (sharp-right). This means 4 is **slight-right** and 5
is **sharp-right** in the icon set. Prior assignments of 4→sharp-left and
5→sharp-right were partially wrong.

Correct mapping from rendered geometry:
- 1 = slight left
- 2 = sharp left
- 4 = slight right
- 5 = sharp right

### IDs 11 and 12 — Keep left / Keep right

Prior code: `"keep left" -> 20`, `"keep right" -> 21`.
Rendered icons: ID 11 is a left-facing horizontal arrow with a right-side
vertical bar (keep left / T-left), ID 12 is a right-facing arrow with left
vertical bar (keep right). IDs 20 and 21 are merge-right and
straight-with-crossbar respectively — different semantics.

Correct: keep-left = 11, keep-right = 12.

### ID 21 — Straight with crossbar, not "keep right"

Prior code assigned 21 to `"keep right"`. ID 21 is actually an up-arrow with
a horizontal bar across the lower portion — semantically "continue straight
past a junction" (don't turn).

### IDs 20 and 19 — Merge, not keep-lane

IDs 19 and 20 show diagonal merge arrows (diagonal merging into a vertical
highway line). These are on-ramp merge icons, not keep-lane icons.

### IDs 50–57 — Compass-mode departure icons

These were assumed to be "destination" icons (prior code mapped 50 to
"arrive/destination"). They are actually 8-directional "head north/east/etc."
compass icons for the start-of-route departure. ID 50 = head north (up).

### IDs 36 and 37 — Ferry and Tunnel

Not navigation turn arrows at all. 36 = ferry, 37 = tunnel. Previously unknown.

### IDs 73/74 and 75 — Motorway exit arrows (dual carriageway)

73 and 74 are identical left-exit drawables on a two-lane road (Mappls
presumably uses both for slightly different scenarios but renders the same
graphic). 75 is the mirror for right exits. These are better for highway
off-ramp text than IDs 17/18 (which are more generic ramp icons).

### ID 72 — Roundabout generic (3-arrow symbol)

This is the cleanest single "roundabout" icon — three curved arrows in a
circle. Good for generic roundabout text when no exit-count is known.

### IDs 58–71 — Roundabout directional set

14 roundabout icons covering CW and CCW rotations with 7 exit directions each.
IDs 63 and 64 are byte-for-byte identical drawables (Mappls may differentiate
them by exit count at the API level but the cluster renders the same graphic).
