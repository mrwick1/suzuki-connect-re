# Discoveries Log

> Chronological log of what we learned, when, what we assumed at the time, and whether assumptions held up. Counterpart to `NOTES.md` (which is the polished current-state spec). When an assumption gets contradicted later, that's an entry — not a silent rewrite.

## 2026-05-23 — M0 session

### Setup phase findings

**Bike's BLE chip is Texas Instruments** (OUI `74:b8:39` → TI). Common BLE SoC vendor for automotive/IoT — confirms standard BLE stack, not exotic firmware.

**Bike's advertised name follows pattern `SBM<digits>`** — looks serial-derived. Worth noting for any future RE'd bike capability: the name might encode model+serial in a parseable way.

**LineageOS A16 + KSU peculiarity**: KSU's `su` binary is NOT on adb's PATH (UID 2000), even after granting Shell root in KSU Manager. Workaround: enable "Rooted debugging" in LineageOS Developer Options → `adb root` works → full root adb shell.

**Pacman cache eats space**: aside note from CLAUDE.md — `paccache.timer` not enabled. Not relevant here but noted.

### Wireshark dissector misfire (assumption made, then walked back)

**Initial assumption (after first HCI scan)**: Wireshark labeled the bike's active handles (0x001e write, 0x0020 notify) as "Public Key Open Credential (PKOC): ICCE Digital Key / Aliro". I interpreted this as Suzuki using a real automotive digital-key spec, which would have made the project significantly harder (PKOC = public-key challenge-response; Gate 2 would require defeating a real crypto handshake).

**Validation**: Did the direct GATT walk via bleak from laptop. Got the actual UUIDs.

**Result: ASSUMPTION WAS WRONG.**
The bike's vendor characteristics are `0000fff1-0000-1000-8000-00805f9b34fb` and `0000fff2-...` — simple 16-bit "vendor specific" UUIDs in the `0xFFF0` range, NOT in the PKOC UUID namespace. Wireshark's dissector pattern-matched on something incidental (maybe handle position in CBOR/Aliro test fixtures) and mislabeled.

**Lesson**: Wireshark's protocol labels are heuristic and can mislead. Always cross-verify with the actual UUIDs from a clean GATT walk.

### Protocol surface is much simpler than feared

**Finding**: The bike exposes exactly ONE Suzuki vendor service (`0xFFF0`) with TWO characteristics:
- `0xFFF1` (write only) — phone → bike
- `0xFFF2` (notify only) — bike → phone

Plus the standard GAP/GATT/Device Information services.

**Implication for plan**: M5 doesn't need to identify "which characteristic to target for nav" — there's only one write char and everything goes through it. Filtering captures is trivial.

### Payload analysis (preliminary — done during M0 because data was sitting there)

**Finding**: All 30-byte frames. Plaintext ASCII. Common structure: `0xA5 <ASCII-digit type> <ASCII payload + 0xFF padding> <checksum> 0x7F`.

**Four message types observed in 18-min capture:**
- `0xA5 0x31` (type '1', 2988 writes, ~2.7/sec): display update — pushes time + distance fields to bike for cluster rendering
- `0xA5 0x33` (type '3', 446 writes, ~0.4/sec): phone heartbeat with incrementing counter
- `0xA5 0x36` (type '6', 36 writes, episodic): user name push ("ARJUN")
- `0xA5 0x37` (type '7', 163 notifies, every 5 sec): bike heartbeat with sequence counter

**Assumption made and being held until contradicted**: The bike's cluster display is "dumb" — it just renders ASCII strings the phone pushes. The bike's MCU doesn't compute display content. This means Phase 3 Branch A (custom display) is much more open than originally scoped — likely we can show arbitrary text in the cluster.

**TO VERIFY** (in later milestones): send a custom string in an `a531` message and observe the cluster.

### Telemetry assumption: WRONG (caught by user, 2026-05-23)

**Initial assumption I stated**: "The bike doesn't push live telemetry over BLE; the notify stream is pure heartbeat. **Therefore Phase 3 Branch B (telemetry dashboard) is dead.**"

**Why I said this**: All 163 notifies in the M0 capture had identical content prefix, only a sequence-counter byte changing. No RPM/throttle/fuel/speed data observable.

**Arjun's pushback**: "But the app shows fuel level, odometer, trip A/B, last bike location. Where does that data come from? Did we miss something?"

**Result: MY CONCLUSION WAS WRONG. Right observation, wrong inference.**

The Gixxer SF 150's Suzuki Connect TCU has its own embedded cellular SIM. Telemetry uploads to Suzuki's cloud over cellular, *independently of the paired phone*. The Suzuki Connect app reads from cloud via HTTPS, not from the bike via BLE.

Evidence I should have considered:
- "Last bike location" works when the phone isn't even nearby — only possible if bike phones home on its own
- Industry-standard architecture (Tesla, BMW, Honda all work this way)
- Our 18 min of BLE shows zero telemetry — that's consistent with telemetry going elsewhere, not with "telemetry doesn't exist"

**Corrected verdict for Phase 3 Branch B**: NOT dead. Path is "MITM Suzuki's HTTPS cloud API" rather than "subscribe to bike BLE telemetry." Possibly more powerful (cloud likely has historical data, not just real-time).

**Lesson**: Absence of evidence in one channel ≠ evidence of absence. Map *all* data channels (BLE + cellular + cloud API + app local storage) before concluding capability gaps.

### Open puzzle: variable checksum on identical visible payloads

**Finding**: 36 `a536` ("ARJUN") write messages have byte-identical visible payloads, but two distinct trailing checksum values (`46 f7 7f` early in capture vs `52 03 7f` later). A pure CRC of the visible payload would give one answer.

**Hypothesis (untested)**: The checksum involves hidden state — likely a session counter or HMAC key established during pairing handshake. The bytes we see as "checksum" may be a function of `(payload, hidden_session_state)`.

**Why this matters**: This is the hardest problem for Gate 2 ("send valid third-party message"). If the checksum is HMAC-keyed off the pairing handshake, we cannot construct valid messages from a fresh client without first replicating the handshake. The M0 capture already contains the pairing handshake — recoverable from earliest packets.

**To resolve in M4**.

### Open puzzle: turn-arrow encoding location

**Finding**: We expected explicit "TURN_LEFT" / "TURN_RIGHT" message types in writes. We don't see them, but the bike DOES show turn instructions when navigating.

**Hypothesis**: Turn type + distance are embedded in `a531` payload fields we haven't decoded. The bytes we're currently reading as "distance" (e.g., `05.6K`) may actually be `<direction-code><distance>`.

**To resolve in M5** with targeted captures (force the bike through specific turn types, diff the payloads).

---

## 2026-05-23 — Exhaustive capture re-analysis (in response to Arjun's pushback)

### What prompted this

Arjun called out that I'd only been spot-checking the capture, not analyzing it fully. He observed: "the app shows fuel/odo/trip/last-location even with the bike OFF, and there's a 'last sync time' displayed — so where does that data come from? Don't proceed on assumptions."

Both criticisms were correct.

### Full-capture analysis (all 7300 ATT packets across 18 min)

**ATT opcode distribution across the ENTIRE capture:**

| Opcode | Name | Count |
|--------|------|-------|
| 0x12 | Write Request | 3474 |
| 0x13 | Write Response | 3473 |
| 0x1b | Handle Value Notification | 163 |
| 0x08 | Read By Type Request | 64 |
| 0x01 | Error Response | 40 |
| 0x09 | Read By Type Response | 34 |
| 0x10/0x11 | Read By Group Type Req/Resp | 11/11 |
| 0x02/0x03 | Exchange MTU Req/Resp | 5/5 |
| 0x04/0x05 | Find Information Req/Resp | 10/5 |
| 0x06/0x07 | Find By Type Value Req/Resp | 5/5 |
| **0x0a** | **Read Request** | **0** |

### Key derived facts

- **Zero data reads (opcode 0x0a) across the entire 18-min session.** The phone never directly reads any characteristic value. All non-write/notify activity is GATT discovery (Read By Type/Group, Find Info).
- **5 separate BLE connection events**, evidenced by 5 distinct MTU exchanges at 63s, 296s, 620s, 661s, 672s. Each reconnect triggers a fresh GATT discovery cycle. The bike's service tree is identical across all 5 discoveries.
- **Bike caps MTU at 65 bytes** even when phone requests 517. This caps payload size at ~30 bytes (after ATT headers), explaining why all observed application messages are 30 bytes.
- **40 Error Responses are all "Attribute Not Found"** — normal discovery noise from the phone sweeping handle ranges that don't exist on the bike. Not evidence of failed auth or anything interesting.
- The bike's vendor service is at `0xFFF0` with characteristics `0xFFF1` (write) and `0xFFF2` (notify) — same as the GATT walk confirmed, no hidden additional services.

### Observed (not hypothesis): no telemetry in this BLE capture

Across 5 connection events / 7300 ATT packets in the 18-min M0 session:
- Zero `Read Request` operations (opcode 0x0a) from phone to bike.
- Zero non-heartbeat notifications from bike to phone (per-byte analysis of all 163 notifies confirms only positions 25 [sequence] and 28 [checksum] vary; all other 28 bytes are constant).
- Per-byte analysis of all 3470 writes confirms: ASCII time/distance/units (`a531`), incrementing phone counter (`a533`), user identity (`a536`). No telemetry-shaped data echoed from bike.

This is rigorous evidence that **this specific capture does not contain bike telemetry exchange**. It does NOT prove the BLE protocol can never carry telemetry — only that no telemetry crossed in these 18 minutes.

### Wrong claim made then walked back: "data comes from Suzuki cloud via embedded SIM"

**What I claimed**: Fuel/odo/trip/last-location must come from Suzuki cloud via HTTPS, populated by the bike's cellular TCU uploading independently of the phone. Cited "industry-standard architecture (Tesla, BMW, Honda)."

**What's actually true**:
- Arjun confirmed: the Gixxer SF 150 has NO physical SIM card. He's inspected the bike.
- Web search of Suzuki India's product page for Gixxer SF describes connectivity as "Bluetooth-enabled digital console" with mobile app integration. No mention of embedded SIM, telematics, or cellular.

**Therefore the cellular-TCU theory is wrong.** The bike cannot upload data to Suzuki cloud independently. If telemetry reaches Suzuki cloud at all, it must go via the paired phone.

**Lesson**: I extrapolated from "what most connected vehicles do" to "what this specific bike does" without verification. Industry-standard architecture for premium cars does not apply to a Rs. 1.4L Indian motorcycle.

### Updated possibility space for where app's fuel/odo/trip/last-location come from

Now that the cellular-TCU hypothesis is dead, three possibilities remain:

**(P1) Cached data from main-phone sessions, served via Suzuki cloud**
The spare phone is logged into Arjun's existing Suzuki Connect account. His MAIN phone has been pairing with this bike for months. During main-phone sessions, the bike pushes telemetry to main phone via BLE (in events we haven't captured), main phone uploads to Suzuki cloud. Spare phone, on first login, downloads cached values from cloud — even though spare itself has never received this data over BLE.

*Evidence in support*: explains why app shows values immediately after install on spare phone, even before the bike connected to spare phone. Account-bound cloud cache is a common pattern.

*To verify*: log out of Suzuki Connect on spare phone, observe what disappears. Or: check what "last sync time" displays — if it's older than the spare phone's first pairing today, this is the explanation.

**(P2) Bike DOES push telemetry over BLE during specific lifecycle events we didn't capture**
The 18-min M0 session was: connect → set destination → drive a bit → set another destination → walk. We did NOT cleanly trigger: engine start, engine stop, ignition cycle while paired, trip end button in app, manual sync gesture, app foreground/background transition.

*Evidence in support*: notify channel has the right shape (notify can carry bulk data), bike's BLE chip definitely has access to ECU state. Just need to trigger the right event.

*To verify*: run a laptop-as-Central listener (Arjun's suggested approach), trigger each lifecycle event sequentially, observe what new messages appear.

**(P3) Values shown in app are stale / never update**
The fuel/odo/trip values in the app might have been written at a prior point (initial pairing setup, last service center visit, etc.) and just never refresh. The "last sync time" might be a connection check-in, not a data refresh.

*To verify*: ride bike (consume measurable fuel), reconnect spare phone, see if fuel level changes in app. If never updates → confirmed stale.

### Implication for Phase 3 Branch B (telemetry dashboard) — REVISED

Previous claim that this is "dead via BLE, viable via cloud" was based on the wrong SIM assumption. With cellular ruled out:

- If P1 or P2 is true: telemetry IS reaching the phone (or could, on the right event/account). A telemetry dashboard would either pull from Suzuki cloud (P1 path) or subscribe to BLE during specific events (P2 path).
- If P3 is true: there's no live telemetry anywhere; the app values are decorative.
- We don't know which is true and must verify before scoping Phase 3-B.

### Lessons reinforced

1. **Don't sample when asked to analyze.** (Earlier lesson, repeated.)
2. **"Industry-standard architecture" claims are guesses, not facts.** What Tesla does is not what Suzuki does.
3. **Don't conclude an upstream source when the absence in one channel could mean many things.** "Not in BLE" could mean: not in BLE *now*, not in BLE *at all*, in BLE *during events not yet captured*, or the data never updates.
4. **Web search is unverified context, not proof.** The Suzuki product page mentioning Bluetooth-only is consistent with no-SIM but doesn't prove it; Arjun's physical inspection does.
5. **Walk back wrong claims explicitly in this log.** Don't just rewrite the spec — leave a record of the mistake so the next session understands the journey.

---

## 2026-05-23 — Laptop-as-Central live experiments

### Setup
Built two new tools under the no-assumptions rule:
- `tools/passive_listen.py` — connect to bike, subscribe to notify, listen.
- `tools/provoke_and_listen.py` — connect, subscribe, send captured `a536` identity + loop captured `a533` heartbeat, listen.
- `tools/send_custom.py` — write custom payloads to `0xFFF1` (for Phase B replay/forge test, not yet run).

Bike re-keyed and put in pairing mode (after a power cycle to clear BlueZ state).

### Test 0 — Passive listen alone produced ZERO notifications

Ran `passive_listen.py` for 30+ seconds: subscribed to `0xFFF2`, did NOT send any writes. Result: zero notifications received. Contradicted my earlier assumption that the bike pushes heartbeats autonomously to any subscribed Central.

**Therefore (new confirmed fact)**: the bike begins streaming `a537` heartbeats only AFTER the Central writes the first message (identity push). Notify is response-driven, not autonomous.

### Test 1 — Provoke + listen

Ran `provoke_and_listen.py` for 30s: subscribed → sent captured ARJUN identity → looped captured heartbeat every 1s. Received 7 notifications.

First major observation: at +23.34s, ONE notification differed from the baseline:
- Baseline position 4: `0x30` ('0'), checksum 0x3a
- That one: position 4: `0x32` ('2'), checksum 0x3c (delta +2)

**Both byte changes are +2.** This confirmed the checksum algorithm: **simple byte sum mod 256** over positions `[1:28]`. Verified mathematically — if checksum is `sum(payload[1:28]) mod 256`, then bumping any payload byte by 2 bumps checksum by 2.

This is a major unlock for Gate 2 — we can now compute valid checksums for forged messages.

### Test 2 — 90-second passive observation (engine state unknown)

Ran with Arjun doing nothing on the bike. Position 25 (after `N4`) drifted monotonically downward across the test: X → W → V → U (one decrement every 20-40 seconds).

### Test 3 — 95-second trigger session (engine OFF for first half, ON for second half)

Arjun deliberately started engine around +45s. Result:
- Before +45s (engine off): position 25 fell T → S (continuing the cooling trend from Test 2)
- After +45s (engine on): position 25 climbed S → T → U → V → W → X → Y (warming)

He also did horn presses, throttle revs, indicator on/off during the session. **None of these produced any visible change in any byte position OR any new message type.** The notify stream stayed pure `a537` with position 25 (temperature) as the only varying field.

Session ended at +95s with a `BleakGATTProtocolError: Unlikely Error` from the bike — likely caused by:
- (a) Bike's BLE disconnect-on-engine-off behavior (he had just killed the engine)
- (b) Our `a533` heartbeats use a STATIC captured counter (`050154`) instead of incrementing it like the legitimate phone does (`050154 → 050155 → ...`). Bike may tolerate this briefly then drop.

### Confirmed facts from these tests

1. **Position 25 of the `a537` notify encodes engine coolant temperature in degrees C** (observed range 0x53-0x59 = 83-89 °C, decrementing while cool, incrementing while warm).
2. **Checksum algorithm is `sum(payload[1:28]) mod 256`** — simple byte sum, no hidden state, no HMAC. Gate 2 substantially unblocked.
3. **Notify is response-driven**, not autonomous. Central must write first.
4. **Bike's notify stream is sparse**: only the `a537` heartbeat type exists, and most of its bytes are static. Horn/rev/indicator/etc. do NOT produce notify events in this firmware (or they go via a channel we haven't found).
5. **Bike's BLE disconnects shortly after engine off** (likely auto-disconnect feature).

### Things still NOT known

- Whether OTHER bike events (riding, hard braking, fault conditions, low fuel, trip end) push different notify types or change other byte positions. None of those were triggered in this short experiment.
- Whether the static `0x33` heartbeat counter is what caused the disconnect, or if engine-off triggered it independently.
- Whether the bike will accept WRITES from our laptop and display custom content on the cluster (Phase B test — not yet run).
- Whether fuel level / odometer / trip data is encoded anywhere we haven't decoded.

### Walked-back assumption

Previously documented (twice) that the M0 capture proved "no telemetry in the bike's notify stream." That was technically true for the M0 capture but misleading as a general statement. The M0 capture happened with the bike at a stable engine-warm state (no transitions), so position 25 didn't move. This new test proves position 25 DOES move — it's a real telemetry field; the M0 capture just didn't trigger a change.

**Lesson**: "no variation in our 18-min sample" does not equal "the field is static in the protocol." Need to trigger state changes deliberately to map telemetry.

---

## 2026-05-23 — Phase B forge experiment + WITH-SIM capture

### Phase B: full diag with forged messages (no SIM)

Built `tools/bike_full_diag.py` and ran one connection with all 8 test steps:
1. ARJUN identity ✓ WRITE OK
2. a533 NETWORK=YES (×2) ✓ both WRITE OK
3. Captured a531 display replay (time=0517PM, dist=05.6K) ✓ WRITE OK
4. a531 with modified time "9999" ✓ WRITE OK
5. a531 with arbitrary text "HELLO BIKE" ✓ WRITE OK
6. a536 with name "Arch Linux" ✓ WRITE OK
7. 20s maintenance loop (heartbeat + a531 every 2s) ✓ all WRITES OK
8. Unknown msg type a534 probe ✓ WRITE OK (no error response, silent accept)

**Bike accepted ALL writes at the protocol level — every write returned OK with no GATT errors.** But the cluster did NOT visibly render any of our content. It stayed in "Searching for network" state throughout.

**Arjun's observation**: "Searching for network" appears in the same display region where nav arrows would appear. So the bike is gating the entire nav-content region behind a network-status check — and our forged YN heartbeat (pos 14 = 'Y') was NOT enough to satisfy that check.

### WITH-SIM ground-truth capture

Arjun installed a SIM card into the spare phone, opened Suzuki Connect, paired with bike. Cluster immediately showed nav arrows (no "Searching for network"). Pulled HCI snoop log: `captures/with-sim-nav-20260523-1840.pcap` (56KB, 1192 packets, 268 writes, 13 notifies). **Backed up to `~/.suzuki-re-backups/` (outside the repo) so we don't lose it.**

### Differential analysis: WITH-SIM vs NO-SIM

Compared per-byte values across `a531`, `a533`, and `a536` messages between WITH-SIM and earlier NO-SIM captures.

**`a533` heartbeat — the discriminating bytes**:

| Position | WITH-SIM (arrows showing) | NO-SIM (searching) | Likely meaning |
|----------|---------------------------|--------------------|----|
| 21 | `0x02` (only value) | `0x01` (early) / `0x05` (late) | **Network type byte**: 0x02 = cellular, 0x05 = WiFi-only, 0x01 = none |
| 22 | `0xc9` (only value) | `0x00` (early) / `0xcb` (late) | **Signal strength**: `0xc9` = -55 dBm signed (strong cellular). `0xcb` = -53 dBm but with type=WiFi means "not real network" to bike. `0x00` = no signal. |
| 14 | `'N'` always | `'N'` mostly / `'Y'` 2 messages | NOT the gating field. Pos 14 was 'N' in WITH-SIM despite arrows showing. Hypothesis P1 (YN = network) was wrong. |

**`a536` identity — the discriminating byte**:

| Position | WITH-SIM | NO-SIM | Likely meaning |
|----------|----------|--------|----|
| 27 | `'R'` only | `'F'` (early) / `'R'` (late) | **Registration state**: `'R'` = Registered (to a network), `'F'` = Fresh/unregistered |

**`a531` display — most positions are content (time/distance/units)** and naturally differ between captures because the captured display content was different.

### Updated hypothesis (UNVERIFIED — to be tested next session)

