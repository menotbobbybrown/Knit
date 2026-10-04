#!/usr/bin/env bash
#
# An emulator on the real BLE mesh through one of knit-ios's nRF52840 dongles, with the host stack's whole HCI
# conversation tapped and fault-injectable on the host (scripts/hci-bridge.py has the why).
#
# The other route, scripts/emulator-ble-mesh.sh, passes a dongle's USB device into the guest. This one leaves the
# dongle on the host and points the emulator's Bluetooth HAL at a Bumble bridge instead of netsim
# (`-packet-streamer-endpoint`), so it needs no root image, no firmware push, no SELinux or driver rebind, and no
# unbind from the host's btusb — Bumble's libusb detaches btusb on open and hands the dongle back on close.
#
# Usage:
#   scripts/emulator-hci-bridge.sh host [a|b]     # check the host side for that dongle (prints the one sudo step)
#   scripts/emulator-hci-bridge.sh up [a|b]       # start the bridge on that dongle, then the AVD on it
#   scripts/emulator-hci-bridge.sh status         # bridge links/faults/counters, adapter address, mesh state
#   scripts/emulator-hci-bridge.sh ctl ARGS…      # hci-bridge.py ctl ARGS… (refuse / cut / deafen / clear / links)
#   scripts/emulator-hci-bridge.sh down           # kill the emulator and the bridge; the dongle goes back to BlueZ
#
# Each `up` writes build/hci-bridge/<stamp>/{bridge.log,hci.btsnoop,emulator.log}; `latest` points at it.
# Env: KNIT_HCI_AVD (Knit_Mesh_BT), KNIT_HCI_EMU_PORT (5582), KNIT_HCI_PORT (8877, the packet streamer),
#      KNIT_HCI_CONTROL (8878), KNIT_HCI_HEADLESS=1 for -no-window.
#
# The dongles belong to knit-ios's Linux peer rig (its .agents/rules/devices.md): A (E0:E4:06:34:B9:73) is
# knit-peer's default adapter, B (7C:F7:1D:74:2A:49) its second peer. While the bridge holds one, BlueZ has no such
# adapter and a knit-peer run on it exits; check nothing of knit-ios's is using it first.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
AVD=${KNIT_HCI_AVD:-Knit_Mesh_BT}
EMU_PORT=${KNIT_HCI_EMU_PORT:-5582}
SERIAL="emulator-$EMU_PORT"
PORT=${KNIT_HCI_PORT:-8877}
CONTROL=${KNIT_HCI_CONTROL:-8878}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
EMULATOR="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
OUT="$ROOT/build/hci-bridge"
VIDPID=2fe3:000b

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
adb_() { "$ADB" -s "$SERIAL" "$@"; }
bridge() { uv run -q "$ROOT/scripts/hci-bridge.py" "$@"; }

# Dongle → USB serial and BD address (knit-ios .agents/context/linux-peer.md, "The adapter").
usb_serial() { case $1 in a) echo C849E41B33D5B711 ;; b) echo FA157AEC5AD9FD9D ;; *) die "dongle is a or b" ;; esac; }
bd_addr() { case $1 in a) echo E0:E4:06:34:B9:73 ;; b) echo 7C:F7:1D:74:2A:49 ;; esac; }

