#!/usr/bin/env python3
"""Connect to a BLE peripheral and enumerate every Service + Characteristic.

Usage:
    python gatt_walk.py --scan                       # find devices
    python gatt_walk.py --address AA:BB:CC:DD:EE:FF  # walk one device

Prints a tree and (for readable characteristics) the bytes of a single read.
NEVER WRITES.
"""
import argparse
import asyncio
import sys
from bleak import BleakScanner, BleakClient


async def scan(timeout: float = 8.0) -> None:
    print(f"Scanning for {timeout}s...")
    devices = await BleakScanner.discover(timeout=timeout, return_adv=True)
    for addr, (d, adv) in devices.items():
        print(f"  {addr}  RSSI={adv.rssi}  name={d.name!r}")


async def walk(address: str) -> None:
    print(f"Connecting to {address}...")
    async with BleakClient(address) as client:
        print(f"Connected. MTU={client.mtu_size}")
        for service in client.services:
            print(f"\nService {service.uuid}  ({service.description})")
            for char in service.characteristics:
                props = ",".join(char.properties)
                print(f"  Char {char.uuid}  [{props}]  ({char.description})")
                if "read" in char.properties:
                    try:
                        data = await client.read_gatt_char(char.uuid)
                        print(f"    READ -> {data.hex()}  ({len(data)} bytes)")
                    except Exception as e:
                        print(f"    READ failed: {type(e).__name__}: {e}")
                for desc in char.descriptors:
                    print(f"    Descriptor {desc.uuid}  handle={desc.handle}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scan", action="store_true")
    parser.add_argument("--address")
    parser.add_argument("--timeout", type=float, default=8.0)
    args = parser.parse_args()

    if args.scan:
        asyncio.run(scan(args.timeout))
    elif args.address:
        asyncio.run(walk(args.address))
    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
