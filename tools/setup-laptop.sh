#!/usr/bin/env bash
# Phase 1 laptop tooling installer. Idempotent.
set -euo pipefail

echo "==> Installing pacman packages"
sudo pacman -S --needed --noconfirm \
    android-tools \
    wireshark-qt \
    python python-pip

echo "==> Installing yay (AUR) packages"
yay -S --needed --noconfirm \
    jadx \
    apktool

echo "==> Installing Python packages in a project venv"
cd "$(dirname "$0")/.."
if [[ ! -d .venv ]]; then
    python -m venv .venv
fi
# shellcheck disable=SC1091
source .venv/bin/activate
pip install --upgrade pip
pip install bleak frida-tools mitmproxy pytest pyshark cryptography

echo
echo "==> Checking versions"
jadx --version
adb version | head -1
wireshark --version | head -1
.venv/bin/frida --version
.venv/bin/python -c "from importlib.metadata import version; \
    print('bleak', version('bleak')); \
    print('pytest', version('pytest')); \
    print('pyshark', version('pyshark')); \
    print('cryptography', version('cryptography'))"

echo
echo "All laptop tooling installed."
echo "Activate venv with: source .venv/bin/activate"
