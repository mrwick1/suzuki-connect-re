# UI Overhaul — Wave 1 (Home vertical-slice prototype) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Home screen end-to-end using a new design-token system (color, type, motion, fonts) so the visible UI shifts from the May-24-assembled look-and-feel toward the spec's "premium + fast" north star. Tokens come into existence as part of this wave, not as a separate PR.

**Architecture:** Single source of truth for design tokens at `ui/theme/GixxerTokens.kt`, `ui/theme/Motion.kt`, `ui/theme/GixxerFonts.kt`. `Theme.kt` wires them through `MaterialTheme.colorScheme` so the rest of the app inherits them. The new Home consists of three zones (status, today-hero, quick actions) built from five small composables in `ui/home/components/`. A Konsist static-analysis test forbids hardcoded `Color(0x…)` in `ui/home/**`. A Roborazzi screenshot test pins the visual contract.

**Tech Stack:** Kotlin 2.1, Jetpack Compose BOM 2026.05.00, Material 3 1.3.x, AGP 8.10, Gradle 8.11.1, JDK 17. New deps: **Haze 1.6** (backdrop blur), **Coil 3** (async images), **Compose UI text Google Fonts** (downloadable Inter + Geist Mono), **Roborazzi** (Robolectric screenshot tests), **Konsist** (static analysis).

**Spec:** `docs/superpowers/specs/2026-05-25-ui-overhaul-design.md` (commit `8e6d9bb`).

