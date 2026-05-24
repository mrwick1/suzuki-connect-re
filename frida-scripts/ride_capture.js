/*
 * ride_capture.js — Frida hook script for the Suzuki Connect app.
 *
 * Purpose: capture the Java-side semantic context during a ride that pure HCI
 * snoop can't see. Hooks every BLE write, every cluster-data receive, every
 * weather/signal update, and every nav-state flag toggle. Emits one JSON line
 * per event to stdout — pipe to a file with frida -U -l ride_capture.js -f
 * suzuki.com.suzuki -o /tmp/ride.log  (or use frida-trace).
 *
 * Pair this with the HCI snoop log for ground truth:
 *   adb pull /data/misc/bluetooth/logs/btsnoop_hci.log captures/ride-N.pcap
 * Then cross-reference timestamps to map Java-side events to wire bytes.
 *
 * Usage on Arjun's setup (from CLAUDE.md — frida-server running on phone):
 *   1. Push this script via frida-tools on the laptop
 *   2. Spawn the app:
 *      frida -U -l frida-scripts/ride_capture.js -f suzuki.com.suzuki -o /tmp/ride.log --no-pause
 *   3. Enable Bluetooth HCI snoop log in dev options on the phone
 *   4. Take the ride
 *   5. After the ride: kill the app, pull btsnoop_hci.log + grab /tmp/ride.log
 *
 * Each emitted line is one JSON object — easy to parse with jq or a Python
 * one-liner. Timestamps are millis since the script attached (relative — use
 * adb shell date during attach to anchor to wall-clock).
 *
 * Field meanings come from DISCOVERIES.md 2026-05-24 protocol decode.
 */

"use strict";

