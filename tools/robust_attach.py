"""
robust_attach.py — long-running Frida attach wrapper that survives the
Suzuki Connect app being killed and respawned during a motorcycle ride.

================================================================================
                              README (read me first)
================================================================================

THE PROBLEM
-----------
test_attach.py does the naive thing: device.attach(name) -> create_script ->
load -> sleep. If the target process dies (Android OOM, app backgrounding,
battery saver), the script dies with it and we never re-inject. Real symptom
from 2026-05-24 ride: ~113 s of Frida log out of a 17-min ride.

THE FIX
-------
This wrapper:
  1. Discovers the running PID of `suzuki.com.suzuki` (does NOT spawn — we
     don't want to reset Arjun's existing BLE link to the bike).
  2. Attaches to it and injects the agent.
  3. Enables spawn-gating + subscribes to spawn-added + child-added events
     so respawns are re-attached automatically.
  4. Runs a periodic poll (every POLL_SECS) as a safety net for events the
     spawn-gating layer might miss (some Android forks don't fire spawn-added
     cleanly for backgrounded restart paths).
  5. Watches existing sessions for `detached` events and re-discovers/re-attaches.
  6. Writes its OWN log to a separate phone-local file so post-mortem analysis
     can correlate watcher actions (re-attach, errors) with agent telemetry.

The agent script itself is unchanged — it already writes its events to
/data/user/0/suzuki.com.suzuki/files/suzuki-ride-<ts>.jsonl (see the
PrintWriter setup in ride_capture.js). hook_error events from the new
ProGuard build (e.g. C0855q0 drift) will appear in the agent log and are
non-fatal — we forward them to the watcher log so they're easy to spot.


HOW TO INVOKE
-------------

PRIMARY MODE — run on the phone in Termux (recommended for actual rides):

    # one-time Termux setup (in Termux shell, not adb shell — Termux needs
    # its own Python env; adb shell can't access KSU su for frida-server):
    pkg install python rust libffi openssl   # rust is needed to build frida wheel
    pip install --upgrade pip
    pip install frida frida-tools             # ~5 min on phone, builds from sdist

    # copy the agent + this wrapper onto the phone (one option among many):
    #   adb push frida-scripts/ride_capture.compiled.js /sdcard/Download/
    #   adb push tools/robust_attach.py                  /sdcard/Download/
    # then in Termux:
    cp /sdcard/Download/ride_capture.compiled.js ~/
    cp /sdcard/Download/robust_attach.py         ~/

    # make sure frida-server is running on the phone:
    su -c "/data/local/tmp/frida-server &"

    # then run the watcher (locally on the phone, NOT via USB):
    python ~/robust_attach.py --script ~/ride_capture.compiled.js --local

    # this stays running across the whole ride. USB stays disconnected.

SECONDARY MODE — run on the laptop with USB (use this for verify-before-ride):

    source .venv/bin/activate
    python tools/robust_attach.py --script frida-scripts/ride_capture.compiled.js

    # USB must stay connected the entire time. Will try to reconnect on brief
    # transport drops. NOT recommended for actual rides — USB will disconnect
    # the moment the phone leaves the laptop.

CLI ARGS
--------
  --script PATH       Path to the (compiled) Frida agent JS file. Required.
  --local             Use frida.get_local_device() instead of get_usb_device().
                      Set this when running inside Termux on the phone.
  --target NAME       Process name to watch. Default: "Suzuki Ride Connect"
                      (matches device.attach() — package id "suzuki.com.suzuki"
                      also works).
  --poll-secs N       How often to re-check the target PID. Default: 5.
  --watcher-log PATH  Watcher's own log file. Default:
                      /data/local/tmp/robust_attach-<ts>.jsonl  (Termux mode)
                      ./robust_attach-<ts>.jsonl                  (laptop mode)
  --quiet             Don't echo agent send events to stdout (still logged).


EXPECTED OUTPUT
---------------

Watcher log (one JSON line per event, type field tells you what happened):
  {"t":0.0,                "type":"watcher_start","target":"Suzuki Ride Connect",...}
  {"t":0.12,"pid":12345,   "type":"attach_ok"}
  {"t":0.13,"pid":12345,   "type":"script_loaded"}
  {"t":0.15,"pid":12345,   "type":"agent_event","payload":{"type":"attach",...}}
  ...
  {"t":234.5,"pid":12345,  "type":"session_detached","reason":"process-terminated"}
  {"t":234.5,               "type":"poll_no_target"}
  {"t":239.5,"pid":12999,  "type":"attach_ok"}   # respawn caught
  {"t":239.6,"pid":12999,  "type":"script_loaded"}
  ...

Agent log: untouched, lives on the phone at
  /data/user/0/suzuki.com.suzuki/files/suzuki-ride-<ts>.jsonl
A new agent log is created EACH TIME we re-inject (because ride_capture.js
opens a fresh ts-named file inside Java.perform). After a ride you'll
typically have N agent logs (one per process incarnation) + 1 watcher log.

Console output (when not --quiet):
  Real-time stream of agent send events, plus watcher transitions like
  "[+] Attached to PID 12345" / "[!] Session detached: process-terminated".


HOW TO VERIFY IT'S WORKING BEFORE A RIDE
-----------------------------------------

  1. Plug phone in. Open the Suzuki app. Make sure BLE link is up
     (cluster shows the phone icon).
  2. From laptop, run:
       python tools/robust_attach.py --script frida-scripts/ride_capture.compiled.js
     You should see within ~2 s:
       [+] Watcher started, watching 'Suzuki Ride Connect'
       [+] Attached to PID <N>
       [+] Script loaded
       [agent] {"t":..,"type":"attach",...}
       [agent] {"t":..,"type":"hooks_installed",...}
  3. With the watcher still running, FORCE-KILL the app from Android
     recents (swipe up). Within a few seconds:
       [!] Session detached: process-terminated
       [.] Polling — target not running
  4. Reopen the Suzuki app. Within ~5-10 s the watcher should print:
       [+] Attached to PID <new N>
       [+] Script loaded
       [agent] {"t":..,"type":"attach",...}
     If you see step 4, the respawn loop works. Ctrl-C, then for the real
     ride run the Termux variant.


POST-RIDE RECOVERY
------------------

  # pull watcher log from phone (Termux mode):
  adb pull /data/local/tmp/robust_attach-<ts>.jsonl captures/

  # pull every agent log (one per process incarnation during the ride):
  adb root  # needed; KSU should already grant
  adb pull /data/user/0/suzuki.com.suzuki/files/   captures/agent-logs/

  # pull HCI snoop log (ground-truth wire bytes):
  adb pull /data/misc/bluetooth/logs/btsnoop_hci.log captures/

  # then cross-reference: each agent log's "attach" event ts_unix_ms anchors
  # its relative timestamps to wall clock; the watcher log's "attach_ok"
  # events give you the PID timeline.


CONSTRAINTS / GOTCHAS
---------------------
- runtime="v8" is required because frida-java-bridge is bundled separately
  via frida-compile. Default QuickJS won't auto-load the Java global.
- We do NOT spawn the target. Spawn-gating + respawn-watching only.
- The "C0855q0 class not found" issue from the newer ProGuard build is a
  ride_capture.js concern — this wrapper just forwards hook_error events
  to the watcher log so you can see drift after the fact.
- Termux mode does not need USB. Laptop mode needs USB the whole time.
- frida 17+, Python 3.10+. Both already in the project venv.
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import frida

# -----------------------------------------------------------------------------
# Config
# -----------------------------------------------------------------------------

DEFAULT_TARGET = "Suzuki Ride Connect"  # matches device.attach() by name
DEFAULT_POLL_SECS = 5.0
RECONNECT_BACKOFF_SECS = 2.0
MAX_RECONNECT_BACKOFF_SECS = 30.0


# -----------------------------------------------------------------------------
# Watcher state
# -----------------------------------------------------------------------------


@dataclass
class WatcherState:
    """Shared mutable state between the main loop and Frida callbacks."""

    target: str
    script_source: str
    watcher_log_path: Path
    quiet: bool

    device: Optional[frida.core.Device] = None
    session: Optional[frida.core.Session] = None
    script: Optional[frida.core.Script] = None
    current_pid: Optional[int] = None

    log_fp = None  # file handle
    log_lock: threading.Lock = field(default_factory=threading.Lock)
    t0: float = field(default_factory=time.time)
    stop_event: threading.Event = field(default_factory=threading.Event)


# -----------------------------------------------------------------------------
# Logging
# -----------------------------------------------------------------------------


def log_event(state: WatcherState, event_type: str, **payload) -> None:
    """Append one JSON line to the watcher log file AND echo a brief line to stdout."""
    record = {
        "t": round(time.time() - state.t0, 3),
        "type": event_type,
        **payload,
    }
    if state.current_pid is not None and "pid" not in payload:
        record["pid"] = state.current_pid
    line = json.dumps(record, default=str)
    with state.log_lock:
        if state.log_fp is not None:
            try:
                state.log_fp.write(line + "\n")
                state.log_fp.flush()
            except Exception as e:
                # Last-ditch: print to stderr so we know writes broke.
                print(f"[!] watcher-log write failed: {e}", file=sys.stderr)
    # Console echo — keep it short.
    if event_type == "agent_event":
        if not state.quiet:
            print(f"[agent] {json.dumps(payload.get('payload'), default=str)}")
    elif event_type == "agent_error":
        print(f"[agent-error] {payload.get('description')}")
    elif event_type == "attach_ok":
        print(f"[+] Attached to PID {payload.get('pid')}")
    elif event_type == "script_loaded":
        print(f"[+] Script loaded")
    elif event_type == "session_detached":
        print(f"[!] Session detached: {payload.get('reason')}")
    elif event_type == "poll_no_target":
        print(f"[.] Polling — target not running")
    elif event_type == "attach_failed":
        print(f"[!] Attach failed: {payload.get('err')}")
    elif event_type == "watcher_start":
        print(f"[+] Watcher started, watching {state.target!r}")
    elif event_type == "spawn_added":
        print(f"[*] Spawn-added: pid={payload.get('pid')} ident={payload.get('identifier')}")
    elif event_type == "child_added":
        print(f"[*] Child-added: pid={payload.get('pid')} ident={payload.get('identifier')}")
    elif event_type == "device_lost":
        print(f"[!] Device lost — entering reconnect loop")
    elif event_type == "device_reconnected":
        print(f"[+] Device reconnected")
    elif event_type == "stopping":
        print(f"[+] Stopping watcher")


# -----------------------------------------------------------------------------
# Device acquisition
# -----------------------------------------------------------------------------


def acquire_device(use_local: bool, state: WatcherState) -> frida.core.Device:
    """Get the Frida device, retrying on transport errors with exponential backoff."""
    backoff = RECONNECT_BACKOFF_SECS
    while not state.stop_event.is_set():
        try:
            if use_local:
                dev = frida.get_local_device()
            else:
                dev = frida.get_usb_device(timeout=5)
            return dev
        except Exception as e:
            log_event(state, "device_acquire_failed", err=str(e), backoff=backoff)
            state.stop_event.wait(backoff)
            backoff = min(backoff * 2, MAX_RECONNECT_BACKOFF_SECS)
    raise RuntimeError("stop requested while acquiring device")


# -----------------------------------------------------------------------------
# Process discovery
# -----------------------------------------------------------------------------


def find_target_pid(device: frida.core.Device, target: str) -> Optional[int]:
    """Look for the target process by name. Returns PID or None.

    Tries get_process first (exact name match), falls back to enumerate_processes
    (substring match) — covers both "Suzuki Ride Connect" display-name lookup
    and "suzuki.com.suzuki" package-id lookup.
    """
    try:
        proc = device.get_process(target)
        return proc.pid
    except frida.ProcessNotFoundError:
        pass
    except Exception:
        pass

    # Fallback: enumerate + substring match (handles either name or package id).
    try:
        for p in device.enumerate_processes():
            if p.name == target or target in p.name:
                return p.pid
            # Also match the suzuki package id even when target is the display name.
            if "suzuki" in p.name.lower():
                return p.pid
    except Exception:
        return None
    return None


# -----------------------------------------------------------------------------
# Attach + script load
# -----------------------------------------------------------------------------


def attach_and_inject(state: WatcherState, pid: int) -> bool:
    """Attach to pid and load the agent. Returns True on success."""
    try:
        session = state.device.attach(pid)
    except Exception as e:
        log_event(state, "attach_failed", pid=pid, err=str(e))
        return False

    # Detach handler — fires when target dies or we lose the session.
    def on_detached(reason, crash):
        # current_pid may have rolled forward already if a respawn raced us;
        # snapshot the pid that actually detached.
        log_event(
            state,
            "session_detached",
            pid=pid,
            reason=str(reason),
            crash=str(crash) if crash else None,
        )
        # Clear our session pointer so the main loop knows to re-attach.
        if state.session is session:
            state.session = None
            state.script = None
            state.current_pid = None

    session.on("detached", on_detached)

    try:
        script = session.create_script(state.script_source, runtime="v8")
    except Exception as e:
        log_event(state, "script_create_failed", pid=pid, err=str(e))
        try:
            session.detach()
        except Exception:
            pass
        return False

    def on_message(msg, data):
        if msg.get("type") == "send":
            log_event(state, "agent_event", payload=msg.get("payload"))
        elif msg.get("type") == "error":
            log_event(
                state,
                "agent_error",
                description=msg.get("description"),
                stack=msg.get("stack"),
                file_name=msg.get("fileName"),
                line=msg.get("lineNumber"),
            )
        else:
            log_event(state, "agent_other_msg", msg=msg)

    script.on("message", on_message)

    try:
        script.load()
    except Exception as e:
        log_event(state, "script_load_failed", pid=pid, err=str(e))
        try:
            session.detach()
        except Exception:
            pass
        return False

    state.session = session
    state.script = script
    state.current_pid = pid
    log_event(state, "attach_ok", pid=pid)
    log_event(state, "script_loaded", pid=pid)
    return True


# -----------------------------------------------------------------------------
# Spawn-gating handlers
# -----------------------------------------------------------------------------


def install_spawn_gating(state: WatcherState) -> None:
    """Enable spawn-gating and wire up event handlers so respawns get caught."""
    device = state.device

    def on_spawn_added(spawn):
        ident = getattr(spawn, "identifier", "") or ""
        pid = getattr(spawn, "pid", None)
        log_event(state, "spawn_added", pid=pid, identifier=ident)
        if "suzuki" in ident.lower():
            # Respawn detected — resume it (gating holds it suspended), then
            # the main loop's poll will see the new PID and attach.
            try:
                device.resume(pid)
            except Exception as e:
                log_event(state, "spawn_resume_failed", pid=pid, err=str(e))
        else:
            # Not our target — resume immediately so we don't freeze other apps.
            try:
                device.resume(pid)
            except Exception:
                pass

    def on_child_added(child):
        ident = getattr(child, "identifier", "") or ""
        pid = getattr(child, "pid", None)
        log_event(state, "child_added", pid=pid, identifier=ident)

    device.on("spawn-added", on_spawn_added)
    device.on("child-added", on_child_added)

    try:
        device.enable_spawn_gating()
        log_event(state, "spawn_gating_enabled")
    except Exception as e:
        # Non-fatal: poll fallback will still work, we just rely on the timer.
        log_event(state, "spawn_gating_failed", err=str(e))


# -----------------------------------------------------------------------------
# Main loop
# -----------------------------------------------------------------------------


def main_loop(state: WatcherState, use_local: bool, poll_secs: float) -> None:
    """Discover -> attach -> watch -> on detach, re-discover."""

    while not state.stop_event.is_set():
        # Make sure we have a device.
        if state.device is None:
            try:
                state.device = acquire_device(use_local, state)
                install_spawn_gating(state)
                log_event(state, "device_reconnected")
            except Exception as e:
                log_event(state, "device_acquire_aborted", err=str(e))
                break

        # Make sure we have an active session.
        if state.session is None:
            pid = None
            try:
                pid = find_target_pid(state.device, state.target)
            except Exception as e:
                # Likely transport-level failure — drop device and retry.
                log_event(state, "device_lost", err=str(e))
                _safe_cleanup(state)
                state.device = None
                continue

            if pid is None:
                log_event(state, "poll_no_target")
            else:
                ok = attach_and_inject(state, pid)
                if not ok:
                    # Wait a beat and retry.
                    state.stop_event.wait(poll_secs)
                    continue

        # We have a session (or just failed to attach). Sleep until next poll.
        # The detach callback flips state.session = None asynchronously, which
        # the next iteration picks up.
        state.stop_event.wait(poll_secs)

    _safe_cleanup(state)
    log_event(state, "stopping")


def _safe_cleanup(state: WatcherState) -> None:
    """Best-effort teardown of session/script."""
    try:
        if state.script is not None:
            try:
                state.script.unload()
            except Exception:
                pass
        if state.session is not None:
            try:
                state.session.detach()
            except Exception:
                pass
    finally:
        state.script = None
        state.session = None
        state.current_pid = None


# -----------------------------------------------------------------------------
# Entry point
# -----------------------------------------------------------------------------


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Robust Frida attach wrapper that survives target respawns "
        "during long offline sessions (e.g. a motorcycle ride).",
    )
    p.add_argument(
        "--script",
        required=True,
        help="Path to the compiled Frida agent .js file (use ride_capture.compiled.js).",
    )
    p.add_argument(
        "--local",
        action="store_true",
        help="Use frida.get_local_device() (set when running inside Termux on phone).",
    )
    p.add_argument(
        "--target",
        default=DEFAULT_TARGET,
        help=f"Process name to watch (default: {DEFAULT_TARGET!r}).",
    )
    p.add_argument(
        "--poll-secs",
        type=float,
        default=DEFAULT_POLL_SECS,
        help=f"Poll interval in seconds (default: {DEFAULT_POLL_SECS}).",
    )
    p.add_argument(
        "--watcher-log",
        default=None,
        help="Path to watcher log file. Default: auto-named in /data/local/tmp "
        "(local mode) or cwd (USB mode).",
    )
    p.add_argument(
        "--quiet",
        action="store_true",
        help="Suppress per-event agent stdout echo (events still logged to file).",
    )
    return p.parse_args()


def default_watcher_log(use_local: bool) -> Path:
    ts = time.strftime("%Y%m%dT%H%M%S")
    fname = f"robust_attach-{ts}.jsonl"
    if use_local:
        # Termux usually has /data/local/tmp writable; fall back to $HOME otherwise.
        candidates = [Path("/data/local/tmp") / fname, Path.home() / fname]
        for c in candidates:
            try:
                c.parent.mkdir(parents=True, exist_ok=True)
                # quick writability test
                with open(c, "a") as _:
                    pass
                return c
            except Exception:
                continue
        return Path.cwd() / fname
    return Path.cwd() / fname


def main() -> int:
    args = parse_args()

    script_path = Path(args.script)
    if not script_path.exists():
        print(f"[!] Script not found: {script_path}", file=sys.stderr)
        return 2
    script_source = script_path.read_text()

    watcher_log = Path(args.watcher_log) if args.watcher_log else default_watcher_log(args.local)
    watcher_log.parent.mkdir(parents=True, exist_ok=True)

    state = WatcherState(
        target=args.target,
        script_source=script_source,
        watcher_log_path=watcher_log,
        quiet=args.quiet,
    )

    # Open watcher log first so the start event lands in it.
    state.log_fp = open(watcher_log, "a", buffering=1)
    log_event(
        state,
        "watcher_start",
        target=args.target,
        script=str(script_path.resolve()),
        watcher_log=str(watcher_log.resolve()),
        local=args.local,
        poll_secs=args.poll_secs,
        pid_self=os.getpid(),
    )
    print(f"[+] Watcher log: {watcher_log}")

    # Signal handling — clean shutdown on Ctrl-C / SIGTERM.
    def _on_signal(signum, _frame):
        log_event(state, "signal", signum=signum)
        state.stop_event.set()

    signal.signal(signal.SIGINT, _on_signal)
    signal.signal(signal.SIGTERM, _on_signal)

    try:
        main_loop(state, use_local=args.local, poll_secs=args.poll_secs)
    finally:
        _safe_cleanup(state)
        try:
            if state.log_fp is not None:
                state.log_fp.close()
        except Exception:
            pass

    print(f"[+] Done. Watcher log: {watcher_log}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
