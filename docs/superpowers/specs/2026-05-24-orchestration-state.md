# GixxerBridge Orchestration State

> Live tracker of what subagents are running, returned, and outstanding.
> Updated as I dispatch / receive agents. Read-only for the rest of the project.

## Phase status

| Phase | Status | Notes |
|-------|--------|-------|
| Phase 0 — Env setup | not started | JDK17 install + Android cmdline tools + project skeleton |
| Phase 1 — Research | not started | 4 parallel R-agents |
| Phase 2 — Module impl | blocked on Phase 1 | 6 parallel C-agents |
| Phase 3 — Core integration | blocked on Phase 2 | serial, me |
| Phase 4 — Feature screens | blocked on Phase 3 | 5 parallel C-agents |
| Phase 5 — Build + smoke | blocked on Phase 4 | serial |

## Agent registry

| Agent | Type | Goal | Started | Completed | Output location |
|-------|------|------|---------|-----------|-----------------|
| R1 | research | Google Maps nav notification format on current Android | 2026-05-24 ~now | DONE | A1/A2/A16 resolved; **MAJOR PIVOT** — Maps uses RemoteViews not extras; see decisions log |
| R2 | research | WMO -> Suzuki weather code mapping (reads C.r() source + Open-Meteo docs) | 2026-05-24 ~now | DONE | A4 resolved; full mapping table ready for `weather/WeatherCodeMap.kt` |
| R3 | research | Android env: AGP/JDK/SDK versions, fg service, SMS, MediaSession, BLE write types | 2026-05-24 ~now | DONE | All Q1-Q10 answered; A5/A6/A7/A8/A9/A10/A11/A14/A15/A19 resolved; **API 36 not 34**, AGP 9.2.0, BG-FGS-start has BLE exception |
| R4 | inline | BLE write semantics of existing Python tools | 2026-05-24 ~now | DONE | A3 resolved in assumptions log |

## Decisions made / pivots

| Date/time | Decision | Why | Spec sections affected |
|-----------|----------|-----|----------------------|
| 2026-05-24 R1 | **GoogleMapsParser switches from `Notification.extras` to RemoteViews walking** | R1 verified Maps uses a custom RemoteViews layout, not text extras. Standard `EXTRA_TITLE` etc. are empty. Only working path: `Notification.Builder.recoverBuilder(ctx, n).createBigContentView()` → inflate → walk view tree → match by `resource-entry-name` (`nav_title`, `nav_description`, `nav_time`, `nav_notification_icon`). Open-source reference: 3v1n0/GMapsParser (but ~5 years old + broken on Android 12+ without an unmerged fix). | Architecture (nav/), Data flow, Risks |
| 2026-05-24 R1 | **Maneuver detection switches from icon-resId-lookup to bitmap classification + text fallback** | The maneuver icon comes through as a `Bitmap` on a Maps-internal ImageView, not a stable resource id. Strategy: (1) perceptual hash of the bitmap, lookup table built empirically from known Maps icons → Mappls maneuver id; (2) text fallback parses `nav_description` for "Turn left/right/U-turn..." keywords; (3) default to maneuver 8 (generic arrow) when both fail. | nav/ subtree (new: MapsBitmapClassifier.kt) |
| 2026-05-24 R1 | **Empirical pre-coding step added: dump real Maps notification on K20 Pro before parser is written** | GMapsParser broken on Android 12+, "still works in 2026?" issue unanswered. Must verify on Arjun's device that (a) RemoteViews path even works, (b) entry names still match, (c) the Android 12+ context workaround `createPackageContext("com.android.systemui")` is needed. | Pre-Phase 2 step: `tools/dump_maps_notification.py` or similar adb-driven dump |
| 2026-05-24 R2 | **WMO→Suzuki mapping locked + WeatherCodeMap.kt ready to drop in** | All 12 Suzuki weather codes enumerated from C.java source. 25+ WMO codes mapped. Ambiguities flagged. | weather/WeatherCodeMap.kt |
| 2026-05-24 R3 | **Bumped compile/target SDK to 36 (Android 16); minSdk stays 29** | LineageOS 23 = Android 16 = API 36; targeting 34 would miss platform features and ship a warning | spec build matrix |
| 2026-05-24 R3 | **Locked toolchain: AGP 9.2.0, JDK 17, Kotlin 2.3.21, Compose BOM 2026.05.01, Material3 1.4.0** | These are the latest stable, all pin together; JDK 17 is non-optional (JDK 26 unsupported by AGP 9.2.0) | build.gradle.kts files |
| 2026-05-24 R3 | **FGS start strategy: user-tap launch only; no background-start of the BLE service** | Android 14+ background-FGS-start has no BLE exemption; bike-key-on auto-connect must rely on `autoConnect=true` on an already-running service, not on starting the service from background | UI/UX (pairing screen flow), AndroidManifest |
| 2026-05-24 R3 | **SMS strategy: NotificationListener only, skip READ_SMS/RECEIVE_SMS** | Play policy disallows them for non-default SMS apps and they're unreliable in modern Android. Notification on system Messages package gives us the same content. Bonus: also captures iMessage-style notifications, RCS chats, etc. | spec permissions table, notifications/PhoneEventBridge → NotificationListener dispatch |
| 2026-05-24 R3 | **BLE writes: WRITE_TYPE_DEFAULT for all frames (revising earlier mixed strategy)** | At 1 Hz we have plenty of budget; the `onCharacteristicWrite` callback gives us stall detection for free. Only revisit if profiling shows we're close to the connection-interval budget | ble/BleClient.kt, FrameWriter.kt |
