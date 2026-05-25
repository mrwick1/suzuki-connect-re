# GixxerBridge — Engineering Notes

> Things you need to know to work in this codebase. Distinct from the protocol spec (NOTES.md) and the project journal (DISCOVERIES.md). Last updated: 2026-05-25.

---

## Design system summary

All visual tokens live in `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/`:

- **`GixxerTokens.kt`** — color tokens. Linear-style warm-neutral dark stack (`bg=#0A0A0A`, `surface=#161616`, `surfaceElevated=#222222`) plus two-tier red:
  - `accent=#B91C1C` — everyday use (active tab, connected dot, button outlines). 1–3% surface coverage max.
  - `accentHero=#D93B25` — one use only: the speed-display underline ticker.
  - Semantic colors (`success`, `warning`, `danger`) are locked and not interchangeable with accent.
- **`GixxerFonts.kt`** — Inter (chrome) + Geist Mono (all ticking numerics) via downloadable Google Fonts provider. Bundled Roboto Mono as first-paint fallback.
- **`Motion.kt`** — two named springs:
  - `SpringStandard = spring<Float>(dampingRatio=0.85f, stiffness=400f)` — most state transitions.
  - `SpringSoft = spring<Float>(dampingRatio=0.75f, stiffness=200f)` — sheet open, big number reveals.
  - No `tween()` for UI state. No `infiniteRepeatable` except loaders.

**Icon rule**: Material Symbols only (`material-icons-extended`). Outlined fill default, filled for selected/active. No emoji anywhere in source. A Konsist lint test (`test/konsist/NoHardcodedHexTest.kt`) enforces no `Color(0xFF...)` literals outside `ui/theme/`.

**No-emoji rule**: enforced by lint. Every emoji must be replaced with a Material Symbol before commit.

---

## Compose gotchas

These bit us during the 2026-05-25 overhaul. Check here before spending time debugging.

**`Icons.Outlined.*` import style matters.** Extension properties for outlined-variant icons live in the package `androidx.compose.material.icons.outlined`. An inline FQN like `androidx.compose.material.icons.Icons.Outlined.WbSunny` does NOT resolve at compile time. You must use a package-level import:

```kotlin
import androidx.compose.material.icons.outlined.WbSunny
// then: Icons.Outlined.WbSunny
```

**`AnimatedVisibility` rejects `SpringStandard` directly.** Enter/exit specs require `FiniteAnimationSpec`. `Motion.SpringStandard` is typed as `AnimationSpec<Float>`, which is not `FiniteAnimationSpec`, so the compiler rejects it there. Workaround: use `tween(200)` inline at each `AnimatedVisibility` site and leave a comment that springs can't be used here. Do not try to cast or wrap — the type system is intentional.

**`animateDpAsState` also rejects `AnimationSpec<Float>`.** It needs `AnimationSpec<Dp>`. Use a matching inline spring instead:

```kotlin
animateDpAsState(targetValue = x, animationSpec = spring<Dp>(dampingRatio=0.85f, stiffness=400f))
```

**Nested `/**` inside KDoc crashes the lexer.** If you write a path like `ui/home/**` inside a KDoc comment (`/** ... */`), the `**` is parsed as a nested block-comment opener. The file compiles until end-of-file, then throws "Unclosed comment". Use backtick-escaped paths (`\`ui/home/\``) or restructure the prose to avoid `**` in KDoc.

**Compose BOM `2024.12.01 → 2026.05.00`** is a clean version bump. You will see `LocalLifecycleOwner` deprecation warnings in affected composables — they don't break the build and the migration to `LocalContext` + `lifecycleOwner` is low priority.

---

## Toolchain pins

**JDK must be 17, not system default.** Arch's system Java is JDK 26 (as of 2026-05-25). Gradle 8.11.1's embedded Kotlin compiler throws `IllegalArgumentException: Failed to parse version "26.0.1"` inside `JavaVersion.parse` at configuration time, before any compilation. This is pinned in `android/gradle.properties`:

```
org.gradle.java.home=/usr/lib/jvm/java-17-openjdk
```

Do not remove this line. If you see `IllegalArgumentException` from `JavaVersion.parse` in a Gradle sync, the JDK pin has been removed or the path has changed. Verify with `ls /usr/lib/jvm/` and update the path. JDK 17 works; JDK 21 may also work but has not been tested.

---

## Subagent workflow

When dispatching parallel subagents via `isolation: worktree`, follow this checklist or the worktrees are wasted:

- [ ] Agent prompt must explicitly say: **"cd into your assigned worktree before editing any file."** Without this the agent edits the main checkout regardless of worktree assignment.
- [ ] Assign a distinct worktree per agent for any task where two agents touch overlapping files. Shared files require manual cherry-pick/merge after agents complete — the harness will NOT auto-merge.
- [ ] After agents finish, collect their output branches and inspect diffs before merging. Worktree branches are preserved even if agents fail partway through.
- [ ] For independent tasks (non-overlapping files), parallel agents in the same worktree are fine — no isolation needed.
- [ ] For doc-only changes (NOTES.md, DISCOVERIES.md, this file): run sequentially or in the main checkout. Parallel agents on a single Markdown file always conflict.