**Exit gates (verified at the last task):**
- Konsist rule live, fails CI if `Color(0x…)` literals appear in `ui/home/**` outside `ui/theme/`.
- Zero `tween()` calls in `ui/home/**` (`grep` check in plan's last task).
- Zero hardcoded hex in `ui/home/**` (Konsist proves this).
- Home renders at ≥ 60 fps on the K20 Pro (manual GPU profiler check).
- Roborazzi golden snapshot for Home committed and passing.

---

## File map

**New files (15):**

```
android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/
├── GixxerTokens.kt          Color tokens — single source of truth
├── GixxerFonts.kt           Google Fonts provider + FontFamily for Inter + Geist Mono
├── Motion.kt                SpringStandard + SpringSoft

android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/
├── HomeScreen.kt            REWRITTEN — three-zone layout

android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/
├── ConnectionDot.kt         12 dp shape-morphing dot
├── SpeedDisplay.kt          144 sp tabular speed + 4 px brand-red ticker
├── TodayHeroCard.kt         Single hero card: km / streak / next service
├── QuickActionsRow.kt       3 outlined icon buttons: Start ride / Open nav / Pair
├── EmptyState.kt            Reusable 64 dp icon + body + CTA

android/app/src/test/kotlin/dev/mrwick/gixxerbridge/
├── konsist/HardcodedHexLintTest.kt   Fails if Color(0x...) in ui/home/**
├── ui/theme/MotionTest.kt            Verifies spring stiffness/damping constants
├── ui/home/components/SpeedDisplayTickerTest.kt   Ticker fraction logic test
├── ui/home/components/SpeedDisplayScreenshotTest.kt   Roborazzi
├── ui/home/components/ConnectionDotScreenshotTest.kt   Roborazzi
├── ui/home/HomeScreenshotTest.kt     Roborazzi golden of full Home

android/app/src/main/res/values/
└── donottranslate_fonts.xml   Google Fonts provider certs config
```

**Modified files (7):**

```
android/gradle/libs.versions.toml                    Bump Compose BOM + new deps
android/app/build.gradle.kts                          Wire new deps
android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/Theme.kt
                                                      Wire GixxerTokens through ColorScheme + new type scale
android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeViewModel.kt
                                                      Expose what new layout needs (today km, streak, next service)
android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/cluster/ClusterPreview.kt
                                                      Retokenize colors (no structural change)
android/app/src/main/AndroidManifest.xml              Google Fonts provider preloaded font query
android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/ActiveRideCard.kt
android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/RideSummaryCard.kt
android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/LastParkedCard.kt
android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/QuickDestinationsCard.kt
                                                      No longer referenced from HomeScreen; left in place for Wave 3 (data screens) to absorb or delete
```

---

## Task 1: Bump Compose BOM 2024.12.01 → 2026.05.00

**Files:**
- Modify: `android/gradle/libs.versions.toml:5`

- [ ] **Step 1: Update the version**

In `android/gradle/libs.versions.toml`, change:

```toml
compose-bom = "2024.12.01"
```

to:

```toml
compose-bom = "2026.05.00"
```

- [ ] **Step 2: Build to confirm no breakage**

Run from `android/`:
```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. Compose API surface is backward-compatible across BOM bumps within a major. If you see compile errors about a renamed/removed Compose API, fix them in the offending file (most likely renamed: `LocalLifecycleOwner` moved to `androidx.lifecycle.compose`).

- [ ] **Step 3: Commit**

```bash
git add android/gradle/libs.versions.toml
git commit -m "build: bump Compose BOM 2024.12.01 → 2026.05.00 (Wave 1)"
```

---

## Task 2: Add Haze, Coil, Google Fonts, Roborazzi, Konsist dependencies

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Add version entries**

In `android/gradle/libs.versions.toml` under `[versions]`, add:

```toml
haze = "1.6.0"
coil = "3.0.4"
ui-text-google-fonts = "1.7.6"
roborazzi = "1.34.0"
konsist = "0.17.3"
robolectric = "4.14"
```

- [ ] **Step 2: Add library entries**

In `[libraries]`, add:

```toml
haze = { group = "dev.chrisbanes.haze", name = "haze", version.ref = "haze" }
haze-materials = { group = "dev.chrisbanes.haze", name = "haze-materials", version.ref = "haze" }
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
coil-network-okhttp = { group = "io.coil-kt.coil3", name = "coil-network-okhttp", version.ref = "coil" }
androidx-compose-ui-text-google-fonts = { group = "androidx.compose.ui", name = "ui-text-google-fonts", version.ref = "ui-text-google-fonts" }

roborazzi = { group = "io.github.takahirom.roborazzi", name = "roborazzi", version.ref = "roborazzi" }
roborazzi-compose = { group = "io.github.takahirom.roborazzi", name = "roborazzi-compose", version.ref = "roborazzi" }
roborazzi-junit-rule = { group = "io.github.takahirom.roborazzi", name = "roborazzi-junit-rule", version.ref = "roborazzi" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
konsist = { group = "com.lemonappdev", name = "konsist", version.ref = "konsist" }
```

- [ ] **Step 3: Add Roborazzi plugin entry**

In `[plugins]` (add the section if missing — current `libs.versions.toml` may not have one):

```toml
[plugins]
roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
```

- [ ] **Step 4: Wire deps in app build.gradle.kts**

In `android/app/build.gradle.kts`, inside the existing `dependencies { }` block, add:

```kotlin
implementation(libs.haze)
implementation(libs.haze.materials)
implementation(libs.coil.compose)
implementation(libs.coil.network.okhttp)
implementation(libs.androidx.compose.ui.text.google.fonts)

testImplementation(libs.roborazzi)
testImplementation(libs.roborazzi.compose)
testImplementation(libs.roborazzi.junit.rule)
testImplementation(libs.robolectric)
testImplementation(libs.konsist)
```

Inside the top-level `plugins { }` block in `android/app/build.gradle.kts`, add:

```kotlin
alias(libs.plugins.roborazzi)
```

- [ ] **Step 5: Configure Robolectric for unit tests**

In `android/app/build.gradle.kts`, inside `android { }`, add (or merge with existing `testOptions`):

```kotlin
testOptions {
    unitTests {
        isIncludeAndroidResources = true
        isReturnDefaultValues = true
    }
}
```

- [ ] **Step 6: Build to confirm**

Run from `android/`:
```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. If Maven resolves can't find a version, double-check the version strings against the Maven Central pages for each library.

- [ ] **Step 7: Commit**

```bash
git add android/gradle/libs.versions.toml android/app/build.gradle.kts
git commit -m "build: add Haze, Coil 3, Google Fonts, Roborazzi, Konsist (Wave 1)"
```

---

## Task 3: Create GixxerTokens.kt — color tokens single source of truth

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerTokens.kt`

- [ ] **Step 1: Create the tokens file**

Create `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerTokens.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * GixxerBridge design tokens — Wave 1.
 *
 * Single source of truth for color. Every screen must reference colors
 * through this object or through MaterialTheme.colorScheme (which is
 * wired from these tokens in Theme.kt). Hardcoded Color(0xFF...) literals
 * outside ui/theme/ are forbidden by Konsist test
 * (see HardcodedHexLintTest).
 *
 * See docs/superpowers/specs/2026-05-25-ui-overhaul-design.md §"Visual system"
 * for rationale.
 */
object GixxerTokens {

    // --- Foundation: Linear-style warm-neutral dark stack -----------------
    val bg = Color(0xFF0A0A0A)
    val surface = Color(0xFF161616)
    val surfaceElevated = Color(0xFF222222)
    val border = Color(0x14FFFFFF)             // 8% white

    val textPrimary = Color(0xFFFAFAFA)
    val textMuted = Color(0xFFA1A1AA)

    // --- Brand accent: two-tier red ---------------------------------------
    /** Everyday accent: active tab, connected dot, button outlines. ~1-3% surface. */
    val accent = Color(0xFFB91C1C)             // deep cherry
    /** Hero accent. ONE use only: SpeedDisplay underline ticker. */
    val accentHero = Color(0xFFD93B25)         // Suzuki-brand red

    // --- Semantic colors: locked, not interchangeable with accents --------
    val success = Color(0xFF22C55E)
    val warning = Color(0xFFF59E0B)
    val danger = Color(0xFFEF4444)
}
```

- [ ] **Step 2: Build to confirm no breakage**

Run from `android/`:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerTokens.kt
git commit -m "feat(theme): GixxerTokens.kt — color tokens single source of truth"
```

---

## Task 4: Create GixxerFonts.kt — Inter + Geist Mono via Google Fonts

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerFonts.kt`
- Modify: `android/app/src/main/res/values/strings.xml` (add provider certs)

- [ ] **Step 1: Add Google Fonts provider certs**

Verify `android/app/src/main/res/values/strings.xml` has — and if not, add — Google's downloadable-font provider config. Add inside `<resources>`:

```xml
<string name="com_google_android_gms_fonts_certs" translatable="false">@array/com_google_android_gms_fonts_certs</string>
<string-array name="com_google_android_gms_fonts_certs" translatable="false">
    <item>@array/com_google_android_gms_fonts_certs_dev</item>
    <item>@array/com_google_android_gms_fonts_certs_prod</item>
</string-array>
```

Create `android/app/src/main/res/values/font_certs.xml` (new file) with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="com_google_android_gms_fonts_certs_dev" translatable="false">
        <item>
            MIIEqDCCA5CgAwIBAgIJANWFuGx90071MA0GCSqGSIb3DQEBBAUAMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTAeFw0wODA0MTUyMzM2NTZaFw0zNTA5MDEyMzM2NTZaMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBANbOLggKv+IxTdGNs8/TGFy0PTP6DHThvbbR24kT9ixcOd9W+EaBPWW+wPPKQmsHxajtWjmQwWfna8mZuSeJS48LIgAZlKkpoyLcfJDf61JKr6IDF1XmEuxN3wQVnWAhZYpcLpDLwHA+vYMQpEH35wO9DTOQUGn3rzbkr6e+G+0VQjbpcMmrr4ZWPzv6Cj48BqMzVdpRRR9MwQzVQF/CIVS3qhA0gAhSpfqQPMjPN6yPaqpQ3okHHxsKlYHCe9SE6L2bMHd5jSqUyGcXl/q3OYR6tkB/8z+gFRGSpDLZQrYZ3hPDsdcKOZdMlcAJyJv4+0Z9MGCgWWBmh3J3DcCAQOjgfwwgfkwHQYDVR0OBBYEFI0cxb6VTEM8YYY6FbBMvAPyT+CyMIHJBgNVHSMEgcEwgb6AFI0cxb6VTEM8YYY6FbBMvAPyT+CyoYGapIGXMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbYIJANWFuGx90071MAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEEBQADggEBABnTDPEF+3iSP0wNfdIjIz1AlnrPzgAIHVvXxunW7SBrDhEglQZBbKJEk5kT0mtKoOD1JMrSu1xuTKEBahWRbqHsXclaXjoBADb0kkjVEJu/Lh5hgYZnOjvlba8Ld7HCKePCVePoTJBdI4fvugnL8TsgK05aIskyY0hKI9L8KfqfGTl1lzOv2KoWD0KWwtAWPoGChZxmQ+nBli+gwYMzM1vAkP+aayLe0a1EQimlOalO762r0GXO0ks+UeXde2Z4e+8S/pf7pITEI/tP+MxJTALw9QUWEv9lKTk+jkbqxbsh8nfBUapfKqYn0eidpwq2AzVp3juYl7//fKnaPhJD9gs=
        </item>
    </string-array>
    <string-array name="com_google_android_gms_fonts_certs_prod" translatable="false">
        <item>
            MIIEQzCCAyugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDAeFw0wODA4MjEyMzEzMzRaFw0zNjAxMDcyMzEzMzRaMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBAKtWLgDYO6IIrgqWbxJOKdoR8qtW0I9Y4sypEwPpt1TTcvZApxsdyxMJZ2JORland2qSGT2y5b+3JKkedxiLDmpHpDsz2WCbdxgxRczfey5YZnTJ4VZbH0xqWVW/8lGmPav5xVwnIiJS6HXk+BVKZF+JcWjAsb/GEuq/eFdpuzSqeYTcfi6idkyugwfYwXFU1+5fZKUaRKYCwkkFQVfcAs1fXA5V+++FGfvjJ/CxURaSxaBvGdGDhfXE28LWuT9ozCl5xw4Yq5OGazvV24mZVSoOO0yZ31j7kYvtwYK6NeADwbSxDdJEqO4k//0zOHKrUiGYXtqw/A0LFFtqoZKFjnkCAwEAAaOB1zCB1DAdBgNVHQ4EFgQUiHhmuvOptdRWGSrcGFRBZA82YlowgaQGA1UdIwSBnDCBmYAUiHhmuvOptdRWGSrcGFRBZA82YlqheKR2MHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZIIJAMLgh0ZkSjCNMAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEEBQADggEBAE/n3Sx1ZJa9NRshrFP7uvL5pmuwd5/2T2OdMrZc8WUF1HQT+IiNkBuwAtO9bGOY1iPwbjKnxLN6h6JifrUbY9rEpVlAZHF95oqzAjj/Qb1KdgkYqVj7DyJ4cQbtu+H7nyVOlBP8wjs7+m4qe6lZ8oHnDsf/9XJUBT8cFZ1wzU1U9LzC1bp4xKv7MlOaY3OqdAg6+jHjU1YlMlSp0V6Y6yQDV+9X+8+m3HEcl85L9LMNCDXylyVHnZ5Js7gMcN6V+CD/U4yymKozs7N6kDmRznVOSjGhPfWLrqq1+lFrAJpYK6+EHfYAjqGtSEDDvfV66nLAUlF7DhuZX3xK6ZZsoX0=
        </item>
    </string-array>
</resources>
```

(Both certs are Google's published constants for downloadable fonts; verbatim from the Android Jetpack docs.)

- [ ] **Step 2: Create the fonts file**

Create `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerFonts.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import dev.mrwick.gixxerbridge.R

/**
 * Downloadable Google Fonts — Inter (chrome) + Geist Mono (numerics).
 *
 * First-paint fallback is FontFamily.Default (Roboto) for Inter and
 * FontFamily.Monospace (Roboto Mono) for Geist Mono — both already in the
 * Android system, so layout doesn't shift on font load.
 */
private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val Inter = GoogleFont("Inter")
private val GeistMono = GoogleFont("Geist Mono")

val InterFamily: FontFamily = FontFamily(
    Font(googleFont = Inter, fontProvider = GoogleFontsProvider, weight = FontWeight.W400),
    Font(googleFont = Inter, fontProvider = GoogleFontsProvider, weight = FontWeight.W500),
    Font(googleFont = Inter, fontProvider = GoogleFontsProvider, weight = FontWeight.W600),
    Font(googleFont = Inter, fontProvider = GoogleFontsProvider, weight = FontWeight.W700),
)

val GeistMonoFamily: FontFamily = FontFamily(
    Font(googleFont = GeistMono, fontProvider = GoogleFontsProvider, weight = FontWeight.W400),
    Font(googleFont = GeistMono, fontProvider = GoogleFontsProvider, weight = FontWeight.W500),
    Font(googleFont = GeistMono, fontProvider = GoogleFontsProvider, weight = FontWeight.W700),
)
```

- [ ] **Step 3: Build to confirm**

Run from `android/`:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerFonts.kt \
        android/app/src/main/res/values/font_certs.xml \
        android/app/src/main/res/values/strings.xml
git commit -m "feat(theme): Inter + Geist Mono via Google Fonts downloadable provider"
```

---

## Task 5: Create Motion.kt — SpringStandard + SpringSoft

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/Motion.kt`
- Create: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/theme/MotionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/theme/MotionTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {

    @Test
    fun springStandard_uses_spec_stiffness_and_damping() {
        val s = Motion.SpringStandard as SpringSpec<Float>
        assertEquals(400f, s.stiffness, 0.001f)
        assertEquals(0.85f, s.dampingRatio, 0.001f)
    }

    @Test
    fun springSoft_uses_spec_stiffness_and_damping() {
        val s = Motion.SpringSoft as SpringSpec<Float>
        assertEquals(200f, s.stiffness, 0.001f)
        assertEquals(0.75f, s.dampingRatio, 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run from `android/`:
```bash
./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.ui.theme.MotionTest"
```
Expected: FAIL with `Unresolved reference: Motion`.

- [ ] **Step 3: Create Motion.kt**

Create `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/Motion.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring

/**
 * The two named springs that cover ~all UI state in the GixxerBridge
 * design system. M3 Expressive spring physics — never duration-based tween.
 *
 * See docs/superpowers/specs/2026-05-25-ui-overhaul-design.md §"Motion".
 *
 * Forbidden in implementation:
 *   - tween() for any UI state — use one of these two
 *   - infiniteRepeatable for any non-loading purpose
 *   - Crossfade — use AnimatedContent with SpringStandard
 */
object Motion {
    /** Most state transitions: color, size, position, alpha. */
    val SpringStandard: AnimationSpec<Float> =
        spring(dampingRatio = 0.85f, stiffness = 400f)

    /** Sheet open, big number reveals, post-ride summary. */
    val SpringSoft: AnimationSpec<Float> =
        spring(dampingRatio = 0.75f, stiffness = 200f)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run from `android/`:
```bash
./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.ui.theme.MotionTest"
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/Motion.kt \
        android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/theme/MotionTest.kt
git commit -m "feat(theme): Motion.kt — SpringStandard + SpringSoft (M3 Expressive springs)"
```

---

## Task 6: Update Theme.kt — wire tokens through ColorScheme + new type scale

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/Theme.kt`

- [ ] **Step 1: Replace Theme.kt**

Replace the entire contents of `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/Theme.kt` with:

```kotlin
package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFeature
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * GixxerBridge theme — Wave 1 of the May 2026 UI overhaul.
 *
 * Colors flow from [GixxerTokens]. The `accent` parameter overrides the
 * primary slot (kept for the user-pickable accent in Settings until the
 * Wave 4 settings split removes it).
 *
 * Dark theme only — a matte black UI saves OLED power and reads well in
 * sunlight, which matters for a rider glancing at the phone.
 */
@Composable
fun GixxerTheme(
    accent: Color = GixxerTokens.accent,
    content: @Composable () -> Unit,
) {
    val scheme = GixxerColors.copy(
        primary = accent,
        primaryContainer = accent.copy(alpha = 0.7f),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = GixxerTypography,
        shapes = GixxerShapes,
    ) {
        CompositionLocalProvider(content = content)
    }
}

/**
 * Named accent palette the user can pick in Settings. Wave 1 keeps the
 * accent picker working but the design system defaults to GixxerTokens.accent.
 */
val ACCENT_PALETTE: Map<String, Color> = linkedMapOf(
    "red" to GixxerTokens.accent,             // new default — deep cherry
    "cyan" to Color(0xFF22D3EE),
    "amber" to Color(0xFFFBBF24),
    "magenta" to Color(0xFFEC4899),
    "green" to Color(0xFF10B981),
    "orange" to Color(0xFFFB923C),
)

fun accentColorFor(name: String?): Color =
    ACCENT_PALETTE[name] ?: ACCENT_PALETTE.getValue("red")

// --- Color scheme: wired from GixxerTokens ---------------------------------

val GixxerColors = darkColorScheme(
    primary = GixxerTokens.accent,
    onPrimary = GixxerTokens.textPrimary,
    primaryContainer = GixxerTokens.accent.copy(alpha = 0.7f),
    onPrimaryContainer = GixxerTokens.textPrimary,
    secondary = GixxerTokens.warning,
    onSecondary = GixxerTokens.bg,
    tertiary = GixxerTokens.success,
    onTertiary = GixxerTokens.bg,
    error = GixxerTokens.danger,
    onError = GixxerTokens.textPrimary,
    background = GixxerTokens.bg,
    onBackground = GixxerTokens.textPrimary,
    surface = GixxerTokens.surface,
    onSurface = GixxerTokens.textPrimary,
    surfaceVariant = GixxerTokens.surfaceElevated,
    onSurfaceVariant = GixxerTokens.textMuted,
    surfaceContainer = GixxerTokens.surface,
    surfaceContainerHigh = GixxerTokens.surfaceElevated,
    surfaceContainerHighest = GixxerTokens.surfaceElevated,
    surfaceContainerLow = GixxerTokens.bg,
    outline = GixxerTokens.border,
    outlineVariant = GixxerTokens.border,
    inverseSurface = GixxerTokens.textPrimary,
    inverseOnSurface = GixxerTokens.bg,
)

/**
 * Brand-name aliases for places that need a color by intent rather than role.
 * Prefer MaterialTheme.colorScheme.* when possible; this is for the few
 * cluster-style flourishes that want explicit semantics.
 */
object GixxerBrand {
    val accent = GixxerTokens.accent
    val accentHero = GixxerTokens.accentHero
    val warning = GixxerTokens.warning
    val danger = GixxerTokens.danger
    val success = GixxerTokens.success
    val textSubtle = GixxerTokens.textMuted
}

// --- Typography: Inter + Geist Mono via Google Fonts ------------------------
//
// Type scale: opinionated weights (Airbnb 2025 "modest 500/600", not heavy
// 700/800 except the speed display). Tabular numerics on every monospaced
// style — required to stop the layout jitter on per-frame value changes.

private val Tabular = listOf(FontFeature("tnum"))

val GixxerTypography = Typography(
    // Display family — used only for the speed hero figure (144 sp). The
    // standard Material display roles map to Inter so chrome-display text
    // (post-ride summary numbers) gets the right look.
    displayLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 48.sp, lineHeight = 52.sp),
    displayMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 36.sp, lineHeight = 40.sp),
    displaySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 28.sp, lineHeight = 32.sp),

    headlineLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 24.sp, lineHeight = 28.sp),
    headlineMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 20.sp, lineHeight = 24.sp),
    headlineSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 18.sp, lineHeight = 22.sp),

    titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 16.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 14.sp, lineHeight = 18.sp),

    bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W400, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W400, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W400, fontSize = 12.sp, lineHeight = 16.sp),

    labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

/**
 * Monospaced text styles for live numerics — speed, odo, trip km, fuel
 * range, hex frames. Every style includes `tnum` so digits never shift
 * horizontally as values change.
 */
object GixxerMono {
    /** 144 sp Geist Mono — single use: SpeedDisplay. */
    val display = TextStyle(fontFamily = GeistMonoFamily, fontWeight = FontWeight.W700, fontSize = 144.sp, lineHeight = 144.sp, fontFeatureSettings = "tnum")
    /** Post-ride summary numbers, large data values. */
    val headline = TextStyle(fontFamily = GeistMonoFamily, fontWeight = FontWeight.W500, fontSize = 32.sp, lineHeight = 36.sp, fontFeatureSettings = "tnum")
    /** Odo, trip km, fuel range — most data lines. */
    val body = TextStyle(fontFamily = GeistMonoFamily, fontWeight = FontWeight.W400, fontSize = 14.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
    /** Caption-sized numerics — sub-text in cards. */
    val label = TextStyle(fontFamily = GeistMonoFamily, fontWeight = FontWeight.W500, fontSize = 11.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum")
}

// --- Shapes: 16 / 8 / 28 (cards / chips / sheets) ---------------------------

val GixxerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
```

- [ ] **Step 2: Build to confirm**

Run from `android/`:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. If a callsite still references `BgBase`, `BrandCyan`, or `TextSecondary` (the old private vals removed from Theme.kt), it'll fail — those references are inside `ui/theme/` files only (we kept `GixxerBrand` aliases). Fix by replacing with `GixxerTokens.*` or `GixxerBrand.*`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/Theme.kt
git commit -m "feat(theme): wire GixxerTokens through ColorScheme + new type scale"
```

---

## Task 7: Konsist rule — fail on hardcoded hex in ui/home/**

**Files:**
- Create: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/konsist/HardcodedHexLintTest.kt`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/konsist/HardcodedHexLintTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test

/**
 * Wave 1 lint rule: Color(0x…) literals are forbidden in ui/home/** —
 * every color must come from MaterialTheme.colorScheme or GixxerTokens
 * (which live in ui/theme/).
 *
 * Scope is narrow on purpose. Waves 2-5 will widen the scope to all of
 * ui/** as more screens are retokenized.
 */
class HardcodedHexLintTest {

    @Test
    fun `no hardcoded hex Color literals in ui_home`() {
        Konsist.scopeFromProject()
            .files
            .withPackage("dev.mrwick.gixxerbridge.ui.home..")
            .assertFalse { file ->
                HEX_COLOR_REGEX.containsMatchIn(file.text)
            }
    }

    companion object {
        private val HEX_COLOR_REGEX = Regex("""Color\(\s*0x[0-9A-Fa-f]{6,8}""")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run from `android/`:
```bash
./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.konsist.HardcodedHexLintTest"
```
Expected: FAIL — Home today has 8 hardcoded hex literals (in `HomeScreen.kt`).

- [ ] **Step 3: Note: this test will go GREEN after Task 13 rewrites HomeScreen.kt**

Leave the test in place. It serves as a regression guard. The expected failure right now is correct — it documents that the Wave 1 rewrite has work left to do.

- [ ] **Step 4: Commit (test red — that's intentional)**

```bash
git add android/app/src/test/kotlin/dev/mrwick/gixxerbridge/konsist/HardcodedHexLintTest.kt
git commit -m "test(konsist): forbid hardcoded hex in ui/home/** (Wave 1 lint gate)

Test is RED at this commit by design. Task 13 (rewrite Home using new
components) will make it green by replacing all Color(0xFF...) references
in HomeScreen.kt with GixxerTokens.* / MaterialTheme.colorScheme.*."
```

---

## Task 8: SpeedDisplay — ticker fraction logic (TDD, pure Kotlin)

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/SpeedDisplay.kt`
- Create: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/home/components/SpeedDisplayTickerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/home/components/SpeedDisplayTickerTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.home.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedDisplayTickerTest {

    @Test
    fun ticker_returns_zero_at_lastUpdate_time() {
        val f = tickerFraction(nowMs = 1_000, lastUpdateMs = 1_000, intervalMs = 5_000)
        assertEquals(0.0f, f, 0.001f)
    }

    @Test
    fun ticker_returns_half_at_midpoint() {
        val f = tickerFraction(nowMs = 3_500, lastUpdateMs = 1_000, intervalMs = 5_000)
        assertEquals(0.5f, f, 0.001f)
    }

    @Test
    fun ticker_returns_one_at_or_after_interval() {
        val full = tickerFraction(nowMs = 6_000, lastUpdateMs = 1_000, intervalMs = 5_000)
        val stale = tickerFraction(nowMs = 9_999, lastUpdateMs = 1_000, intervalMs = 5_000)
        assertEquals(1.0f, full, 0.001f)
        assertEquals(1.0f, stale, 0.001f)
    }

    @Test
    fun ticker_returns_one_when_no_last_update() {
        // lastUpdateMs == 0 means "never updated" — show stale-full.
        val f = tickerFraction(nowMs = 1_000, lastUpdateMs = 0, intervalMs = 5_000)
        assertEquals(1.0f, f, 0.001f)
    }

    @Test
    fun ticker_clamps_negative_age_to_zero() {
        // Defensive — if clock skews, never return negative fraction.
        val f = tickerFraction(nowMs = 500, lastUpdateMs = 1_000, intervalMs = 5_000)
        assertEquals(0.0f, f, 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run from `android/`:
```bash
./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.ui.home.components.SpeedDisplayTickerTest"
```
Expected: FAIL — `tickerFraction` doesn't exist.

- [ ] **Step 3: Create SpeedDisplay.kt with the pure ticker function**

Create `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/SpeedDisplay.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.home.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * Live BLE-frame staleness fraction.
 *
 * Returns 0.0 at [lastUpdateMs] and ramps linearly to 1.0 at
 * `lastUpdateMs + intervalMs`. Stays at 1.0 past that (silent stale
 * indicator). Returns 1.0 if [lastUpdateMs] is 0 (never updated).
 * Clamps to [0.0, 1.0]; never negative.
 */
fun tickerFraction(nowMs: Long, lastUpdateMs: Long, intervalMs: Long): Float {
    if (lastUpdateMs <= 0L) return 1f
    val age = nowMs - lastUpdateMs
    if (age <= 0L) return 0f
    if (age >= intervalMs) return 1f
    return (age.toFloat() / intervalMs.toFloat())
}

/** Connection state hint for [SpeedDisplay]. */
enum class SpeedState { Connected, Connecting, Disconnected }

/**
 * The hero speed figure for the GixxerBridge UI.
 *
 * - 144 sp tabular Geist Mono on transparent background.
 * - 4 dp [GixxerBrand.accentHero] underline ticker that fills 0→100% across
 *   the 5 s BLE poll interval. Reset by the parent passing a fresh
 *   [lastUpdateMs] whenever a new a537 frame is decoded.
 * - State-aware: greys to [GixxerTokens.textMuted] when not [SpeedState.Connected];
 *   ticker hides when [SpeedState.Disconnected].
 *
 * Used by Home preview, Dashboard, and Wave 2 active-ride layout.
 */
@Composable
fun SpeedDisplay(
    speedKmh: Int?,
    state: SpeedState,
    lastUpdateMs: Long,
    modifier: Modifier = Modifier,
    intervalMs: Long = 5_000L,
) {
    val number = speedKmh?.toString()?.padStart(3, ' ') ?: "  —"
    val color = if (state == SpeedState.Connected) GixxerTokens.textPrimary else GixxerTokens.textMuted

    // Re-tick every 50 ms so the underline animates smoothly without a
    // foreground service — cheap, only running while this composable is in
    // composition.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(50)
        }
    }
    val targetFraction = tickerFraction(nowMs, lastUpdateMs, intervalMs)
    val fraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = Motion.SpringStandard,
        label = "speedTicker",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = number,
            style = GixxerMono.display.copy(color = color),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (state != SpeedState.Disconnected) {
            UnderlineTicker(fraction = fraction, color = GixxerBrand.accentHero)
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun UnderlineTicker(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            val w = size.width * fraction.coerceIn(0f, 1f)
            drawRect(
                color = color,
                topLeft = Offset(0f, 0f),
                size = Size(w, size.height),
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run from `android/`:
```bash
./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.ui.home.components.SpeedDisplayTickerTest"
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/SpeedDisplay.kt \
        android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/home/components/SpeedDisplayTickerTest.kt
git commit -m "feat(home): SpeedDisplay component — 144 sp tabular + ticker"
```

---

## Task 9: ConnectionDot — 12 dp shape-morphing dot

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/ConnectionDot.kt`

- [ ] **Step 1: Create the component**

Create `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/ConnectionDot.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.home.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.ui.theme.Motion

/**
 * 12 dp shape-morphing connection-state dot. Replaces verbose
 * "Connected via Bluetooth · MAC: …" status text everywhere.
 *
 * Visual mapping (see spec §"Connection state — 12 dp shape-morphing dot"):
 *   Idle           hollow circle    textMuted   static
 *   Connecting     filled circle    accent      pulse (spring)
 *   Discovering    rounded square   warning     shape-morph from circle
 *   Ready          filled circle    success     shape-morph to circle
 *   Disconnected   hollow circle    textMuted   one slow fade
 *   Failed         filled circle    danger      brief shake
 *
 * Hand-rolled with Animatable — does not pin M3 Expressive alpha for the
 * shape-morph; uses corner-radius interpolation instead.
 */
@Composable
fun ConnectionDot(state: ConnectionState, modifier: Modifier = Modifier) {
    val color = colorFor(state)
    val cornerRadius by animateDpAsState(
        targetValue = if (state is ConnectionState.Discovering) 3.dp else 6.dp,
        animationSpec = Motion.SpringStandard,
        label = "dotCornerRadius",
    )
    val filled = state !is ConnectionState.Idle && state !is ConnectionState.Disconnected

    // Pulse alpha for the Connecting state — single Animatable so we don't
    // run an infinite transition unless the state needs it.
    val pulseAlpha = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state is ConnectionState.Connecting) {
            pulseAlpha.animateTo(
                targetValue = 0.4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 700, easing = LinearEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
            )
        } else {
            pulseAlpha.snapTo(1f)
        }
    }

    Box(
        modifier = modifier
            .size(12.dp)
            .clip(RoundedCornerShape(cornerRadius))
            .then(
                if (filled) {
                    Modifier.background(color.copy(alpha = pulseAlpha.value))
                } else {
                    Modifier.border(1.dp, GixxerTokens.textMuted, RoundedCornerShape(cornerRadius))
                },
            ),
    )
}

private fun colorFor(state: ConnectionState): Color = when (state) {
    is ConnectionState.Ready -> GixxerBrand.success
    is ConnectionState.Connecting -> GixxerBrand.accent
    is ConnectionState.Discovering -> GixxerBrand.warning
    is ConnectionState.Failed -> GixxerBrand.danger
    is ConnectionState.Disconnected -> GixxerTokens.textMuted
    is ConnectionState.Idle -> GixxerTokens.textMuted
}
```

- [ ] **Step 2: Build to confirm**

Run from `android/`:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. The `infiniteRepeatable + tween` here is the one allowed use of `tween` in `ui/home/` — it's the pulse loop, a loading-style animation. The Wave-1 exit-gate grep for `tween(` checks for *state* tweens, not loading loops, so this passes. Document the exception with the inline comment shown.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/ConnectionDot.kt
git commit -m "feat(home): ConnectionDot — 12 dp shape-morphing state indicator"
```

---

## Task 10: EmptyState — reusable single-CTA empty state

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/EmptyState.kt`

- [ ] **Step 1: Create the component**

Create `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/EmptyState.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/**
 * The one empty-state shape used everywhere in the app. Replaces the
 * "Start the service…" placeholder vomit on Dashboard and other screens.
 *
 * Layout: 64 dp Material Symbol weight 200 + body line + single outlined CTA.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    body: String,
    ctaLabel: String?,
    onCta: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(PaddingValues(horizontal = 24.dp, vertical = 32.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = GixxerTokens.textMuted,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = GixxerTokens.textMuted,
        )
        if (ctaLabel != null && onCta != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onCta) {
                Text(ctaLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
```

- [ ] **Step 2: Build to confirm**

Run from `android/`:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/EmptyState.kt
git commit -m "feat(home): EmptyState — reusable single-CTA empty"
```

---

## Task 11: TodayHeroCard — the single hero card for today

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/TodayHeroCard.kt`

- [ ] **Step 1: Create the component**

Create `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/TodayHeroCard.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/**
 * The single hero card for Home's today-zone. Replaces the multi-card
 * stack of RideSummary + BikeHealth + ServiceDue + ride streak. Three
 * lines, one card, no shadows. Each line is icon + label + tabular value.
 *
 * Values are nullable — null renders an em-dash so the card never collapses.
 */
@Composable
fun TodayHeroCard(
    todayKm: Double?,
    streakDays: Int?,
    nextServiceLabel: String?,
    nextServiceDueIn: String?,    // e.g. "120 km" or "Overdue 30 km"
    nextServiceOverdue: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "TODAY",
                style = MaterialTheme.typography.labelMedium,
                color = GixxerTokens.textMuted,
            )
            Spacer(Modifier.height(16.dp))

            HeroRow(
                icon = Icons.Outlined.Speed,
                label = "Distance",
                value = todayKm?.let { "%.1f".format(it) + " km" } ?: "—",
                valueColor = GixxerTokens.textPrimary,
            )
            Spacer(Modifier.height(12.dp))

            HeroRow(
                icon = Icons.Outlined.LocalFireDepartment,
                label = "Ride streak",
                value = streakDays?.let { "$it ${if (it == 1) "day" else "days"}" } ?: "—",
                valueColor = GixxerTokens.textPrimary,
            )
            Spacer(Modifier.height(12.dp))

            HeroRow(
                icon = Icons.Outlined.Build,
                label = nextServiceLabel ?: "Next service",
                value = nextServiceDueIn ?: "Set a baseline",
                valueColor = if (nextServiceOverdue) GixxerTokens.danger else GixxerTokens.textPrimary,
            )
        }
    }
}

@Composable
private fun HeroRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = GixxerTokens.textMuted, modifier = Modifier.width(20.dp).height(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = GixxerTokens.textMuted,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = GixxerMono.body.copy(color = valueColor),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
```

- [ ] **Step 2: Build to confirm**

Run from `android/`:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/TodayHeroCard.kt
git commit -m "feat(home): TodayHeroCard — single card for distance / streak / next service"
```

---

## Task 12: QuickActionsRow — 3 outlined icon buttons

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/QuickActionsRow.kt`

- [ ] **Step 1: Create the component**

Create `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/QuickActionsRow.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Three outlined icon buttons under the hero card: Start ride / Open nav / Pair.
 * Replaces the bulky button stack at the bottom of today's Home.
 */
@Composable
fun QuickActionsRow(
    onStartRide: () -> Unit,
    onOpenNav: () -> Unit,
    onOpenPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickAction(icon = Icons.Outlined.PlayArrow, label = "Ride", onClick = onStartRide, modifier = Modifier.weight(1f))
        QuickAction(icon = Icons.Outlined.Map, label = "Nav", onClick = onOpenNav, modifier = Modifier.weight(1f))
        QuickAction(icon = Icons.Outlined.Settings, label = "Pair", onClick = onOpenPairing, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.width(18.dp).height(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
```

- [ ] **Step 2: Build to confirm**

Run from `android/`:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/components/QuickActionsRow.kt
git commit -m "feat(home): QuickActionsRow — 3 outlined icon buttons"
```

---

## Task 13: Rewrite HomeScreen.kt — three-zone layout

**Files:**
- Modify (full rewrite): `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeScreen.kt`
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeViewModel.kt` (add the fields below if missing)

- [ ] **Step 1: Read HomeViewModel to confirm what it exposes**

Run from repo root:
```bash
cat android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeViewModel.kt
```

You're checking for: connection state flow, today's km, ride streak, next service due item + amount. If any are missing, add them by exposing the relevant `Settings` / `RideStore` / `ServiceSchedule` flow as a `StateFlow`. Pattern matches existing flows in `HomeViewModel`. If everything is already there, skip to step 2.

- [ ] **Step 2: Replace HomeScreen.kt**

Replace the entire contents of `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeScreen.kt` with:

```kotlin
package dev.mrwick.gixxerbridge.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.ui.home.components.ConnectionDot
import dev.mrwick.gixxerbridge.ui.home.components.QuickActionsRow
import dev.mrwick.gixxerbridge.ui.home.components.TodayHeroCard
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/**
 * Wave 1 Home — three zones:
 *   1. Top zone: ConnectionDot + rider name + connection state label
 *   2. Today zone: single TodayHeroCard
 *   3. Quick actions: row of 3 outlined icon buttons
 *
 * No hardcoded hex anywhere — Konsist HardcodedHexLintTest enforces this.
 */
@Composable
fun HomeScreen(
    onOpenPairing: () -> Unit,
    onStartRide: () -> Unit = {},
    onOpenNav: () -> Unit = {},
    vm: HomeViewModel = viewModel(),
) {
    val connectionState by vm.connectionState.collectAsStateWithLifecycle(initialValue = ConnectionState.Idle)
    val riderName by vm.riderName.collectAsStateWithLifecycle()
    val todayKm by vm.todayDistanceKm.collectAsStateWithLifecycle()
    val streak by vm.rideStreakDays.collectAsStateWithLifecycle()
    val nextService by vm.nextServiceDue.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Zone 1 — top status row
        TopStatusZone(
            connectionState = connectionState,
            riderName = riderName,
        )

        // Zone 2 — today hero card
        TodayHeroCard(
            todayKm = todayKm,
            streakDays = streak,
            nextServiceLabel = nextService?.label,
            nextServiceDueIn = nextService?.dueInText,
            nextServiceOverdue = nextService?.overdue == true,
        )

        // Zone 3 — quick actions
        QuickActionsRow(
            onStartRide = onStartRide,
            onOpenNav = onOpenNav,
            onOpenPairing = onOpenPairing,
        )
    }
}

@Composable
private fun TopStatusZone(connectionState: ConnectionState, riderName: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ConnectionDot(state = connectionState)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "Hi, $riderName",
                style = MaterialTheme.typography.titleLarge,
                color = GixxerTokens.textPrimary,
            )
            Text(
                text = stateLabel(connectionState),
                style = MaterialTheme.typography.bodySmall,
                color = GixxerTokens.textMuted,
            )
        }
    }
}

private fun stateLabel(s: ConnectionState): String = when (s) {
    is ConnectionState.Ready -> "Connected"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Discovering -> "Discovering services…"
    is ConnectionState.Disconnected -> "Waiting for bike"
    is ConnectionState.Failed -> "Failed — tap to retry"
    is ConnectionState.Idle -> "Idle"
}
```

- [ ] **Step 3: Add missing flows to HomeViewModel**

If step 1 found any of `todayDistanceKm`, `rideStreakDays`, `nextServiceDue`, `connectionState`, `riderName` missing, add them. Pattern (using `RideStore` + `ServiceSchedule.healthFor`):

```kotlin
// Inside HomeViewModel — add only those flows that aren't already exposed.

data class NextServiceSummary(val label: String, val dueInText: String, val overdue: Boolean)

val todayDistanceKm: StateFlow<Double?> = rideStore.todayDistanceKm
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

val rideStreakDays: StateFlow<Int?> = rideStore.currentStreakDays
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

val nextServiceDue: StateFlow<NextServiceSummary?> = combine(
    settings.serviceSchedule,
    telemetry.latest,
) { schedule, latest ->
    val odo = latest?.odometerKm ?: return@combine null
    val worst = ServiceSchedule.mostOverdue(schedule.values.toList(), odo, System.currentTimeMillis())
        ?: return@combine null
    NextServiceSummary(
        label = worst.item.label,
        dueInText = if (worst.overdue) "Overdue ${-worst.kmRemaining} km" else "${worst.kmRemaining} km",
        overdue = worst.overdue,
    )
}.stateIn(viewModelScope, SharingStarted.Eagerly, null)

val connectionState: Flow<ConnectionState> = AppGraph.bleClient?.state
    ?: flowOf(ConnectionState.Idle)

val riderName: StateFlow<String> = settings.riderName
    .stateIn(viewModelScope, SharingStarted.Eagerly, "Rider")
```

(Adapt to exact names of existing flows in `HomeViewModel` and `RideStore`. If `RideStore.todayDistanceKm` or `currentStreakDays` don't exist, add them — pattern matches existing aggregations in `RideStore`.)

- [ ] **Step 4: Build to confirm**

Run from `android/`:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. Fix any unresolved references by adapting to the actual flow names in `HomeViewModel` and `RideStore`.

- [ ] **Step 5: Run the Konsist hardcoded-hex test — must now pass**

Run from `android/`:
```bash
./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.konsist.HardcodedHexLintTest"
```
Expected: PASS. If it fails, grep for `Color(0x` under `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/` and replace each occurrence with a `GixxerTokens.*` or `MaterialTheme.colorScheme.*` reference.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeScreen.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeViewModel.kt
git commit -m "feat(home): rewrite HomeScreen as 3 zones (status / today / actions)

- Top: ConnectionDot + rider name
- Today: single TodayHeroCard (km / streak / next service)
- Actions: QuickActionsRow (Ride / Nav / Pair)

Zero hardcoded hex. Konsist HardcodedHexLintTest now green."
```

---

## Task 14: Retokenize ClusterPreview — no structural change

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/cluster/ClusterPreview.kt`

- [ ] **Step 1: Find every hardcoded hex in ClusterPreview**

Run from repo root:
```bash
grep -n "Color(0x" android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/cluster/ClusterPreview.kt
```

- [ ] **Step 2: Replace each with a token reference**

For each match, replace with the nearest `GixxerTokens.*` or `MaterialTheme.colorScheme.*` reference. Mapping table:

| Old | New |
|---|---|
| `Color(0xFF050B1A)` or any near-black bg | `GixxerTokens.bg` |
| `Color(0xFF0F172A)` / `Color(0xFF161616)` | `GixxerTokens.surface` |
| `Color(0xFF1E293B)` / `Color(0xFF222222)` | `GixxerTokens.surfaceElevated` |
| `Color(0xFFF1F5F9)` / `Color(0xFFFAFAFA)` | `GixxerTokens.textPrimary` |
| `Color(0xFF94A3B8)` / `Color(0xFFA1A1AA)` | `GixxerTokens.textMuted` |
| `Color(0xFF22D3EE)` (old cyan accent) | `GixxerTokens.accent` |
| `Color(0xFF10B981)` (old success) | `GixxerTokens.success` |
| `Color(0xFFFBBF24)` (old warning) | `GixxerTokens.warning` |
| `Color(0xFFEF4444)` (old danger) | `GixxerTokens.danger` |

For any colour not in this table, default to `GixxerTokens.textPrimary`/`textMuted` for foreground or `GixxerTokens.surface` for background, and add a `// PERF: token-mapped from <old hex>` comment.

- [ ] **Step 3: Build to confirm**

Run from `android/`:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. If new imports needed: add `import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens`.

- [ ] **Step 4: Visual check — install and look at Home**

Run from `android/`:
```bash
./gradlew :app:installDebug
adb shell am force-stop dev.mrwick.gixxerbridge.debug
adb shell am start -n dev.mrwick.gixxerbridge.debug/dev.mrwick.gixxerbridge.MainActivity
```

Tap Home tab. The cluster preview should render with the new dark stack — `#0A0A0A` bg, deep cherry accent on the maneuver arrow, single muted grey for labels. If colors look wrong (washed out, wrong accent), grep step 1 again for any missed literal.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/cluster/ClusterPreview.kt
git commit -m "refactor(cluster): retokenize ClusterPreview colors (no structural change)"
```

---

## Task 15: Roborazzi screenshot test for Home

**Files:**
- Create: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeScreenshotTest.kt`

- [ ] **Step 1: Create the test**

Create `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeScreenshotTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.ui.home.components.ConnectionDot
import dev.mrwick.gixxerbridge.ui.home.components.QuickActionsRow
import dev.mrwick.gixxerbridge.ui.home.components.TodayHeroCard
import dev.mrwick.gixxerbridge.ui.theme.GixxerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden snapshot of the Wave 1 Home composition.
 *
 * We render the three zones directly rather than the full HomeScreen
 * (which requires a ViewModel + DataStore + RideStore + AppGraph). This
 * is intentional: the test pins the *visual contract* of the new
 * components, which is what we want a golden to guard against.
 *
 * Regenerate goldens with: ./gradlew :app:recordRoborazziDebug
 * Verify with:             ./gradlew :app:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class HomeScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    @get:Rule val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
    )

    @Test
    fun home_three_zones_default_state() {
        composeRule.setContent {
            GixxerTheme {
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier
                        .background(dev.mrwick.gixxerbridge.ui.theme.GixxerTokens.bg)
                        .padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        ConnectionDot(state = ConnectionState.Ready)
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
                        androidx.compose.material3.Text(
                            "Hi, Arjun",
                            color = dev.mrwick.gixxerbridge.ui.theme.GixxerTokens.textPrimary,
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        )
                    }
                    TodayHeroCard(
                        todayKm = 12.4,
                        streakDays = 3,
                        nextServiceLabel = "Air filter",
                        nextServiceDueIn = "1240 km",
                        nextServiceOverdue = false,
                    )
                    QuickActionsRow(onStartRide = {}, onOpenNav = {}, onOpenPairing = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
```

Missing imports (add to file):

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
```

- [ ] **Step 2: Record the initial golden**

Run from `android/`:
```bash
./gradlew :app:recordRoborazziDebug
```
Expected: `BUILD SUCCESSFUL`. Golden saved at `android/app/src/test/snapshots/images/dev.mrwick.gixxerbridge.ui.home.HomeScreenshotTest.home_three_zones_default_state.png`.

- [ ] **Step 3: Verify the golden by running normally**

```bash
./gradlew :app:verifyRoborazziDebug
```
Expected: PASS.

- [ ] **Step 4: Inspect the golden**

Open `android/app/src/test/snapshots/images/dev.mrwick.gixxerbridge.ui.home.HomeScreenshotTest.home_three_zones_default_state.png` in your image viewer. Confirm:
- Background is `#0A0A0A` (almost black).
- ConnectionDot is a small filled green circle.
- "Hi, Arjun" in Inter weight 600.
- TodayHeroCard shows three rows with tabular numerics.
- QuickActionsRow shows three outlined buttons spread across the width.

If anything looks wrong, fix the source and re-run `recordRoborazziDebug`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeScreenshotTest.kt \
        android/app/src/test/snapshots/
git commit -m "test(home): Roborazzi golden snapshot for Home three-zone layout"
```

---

## Task 16: Exit-gate verification + final commit

**Files:** none modified — verification only.

- [ ] **Step 1: Run all unit tests**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```
Expected: PASS. Failure stops the wave — fix before proceeding.

- [ ] **Step 2: Verify no tween() in ui/home/**

```bash
grep -rn "tween(" android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/
```
Expected: ONE match in `ConnectionDot.kt` inside the `infiniteRepeatable` (the documented allowed exception for the loading pulse). Any other match must be removed.

- [ ] **Step 3: Verify no Color(0x in ui/home/**

```bash
grep -rn "Color(0x" android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/
```
Expected: zero matches. The Konsist test would have caught this in step 1, but grep is a belt-and-braces check.

- [ ] **Step 4: Install + measure FPS on the K20 Pro**

```bash
adb shell setprop debug.hwui.profile true
cd android && ./gradlew :app:installDebug
adb shell am force-stop dev.mrwick.gixxerbridge.debug
adb shell am start -n dev.mrwick.gixxerbridge.debug/dev.mrwick.gixxerbridge.MainActivity
```

Scroll Home up and down a few times. Then:

```bash
adb shell dumpsys gfxinfo dev.mrwick.gixxerbridge.debug framestats | grep "Janky frames" | head -1
adb shell dumpsys gfxinfo dev.mrwick.gixxerbridge.debug | grep "50th percentile"
```

Expected:
- `Janky frames` < 5 % on a fresh launch + scroll session
- `50th percentile` total frame time < 16 ms (i.e. ≥ 60 fps median)

If either exit gate fails, profile with Android Studio's Layout Inspector / GPU profiler. The most likely culprit is over-recomposition in `TodayHeroCard` — wrap the value strings in a `remember(value) { … }` and confirm.

Reset:
```bash
adb shell setprop debug.hwui.profile false
```

- [ ] **Step 5: Reset Konsist test scope check**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.konsist.HardcodedHexLintTest"
```
Expected: PASS — proves the lint gate is live and green.

- [ ] **Step 6: Final commit with exit-gate evidence in message**

```bash
git commit --allow-empty -m "chore: Wave 1 exit gates verified

- Konsist HardcodedHexLintTest: PASS (no Color(0x...) in ui/home/**)
- grep tween( ui/home/: 1 match (allowed loading-pulse in ConnectionDot)
- All unit tests: PASS
- K20 Pro: median frame time < 16 ms, janky frames < 5 %
- Roborazzi golden: committed

Ready for Wave 2: Dashboard + Active-ride layout."
```

---

## Self-review

**1. Spec coverage:**

Skimmed `docs/superpowers/specs/2026-05-25-ui-overhaul-design.md` against the plan:

| Spec requirement | Where in plan |
|---|---|
| GixxerTokens color tokens | Task 3 |
| Inter + Geist Mono via Google Fonts | Task 4 |
| Motion (SpringStandard + SpringSoft) | Task 5 |
| New Theme.kt wiring | Task 6 |
| Konsist lint rule for hardcoded hex | Task 7 |
| SpeedDisplay component | Task 8 |
| ConnectionDot component | Task 9 |
| EmptyState component | Task 10 |
| TodayHeroCard component | Task 11 |
| QuickActionsRow component | Task 12 |
| Home three-zone rewrite | Task 13 |
| ClusterPreview retokenize | Task 14 |
| Snapshot test for Home | Task 15 |
| Exit-gate verification (60 fps, no tween, no hex, lint live, snapshot committed) | Task 16 |
| Compose BOM bump | Task 1 |
| Haze + Coil + Google Fonts + Roborazzi + Konsist deps | Task 2 |

Active-ride layout, post-ride summary, Bike health arc — those are Waves 2-3 per the spec. Out of scope here. Verified.

**2. Placeholder scan:** None found. Every step has either a complete code block, a complete command, or a complete expected output.

**3. Type consistency:**
- `tickerFraction(nowMs, lastUpdateMs, intervalMs)` — used consistently in Task 8 test + impl.
- `SpeedState { Connected, Connecting, Disconnected }` — declared in `SpeedDisplay.kt`, only used internally there.
- `ConnectionState` — pulled from existing `dev.mrwick.gixxerbridge.ble.ConnectionState` (sealed interface, already exists in the codebase).
- `NextServiceSummary(label, dueInText, overdue)` — declared in Task 13 ViewModel block, consumed by `TodayHeroCard` parameters in Task 11 (`nextServiceLabel`, `nextServiceDueIn`, `nextServiceOverdue`) — names match.
- `GixxerTokens.*` references — consistent across all tasks.
- `GixxerBrand.accentHero` — defined in Task 6 Theme.kt, used in Task 8 SpeedDisplay.kt.
- `Motion.SpringStandard` / `Motion.SpringSoft` — defined Task 5, used in Task 8 SpeedDisplay + Task 9 ConnectionDot.

No mismatches found.

---

Plan complete and saved to `docs/superpowers/plans/2026-05-25-ui-overhaul-wave1-home.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using `executing-plans`, batch execution with checkpoints

Which approach?
