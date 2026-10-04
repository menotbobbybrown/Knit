---
id: "2026-10.nxfb"
slug: a-blob-is-not-asked-of-a-peer-streaming-to-us
title: "A blob is not asked of a peer streaming to us, and a fed file stays in flight while its link keeps feeding"
date: 2026-10-03
topics: [mesh, attachments, blob-exchange, bluetooth]
---

# ADR 2026-10.nxfb — A blob is not asked of a peer streaming to us, and a fed file stays in flight while its link keeps feeding

Status: Accepted (2026-10-03). Work item knit/knit-next#121. Amends ADR 2026-09.4tx5: its two reads of the link
stay, and each gains the case the Bluetooth stack's buffer opened between them. No wire change, no DB change.

**What was observed.** During the #117 trial on 2026-10-03 the Pixel 9 served the same photo to the same peer twice,
over and over: four P9 → P8 DM photos twice each, four photos to the P3, two to the P7. The issue first read it as
the P8 dropping blobs it held. The logs the P7 kept show otherwise. On the host clock, the P9 finished feeding
`105d82b9…` at 16:53:11.1 and served it again at 16:53:12.3, and the P7 read the first copy's header at 16:54:44.1.
It finished feeding `6eb69b5e…` at 16:59:31.4 and served it again at 17:00:14.7, and the P7 read the first header at
17:00:15.1. Each time, the P7 asked again before a byte of the first copy had reached it.

ADR 2026-09.4tx5 keeps a second copy off the link with two reads of it, and the stack's buffer sits between them:

- `FramedLink` counts a serve in flight from the enqueue to the end of its own feed, when `FILE_END` is handed to
  the socket, not to the peer. The 45 s serve memo is stamped at the enqueue, so a serve that waited behind other
  files has outlived it by the time its feed ends.
- The asker sees a file arriving only once its header is read, and a link carries one file at a time. The 60 s tick
  asks for every missing hash that is not arriving, and that includes every file queued behind the one streaming in.

When the feed outpaces the drain, the stack holds a whole photo or more (#117 measured hundreds of KB). The holder
finishes feeding H while the asker is still reading the photo ahead of it, and the asker's next tick asks for H.
An ask can also be late on its own: an asker that is itself feeding the holder a file queues its asks behind those
bytes, and they land after the holder's feed of H has ended. ADR 2026-09.4tx5's device check sent one photo at a
time, which never queues a second file behind the first.

**What changed.** Both are still reads of the link.

1. **The asker.** `BlobExchange.onNeighborAdded` (the tick's re-ask) sends nothing to a peer while a file from that
   peer is streaming in, read through the new `MeshTransport.fileArrivingFrom` (a `FramedLink.rxInProgress` on
   either plane). A link delivers in order, so whatever that peer has already fed us is behind the file arriving now.
   This works against any holder, including older builds and the iOS port. `want`, the first ask, is unchanged.
2. **The holder.** `FramedLink.hasPendingFile`, which `fileInFlightTo` reads, stays true for a file the link has fed
   in full while it goes on feeding that peer a later one. The stack delivers the later file behind it, so the
   earlier one has not provably landed. The set empties when nothing is queued or streaming, and a later busy spell
   does not bring an earlier one's files back. This covers the late ask and askers on older builds.

Each covers a gap the other leaves. The asker's read cannot help when the holder has gone idle with its last file
still in the stack. The holder's read cannot help with a holder that does not run it, or with a late ask that lands
after the holder has gone idle.

The alternative a reader reaches for first is a TTL on the memo after the feed ends. ADR 2026-09.4tx5's trap rules it
out: the drain is the receiver's controller's to decide, and no constant covers a stack buffer. The exact fix is a
`blobreq` field naming how many files the asker has read on the link, refused while the holder's serve is further
along. It needs an additive wire field, golden vectors, a paired iOS change and per-link counters across two planes,
for what the two reads above already catch in every logged case. Not taken.

**What it costs.** A re-ask to a busy neighbour waits until its stream ends, including the hop-by-hop walk through a
holder pulling on our behalf. Anything that holder would serve now queues behind that stream anyway. A genuine loss
on a busy link (a copy that failed its hash, a full disk) is re-served once the link goes idle rather than at once.
What it does not cover: a late ask that lands after the holder has gone idle, while its last file is still in the
stack. Since ADR 2026-10.8jwn the feed sits under the drain by default and the stack stays shallow, so this is the
overfed case, an older build, or the iOS port. The trap: `fileArrivingFrom` is about the peer, not the hash. Reading
`arrivingFiles` for the peer's other files would miss everything queued behind the one streaming.

Kept true by `BlobExchangeTest.theTickDoesNotReAskAPeerWhoseFileIsStreamingIn`,
`FramedLinkTest.aFileFedWhileTheLinkGoesOnFeedingThePeerStaysPendingUntilTheLinkIsIdle`, and
`AttachmentLabTest.aPictureStillInTheHoldersStackIsNotAskedForAgainWhileTheOneAheadOfItStreamsIn`. The lab scenario
parks Alice's second picture in a new `LabTransport.stackFiles` mode, which models a holder that cannot see its own
stack: neither arriving at Bob nor in flight at Alice. It runs Bob's tick through `LabNode.reoffer` past the serve
memo. Each test fails without its half of the change. The device oracle is the holder's `file route:` line, the
serve decision, against the asker's `rx ATTACHMENT/<hash> … ←` header on a host-aligned clock: one route per hash
per peer.

**Device-verified 2026-10-03, 21:13–21:20,** on this change installed on the P9 and the P7. The P9 sent the P7 three
~700 KB photo DMs back to back, with Wi-Fi Aware off on both, `…debug.PHY --ei filePace 57344`, and the P9 also
serving the P8 and the P3 as carriers. The P9 finished feeding `15b360e3…` at 21:14:16.1 and the P7 read its header
at 21:15:38.9, 83 s later. That is the gap in which the parent build served a second copy within 1–43 s. The P9 routed
each picture to the P7 once and to the P8 once. The P3 runs an older build without the asker's half. It was served
`781b0b19…` again at 21:16:27.6, 19 s after the P9's link to it went idle with that picture still in the stack. That
is the case this ADR does not cover, on an asker that does not run it.
