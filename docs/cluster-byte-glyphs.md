# Suzuki cluster byte → on-cluster glyph table

This table is filled in **empirically** by running the dev Maneuver Sweep
(`ManeuverSweepScreen`) on the bike with the cluster powered on, sending each
byte in turn, and recording what the cluster ROM actually renders.

**Authoritative source:** the rendered cluster glyph itself. Photo
documentation lives at `captures/cluster-glyphs/byte-NN.jpg` (gitignored).

**OEM translation source:** `A0.C()` at
`decompiled/jadx-retry/sources/com/suzuki/application/fragment/A0.java:458`
produces these cluster bytes from Mappls maneuver IDs. See also
`docs/superpowers/specs/2026-05-25-maneuver-id-rework-design.md`.

## Coverage so far

| Cluster byte | OEM-known Mappls source IDs | Cluster renders | Notes |
|-------------:|:----------------------------|:----------------|:------|
| 1            | Mappls 0 (turn left 90°)    | _empty_         | _to fill in_ |
| 2            | Mappls 1 (slight left)      | _empty_         | _to fill in_ |
| 3            | Mappls 2 (sharp left)       | _empty_         | _to fill in_ |
| 4            | Mappls 3 (turn right 90°)   | _empty_         | _to fill in_ |
| 5            | Mappls 4 (slight right)     | _empty_         | _to fill in_ |
| 6            | Mappls 5 (sharp right)      | _empty_         | _to fill in_ |
| 7            | Mappls 6 (u-turn)           | _empty_         | _to fill in_ |
| 8            | Mappls 7 (straight/head)    | _empty_         | _to fill in_ |
| 9            | Mappls 8,9,10 (destination/arrival cluster) | _empty_ | _to fill in_ |
| 10           | Mappls 75 (highway exit right) | _empty_      | _to fill in_ |
| 11           | Mappls 11 (keep left)       | _empty_         | _to fill in_ |
| 12           | Mappls 12 (keep right)      | _empty_         | _to fill in_ |
| 13           | Mappls 13 (T-junction left) | _empty_         | _to fill in_ |
| 14           | Mappls 14 (T-junction right) | _empty_        | _to fill in_ |
| 15           | Mappls 53 (compass SE)      | _empty_         | _to fill in_ |
| 16           | Mappls 54 (compass S)       | _empty_         | _to fill in_ |
| 17           | Mappls 55 (compass SW)      | _empty_         | _to fill in_ |
| 18           | Mappls 56 (compass W)       | _empty_         | _to fill in_ |
| 19           | Mappls 57 (compass NW)      | _empty_         | _to fill in_ |
| 20           | Mappls 65 (roundabout SE CCW) | _empty_       | _to fill in_ |
| 21           | Mappls 66 (roundabout W CCW)  | _empty_       | _to fill in_ |
| 22           | Mappls 67 (roundabout NE CCW) | _empty_       | _to fill in_ |
| 23           | Mappls 68 (roundabout N CCW)  | _empty_       | _to fill in_ |
| 24           | Mappls 69 (roundabout NE high CCW) | _empty_  | _to fill in_ |
| 25           | Mappls 70 (roundabout E CCW)  | _empty_       | _to fill in_ |
| 26           | Mappls 71 (roundabout SE CCW alt) | _empty_   | _to fill in_ |
| 27           | Mappls 19 (merge left)        | _empty_       | _to fill in_ |
| 28           | Mappls 20 (merge right)       | _empty_       | _to fill in_ |
| 29           | Mappls 17 (exit left)         | _empty_       | _to fill in_ |
| 30           | Mappls 18 (exit right)        | _empty_       | _to fill in_ |
| 31           | Mappls 15, 26-28 (fork left) | _empty_        | _to fill in_ |
| 32           | Mappls 16, 30, 31 (fork right) | _empty_      | _to fill in_ |
| 33           | Mappls 21 (straight w/ crossbar) | _empty_    | _to fill in_ |
| 34           | Mappls 22 (u-turn wide left)  | _empty_       | _to fill in_ |
| 35           | Mappls 23 (slight left ramp)  | _empty_       | _to fill in_ |
| 36           | Mappls 24 (u-turn wide right) | _empty_       | _to fill in_ |
| 37           | Mappls 25 (slight right ramp) | _empty_       | _to fill in_ |
| 38           | Mappls 73 (motorway exit left) — Burgman: also 74 | _empty_ | _to fill in_ |
| 39           | Mappls 41 (u-turn right)      | _empty_       | _to fill in_ |
| 40           | Mappls 50 (depart N)          | _empty_       | _to fill in_ |
| 41           | Mappls 51 (depart NE)         | _empty_       | _to fill in_ |
| 42           | Mappls 52 (depart E)          | _empty_       | _to fill in_ |
| 44           | Mappls 74 (motorway exit right) — Burgman: 58 also | _empty_ | _to fill in_ |
| 45           | Mappls 72 (roundabout generic) | _empty_      | _to fill in_ |
| 46           | Mappls 58 (roundabout SE CW)  | _empty_       | _to fill in_ |
| 47           | Mappls 59 (roundabout E CW)   | _empty_       | _to fill in_ |
| 48           | Mappls 60 (roundabout NE high CW) | _empty_   | _to fill in_ |
| 49           | Mappls 61 (roundabout N CW)   | _empty_       | _to fill in_ |
| 50           | Mappls 62 (roundabout W CW)   | _empty_       | _to fill in_ |
| 51           | Mappls 63 (roundabout SW CW)  | _empty_       | _to fill in_ |
| 52           | Mappls 64 (roundabout SW CW alt) | _empty_    | _to fill in_ |

Cluster bytes 43 and 53+ are not produced by any Mappls ID in the OEM default
branch. Sweep them too; they may render distinct glyphs (cluster ROM has its
own slots independent of Mappls coverage).