# The sysfs directory of the dongle with that USB serial (empty if it is not plugged in).
usb_dir() {
  local d
  for d in /sys/bus/usb/devices/*; do
    [ "$(cat "$d/idVendor" 2>/dev/null):$(cat "$d/idProduct" 2>/dev/null)" = "$VIDPID" ] || continue
    [ "$(cat "$d/serial" 2>/dev/null)" = "$1" ] && { echo "$d"; return; }
  done
}

cmd_host() {
  local dongle=${1:-b} serial dir node ok=0
  serial=$(usb_serial "$dongle")
  dir=$(usb_dir "$serial")
  [ -n "$dir" ] || die "dongle $dongle (USB serial $serial) is not plugged in"
  node=$(printf '/dev/bus/usb/%03d/%03d' "$(cat "$dir/busnum")" "$(cat "$dir/devnum")")
  if [ -w "$node" ]; then
    echo "ok: $node (dongle $dongle) is writable"
  else
    ok=1
    echo "MISSING: $node is not writable by $USER. One sudo, persists:"
    # shellcheck disable=SC2028  # the \\n is meant literally: it is the printf the user runs
    echo "  printf 'SUBSYSTEM==\"usb\", ATTR{idVendor}==\"2fe3\", ATTR{idProduct}==\"000b\", MODE=\"0660\", GROUP=\"plugdev\"\\n' \\"
    echo "    | sudo tee /etc/udev/rules.d/61-knit-hci-bridge.rules"
    echo "  sudo udevadm control --reload && sudo udevadm trigger --subsystem-match=usb --attr-match=idVendor=2fe3"
  fi
  command -v uv >/dev/null || { ok=1; echo "MISSING: uv (the bridge runs as a uv script)"; }
  if pgrep -af knit-peer | grep -q -- "$(bd_addr "$dongle")"; then
    ok=1; echo "BUSY: a knit-peer is running on dongle $dongle"
  fi
  return $ok
}

wait_booted() {
  adb_ wait-for-device
  # shellcheck disable=SC2016  # $(getprop) must expand in the guest shell, not here
  timeout 300 "$ADB" -s "$SERIAL" shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done' \
    || die "$SERIAL never finished booting"
}

cmd_up() {
  local dongle=${1:-b}
  cmd_host "$dongle" || die "host prerequisites are not met (see above)"
  pgrep -f "hci-bridge.py serve" >/dev/null && die "a bridge is already running (down first)"
  pgrep -f "qemu-system-x86_64.* -avd $AVD" >/dev/null && die "$AVD is already running"
  local run; run="$OUT/$(date +%Y%m%d-%H%M%S)-$dongle"
  mkdir -p "$run"; ln -sfn "$run" "$OUT/latest"

  say "bridge on dongle $dongle ($(bd_addr "$dongle")) → localhost:$PORT (logs: $run)"
  nohup uv run -q "$ROOT/scripts/hci-bridge.py" serve --controller "usb:${VIDPID}/$(usb_serial "$dongle")" \
    --port "$PORT" --control "$CONTROL" --snoop "$run/hci.btsnoop" >"$run/bridge.log" 2>&1 &
  echo $! >"$run/bridge.pid"
  local _
  for _ in $(seq 30); do grep -q "control on" "$run/bridge.log" && break; sleep 1; done
  grep -q "control on" "$run/bridge.log" || { cat "$run/bridge.log"; die "the bridge never came up"; }

  local window=(); [ -n "${KNIT_HCI_HEADLESS:-}" ] && window=(-no-window)
  say "launching $AVD on $SERIAL"
  nohup "$EMULATOR" -avd "$AVD" -port "$EMU_PORT" -no-audio -no-snapshot "${window[@]}" \
    -packet-streamer-endpoint "localhost:$PORT" >"$run/emulator.log" 2>&1 &
  wait_booted
  adb_ shell 'svc bluetooth enable' >/dev/null 2>&1 || true
  sleep 8
  cmd_status
}

cmd_status() {
  if pgrep -f "hci-bridge.py serve" >/dev/null; then
    say "bridge"
    bridge ctl status --control "$CONTROL" | python3 -m json.tool
    tail -5 "$OUT/latest/bridge.log" 2>/dev/null | sed 's/^/  /'
  else
    say "bridge: not running"
  fi
  if "$ADB" devices | grep -q "^$SERIAL"; then
    say "adapter ($SERIAL)"
    adb_ shell 'dumpsys bluetooth_manager | sed -n "2,6p"'
    if adb_ shell 'pm path app.getknit.knit' >/dev/null 2>&1; then
      say "knit mesh"
      adb_ shell 'am broadcast -p app.getknit.knit -a app.getknit.knit.debug.STATE' 2>/dev/null |
        sed -n 's/.*data="//;s/"$//p' | sed -e 's/,"metrics".*//' -e 's/^/  /'
    fi
  fi
}

cmd_down() {
  adb_ emu kill >/dev/null 2>&1 || true
  # SIGINT, so Bumble closes the device and libusb gives it back to btusb; a SIGTERM leaves it detached.
  local pidfile="$OUT/latest/bridge.pid" pid _
  if [ -f "$pidfile" ] && pid=$(cat "$pidfile") && kill -0 "$pid" 2>/dev/null; then
    kill -INT "$pid"
    for _ in $(seq 10); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill "$pid"
  fi
  sleep 2
  echo "emulator and bridge stopped; BlueZ adapters now:"
  hcitool dev | sed 1d
}

case "${1:-status}" in
  host)   shift; cmd_host "$@" ;;
  up)     shift; cmd_up "$@" ;;
  status) cmd_status ;;
  ctl)    shift; bridge ctl "$@" --control "$CONTROL" ;;
  down)   cmd_down ;;
  *)      die "unknown command '${1}' — one of: host up status ctl down" ;;
esac
