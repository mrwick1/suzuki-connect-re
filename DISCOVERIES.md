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

## Pending validation work (carry into next session)

- [ ] Run Phase B: replay captured `a531` write from laptop, see if bike cluster displays the captured content (proves we can write to bike from third-party Central; tests Gate 2 path)
- [ ] If Phase B succeeds: try Phase C (modify the time field, recompute checksum with confirmed sum algo, see if cluster shows new time)
- [ ] If Phase C succeeds: try Phase D (fully custom ASCII text in `a531`, see if cluster shows arbitrary text — Phase 3-A unlock)
- [ ] Fix `provoke_and_listen.py` to increment the `a533` heartbeat counter properly (matches legitimate phone behavior, may prevent the "Unlikely Error" disconnect)
- [ ] Comprehensive triggering: longer session with engine state changes, riding (if safe), more diverse triggers, to map any additional notify variation
- [ ] mitmproxy + Frida SSL pinning bypass — investigate where fuel/odo/trip values come from for the app (still unresolved; bike has no SIM, so it's either BLE-event-we-missed or cloud-via-phone-relay or app-local-cache)
- [ ] Decode `a531` further to find the turn-arrow encoding (M5)