To get the bike to clear "Searching for network" and show our display content WITHOUT a real SIM, we need to forge:
- `a533` with **position 21 = 0x02** AND **position 22 = 0xc9** (cellular type + strong signal)
- `a536` with **position 27 = 'R'** (registered)

If sent in a stream, the bike should believe the phone has cellular network and unlock nav rendering.

**Test plan for next session**: build new tool that sends ARJUN identity (with pos 27 = 'R'), then continuous loop of forged a533 (with pos 21 = 0x02, pos 22 = 0xc9) and a531 display content. Observe if cluster clears "searching" and shows our content.

### What we still DON'T know

- **Turn-arrow encoding**: WITH-SIM capture has 216 a531 messages but only positions 12 (minutes-digit of time) and 28 (checksum) vary. The rest are identical. The captured nav session had a destination set but Arjun didn't actually ride toward it. Without traversing real turns, we don't capture turn-instruction message variations. **Resolved only by capturing an actual ride.**
- **Whether forging network bytes will actually defeat the gating**: hypothesis-only until next session test.
- **Where fuel/odo/trip data really comes from**: not BLE (per exhaustive analysis), and not Suzuki cloud via cellular (bike has no SIM). Remaining: maybe Suzuki cloud via the phone (the user's main phone uploads when paired, spare phone reads via cloud), or local cache, or BLE in events we never triggered.
- **The a533 binary bytes 21-22 dynamics in WITH-SIM**: they were CONSTANT at 0x02 0xc9 across all 44 messages. Need a session with varying signal strength to see how the byte changes.

---

## 2026-05-23 — M1 APK static analysis (initial 25-min sprint)

### What we did

1. Identified app package: `suzuki.com.suzuki` (note unusual reverse-domain pattern)
2. Pulled all 4 APK splits: base.apk (191 MB), arm64_v8a, en, xxhdpi (~213 MB total)
3. JADX-decompiled base.apk → 6751 Java files (45 MB) in `decompiled/jadx-out/sources/`
4. Identified that **Suzuki uses the FastBle library** (`com.clj.fastble.*`) for BLE
5. Found key Suzuki-namespace BLE classes (most unobfuscated):
   - `com/suzuki/services/MyBleService.java` — connection orchestrator
   - `com/suzuki/services/work.java` — BLE write helper (`work.g(byte[])` calls FastBle)
   - `com/suzuki/services/f.java` — heartbeat timer (runs every 1000ms, calls `work.g`)
   - `com/suzuki/services/NotificationService.java` — pushes Android notifications to bike cluster
   - `com/suzuki/broadcaster/CallReceiverBroadcast.java` — pushes call events
   - `com/suzuki/broadcaster/IncomingSms.java` — pushes SMS events
   - `com/suzuki/application/fragment/A0.java` — navigation/display refresh message construction (a531)
   - `com/suzuki/application/fragment/B.java` — `PhoneStateListener` for cellular signal
   - `com/suzuki/application/fragment/C.java` — main UI fragment, holds BLE state
   - `com/suzuki/application/SuzukiApplication.java` — contains the checksum function

### Confirmed from source code

**1. Checksum algorithm matches our hypothesis exactly:**

```java
// In com/suzuki/application/SuzukiApplication.java
public static byte a(byte[] bArr) {
    byte b = 0;
    for (byte b2 = 1; b2 <= 27; b2 = (byte) (b2 + 1)) {
        b = (byte) (b + bArr[b2]);
    }
    return K.g
        ? (byte) (255 - (b % 256))   // INVERTED variant — gated by K.g flag
        : (byte) (b % 256);           // STANDARD variant — what we've observed
}
```

This confirms `sum(payload[1:28]) mod 256` exactly. **Plus a new finding**: there's a `K.g` flag that switches to a `255 - (sum % 256)` variant. We haven't seen this variant in our captures yet, but if a future capture's checksum doesn't match the simple sum, this is why.

**2. Frame structure constants confirmed:**
- `bytes[0] = -91` → header `0xA5`
- `bytes[29] = 127` → terminator `0x7F`
- Padding bytes `bytes[i] = -1` → `0xFF`

**3. `f.java` produces an "extra" heartbeat variant we haven't captured.** Its template is `"?31Y00021118370000000000000000"` with positions 4-6 and 14-27 forced to `0xFF`, plus position 14=`HomeScreenActivity.i0`, position 15=`HomeScreenActivity.j0`. This DOESN'T match our captured `a533` content — there's clearly another heartbeat construction path producing the `33Y214...050154NN...` messages we captured. Suzuki has multiple a533 variants for different purposes.

### Walked back (again)

**Previously claimed**: positions 14-15 of `a533` are the network status flag ('Y'/'N').

**Actual truth (from source)**: positions 14-15 are **SMS-present (i0) and Call-present (j0) indicators**, not network status. They're set by:
- `IncomingSms.java:107` sets `i0` (when SMS arrives)
- `NotificationService.java:440/692` sets `i0` (when notification active)
- `CallReceiverBroadcast.java:142` sets `j0` (when call received)
- `C0862u0.java:54/64` sets `j0` (call-cleared timer)

Inverted semantics: `'N'` (0x4E = 78) means **present/active**, `'Y'` (0x59 = 89) means **cleared/none**. So the 1-second 'Y' we saw at t=674s in M0 was probably a brief notification clearing, not a network state.

**Real network status is elsewhere.** B.java reads cellular signal:
```java
// com/suzuki/application/fragment/B.java
int dataNetworkType = telephonyManager.getDataNetworkType();
c.I = "0";       // default no-signal
C.e1 = true;     // flag: no signal
if (dataNetworkType == 20) {  // NETWORK_TYPE_NR (5G)
    int ssRsrp = cellSignalStrengthNr.getSsRsrp();
    if (ssRsrp > -90)       { c.I = "3"; C.e1 = false; }  // strong
    else if (ssRsrp > -105) { c.I = "2"; ... }            // medium
    // ...
}
```

So **`C.I` (the instance field, single ASCII digit "0"/"1"/"2"/"3") holds signal strength** — derived from cellular RSRP. The string value gets embedded into outgoing messages via the `A0.java` construction (one of the `m0`/`n0`/`o0`/`p0`/`q0` template fields). This is the byte we need to forge to defeat "Searching for network."

We previously thought `a533` pos 21=0x02 and pos 22=0xc9 were the network bytes. The reality may be different — `0x02 0xc9` could just be the random binary state of `f.java`'s "extra heartbeat" variant. **Hypothesis to verify**: the actual SIM/network signal is encoded as a single ASCII digit in the a531 (display refresh) `m0`/`n0`/`o0` field — not in a533 at all. To verify by comparing the a531 template fields between WITH-SIM and NO-SIM captures.

### A0.java a531 construction (decoded)

```java
// In A0.java (the navigation/display fragment), line ~483:
String str3 = "?110" + this.p0 + this.m0 + this.n0 + "000"
            + this.q0 + this.o0 + str + str2 + "00000";
bytes = str3.getBytes("UTF-8");
bytes[0] = -91;             // 0xA5
bytes[2] = (byte) i;        // status code: usually 46 ('.'), or saved n1
bytes[3] = -1;              // 0xFF
// ... patches for specific TFT-edition bikes ...
bytes[15] = -1; bytes[16] = -1; bytes[17] = -1;
for (i4 = 25; i4 <= 27; i4++) bytes[i4] = -1;
bytes[28] = SuzukiApplication.a(bytes);
bytes[29] = 127;
```

Where:
- `p0`, `m0`, `n0`, `q0`, `o0` are instance string fields (probably time, distance, units, etc. — need to trace each)
- `str` and `str2` are local vars (likely GPS status + some other state)
- `i` is set to `46` (= ASCII `'.'`) by default, or `n1` (a stored value) when `str` ∈ {"1", "3", "5"}

This explains why our captured a531 had `pos 2 = 0x2e` (=`.`=46) sometimes and other values (`0x01/0x04/0x08`) other times — those were the `n1` saved values from earlier GPS states.

**There's even a model-specific patch in the code**: if the bike is "e-ACCESS", "Access-TFT Edition", or "Burgman Street-TFT Edition" AND `n0` starts with '0', then `bytes[9] = 32` (= ASCII space). So the same protocol is shared across multiple Suzuki models with minor per-model tweaks.

### Updated walked-back claims & open questions

- **Position 14-15 of a533 are NOT network status** (they're SMS/Call). Earlier "YN means network YES" hypothesis is wrong. Position 14 carries SMS state, position 15 carries Call state, both inverted semantics.
- **Network signal is encoded as an ASCII digit string in some a531 template field** (probably `m0`, `n0`, or `o0` from A0.java's construction). Not in a533.
- **The 0x02 0xc9 at a533 positions 21-22 in WITH-SIM** might just be random/uninitialized bytes from the message variant produced by f.java's "Phone Smart status pkt" template (since f.java sets pos 21-22 to 0xFF, but other heartbeat code paths may produce different values). The differential analysis may have caught the wrong correlation.
- **To definitively map signal-strength byte position**: trace `C.I` through A0.java's template fields, identify which template position holds it, then look at our captures at that position.

### 2026-05-23 — Position 23 of a531 CONFIRMED as signal-status byte

By mapping `A0.D()`'s template `"?110" + p0 + m0 + n0 + "000" + q0 + o0 + str + str2 + "00000"` byte-by-byte against the actual captured NO-SIM and WITH-SIM messages, the field lengths and positions are now nailed down:

- `p0` = 4 chars (positions 4-7)
- `m0` = 1 char (position 8) — always "M"
- `n0` = 6 chars (positions 9-14) — **TIME** as `HHMMxM` (e.g., `0517PM`)
- `"000"` literal at 15-17 (overwritten with 0xFF by code)
- `q0` = 4 chars (positions 18-21) — **DISTANCE NUMBER**
- `o0` = 1 char (position 22) — **UNIT**: `K` (km) or `M` (meters)
- **`str` (= `A0.H1`) at position 23** — **SIGNAL STATUS**: `'0'` blocks nav (shows "Searching for network"), `'1'` allows nav
- `str2` (= `A0.I1`) at position 24 — secondary status, both captures showed `'1'`
- `"00000"` literal at 25-29 (positions 25-27 overwritten with 0xFF, 28 with checksum, 29 with 0x7F)

**Empirical confirmation from captures**:
- NO-SIM (WiFi only, cluster shows "Searching for network"): `bytes[23] = '0'` AND `bytes[2] = '.'` (0x2e — degraded mode marker)
- WITH-SIM (cluster shows real nav arrows): `bytes[23] = '1'` AND `bytes[2] = 0x08` (a real Mappls maneuver ID)

This matches `A0.D()`'s code branch: when `str ∈ {"1","3","5"}` (good signal), the real maneuver ID is restored to `bytes[2]`; otherwise it's forced to `46` (`'.'`).

### Forge tool corrected

Replaced earlier (wrong-hypothesis) `forge_network.py` strategy with `tools/forge_signal_v2.py`, which builds an a531 with:
- `bytes[2]` = chosen maneuver ID (default 8, a known real value)
- `bytes[23]` = `'1'` (signal good)
- `bytes[24]` = `'1'`
- All other fields set to look like a valid a531 (time, distance, unit, padding)
- Checksum recomputed via the confirmed `sum(bytes[1:28]) mod 256` algorithm

Sanity-checked: produces `a53108ff303038304d30353137504dffffff30312e354b3131ffffff227f` with verified checksum. NOT YET tested against bike — that's the first thing to try next session.

### Still pending (for next session)

- [ ] Run `tools/forge_signal_v2.py` against bike (key on, Suzuki Connect closed). Expected: cluster clears "Searching for network" and shows arrow icon 8.

## 2026-05-23 — Cloud API architecture mapped from static source

### What we did

Searched the decompiled JADX source (6751 Java files) for HTTPS URLs, Retrofit interfaces, OkHttp clients, and BLE callback registrations.

### What we found

**Suzuki Connect's cloud API is shockingly minimal:**

- Base URL: `https://projects.mapmyindia.com/` (Mappls-hosted, not a Suzuki-owned domain)
- Only TWO endpoints, both license-related:
  - `GET /autolicverify/{BTID}/expiry/date/` → `ExpirationInfoResponse`
  - `POST /autolicverify/...updatePlan` → `PlanUpdateResponse`
- OAuth-style token auth (`TokenResponse.access_token`, stored in SharedPreferences `AppPrefs`)
- Retrofit interfaces at `com.suzuki.interfaces.f` (GET) and `com.suzuki.interfaces.g` (POST)
- Annotations stripped by ProGuard, but interface signatures + callbacks (`InterfaceC1072d`, `InterfaceC1075g`, `retrofit2.P`, `retrofit2.converter.gson.a`) are intact

**This is a big finding**: **there is NO cloud API for fuel/odometer/trip/last-location** in Suzuki Connect. All search variants confirmed it — no Suzuki-owned API domain, no telemetry-related Retrofit endpoints, only the license-verification API.

**Therefore the fuel/odo/trip data MUST come from BLE** (no other channel exists). This contradicts our earlier hypothesis P1 ("data cached from main-phone sessions via Suzuki cloud") — there's simply no cloud API to cache from.

### What this means for the open question

Remaining hypotheses are now narrowed to:
- **(P2 reinforced)**: bike pushes telemetry over BLE during specific events we haven't captured. The notify-callback handler that parses these must exist in the app — we just haven't found it (no class extends `BleNotifyCallback`, suggesting it's set up inline via a lambda or anonymous class). Once found, we can map exactly which incoming message types carry telemetry.
- **(P3 weakened)**: stale local cache. Possible but doesn't explain how Arjun's app got values in the first place. Something must have populated them.

### Architecture confirmed

- **Storage**: Realm Mobile Database. POJO classes at `com.suzuki.pojo.*` are Realm models with obfuscated field names (single letters).
- **App-state singleton**: `com.suzuki.pojo.e` holds ~80 static fields for live session state (boolean flags + ints + strings).
- **BLE wrapper**: FastBle (`com.clj.fastble.*`).
- **Suzuki uses FastBle's `BleGattCallback` (callback.a) and `BleWriteCallback` (callback.b)** — but no extension of `BleNotifyCallback`. The notify subscription is inline somewhere (TBD).

### Next thing to find

Locate the FastBle `.notify(...)` invocation in Suzuki source — specifically, find what method calls `BleManager.notify(bleDevice, serviceUuid, charUuid, callback)` to register for `0xFFF2` notifications. The callback inside that call is what parses the incoming bytes and writes them to Realm. That code is the key to mapping which incoming message types carry fuel/odo/trip vs heartbeat.

## 2026-05-23 — MAJOR FINDING — `a537` notify carries FULL telemetry (we misread it all session)

### What we found

Found the notify call chain end-to-end in source:

1. **Notify subscribe**: `MyBleService.a(BleDevice)` calls `bVar.h(bleDevice, "0000fff0...", "0000fff2...", new com.suzuki.services.c(this, characteristic))` — this is FastBle's `BleManager.notify()`. Picks the 4th GATT service (`getServices().get(3)` = `0xFFF0`) and 2nd characteristic (`getCharacteristics().get(1)` = `0xFFF2`).
2. **FastBle internal dispatch**: `com/clj/fastble/bluetooth/a.java:onCharacteristicChanged` iterates registered callbacks, builds a `Handler.Message` with `what=19`, puts notify bytes in Bundle as `"notify_value"`, posts to a Handler stored on the callback.
3. **Handler processing**: `androidx/localbroadcastmanager/content/a.java:case 19` extracts the byte[], wraps it in a `com.suzuki.pojo.C0944c` event object (`C0944c.a = String, C0944c.b = byte[]`), and posts to **EventBus** (`org.greenrobot.eventbus.d.b().f(c0944c)`).
4. **EventBus subscribers**: 6 classes have `public void onClusterDataRecev(C0944c)` methods that receive the event and parse the bytes:
   - `HomeScreenActivity` (961 instruction units — JADX failed to decompile)
   - `NavigationActivity` (fully decompiled — most complete schema source)
   - `RouteActivity` (decoded)
   - `RiderProfileActivity` (small)
   - `CreateProfileActivity` (small)
   - `com.suzuki.application.fragment.C` (decoded — has fuel parsing)

### Major reveal — `a537` notify is NOT a heartbeat

We've been calling `a537` "the bike heartbeat with constant ID + sequence counter." **That was wrong.**

The actual content (from source parsers):

| Bytes | Field | Decode method |
|-------|-------|---------------|
| 2-4 | **Current speed (km/h)** | 3 ASCII digits → int |
| 5-10 | **Odometer (km, lifetime)** | 6 ASCII digits, strip leading zeros |
| 11-16 | **Trip A** (km, format `XXXXX.X`) | 6 ASCII digits with implicit decimal |
| 17-22 | **Trip B** (km) | Same as Trip A |
| 24 | **Fuel level (1-6 bars)** | byte `'1'-'6'` → 1-6 |
| 25-27 | **Fuel economy / consumption** | 24-bit bitfield; formula varies by bike model |

**Our M0 capture decoded**:
- Speed: 0 km/h (engine off ✓)
- Odometer: **1672 km** (Arjun's actual bike mileage at capture time)
- Trip A: 90491.1 (suspicious — may need model-specific decoding for SF 150)
- Trip B: 98.4
- Fuel: **4 bars / 6** (byte `'4'`)
- Fuel economy: sentinel `0xFFFFFF` = "no data" (engine off)

### What I had WRONG for most of this session

1. **"a537 is pure heartbeat, only sequence counter varies"** — wrong. The byte we tracked as "sequence" at position 25 is the HIGH byte of fuel-economy. It varied because fuel-economy readings vary (and during engine-off, all bytes 25-27 are `0xFF` sentinel — which is what we saw mostly).
2. **"Position 25 of a537 encodes engine coolant temperature"** — wrong. It's the high byte of a 24-bit fuel-economy bitfield. The X→W→V→U pattern I "observed" was likely random variation during engine-off `0xFFFFFF` sentinel + noise.
3. **"Bike doesn't push fuel/odo/trip over BLE"** — VERY wrong. The bike pushes ALL of this every 5 seconds in every `a537` notify. We had it the whole time and didn't decode it.
4. **"Bytes 2-22 are a constant device ID string"** — wrong. They're speed + odometer + trip A + trip B, fully decoded fields.

### What this means for Phase 3 Branch B (telemetry dashboard)

**Branch B is VERY ALIVE.** We have ALL the telemetry data in the BLE notify channel. A dashboard app just needs to:
1. Subscribe to `0xFFF2` notify (which we know how to do)
2. Parse each incoming `a537` with the schema above
3. Display speed / odometer / trip A / trip B / fuel level / fuel economy

No cloud needed. No additional message types to discover. The data has been in front of us this whole session.

### What's STILL unknown

- The 8 specific cases in the larger `HomeScreenActivity.onClusterDataRecev` (961 units, didn't decompile) probably handle MORE message types than just `a537`. May include `a531`/`a533`/`a536` reception cases or different message types entirely (e.g., engine alarms, fault codes, service indicators).
- The `Trip A = 90491.1 km` value is suspicious — there may be model-specific decoding for the SF 150 that the smaller parsers don't capture.
- The `K.g` flag's role in checksum inversion is still untested.

### Lesson reinforced

**Static source analysis catches what dynamic black-box guessing misses.** We spent the entire session trying to infer the protocol from byte patterns in captures, and built increasingly elaborate (and wrong) hypotheses. Reading the decompiled source for 15 minutes gave us the complete answer.

For future projects: **decompile FIRST, capture SECOND.** Use captures to verify hypotheses derived from source, not to guess at structures.
- [ ] Run `tools/forge_network.py` (built by subagent during this session) once bike is on next time, to test the OLD hypothesis (a533 pos 21/22). May fail — see above — but worth one test.
- [ ] Capture an actual ride to get turn-arrow message variations.
- [ ] mitmproxy + Frida SSL bypass to settle fuel/odo/trip data source.

### Pending work from earlier (unchanged)

- [ ] Fix `provoke_and_listen.py` to increment the a533 counter (positions 11-13).
- [ ] More comprehensive byte-position mapping across all captures.

## 2026-05-24 — `a531` nav frame fully decoded from source

### What we did

Hit the same JADX-fails-on-big-method wall on `A0.C(com.mappls.sdk.navigation.model.a)` (2230 instructions) as on `HomeScreenActivity.onClusterDataRecev`. Re-ran JADX with `--show-bad-code --comments-level debug --single-class com.suzuki.application.fragment.A0` — that flag combination decompiled the previously-skipped method successfully. Same trick will likely unblock `HomeScreenActivity` too (haven't tried yet).

### Full a531 byte layout (confirmed)

The 30-byte frame the app writes to the bike (per `A0.D()` + `A0.C()`):

| Pos | Source | Length | Semantic |
|-----|--------|--------|----------|
| 0 | literal `?` → 0xA5 | 1 | Header |
| 1 | literal `'1'` | 1 | Constant (0x31) |
| 2 | literal `'1'` → maneuver ID `i` | 1 | **Maneuver/arrow ID** (mapped from `aVar.f`, the Mappls maneuver lookup value, via the `e0` translation table) |
| 3 | literal `'0'` → 0xFF | 1 | Sentinel |
| 4-7 | `p0` (4 ASCII) | 4 | **Distance to next maneuver** (formatted number, leading zeros). Computed from `aVar.c` rounded to nearest 10. |
| 8 | `m0` (1 ASCII) | 1 | **Unit for p0**: `'K'` if Mappls returned "km", `'M'` if "m" |
| 9-14 | `n0` (6 ASCII) | 6 | **ETA**, 6-char format. 24h: "001730" (HHMM, zero-padded). 12h: "0530PM" (HHMMAA). Source: `aVar.e`. e-ACCESS / Access-TFT / Burgman bikes: if `n0[0]=='0'`, position 9 is overwritten with `0x20` (space). |
| 15-17 | literal `"000"` → 0xFF×3 | 3 | Sentinel |
| 18-21 | `q0` (4 ASCII) | 4 | **Distance to go (DTG, total remaining)** formatted number. From `aVar.d`. |
| 22 | `o0` (1 ASCII) | 1 | **Unit for q0**: `'K'`/`'M'`. NOTE: condition reads `if (!strB.contains("km")) o0="K"; else if (strB.contains("m")) o0="M"` — looks like a copy-paste bug vs `m0`'s correct check; may produce swapped/inverted values vs intuition. Verify against a real ride capture. |
| 23 | `str` / `A0.H1` (1 ASCII) | 1 | **Nav status digit**: `'1'`=normal, `'0'`=exit/airplane-mode, `'2'`=X-flag (recalc?), `'3'`=b0-flag, `'4'`=GPS-lost, `'5'`=a0-flag, `'6'`=v0-flag |
| 24 | `str2` / `A0.I1` (1 ASCII) | 1 | **Continuation flag**: `'0'` = terminate navigation |
| 25-27 | literal `"000"` → 0xFF×3 | 3 | Sentinel |
| 28 | computed | 1 | **XOR checksum** (`SuzukiApplication.a(bytes)`) |
| 29 | literal `'0'` → 0x7F | 1 | End marker |

Source: `decompiled/jadx-retry/sources/com/suzuki/application/fragment/A0.java` lines 455-533 (`D`), 488-770 (`C`'s assignments to these fields).

### Defaults

When `this.a0 == true` (some flag, semantic still unknown):
- `p0` is reset to `"0000"`
- `q0` is reset to `"0000"`

These would effectively zero out the distance fields. Likely "no active maneuver" / "no nav" defaults.

### What's STILL unknown

- The `a0` flag's full semantic (what state triggers the "0000" reset).
- The `e0` table mapping `aVar.f` (Mappls maneuver-lookup ID) → maneuver-ID byte we already documented in NOTES.md. Worth cross-referencing this code path against the documented arrow mapping to confirm consistency.
- Status digits `'2'`/`'3'`/`'5'`/`'6'` semantic (X / b0 / a0 / v0 flags — all booleans in A0, but what UI events set them?).
- Whether `HomeScreenActivity.onClusterDataRecev` (still un-decompiled) handles MORE message types beyond a531/a537. Same JADX flag trick should crack it.

### Tooling lesson

JADX's `--show-bad-code --comments-level debug --single-class <FQN>` combo can decompile methods that the default decompile (`jadx -d outdir base.apk`) silently skips. Worth re-running selectively on any class whose Java output contains "Method not decompiled" or "instruction units count: N".

## 2026-05-24 — `HomeScreenActivity.onClusterDataRecev` decompiled + RX protocol closed

### What we did

Same `jadx --show-bad-code --comments-level debug --single-class com.suzuki.activity.HomeScreenActivity` trick — decompiled the 961-instruction method that the default JADX run had silently skipped.

### Negative finding (large): the bike sends ONE message type, not many

Expected to find a switch/dispatch on message-type byte handling multiple framings (a531 / a533 / a536 / a537 / other). **Instead, every single cluster-data parser in the app gates on `bArr[1] == 0x37` (= ASCII `'7'`, a537).**

Verified across all 6 EventBus subscribers of `C0944c` (the cluster-data event):

```
com/suzuki/activity/HomeScreenActivity.java:269   if (bArr[0]==-91 && bArr[1]==55 && bArr[29]==127)
com/suzuki/activity/NavigationActivity.java:190   if (bArr[0]==-91 && bArr[1]==55 && bArr[29]==127)
com/suzuki/activity/RouteActivity.java:284        if (bArr[0]==-91 && bArr[1]==55 && bArr[29]==127)
com/suzuki/activity/CreateProfileActivity.java:396 if (bArr[0]==-91 && bArr[1]==55 && bArr[29]==127)
com/suzuki/activity/RiderProfileActivity.java:147 if (bArr[0]==-91 && bArr[1]==55 && bArr[29]==127)
com/suzuki/application/fragment/C.java:817        if (bArr[0]==-91 && bArr[1]==55 && bArr[29]==127)
```

`55` is `0x37` = a537. There are **zero** RX handlers for a531 / a533 / a536.

### What this means

Confirms the protocol direction:

```
Phone → Bike (writes on 0xFFF1):
  a531 = nav frame (turn arrow + distance + ETA + status)
  a533 = (still TBD — heartbeat?)
  a536 = user identity / pairing

Bike → Phone (notify on 0xFFF2):
  a537 ONLY — all telemetry (speed, odo, trip A, trip B, fuel, fuel economy)
```

The bike never echoes a531/a533/a536 back. They're write-only commands from the phone.

**Caveat**: this is static-source evidence ("Suzuki's own app only listens for a537"). It doesn't prove the bike can't physically send other framings — just that if it did, the official app would ignore them. To 100% confirm, snoop HCI on the bike side, but for the purpose of building Phase 2/3 clients, treating bike→phone as a537-only is safe.

### HomeScreenActivity.onClusterDataRecev semantic detail

Parses (same a537 format already documented in NOTES.md):
- bytes 5-10: odometer → `this.T` + `com.suzuki.pojo.e.h` + `e.D0`
- byte 24: fuel/battery byte, with **dual semantic by bike type**:
  - Petrol bikes: ASCII `'1'`-`'6'` → fuel bars (1-6). `'0'` (or anything outside `'1'`-`'6'`) → V=0 → triggers **"Low Fuel Alert"** TTS prompt
  - e-ACCESS: raw byte → `U = unsignedByte - 64` → if `U < 16` → triggers **"Low Battery Alert"** TTS prompt
- bytes 25-27: 24-bit big-endian fuel/energy consumption, **three model-specific decodings**:

| Bike model | Formula | Unit |
|------------|---------|------|
| Access-TFT / Burgman-TFT | `int24 / 10.0 / 2048.0` | proprietary fixed-point |
| e-ACCESS ("Energy Consumption") | `int24 / 10000.0` | kWh/km (likely) |
| **Default (incl. Gixxer SF 150)** | top 13 bits = int km/L, bottom 11 bits = fraction/2048, then `/ 10.0` | km/L |

So for our SF 150, fuel-economy decode is: `((bytes25-27 >> 11) + (bytes25-27 & 0x7FF) / 2048.0) / 10.0`. Sentinel `0xFFFFFF` (engine off) decodes to nonsense (~819 km/L) — code does not guard against this.

Then odometer-delta running totals are written to Realm every 12 ticks (≈60s @ 5s notify rate) and every 720 ticks (≈1hr).

### Confirmed: byte 25 alone is NOT engine coolant temperature

This was a wrong M0+ hypothesis that's been hanging around. Byte 25 is the **high byte of the 24-bit fuel-economy field**. Variation we attributed to coolant temp was actually fuel-economy variation (or `0xFF` sentinel noise during engine-off captures).

### What's STILL unknown

- The TX (phone → bike) `a533` message format — likely a heartbeat / keepalive given the timing, but contents not yet decoded.
- The TX `a536` message format — likely user-identity / pairing handshake. Decompiled `services/a.java` and `services/b.java` (the `BleWriteCallback` subclasses) might reveal where these get built.
- Whether the bike has any non-cluster BLE services we haven't enumerated (already did the GATT walk in M1.6 — see NOTES.md; the answer was "no").

### What this closes

The "find missing RX message types" question is resolved (statically). Phase 1's RX-side protocol surface is **complete**: a537 carries everything the phone displays from the bike. Phase 3 Branch B (telemetry dashboard) needs only an a537 parser — no other message types to handle.

Phase 1 remaining work is now TX-side (decode a533, a536 — both still partially unknown) plus live-ride validation of the a531 decode (especially the `o0` unit-swap bug).

## 2026-05-24 — TX frame inventory: 7 message types, not 3

### What we did

Decompiled and grep-scanned the full source for byte-array senders that call `MyBleService.f(byte[], int)` (the central write function targeting `0xFFF1`) and `work.g(byte[])` (a parallel write path). Found and mapped every framing.

### The full TX inventory

Phone → bike sends **seven** distinct frame types, all 30 bytes, all sharing the `[0xA5 <type> ... <chk> 0x7F]` envelope:

| Type byte | Frame | Sender(s) | Purpose |
|-----------|-------|-----------|---------|
| `'1'` 0x31 | a531 | `A0.D()` (in `application/fragment/A0`) | Navigation update (turn arrow / dist / ETA / status) — fully decoded |
| `'2'` 0x32 | **a532** | `CallReceiverBroadcast.d()`, `NotificationService` (line 786 — WhatsApp call variant) | **Incoming call** notification with caller's phone number at bytes 2-21 + state byte at 23 |
| `'3'` 0x33 | a533 | `services/f.java` (1 Hz × 3) + `C0940y.java` (~5s, two K.g-dependent variants) | **Phone heartbeat** — carries SMS/call pending flags at bytes 14-15 + other state |
| `'4'` 0x34 | **a534** | `CallReceiverBroadcast.e()`, `NotificationService` (line 729) | **Missed call** notification with caller name (up to 22 chars) + missed-count at byte 3 |
| `'5'` 0x35 | **a535** | `IncomingSms.java` (line 91), `NotificationService` (line 412 — SMS/WhatsApp variant) | **SMS / WhatsApp notification** with sender name + message marker bytes |
| `'6'` 0x36 | a536 | `A0.E()`, `C0940y.java` (first run) | **User identity** — user's display name + new-vs-known-cluster flag |
| `'7'` 0x37 | a537 | **bike → phone (notify)**, not TX | Telemetry — fully decoded |

We had been thinking the TX side was just three types (a531 / a533 / a536). It's actually six (a531, a532, a533, a534, a535, a536). The extra four are event-driven (calls + SMS/notifications) and only fire when those Android events occur — which is why they weren't in the M0 capture (Arjun's phone was quiet during the 18-min session).

### a533 templates (multiple senders, different layouts)

**Template 1 — `services/f.java` (1-Hz periodic heartbeat, sent 3× per tick for reliability):**

```
?31Y[0xFF×3]"2111837"[i0][j0][0xFF×12][chk][0x7F]
```
- bytes 2-3: literal `'1' 'Y'`
- bytes 4-6: 0xFF padding
- bytes 7-13: hardcoded magic string `"2111837"` (only appears in this one file — likely phone-side protocol-version identifier)
- byte 14: `HomeScreenActivity.i0` (SMS-pending flag — `'N'` = 78 default, `'Y'` = 89 set by NotificationService to signal clear)
- byte 15: `HomeScreenActivity.j0` (call-pending flag — same N/Y encoding, set by CallReceiverBroadcast)

**Template 2 — `C0940y.java` (every ~5s, fires after the first-run a536; two variants by bike type):**

The toggle is `K.g`, decided by the cluster's BLE-advertised name character at position 1:
- `name[1] == 'A'` → `K.g = true` (Access 125, Burgman Street — older simpler scooters)
- `name[1] == 'B'` → `K.g = false` (Gixxer SF 150, Avenis, others — newer bikes)

Common to both: `str = "?3" + c.G + c.P + c.I + c.O + zeros` where:
- `c.G` = 2-char mode/flag string, defaults `"1N"` — exact reassignment path unclear (see "mystery" below)
- `c.P` = 3-digit zero-padded speed from SharedPreferences
- `c.I` = 1-digit signal/status code (similar to A0.H1 in a531)
- `c.O` = current time as 6-digit `"hhmmss"` (via `SimpleDateFormat("hhmmss")`) — this is the incrementing counter we saw

K.g==true (simpler): bytes 16-27 all 0xFF.
K.g==false (richer, our bike): adds bytes 21-23 = `c.M` (mode int 0-11) + `(int)c.N` (some angle/bearing) + literal `1`.

### a536 template (identity)

`C0940y.java` first run + `A0.E()`:

```
?6<NAME 22 chars NUL-padded>[0xFF×5]['F' or 'R'][chk][0x7F]
```
- bytes 2-23: user's display name from `SharedPreferences("user_data").getString("name")`, NUL-padded to 22 chars (then bytes 22-26 get clobbered to 0xFF, so effective name length is 20 chars at bytes 2-21)
- byte 27: `'F'` (0x46, "Fresh") if `K.s == true` (connecting to a NEW/different cluster than last time), `'R'` (0x52, "Reconnect") if reconnecting to the same cluster as last session. Source: `services/d.java` line 64-66.

### a532 / a534 / a535 (call/SMS/notification frames — new finding)

These four event-driven frames carry contact name or phone number as ASCII text in the body. We didn't capture them in M0 because Arjun's phone was quiet. To observe: trigger a call / SMS / WhatsApp notification while HCI snoop is running.

- **a532** (incoming call): `?2<phone number, 20 chars NUL-pad>[0x4E ('N')][state byte: '1' active, '2' something][0xFF×4][chk][0x7F]`
- **a534** (missed call): `?4<contact name, 22 chars NUL-pad>...[missed count at byte 3][...]'N' at byte 24[0xFF×3][chk][0x7F]`
- **a535** (SMS/WhatsApp): `?5<sender or message, 23 chars NUL-pad><app marker byte (W=WhatsApp, N=other)>[NUL×3][chk][0x7F]`

### Mystery flagged for live verification

The M0 capture's a533 sample logged in NOTES.md was `.33Y214.050154NN.........` — bytes 1-3 read `'3' '3' 'Y'`. But every source-side a533 template puts type-byte `'3'` at byte 1, then non-`'3'` content at byte 2 (either `'1'` from services/f.java's literal "?31Y", or `c.G[0]` from C0940y which defaults `'1'`).

So either:
- The capture-display rendering of bytes is off-by-one (e.g. the leading `.` is NOT the 0xA5 header byte but something else), OR
- `c.G` is dynamically reassigned to `"3Y"` somewhere we haven't traced, OR
- The captured frames are from a sender path we still haven't found

**To verify**: hex-dump 5-10 actual `a533` frames from `captures/m0-pairing-and-first-nav-20260523-1712.pcap` and compare byte 2 against the three template predictions. Cheap to do — punted for now.

### Verification (2026-05-24, same day): pcap hex-dump confirms templates EXCEPT a533 byte 2-3

Used `tshark -r ... -Y "btatt.opcode == 0x12 or btatt.opcode == 0x52" -T fields -e btatt.value` to extract all ATT Write payloads from `m0-pairing-and-first-nav-20260523-1712.pcap`.

**Frame counts by header tuple (header, type, byte2, byte3):**

| Header tuple | Count | Source template match |
|--------------|-------|---|
| `a5 31 2e ff` (a531, byte2=`.`/0x2e) | 1592 | ✓ A0.D() degraded-signal override (maneuver=46 placeholder) |
| `a5 33 33 59` (a533) | 154 | ⚠️ See mystery below |
| `a5 31 08 ff` (a531, byte2=0x08) | 131 | ✓ A0.D() normal (real Mappls maneuver ID 8) |
| `a5 36 41 52` (a536, body starts "AR…") | 25 | ✓ C0940y/A0.E identity ("ARJUN" as 22-char NUL-padded name) |

So the M0 capture's writes are exclusively a531 + a533 + a536. No a532 / a534 / a535 fired during this 18-min window — phone was quiet, as expected.

**a536 sample fully verified:**
```
a5 36 41 52 4a 55 4e 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ff ff ff ff ff 46 f7 7f
^  ^  A  R  J  U  N  <NUL padding to byte 22>          <FF×5 override>  ^  ^  ^
hdr 6                                                                  'F' chk end
```
- Byte 27 = `0x46` = `'F'` → confirms `K.s == true` (first connection to this cluster). Consistent with M0 being Arjun's first pairing session.
- Bytes 2-6 = "ARJUN", followed by NUL padding through byte 21, then 0xFF override at 22-26 per the source. Matches exactly.

**a531 samples fully verified:**
- 1592 frames with byte 2 = 0x2e (`'.'`) — the A0.D() degraded-mode placeholder (maneuver=46 when signal status `H1 ∈ {"0","2","4","6"}`).
- 131 frames with byte 2 = 0x08 — real Mappls maneuver ID 8, fires when signal restores to good (`H1 ∈ {"1","3","5"}`). The 131:1592 ratio matches the M0 timeline (mostly engine-off + nav-not-active, brief windows of real nav frames).

**a533 sample — partial match, byte 2-3 mystery REMAINS:**

Captured frame:
```
a5 33 33 59 32 31 34 00 30 35 30 31 35 34 4e 4e ff ff ff ff ff 01 00 01 ff ff ff ff 1a 7f
```

Byte-by-byte against C0940y.java K.g==false template (Gixxer SF 150's expected path):

| Pos | Captured | Expected | Match? | Note |
|-----|----------|----------|--------|------|
| 0 | `a5` | `0xA5` | ✓ | header |
| 1 | `33` | `'3'` | ✓ | a533 type byte |
| 2 | `33` | `'1'` (= `c.G[0]` default) | ✗ | **Mystery** |
| 3 | `59` | `'N'` (= `c.G[1]` default) | ✗ | **Mystery** |
| 4-6 | `32 31 34` | `c.P` (3-digit speed) | ✓ | `c.P = "214"` (stale prefs?) |
| 7 | `00` | `0` (override if `c.I=="0"`) | ✓ | confirms `c.I == "0"` (no signal) |
| 8-13 | `30 35 30 31 35 34` | `c.O` (6-char `hhmmss`) | ✓ | `"050154"` = 5:01:54 PM |
| 14-15 | `4e 4e` | `i0`, `j0` | ✓ | both `'N'` (no pending SMS/call) |
| 16-20 | `ff ff ff ff ff` | 0xFF override | ✓ | |
| 21 | `01` | `c.M` | ✓ | `M = 1` (default) |
| 22 | `00` | `(int) c.N` | ✓ | `N ≈ 0` (default) |
| 23 | `01` | literal `1` | ✓ | |
| 24-27 | `ff ff ff ff` | 0xFF override | ✓ | |
| 28 | `1a` | checksum | ✓ | |
| 29 | `7f` | `0x7F` | ✓ | end marker |

**29 of 30 bytes match exactly. Bytes 2-3 are the only discrepancy.**

All 154 captured a533 frames have IDENTICAL bytes 2-3 = `0x33 0x59` (`"3Y"`). So whatever sets `c.G`, it's set once during the session and held constant.

**What I tried but couldn't explain via source:**
- Grep for `\.G\s*=` in all C0940y/C.java code paths (including JADX `--show-bad-code` re-decompile that recovered the giant `K(Boolean)` and `L()` methods, 3986 + 4882 instruction units each) — finds only the default `public String G = "1N";` at C.java:191.
- Grep for `"3Y"` literal anywhere in source — zero hits.
- Checked the `iArr[2] = i5` override path (line 175-177): only fires for `C.v1` equal to `"e-ACCESS"`, `"Access-TFT Edition"`, or `"Burgman Street-TFT Edition"`. Our Gixxer SF 150 wouldn't trigger this. Even if it did, `i5 = Integer.parseInt(c.H.substring(2), 16)` would have to coincidentally yield 0x33 — possible but unverified.
- Checked C.v1 source — set only from `vehicle_model` SharedPreferences in HomeScreenActivity. For Gixxer it would NOT equal any of the e-ACCESS/TFT strings.

**Possible explanations (all unverified):**
1. ProGuard or JADX is failing to surface a runtime field assignment (maybe via a Kotlin property or a synthetic accessor).
2. `c.G` is initialized via a code path that runs ONCE during pairing handshake, sourced from BLE cluster name or session token (which is why it's stable but not equal to default).
3. The override path at line 175-177 IS firing — maybe `C.v1` does equal one of those strings in practice (the Realm-stored vehicle_model name might be normalized to one of those values regardless of bike type), and `c.H.substring(2)` parsed as hex yields exactly `0x33`. To verify: trace `C.v1` and `c.H` at runtime via Frida.

**Practical workaround for forging tools:** Until Frida-hook verifies, treat bytes 2-3 of a533 as opaque session-state bytes. Replay them verbatim from a captured frame, OR set to `"1N"` per source default and observe whether the bike rejects the frame.

### Verification updates DISCOVERIES.md changes how we should phrase NOTES.md

- The a533 "captured sample looks weird vs source" warning in NOTES.md → upgrade to "29/30 bytes verified; bytes 2-3 unresolved, treat as session state."
- The a536 sample in NOTES.md is fully consistent with source — no NOTES.md change needed beyond what we wrote.
- The a531 samples are fully consistent — confirms the degraded-mode override behavior we already documented from A0.D().

### 2026-05-24 — Mystery RESOLVED: a533 bytes 2-3 = phone battery status

Wrote `tools/find_field_writes.py` (androguard, ~30 LOC) to enumerate every `iput-object` / `sput-object` instruction in the dex targeting `Lcom/suzuki/application/fragment/C;->G`. Found **17 writes** — 1 in C's constructor (the `"1N"` default we already knew) and **16 in a single method**: `Landroidx/appcompat/app/z;->onReceive`.

That class — `androidx/appcompat/app/z.java` — is a multi-purpose BroadcastReceiver that ProGuard relocated **out of the Suzuki package namespace** into `androidx/appcompat/app`. That's why every grep we ran inside `decompiled/jadx-out/sources/com/suzuki/` came up empty. Static analysis caveat learned: **ProGuard can move classes across package boundaries** — future searches must scan the entire decompile output, not just the app's package.

**The full encoding** (`androidx/appcompat/app/z.java` lines 153-209, case-3 branch handling `ACTION_BATTERY_CHANGED`):

`c.G` is a **2-ASCII-char encoding of the phone's battery status**:

| Char 1 | Battery level bucket |
|--------|---------------------|
| `'3'` | 75-100% |
| `'2'` | 50-74% |
| `'1'` | 25-49% |
| `'0'` | 0-24% |

| Char 2 | Charging state |
|--------|---------------|
| `'Y'` | currently charging (`BatteryManager.BATTERY_STATUS_CHARGING == 2`) |
| `'N'` | not charging (status 1=UNKNOWN, 3=DISCHARGING, 4=NOT_CHARGING, 5=FULL) |

So our captured `"3Y"` = phone was 75-100% charged AND plugged in (charging). Consistent with Arjun's M0 setup: phone USB-connected to laptop for HCI snoop the entire session.

**Bonus finding from the same code path:**

`c.H` is set in the same receiver branch to a hex string `String.format("0x%02X", battery_percent_int)` (e.g. `"0x64"` for 100%). That's the field referenced at C0940y.java line 167: `int i5 = Integer.parseInt(c.H.substring(2), 16);`. For e-ACCESS / Access-TFT / Burgman-TFT bikes, `iArr[2] = i5` overrides byte 2 to the raw battery percent (so the bike's cluster displays the phone's exact battery percentage in addition to / instead of the bucket). For our Gixxer SF 150, only the bucket char `c.G[0]` makes it to byte 2.

### What this fully closes

a533 is now **100% decoded** end-to-end. Every byte position mapped to a semantic source. The full TX inventory + every field decoded:

| Pos | Field | Decode |
|-----|-------|--------|
| 0 | header | 0xA5 |
| 1 | type | `'3'` (0x33, a533) |
| 2 | `c.G[0]` | Phone battery bucket digit: `'0'`=0-24%, `'1'`=25-49%, `'2'`=50-74%, `'3'`=75-100% |
| 3 | `c.G[1]` | Phone charging state: `'Y'`=charging, `'N'`=not charging |
| 4-6 | `c.P` (3 ASCII) | Speed from SharedPreferences (`%03d` format). 0xFF×3 if speed==0 |
| 7 | `c.I` (1 ASCII) | Signal/nav status digit (same encoding as a531 byte 23). Override to `0x00` if `c.I=="0"` |
| 8-13 | `c.O` (6 ASCII) | Current wall-clock time in `hhmmss` 12-hour format. 0xFF×6 if `"000000"` |
| 14 | `HomeScreenActivity.i0` | SMS/notification pending flag (`'N'`=78 default / cleared, `'Y'`=89 set by NotificationService) |
| 15 | `HomeScreenActivity.j0` | Call pending flag (same N/Y encoding, set by CallReceiverBroadcast). In K.g==true branch, replaced with `e.u0 ? 'Y' : 'N'` |
| 16-20 | sentinel | 0xFF×5 |
| 21 | `c.M` (int, K.g==false only) | Mode int 0-11 (gear?). 0xFF in K.g==true branch |
| 22 | `(int) c.N` (K.g==false only) | Some angle/double field cast to byte. 0xFF in K.g==true branch |
| 23 | literal `1` (K.g==false only) | Constant 0x01. 0xFF in K.g==true branch |
| 24-27 | sentinel | 0xFF×4 |
| 28 | checksum | `sum(bytes[1:28]) mod 256` |
| 29 | end | 0x7F |

**Lesson recorded**: when JADX shows a field with only its default-value assignment but captures prove a different runtime value, the assignment is somewhere ProGuard hid it — likely in a renamed class moved out of the app's package. Use androguard or similar to grep at the dex bytecode level (`iput-object` / `sput-object` instructions) rather than the Java decompile. Tool: `tools/find_field_writes.py`.

## 2026-05-24 — Network/cloud audit + MQTT verdict

### What we did

The earlier M1 commit (`26838d6 — map Suzuki Connect cloud API architecture from source`) claimed "only 2 cloud endpoints exist." That claim was based on a narrow grep of the Retrofit interface classes (`com.suzuki.interfaces.{f,g}`). Today, broader sweep to verify or correct.

Also followed up on the `MqttService` reference we saw in the `androidx/appcompat/app/z.java` BroadcastReceiver (which turned out to host the `BATTERY_CHANGED` handler that sets `c.G`).

### Results

**MQTT verdict: DEAD CODE.**

- `org.eclipse.paho.android.service.MqttService` IS registered in `AndroidManifest.xml` (verified with `tools/dump_manifest.py` via androguard).
- BUT no Suzuki code instantiates an `MqttClient`, calls `MqttService.class`, or contains any broker URL.
- Zero hits across the entire `com.suzuki.*` tree for `tcp://`, `ssl://`, `mqtt://`, `mqtts://`, `MqttConnect`, `MqttClient(`, `new IMqtt`, or any `.connect()`-shaped MQTT call.
- The Paho receiver in `z.java` `case default` is the library's own `NetworkConnectionIntentReceiver` — it waits for an MQTT client that never gets created, so the receiver branch is effectively dead code at runtime.

**Cloud endpoint inventory (final, validated):**

| URL | Method | Used by | Purpose |
|-----|--------|---------|---------|
| `https://projects.mapmyindia.com/autolicverify/{BTID}/expiry/date/` | GET | `RunnableC0828e.java`, `DashboardFragment$16.java` | Subscription expiry check |
| `https://projects.mapmyindia.com/autolicverify/updatePlan?session_device=...&planType=...` | POST | `RunnableC0828e.java` | Plan upgrade |

Plus deep-link URLs (NOT API — just browser handoffs):
- `https://maps.mapmyindia.com/@<lat>,<lng>` — open parked location in Mappls
- `https://www.suzukimotorcycle.co.in/...` — Suzuki India homepage / Help
- `https://play.google.com/...` — Play Store
- `https://drive.google.com/...` — user manual link
- `https://mappls.com/...` — Mappls promo

And: the bundled Mappls SDK (`com.mappls.sdk.*`) talks to many internal Mappls servers (map tiles, routing, geocoding, directions). Those are part of the navigation library and orthogonal to bike telemetry/control. Suzuki app code doesn't see them.

**WebSockets: none.** No `ws://` / `wss://` references in `com.suzuki.*`.

### Bonus: hidden receivers turn out to be stubs

The manifest declares 9 Suzuki components total. Three I hadn't catalogued before — `BleConnection`, `MapShortDistBroadcast`, `DataFromBle` — turn out to be **logging-only skeleton receivers** (single `Log.d` call each, no real handling). They're not hidden side-channels for telemetry; they look like scaffolding that was never implemented or debug instrumentation.

Full Suzuki manifest inventory now in NOTES.md.

### What this closes

- "Are there other cloud endpoints we missed?" → **No.** Only the 2 license endpoints, confirmed via grep across the entire `com.suzuki.*` tree (not just Retrofit interfaces).
- "Does Suzuki Connect use MQTT?" → **No.** Library is bundled but unused.
- "Are there any non-BLE, non-HTTP channels for telemetry?" → **No.** No WebSockets, no MQTT, no hidden receivers carrying data.

**Bike control / telemetry flows exclusively through the single BLE service (0xFFF0 / chars 0xFFF1 write, 0xFFF2 notify).** The cloud is just for license bookkeeping. This is the cleanest "phone-mediated control" architecture imaginable — and it means a phone-app replacement (Phase 2) needs only the BLE protocol library we built today (`tools/protocol.py`) plus a separate token for the license API if license bookkeeping matters (it might not — would need to test whether the bike functions normally without an active subscription).

### Tools created

- `tools/dump_manifest.py` — pretty-prints the binary `AndroidManifest.xml` from an APK via androguard + lxml.

## 2026-05-24 — Cluster hardware: known unknown

Spawned a web-research subagent to scope the cluster MCU / BLE chip / debug pad situation. **The honest finding is that no public PCB-level evidence exists** for the 2023 Suzuki Gixxer SF 150 cluster, or for any other Suzuki Connect-equipped Indian two-wheeler (Access 125, Burgman Street, Avenis, e-Access, Gixxer 250).

### What was searched (all returned no hits)

- YouTube, Reddit (r/motorcycles, r/IndianMotorcycles, r/SuzukiMotorcycles), Team-BHP, xBhp, Gixxer.com forum, BikeBD — every existing video covers cluster *removal* or *no-display fault diagnosis* without opening the housing
- FCC ID / WPC ETA (India BLE certification) databases — no surfaced filing
- boodmo.com, safexbikes, partshelmsmen — Connect-specific cluster SKU not indexable
- Suzuki service manual PDFs reachable on Scribd cover **non-Connect** Gixxer SF variants (part numbers like `34100-34J10/J20/J30/J31-000` for the basic cluster; no Connect SKU)

### What we have as circumstantial info

- **Supplier shortlist (LIKELY, not confirmed):** Indian two-wheeler cluster market is dominated by **Pricol** (Coimbatore) and **NS Instruments India** (Nippon Seiki Manesar/Bawal subsidiary). DENSO-Pricol JV also active. Suzuki India buys from these.
- **Industry reference pattern (SPECULATION):** EDOM's reference motorcycle cluster design uses a Dialog (now Renesas) Wi-Fi+BLE module ([edomtech.com motorcycle-digital-instrument-cluster](https://www.edomtech.com/en/solution-detail/motorcycle-digital-instrument-cluster/)). Not Suzuki-specific, just an industry data point.
- **MCU class (SPECULATION):** at the Suzuki Connect price point, expect cost-optimised Renesas RL78, NXP S12, or low-end ARM Cortex-M0/M3. Nothing concrete.

### Cheapest path to ground truth

1. **Physically open the cluster**: every web channel is empty, so direct teardown is the only way. Destructive-ish (housing clips usually break), but cluster is still functionally repairable.
2. **Suzuki dealer query**: parts catalogue 2023 should list the Connect-specific SKU; supplier code is often embossed on the housing.
3. **WPC ETA database direct query** (dgca.wpc.gov.in): mandatory for any BLE device sold in India — would list whoever certified Suzuki's BT cluster module. Lighter lift than opening the case.

### Implication for Phase 3 firmware-modding hypothesis

Reflashing the cluster firmware remains the same "wildly disproportionate to the goal" bet I described to Arjun before this research: zero ground truth on chip → can't even scope the SWD/JTAG vs glitching question. **Frame forging (Phase 3 Branch A) and telemetry mirroring (Branch B) remain the practical paths**; cluster reflashing is parked indefinitely.

If we ever revisit, the first concrete step is the WPC database query — cheaper than the dealer trip, cheaper than the teardown.

## 2026-05-24 — Connection handshake fully mapped + auth verdict

### What we did

Read both `C.K()` (3986 instr units) and `C.L()` (4882 instr units) — the two giant methods in the C fragment we hadn't explored. **Neither contains handshake logic** — both are giant UI dispatchers (nested switches on bike model × color variant, with each leaf setting an `R.drawable.<bike_model>_<color>` image). That rules out one hypothesis cleanly.

Then traced the actual handshake by following the FastBle `BleGattCallback` subclass:

- `com.suzuki.activity.C0855q0` extends `com.clj.fastble.callback.a` (= `BleGattCallback`)
- Connect entry point: `DeviceListingScanActivity.m(bleDevice)` → `BleManager.connect(bleDevice, new C0855q0(this, 1))`
- On `onConnectSuccess` (`C0855q0.b()`), case `default` (fresh pair via DeviceListingScanActivity):
  1. Set MTU via `.k(bleDevice, new J(0))`
  2. Save cluster name + MAC to `BLE_DEVICE` SharedPreferences
  3. Set `K.s = true` (FRESH) if cluster name differs from `prev_cluster`, else `K.s = false` (RECONNECT)
  4. Broadcast `Intent("status")` with `status=true`
  5. After 500 ms delay → `MyBleService.a(bleDevice)` (subscribe to `0xFFF2` notify)
- On `onConnectSuccess`, case `0` (reconnect via HomeScreenActivity background):
  1. Set MTU, broadcast `closeDialogActivity`, finish DeviceListingScanActivity if open
  2. Sleep 500 ms → `MyBleService.a(bleDevice)`

### Complete handshake sequence

```
1. User opens DeviceListingScanActivity → BLE scan
2. User taps bike entry
3. DeviceListingScanActivity.m(bleDevice) → BleManager.connect(...)
4. onConnectSuccess (C0855q0.b case=default)
     - MTU negotiated
     - Cluster name/MAC saved to SharedPreferences
     - K.s set based on whether this matches the last paired cluster name
     - 500 ms delay
5. MyBleService.a(bleDevice) — subscribes to 0xFFF2 notify
     (At this point bike is silent — does NOT push a537 yet)
6. C0940y TimerTask scheduled at 1 Hz (scheduled in r.java when connection-state event fires)
7. First C0940y tick (delay 0ms):
     - Build a536 frame: "?6" + user.name + 0xFF×5 + ('F' if K.s else 'R') + chk + 0x7F
     - Send via MyBleService.f(bytes, 36)
8. Bike receives a536 → starts streaming a537 (telemetry) every ~5 s on 0xFFF2
9. Subsequent C0940y ticks (1s cadence):
     - Send a533 heartbeat (with current battery + speed + time + SMS/call flags)
     - If active nav: A0.D() also sends a531 frames (~2.7 Hz)
10. Event-driven frames fire as needed:
     - Incoming call → a532
     - Missed call → a534
     - SMS / WhatsApp notification → a535
```

### MAJOR finding: NO protocol-level authentication

**The Suzuki Connect protocol has zero application-layer authentication.** From the source:

- No key exchange, no challenge-response, no HMAC, no signed messages
- `K.s` (fresh vs reconnect) is **purely cosmetic** — it just toggles byte 27 of a536 between `'F'` and `'R'`. The bike does not gate behavior on this byte; it's a hint for the cluster's UI to display "first-time pairing" vs "welcome back" type prompts (if it even uses it).
- The only "identity" sent is the user's display name in a536, which is plaintext and trivial to forge
- The "pairing" is just Android's standard BLE GATT connection — there's no Suzuki-specific auth on top

**What this implies (hypotheses to verify, NOT facts):**

- **Hypothesis A**: Anyone within BLE range of a Suzuki Connect cluster can connect and send a531/a532/a533/a534/a535/a536 frames. The cluster would parse and act on them. No spoofing protection.
- **Hypothesis B**: The bike might still gate on BLE-level bonding (the cluster could require Just Works bonding before accepting writes on 0xFFF1). This is a separate layer below the Suzuki protocol — we haven't verified.
- **Hypothesis C**: The cluster might enforce per-cluster MAC pairing (only respond to one phone MAC at a time, learned during the first connection). Also unverified.

**To verify**: write a custom Python (bleak) script that scans for the bike's BLE name, connects, subscribes to `0xFFF2`, sends a fabricated a536 identity, and observes whether a537 telemetry starts streaming back. If yes → no MAC pinning, no BLE-bond requirement. If no → there's some bonding/pinning layer we need to understand. We have `tools/passive_listen.py` already; this would be a small follow-on script.

**Phase 2 / Phase 3 implication**: a replacement Android app needs only the BLE protocol library — no auth handshake to replicate. If hypotheses A and B both hold, a Linux laptop or ESP32 could fully replace the phone with no Suzuki app at all.

### What this closes

- "How does the app pair with a new bike?" → mapped end-to-end
- "Is there an auth handshake?" → no application-layer one. BLE-layer bonding TBD.
- "What does C.K() / C.L() do?" → giant UI dispatchers for bike-model imagery, not protocol
- "When does the bike start sending a537?" → after the phone sends its first a536

### What's still open

- BLE-bonding requirement (Hypothesis B above) — test with custom client
- MAC pinning (Hypothesis C above) — same test
- Whether the cluster keeps multiple "trusted phones" — would matter for a multi-user setup

### Second post-connect Runnable — RESOLVED 2026-05-24

The second `Handler.postDelayed` call in `C0855q0.b()` case=default is `new androidx.emoji2.text.l(this, bluetoothGatt, bleDevice, 7)`. JADX skipped its 1504-instruction `run()` method on the default decompile; re-running with `--show-bad-code --single-class androidx.emoji2.text.l` recovered it.

`androidx/emoji2/text/l.java` is a ProGuard-merged multipurpose Runnable (9 cases). **Case 7** persists the discovered cluster's BLE info to a Realm row:

```java
RealmQuery q = j3.f0(C0943b.class);  // BLE-info table
q.e("bleId", 1);
C0943b row = (C0943b) q.h();
if (row == null) row = j3.G(C0943b.class, 1);
row.o(bleDevice.b());                              // MAC address
row.p(bleDevice.c());                              // advertised name
row.q(service.getCharacteristics().get(1).getUuid().toString());  // 0xFFF2 notify UUID
row.s(service.getCharacteristics().get(0).getUuid().toString());  // 0xFFF1 write UUID
row.r(bluetoothGatt.getServices().get(3).getUuid().toString());   // 0xFFF0 service UUID
j3.d0(row);
```

Plus writes `FT.first = MapplsLMSActivityLifecycleCallbacks.CHECK_DELAY` (a sentinel meaning "first-time setup completed") to SharedPreferences.

**Bottom line**: this is bookkeeping only — caches the cluster's BLE identity + UUID triple to Realm so future reconnects don't need fresh GATT discovery. Not protocol-relevant; doesn't affect Phase 2/3 design. The SharedPreferences `prev_cluster_macAddr` / `prev_cluster_name` writes from the case=default branch handle the same purpose for app-startup reconnect; Realm storage is presumably for cross-session UI lookup (multiple bikes per profile, switching between clusters, etc.).

Handshake mapping is now fully closed — every post-connect action accounted for.

## 2026-05-24 — NotificationService deep-dive: a532/a534/a535 templates corrected

### What we did

Earlier `protocol.py` had `CallFrame`, `MissedCallFrame`, and `SmsFrame` as template-only dataclasses based on a quick grep of the source. None had been validated end-to-end against the source builders. Read `services/NotificationService.java` (817 lines, 3 BLE write sites) + cross-referenced with `IncomingSms.java` + `CallReceiverBroadcast.java` to get the real templates.

### Three corrections to prior `protocol.py` field model

**a532 (incoming call):**

| Was | Now |
|-----|-----|
| `number` + `state` only | `number` + `is_whatsapp` + `state` |

Byte 22 is a source marker:
- `CallReceiverBroadcast.d` (cellular call) → bytes[22] = `'N'` (0x4E)
- `NotificationService.q` (WhatsApp call) → bytes[22] = `'W'` (0x57)

State byte (23): `'1'` (active) or `'2'` (some sub-state — set in WhatsApp path when `str2=="2"`, and in cellular path when `str2 != "2" AND missed-count l != 0`).

**a534 (missed call):**

| Was | Now |
|-----|-----|
| `name` + `missed_count` | `name` + `missed_count` + `is_whatsapp` |

Byte 24 is the source marker:
- `CallReceiverBroadcast.e` (cellular missed) → bytes[24] = `'N'` (0x4E)
- `NotificationService` line 729 (WhatsApp missed) → bytes[24] = `'W'` (0x57)

Plus a structural detail I'd missed: the source template prefixes the caller name with literal `"Y1"`. So bytes 4-5 = `"Y1"`, bytes 6-23 = 18 chars of name. Our decoder strips the `Y1` prefix on read; encoder emits it on write.

**a535 (SMS / WhatsApp / notification):**

This one was the most wrong. Previous `SmsFrame` had `body` + `is_whatsapp` where `is_whatsapp` was guessed to be at byte 25 with `'W'`/`'N'`. Source shows that:

- The source DOES compute a `str3 = "W"/"X"/"N"` marker but it lands at position 26 — which is **always overridden to 0xFF** by the next line. So the marker never reaches the bike. Looks like a Suzuki app bug or leftover from an earlier protocol version.
- Real layout (from `NotificationService.o` and `IncomingSms.c`):
  - byte 3 = `'N'` (silenced, `z=true`) or `'Y'` (not-silenced, `z=false`). `IncomingSms.c` doesn't explicitly set this — leaves it as sender's second char. Treat as advisory.
  - byte 4 = message count (= `e.m0` for SMS, `K.m` for WhatsApp, `G` for notifications — always an int count)
  - bytes 5-24 = sender name (20 chars, NUL-padded; the source pads to 24 chars then bytes 2-4 get clobbered to constants, so effective name starts at byte 5)
  - byte 25 = type-source byte. In `IncomingSms.c` it's always `'N'` (literal). In `NotificationService.o` it's the input `b` parameter — purpose ambiguous, may be a per-message type code. Treat as opaque.
  - bytes 26-27 = 0xFF

Corrected `SmsFrame`: `sender` + `message_count` + `silenced` + `type_byte` (opaque escape hatch).

### Source asymmetry warnings

Both a534 and a535 have **two source paths** that produce slightly different bytes in marker positions. This is a real Suzuki app inconsistency — likely because the SMS-from-broadcast path was written separately from the notification-listener path and they drifted. For Phase 2 forging tools, picking either convention should work; the bike presumably tolerates both since both ship in production.

### Sanity check

After the protocol.py changes:
- 35 tests pass (up from 33; added two new tests for the WhatsApp variants of a532 and a534)
- All 5,619 captured frames in both M0 pcaps still round-trip byte-identical via `tools/validate_pcap.py`

### What this closes

- a532/a534/a535 are now **fully source-validated templates** rather than guesses. Each has a documented WhatsApp-vs-cellular marker byte where applicable.
- All 6 phone→bike frame types are now fully decoded in `protocol.py` with source-derived semantics.

### What's still open

- The "second source path" inconsistency (a534/a535 differ slightly between `IncomingSms`/`CallReceiverBroadcast` vs `NotificationService` builders). Worth verifying which one the bike actually displays correctly — needs a phone-event capture during a paired session.
- The `b` parameter in `NotificationService.o()` (what becomes byte 25 of a535) — caller-provided, but the call sites compute it from notification metadata. Could trace upward but low priority.

## 2026-05-24 — K.* static state sweep

Last unmapped chunk of "mystery globals" — the `com.google.android.gms.measurement.internal.K` class is Suzuki's session-state singleton (despite the misleading ProGuard package). Ran `tools/find_field_writes.py` on every K.* we've seen referenced.

### Inventory (all app-internal; none are protocol-on-the-wire)

| Field | Type | Purpose | Set by | Read by |
|-------|------|---------|--------|---------|
| `K.f` | boolean | **In-call mute flag** — when true, notification listener skips processing (suppresses SMS/notification frames during an active call) | `CallReceiverBroadcast.d` (cellular call) → true; `NotificationService.q` (WhatsApp call) → true/false depending on `str2` | `NotificationService.onNotificationPosted` line 629 (break/skip when true) |
| `K.g` | boolean | **Bike type flag** — `true` for Access/Burgman family (cluster name starts with `'A'`), `false` for Gixxer/Avenis/others (name starts with `'B'`) | `services/d.java` connection handler, decided from cluster's BLE advertised name char[1] | `C0940y.java` (selects a533 builder variant), various model-conditional code |
| `K.i` | `BleDevice` | Current connected BleDevice reference | Mappls SDK + a couple of view holders (writes are bookkeeping) | `C0855q0.c` (onDisconnected reconnect path) |
| `K.j` | int | Some counter, missed-call-adjacent (4 writes across Mappls navigation receiver + CallReceiverBroadcast + u0.run) | mostly Mappls SDK | Cross-checked with `C0862u0.java:56` resetting it to 0 — call-state tracking |
| `K.k` | int | **Missed-call sequence counter** — incremented for each missed call (with 1-second debounce via K.o). Becomes byte 3 of a534. | `NotificationService.p` line 720-721, `NotificationService.f` (reset) | `CallReceiverBroadcast.e(K.k, ...)` (passed as missed_count) |
| `K.m` | int | **WhatsApp notification count** — mirrors `e.m0` (SMS count) for the WhatsApp path. Becomes byte 4 of a535 in WhatsApp variant. | `NotificationService.n` line 368 (`K.m = iD`), `NotificationService.onNotificationPosted` (zero-reset) | passed to `o()` builder as `i2` param |
| `K.n` | long | Last messaging-notification timestamp (millis) | `NotificationService.o` line 379, `onNotificationPosted` | (read by various rate-limit logic) |
| `K.o` | long | Last missed-call timestamp (millis) — used for 1-sec debounce on K.k++ | `NotificationService.p` line 766 | `NotificationService.p` line 719 (`if (K.o + 1000 < currentTimeMillis())`) |
| `K.s` | boolean | **Fresh vs reconnect cluster flag** (already mapped) — `true` if connecting to a NEW cluster, `false` if reconnecting | `services/d.java` lines 64-66 | a536 `IdentityFrame.encode()` byte 27: 'F' (fresh) or 'R' (reconnect) |
| `K.t` | `androidx.lifecycle.I` (MutableLiveData) | Observable cluster-name wrapper — broadcasts current cluster name to UI observers. The field itself is set once in `onCreate`; the value updates via `.setValue()` (method call, not field write — that's why our field-write tool only sees 2 writes) | `HomeScreenActivity.onCreate`, `NotificationService.onCreate` (one-time init) | Many UI observers (`.d()` getValue) and value updaters (`.setValue()`) — not field-level writes |

### Implications

- **Nothing protocol-relevant uncovered.** All K.* fields are app-internal state for UI / rate-limiting / notification suppression. They don't appear on the wire as their own bytes.
- The two fields that DO end up in TX frames are `K.k` (→ a534 byte 3 = missed_count) and `K.m` (→ a535 byte 4 = message_count when WhatsApp). Both already exposed by our `protocol.py` `MissedCallFrame.missed_count` and `SmsFrame.message_count` fields — no library changes needed.
- The "in-call mute" behavior of `K.f` is interesting for Phase 2 design: a replacement app would want similar logic (don't send SMS frames during an active call) to match cluster UX. But this is policy, not protocol — easy to replicate.

### What this closes

- All remaining mystery K.* statics catalogued. No more "what is K.f / K.t" open questions.
- Confirmed our `MissedCallFrame.missed_count` and `SmsFrame.message_count` fields correctly correspond to the app's internal counters.

**M1 status check**: I think this might genuinely be the last static-analysis thread of substance. Everything that touches the wire is now decoded and library-implemented; everything app-internal is catalogued. Remaining open items all require either the bike physically (BLE-bond test, ride capture, forge tools running) or a different problem domain (Frida hooks, mitmproxy SSL bypass).

## 2026-05-24 — First real ride capture (M2-ish): 3315 frames, 17-min window

### Setup outcome

- Built `frida-scripts/ride_capture.js` (8 hook groups) + `tools/test_attach.py` Python wrapper + `frida-scripts/build.sh` to bundle the Frida 17 + frida-java-bridge dependency.
- Test-attached against the live app pre-ride. Confirmed hooks fire on real frames (captured a "Devu" WhatsApp a535 in the test).
- During the ride: Android killed `suzuki.com.suzuki` (PID 4116 → 13388 by end). Frida agent died with the old process; the Python wrapper had no idea and held a ghost session. **113s of Frida data out of 17 minutes.**
- **HCI snoop kept logging via Android-native mechanism for the full ride** — 866 KB pcap captured. That's what saved us.

### Ride pcap analysis (`captures/ride-20260524-1810.pcap`)

3,315 BLE frames, 1003s of BLE-active window (with 5 reconnect events from BLE drops). All decode cleanly via `tools/protocol.py`:

| Type | Count | Notes |
|------|-------|-------|
| a531 NAV | 2715 | 7 distinct real maneuver IDs (1, 4, 5, 8, 9, 14, 39) + 146 degraded placeholders |
| a532 CALL | 22 | First captured! `'Me'` as caller name, `is_whatsapp=False`, cellular path |
| a533 HEARTBEAT | 393 | Real-time updates |
| a534 MISSED_CALL | 5 | First captured! "Y1" prefix decode validated against live data |
| a536 IDENTITY | 21 | All `is_fresh=False` (= 'R' reconnect); 5 long gaps (>5s) indicate 5 BLE drops |
| a537 TELEMETRY | 159 | Speed seen 0-10 km/h (city/parking-lot pace); odometer 16762→16765 (3 km this window); fuel_bars steady at 3 |

### Major finding 1 — `A0.a0` and `A0.b0` flag semantics RESOLVED

These were "TBD precise meaning" in the earlier static analysis. The ride captures revealed the answer via the surprise that status='5' fired 26% of frames (way more than any "off-route" hypothesis predicted).

Source trace (after the surprise prompted re-reading the Mappls callbacks):

- **`A0.a0` = "destination reached" sticky flag.** Set true in `com.mappls.sdk.navigation.routing.f.run` case 1 (line 74), alongside showing the "Destination Reached, do you want to exit?" dialog. **Never reset in source** — once true, every subsequent a531 frame in that session lands status='5' via the v0.java if-else chain.
- **`A0.b0` = "via-point reached"** (waypoint along multi-stop route). Set in `com.mappls.sdk.navigation.routing.i.m` (line 607), alongside a `Toast: "Via Point Reached <name>"`. Same sticky behavior.
- The ride had no via-points configured, so status='3' fired zero times (matches: `A0.b0` never set).

### Updated complete status-digit map (corrected)

| Digit | Source | Meaning | Reset? |
|-------|--------|---------|--------|
| `'0'` | `C.e1` true OR airplane mode | No signal / radio off | Auto when signal restored |
| `'1'` | else (default) | Normal nav | — |
| `'2'` | `A0.X` | Route recalculating | **Auto-resets in v0.java after one frame** |
| `'3'` | `A0.b0` | Via-point (waypoint) reached | Sticky until next via-point |
| `'4'` | `A0.Z` | GPS provider disabled | Auto when GPS returns |
| `'5'` | `A0.a0` | **Destination reached** | **Sticky forever** (no source reset) |
| `'6'` | `A0.v0` | Phone has no network | Auto when network returns |

Prior NOTES.md/DISCOVERIES.md hypothesis "A0.a0 = off-route" was **wrong**. Corrected.

Implication for `tools/protocol.py`'s `NavFrame.status` field: the docstring/inline comment should be updated. The encoding is identical (just a 1-char ASCII digit), but the documented semantics need to match this finding.

### Major finding 2 — maneuver ID 39 in the "gap" zone

The bike's icon set extract (from APK `res/drawable-nodpi-v4/ic_step_*.xml`) showed gaps at 9, 26-35, 38-39, 42-49. The ride pcap shows the phone sent maneuver ID 39 to the bike 118 times. Three possibilities:

1. The APK icon extract was incomplete (ic_step_39 may actually exist in a split APK we didn't enumerate)
2. The bike's firmware has icons in the gap zones even if the APK doesn't ship them
3. The bike falls back to a default icon for unknown IDs

To verify: trigger maneuver 39 again on a future ride and visually observe the cluster.

### Major finding 3 — 5 BLE disconnect/reconnect events during the ride

Inter-frame gap analysis on the 21 a536 IDENTITY frames showed 5 gaps >5s (ranging 10.6s to 317.4s = 5+ minutes). This means the BLE connection dropped 5 times during the ride. Likely causes:
- Bike engine off (cluster powers down)
- Stopped at length (e.g. parking, errand)
- BLE range exceeded briefly

Each reconnect triggered a fresh a536 IDENTITY frame (with `is_fresh=False` 'R' = "reconnect to known cluster"), matching our handshake decode.

### Engineering lesson — Frida agent died with the app process

The standard `device.attach(name) → session.create_script → script.load` flow gets a session that's tied to a specific PID. When Android kills that PID (background management, OOM, battery saver, etc.), the agent dies with it. The wrapper script has no signal — frida-server keeps the session in a "ghost" state.

**Solution being built** in a parallel subagent task: `tools/robust_attach.py` with spawn-gating + respawn-aware re-attach. Use that for the next ride. See agent's deliverable.

### Files

- `captures/ride-20260524-1810.pcap` — HCI snoop, 866 KB
- `captures/ride-20260524-1810-frida.jsonl` — partial Frida log (113s of pre-ride data only)

Both gitignored (PII).

## 2026-05-24 — Post-ride deeper analysis (via new `tools/ride_summary.py`)

### Built `tools/ride_summary.py`

A one-shot ride-summary tool that decodes every BLE write+notify in a pcap and prints structured stats: capture window, frame-type distribution, a531 nav (maneuver IDs + status digits), a532/a534/a535 event timeline, a536 reconnect timeline, a537 telemetry (odo / speed / fuel / trip), a533 environmental fields (battery / cell signal / weather / temp). Invoked with `python tools/ride_summary.py captures/ride-<tag>.pcap`.

### New findings from running it on the ride pcap

**Finding A — WhatsApp call also captured ("Amma" at t=14:01)**

Beyond the cellular call from "Me" at t=13:39, the ride also captured a WhatsApp call from "Amma":
```
CallFrame(number='Amma', is_whatsapp=True, state=49)
```
First-ever live capture of an a532 WhatsApp variant. `is_whatsapp=True` confirms our `'W'` marker at byte 22 decode is correct.

**Finding B — Fuel-economy decode is implausibly high for Gixxer SF 150**

Across 159 a537 frames, fuel_econ_kml values ranged 131.2 to 371.2 (median 310.4). A real-world Gixxer SF 150 averages 40-55 km/L. Our default-petrol formula (13.11 fixed-point /10, from `HomeScreenActivity.onClusterDataRecev`) gives values that are 5-10× too high.

Hypothesis: the Gixxer-SF-150 firmware uses a model-specific encoding that diverges from the default petrol formula we extracted. The decompiled source had 3 formulas (Access-TFT, e-ACCESS, default petrol); Gixxer might be a 4th unwritten variant that the app's default branch happens to mis-decode for this bike. OR the cluster sends a different field (e.g., instantaneous mpg or some other metric) that just happens to land in the same bytes.

To verify: capture a known-fuel-economy moment (after a full-tank reset, ride a known distance, compare cluster display vs computed value). Until then, the `TelemetryFrame.fuel_econ_kml` field should be treated as an opaque scaled value for Gixxer bikes, not a kml number.

**Finding C — Weather API DID fire this ride (unlike M0)**

a533 byte 22 (temperature_F+115) ranged 199-202 → 84-87°F → 29-31°C. Realistic ambient temp for the ride.
a533 byte 21 (weather code) split as: 85 frames code 1 ("sunny") + 308 frames code 2 ("cloudy"). Weather updated mid-ride from sunny to cloudy.

This validates the Mappls weather API → C.q/C.r → a533 pipeline end-to-end against live data.

### Maneuver ID 39 — bike-side mystery (cannot resolve via static analysis alone)

The ride sent maneuver ID 39 to the bike 118 times. Static analysis findings:

- **No `ic_step_39.xml`** in any of the 4 APK splits (base + arm64_v8a + en + xxhdpi). Verified via `unzip -l` grep.
- **No phone-side remap entry for 39** in `C0897z.java` (the in-app turn-strip ViewPager adapter). The class remaps 9/10→8, 26-28→15, 29-31→16, 65→67, 72→65-71 (based on `e.b0` state), but 39 falls through to the default `imageView.setImageResource(getResources().getIdentifier("ic_step_39"))` which returns 0 (no such resource). Phone in-app strip renders nothing for maneuver 39.
- **`A0.D()` sends the RAW Mappls ID** to the bike unmodified (`bytes[2] = (byte) i`). No phone-side remap is applied to the wire byte. So the bike sees `39` and its firmware decides what to render.
- **Mappls SDK has no symbolic names** for maneuver IDs — all are anonymous integers after obfuscation. Can't infer "39 = slight-right" or similar from source.

**Conclusion**: We cannot tell from static analysis whether the bike's cluster shows anything for maneuver 39. The cluster firmware has its own icon table independent of the phone's `ic_step_*` resources. To resolve: during a future nav session, visually observe the cluster when ride_summary.py shows maneuver 39 events — either an icon appears (cluster firmware has 39 mapped) or nothing/fallback appears.

This same uncertainty applies to other "gap" IDs in the bike's protocol space (9, 26-31, 38, 42-49, etc.) — they may all have cluster-firmware-side icons we can't enumerate without opening the cluster.

### What this closes / opens

**Closes:**
- Why status='5' fires 26% of frames (DESTINATION REACHED + sticky flag — see earlier section)
- Why we didn't see status='3' (no via-points configured this ride)
- Validation of a532 WhatsApp variant and a534 missed-call template against live data

**Opens:**
- Fuel-economy formula for Gixxer SF 150 — currently produces ~5-10× expected values. Worth a focused capture+verify session.
- Maneuver 39 cluster rendering — empirical-only question; needs visual cluster observation during nav.
- Reverse the `e.b0` state values (20-26) which gate the 72→65-71 remap in C0897z — currently unknown what triggers each.

### 2026-05-24 — Bike does NOT render weather/temperature (user confirmation)

Direct user observation: **the Gixxer SF 150 cluster does not display any weather icon or temperature reading during navigation.**

This is despite the fact that we captured:
- 308 a533 frames with `weather=2` ('cloudy')
- 85 a533 frames with `weather=1` ('sunny')
- 393 a533 frames with `temp_f_plus_115 = 199-202` (= 84-87°F = 29-31°C, valid live data from Mappls)

So the end-to-end pipeline (Mappls weather API → `m.onSuccess` case 13 → `e.D`/`e.F` → `C.q`/`C.r` → `C.M`/`C.N` → a533 bytes 21-22) IS fully active and producing valid byte values, but the **cluster firmware on the Gixxer SF 150 ignores these bytes**.

**Three plausible explanations:**
1. Bytes 21-22 are intended for other Suzuki Connect bikes (Burgman Street, Access 125, e-ACCESS) whose clusters have a weather widget — Gixxer SF 150 firmware doesn't have one and silently drops them.
2. The bike-side firmware uses these bytes for some non-visual purpose (backlight dimming based on temp? rain-warning chime?) — not observed in this ride but worth keeping in mind.
3. The bytes are leftover from an earlier protocol revision that originally had a weather widget; current Gixxer firmware ignores them.

**Implication for Phase 3 forging on Gixxer SF 150:**
- a533 bytes 21-22 are **dead bytes** on the wire as far as the cluster is concerned
- Phase 2/3 replacement client (per `phase2-weather-api` memory) **doesn't need to populate them accurately** — can leave at default `weather=1, temp_f_plus_115=0` or zero them out entirely
- `protocol.py`'s `HeartbeatFrame` should keep the fields documented (they're real fields used by other bike models) but the encoded values don't affect Gixxer cluster output

**To verify hypothesis #2 (non-visual use)**: forge an a533 with `weather=11` ('windy') or `temp_f_plus_115=255` (~140°F, extreme) and see if anything changes — backlight, audio chime, anything subtle. Low priority. Most likely the bytes are simply dropped.

## 2026-05-24 — Parallel-agent ride deep-dive: 8 major findings + 4 corrections

Dispatched 3 subagents in parallel to do thorough timeline analysis of `captures/ride-20260524-1810.pcap` (3,315 frames, 17 min). Agent A focused on a531 NAV, Agent B on a537 + a533, Agent C on connection state + events + cross-frame patterns. Their reports surfaced findings the per-frame-type aggregate stats missed entirely.

### CORRECTIONS to earlier docs (we got these wrong)

**Correction 1 — Fuel-economy decode is fundamentally wrong for Gixxer SF 150.**

Earlier "default petrol formula" assumed bytes 25-27 of a537 form a 24-bit fixed-point. The ride data shows bytes 26 and 27 are **`0xFF` in 100% of 159 frames** — they're padding, not data. **Only byte 25 varies** (61 distinct values in 0x28-0x73 = 40-115). The legacy decoder was packing `(byte25 << 16) | 0xFFFF` into the 24-bit slot and fitting noise — producing the absurd 131-371 km/L values.

**The realistic decode for Gixxer SF 150 is `byte_25 / 2 → 20-57.5 km/L (median 48)`.** Lands squarely in expected range.

But byte 25 **monotonically increases** through the ride AND ticks during engine-off idle. So it's NOT instantaneous fuel-econ. Best hypothesis: **trip-average km/L since last reset, scaled ×2.** To verify: photograph cluster's avg-kml readout during a future ride and compare against `byte_25 / 2` from pcap.

protocol.py now exposes `TelemetryFrame.fuel_econ_kml_v2` (the byte-25/2 formula). The legacy `fuel_econ_kml` field stays for backwards-compat with Access/Burgman models that may use the 24-bit form. Docstring updated to flag the Gixxer caveat.

**Correction 2 — "5 BLE reconnects" was wrong. Only 2 real drops.**

Earlier I counted long gaps between adjacent **a536** frames and called each a reconnect (5 total). But Agent C's any-frame-adjacency check showed only 2 gaps had complete BLE silence:
- **209.4s gap at 17:51:57 → 17:55:27** (triggered by the 32.8s status='6' phone-no-network block preceding it — connectivity drop tore down GATT even after radio recovered)
- **7.4s gap at 18:04:56**

The other 3 "reconnect" gaps had continuous non-a536 traffic across them. **a536 is sent both on reconnect AND as a periodic app-level keepalive** — the 6-frame bursts come from `services/f.java`'s 3-per-tick reliability pattern applied to a536 too. So 5 a536 bursts ≠ 5 reconnects.

**Correction 3 — `is_fresh=True` ('F') is ONLY ever sent on the very first pair-bond.**

All 21 a536 frames in this ride had `is_fresh=False` ('R'), including 2 across real BLE reconnects. So `K.s = false` (= 'R') is set once-and-stays from the bike's perspective forever after first pairing. The "is_fresh = fresh connection" doc was right per source but the practical reality is: in any reasonably-aged pairing relationship, you'll only ever see 'R' on the wire.

**Correction 4 — `continue_flag='0'` is NOT "user terminated nav".**

Earlier protocol.py docstring said `cf='0' = terminate nav`. The ride pcap shows `cf='0'` fires **2-3 frames immediately BEFORE every BLE drop** and at the very last frame of the capture. **It's a phone-side BLE-graceful-disconnect signal** — "I'm about to disappear, hold display state." 6 frames in the ride had `cf='0'` paired with `status='1'` (normal nav) — the app fired the signal then recanted milliseconds later. A forging tool sending `cf='0'` alone won't kill nav; the bike treats it as a one-shot teardown hint that gets overridden by the next frame with `cf='1'`. protocol.py docstring updated.

**Correction 5 (carry-over)** — the suspected "`o0` unit-swap bug" in a531 byte 22 is **also wrong** (zero observations in the ride; all M↔K flips were correct). Walked back.

### NEW FINDINGS (things we didn't know before)

**Finding 1 — Trip A and Trip B are alternating-tick meters offset by 0.05 km.**

They never advance in the same frame. `trip_a - trip_b` is always 39260 or 39261 (across all 159 frames). Each ticked Δ25 (= +2.5 km). The bike's ECU has a 0.05-km internal counter; trip A and trip B sample alternating phases. **The "0.5 km gap between odometer Δ=3 and trip Δ=2.5" wasn't a gap at all** — it was integer-km-truncation at odometer rollovers. Bike actually traveled ~2.5 km; both meters tracked correctly.

**Finding 2 — `c.P` speed_str in a533 is HARDCODED stale across the entire ride.**

All 393 heartbeats had `speed_str='214'` verbatim. The SharedPreferences-cached value never updates from live GPS in this firmware build. Bike doesn't trust this anyway (it has its own ECU speed in a537). **For Phase 2/3 forging tools: set whatever — the bike ignores byte 4-6 of a533.** Documented in protocol.py.

**Finding 3 — `call_pending` byte 15 of a533 is DEAD CODE in Gixxer firmware.**

Agent C cross-checked ±6s windows around all 27 a532/a534 call events. Byte 15 was `'N'` for every single one of 393 heartbeats, including during active cellular AND WhatsApp calls. **Calls are conveyed exclusively via the discrete a532/a534 frames; the heartbeat flag is vestigial** (may be active in other bike models). Documented in protocol.py.

**Finding 4 — `time_hhmmss` lags wall-clock by uniform +1.0 second.**

Field is captured at heartbeat construction time, transmitted ~1s later. No skew across the ride, so it's deterministic, not jitter.

**Finding 5 — Weather API fired exactly at t=334.7s (the first real BLE reconnect).**

First 332 seconds of the ride had `weather=0x01` default and `temp=0x00` unset (no Mappls call). Then at t=334.7s, weather flipped to 0x02 (cloudy) and temp jumped to 0xCA (30.6°C). Mappls weather polls roughly every 568 seconds (~9.5 min). Temperature update at t=903.4s dropped to 28.9°C — confirming evening cooling. **First-ever live evidence of the weather pipeline updating mid-session.**

**Finding 6 — Status='6' (phone offline) was one sustained 32.5-second block.**

t=29.8-62.3s, 138 frames frozen at `dn=0160M, dt=01.3K, eta=0556PM, mid=46`. Clean signature for distinguishing "no network" (long sustained, frozen telemetry, degraded mid=46) from "recalculation" (status='2' for exactly 1 frame).

**Finding 7 — The destination-reached moment is a surgical clean event.**

At t=710.20s (= t=2770.21s absolute) one frame went: `mid=9, status='5', dist_next='0000'M, dist_total='0000'M, eta='0602'PM`. Raw bytes: `a53109ff303030304d30363032504dffffff303030304d3531ffffff187f`. The phone's GPS clamped to "arrived" within one 200ms tick of `dist_next` hitting 20M. After that, status='5' was sticky for 155 seconds (710 frames) until the phone stopped pushing nav at t=865.3s.

**Finding 8 — You missed a turn at t=513.4s.**

`dist_total` jumped 130m → 645m (+515m) in one frame — unmistakable reroute signature. ETA degraded from 5:59→6:00→6:01→6:03 over the next minute. (Recovered to 6:02 final.) Cross-reference: status='2' (recalc) fired exactly at this moment for 1 frame, then status='1' resumed with the new route.

### The Frida JSON mystery resolved

The Frida log at `captures/ride-20260524-1810-frida.jsonl` captured 9 a535 events at frida-relative t=23.6-58.2s. Agent C found these correspond to **wall-clock 17:41:43-17:42:17 IST** — which is **7-8 minutes BEFORE the pcap's first protocol traffic (17:49:54)**. During that earlier window, the phone was in a failed BLE-connect retry loop (LE Create Connection Cancel + retry). The SmsFrame objects were CONSTRUCTED at the Java layer but the `MyBleService.f()` writes failed at the BLE stack — never hit the wire.

This also explains the 3 `sms_pending='Y'` heartbeats at the start of the pcap: stale `i0` flag from the earlier failed session, broadcast for 3 heartbeats on the new BLE connection, then cleared. No fresh SMS event during the pcap's BLE-connected window → no a535 needed.

### Engineering / methodology takeaway

The earlier "aggregate stats only" analysis missed every one of these findings. Counting `weather=2` showed up 308 times told us nothing; tracing **when** it flipped from default → live revealed the weather API was inactive for the first 5+ minutes and only fires periodically. Counting status='5' showed 710 frames; tracing the **exact moment** of `'1'→'5'` revealed the surgical-clean dist→0 cutover.

**Lesson**: for protocol-RE work, time-series-narrative analysis beats distribution-counting every time. ride_summary.py was a half-step (it segregated by frame type but stopped at counts/ranges); the full step is per-event timeline narration. Worth building a `ride_narrate.py` follow-on tool.

## 2026-05-24 — Obfuscated-field enumeration: a533 carries WEATHER + TEMPERATURE

### What we did

Generalized `tools/find_field_writes.py` to take a `<class-path>.<field>` CLI arg, then ran it against every remaining mystery field in a533 + the A0 flags driving a531 byte 23.

### MAJOR corrections (prior speculation walked back)

| Field | Was speculated as | ACTUALLY IS | Evidence |
|-------|-------------------|-------------|----------|
| `C.M` (a533 byte 21) | "mode int 0-11 (gear?)" | **Weather code 0-11** | `C.r(String)` at C.java:4075 parses AccuWeather-style condition strings ("sunny", "cloudy", "thunderstorm", etc.) into 0-11 |
| `C.N` (a533 byte 22) | "angle/bearing (`115.0 + dCeil`)" | **Temperature**, Fahrenheit + 115 offset | `C.q(String)` at C.java:4065 parses temperature like "27°C", converts to Fahrenheit via `ceil(9C/5 + 32)`, stores as `115.0 + F`. Encoded byte = `F + 115`. Decode: `F = byte - 115`, then `C = (F-32)*5/9` |

### Full new field-semantic map

**`C.M` weather code** (a533 byte 21):

| Value | Weather |
|-------|---------|
| 0 | unknown / other |
| 1 | sunny / mostly sunny / clear |
| 2 | cloudy / partly cloudy / overcast / clouds |
| 3 | fog / light fog |
| 4 | showers / light rain |
| 5 | thunderstorms |
| 6 | rain / showers or light rain |
| 7 | snow / flurries / ice |
| 8 | hail / sleet / freezing rain |
| 9 | hot |
| 10 | cold |
| 11 | windy |

**`C.N` temperature** (a533 byte 22):

- `byte_22 = (int)(115.0 + ceil(9C/5 + 32))` where `C` is Celsius temperature from a weather string like `"27°C"`.
- Decode: `F = byte_22 - 115`, `C = (F - 32) * 5/9`.
- 27°C → ceil(80.6) = 81°F → byte = 196 (0xC4). Decode: 196-115=81°F → 27°C. ✓
- Range: ~-40°C to ~60°C (-40°F to 140°F) → byte range 75 to 255. The +115 offset lets the unsigned-byte representation cover sub-freezing temps.

**`C.I` phone cellular signal strength** (a531 byte 23, a533 byte 7):

Set by `com.suzuki.application.fragment.B.onSignalStrengthsChanged(SignalStrength)` — a `PhoneStateListener` callback. Maps:

| `c.I` | Source | Meaning |
|-------|--------|---------|
| `"3"` | `signal.getLevel() >= 4` (or 5G NR `ssRsrp > -90dBm`) | Strong (4-5 bars) |
| `"2"` | `level == 3` (or 5G NR `ssRsrp > -105dBm`) | Medium (3 bars) |
| `"1"` | `level == 2` (or 5G NR `ssRsrp > -120dBm`) | Weak (2 bars) |
| `"0"` | `level <= 1` (or 5G NR `ssRsrp <= -120dBm`) | None / very weak |

So in normal operation, the byte takes values 0-3 (cell signal bars).

**During active navigation**, the same byte (a531 byte 23 via `A0.D()`) is OVERLOADED with extra states from A0's flags. Updated mapping (combining `v0.java` switch with the actual flag sources):

| Value | Source flag | Meaning |
|-------|-------------|---------|
| `'0'` | `C.e1` true (no signal) or airplane mode | Phone offline / no service |
| `'1'` | else branch (normal nav) | Normal operation |
| `'2'` | `A0.X` true (set by Mappls `addNavigationListener` + `routing/i.b`) | **Mappls is rerouting** |
| `'3'` | `A0.b0` true (set by Mappls `routing/i.m`) | TBD — possibly "arrived at destination" |
| `'4'` | `A0.Z` true (set by `A0.B()` GPS-status handler) | **GPS provider disabled** |
| `'5'` | `A0.a0` true (set by Mappls `routing/f.run`) | TBD — possibly "off-route" |
| `'6'` | `A0.v0` true (set by `y0.onReceive` from `CONNECTIVITY_ACTION`) | **Phone has no network connectivity** |

So the cluster gets either "cell bars 0-3" (when not navigating) OR "nav state 0-6" (when navigating). The bike presumably has icons or text mapped to each value.

### Updated a533 semantic picture

Previously called a "heartbeat." It's actually a **complete environmental dashboard frame** carrying:

| Pos | Field | Carries |
|-----|-------|---------|
| 2-3 | `c.G[0..1]` | Phone battery level bucket + charging state |
| 4-6 | `c.P` | Speed (from SharedPreferences, often stale when engine off) |
| 7 | `c.I` | Phone cell signal bars (0-3) |
| 8-13 | `c.O` | Current wall-clock time (hhmmss 12-hour) |
| 14 | `i0` | SMS/notification pending flag (N/Y) |
| 15 | `j0` | Call pending flag (N/Y) |
| 21 | `c.M` | **Current weather code (0-11)** |
| 22 | `(int) c.N` | **Current outdoor temperature (Fahrenheit + 115 offset)** |
| 23 | literal `0x01` | constant |

The cluster apparently displays weather and temperature alongside time and notifications — making sense of why riders see a small weather widget on Suzuki Connect-equipped bikes.

**Where does the weather data come from?** Not yet traced. C.q() and C.r() take string inputs; their callers must be pulling from Mappls SDK's weather service or another API. Worth tracing if Phase 2 (replacement app) wants to keep the weather display working. For Phase 3 forging tools, we can synthesize values directly.

### Other smaller findings

- `A0.Z` is set by `A0.B(boolean)` — the GPS-status method we already saw. `Z=true` when GPS lost, `Z=false` when restored. Confirms our prior decode.
- `A0.X` has 5 writers: Mappls `addNavigationListener`, `routing/i.b`, A0 `<init>`, `o0.run`, `v0.run`. Multiple state machines toggle it — most likely "recalculation needed" set during route-deviation events.
- `A0.a0` is set by Mappls `routing/f.run` — likely an off-route detector.
- `A0.b0` is set by Mappls `routing/i.m` — likely arrival or maneuver-passed event.

These three (X, a0, b0) need a live ride capture to fully pin semantics. Static analysis is ambiguous.

### Lesson

The dex-bytecode field-write tool (`find_field_writes.py`) keeps paying dividends. Every time JADX shows a field with default-only assignment, running the androguard scan surfaces the real writer — often in a ProGuard-renamed class outside the app's namespace (`androidx/appcompat/app/z.java` for `C.G`, `com.suzuki.application.fragment.B` for `C.I`, `com.suzuki.application.fragment.y0` for `A0.v0`).

Tool now general-purpose: `python tools/find_field_writes.py <class-path>.<FieldName>` works for any class/field combo.

## 2026-05-24 — License/subscription state is PURELY COSMETIC

### What we did

Earlier commits established that the only cloud API surface is the 2 Mappls license endpoints (subscription expiry check + plan update). Today's question: **does the bike actually care?** Specifically:

- Is BLE write privilege gated on a valid subscription?
- Is any frame field (a531/a533/a536/etc.) altered based on subscription status?
- Does the bike refuse to function if the phone hasn't authenticated to the cloud recently?

### Findings (all NO)

**ACCESS_TOKEN usage trace:**

The OAuth access token (from the cloud token endpoint) is:
1. Written to `SharedPreferences("AppPrefs").ACCESS_TOKEN` by `DashboardFragment$16` after the token API succeeds
2. Read by **`okhttp3.internal.platform.d.y()`** — a single getter (line 707-709 of that file)
3. Used by exactly 5 call sites — every one is a Mappls license API call (`https://projects.mapmyindia.com/autolicverify/...`):
   - `RunnableC0828e.java:65, 105, 175, 206` (4 license-API calls inside ActivateYourPlan flow)
   - `DashboardFragment$16.java:76` (1 license-API call inside dashboard refresh)

That's it. No other code reads `ACCESS_TOKEN`. **The token is NEVER used for BLE writes, notify subscription, or any non-license purpose.**

**Subscription-expired flag (`com.suzuki.pojo.e.w0`) usage trace:**

Set by `DashboardFragment$16.onResponse` (the license-API callback) — `true` if both complimentary period expired AND no active paid subscription, else `false`. **Crucially: on API failure** (no internet, timeout, server error) `e.w0 = false` — the app assumes "subscribed" rather than denying service.

Read by:
- `C0915i0.t()` — sets visibility on a "no subscription" banner (`this.c.setVisibility(...)`) and swaps the banner background between `R.drawable.no_subscription` (default) / `R.drawable.no_subscription_ev` (e-ACCESS). UI only.
- `RunnableC0911g0.java:40` — just a `Log.e("SUB_DASH_Map", ...)` debug line.
- `SubscriptionManager$2.lambda$onResponse$0` — wakes up `C0915i0.t()` after the API call returns. UI only.

**Total READ paths for `e.w0` = three. All UI visibility. Zero functional gating.**

**No BLE frame field carries subscription state:**

Every TX frame's body has been fully decoded (commits `b916c2c`, `b99ca90`). No subscription/license byte anywhere in a531/a532/a533/a534/a535/a536.

### Cloud architecture verdict

- Cloud talks to the phone for license bookkeeping only
- Phone NEVER tells the bike about subscription state (no protocol field exists for it)
- Bike NEVER asks the phone about subscription state (no RX frame type)
- On API failure, the app fails OPEN (`e.w0 = false`, "subscribed") — even Suzuki's own app doesn't enforce the subscription if the cloud is unreachable
- Even when `e.w0 = true` ("expired"), the only effect is a UI banner — BLE writes continue normally, the bike keeps receiving nav/heartbeat/identity frames, the cluster keeps displaying them

### Phase 2 implication

A replacement Android app (or Linux laptop / ESP32) **can skip the cloud entirely with zero functional loss**. No subscription check. No token refresh. No license API calls. The bike will be unable to tell the difference between the official Suzuki app with an active subscription and a 200-line custom Python script. The cluster display, the BLE write/notify behavior — all identical.

The only thing lost by skipping the cloud is the "X days until renewal" banner the official app displays — and even that's only displayed *if* the user opens the dashboard tab.

### Phase 3 implications

- Branch A (custom cluster display via forging): zero impact — already works without cloud.
- Branch B (telemetry dashboard): zero impact — a537 notifies are bike-initiated and don't pass through anything cloud-touched.
- Bike-firmware reflash (parked indefinitely per the cluster-hardware research note above): zero impact — was never coupled to subscription anyway.

### What this closes

- "Does Phase 2 need to maintain a license bookkeeping path?" → **No.**
- "Could a subscription-lapse brick the bike's BLE features when paired with the official app?" → No. The official app fails open.
- "Is there any hidden auth tied to subscription?" → No. The ACCESS_TOKEN is only used for cloud calls, never for BLE.

This combined with the no-application-layer-auth finding from earlier today (`69da3ce`) makes the picture extremely clean: **a 200-LOC Python script + our `protocol.py` library is functionally equivalent to the full Suzuki Connect app for everything the bike actually does.**

## 2026-05-24 — Weather data source: Mappls SDK internal API

### Trace question

a533 byte 21 (`C.M` weather code) and byte 22 (`C.N` temperature) get populated by `C.r(String weatherText)` and `C.q(String tempStr)`. But who CALLS those methods? Where do the strings come from? Trace it end-to-end to identify any 3rd-party API endpoint we hadn't catalogued.

### Pipeline

```
[trigger] C.java line 806:
  MapplsWeatherManager.newInstance(
    MapplsWeather.builder().location(lat, lng).build()
  ).call(new androidx.work.impl.model.m(this, iArr, handler, 13, false))
       ↓ Mappls SDK does HTTP internally
       ↓ GET https://explore.mappls.com/<weather endpoint>?lat=&lng=...
       ↓
[response]: WeatherResponse POJO with .data.temperature, .weatherCondition.weatherText, .airQuality.airQualityIndex, etc.
       ↓
[callback] androidx/work/impl/model/m.onSuccess (case 13 — multipurpose callback,
  ProGuard-merged with many other handlers; the WeatherResponse switch is at line 552):
  com.suzuki.pojo.e.D = "<temp value><unit>"  (e.g. "27°C")
  com.suzuki.pojo.e.E = <icon URL>
  com.suzuki.pojo.e.F = <weather text>        (e.g. "Partly Cloudy")
  com.suzuki.pojo.e.G = <AQI>
  com.suzuki.pojo.e.H = <real-feel text>
       ↓
[immediate dispatch within m.onSuccess, lines 597-604]:
  c.r(com.suzuki.pojo.e.F)    →  C.M = weather code (0-11) via C.r() switch on lowercase text
  c.q(com.suzuki.pojo.e.D)    →  C.N = 115 + ceil((9C/5)+32) via C.q() temperature parse
       ↓
[next C0940y heartbeat tick, ~1s later]:
  a533 frame built with byte 21 = (int) C.M, byte 22 = (int) C.N
       ↓
[bike cluster receives a533, decodes byte 21 as weather-icon index, byte 22 as F+115 → displays temperature]
```

### New cloud endpoint catalogued: `https://explore.mappls.com/`

The Mappls SDK class `MapplsWeather` (and many sibling classes — `MapplsNearbyReport`, `MapplsCostEstimation`, `MapplsCategoryMaster`, `MapplsRouteSummary`, `MapplsSubmitReport`, `MapplsFuelCost`, `MapplsPlaceDetail`) all build on `Constants.EXPLORE_BASE_URL` = `"https://explore.mappls.com/"`. This is a 3rd-party API endpoint Suzuki Connect talks to (via the bundled SDK), distinct from the 2 license endpoints on `projects.mapmyindia.com` we already catalogued.

**Refined cloud surface inventory:**

| Endpoint | Code path | Purpose | Required for bike control? |
|----------|-----------|---------|---------------------------|
| `https://projects.mapmyindia.com/autolicverify/...` (2 endpoints) | Suzuki code, direct | Subscription expiry + plan update | No — purely cosmetic (DISCOVERIES.md 2026-05-24 license audit) |
| `https://explore.mappls.com/<weather>` | Mappls SDK internal | Weather + temperature | No — bike just won't show weather icon (cluster defaults to weather=1, temp=0) |
| `https://explore.mappls.com/<aqi>` | Mappls SDK internal | Air-quality index | No — only used in app UI, no a5XX byte for AQI |
| `https://explore.mappls.com/<poi, nearby, costEstimation, ...>` | Mappls SDK internal | Map/POI features | No — orthogonal to bike protocol |
| Mappls map-tile servers (various `mappls.com` paths) | Mappls SDK internal | Navigation map rendering | No |

**Earlier claim refinement**: the prior "only 2 endpoints" was for **Suzuki-coded HTTP**. The Mappls SDK adds many opaque API calls under `explore.mappls.com` and `apis.mappls.com`. They were not catalogued before because our URL grep was scoped to `com.suzuki.*`. They don't change the "Phase 2 can skip the cloud" verdict — all of them are optional for bike control.

### Phase 2 implications

A replacement client has three options for weather:

1. **Skip weather entirely**: leave a533 byte 21 = 0x00 (default) and byte 22 = 0x00 (default). Cluster displays no weather icon and presumably a sentinel temperature, OR ignores the bytes. Costs nothing; least information shown.

2. **Bundle the Mappls SDK**: get weather automatically the same way the Suzuki app does. Requires a Mappls API key and the SDK dependency.

3. **Use a different weather API** (OpenWeatherMap, AccuWeather direct, etc.): fetch temperature + weather text yourself, feed values to `HeartbeatFrame(weather=..., temp_f_plus_115=...)` from our `protocol.py`. Easy with any HTTP client.

For our protocol library, no changes needed — `HeartbeatFrame` already exposes `weather` and `temp_f_plus_115` as direct fields. The encoding is the same regardless of source.

### What this closes

- "Where does the a533 weather data come from?" → Mappls SDK's `explore.mappls.com` weather endpoint, dispatched in C.java line 806, callback in `androidx/work/impl/model/m.onSuccess` (case 13).
- "Is there a hidden 3rd-party API we missed?" → Yes, `explore.mappls.com` (multiple endpoints, all routed through Mappls SDK internally). Doesn't change Phase 2 viability — all optional.

### What's open

- Trigger for the weather API call (line 806 was inside some onClick or scheduled handler — would be worth confirming when it fires, e.g. "on every nav start" vs "every X minutes" vs "manual user action")
- Whether Mappls SDK ever fires its `explore.mappls.com` calls without an API key (likely no — Mappls requires registration). A replacement client using Mappls would need its own key.

### What this closes / opens

**Closes:** the "what writes does the phone send" question is statically resolved. 6 TX frame types catalogued, each with at least one source template.

**Opens:**
- Cross-verify the captured a533 byte 2 (mystery above)
- Capture a call / SMS / WhatsApp event with HCI snoop running to validate the a532 / a534 / a535 templates
- `c.G`, `c.M`, `c.N` semantic verification (mode-byte / angle/bearing-byte) — needs a live ride
- Whether `K.g` for Gixxer SF 150 specifically is true or false — depends on the cluster's BLE name; our LOCAL_NOTES.md has the cluster MAC but probably not the name char-by-char. Quick check next session.

## 2026-05-25 — Oil change / periodic-service detection is APP-side, not bike-side

### Context

While testing the live Dashboard with a freshly-connected bike, Arjun asked: how does the bike detect that it's time for an oil change? Sensor? Hardcoded km?

### Finding

The bike has **no oil-life or service sensor**. Service detection is **entirely app-side**: the Suzuki Connect app holds hardcoded calendar-day + odometer-km thresholds and compares them against the live odometer reading (from a537) and a stored install/reset date.

Source: `decompiled/jadx-out/sources/com/suzuki/activity/PeriodicVehicleServiceActivity.java:158-180`. The `n(...)` call sets up a reminder; the 6th arg is days, the 7th is km threshold (range string or `""` for no km gate):

```
Air Filter Replacement    →  365 days  OR 12000 km
Brake Oil Change          →  730 days  (no km gate)
Periodic Service (engine) →  120 days  OR 3500-4000 km
Spark Plug Change         →  240 days  OR  8000 km
Battery Checkup           →  120 days  OR 3500-4000 km
```

The app fires a notification (and writes a reminder into local storage — see `NotificationHistoryActivity.java:162` keys: `_ServiceDate`, `_Kilometers`, `_PriorDays`, `_VehicleName`) when either threshold crosses. The bike-side a537 payload has no service-related byte; it's purely speed / odo / Trip A / Trip B / fuel bars / fuel economy.

### Why this matters

- Our GixxerBridge `Settings.serviceIntervalKm` mirrors this model — km-based gate computed in-app from the live odometer. No additional BLE work needed.
- Replacing the official app does NOT lose any service-detection capability. The Suzuki app isn't reading anything special from the bike; it's just doing the same odometer arithmetic we already do.
- The "Bike health · Service N" gauge on Home is functionally equivalent to what the Suzuki app shows, as long as we track per-service km thresholds independently (currently we only track one). Future enhancement: per-service intervals matching the table above.

### What this closes

- "Is there an oil sensor?" → No.
- "Where does service-due come from?" → Hardcoded constants × odometer + install date, both app-side.
- "Does the bike push a service warning over BLE?" → No frame for it; bike never tells the phone when service is due.

## 2026-05-25 — BLE handshake timing: the bike drops the link if identity is late

### Context

First end-to-end live test of the GixxerBridge replacement app against the bike. Connection reached `Ready` cleanly, heartbeats wrote OK at 1 Hz, but bike stayed in "pairing mode" on the cluster and dropped the link after ~26-35 s with `status=8` (`HCI_ERR_CONN_TIMEOUT`). Repeated across multiple attempts.

### Root cause

Three distinct problems, only fully diagnosed by adding `BluetoothStatusCodes` Int-level logging to `BleClient.write` and cross-referencing the M0 pcap.

**(1) Identity (a536) `writeCharacteristic` was returning `false` *immediately* after Ready.** Our old code flipped `_state.value = Ready` synchronously inside `onServicesDiscovered`, before the CCC descriptor write completed. Android's BLE stack allows **only one outstanding GATT op** per connection — the identity write raced the still-pending descriptor write and the stack rejected it. The bike never saw a536, sat in pairing mode waiting, and TCP-equivalent supervision timed out the link.

Pcap proof of the right ordering (`captures/m0-pairing-and-first-nav-20260523-1712.pcap` — official Suzuki Connect app):

```
63.082  Exchange MTU Req (client=517)
63.104  Exchange MTU Resp (bike caps at 65)
63.642  Sent Write Request → CCC of 0xFFF2     ← 538 ms gap
63.749  Rcvd Write Response (ack)
63.799  Sent Write Request → a536 IDENTITY     ← 50 ms after CCC ack, not before
63.885  Rcvd Write Response
64.201  Rcvd Handle Value Notification (a537 starts streaming)
```

The decompiled APK does this same dance in `MyBleService.a(BleDevice)` with an explicit `postDelayed(500)` between MTU negotiation and the notify subscription (already noted in DISCOVERIES 2026-05-24 "Connection handshake fully mapped" line 1008).

**(2) Even after waiting for the CCC ack, the first 1-1.5 s of `writeCharacteristic` calls still returned synchronous failures.** On TIRAMISU+ the new overload returns `BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY` (= 201) for a brief window after a descriptor write completes — the stack is still draining its internal queue. The old boolean-returning overload masked this as a generic `false`. Heartbeats happened to succeed ~1.5 s later (after the URGENT identity write had already been given up on), which is why we saw a connection that *seemed* live but the bike was sitting silent.

**(3) The writer drain was dropping entries when the link wasn't Ready.** Old code: `take(); if (!Ready) { delay(100); continue; }` — that `continue` silently discards the dequeued entry. URGENT identity racing a fresh connect could be eaten outright. Fixed by re-enqueueing instead of dropping.

### Fixes shipped (commit pending)

All in `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ble/`:

- **`BleClient.runHandshake(gatt)`** (new) — replaces inline `onServicesDiscovered` work. Sequence: `requestMtu(65)` → wait `onMtuChanged` → `delay(500)` → `setCharacteristicNotification` → `writeDescriptor(CCC, ENABLE)` → wait `onDescriptorWrite` → only THEN mark `_state = Ready`. Two new `CompletableDeferred` paired with `onMtuChanged` and `onDescriptorWrite` callbacks.
- **`BleClient.write` retry loop** — polls `writeCharacteristic` every 50 ms for up to 2 s on transient `BUSY (201)` returns. Identity now lands on the 2nd attempt. Logs `write: ok type=0x36 after N attempts (busy backoff)` so the success-after-retry is visible in the diag log.
- **`writeStatusName` helper** — maps `BluetoothStatusCodes` Ints to friendly names (`SUCCESS`, `BUSY`, `WRITE_NOT_ALLOWED`, `DEVICE_NOT_BONDED`, …) so the diag log no longer says "returned false" without context.
- **`BikeBridgeService` writer drain** — re-enqueues instead of dropping when not Ready, and logs every TX with type byte for easy correlation with bike behavior.

### Proof it works (2026-05-25 09:24 diag log)

```
09:24:05.653  onDescriptorWrite status=0 (SUCCESS)
09:24:05.655  CCC notify-enable ack received — handshake clear to send
09:24:05.656  Ready
09:24:05.832  write: ok type=0x36 after 2 attempts (busy backoff)   ← IDENTITY landed
09:24:05.925  writer: TX identity type=0x36
```

Bike then left pairing mode, accepted welcome a531, started streaming a537. Arjun confirmed cluster greeting displayed correctly.

### What this updates

- The earlier DISCOVERIES 2026-05-24 entry ("Connection handshake fully mapped") was accurate about what the *Suzuki app* does but didn't call out that the 500 ms delay and the CCC ack wait are **load-bearing** for a third-party client, not cosmetic. A correct client cannot skip either. Same goes for the post-descriptor-write busy window — that's an Android stack quirk, not protocol-level.

---

## 2026-05-25 — Suzuki cluster uses TI OUI 74:B8:39, but BLE name may go missing

### Findings during pair-screen testing

1. **The Suzuki cluster's confirmed public MAC OUI is `74:B8:39`** (Texas Instruments BLE SoC range). Cluster name advertised: `SBXXXXXXXXXX` (`SBM` = Suzuki Bike Model prefix + 9-digit serial). Used as a "likely your bike" heuristic in the pair UI.

2. **One earlier session showed a different MAC** `22:1E:DA:75:57:B4` saved in DataStore — leading byte `0x22 = 00100010`, top 2 bits `00` = **non-resolvable random BLE address**. Either the cluster occasionally rotates address (rare for a fixed peripheral), or this was a stale leftover from a different test device. Worth a follow-up confirmation that `AA:BB:CC:DD:EE:FF` is the stable address for this bike across power cycles.

3. **Most non-Suzuki nearby BLE devices advertise no name at all** (31-byte adv packet fills up fast; phones/buds put their name only in the scan response or omit entirely). Without a vendor label, the pair list showed raw MACs and "(unknown)" titles. Fixed by `BleVendor.identify(ScanResult)` (`android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ble/BleVendor.kt`) which derives a label from, in priority order:
   - Manufacturer-specific data → 16-bit Bluetooth SIG company ID (Apple=0x004C, Samsung=0x0075, Google=0x00E0, Microsoft=0x0006, …). Works on randomized-MAC devices because companies still embed their company ID.
   - Service data UUIDs → 16-bit member service (Fast Pair=0xFEF3, Eddystone=0xFEAA, …).
   - MAC OUI → vendor (curated table — Suzuki/TI specifically + common phone vendors).

   Live test result: previously-unknown devices now show "Google (Fast Pair / Find My)", "Samsung", etc.

### What's open

- Cluster MAC stability across power cycles — verify `AA:BB:CC:DD:EE:FF` doesn't change. If it does, scanning by service-UUID-or-name (instead of MAC) is the only durable identity.

### 2026-05-25 (later) — Cluster MAC stability: evidence-based answer

Follow-up investigation on the H1/H2 question above (does the cluster rotate its BLE MAC across power cycles?). Three independent evidence channels were checked.

#### 1. The official Suzuki Connect app reconnects by NAME, not by MAC — FACT

The official app uses the [FastBle](https://github.com/Jasonchenlijian/FastBLE) library (`com.clj.fastble.*` in the decompile). All scan/connect paths route through `com.clj.fastble.b`:

- **Scan** (`b.i(callback)`, decompiled/jadx-out/sources/com/clj/fastble/b.java:221) calls `startLeScan(null, ...)` — a totally unfiltered scan that delivers every nearby BLE advertisement.
- **Connect** (`b.b(BleDevice, callback)`, b.java:47) — takes a `BleDevice` (which wraps an `android.bluetooth.BluetoothDevice`) and calls into the standard `BluetoothGatt` connect path on its `getRemoteDevice(mac)` instance.

The crucial question is therefore **what produces the `BleDevice` passed into `b.b()`** in both the first-time pair and the reconnect-after-disconnect paths.

**First-time pair** (`DeviceListingScanActivity`, decompiled/jadx-out/sources/com/suzuki/activity/DeviceListingScanActivity.java + `adapter/C0882j.java`):
- The scan callback `G(this, 0).y(BleDevice)` (decompiled/jadx-out/sources/com/suzuki/activity/G.java:114-119) forwards every result into `C0882j.a(BleDevice)`.
- `C0882j.a()` filters by **NAME**: it accepts a device only if `bleDevice.c()` (= `BluetoothDevice.getName()`) contains `"SC"`, `"SB"`, or `"SA"` **and** is exactly 12 chars long (adapter/C0882j.java:48). Our bike's name `SBXXXXXXXXXX` matches: starts with `SB`, 12 chars.
- When the rider taps a row, on `onConnectSuccess` (`C0855q0.b()` case `default`, activity/C0855q0.java:75-127) the app saves **both** the name AND the MAC into SharedPreferences `BLE_DEVICE`:
  - `prev_cluster` = `bleDevice.c()` = the BLE name (e.g. `SBXXXXXXXXXX`)
  - `prev_cluster_macAddr` = `bleDevice.b()` = the MAC (e.g. `AA:BB:CC:DD:EE:FF`)
  - And the *name* (not the MAC) is mirrored into the in-memory `LiveData<String>` `com.google.android.gms.measurement.internal.K.t`.

**Reconnect after disconnect** (`HomeScreenActivity.p()`, activity/HomeScreenActivity.java:527-534):
- The reconnect path **starts a fresh scan** (`bVar.i(new G(this, 1))`), not a `getRemoteDevice(savedMac).connectGatt()`.
- Scan callback case 1 (G.java:121-129) compares the discovered `bleDevice.c()` (NAME) against `com.suzuki.application.fragment.C.b1` (the saved cluster name, populated from `K.t.d()` which was set from `bleDevice.c()` at first pair).
- On match: the app logs `"cluster found"` and proceeds.
- The saved MAC (`prev_cluster_macAddr` in SharedPrefs) is read by `MyBleService` and `services/d.java` paths, but the **primary key for re-identifying the bike is the BLE name**, not the MAC.

**Conclusion (fact)**: The official Suzuki Connect app's reconnect path is **name-based, not MAC-based**. Even though it persists the MAC, the actual scan→match logic compares names. This is strong indirect evidence that Suzuki's engineering team considers the cluster MAC *possibly* unstable (otherwise the canonical scan-then-match-by-name approach would be wasteful — `getRemoteDevice(mac).connectGatt()` is much simpler).

#### 2. Multiple historical captures of this bike all show the same MAC — FACT

Across four `.pcap` files in `captures/` from 2 distinct days (2026-05-23 afternoon + evening, 2026-05-24 ride), `tshark -T fields -e bthci_evt.bd_addr` yields exactly one MAC in the `74:b8:39:*` OUI:

| Capture file | Date | `74:b8:39:*` BD_ADDR hits | Distinct MACs |
|---|---|---|---|
| `m0-pairing-and-first-nav-20260523-1712.pcap` | 2026-05-23 ~17:12 | 332 | `AA:BB:CC:DD:EE:FF` |
| `m0-with-2-nav-20260523-1719.pcap` | 2026-05-23 ~17:19 | 332 | `AA:BB:CC:DD:EE:FF` |
| `with-sim-nav-20260523-1840.pcap` | 2026-05-23 ~18:40 | 5 | `AA:BB:CC:DD:EE:FF` |
| `ride-20260524-1810.pcap` | 2026-05-24 ~18:10 | 14 | `AA:BB:CC:DD:EE:FF` |

No occurrence of `22:1e:da:*` in any pcap. Each pcap spans at least one full connection lifecycle (advertisement → discovery → GATT connect → identity write → notify stream), so every connection event observed across two days used the same MAC.

#### 3. The MAC is in the bike's hardware via 2a23 System ID — FACT

NOTES.md line 363 documents that reading the standard BLE Device Info Service characteristic `2a23 System ID` returns `ffeedd0000ccbbaa` — that's `AA:BB:CC:DD:EE:FF` in big-endian with a 2-byte manufacturer-ID stub (`0x0000`) inserted between the upper and lower MAC halves, exactly per the BLE System ID spec (lower 3 bytes of MAC, then 2 manufacturer bytes, then upper 3 bytes of MAC). This is a hardware-burned identifier that a peripheral with rotating/non-resolvable random addresses *would not* expose — non-resolvable random BLE addresses by definition are unrelated to the chip's IEEE EUI-48.

A TI BLE SoC with a public IEEE-allocated OUI (`74:b8:39`) and a `2a23 System ID` that matches the advertised BD_ADDR is the canonical signature of a **fixed public BLE address**.

#### Answer to H1 vs H2

- **H1 (cluster rotates MAC every power cycle): falsified by current evidence.** Across 4 pcaps spanning ~25 hours and multiple key-on/key-off cycles, the bike's BLE BD_ADDR is `AA:BB:CC:DD:EE:FF` every time. Plus the hardware-burned `2a23 System ID` matches that MAC byte-for-byte, which is incompatible with random/rotating addresses.

- **H2 (the `22:1E:DA:75:57:B4` DataStore entry was a stale leftover): supported.** That MAC's top 2 bits are `0b00` (non-resolvable random), and the OUI prefix `22:1E:DA` does not match the bike's TI OUI. No pcap contains it, and no current code persists it. Most plausible explanation: it was written in a prior debug build by either a typo, a different test device, or by an Android stack quirk where a peripheral's transient random address got cached before bonding upgraded to identity-resolved.

#### Caveats / what this doesn't prove

- **All 4 pcaps were captured within ~25 hours.** Longer-term rotation (e.g. weekly, on FW update, after battery disconnect) is not ruled out — only short-term per-power-cycle rotation. The diag-file hook below is the durable verification path.
- **All captures are on a single phone (Redmi K20 Pro / LineageOS 16).** If another phone's BT stack ever sees a different address for this same cluster, that would be new evidence — but the `2a23` hardware ID would still be the source of truth.
- **The official Suzuki app uses name-based reconnect.** That's evidence the *Suzuki engineers* might have been uncertain about MAC stability, but it could equally be a FastBle library convention. Doesn't independently prove the MAC actually rotates.

#### Implication for GixxerBridge — RECOMMENDED CHANGE (not done in this commit)

Today `BleClient.connect(mac)` uses the saved MAC directly via `adapter.getRemoteDevice(mac).connectGatt(...)`. Given the current evidence, this is safe **for this specific bike right now**. But to be robust if rotation ever does happen (and to match the official app's strategy), the durable identity should be the saved BLE name with the MAC as a tie-breaker.

Suggested future change (tracked here, not in this commit):

1. Persist `bikeName` (e.g. `SBXXXXXXXXXX`) alongside `bikeMac` in `Settings`.
2. On reconnect: first try `getRemoteDevice(savedMac).connectGatt()` (fast path).
3. On `CONN_TIMEOUT` (status=8) for >N seconds, fall back to: scan with `ScanFilter` matching the saved name (or no filter + adapter-side filter), pick the first SBM* result whose name == saved name, and connect on the freshly discovered MAC. Update saved MAC if it changed.

Not shipped now because (a) current evidence says rotation is unlikely, (b) the diag hook below will give empirical confirmation over the next few power cycles, (c) it's a substantial refactor touching the autoConnect contract.

#### Diag hook shipped this commit

`android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ble/BleScanner.kt` now appends one JSONL row to `filesDir/diag/cluster-mac-history.jsonl` every time the scanner sees a fresh device whose advertised name starts with `SBM`. File is capped at 50 entries (oldest dropped on overflow). Pull command:

```bash
adb shell run-as dev.mrwick.gixxerbridge.debug cat files/diag/cluster-mac-history.jsonl
```

Line format (UTC timestamp, ISO8601 millis):
```
{"t":"2026-05-25T09:24:05.123Z","name":"SBXXXXXXXXXX","mac":"AA:BB:CC:DD:EE:FF","rssi":-50}
```

To-verify protocol: open the GixxerBridge pair screen → key the bike on → confirm a row is appended → key the bike off + power-cycle the cluster (turn bike fully off, count to 10, key on again) → open pair screen again → confirm a second row. Repeat across 5-10 power cycles over a few days. If all rows for `SBXXXXXXXXXX` show the same MAC, H2 is empirically confirmed. If any row shows a different MAC for the same serial, H1 becomes the live case and the name-based fallback above becomes load-bearing.

---

## 2026-05-25 (end of day) — Full UI overhaul shipped + supporting infrastructure

### Live-bike test exposed BLE handshake race

First real run of GixxerBridge against the bike this morning. Connection reached `Ready` but the cluster stayed in pairing mode and dropped with `status=8` (`HCI_ERR_CONN_TIMEOUT`) every attempt. Root cause was three layered bugs: identity write was racing the still-in-flight CCC descriptor write, Android's TIRAMISU+ BLE stack returns `ERROR_GATT_WRITE_REQUEST_BUSY (201)` for ~1 s after the descriptor write completes, and the writer drain was silently dropping dequeued entries when not Ready. Fixed by a proper `runHandshake()` sequence gated on `onDescriptorWrite` callback + 50 ms retry-on-busy loop. Full trace in the earlier 2026-05-25 BLE-handshake-timing entry above.

### AppLog + Diagnostics screen

Added `util/AppLog.kt` — process-singleton with a 2000-entry in-memory ring (for `ui/diagnostics/DiagnosticsScreen.kt`), logcat mirror, and rolling file at `filesDir/diag/app.log` (~512 KB cap, 1 rotation). Every BLE site and the pair flow now use `AppLog.i/d/w/e`. Pull command: `adb shell run-as dev.mrwick.gixxerbridge.debug cat files/diag/app.log`. Motivation: diagnosing the handshake race required knowing exactly what the BLE stack returned on each write; the log gave us that without needing the phone tethered.

### Pair-screen vendor labels, "Currently paired" card, RSSI smoothing

`BleVendor.identify(ScanResult)` derives a human-readable label from manufacturer-data company ID (Apple, Samsung, Google, Microsoft), service-data UUID (Fast Pair, Eddystone), and OUI table — so the pair list no longer shows raw MACs for everything that isn't a Suzuki. A "Currently paired" card surfaces the live BLE state (connecting / ready / disconnected) with a Forget button and bonded-devices seeding. RSSI smoothing: `BleScanner.onScanResult` applies `smoothed = 0.3*raw + 0.7*prev` per device; `PairingViewModel.results` samples the resulting flow at 500 ms via `kotlinx.coroutines.flow.sample()` to prevent the UI thrashing at 10 Hz.

### Demo mode auto-disable

`BikeBridgeService` checks `settings.demoMode` on every a537 notify. On the first real frame, it calls `settings.setDemoMode(false)` and emits `AppEvent.DemoModeAutoDisabled` on the new `app/AppEvents.kt` one-shot event bus (replay=0, no stale buffering). MainActivity's Scaffold `SnackbarHost` shows "Real bike telemetry detected — Demo mode turned off." with an Undo action.

### MapsNavSource 60 s stale-nav watchdog

`MapsNavSource` starts a 60-second watchdog on every nav update. If Google Maps stops posting new notification extras for 60 s (Maps minimized, nav ended, etc.), the cached nav frame is cleared so the cluster falls back to idle-clock mode rather than showing a frozen ETA.

### Per-service intervals implementation landed

`data/ServiceSchedule.kt` and `analytics/ServiceSchedule.kt` implement the per-service interval table (air filter / brake oil / periodic engine service / spark plug / battery) with both day-based and km-based thresholds, matching the values from the Suzuki Connect source. xref: 2026-05-25 oil-change entry above for the source trace.

### 5-wave UI overhaul completed in one day (commits c79e1e9..c77d32e)

All five waves plus post-wave polish shipped to master in a single day:

- **Wave 1**: `GixxerTokens.kt` (Linear-style dark stack + two-tier red), `GixxerFonts.kt` (Inter + Geist Mono via downloadable Google Fonts), `Motion.kt` (SpringStandard + SpringSoft), Konsist hardcoded-hex lint test, 5 home components (SpeedDisplay, ConnectionDot, TodayHeroCard, QuickActionsRow, EmptyState), 3-zone Home rewrite, ClusterPreview retokenize, Roborazzi golden test.
- **Wave 2**: Speed-first Dashboard rewrite; Active-ride layout (BLE-speed-gated, >5 km/h for 3 s); Settings Active-ride bottom-metric picker.
- **Wave 3**: Stats + Trips + TripDetail retokenize; Spotify-Wrapped-style PostRideSummary (4-card spring sequence, haptics, shareable PNG).
- **Wave 4**: Settings split into 5 sub-routes (Bike / Cluster / Notifications / Maintenance / Developer); Pairing + Diagnostics + Onboarding token sweep.
- **Wave 5**: SOS + Crash prompt + AppLock cold-start polish.
- **Post-wave polish**: floating-pill bottom nav (Instagram/X style), Trips listing premium row (date rail + sparkline), cluster preview humanization (idle-clock / now-playing layouts), RSSI smoothing + pair-screen UI throttle.

### Custom bottom nav iterations

Started with M3 `NavigationBar` (too tall, label noise). Iterated through: labels + pill → labels + dot → icons-only no-container → floating pill → final form: no-dot, color-contrast-only selection indicator. Custom `ui/nav/GixxerBottomNav.kt` replaces M3 entirely.

### Cluster preview humanization

`ClusterPreview` now switches layout per producer based on `distTotalUnit` byte:
- `"C"` → idle-clock layout (time prominent, weather code + temp in distance slots).
- `"*"` + `distNextUnit == "@"` → now-playing layout (track title across both distance fields).
- Otherwise → standard nav layout.

### Compose gotchas encountered today

- `Icons.Outlined.X` icons are package-level extension properties in `androidx.compose.material.icons.outlined.*`. Inline FQN like `androidx.compose.material.icons.Icons.Outlined.WbSunny` does not resolve — must use a package-level import.
- `AnimatedVisibility` enter/exit specs require `FiniteAnimationSpec`. `Motion.SpringStandard` is typed as `AnimationSpec<Float>` and cannot be passed directly — fallback to `tween(200)` inline at each use, documented per site.
- `animateDpAsState` needs `AnimationSpec<Dp>`, not `AnimationSpec<Float>` — use inline `spring<Dp>(dampingRatio=0.85f, stiffness=400f)` with matching physics.
- Kotlin block-comment parser is strict about nested `/**` — a literal `**` inside KDoc prose (e.g. `ui/home/**` in a path example) tries to open a nested block comment, blowing up the lexer at end-of-file with "Unclosed comment".
- Compose BOM `2024.12.01 → 2026.05.00` is a clean bump; `LocalLifecycleOwner` deprecation warnings appear but do not break the build.
- Arch's system Java moved to JDK 26 mid-day; Gradle 8.11.1's embedded Kotlin compiler throws `IllegalArgumentException` in `JavaVersion.parse` on `"26.0.1"`. Pinned `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk` in `android/gradle.properties`.
- Subagent `isolation: worktree` works only if the agent prompt explicitly says "cd to your worktree". Without that, the agent edits the main checkout and the worktree is wasted. When parallel agents touch the same file, the harness will not auto-merge — the worktree branch is preserved and the controller cherry-picks manually.

### Auto-start BikeBridgeService on app launch

`BikeBridgeService` now starts automatically on MainActivity launch (no manual tap required). A "Restart bike service" button is exposed in Settings → Developer for recovery without killing the app.

## 2026-05-25 (later) — Mappls maneuver-id → cluster icon table extracted from APK

### Discovery path

Found the resolution mechanism in `decompiled/jadx-out/sources/com/suzuki/adapter/C0897z.java:81`:

```java
context.getResources().getIdentifier("step_" + bVar.h, "drawable", context.getPackageName())
```

`bVar.h` is the integer maneuver-id from the Mappls SDK. The cluster renders exactly whatever `ic_step_N.xml` drawable the APK ships — no secondary mapping table exists. The complete authoritative table is therefore: enumerate all `ic_step_*.xml` files in `apk/base.apk`.

`unzip -l apk/base.apk | grep ic_step_` reveals 55 drawables: IDs **0–8, 10–25, 36–37, 40–41, 50–75**.

### Extraction method

1. Decoded binary AXML from `apk/base.apk` using `androguard 4.1.3` (`androguard.core.axml.AXMLPrinter`) — the 4.x API moved from `core.bytecodes.axml` to `core.axml`.
2. Converted Android vector XML to SVG by hand-mapping `android:pathData`, `android:fillColor`, `android:strokeColor`, `android:strokeWidth`, `android:strokeLineCap/Join` to their SVG equivalents.
3. Rendered to 128×128 PNG using `rsvg-convert` (librsvg) with `#222222` background.
4. Visually inspected all 55 PNGs and classified each icon.

### Key findings

**55 IDs decoded; 52 high confidence, 3 medium, 0 low.**

Full table in `docs/maneuver-id-table.md`.

### Corrections to prior `ManeuverMap.kt` assumptions

1. **`GENERIC_ARROW = 8` was wrong.** ID 8 is a hollow circle (GPS position marker / waypoint dot) — it has no directional component. The real straight-ahead arrow is **ID 7** (plain vertical up-arrow). `GENERIC_ARROW` updated to 7.

2. **U-turn was mapped to 23.** ID 23 is a "slight left with ramp tail" — a ramp or fork variant, not a U-turn. Correct U-turn icons: **6** (U-left) and **41** (U-right). Updated to 6.

3. **`"slight right" -> 7` was wrong.** ID 7 is the straight-ahead up-arrow. Slight-right is **ID 4** (lower-right diagonal hook). Fixed.

4. **`"slight left" -> 6` was wrong.** ID 6 is the U-turn-left loop. Slight-left is **ID 1** (lower-left diagonal hook). Fixed.

5. **`"keep left" -> 20`, `"keep right" -> 21` were wrong.** IDs 20 and 21 are merge-right-onto-highway and straight-with-crossbar respectively. Actual keep-left = **ID 11** (horizontal left arrow + vertical right bar); keep-right = **ID 12** (mirror). Fixed.

6. **`"arrive/destination" -> 50` was wrong.** IDs 50–57 are compass-mode departure icons ("head north/NE/east…"). There is no dedicated "destination flag" in the ic_step set. **ID 40** (waypoint circle) is the closest stop-point icon. Updated.

7. **`"merge" -> 11` was wrong.** ID 11 is keep-left. Merge icons are **19** (merge left) and **20** (merge right). Fixed.

8. **`"exit" -> 24/25` IDs were unrelated.** ID 24 is a wide-right U-curve and 25 is a wide-right fork tail — not highway exit icons. Real exit icons: **73/74** (dual-carriageway left exit), **75** (right exit). Updated.

### Surprising structural findings

- **IDs 36 and 37**: Ferry and tunnel icons — not navigation arrows at all. Useful for text like "take ferry" / "enter tunnel".
- **IDs 50–57**: Eight compass-direction departure icons (compass rose + directional arrow) — one per cardinal/ordinal direction. Good for Google Maps "Head north/east/…" text.
- **IDs 58–71**: Fourteen roundabout directional icons covering both CW and CCW rotation with 7 exit angles each. IDs 63 and 64 are byte-for-byte identical drawables.
- **IDs 73 and 74**: Also byte-for-byte identical (left motorway exit). Mappls likely uses both for subtly different scenarios but renders the same graphic.
- **ID 72**: Three-arrow roundabout symbol — best generic "roundabout" fallback when no exit-count is known.
- The icon set contains no dedicated "destination flag" or "finish" icon in the ic_step namespace.

---

## 2026-05-25 (later) — Mappls maneuver-id authoritative table + on-bike verification tooling

### What drove this

Ride 11 (the 2026-05-25 real ride session) exposed wrong arrows on the cluster at several turns. The symptom was that `ManeuverClassifier` was returning plausible-sounding icon IDs that visually disagreed with what the cluster showed at known maneuver types. That prompted a code reading of `nav/ManeuverClassifier.kt` and `nav/ManeuverMap.kt` in GixxerBridge.

### Five failure modes found in ManeuverClassifier

1. **Self-training pollution** — the classifier was always updating its bitmap-hash → text-id mappings from every Google Maps notification it parsed, including low-confidence hits. A bad frame would permanently corrupt future lookups.
2. **Hamming tolerance of 5/64 bits** — too loose for a 64-bit perceptual hash; semantically different icons are close enough to collide.
3. **Unverified ID semantics** — `ManeuverMap.kt`'s `fromText()` mappings were hand-guessed, not derived from the APK. The comments said `ASSUMED: verify on cluster` throughout.
4. **Missing-bitmap fallback** — when no bitmap was present in the notification, the classifier defaulted to `GENERIC_ARROW` (which was itself wrong — see corrections below).
5. **No per-decision logging** — when the cluster showed the wrong arrow there was no trace of which classifier path fired or what inputs it received.

### Three-part fix (commit `b52f0f5`)

- **Instrumentation**: per-decision `AppLog.d` trace in `ManeuverClassifier` (path taken, bitmap hash, text match, final ID) and per-notification `AppLog.d` in `GoogleMapsParser` (raw extras dump).
- **Self-train default OFF**: new `Settings.maneuverSelfTrainEnabled` pref, default `false`. Self-training is now opt-in. Exposed as a toggle in Settings → Developer.
- **Disagreement WARN**: when the bitmap-hash path and the text-fallback path return different non-default IDs, `AppLog.w` is emitted so conflicts are visible in the Diagnostics screen.

### Closing the ID-semantics gap: the APK as ground truth (commit `bb768ab`)

The resolution mechanism in the Suzuki app is in `decompiled/jadx-out/sources/com/suzuki/adapter/C0897z.java:81`:

```java
context.getResources().getIdentifier("step_" + bVar.h, "drawable", context.getPackageName())
```

`bVar.h` is the raw Mappls maneuver integer. The cluster renders exactly whatever `ic_step_N.xml` ships in the APK — no secondary lookup table. The complete authoritative table is therefore: enumerate every `ic_step_*.xml` in `apk/base.apk`.

Extraction pipeline: `unzip -l` enumeration → 55 drawables (IDs 0–8, 10–25, 36–37, 40–41, 50–75) → decoded binary AXML via `androguard 4.1.3` (`androguard.core.axml.AXMLPrinter`) → hand-mapped Android vector attributes to SVG → rendered 128×128 PNGs via `rsvg-convert` (librsvg) → visually classified all 55. Rendered PNGs landed at `/tmp/step-icons/png/` (not in repo; pipeline is re-runnable). Full table in `docs/maneuver-id-table.md`.

### Major corrections to prior ManeuverMap.kt assumptions

The old code had been sending wrong icons. Confirmed errors (see commit `bb768ab` for the full list; highlights below):

- **`GENERIC_ARROW` was 8** — ID 8 is a hollow circle (GPS position dot), not a directional arrow. Corrected to **7** (plain up-arrow).
- **U-turn was 23** — ID 23 is a ramp/fork variant. Corrected to **6** (U-left) / **41** (U-right).
- **Slight-left was 6, slight-right was 7** — both wrong (6 = U-turn-left loop, 7 = straight up-arrow). Corrected to **1** (slight-left diagonal) and **4** (slight-right diagonal).
- **Keep-left was 20, keep-right was 21** — IDs 20/21 are merge-right and straight-with-crossbar. Corrected to **11** (keep-left) and **12** (keep-right).
- **Arrive/destination was 50** — IDs 50–57 are compass-direction departure icons ("head north/NE/east…"). No dedicated finish icon exists in the ic_step set. Best proxy: **40** (waypoint circle).
- **Roundabout generic was 71** — corrected to **72** (three-arrow roundabout symbol). 71 is a specific roundabout-exit angle.

Every `ASSUMED: verify on cluster` comment in `ManeuverMap.kt` was replaced with a source-derived mapping. `fromText()` updated in the same commit.

All 55 `ic_step_N.xml` vector drawables were also copied to `android/app/src/main/res/drawable/` (commit `c2a08e6`) so GixxerBridge can render the expected icon phone-side using the same `getIdentifier("ic_step_$id", …)` pattern as `C0897z.java`. One aapt2 gotcha: numeric `strokeLineCap`/`strokeLineJoin` values in the binary AXML (`1`, `2`, `0`) had to be sed-replaced to string enums before shipping (`1 → round`, `2 → square`/`bevel`, `0 → butt`/`miter`). No visual content change vs APK originals.

### Maneuver Sweep dev tool (commit `a93f82e`)

`ui/dev/ManeuverSweepScreen.kt` lists all 55 valid maneuver IDs. Each row shows the expected `ic_step_N` drawable (via dynamic `getIdentifier`) and a Send button. Tapping Send ships an a531 NavFrame with that ID as `bytes[2]` and dummy distance/ETA/total fields. Wired into Settings → Developer.

Purpose: empirically verify that the bike cluster's firmware renders the same icons as the APK's `ic_step_N` set. This is a high-confidence proxy (both ship from the same APK; the `C0897z.java` call and `A0.D()` both use the raw ID directly), but the firmware's own icon table has not been independently confirmed. The Sweep tool gives a one-shot visual verification without needing a full ride.

### Weather Sweep dev tool (commit `fe62b6f`)

`ui/dev/WeatherSweepScreen.kt` lists all 12 `SuzukiWeather` codes (0=UNKNOWN through 11=WINDY). Each row has a Send button that ships an a533 HeartbeatFrame with the chosen weather byte in slot 21 and a fixed 25 °C temperature in slot 22.

**Unlike the Maneuver Sweep, there are no phone-side previews.** The Suzuki APK does not ship cluster weather drawables — only three large in-app PNGs (`weather_sunny.png`, `weather_partially_cloudy.png`, `weather_rainy.png`) used by the phone's own dashboard UI, keyed by matching Mappls weather text strings, not by the 0–11 cluster byte. The cluster's 12 weather icons are firmware-baked and not recoverable from the APK.

Caveat for on-bike use: the regular 1 Hz heartbeat loop overwrites byte 21 almost immediately. Rider needs to shoot fast or pause Maps before sending a Weather Sweep frame.

## 2026-05-25 — Mappls maneuver ID ≠ Suzuki cluster byte

**Wrong assumption:** The Mappls SDK's maneuver ID (the integer that drives
the phone-side nav-strip widget) is the same integer that the bike's cluster
ROM uses to look up its turn-arrow glyph.

**Reality:** They are different integers. The OEM Suzuki Connect app runs a
hand-rolled translation in `A0.C()` (`com.suzuki.application.fragment.A0`)
that maps Mappls IDs (0..75) to a separate cluster-byte space (1..52). The
cluster has its own icon ROM indexed by the translated byte; the
`ic_step_N.xml` drawables in `apk/base.apk` are *not* what the cluster shows
— those are phone-side widgets rendered inside the Suzuki Connect UI.

**Symptom that surfaced the bug:** During the 2026-05-25 18:48 ride with
GixxerBridge driving the BLE, every turn arrow on the cluster pointed the
wrong way. Separately, the in-app `ManeuverSweep` dev tool sent NavFrames
(confirmed via writer log `BikeBridge: writer: TX composer type=0x31`) but
the cluster never changed across the swept IDs.

**Evidence that established the truth:** Decompiled the OEM app with
`jadx --no-imports --use-dx --deobf` (jadx-retry output). Read
`decompiled/jadx-retry/sources/com/suzuki/application/fragment/A0.java:458`
(`public final void C(com.mappls.sdk.navigation.model.a aVar)`) — a 200-line
if-chain that switches on `aVar.f` (the Mappls maneuver ID per the
`model.a.toString()` label `maneuverID=`) and assigns `this.e0` (the byte
that ends up in NavFrame byte 2 via `A0.D(int i, ...)` at A0.java:455).

**Resolution:** See spec
`docs/superpowers/specs/2026-05-25-maneuver-id-rework-design.md` and the
implementation plan
`docs/superpowers/plans/2026-05-25-maneuver-id-rework.md`. Stage 2
translation added at `ManeuverMap.mapplsIdToClusterByte`; pipeline rewired
to apply it at the `GoogleMapsParser → ParsedNavData` boundary so everything
downstream is cluster bytes. The `docs/maneuver-id-table.md` file is renamed
to `docs/mappls-id-icons.md` to reflect what it actually describes.

**Lesson:** Wireshark labels mislabeled UUIDs in M0. APK drawables mislabeled
themselves as cluster icons in M5. In both cases, the No-Assumptions rule
applies: tool-derived labels are heuristic until you trace them to source.
"`ic_step_N.xml` exists therefore the cluster renders it" was an inference,
not an observation.

---

## 2026-06-16 — BLE fuel-economy field (`fuelEconKmlV2`) over-reads ~30%

**Observation (ground truth from fuel fills):** Two logged fills — odo 16891 @ 9.49 L,
then odo 17271 @ 7.73 L — give a tank mileage of (17271−16891)/7.73 = **49.2 km/L**.
The rider also reports the bike's own cluster average for the ~670 km round trip was
**~53 km/L**.

**What the app's BLE field shows:** `RideSampleEntity.fuelEconKml` (persisted from
`TelemetryFrame.fuelEconKmlV2`, i.e. byte 25 / 2) averages **64–65 km/L** across both
the Sunday (id 98) and Saturday (id 99) merged journeys — arithmetic mean 64.3, median
65.5, and a distance-weighted (Σspeed / Σ(speed/econ)) figure of 65.0. So the high value
is **not** a coasting/instantaneous-spike artifact (median ≈ mean ≈ distance-weighted);
the field itself reads consistently ~30% high versus reality.

**Conclusion:** `fuelEconKmlV2` is not a trustworthy absolute mileage on this bike — it
over-reads ~30% (≈64 vs fill-measured ≈49 / cluster ≈53). The earlier code comment
("byte 25 / 2 … median ~48 km/L observed, realistic") does not hold for these rides.

**Applied:** The trip-detail "Mileage" stat now uses the fill-measured km/L
(`MileageAnalytics.averageKmPerL`), not the BLE econ field. The BLE econ is still kept
for trend display (clearly labelled as bike-reported), never as an absolute figure.

**To verify (future):** which cluster byte carries the *displayed* trip-average mileage
(the ~53 figure). byte 25/2 ≈ 64 is close-but-high; candidates: a different scale on
byte 25, or another byte entirely. Capture cluster avg-mileage readout alongside an HCI
snoop to pin the exact byte + formula.
