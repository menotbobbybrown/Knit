#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["bumble[android]==0.0.235"]
# ///
"""Put a real Bluetooth controller under an emulator's own Bluetooth stack, with a tap and faults in between.

    uv run scripts/hci-bridge.py serve --controller usb:2fe3:000b/SERIAL [--port 8877] [--control 8878]
        [--snoop FILE.btsnoop] [--trace]
    uv run scripts/hci-bridge.py ctl status|links|clear
    uv run scripts/hci-bridge.py ctl refuse 0x2039 0x0d [--count N] [--param0 0x01] [--pad N]
    uv run scripts/hci-bridge.py ctl cut all|HANDLE [--reason 0x08]
    uv run scripts/hci-bridge.py ctl deafen SECONDS

The emulator's Bluetooth HAL speaks netsim's packet-streamer gRPC; `emulator -packet-streamer-endpoint
localhost:PORT` points it here instead of at netsim, and Bumble's `android-netsim:…,mode=controller` transport is
the server end. The other end is any Bumble controller transport, here the nRF52840 dongles knit-ios flashed with
`firmware/knit-hci` (Zephyr `hci_usb`, no vendor firmware to load). So the app, the AOSP host stack and the real
controller are all the shipped ones; only the cable between the host stack and the controller is ours. That is the
point: everything the host stack says to the controller, and everything the controller answers, passes through here.

- **The tap.** `--snoop` writes a btsnoop file (Wireshark, `btmon -r`), the same records a phone's
  "Bluetooth HCI snoop log" keeps, live on the host. The log line per event is the short oracle: every connection
  up/down with its handle, role, peer and reason, PHY and parameter updates, and every command the real controller
  answered with an error (`controller refused …`) — the refusals ADR 2026-10.9utz had to infer from logcat.
- **Faults, armed at run time** through the control port (JSON lines on localhost; `ctl` is the client):
  - `refuse OPCODE STATUS` answers the next COUNT matching host commands with STATUS and never forwards them —
    Command Status for the commands the spec answers that way, else Command Complete with STATUS and PAD zero bytes
    (a command whose return carries more than a status needs its length padded, or the host stack may reject the
    event). `--param0` matches only commands whose first parameter byte is that value (0x2039 with 0x01 is an
    *enable*). `refuse 0x2039 0x0d` is 9utz's refusal on demand.
  - `cut` drops a live link the way a fade does: the bridge sends the controller an HCI Disconnect of its own,
    swallows its Command Status, and hands the host the Disconnection Complete with REASON (0x08, connection timeout,
    by default). Injected commands wait for the host's commands to drain first, so the command-credit count the
    host keeps never moves. **The fade is one-sided:** the far side gets an LL terminate at once (0x13, remote user
    terminated) instead of waiting out its own supervision timeout, so a relink timed from the far side reads
    faster than after a real fade. Only the emulator's side of a cut is evidence; a two-sided fade needs the
    controller to stop transmitting, which is a firmware change (a vendor command in knit-ios's `knit-hci`).
  - `deafen` drops advertising reports on their way to the host for SECONDS: the scan runs, the host hears nothing.
- **The shim (`--no-shim` turns it off).** The nRF52840 has no BR/EDR radio, and Android's stack assumes one: GD
  aborts on a Read Local Name it cannot parse, and `btm_sec_dev_reset` refuses a controller without Secure Simple
  Pairing. So when the controller answers one of a fixed list of BR/EDR housekeeping commands with "unknown
  command", the bridge answers success with spec-sized return parameters (`SHIM`); Read Local Extended Features is
  answered from the controller's own Read Local Supported Features, plus the SSP bit (`CLAIMED_FEATURES`). Nothing
  BR/EDR ever reaches the controller to use them. `ctl status` lists what was shimmed.
- **The scan arbiter (off with the shim).** GD dials with its scan on, which a phone's controller allows. Zephyr's
  initiator runs on its scanner and answers that dial Command Disallowed (0x0c), so on 2026-10-04 every dial of a
  second boot failed. The bridge turns the scanner off before forwarding a dial. It answers scan commands sent
  during the dial itself and keeps them, then restores the scanner the way the host last asked once the dial
  completes or is cancelled. `counts.arbitrated` counts the dials it stepped in for.
- **Known limits of the rig, not of Knit:**
  - Zephyr refuses a second accept-list dial while a link it dialed is up (`0x0b`, ACL Connection Already Exists):
    `ll_create_connection` records the command's all-zero peer address on the link, and its same-peer check then
    matches the next accept-list dial. GD always dials through the accept list, so the emulator dials one peer at a
    time; links it accepts are unaffected. The cure is `CONFIG_BT_CTLR_ALLOW_SAME_PEER_CONN=y` in `knit-hci`.
  - A dongle let go of mid-stream wedged Zephyr's USB HCI class until a replug, so the bridge resets the controller
    on its way out (`quiesce`, on SIGINT or SIGTERM) and rebinds btusb (`give_back`).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import signal
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass, field

CMD, ACL, EVT = 0x01, 0x02, 0x04
EV_DISCONNECTION_COMPLETE = 0x05
EV_COMMAND_COMPLETE = 0x0E
EV_COMMAND_STATUS = 0x0F
EV_LE_META = 0x3E
OP_DISCONNECT = 0x0406
OP_READ_LOCAL_EXTENDED_FEATURES = 0x1004
OP_SCAN_PARAMS, OP_SCAN_ENABLE = 0x2041, 0x2042  # LE Set Extended Scan Parameters / Enable
DIALS = {0x200D, 0x2043}  # LE Create Connection, LE Extended Create Connection
SCAN_OFF = bytes([CMD, OP_SCAN_ENABLE & 0xFF, OP_SCAN_ENABLE >> 8, 6, 0, 0, 0, 0, 0, 0])
# LMP feature bits (page 0) the AOSP stack refuses to start without, which an LE-only controller lacks. Claimed in the
# shimmed page 0 only; nothing BR/EDR ever reaches the controller to use them.
CLAIMED_FEATURES = {51: "Secure Simple Pairing (Controller Support)"}  # btm_sec.cc btm_sec_dev_reset

LE_CONNECTION_COMPLETE = 0x01
LE_ADVERTISING_REPORT = 0x02
LE_CONNECTION_UPDATE_COMPLETE = 0x03
LE_DATA_LENGTH_CHANGE = 0x07
LE_ENHANCED_CONNECTION_COMPLETE = 0x0A
LE_DIRECTED_ADVERTISING_REPORT = 0x0B
LE_PHY_UPDATE_COMPLETE = 0x0C
LE_EXTENDED_ADVERTISING_REPORT = 0x0D
LE_ADVERTISING_SET_TERMINATED = 0x12
LE_ENHANCED_CONNECTION_COMPLETE_V2 = 0x29
ADVERTISING_REPORTS = {LE_ADVERTISING_REPORT, LE_DIRECTED_ADVERTISING_REPORT, LE_EXTENDED_ADVERTISING_REPORT}
CONNECTION_COMPLETES = {LE_CONNECTION_COMPLETE, LE_ENHANCED_CONNECTION_COMPLETE, LE_ENHANCED_CONNECTION_COMPLETE_V2}

# Commands the spec answers with Command Status, not Command Complete (the ones an LE host sends).
STATUS_COMMANDS = {
    0x0406,  # Disconnect
    0x0405,  # Create Connection
    0x041D,  # Read Remote Version Information
    0x200D,  # LE Create Connection
    0x2013,  # LE Connection Update
    0x2016,  # LE Read Remote Features
    0x2019,  # LE Enable Encryption
    0x2025,  # LE Read Local P-256 Public Key
    0x2026,  # LE Generate DHKey
    0x2032,  # LE Set PHY
    0x2043,  # LE Extended Create Connection
    0x2044,  # LE Periodic Advertising Create Sync
}
PHYS = {1: "1M", 2: "2M", 3: "Coded"}
UNKNOWN_COMMAND = 0x01

# BR/EDR housekeeping an LE-only controller (Zephyr's) answers "unknown command", which Android's GD stack takes as a
# fatal Command Complete it cannot parse (`controller.cc read_local_name_complete_handler: complete_view.IsValid()`,
# a crash loop). Each entry is the return parameters after the status, sized as the spec gives them, so the
# synthesized success parses. The shim only ever answers a command the controller just refused as unknown.
SHIM = {
    0x0C13: b"",  # Write Local Name
    0x0C14: b"Knit nRF52840 HCI".ljust(248, b"\0"),  # Read Local Name
    0x0C18: b"",  # Write Page Timeout
    0x0C1A: b"",  # Write Scan Enable
    0x0C1C: b"",  # Write Page Scan Activity
    0x0C1E: b"",  # Write Inquiry Scan Activity
    0x0C23: bytes(3),  # Read Class of Device
    0x0C24: b"",  # Write Class of Device
    0x0C33: b"",  # Host Buffer Size
    0x0C43: b"",  # Write Inquiry Scan Type
    0x0C45: b"",  # Write Inquiry Mode
    0x0C47: b"",  # Write Page Scan Type
    0x0C52: b"",  # Write Extended Inquiry Response
    0x0C56: b"",  # Write Simple Pairing Mode
    0x0C58: bytes(1),  # Read Inquiry Response Transmit Power Level
    0x0C5A: bytes(1),  # Read Default Erroneous Data Reporting
    0x0C63: b"",  # Set Event Mask Page 2
    0x0C6D: b"",  # Write LE Host Support
    0x0C7A: b"",  # Write Secure Connections Host Support
    0x080F: b"",  # Write Default Link Policy Settings
    0x0C05: b"",  # Set Event Filter
    0x0C12: bytes(2),  # Delete Stored Link Key
    0x0C26: b"",  # Write Voice Setting
    0x0C38: b"\x01",  # Read Number of Supported IAC
    0x0C39: b"\x00",  # Read Current IAC LAP (none)
    0x0C3A: b"",  # Write Current IAC LAP
    0x0C59: b"",  # Write Inquiry Transmit Power Level
    0x0C5B: b"",  # Write Default Erroneous Data Reporting
    # Read Buffer Size: GD asserts it succeeds, and uses it only when LE Read Buffer Size reports no LE buffers of
    # their own, which the knit-hci firmware always does (251 B × 8). These mirror those.
    0x1005: (251).to_bytes(2, "little") + b"\x00" + (8).to_bytes(2, "little") + bytes(2),
}


def now() -> str:
    t = time.time()
    return time.strftime("%H:%M:%S", time.localtime(t)) + f".{int(t * 1000) % 1000:03d}"


def say(line: str) -> None:
    print(f"{now()} {line}", flush=True)


def op_name(op: int) -> str:
    from bumble import hci

    name = hci.HCI_Command.command_names.get(op)
    return f"{name.removeprefix('HCI_').removesuffix('_COMMAND')} (0x{op:04x})" if name else f"0x{op:04x}"


def status_name(status: int) -> str:
    from bumble import hci

    try:
        return f"{hci.HCI_Constant.error_name(status)}(0x{status:02x})"
    except Exception:  # noqa: BLE001 - an unknown code still has a number
        return f"0x{status:02x}"


def addr(b: bytes) -> str:
    return ":".join(f"{x:02X}" for x in reversed(b))


@dataclass
class Link:
    handle: int
    role: str
    peer: str
    interval_ms: float
    timeout_ms: int
    since: float = field(default_factory=time.time)
    phy: str = "1M"
    cut_reason: int | None = None

    def view(self) -> dict:
        return {
            "handle": self.handle, "role": self.role, "peer": self.peer, "phy": self.phy,
            "intervalMs": self.interval_ms, "timeoutMs": self.timeout_ms, "ageS": round(time.time() - self.since, 1),
        }


@dataclass
class Refusal:
    op: int
    status: int
    count: int  # 0: until cleared
    param0: int | None
    pad: int

    def view(self) -> dict:
        return {"opcode": f"0x{self.op:04x}", "status": f"0x{self.status:02x}", "count": self.count,
                "param0": None if self.param0 is None else f"0x{self.param0:02x}", "pad": self.pad}


class Bridge:
    def __init__(self, host_sink, controller_sink, snooper, trace: bool, shim: bool = True):
        self.host_sink = host_sink  # packets to the emulator's stack
        self.controller_sink = controller_sink  # packets to the dongle
        self.snooper = snooper
        self.trace = trace
        self.shim = shim
        self.shimmed: set[int] = set()
        self.links: dict[int, Link] = {}
        self.refusals: list[Refusal] = []
        self.deaf_until = 0.0
        self.host_cmds_in_flight = 0
        # Commands of the bridge's own: queued until the host's commands drain, then answered to a callback and
        # never to the host. FIFO per opcode, as the controller answers them.
        self.injected: list[tuple[bytes, Callable[[bytes], None]]] = []
        self.own_pending: dict[int, list[Callable[[bytes], None]]] = {}
        self.host_params: dict[int, bytes] = {}  # the last parameters the host sent per opcode
        self.features: bytes | None = None  # the controller's LMP features page 0, once read
        self.closing = False
        # The scan arbiter: what the host last asked of the scanner, what the controller is doing, and a dial.
        self.arbiter = shim
        self.scan_on = False
        self.scan_wanted = False
        self.scan_enable: bytes | None = None  # the host's last enabling parameters
        self.scan_params: bytes | None = None  # the host's last scan parameters
        self.scan_params_owed = False  # changed while a dial held the scanner off
        self.dialing = False
        self.counts = {"cmd": 0, "acl_out": 0, "acl_in": 0, "acl_out_bytes": 0, "acl_in_bytes": 0, "evt": 0,
                       "adv_reports": 0, "adv_dropped": 0, "refused": 0, "controller_errors": 0, "cuts": 0}

    # -- host → controller -------------------------------------------------------------------------------------------
    def from_host(self, packet: bytes) -> None:
        if self.closing:
            return
        kind = packet[0]
        if kind == ACL:
            self.counts["acl_out"] += 1
            self.counts["acl_out_bytes"] += len(packet) - 5
        elif kind == CMD:
            self.counts["cmd"] += 1
            op = packet[1] | packet[2] << 8
            if self.trace:
                say(f"> {op_name(op)} {packet[4:].hex()}")
            if self.maybe_refuse(op, packet):
                return
            if op == 0x0C03:  # Reset: the host starts over, and so does what we know
                say("host reset the controller")
                self.links.clear()
                self.injected.clear()
                self.own_pending.clear()
                self.host_cmds_in_flight = 0
                self.scan_on = self.scan_wanted = self.dialing = self.scan_params_owed = False
            self.host_params[op] = packet[4:]
            self.host_cmds_in_flight += 1
            if self.arbiter and self.arbitrate(op, packet):
                return
        self.snoop(packet, 0)
        self.controller_sink.on_packet(packet)

    def arbitrate(self, op: int, packet: bytes) -> bool:
        """Zephyr's initiator runs on its scanner (`ull_central.c`: a dial while the 1M scanner is on is Command
        Disallowed), and GD dials with its scan on, as a phone's controller allows. So a dial turns the scanner off
        first, scan commands during a dial are answered here and kept, and the scanner comes back the way the host
        last asked once the dial ends. True when the packet was handled here."""
        if op == OP_SCAN_PARAMS:
            self.scan_params = packet[4:]
        elif op == OP_SCAN_ENABLE and len(packet) > 4:
            self.scan_wanted = packet[4] == 1
            if self.scan_wanted:
                self.scan_enable = packet[4:]
        if op in (OP_SCAN_PARAMS, OP_SCAN_ENABLE) and self.dialing:
            self.scan_params_owed |= op == OP_SCAN_PARAMS
            self.snoop(packet, 0)
            self.answer_host(op, 0)
            return True
        if op in DIALS and self.scan_on:
            self.snoop(packet, 0)
            self.dialing = True
            self.counts["arbitrated"] = self.counts.get("arbitrated", 0) + 1

            def scan_off(p: bytes) -> None:  # then the dial, as the host sent it
                self.scan_on = False
                if p[3]:
                    say(f"arbiter: the scanner refused to stop ({status_name(p[3])}); dialing anyway")
                self.controller_sink.on_packet(packet)

            self.own_pending.setdefault(OP_SCAN_ENABLE, []).append(scan_off)
            self.snoop(SCAN_OFF, 0)
            self.controller_sink.on_packet(SCAN_OFF)
            return True
        return False

    def answer_host(self, op: int, status: int) -> None:
        self.host_cmds_in_flight = max(0, self.host_cmds_in_flight - 1)
        self.to_host(bytes([EVT, EV_COMMAND_COMPLETE, 4, 1, op & 0xFF, op >> 8, status]))

    def dial_ended(self) -> None:
        self.dialing = False
        if not self.arbiter or not self.scan_wanted or self.scan_on or self.scan_enable is None:
            return

        def scan_back(p: bytes) -> None:
            self.scan_on = p[3] == 0
            if p[3]:
                say(f"arbiter: the scanner refused to restart ({status_name(p[3])})")

        if self.scan_params_owed and self.scan_params is not None:
            self.scan_params_owed = False
            params = bytes([CMD, OP_SCAN_PARAMS & 0xFF, OP_SCAN_PARAMS >> 8, len(self.scan_params)]) + self.scan_params
            self.inject(params, lambda p: p[3] and say(f"arbiter: scan parameters refused ({status_name(p[3])})"))
        enable = bytes([CMD, OP_SCAN_ENABLE & 0xFF, OP_SCAN_ENABLE >> 8, len(self.scan_enable)]) + self.scan_enable
        self.inject(enable, scan_back)

    def maybe_refuse(self, op: int, packet: bytes) -> bool:
        for r in self.refusals:
            if r.op != op or (r.param0 is not None and (len(packet) < 5 or packet[4] != r.param0)):
                continue
            if r.count:
                r.count -= 1
                if r.count == 0:
                    self.refusals.remove(r)
            self.counts["refused"] += 1
            self.snoop(packet, 0)
            if op in STATUS_COMMANDS:
                reply = bytes([EVT, EV_COMMAND_STATUS, 4, r.status, 1, op & 0xFF, op >> 8])
            else:
                reply = bytes([EVT, EV_COMMAND_COMPLETE, 4 + r.pad, 1, op & 0xFF, op >> 8, r.status]) + bytes(r.pad)
            say(f"FAULT refused {op_name(op)} params={packet[4:].hex()} with {status_name(r.status)}")
            self.snoop(reply, 1)
            self.host_sink.on_packet(reply)
            return True
        return False

    # -- controller → host -------------------------------------------------------------------------------------------
    def from_controller(self, packet: bytes) -> None:
        kind = packet[0]
        if kind == ACL:
            self.counts["acl_in"] += 1
            self.counts["acl_in_bytes"] += len(packet) - 5
        elif kind == EVT:
            self.counts["evt"] += 1
            packet = self.on_event(packet)
        if packet is not None:
            self.to_host(packet)
        self.flush_injected()

    def to_host(self, packet: bytes) -> None:
        self.snoop(packet, 1)
        if self.closing:
            return
        self.host_sink.on_packet(packet)

    def on_event(self, packet: bytes) -> bytes | None:
        code, p = packet[1], packet[3:]
        if code in (EV_COMMAND_COMPLETE, EV_COMMAND_STATUS) and len(p) >= 3:
            op = p[1] | p[2] << 8 if code == EV_COMMAND_COMPLETE else p[2] | p[3] << 8
            if self.own_pending.get(op):
                self.snoop(packet, 1)  # in the capture, never to the host
                self.own_pending[op].pop(0)(p)
                return None
        if code == EV_COMMAND_COMPLETE and len(p) >= 3:
            op = p[1] | p[2] << 8
            if op:
                self.host_cmds_in_flight = max(0, self.host_cmds_in_flight - 1)
            if op == OP_SCAN_ENABLE and len(p) >= 4 and p[3] == 0:
                self.scan_on = self.scan_wanted
            if len(p) >= 4 and p[3] == UNKNOWN_COMMAND and (shimmed := self.on_unknown(op, p[0], packet)) is not None:
                return shimmed or None
            if len(p) >= 4 and p[3] != 0 and op:
                self.counts["controller_errors"] += 1
                say(f"controller refused {op_name(op)} with {status_name(p[3])}")
        elif code == EV_COMMAND_STATUS and len(p) >= 4:
            op = p[2] | p[3] << 8
            if op:
                self.host_cmds_in_flight = max(0, self.host_cmds_in_flight - 1)
            if op in DIALS:
                if p[0] == 0:
                    self.dialing = True
                elif self.dialing:
                    self.dial_ended()
            if p[0] == UNKNOWN_COMMAND and (shimmed := self.on_unknown(op, p[1], packet)) is not None:
                return shimmed or None
            if p[0]:
                self.counts["controller_errors"] += 1
                say(f"controller refused {op_name(op)} with {status_name(p[0])}")
        elif code == EV_DISCONNECTION_COMPLETE and len(p) >= 4:
            handle, reason = (p[1] | p[2] << 8) & 0x0FFF, p[3]
            link = self.links.pop(handle, None)
            if link and link.cut_reason is not None:
                say(f"link down handle={handle} {link.peer} (cut; host told {status_name(link.cut_reason)}, "
                    f"controller said {status_name(reason)})")
                packet = bytes([EVT, EV_DISCONNECTION_COMPLETE, 4, p[0], p[1], p[2], link.cut_reason])
            else:
                peer = link.peer if link else "?"
                age = f" after {time.time() - link.since:.1f}s" if link else ""
                say(f"link down handle={handle} {peer} {status_name(reason)}{age}")
        elif code == EV_LE_META and p:
            return self.on_le_meta(packet, p[0], p[1:])
        return packet

    def on_unknown(self, op: int, ncmd: int, refusal: bytes) -> bytes | None:
        """A command the controller refused as unknown (by Command Status, as Zephyr does, or Complete). None passes
        the refusal on; otherwise the host's answer, or b"" when it will come later."""
        if not self.shim or (op not in SHIM and op != OP_READ_LOCAL_EXTENDED_FEATURES):
            return None
        self.snoop(refusal, 1)  # the controller's own answer stays in the capture
        if op not in self.shimmed:
            self.shimmed.add(op)
            say(f"shim: {op_name(op)} is unknown to the controller; answering success for the host")
        if op == OP_READ_LOCAL_EXTENDED_FEATURES:
            self.shim_extended_features(ncmd)
            return b""
        params = bytes([ncmd, op & 0xFF, op >> 8, 0]) + SHIM[op]
        return bytes([EVT, EV_COMMAND_COMPLETE, len(params)]) + params

    def shim_extended_features(self, ncmd: int) -> None:
        """Read Local Extended Features, unknown to an LE-only controller: page 0 is the controller's own LMP
        features (Read Local Supported Features, which it does know, asked on the host's behalf), and there is no
        page past it."""
        op = OP_READ_LOCAL_EXTENDED_FEATURES
        page = (self.host_params.get(op) or b"\0")[0]

        def answer(features: bytes) -> None:
            if page == 0:
                bits = int.from_bytes(features, "little")
                for bit in CLAIMED_FEATURES:
                    bits |= 1 << bit
                features = bits.to_bytes(8, "little")
            params = bytes([ncmd, op & 0xFF, op >> 8, 0, page, 0]) + (features if page == 0 else bytes(8))
            self.to_host(bytes([EVT, EV_COMMAND_COMPLETE, len(params)]) + params)

        if self.features is not None:
            answer(self.features)
            return

        def on_features(p: bytes) -> None:  # Command Complete: ncmd, opcode, status, 8 feature bytes
            self.features = bytes(p[4:12]) if len(p) >= 12 and p[3] == 0 else bytes(8)
            claimed = ", ".join(f"bit {b} {n}" for b, n in CLAIMED_FEATURES.items())
            say(f"shim: controller LMP features {self.features.hex()}; page 0 to the host also claims {claimed}")
            answer(self.features)

        self.inject(bytes([CMD, 0x03, 0x10, 0]), on_features)

    def on_le_meta(self, packet: bytes, sub: int, p: bytes) -> bytes | None:
        if sub in ADVERTISING_REPORTS:
            self.counts["adv_reports"] += 1
            if time.time() < self.deaf_until:
                self.counts["adv_dropped"] += 1
                self.snoop(packet, 1)
                return None
        elif sub in CONNECTION_COMPLETES and len(p) >= 18:
            status, handle, role = p[0], p[1] | p[2] << 8, p[3]
            peer = addr(p[5:11])
            tail = p[11:] if sub == LE_CONNECTION_COMPLETE else p[23:]
            interval, timeout = (tail[0] | tail[1] << 8) * 1.25, (tail[4] | tail[5] << 8) * 10
            if role == 0 and self.dialing:
                self.dial_ended()
            if status == 0x02 and role == 0:
                pass  # a dial the host cancelled
            elif status:
                say(f"link failed {peer} {status_name(status)}")
            else:
                self.links[handle] = Link(handle, "central" if role == 0 else "peripheral", peer, interval, timeout)
                say(f"link up handle={handle} {self.links[handle].role} {peer} interval={interval}ms "
                    f"timeout={timeout}ms")
        elif sub == LE_CONNECTION_UPDATE_COMPLETE and len(p) >= 9 and p[0] == 0:
            handle = p[1] | p[2] << 8
            interval, timeout = (p[3] | p[4] << 8) * 1.25, (p[7] | p[8] << 8) * 10
            if link := self.links.get(handle):
                link.interval_ms, link.timeout_ms = interval, timeout
            say(f"link params handle={handle} interval={interval}ms timeout={timeout}ms")
        elif sub == LE_PHY_UPDATE_COMPLETE and len(p) >= 5:
            handle = p[1] | p[2] << 8
            phy = PHYS.get(p[3], str(p[3]))
            if p[0] == 0 and (link := self.links.get(handle)):
                link.phy = phy
            say(f"link phy handle={handle} tx={phy} rx={PHYS.get(p[4], p[4])} {status_name(p[0])}")
        elif sub == LE_DATA_LENGTH_CHANGE and len(p) >= 10:
            say(f"link dle handle={p[0] | p[1] << 8} tx={p[2] | p[3] << 8}B rx={p[6] | p[7] << 8}B")
        elif sub == LE_ADVERTISING_SET_TERMINATED and len(p) >= 5:
            say(f"advert set {p[1]} terminated {status_name(p[0])} by handle={p[2] | p[3] << 8}")
        return packet

    async def quiesce(self) -> None:
        """Leave the controller with nothing in flight before letting go of it: no more host traffic, then an HCI
        Reset (links dropped, queues flushed) answered while the bridge still reads every endpoint. A dongle let go
        mid-stream wedged Zephyr's USB HCI class (a corrupted ACL packet, then a Reset timeout, then no descriptor
        reads until a replug)."""
        self.closing = True
        done = asyncio.get_running_loop().create_future()
        self.own_pending.setdefault(0x0C03, []).append(lambda _: done.done() or done.set_result(None))
        packet = bytes([CMD, 0x03, 0x0C, 0])
        self.snoop(packet, 0)
        self.controller_sink.on_packet(packet)
        try:
            await asyncio.wait_for(done, 3)
            say("controller reset; letting go of it")
        except TimeoutError:
            say("the controller never answered the closing reset")

    # -- faults ------------------------------------------------------------------------------------------------------
    def cut(self, which, reason: int) -> list[int]:
        handles = list(self.links) if which == "all" else [int(which)]
        cut = []
        for h in handles:
            link = self.links.get(h)
            if link is None or link.cut_reason is not None:
                continue
            link.cut_reason = reason
            self.inject(bytes([CMD, OP_DISCONNECT & 0xFF, OP_DISCONNECT >> 8, 3, h & 0xFF, h >> 8, 0x13]),
                        self.on_own_disconnect)
            self.counts["cuts"] += 1
            cut.append(h)
            say(f"FAULT cutting handle={h} {link.peer} (host will read {status_name(reason)})")
        return cut

    @staticmethod
    def on_own_disconnect(p: bytes) -> None:  # Command Status: status, ncmd, opcode
        if p[0]:
            say(f"controller refused our Disconnect with {status_name(p[0])}")

    def inject(self, packet: bytes, on_answer: Callable[[bytes], None]) -> None:
        self.injected.append((packet, on_answer))
        self.flush_injected()

    def flush_injected(self) -> None:
        while self.injected and self.host_cmds_in_flight == 0:
            packet, on_answer = self.injected.pop(0)
            self.own_pending.setdefault(packet[1] | packet[2] << 8, []).append(on_answer)
            self.snoop(packet, 0)
            self.controller_sink.on_packet(packet)

    def snoop(self, packet: bytes, direction: int) -> None:
        if self.snooper:
            self.snooper.snoop(packet, direction)

    # -- control -----------------------------------------------------------------------------------------------------
    def control(self, req: dict) -> dict:
        cmd = req.get("cmd")
        if cmd == "status":
            return {"links": [lk.view() for lk in self.links.values()], "refusals": [r.view() for r in self.refusals],
                    "shimmed": [op_name(op) for op in sorted(self.shimmed)],
                    "lmpFeatures": self.features.hex() if self.features else None,
                    "deafS": max(0.0, round(self.deaf_until - time.time(), 1)),
                    "hostCommandsInFlight": self.host_cmds_in_flight, "counts": self.counts}
        if cmd == "links":
            return {"links": [lk.view() for lk in self.links.values()]}
        if cmd == "refuse":
            r = Refusal(int(str(req["opcode"]), 0), int(str(req["status"]), 0), int(req.get("count", 1)),
                        None if req.get("param0") is None else int(str(req["param0"]), 0), int(req.get("pad", 0)))
            self.refusals.append(r)
            say(f"FAULT armed: refuse {op_name(r.op)} with {status_name(r.status)} "
                f"x{r.count or '∞'}" + ("" if r.param0 is None else f" when param0=0x{r.param0:02x}"))
            return {"armed": r.view()}
        if cmd == "cut":
            return {"cut": self.cut(req.get("handle", "all"), int(str(req.get("reason", "0x08")), 0))}
        if cmd == "deafen":
            self.deaf_until = time.time() + float(req["seconds"])
            say(f"FAULT deaf for {req['seconds']}s")
            return {"deafS": float(req["seconds"])}
        if cmd == "clear":
            self.refusals.clear()
            self.deaf_until = 0.0
            say("FAULT cleared")
            return {"cleared": True}
        return {"error": f"unknown cmd {cmd!r}"}


