---
id: "2026-10.8jwn"
slug: a-bluetooth-file-feed-splits-one-pace-budget-across-the-links-it-feeds
title: "A Bluetooth file feed splits one pace budget across the links it feeds"
date: 2026-10-03
topics: [ble, mesh]
---

# ADR 2026-10.8jwn — A Bluetooth file feed splits one pace budget across the links it feeds

Status: Accepted (2026-10-03). Work item knit/knit-next#117. Replaces the 28 KiB/s per-link pace that predates the
ADRs (8aab750d, 2.1.0) for links on 1M and 2M; a link on Coded keeps the pace ADR 2026-10.yvn6's 2026-10-03
amendment gave it (#114). No wire change, Android only. Device-measured on three Pixel pairs on 2026-10-03.

**What was observed.** `CodedPhyPolicy.pace` fed every Bluetooth link not on Coded at `BLE_PACE_BYTES_PER_SEC`,
28 KiB/s in 16 KiB chunks. Its KDoc said that stayed below L2CAP throughput and told the next person to tune it
against the sender's `file … <n>B in <ms>ms @<pace>` line. That line times the hand-off to the stack, not the air:
the stack's queue takes hundreds of KB before a write blocks, so the line reads size ÷ pace whatever the air does.
The receiver's `rx … in <ms>ms` line (ADR 2026-10.y9qh) times the air, and on the #116 trial it showed a 1M link
from the Pixel 9 to the Pixel 7 draining 6.5–11.2 KB/s. Every frame written after a photo's bytes waited behind
them.

The trial for this ADR (`…debug.NANOFF` on every phone, `…debug.BLECAP` and `…debug.PAUSE` to keep one pair alone
where it could, a ~700 KB photo DM, then DM texts at +5/+15/+30/+60 s timed on the receiver; a text with no
transfer running took 0.5–1.1 s):

| P9 → P7, 1M, link RSSI −51…−61 | photo across | drain | a text sent mid-photo waited |
|---|---|---|---|
| 28 KiB/s (the old pace) | 76–144 s | 4.9–9.2 KB/s | 50–55 s at +30 s, up to 85 s at +15 s |
| 16 KiB/s | 70–83 s | 8.5–10.1 KB/s | 7–21 s |
| 12 KiB/s | 74–77 s | 9.1–9.5 KB/s | ~6 s |
| 8 KiB/s | 85–117 s | 6.0–8.2 KB/s | 3.3–11.4 s |
| 6 KiB/s | 114–116 s (the feed) | kept up | 1.5–6.1 s |
| 4 KiB/s | 171–172 s (the feed) | kept up | 0.9–4.0 s |

The Pixel 8 drained 4.8–7.9 KB/s from the Pixel 9 and the Pixel 3 9.4–9.6 KB/s; at 28 KiB/s a text to either
waited 80–260 s. Overfeeding never bought a faster photo: the air is the limit, and the slowest drains of the day
(4.8 and 4.9 KB/s) came at 28 KiB/s, when the queue was deepest. A drain this low points at a long connection
interval, which Knit never asks to shorten (a follow-up, below).

Three links fed at once are worse again. On the #116 trial the Pixel 9 fed one photo to three phones, and one of
them had not received a single 16 KiB chunk after 8.6 s: a phone's links share one controller's air, so three
feeds at 28 KiB/s each asked for 84 KiB/s of it. A room or group photo is that case every time, because every linked
member asks for it at once.

**What changed.**

1. **One budget, split.** `PhyTuning.filePaceBytesPerSec` (default `BLE_PACE_BYTES_PER_SEC`, now 4 KiB/s) is the
   budget every link on 1M or 2M shares. `CodedPhyPolicy.pace(phy, tuning, feeding)` gives a link its share, the
   budget over `feeding`, and `BluetoothMeshTransport.paceFor` counts `feeding` as the links in `links` whose
   `txInProgress` is set and whose last-read PHY is not Coded, the asking one included. `FramedLink` reads its pace
   before every chunk, so a feed that starts or ends re-splits the others at their next chunk, and `PaceWindow`
   restarts its clock on the change. A Coded link keeps its own pace outside the split.
2. **A chunk is two seconds of the share**, clamped to 2–16 KiB (8 KiB alone at the default, 4 KiB for each of two,
   2 KiB from four up). Coded's default was already that ratio (2 KiB at 1 KiB/s). The writer drains frames between
   chunks, so a text written mid-file waits about one chunk on the feed side and one in the stack's queue.
3. **4 KiB/s** sits under every drain measured, with the slowest (4.8 KB/s) as the floor: about 85% of it. 6 KiB/s
   kept up with the Pixel 7 but would have outrun the Pixel 8's worst stretch.
4. **Tuning and its oracle.** `…debug.PHY --ei filePace N` sets the budget until the process dies, and its reply
   carries `fileChunk`. The transport logs `bt file pace budget=… feeding=… share=… chunk=…` when the split
   changes. Every KDoc that pointed at the sender's `file … in` line now points at the receiver's `rx … in` line.

**Verified on the device.** With this build on the Pixel 9, linked to the Pixel 7 and the Pixel 3 at once, a room
photo had both fetching (a carrier backlog kept the Pixel 3's link busy too). `bt file pace budget=4096 feeding=2
share=2048` fed each link at 2048 B/s, and DM texts to the Pixel 7 at +5/+30/+60 s waited 1.1–7.2 s over two reps.
The same run with `…debug.PHY --ei filePace 57344`, which feeds two links at the old 28 KiB/s each, made the same
texts wait 34, 192 and 281 s, and dropped a link. A link feeding alone at the default is `PaceConfig(4096, 8192)`,
the config the single-link table measured at 4 KiB/s.

**Not taken.**

- **A lower constant per link** (the issue's option 1). It fixes one photo to one phone, but three feeds at once
  still ask the controller for three times what it drains.
- **Receiver flow control** (windowed chunk acks in `LinkFraming`, the issue's option 2). It would track each pair's
  real drain, but it is a link record an older build or the iOS port would tear the link down on (an unknown record
  type is an `IOException`), so it needs a HELLO bit and a paired change. yvn6 set it aside for the same reason.
- **Pacing from the link's parameters** (option 3). The connection interval reaches the app only through the hidden
  `onConnectionUpdated`, on a GATT handle only the Coded experiment holds, and only when the parameters change after
  it registers.

**What it costs.** A photo now crosses no faster than the budget allows. A 700 KB photo to one phone takes about
171 s, where the old pace took 70–145 s on the air (and the texts behind it took as long); a pair that drains
10 KB/s is held to 40% of it. Two phones fetching it at once take twice that each. The budget is one number for
every pair, so the fastest pairs pay for the slowest; the sender cannot tell them apart without a wire change.

**Traps.**

- A feed whose writes stall (a peer gone quiet before its socket fails) keeps its share until the write fails.
- Incoming streams are not counted, though they use the same air. The trial did not measure how much an incoming
  file slows an outgoing one.
- Coded sits outside the split, yet S=8 spends about eight times the air per byte: one Coded feed at 1 KiB/s uses
  about a whole 1M budget's worth. The experiment is dark in release; count Coded against the budget before it ships.
- Tune against the receiver's `rx … in` line, never the sender's `file … in`.

**Follow-up.** Ask for a shorter connection interval (`CONNECTION_PRIORITY_HIGH` through a GATT client on the link's
ACL, as `BleDoorbell` asks for BALANCED) while a file streams, then raise the budget against the drain that buys. It
is the one lever that makes photos faster as well as chat live, and it costs power only while a file moves.

Tests: `CodedPhyPolicyTest` (the 4 KiB/s and 8 KiB default, the split by `feeding`, the two-second chunk and its
clamps, a count under one, Coded outside the split, unbounded at ≤ 0), `TransferPacePolicyTest` (a split change
charges the next chunk at the new share), and `FramedLinkTest` (a link counts as feeding every time it reads its
pace, and stops once the file is out or its source is gone).
