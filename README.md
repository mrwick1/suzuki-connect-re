# Suzuki Connect RE

Personal educational reverse-engineering of the Suzuki Connect BLE protocol on a 2023 Suzuki Gixxer SF 150. Goal: understand the protocol, build a Google-Maps-powered replacement app, explore the cluster display's full capabilities.

## Status

**Current phase:** Phase 1 — Protocol Understanding
**Current milestone:** M0 — Setup
**See:** `docs/superpowers/plans/2026-05-23-phase1-protocol-understanding.md` for the active plan.
**See:** `NOTES.md` for the living protocol spec (the actual deliverable).

## Layout

- `NOTES.md` — protocol spec, grown across milestones
- `frida-scripts/` — runtime hooks for the Suzuki Connect app
- `tools/` — Python helpers (GATT walker, encoder, decoder, etc.)
- `tests/` — pytest suite for encoder/decoder
- `docs/` — primer, design spec, implementation plan
- `apk/`, `decompiled/`, `captures/` — gitignored, may contain PII / copyrighted material

## Setup checklist

- [ ] Laptop tooling installed (`tools/setup-laptop.sh`)
- [ ] Spare Android with LineageOS + KSU root
- [ ] `frida-server` running on phone, `frida-ps -U` works from laptop
- [ ] Suzuki Connect app installed on spare phone, logged in, paired with bike
- [ ] HCI snoop log path on LineageOS verified