class Sink:
    def __init__(self, fn):
        self.on_packet = fn


async def serve(args, snoop_file) -> None:
    from bumble import transport
    from bumble.snoop import BtSnooper

    snooper = BtSnooper(snoop_file) if snoop_file else None
    say(f"opening controller {args.controller}")
    async with await transport.open_transport(args.controller) as (ctl_source, ctl_sink):
        say(f"listening for the emulator on localhost:{args.port} (emulator -packet-streamer-endpoint "
            f"localhost:{args.port})")
        async with await transport.open_transport(f"android-netsim:_:{args.port},mode=controller") as (
            host_source, host_sink,
        ):
            bridge = Bridge(host_sink, ctl_sink, snooper, args.trace, not args.no_shim)
            host_source.set_packet_sink(Sink(bridge.from_host))
            ctl_source.set_packet_sink(Sink(bridge.from_controller))

            async def on_client(reader, writer):
                try:
                    while line := await reader.readline():
                        try:
                            reply = bridge.control(json.loads(line))
                        except Exception as e:  # noqa: BLE001 - a bad request answers, never kills the bridge
                            reply = {"error": str(e)}
                        writer.write((json.dumps(reply) + "\n").encode())
                        await writer.drain()
                finally:
                    writer.close()

            server = await asyncio.start_server(on_client, "127.0.0.1", args.control)
            say(f"control on 127.0.0.1:{args.control}")

            async def flush_snoop():
                while snoop_file:
                    await asyncio.sleep(1)
                    snoop_file.flush()

            # SIGTERM ends the bridge the way SIGINT does, through the quiet reset below.
            main_task = asyncio.current_task()
            asyncio.get_running_loop().add_signal_handler(signal.SIGTERM, main_task.cancel)
            try:
                async with server:
                    await asyncio.gather(server.serve_forever(), flush_snoop())
            except asyncio.CancelledError:
                await bridge.quiesce()
                raise


