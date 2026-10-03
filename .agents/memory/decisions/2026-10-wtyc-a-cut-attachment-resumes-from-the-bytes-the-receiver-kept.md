---
id: "2026-10.wtyc"
slug: a-cut-attachment-resumes-from-the-bytes-the-receiver-kept
title: "A cut attachment resumes from the bytes the receiver kept"
date: 2026-10-03
topics: [mesh, attachments, wire, blob-exchange]
---

# ADR 2026-10.wtyc — A cut attachment resumes from the bytes the receiver kept

Status: Accepted (2026-10-03). Work item knit/knit-next#116. Amends ADR 2026-09.4tx5 (an ask may name an offset
and the holder streams only the rest; the fresh-ask rule and the two link reads are unchanged) and ADR
2026-10.y9qh (a resumed stream's `ArrivingFile` starts at its offset; `size` keeps its meaning). Two additive
fields, no capability bit, no version bump, no DB change; two vectors added, none moved. The iOS companion change
is owed (knit-ios #2); until it lands, the iOS port takes and serves whole files as before. Device-verified on
2026-10-03 (Pixel 9 → Pixel 7, the last paragraph).

**What was observed.** A link that dropped mid-attachment threw the receiver's bytes away: `FramedLink.close()`
deleted the temp file. The want survived (ADR 2026-09.ptv8 re-asks at every link-up and on the 60 s tick) and any
holder could answer it (4tx5), but every answer streamed from byte 0: `BlobReqContent` named only the hash, and the
`FILE_HEADER` carried no offset. On the 2026-10-02 walk, a Coded link between the Pixel 9 and the Pixel 7 dropped at
−96 (`bt phy 24sd5j… link dropped on CODED rssi=-96 (eof)`), and a 204 KB photo needed about three minutes over
the Coded link to the Pixel 8 (#114). A drop like the first one, partway through a transfer like the second, lost
all of it; at the edge of range, or between two people walking in and out of it, a large attachment could fail
forever while both phones paid for the airtime. No lab scenario covered a cut transfer.

**What changed.**

1. **The wire.** `BlobReqContent.offset` names how many bytes of the blob the asker kept; null is omitted from the
   CBOR (`encodeDefaults = false`), so a plain ask is byte-identical to an older build's. `FileHeaderWire.offset`
   echoes it on the stream that answers. The link JSON encodes defaults, so the field carries
   `@EncodeDefault(Mode.NEVER)`: a null is never written, and a whole-file header is the one every build has always
   sent (`fileHeader` did not move). `size` keeps its meaning (WIRE_COMPAT rule 2) — the bytes that follow — so the
   whole is `offset + size`. Unlike the size, the offset says where the bytes go: one that will not decode refuses the
   file (`FileHeaderCore` carries it, so a bad *size* still costs only the label). A holder serves from an offset in
   `1..length` — inclusive, so a prefix that is already complete (a cut between the last chunk and the end) gets an
   empty tail — and a whole file for anything else. A receiver refuses a resumed header for an avatar, a malformed
   key, an offset outside `1..ceiling`, or one past what it kept; an explicit `0` reads as a whole file. No capability bit: only a build that reads the
   header's offset asks with one, and a holder that predates it (an older Android build; the iOS port, whose
   `CBORFields` skips the key) serves the whole file, which lands as it always has.
2. **`PartialBlobs`**, one store for both radios and `BlobExchange` (a Koin `single`). A cut attachment's temp file
   is renamed in as `<hash>.part`, and the next ask names its length. **Session-scoped**: `MeshManager.start` purges
   it beside `MeshBlobStore.clearTransfers`, because room photos, avatars and group photos cross the link as
   plaintext and the blob layer keeps no plaintext on disk past the transfer — a resumed transfer is one transfer
   spread over several links, so a prefix lives until the blob lands, an hour after its last progress, or the end
   of the session. **Immutable once kept**: a longer prefix replaces a kept one by rename, so a reader with it open
   (the copy runs outside the store's lock) reads a file nothing writes to. **Keep-the-longer**: a whole-file stream
   never discards a kept prefix — an older holder's short attempt must not cost an 80 % prefix someone else
   delivered. **Epochs**: a drop retires every copy built on the dropped prefix, so a sibling copy cut after its
   twin failed the hash check cannot bring the bad prefix back. Bounded at 8 files and 32 MiB, oldest first; under
   16 KiB is not worth a file; names come only from well-formed content hashes.
3. **`FileIntake`**, FramedLink's receive side pulled out (knit-ios's `FileIntake` is its twin). FramedLink calls it
   under one per-link lock — the chunk write included, so a `close()` from the transport's thread never races a
   write on the stream it closes. The resume's prefix copy (up to 8 MiB) runs outside the lock and is installed
   after, or given up if the link closed meanwhile; a `closed` flag stops records still buffered behind the socket
   (neither `LinkSocket` closes its input) from opening a temp after `close()`. A cut keeps an attachment's prefix;
   a ceiling or write abort never does, and a replacing header still discards (a sender that moves on abandoned the
   file — not a cut, and the iOS port does the same). Finalize writes a unique `attach-<hash>-<n>.<ext>`: two links
   finishing one blob (a clique's first ask reaches every holder) no longer copy over the file the other's hash
   check is reading.
4. **The sender.** `streamFile` opens the source before the header goes out — a source that has gone costs that
   file, not the link, as an `IOException` used to — positions the channel at the offset (`skipNBytes` is API 33),
   and declares the tail as the size. `CompositeMeshTransport`'s 128 KiB bulk gate reads the bytes still to send.
   `MeshBlobStore.fileFor` writes its `blobtx/` copy aside and renames it in: two serves of one blob raced there, and
   a holder streaming a half-written copy would cost a receiver the good prefix it spliced the tail onto.
5. **`BlobExchange`.** Both asks (`want`, `onNeighborAdded`) carry the kept length; `onRequest` serves from the
   asker's offset; `onReceived` drops the prefix when the blob is stored, and when a *spliced* file fails its hash it
   drops the prefix and asks every neighbour for the whole blob at once (`reaskFromZero`) — only when the drop
   removed a live prefix, so a peer cannot trigger the broadcast without first planting one, and never while the
   bytes are arriving (4tx5). A whole file that fails is the holder's bytes alone: the prefix stays and the tick asks
   as before. The serve memo is unchanged: the holder that sent the bad tail answers on its next tick, other holders
   at once. A memo that let a lower offset through would hand an unsigned asker a way around the flood bound.
6. **Logs and counters.** ` from <offset>` rides last on the holder's `file …` line and the receiver's `rx …` lines,
   so 4tx5's `file ATTACHMENT/<hash>` serve count and every parse before it hold; the store logs each prefix kept,
   dropped, evicted or refused as stale. Three counters — tails served, tails taken, splices refused — live in
   `MeshMetrics.Snapshot.files` (the bridge's STATE: `filesResumedOut`, `filesResumedIn`, `splicesRefused`). The
   flat `Snapshot` had reached the JVM's 255-slot method limit (a `Long` takes two slots, and one field more is a
   `ClassFormatError` at class load), so it was split into groups to take them.

The alternatives. The issue's option 2 — keep the bytes for the same holder over the same link generation — puts
nothing on the wire beyond the offset but covers only a link that comes straight back to the same phone, the
opposite of the walk. Option 3 — pieces with hashes of their own — changes how attachments are addressed and stored
on both platforms. A hash of the kept prefix in the ask would catch a bad prefix before the tail crosses; it costs a
second field, up to 8 MiB of hashing per resumed serve and the iOS port's implementation, and the whole-file SHA-256
the store already checks catches the same thing one tail later. Keeping prefixes across a restart breaks the
plaintext rule above, and the issue's case is a link drop, not a restart. Discarding the prefix at a whole-file
header (the issue's wording) loses a long prefix to an older holder's short attempt. An fsync before a prefix is
kept was planned and dropped: with session-scoped prefixes a power loss can tear one only across a reboot, whose
mesh start purges it, and the sync would land on whichever thread closes the link — the main thread, on an
adapter-off broadcast.

**What it costs.** Up to 32 MiB of cache within a session, and a plaintext window that now spans one resumed
transfer instead of one link's. *Metadata cost*: the offset tells each asked neighbour how much of the blob the
asker holds — progress, never content. A spliced file that fails costs one tail and a broadcast ask. What it does
not cover: a resume across a process restart; the spool plane (`ScopeAttachments` keeps its own path, and its
partial downloads stay on the roadmap); the holder's serve memo, stamped at the enqueue, still holds the same
holder's next serve up to 60 s on a link that flaps faster; and a `link-rx-*.tmp` a process death leaves behind
still leaks (only the prefixes' directory is purged). The iOS companion owes: ask with the kept length, serve from
an offset (its `OutboundFile` already reads by offset), keep prefixes with these rules, and the two new vectors —
knit-ios's `thereAreFiftySevenVectors` fails at its next vector sync by design. The traps: `size` is the tail's,
never the whole; a kept prefix is never written to in place (copy it, as `FileIntake.Resume.copy` does — reopening
a temp file truncates it, and a splice of the tail alone fails the hash every time); and a new receive path goes
through `FileIntake` under the link's lock. Kept true by `PartialBlobsTest`, `FileIntakeTest`, `FramedLinkTest`
(`anAttachmentCutByTheLinkClosingIsResumedByTheNextLinkAndLandsWhole`, `aHeaderReadAfterCloseOpensNoFile`, the
offset sends), `LinkFramingTest` (the offset round trip, the byte-identical whole header, the bad offset),
`BlobExchangeTest` (the asks, the serve bound, the failed splice), `CompositeMeshTransportTest.aResumeWhoseTailIsSmallNeverArmsTheFastPlane`,
the `blobReqContentResumed` and `fileHeaderResumed` vectors, and `AttachmentResumeLabTest`: a 1 MB picture cut at
400 000 and 800 000 bytes by two holders with exact serve lists, an offset-ignoring holder, and a bad prefix that
fails the real `MeshBlobStore` check. The lab drives the receiver's real `FileIntake`
(`LabTransport.streamFiles`, cut by `lab.unlink`), so it runs the production splice.

**The device trial (2026-10-03).** Debug builds of this branch on the Pixel 9 (holder) and the Pixel 7 (receiver),
NAN off on both. A 701,716 B photo was cut from the holder's side after 32,768 B had arrived. The Pixel 9 relinked
and served only the rest (`file ATTACHMENT/82c382d1… 668948B in 23335ms @28672 → 24sd5jd4… from 32768`), and the
Pixel 7 took it (`rx ATTACHMENT/82c382d1… 668948B ← ijeeg44p… from 32768`, its end line 60 s later). The prefix was
dropped once the blob was stored, and STATE read `filesResumedOut` 1 on the holder and `filesResumedIn` 1,
`splicesRefused` 0 on the receiver. An earlier cut (34,816 B kept) was answered first by the lab's iPhone
(`dlrr2hzn…`, knit-ios `feat/blobs`). It ignored the offset and sent the whole file, which landed whole and took the
prefix with it: the mixed-version fallback, on hardware.

Two traps for a re-run:

- Every neighbour the receiver asks also fetches the blob, because an ask for a blob not held is a want, so a carrier
  can answer the re-ask first. Empty the holder with `BLECAP 0`, then cap it at one link (`BLECAP 1`): its next dial
  picks the strongest peer. Keep it off after the cut until its 45 s serve memo lapses.
- A cut before the first whole 16 KiB `FILE_CHUNK` record keeps nothing. A holder feeding three links at once had
  delivered less than one record after 8.6 s.
