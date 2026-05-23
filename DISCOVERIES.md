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

## Pending validation work (carry into next session)

- [ ] Verify "bike cluster is dumb display" by sending custom text in `a531` and observing cluster (deferred until Gate 2 / proof-of-life works — needs checksum solved first)
- [ ] Capture an app session that exercises trip details / fuel / last-location screens, look for new BLE message types (Hypothesis B for app data source)
- [ ] mitmproxy + Frida SSL pinning bypass to verify Hypothesis A (cloud API is the source)
- [ ] Decode `a531` further to find the turn-arrow encoding
- [ ] Solve the checksum mystery on `a536`
