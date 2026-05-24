#!/usr/bin/env python3
"""
dump_maps_notification.py — pull active Google Maps notifications from the
phone via `adb shell dumpsys notification --noredact`, parse, and emit a
Markdown report. Validates assumption A1 (see assumptions-log.md): does Maps
expose nav text in extras, or only in a RemoteViews tree?

Visible via dumpsys --noredact:
  - extras={...} keys + scalar values (android.title / .text / .subText /
    .contains.customView, etc.)
  - whether contentView / bigContentView / headsUpContentView are non-null
    RemoteViews(...) references
  - actions={[i] "Label" -> PendingIntent{...}}
  - pkg, post time (`when=`), channel, importance, icon resource id

NOT visible via dumpsys (needs an on-device APK or Frida into system_server):
  - the inflated RemoteViews tree — TextView entry names / current text,
    ImageView drawable bitmaps. dumpsys prints only `RemoteViews(...)` or
    `null`. Walking the tree is the GixxerBridge APK's job; this tool just
    confirms whether a RemoteViews exists and what extras shape Maps uses.

Usage:
    python tools/dump_maps_notification.py [--watch] [--save-raw] [-s <serial>]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

MAPS_PKG = "com.google.android.apps.maps"
DEFAULT_OUT_DIR = Path(__file__).resolve().parent.parent / "captures"

# Regexes hugged tightly to the dumpsys --noredact format observed on
# LineageOS Android 16 / Redmi K20 Pro (see captures/maps-notification-dump-*).
_REC_RE = re.compile(r"^    NotificationRecord\([^)]*: pkg=(?P<pkg>\S+) ")
_KV_RE = re.compile(r"^\s+(?P<key>[A-Za-z][\w.]*)=(?P<val>.*)$")
_EXTRA_RE = re.compile(r"^\s+(?P<key>[\w.]+)=(?P<val>.*)$")
_ACTION_RE = re.compile(r'^\s+\[(?P<i>\d+)\]\s+"(?P<label>.*?)"\s*->\s*(?P<intent>.*)$')


@dataclass
class NotifRecord:
    pkg: str
    block_start: int  # line idx in the raw dump (for cross-ref)
    raw_lines: list[str] = field(default_factory=list)
    fields: dict[str, str] = field(default_factory=dict)   # top-level n=v
    extras: dict[str, str] = field(default_factory=dict)   # extras={...}
    actions: list[tuple[str, str]] = field(default_factory=list)


def run_dumpsys(device: str | None) -> str:
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    cmd += ["shell", "dumpsys", "notification", "--noredact"]
    res = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if res.returncode != 0:
        sys.stderr.write(f"adb failed (rc={res.returncode}): {res.stderr}\n")
        sys.exit(2)
    return res.stdout


def parse_dump(blob: str) -> list[NotifRecord]:
    """Split the dump into per-record blocks and extract structure."""
    lines = blob.splitlines()
    recs: list[NotifRecord] = []
    cur: NotifRecord | None = None
    in_extras = False
    in_actions = False
    for idx, line in enumerate(lines):
        m = _REC_RE.match(line)
        if m:
            cur = NotifRecord(pkg=m["pkg"], block_start=idx)
            recs.append(cur)
            in_extras = in_actions = False
            cur.raw_lines.append(line)
            continue
        if cur is None:
            continue
        # End-of-block heuristic: next record or unindented section.
        if line and not line.startswith("    "):
            cur = None
            continue
        cur.raw_lines.append(line)

        # extras={...} multi-line block
        stripped = line.strip()
        if stripped.startswith("extras={"):
            in_extras = True
            continue
        if in_extras:
            if stripped == "}":
                in_extras = False
                continue
            em = _EXTRA_RE.match(line)
            if em:
                cur.extras[em["key"]] = em["val"].strip()
            continue

        # actions={ [0] "Label" -> PendingIntent{...} ... }
        if stripped.startswith("actions={"):
            in_actions = True
            continue
        if in_actions:
            if stripped == "}":
                in_actions = False
                continue
            am = _ACTION_RE.match(line)
            if am:
                cur.actions.append((am["label"], am["intent"].strip()))
            continue

        # Generic key=value (color, bigContentView, contentView, when, ...)
        kv = _KV_RE.match(line)
        if kv:
            cur.fields.setdefault(kv["key"], kv["val"].strip())
    return recs


def fmt_when(raw: str) -> str:
    """`when=1779644332722/1779644332722` → ISO timestamp."""
    if not raw:
        return "(missing)"
    head = raw.split("/", 1)[0]
    try:
        ts = int(head)
        return f"{raw}  ({datetime.fromtimestamp(ts / 1000).isoformat()})"
    except ValueError:
        return raw


def render_record_md(rec: NotifRecord) -> str:
    lines = [f"## {rec.pkg}", ""]
    lines.append(f"- **post time (`when`)**: {fmt_when(rec.fields.get('when', ''))}")
    for key in ("contentView", "bigContentView", "headsUpContentView"):
        val = rec.fields.get(key, "(absent)")
        marker = " **<-- RemoteViews present**" if val.startswith("RemoteViews(") else ""
        lines.append(f"- **{key}**: `{val}`{marker}")
    if "tickerText" in rec.fields:
        lines.append(f"- **tickerText**: `{rec.fields['tickerText']}`")
    lines += ["", "### extras", ""]
    if not rec.extras:
        lines.append("_(no extras parsed)_")
    else:
        for k, v in rec.extras.items():
            lines.append(f"- `{k}` = {v}")
    if rec.actions:
        lines += ["", "### actions", ""]
        for i, (label, intent) in enumerate(rec.actions):
            lines.append(f"- `[{i}]` **{label}** → {intent}")
    return "\n".join(lines) + "\n"


def render_report(recs: list[NotifRecord], dump_ts: str, raw_path: Path | None) -> str:
    maps = [r for r in recs if r.pkg == MAPS_PKG]
    others = [r for r in recs if r.pkg != MAPS_PKG]
    out = [
        f"# Maps notification dump — {dump_ts}",
        "",
        f"- total active notifications parsed: **{len(recs)}**",
        f"- Maps (`{MAPS_PKG}`) records: **{len(maps)}**",
    ]
    if raw_path:
        out.append(f"- raw dump archived at: `{raw_path}`")
    out += ["", "## A1 verdict", ""]
    if not maps:
        out += [
            "**No Google Maps notification is currently active.** Either Maps is",
            "not installed, not running, or not navigating. A1 cannot be confirmed",
            "or refuted from this snapshot — re-run with `--watch` after starting",
            "a Maps navigation to capture the live turn.",
            "",
            "The remaining sections list every OTHER active notification's extras",
            "shape so we can sanity-check the parser against ground truth.",
        ]
    else:
        text_keys = {"android.title", "android.text", "android.subText", "android.bigText"}
        for r in maps:
            present = sorted(text_keys & set(r.extras))
            uses_remote = any(
                r.fields.get(k, "").startswith("RemoteViews(")
                for k in ("contentView", "bigContentView", "headsUpContentView")
            )
            cust = r.extras.get("android.contains.customView", "(absent)")
            out += [
                f"- Maps record `{r.pkg}` @ line {r.block_start}:",
                f"  - parseable text keys present: `{present or 'NONE'}`",
                f"  - any RemoteViews on the notification: **{uses_remote}**",
                f"  - `android.contains.customView` = `{cust}`",
            ]
        out += [
            "",
            "If `contains.customView=true` and every text key is missing/empty,",
            "A1's refutation stands and the GixxerBridge APK must inflate the",
            "RemoteViews tree (see assumptions-log.md A1 'New approach').",
        ]
    out += ["", "## Google Maps records", ""]
    out += [render_record_md(r) for r in maps] if maps else ["_(none)_\n"]
    out += ["", "## Other active notifications (compact)", ""]
    if not others:
        out.append("_(none)_")
    else:
        for r in others:
            keys = ", ".join(f"`{k}`" for k in r.extras) or "_(none)_"
            uses_rv = any(
                r.fields.get(k, "").startswith("RemoteViews(")
                for k in ("contentView", "bigContentView", "headsUpContentView")
            )
            tag = " [RemoteViews]" if uses_rv else ""
            out.append(f"- **{r.pkg}**{tag} — extras keys: {keys}")
    return "\n".join(out) + "\n"


def print_summary(recs: list[NotifRecord], report_path: Path) -> None:
    maps = [r for r in recs if r.pkg == MAPS_PKG]
    print(f"[dump] {len(recs)} active notifications; {len(maps)} from Maps")
    if not maps:
        print("[dump] Maps is not currently posting a notification.")
    for r in maps:
        title = r.extras.get("android.title", "(no title)")
        text = r.extras.get("android.text", "(no text)")
        sub = r.extras.get("android.subText", "(no subText)")
        uses_rv = any(
            r.fields.get(k, "").startswith("RemoteViews(")
            for k in ("contentView", "bigContentView", "headsUpContentView")
        )
        print(f"  - title:   {title}")
        print(f"  - text:    {text}")
        print(f"  - subText: {sub}")
        print(f"  - uses RemoteViews: {uses_rv}")
    print(f"[dump] wrote report -> {report_path}")


def one_shot(args: argparse.Namespace) -> Path:
    blob = run_dumpsys(args.device)
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    args.out_dir.mkdir(parents=True, exist_ok=True)
    raw_path: Path | None = None
    if args.save_raw:
        p = args.out_dir / f"notif-dump-{ts}.txt"
        p.write_text(blob)
        raw_path = p
    recs = parse_dump(blob)
    report = render_report(recs, ts, raw_path)
    report_path = args.out_dir / f"maps-notification-dump-{ts}.md"
    report_path.write_text(report)
    print_summary(recs, report_path)
    return report_path


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--device", "-s", help="adb device serial (passed as -s)")
    p.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR, help=f"report dest (default {DEFAULT_OUT_DIR})")
    p.add_argument("--watch", action="store_true", help="re-dump on a loop until ^C")
    p.add_argument("--watch-interval", type=float, default=2.0, help="seconds between watch dumps")
    p.add_argument("--save-raw", action="store_true", help="also save raw dumpsys blob alongside report")
    args = p.parse_args()
    try:
        if args.watch:
            print(f"[watch] every {args.watch_interval}s; ^C to stop")
            while True:
                one_shot(args)
                time.sleep(args.watch_interval)
        else:
            one_shot(args)
    except KeyboardInterrupt:
        print("\n[watch] stopped")


if __name__ == "__main__":
    main()