---

## AppLog usage

`util/AppLog.kt` is the in-process logger. Use it at every BLE site and in the pair flow so issues are debuggable without tethering.

| Call | When to use |
|---|---|
| `AppLog.i(tag, msg)` | Normal lifecycle events (connection state changes, frame received, identity sent) |
| `AppLog.d(tag, msg)` | Verbose detail useful during development (retry counts, raw byte values) |
| `AppLog.w(tag, msg)` | Unexpected-but-recoverable situations (write returned BUSY, scanner callback delayed) |
| `AppLog.e(tag, msg, throwable?)` | Errors and exceptions that break expected flow |

Do NOT call `android.util.Log.*` directly in BLE or pair code — `AppLog` mirrors to logcat anyway, so you lose nothing. Direct `Log.*` calls are acceptable in non-BLE UI code where the ring log overhead is not worth it.

**Never** put PII (bike MAC from `LOCAL_NOTES.md`, rider name, phone number) into AppLog messages — the log file is pulled as plaintext.

---

## Pull commands for on-device artifacts

```bash
# AppLog rolling file (~512 KB, 1 rotation)
adb shell run-as dev.mrwick.gixxerbridge.debug cat files/diag/app.log

# Cluster MAC history (JSONL, capped at 50 entries)
adb shell run-as dev.mrwick.gixxerbridge.debug cat files/diag/cluster-mac-history.jsonl

# Room database (copy to sdcard first — run-as can't pipe binary cleanly)
adb shell run-as dev.mrwick.gixxerbridge.debug cp databases/gixxer.db /sdcard/gixxer-debug.db
adb pull /sdcard/gixxer-debug.db /tmp/gixxer-debug.db
# Then open with any SQLite browser
```

The `run-as dev.mrwick.gixxerbridge.debug` prefix works because the app is a debug build. On a release build you would need root (`adb shell su -c ...` from Termux, since KSU `su` is not on the adb shell PATH — see NOTES.md tooling section).

---

## Reverse-engineering Android binary AXML drawables

### Why this is necessary

APKs ship compiled binary AXML, not text XML. `unzip`-ing `base.apk` and opening a drawable gives raw binary, not readable vector XML. You need a decode step before you can inspect or render icons.

### Toolchain

**Decode binary AXML → text XML:**

```python
from androguard.core.axml import AXMLPrinter   # androguard 4.1.3; note: 3.x used core.bytecodes.axml
import zipfile

with zipfile.ZipFile("apk/base.apk") as z:
    raw = z.read("res/drawable-nodpi-v4/ic_step_7.xml")

xml_text = AXMLPrinter(raw).get_xml()
```

The `core.axml` import path is the 4.x API. The 3.x path (`core.bytecodes.axml`) is gone in 4.x — don't copy old examples verbatim.

**Render to PNG** (via SVG intermediate):

1. Hand-map Android vector XML attributes to SVG equivalents: `android:pathData` → `<path d="..."/>`, `android:fillColor` → `fill`, `android:strokeColor` → `stroke`, `android:strokeWidth` → `stroke-width`, `android:strokeLineCap` → `stroke-linecap`, `android:strokeLineJoin` → `stroke-linejoin`.
2. Wrap in `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 <w> <h>">`.
3. Render: `rsvg-convert -w 128 -h 128 --background-color '#222222' input.svg -o output.png` (requires `librsvg` / `rsvg-convert` — `yay -S librsvg` on Arch).

### aapt2 gotcha: numeric strokeLineCap / strokeLineJoin

Binary AXML stores `strokeLineCap` and `strokeLineJoin` as integers (`0`, `1`, `2`), not string enums. aapt2 (and the Android resource compiler) require the string form when shipping drawables in a new APK. Before copying decoded `ic_step_N.xml` files into `res/drawable/`, sed-replace the numeric values:

| Integer | Attribute | String enum |
|---------|-----------|-------------|
| `0` | `strokeLineCap` | `butt` |
| `1` | `strokeLineCap` | `round` |
| `2` | `strokeLineCap` | `square` |
| `0` | `strokeLineJoin` | `miter` |
| `1` | `strokeLineJoin` | `round` |
| `2` | `strokeLineJoin` | `bevel` |

This is a purely mechanical substitution — no visual content change vs the APK originals.

### Finding what the cluster resolves

To locate icon-resolution code in any decompiled app, search for `getIdentifier` calls:

```bash
grep -r "getIdentifier" decompiled/jadx-out/sources/com/suzuki/ --include="*.java" -l
```

For the Suzuki app the relevant call is in `com/suzuki/adapter/C0897z.java:81`:

```java
context.getResources().getIdentifier("step_" + bVar.h, "drawable", context.getPackageName())
```

The argument to `getIdentifier` is the resource name pattern. Knowing the pattern lets you enumerate the complete icon set by listing all matching files in the APK.

### Where rendered PNGs landed (May 25 session)

`/tmp/step-icons/png/` — not committed to the repo. The decode-and-render pipeline is re-runnable from `apk/base.apk` using the steps above. The 55 classified descriptions are committed to `docs/maneuver-id-table.md`.
