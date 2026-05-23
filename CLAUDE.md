# CLAUDE.md — Suzuki Connect RE Project

## Project context

Reverse-engineering the BLE protocol on Arjun's 2023 Suzuki Gixxer SF 150 motorcycle, for educational purposes. Three planned phases:

- **Phase 1**: Understand the protocol (in progress; see `docs/superpowers/specs/2026-05-23-phase1-protocol-understanding-design.md`)
- **Phase 2**: Replace Mappls with Google Maps via a notification-listener Android app
- **Phase 3**: Custom cluster display + (possibly) telemetry dashboard

Living spec: `NOTES.md`. Discoveries log: `DISCOVERIES.md`.

## NON-NEGOTIABLE RULE: No assumptions

Do **not** assume any technical fact in this project without verification. This rule was added after two real mistakes in M0:

1. I claimed Wireshark's "PKOC/Aliro/ICCE Digital Key" labels meant Suzuki used a digital-key crypto spec. **Wrong** — actual UUIDs were simple `0xFFFx` vendor UUIDs; Wireshark mislabeled.
2. I claimed the bike has an embedded SIM/cellular TCU so fuel/odo/trip data must come from Suzuki's cloud. **Wrong** — Arjun confirmed no physical SIM exists, and Suzuki's own product page describes connectivity as Bluetooth-only with the mobile app.

Both mistakes propagated into NOTES.md as "facts" before being caught. This rule exists to prevent the next one.

### What this means in practice

- **State observed facts, not hypotheses, as facts.** If you didn't measure / read / capture it, it's not a fact.
- **Mark hypotheses explicitly** with "hypothesis:" or "to verify:" prefixes. Never silently let one become a stated truth.
- **When you assume something to make progress, log the assumption** in `DISCOVERIES.md` so it can be checked later. Better: don't assume — verify first.
- **Web/training-data claims about specific hardware are unverified.** What "most automotive systems do" or "what BLE typically uses" is a guess about this bike until proven.
- **Wireshark / tool labels are heuristic.** Cross-verify with the actual data (e.g., GATT walk to get real UUIDs, not dissector labels).
- **Absence of evidence is not evidence of absence.** If you don't see X in one channel, that doesn't mean X doesn't exist somewhere. Check all channels before concluding.
- **Sample analysis is not full analysis.** If asked to "analyze the data," dump and process every record. State explicitly when you're sampling, and what the sample misses.
- **When wrong, document the mistake in `DISCOVERIES.md`** with what was assumed, what was actually true, and what evidence proved it. Don't silently overwrite the spec.

### How to write under this rule

Bad: "The bike uses AES-CBC for encryption." (unverified)
Good: "Captured payloads on `0xFFF1` show ASCII content with no apparent encryption (see DISCOVERIES.md 2026-05-23). Hypothesis: no encryption layer. To verify in M4 by cross-referencing Frida-hooked plaintext with HCI-captured wire bytes."

Bad: "Phase 3 Branch B is dead because the bike doesn't push telemetry over BLE."
Good: "In the M0 capture across 5 connection events / 7300 ATT packets, no telemetry data appears on either write or notify channels (full opcode + per-byte analysis in DISCOVERIES.md). This is necessary but not sufficient evidence that BLE never carries telemetry — other lifecycle events (engine stop, trip end, etc.) could push different message types. To verify by triggering each lifecycle event with capture running."

## Working style

- Interactive sessions with Arjun. He's at the laptop and often at the bike or with the phone.
- Be direct, no sugarcoating (see global `~/.claude/CLAUDE.md` for general Arjun preferences).
- Document everything — `NOTES.md` for current state, `DISCOVERIES.md` for chronological journey including wrong assumptions and corrections.
- Commit per milestone or per meaningful discovery, not weekly (this is unlike the `~/my-life` repo's weekly cadence).
- Personal git identity (`personal` alias) — not work.

## File map

- `NOTES.md` — living protocol spec (current best understanding)
- `DISCOVERIES.md` — chronological log including wrong assumptions and corrections
- `LOCAL_NOTES.md` — PII values (bike MAC, serial); gitignored
- `tools/` — Python helpers (GATT walker, encoders, etc.)
- `frida-scripts/` — runtime hooks for the Suzuki Connect app
- `tests/` — pytest suite for protocol encoders/decoders
- `apk/`, `decompiled/`, `captures/` — gitignored
- `docs/superpowers/specs/` — design spec
- `docs/superpowers/plans/` — implementation plan

## Key commands

```bash
# Activate Python venv
source ~/coding/projects/suzuki-connect-re/.venv/bin/activate

# Pull HCI snoop log (needs adb root, enabled in LineageOS dev options)
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log captures/<name>.pcap

# Start frida-server on phone (from Termux on phone, since adb shell can't reach KSU su)
su -c "/data/local/tmp/frida-server &"

# Verify Frida connection from laptop
frida-ps -U
```
