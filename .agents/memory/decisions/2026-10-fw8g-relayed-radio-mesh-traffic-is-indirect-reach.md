---
id: "2026-10.fw8g"
slug: relayed-radio-mesh-traffic-is-indirect-reach
title: "Relayed radio-mesh traffic is indirect reach"
date: 2026-10-02
topics: [mesh, ui]
---

# ADR 2026-10.fw8g — Relayed radio-mesh traffic is indirect reach

Status: Accepted (2026-10-02)

**What was observed.** The Pixel 9 was getting DMs from the iPhone and getting its receipts back, but its
Diagnostics listed the iPhone under neither *Directly connected* nor the relay heading, only as Known. The log
explains it. The P9 had a BLE link to the iPhone from 18:22 until a pause at 18:57. After the resume at 19:00, the
iPhone app was in the background, so its GATT payload could not be read. The P9 forgot both of its addresses within
a minute (`bt gatt forget … (quiet)`). Since then every frame of the iPhone's arrived relayed by the two Pixels that
still held links to it: `fast-frame from dlrr2… id=BH3s… hop=24sd5…`, and the same frame again with `hop=hzwyq…`.
None of the three tiers ADR 2026-09.2ajk drew could hold that.

- *Direct* is `neighbors`: our own radio sighted the peer's.
- The relay tier was LoRa air reach plus spool presence.
- Neither BLE nor Wi-Fi Aware `reachable` names anyone our radio has not sighted itself.

The relay heading was also misread as "anything relayed". It was renamed *Reachable long-range* the same day
(d5178e6f), and its row tag `Relay` became `Internet`.

**What changed.** There is now a fourth tier, `Reach.Indirect`, shown as **Reachable indirectly**, between Direct and
Long-range. The enum value `Reach.Relay` became `Reach.LongRange` to match its label.

The evidence is `mesh/RelayedPresence`, the third author-presence tracker, shaped like the other two (LoRa's
`noteReachable`, the spool's `peerSeenAt`).

- `InboundPipeline.onDeliver` hands it every frame right after `verifyInbound` (`noteVerified`).
- It stamps the author with our clock and the hop that delivered the frame.
- `indirectPeers` applies the 45-minute `PRESENCE_LINGER_MS` at read, with the reader's clock, so a sweep that runs
  late after the CPU slept never shows a stale peer.
- `MeshManager` sweeps it on the 60 s metrics tick to bound memory. It closes and clears it on `stop()`, which
  covers a pause, under the lock every write takes, so a frame the cancelled session was still verifying cannot
  land after the clear.
- It reaches the UI as `MeshController.heardIndirectly`.

A frame counts only when all of these hold (`isRelayedRadioEvidence`):

- **Not LoRa and not a spool source.** Both planes have their own tracker.
- **The author is not the hop.** A frame its author handed us directly is a link, and `neighbors` already covers
  that. This rule also drops every path that cannot name a hop: a BLE side page and a NAN fast-frame with an unknown
  hop both report the author as their source, as do the `MeshManager` custody replays and `typing`.
- **Signed.** The unsigned tick door and an unchecked `blobreq` name a sender nobody has verified yet.
- **Fresh: `PRESENCE_FRESH_MS` for every type, profiles included.** This is stricter than `isPresenceEvidence`'s 13 h
  profile window, and on purpose. On the radio mesh, other people's profiles are handed over on every new link
  (`KeyExchange.serveKey`, custody re-serves), so a 13 h window would revive anyone whose profile a neighbour still
  carries.

On the screen:

- Rows are tagged `via <hop's name>`, not BLE/NAN, because on a Diagnostics row those tags mean "this peer's own
  radio".
- The dot is between the filled Direct dot and the faded Long-range one.
- The Profile status line reads *Reachable indirectly*, because both surfaces still derive from `reachOf`.
- `…debug.STATE` reports the set as `indirect` (`{nodeId, name, via, agoMs}`).

Indirect ranks above Long-range. An indirect peer is a few radio hops out and sending live, signed traffic. A
LoRa entry is keyed on an author a gateway may be relaying from anywhere, and a spool stamp says nothing about
distance.

**The alternative a reader reaches for first** is to fold relayed authors into `MeshController.reachable` and let
the existing relay tier show them. Two things rule it out:

- `reachable` is not presentation-only. `AckSync` reads it as "can a tick go over the air to this author" (ADR
  2026-09.y5f3), so a peer two BLE hops out would start drawing LoRa-shaped decisions.
- The row would then claim a long-range plane for a peer that only ever reached us over BLE.

The other alternative is a real route table: who is how many hops out, through whom. That is the thing this mesh
deliberately does not have. A pure flood network learns who carried the last frame, not where the next one will go.

**What it costs and does not cover.**

- *Relayed BLE side pages are invisible to it.* A page has no hop identity, so a peer reached only that way stays
  Known. The same is true of a NAN fast-frame whose hop handle is unknown.
- *An idle peer drops out.* Two hops away, a peer that sends nothing emits nothing relayable except its 12 h profile
  republish. So the tier means "heard its live traffic within 45 minutes", not "it is there".
- *The linger over-claims.* A peer that walks away still reads indirect for up to 45 minutes after its last relayed
  frame, as on the other two planes.
- *The wear map does not show it.* Its 4-bit per-peer plane nibble has no honest encoding: BLE/NAN bits would draw
  the peer in the near ring, and the layout is golden-pinned (ADR 2026-09.wetm). The `far` count is unchanged.
- *Every `neighbors` consumer is unchanged*, on purpose: the notification count, the Contacts dot, the chat-list
  status row, Your mesh, the group member picker. None of them claims more than proximity.

**The trap.** This is presence, never a route. Nothing in `mesh/` may read `heardIndirectly` to choose where a
frame goes, whether to custody it, or whether to send a tick. If a delivery path wants it, that is a routing table
being built on evidence that was never meant to carry one. Like the other two trackers, it sees a frame only after
the gate decided, and decides nothing itself.

Regressions:

- `RelayedPresenceTest` (every exclusion, the read-time linger, the cap).
- `DiagnosticsViewModelTest.aPeerAnotherPhoneRelayedIsIndirectAndNamesItsHop`.
- `ProfileDetailsViewModelTest.presenceClimbsFromKnownThroughLongRangeAndIndirectToDirect`.
- `ProfileDetailsScreenContentTest.anIndirectPeerReadsReachableIndirectly`.
- `IndirectReachLabTest` (the ends of a line hear each other via the middle; the middle hears nobody second-hand).