const T0 = Date.now();
const emit = (type, payload) => {
    const line = JSON.stringify({ t: Date.now() - T0, type, ...payload });
    send(line);
    console.log(line);
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function bytesToHex(arr) {
    if (arr === null) return null;
    let out = "";
    for (let i = 0; i < arr.length; i++) {
        const v = arr[i] & 0xff;
        out += (v < 16 ? "0" : "") + v.toString(16);
    }
    return out;
}

// Decode a 30-byte frame's type byte + a few key field positions, mirroring
// tools/protocol.py. Just enough to label events without depending on the
// Python lib at runtime. Full decode is done offline.
function classifyFrame(hex) {
    if (!hex || hex.length !== 60) return { ok: false, reason: "not_30_bytes" };
    if (hex.substr(0, 2) !== "a5" || hex.substr(58, 2) !== "7f") {
        return { ok: false, reason: "bad_envelope" };
    }
    const typeByte = hex.substr(2, 2);
    const names = {
        "31": "a531_NAV",
        "32": "a532_CALL",
        "33": "a533_HEARTBEAT",
        "34": "a534_MISSED_CALL",
        "35": "a535_SMS",
        "36": "a536_IDENTITY",
        "37": "a537_TELEMETRY",
    };
    return { ok: true, frame: names[typeByte] || ("unknown_" + typeByte) };
}

// ---------------------------------------------------------------------------
// Main hooks — installed inside Java.perform
// ---------------------------------------------------------------------------

Java.perform(function () {
    emit("attach", { ts_unix_ms: Date.now() });

    // --- (1) MyBleService.f(byte[], int) — every phone -> bike write ---
    try {
        const MyBleService = Java.use("com.suzuki.services.MyBleService");
        MyBleService.f.overload("[B", "int").implementation = function (bArr, tag) {
            const hex = bytesToHex(bArr);
            const cls = classifyFrame(hex);
            emit("tx", { tag: tag, hex: hex, ...cls });
            return this.f(bArr, tag);
        };
    } catch (e) {
        emit("hook_error", { hook: "MyBleService.f", err: String(e) });
    }

    // --- (2) Every onClusterDataRecev (a537 RX path) ---
    // 6 EventBus subscribers, all take com.suzuki.pojo.C0944c with
    // .b (byte[]). Hook the ones with substantive parsing.
    const rxClasses = [
        "com.suzuki.activity.HomeScreenActivity",
        "com.suzuki.activity.NavigationActivity",
        "com.suzuki.activity.RouteActivity",
        "com.suzuki.application.fragment.C",
    ];
    rxClasses.forEach(function (clsName) {
        try {
            const cls = Java.use(clsName);
            cls.onClusterDataRecev.implementation = function (event) {
                const bArr = event.b.value;
                const hex = bytesToHex(bArr);
                emit("rx", { subscriber: clsName, hex: hex });
                return this.onClusterDataRecev(event);
            };
        } catch (e) {
            emit("hook_error", { hook: clsName + ".onClusterDataRecev", err: String(e) });
        }
    });

    // --- (3) Weather + temperature setters (a533 bytes 21-22 inputs) ---
    try {
        const C = Java.use("com.suzuki.application.fragment.C");
        C.q.implementation = function (s) {
            emit("weather_temp_set", { input: s ? s.toString() : null });
            return this.q(s);
        };
        C.r.implementation = function (s) {
            emit("weather_code_set", { input: s ? s.toString() : null });
            return this.r(s);
        };
    } catch (e) {
        emit("hook_error", { hook: "C.q/C.r", err: String(e) });
    }

    // --- (4) C.I phone signal-strength assignment ---
    // The TelephonyManager callback `B.onSignalStrengthsChanged` writes
    // c.I after computing the signal level. Hook the entry point and log
    // the final c.I value via a setTimeout — simpler than hooking each
    // iput-object site that ProGuard split it into.
    try {
        const B = Java.use("com.suzuki.application.fragment.B");
        B.onSignalStrengthsChanged.implementation = function (ss) {
            this.onSignalStrengthsChanged(ss);
            // After the original ran, c.I reflects the new value.
            // 'this.a' (in obfuscated B) is the C instance.
            try {
                const newVal = this.a.value.I.value;
                emit("signal_strength", { c_I: newVal ? newVal.toString() : null });
            } catch (e2) {
                emit("signal_strength", { err: String(e2) });
            }
        };
    } catch (e) {
        emit("hook_error", { hook: "B.onSignalStrengthsChanged", err: String(e) });
    }

    // --- (5) A0 nav-state flag toggles ---
    // These drive a531 byte 23. Field writes are not hookable via Java.use
    // directly (Frida hooks methods, not fields). Instead, hook the methods
    // KNOWN to write them, per DISCOVERIES.md 2026-05-24 enumeration:
    //   A0.B(boolean)  -> writes A0.Z (GPS lost flag)
    //   A0.C(...)      -> also writes A0.Z (Mappls nav-model handler)
    //   y0.onReceive   -> writes A0.v0 (network connectivity)
    //   Mappls routing/f.run -> writes A0.a0
    //   Mappls routing/i.m   -> writes A0.b0
    //   Mappls routing/i.b + addNavigationListener -> writes A0.X
    // We hook the Suzuki-side ones; Mappls SDK writes will be visible via
    // their downstream impact on the a531 frame anyway.
    try {
        const A0 = Java.use("com.suzuki.application.fragment.A0");
        A0.B.implementation = function (z) {
            emit("flag", { field: "A0.Z", source: "A0.B", val_set_to: z });
            return this.B(z);
        };
    } catch (e) {
        emit("hook_error", { hook: "A0.B", err: String(e) });
    }

    try {
        const y0 = Java.use("com.suzuki.application.fragment.y0");
        y0.onReceive.implementation = function (ctx, intent) {
            // y0 is multi-purpose ProGuard-merged receiver — case 0 sets A0.v0
            // from CONNECTIVITY_ACTION. Log every onReceive; downstream we
            // can filter.
            emit("flag", { field: "A0.v0_or_other", source: "y0.onReceive",
                           action: intent.getAction() });
            return this.onReceive(ctx, intent);
        };
    } catch (e) {
        emit("hook_error", { hook: "y0.onReceive", err: String(e) });
    }

    // --- (6) ACTION_BATTERY_CHANGED -> C.G assignment (phone battery) ---
    // Already known semantic; log to confirm timing vs a533 sends.
    try {
        const z = Java.use("androidx.appcompat.app.z");
        z.onReceive.implementation = function (ctx, intent) {
            const action = intent.getAction();
            if (action === "android.intent.action.BATTERY_CHANGED") {
                emit("battery_changed", { ts: Date.now() - T0 });
            }
            return this.onReceive(ctx, intent);
        };
    } catch (e) {
        emit("hook_error", { hook: "z.onReceive", err: String(e) });
    }

    // --- (7) BLE connection state ---
    try {
        const C0855q0 = Java.use("com.suzuki.activity.C0855q0");
        C0855q0.b.implementation = function (bleDevice, gatt) {
            emit("ble_connect_success", {
                mac: bleDevice.b().toString(),
                name: bleDevice.c() ? bleDevice.c().toString() : null,
            });
            return this.b(bleDevice, gatt);
        };
        C0855q0.c.implementation = function (bleDevice) {
            emit("ble_disconnected", { mac: bleDevice ? bleDevice.b().toString() : null });
            return this.c(bleDevice);
        };
    } catch (e) {
        emit("hook_error", { hook: "C0855q0", err: String(e) });
    }

    emit("hooks_installed", {
        notes: "If any hook_error events appear above, the obfuscated class/method " +
               "name has changed since the APK we decompiled (base.apk SHA256 in NOTES.md). " +
               "Re-derive from a fresh decompile."
    });
});
