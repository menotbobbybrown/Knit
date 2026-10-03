---
id: "2026-10.y9qh"
slug: a-receiver-shows-how-far-an-incoming-attachment-has-got
title: "A receiver shows how far an incoming attachment has got"
date: 2026-10-03
topics: [mesh, attachments, wire, ui]
---

# ADR 2026-10.y9qh — A receiver shows how far an incoming attachment has got

Status: Accepted (2026-10-03). Work item knit/knit-next#115, prompted by the Coded walk recorded in #114.
Amends ADR 2026-09.4tx5: `arrivingFiles()` now carries how far each file has got. An additive link field, no
capability bit, no version bump, no DB change; one vector added, none moved. Device trial owed.

**What was observed.** While an attachment streams in, its bubble drew the `WaitingIndicator` (a spinner that
settles to an hourglass after 30 s, #72) and "Photo appears once a device that has it is reachable". On
2026-10-02 a 203,807 B photo took about three minutes to cross a Coded link (#114), and the receiver could not
tell a slow transfer from a stalled one — nor, worse, from one no device was sending: the copy said nobody had
it while bytes were arriving. The link already counted them (`FramedLink.rxBytes`) and already named the file
(`rxKey`, ADR 2026-09.4tx5). Two things were missing. The receiver never learned the total: the `FILE_HEADER`
carried only kind, key and mime. `MessageContent.attachmentSize` (ADR 2026-09.qq2r) is not that total — it is the
plaintext length, set for files only, and the link carries the stored blob, sealed for DMs and groups (12 B IV,
16 B tag on top). And nothing reached the UI: `arrivingFiles()` was a set of keys, and `MeshController`
exposed no file state.

**What changed.**

1. **`FileHeaderWire.size`**, the sender's byte count for the stream, filled from `item.file.length()` in
   `streamFile`. The link JSON is `ignoreUnknownKeys = true, encodeDefaults = true`, and has been since before
   v2.0.0: every shipped Android build skips the key, and the iOS port's `Decodable` header does too (its
   `LinkTests` pin an unknown key; it discards every file record anyway, attachments being out of its MVP, so
   no companion change is due — when it takes attachments it writes `size`, and the `fileHeader` record in
   `vectors/keyed-v1.json` pins the bytes). WIRE_COMPAT's rule 1 assumes a null is omitted; this config writes
   `"size":null`, so the field is additive because older readers skip it and the sender always fills it. A
   header whose `size` will not decode (a string, a fraction, out of range) is read without it
   (`LinkFraming.decodeFileHeader` falls back to `FileHeaderCore`): a failed header decode aborts the file
   behind it, and a label must never cost the transfer. No capability bit: the size gates nothing, trusts
   nothing and hides nothing — it is a label, never a bound, exactly as `attachmentSize` is. The receiver
   still counts the bytes and holds its own 8 MiB ceiling, takes a total only in `1..MAX_INCOMING_FILE_BYTES`,
   and shows a sender that overruns its own total by the count alone (`ArrivingFile.fraction` is null).
2. **One published snapshot per link.** `FramedLink.rxFile` (`ArrivingFile`: key, bytes in, total) replaces
   the volatile `rxKey`/`rxInProgress` pair, which become reads of it — one reference, so a reader never pairs
   one file's key with another's count. The reader loop sets it at the header, replaces the count after every
   chunk written, and clears it at the end, an abort, a replacing header and `close()`. The chunk update is
   `updateAndGet { it?.copy(…) }`, so the null `close()` writes from the owner's thread is never undone; every
   transport drops a link from its map before closing it, so a late write on a closed link was invisible
   anyway. Two receive-side log lines: `rx KIND/<hash> <size|?>B ← node` at the header (the device oracle
   that the total crossed) and `rx … <bytes>B in <ms>ms ← node` at the end (how long the bytes took on air;
   the sender's `file …` line times only its feed, 7 s against three minutes in #114). The verb is `rx`, not
   `file`, so a bare `file <KIND>/<hash>` grep still counts serves only (4tx5's device check), and the key is
   logged only once it reads as a content hash — it is the peer's until finalize checks it.
3. **`arrivingFiles(): Map<String, ArrivingFile>`**, aggregated per transport and through the composite with
   `furthestByKey()` (two links streaming one blob show the copy furthest along). Still a pull and still a read
   of the link: `BlobExchange` reads only the keys (`hash in map`), and 4tx5's rule — "is it on the way" never
   becomes a memo with a TTL — covers "how far has it got" the same way.
4. **`MeshController.arrivals`**, a cold poll (`arrivalTicker`): every 500 ms while anything is streaming in,
   every 2 s while nothing is, equal samples dropped. A link raises no event per chunk and a Wi-Fi Aware link
   moves hundreds a second, so the UI samples rather than listens. `ChatViewModel.arrivals` collects it only
   for the window's attachments still on their way — shown, not held, not a link card — read off the one shared
   `heldSizes` (ADR 2026-09.fjcw's idiom, no second blob query), so a settled thread holds no subscription and
   never polls. It is a side value like `voicePlayback`, kept out of the five-flow `state` combine so a ring
   moving twice a second rebuilds no rows; `ChatScreen` looks it up per row at the bubble, so only the moving
   bubble recomposes.
5. **The bubbles.** `ArrivalIndicator` is shared by the photo, voice and file bubbles: a plain determinate
   ring easing to each sample on `KnitMotion.effects()` when there is a total, a still download glyph when
   there is none, the `WaitingIndicator` before any link carries the bytes. "84 kB of 204 kB" (or "84 kB
   received") replaces the "appears once a device that has it is reachable" line, and in the file bubble the
   size label and "Waiting for the file"; the relay line (ADR 2026-09.vej5) is dropped while arriving, since
   where else the bytes could come from has nothing to add while they are coming.

The alternative a reader reaches for first is carrying the size in the message, as `attachmentSize` already
is for files. It would be a sealed field for DMs and groups and a new cleartext one on the room's
`ChatContent`, cost bytes on every frame including LoRa's, still say nothing for avatars and group photos, and
give the wrong number for a sealed blob. The link header is where both numbers already live. The second is
showing the count alone with no wire change: no share, so no ring and nothing to read a stall against. The
third keeps `arrivingFiles()` a set and adds a second read beside it; two reads of one fact can disagree, and
4tx5 is one read of the link.

**What it costs.** One JSON key per file header, about 15 bytes. A poll of a few volatile reads every 500 ms
while an attachment shown on screen is streaming in, every 2 s while one is shown but nothing streams.
Nothing animates without end: the ring's motion is finite (ADR 047) and the glyph is still. What it does not
cover: attachments pulled from a relay (`ScopeAttachments`) keep the spinner — they take another path; a
sender on an older build gives the count without a total; and `FILE_END` clears the entry before the blob is
finalized, re-hashed and stored, so for a few tens of milliseconds the bubble can fall back to its waiting
line before the picture shows. Moving when "arriving" ends would change 4tx5's semantics for one frame of
polish; if the device trial shows the flash, that is a follow-up. When two holders stream one blob and the
faster finishes first, the merged count steps back to the slower copy. The trap: the total is the peer's
claim — nothing may size a buffer, cap a stream or decide a stall on it. Kept true by `LinkFramingTest` (the
old shape, the bad sizes, an older decoder reading the new header), `FramedLinkTest` (`rxFile…`, the ceiling
abort, the replacing header, the close), `ArrivingFileTest` (the share, the merge, the cadence),
`CompositeMeshTransportTest.arrivingFilesIsTheUnionAndTheCopyFurthestAlongCounts`,
`ChatViewModelTest.arrivalsAreReadForTheWindowsAwaitedAttachmentsOnly`, `ArrivalIndicatorTest` (the photo,
file and voice bubbles in every state, each arriving case ending idle),
`ChatRelayIndicatorTest.aPhotoStreamingInDropsTheRelayLine`, the `fileHeader` vector in `KeyedVectorTest`, and
the three arriving previews' screenshots.
