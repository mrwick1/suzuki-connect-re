"""
Test-attach: load a Frida script into the running Suzuki Connect app,
wait a few seconds for hooks to install, then detach. Prints every
message the script emits (including hook_error events that would flag
obfuscated-name mismatches between our decompile and the running APK).

Usage:
    python tools/test_attach.py [frida-scripts/<script>.js] [seconds]
"""

import sys
import time

import frida

SCRIPT_PATH = sys.argv[1] if len(sys.argv) > 1 else "frida-scripts/ride_capture.js"
WAIT_SECS = int(sys.argv[2]) if len(sys.argv) > 2 else 5
APP_NAME = "Suzuki Ride Connect"

print(f"Attaching to {APP_NAME!r} via USB ...")
device = frida.get_usb_device(timeout=5)
session = device.attach(APP_NAME)

print(f"Loading {SCRIPT_PATH} ...")
with open(SCRIPT_PATH, "r") as f:
    # runtime="v8" — needed for the `Java` global bridge to auto-load.
    # QuickJS (Frida's default) requires explicit `require("frida-java-bridge")`.
    script = session.create_script(f.read(), runtime="v8")


def on_message(msg, _data):
    if msg.get("type") == "send":
        print(f"  send: {msg['payload']}")
    elif msg.get("type") == "error":
        print(f"  ERROR: {msg.get('description')}\n         stack: {msg.get('stack')}")
    else:
        print(f"  msg: {msg}")


script.on("message", on_message)
script.load()
print(f"Script loaded. Watching for {WAIT_SECS}s ...")
time.sleep(WAIT_SECS)

print("Detaching.")
session.detach()