def ctl(args) -> None:
    import socket

    a = args.action
    if a in ("status", "links", "clear"):
        req = {"cmd": a}
    elif a == "refuse":
        req = {"cmd": "refuse", "opcode": args.rest[0], "status": args.rest[1], "count": args.count,
               "param0": args.param0, "pad": args.pad}
    elif a == "cut":
        req = {"cmd": "cut", "handle": args.rest[0] if args.rest else "all", "reason": args.reason}
    elif a == "deafen":
        req = {"cmd": "deafen", "seconds": float(args.rest[0])}
    else:
        sys.exit(f"unknown action {a}")
    with socket.create_connection(("127.0.0.1", args.control), timeout=5) as s:
        s.sendall((json.dumps(req) + "\n").encode())
        print(s.makefile().readline().strip())


def give_back(spec: str) -> None:
    """Rebind the host's btusb to the dongle, so BlueZ has the adapter again. libusb's auto-detach is meant to do
    this when the interface is released, but a bridge that exits without releasing it (a signal mid-transfer)
    leaves the dongle driverless until a replug."""
    if not spec.startswith("usb:"):
        return
    import usb1

    ids, _, serial = spec.removeprefix("usb:").partition("/")
    vid, pid = (int(x, 16) for x in ids.split(":"))
    with usb1.USBContext() as context:
        for device in context.getDeviceIterator(skip_on_error=True):
            if (device.getVendorID(), device.getProductID()) != (vid, pid):
                continue
            if serial and device.getSerialNumber() != serial:
                continue
            handle = device.open()
            for iface in range(device[0].getNumInterfaces()):
                try:
                    if not handle.kernelDriverActive(iface):
                        handle.attachKernelDriver(iface)
                except usb1.USBError as e:
                    say(f"could not give interface {iface} back to the kernel: {e}")
            handle.close()
            say("dongle handed back to the host's btusb")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="mode", required=True)
    s = sub.add_parser("serve")
    s.add_argument("--controller", required=True, help="Bumble transport spec, e.g. usb:2fe3:000b/SERIAL")
    s.add_argument("--port", type=int, default=8877, help="packet-streamer port the emulator dials")
    s.add_argument("--control", type=int, default=8878)
    s.add_argument("--snoop", help="btsnoop file to write")
    s.add_argument("--trace", action="store_true", help="log every host command")
    s.add_argument("--no-shim", action="store_true",
                   help="pass the controller through as it is: no BR/EDR shim, no scan arbiter")
    c = sub.add_parser("ctl")
    c.add_argument("action", choices=["status", "links", "clear", "refuse", "cut", "deafen"])
    c.add_argument("rest", nargs="*")
    c.add_argument("--control", type=int, default=8878)
    c.add_argument("--count", type=int, default=1, help="refuse: how many (0 = until cleared)")
    c.add_argument("--param0", help="refuse: only commands whose first parameter byte is this")
    c.add_argument("--pad", type=int, default=0, help="refuse: zero bytes after the status")
    c.add_argument("--reason", default="0x08", help="cut: the reason the host reads")
    args = ap.parse_args()
    if args.mode == "serve":
        snoop_file = open(args.snoop, "wb") if args.snoop else None  # noqa: SIM115 - lives as long as the bridge
        try:
            # The emulator also streams its other chips (Wi-Fi) at the packet streamer, which the Bluetooth-only
            # server refuses once per packet; that is noise here, and the guest's Wi-Fi goes without netsim.
            logging.getLogger("bumble.transport.android_netsim").setLevel(logging.ERROR)
            asyncio.run(serve(args, snoop_file))
        except (KeyboardInterrupt, asyncio.CancelledError):
            pass  # SIGINT or SIGTERM: serve() has reset the controller already
        finally:
            if snoop_file:
                snoop_file.close()
            give_back(args.controller)
    else:
        ctl(args)


if __name__ == "__main__":
    main()
