package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Callbacks a [FramedLink] raises up to its owning transport. Kept transport-neutral (no radio types) so
 * both [app.getknit.knit.mesh.wifiaware.WifiAwareTransport] and the Bluetooth transport supply an impl that
 * forwards into their own flows and teardown. [onLinkDown] may fire more than once for one link (both the
 * read and write loop can end), so implementations must be idempotent.
 */
interface LinkCallbacks {
    fun onInbound(frame: InboundFrame)

    fun onDigest(digest: ReceivedDigest)

    fun onFile(file: ReceivedFile)

    fun onLinkDown(nodeId: String)
}

/**
 * One live data-path link to a peer over a connected [LinkSocket] — the **transport-agnostic** per-connection
 * machinery extracted from the Wi-Fi Aware transport so the Bluetooth transport reuses it byte-for-byte. It
 * owns the [LinkFraming] record loops (frames + interleaved file streaming + store-and-forward digests) and
 * raises decoded results up via [LinkCallbacks]; it does **not** own discovery, admission, or teardown policy
 * (those stay in each transport — e.g. NAN's single-NDI quiescence supervisor reads [lastActivityAt]/
 * [linkStartedAt]/[rxInProgress]/[txInProgress] here to decide when to tear an ephemeral sync down; Bluetooth
 * keeps links persistent).
 *
 * Pure of Android: the wall clock is injected ([now]) and logging is a lambda ([log]) so the loops are
 * JVM-unit-testable over a piped [LinkSocket] ([app.getknit.knit.FramedLinkTest]). Callers pass
 * `SystemClock::elapsedRealtime` for [now] so the quiescence props share the supervisor's clock.
 */
@Suppress("LongParameterList") // a link needs its identity, socket, scope, cache dir, partials, metrics, callbacks, clock
class FramedLink(
    val nodeId: String,
    val peer: Peer,
    private val socket: LinkSocket,
    private val scope: CoroutineScope,
    private val cacheDir: File,
    // Where a cut attachment's prefix goes, and where a resumed one is spliced from (#116) — one store for every
    // link, so any holder can finish what another started.
    private val partials: PartialBlobs,
    private val metrics: MeshMetrics,
    private val callbacks: LinkCallbacks,
    private val now: () -> Long,
    // The file feed's pace for a slow shared channel (BLE L2CAP), read before every chunk so a link that changes
    // PHY mid-transfer is paced by the one it is on now, and one whose share of the radio's budget changes as other
    // feeds start and end takes the new share (#117); unbounded (Wi-Fi Aware NDP, and the default so existing
    // callers/tests are unchanged). See [TransferPacePolicy] and [PaceWindow] for the why.
    private val pace: () -> PaceConfig = { PaceConfig() },
    private val log: (String) -> Unit = {},
) {
    private val outbound = Channel<Outbound>(Channel.UNLIMITED)

    // The custody digest waiting in [outbound] to be written, if one is. Whoever swaps it from null queues one
    // [Outbound.DigestDue] and the writer empties it on reaching that marker, so at most one marker is ever
    // queued. [sendDigest] swaps a newer id set in here rather than queueing a second record.
    private val pendingDigest = AtomicReference<List<String>?>(null)
    private var readerJob: Job? = null
    private var writerJob: Job? = null

    // Quiescence bookkeeping for a transport's per-link supervisor (elapsedRealtime, via [now]).
    @Volatile
    var linkStartedAt = 0L
        private set

    @Volatile
    var lastActivityAt = 0L
        private set

    @Volatile
    var txInProgress = false
        private set

    // The receive side: one file streams in at a time, reassembled by [intake] (FileIntake, pulled out of here by
    // #116 so a cut attachment keeps its prefix and a resumed one splices onto it). Every call into it holds
    // [rxLock], which [close] takes too, so a teardown from the owner's thread never races the reader on the file
    // being written; the one call that can copy megabytes runs outside it ([beginRxFile]).
    private val rxLock = Any()
    private val intake = FileIntake(cacheDir, partials, nodeId, now, log)

    // Set by [close] under [rxLock]. The reader can still dispatch records already buffered behind the socket, and
    // a header among them must not open a file that nothing will ever clean up.
    @Volatile
    private var closed = false

    /**
     * The file streaming in right now, with the bytes in so far and the total its header declared — exactly "a
     * FILE_HEADER is in and no FILE_END yet". `BlobExchange` reads its key through `MeshTransport.arrivingFiles`
     * to keep a blob whose bytes are already on the way from being asked for again (a 60 s re-ask against a slow
     * BLE transfer bought a second full copy, work item #79); the chat reads its bytes and declared total to draw
     * the attachment's progress (#115). A torn link clears its own state.
     */
    val rxFile: ArrivingFile? get() = intake.arrivingFile

    /** The key (the content hash) of the file streaming in right now. */
    val rxKey: String? get() = rxFile?.key

    /** True while a file streams in: a transport's supervisor holds the link rather than tear it down mid-transfer. */
    val rxInProgress: Boolean get() = rxFile != null

    // Files queued behind an in-progress file transfer (only one streams at a time).
    private val stash = ArrayDeque<Outbound.FileSend>()

    // key -> how many sends of it are queued or streaming toward this peer, from the enqueue in [sendFile]
    // to the end of [streamFile]. [stash] is the writer loop's own, so this count is what the enqueue side
    // can see; `MeshTransport.fileInFlightTo` reads it so a re-ask never queues a second copy behind one
    // already on the link (#79). Guarded by [pendingLock]; touched on the enqueue and writer threads.
    private val pendingFileKeys = HashMap<String, Int>()
    private val pendingLock = Any()

    /** Starts the read + write loops. Stamps the quiescence window at link-up (a backfill will extend it). */
    fun start() {
        val t = now()
        linkStartedAt = t
        lastActivityAt = t
        // socket.input is already a stable buffered stream (LinkSocket contract) — the responder may have
        // read the HELLO from it, so do NOT wrap it again or those buffered bytes would be stranded.
        readerJob = scope.launch(Dispatchers.IO) { readLoop(socket.input) }
        writerJob = scope.launch(Dispatchers.IO) { writeLoop() }
    }

    /** Enqueue a mesh frame for delivery to this peer. */
    fun send(bytes: ByteArray) {
        outbound.trySend(Outbound.Frame(bytes))
        metrics.onBytesSent(bytes.size.toLong())
    }

    /**
     * Enqueue a store-and-forward custody digest ([LinkFraming.Type.DIGEST]) for this peer. A link holds at most
     * one digest not yet written: while one waits, [ids] replaces its id set where it stands in the queue
     * (ADR 2026-09.tjfb). A digest is a snapshot of custody's live ids, so the newer one says everything the
     * older one would, and a link busy with a back-fill writes one current digest when it gets there instead of
     * one stale digest per re-offer tick, each ahead of every frame queued after it. An idle link writes each
     * digest as it comes, so there the cadence and the bytes are unchanged.
     */
    fun sendDigest(ids: List<String>) {
        if (pendingDigest.getAndSet(ids) == null) {
            outbound.trySend(Outbound.DigestDue)
        } else {
            metrics.onDigestReplaced()
        }
    }

    /**
     * Enqueue a file (avatar or attachment) for streaming to this peer. Returns whether the enqueue was
     * accepted — the channel is UNLIMITED, so false means the link is already closed (the composite uses it
     * to fall back to another plane instead of silently losing the file).
     */
    fun sendFile(
        file: File,
        meta: FileMeta,
    ): Boolean {
        // Counted before the enqueue so a reader between the two never sees the file as neither queued nor
        // streaming; a refused enqueue (closed link) takes it straight back.
        notePending(meta.key, +1)
        return outbound.trySend(Outbound.FileSend(file, meta)).isSuccess.also { if (!it) notePending(meta.key, -1) }
    }

    /** True while a file under [key] is queued on, or streaming over, this link. */
    fun hasPendingFile(key: String): Boolean = synchronized(pendingLock) { pendingFileKeys.containsKey(key) }

    private fun notePending(
        key: String,
        delta: Int,
    ) = synchronized(pendingLock) {
        val n = (pendingFileKeys[key] ?: 0) + delta
        if (n > 0) pendingFileKeys[key] = n else pendingFileKeys.remove(key)
    }

    fun close() {
        readerJob?.cancel()
        writerJob?.cancel()
        outbound.close()
        synchronized(rxLock) {
            closed = true
            intake.cut() // an attachment cut mid-stream keeps its prefix for the next ask (#116)
        }
        synchronized(pendingLock) { pendingFileKeys.clear() } // whatever was queued died with the link
        socket.close()
    }

    /** Mark data-path activity so a supervisor's quiescence window resets (frames + file records only). */
    private fun touch() {
        lastActivityAt = now()
    }

    // --- Read side ---

    private suspend fun readLoop(input: java.io.InputStream) {
        try {
            while (scope.isActive && !closed) {
                val msg = LinkFraming.read(input) ?: break
                when (msg.type) {
                    LinkFraming.Type.FRAME -> {
                        touch()
                        handleFrame(msg.payload)
                    }

                    LinkFraming.Type.FILE_HEADER -> {
                        touch()
                        beginRxFile(msg.payload)
                    }

                    LinkFraming.Type.FILE_CHUNK -> {
                        touch()
                        appendRxFile(msg.payload)
                    }

                    LinkFraming.Type.FILE_END -> {
                        touch()
                        endRxFile()
                    }

                    LinkFraming.Type.DIGEST -> {
                        touch()
                        handleDigest(msg.payload)
                    }

                    LinkFraming.Type.KEEPALIVE -> {
                        // The record's arrival is the whole point; there is nothing in it to handle.
                    }

                    LinkFraming.Type.HELLO -> {
                        // Legacy record from an older peer. Identity was already consumed at accept, so
                        // ignore any stray.
                    }
                }
            }
        } catch (e: IOException) {
            log("read loop ended for $nodeId: ${e.message}")
        } finally {
            callbacks.onLinkDown(nodeId)
        }
    }

    private fun handleFrame(bytes: ByteArray) {
        val wire = WireCodec.decodeWire(bytes)
        if (wire == null) {
            metrics.onDropped(DropReason.DECODE_FAILED)
            return
        }
        val envelope = WireCodec.decodeEnvelope(wire.signed)
        if (envelope == null) {
            metrics.onDropped(DropReason.DECODE_FAILED)
            return
        }
        callbacks.onInbound(InboundFrame(wire, envelope, nodeId))
    }

    private fun handleDigest(payload: ByteArray) {
        val digest = LinkFraming.decodeDigest(payload) ?: return
        callbacks.onDigest(ReceivedDigest(nodeId, digest.ids))
    }

    // --- Write side ---

    /**
     * Drains the outbound queue to the socket. Frames are written immediately; a file is streamed
     * as header→chunks→end, but pending frames are flushed *between* chunks so a large blob never stalls
     * live traffic. Only one file streams at a time (later files wait in [stash]).
     */
    private suspend fun writeLoop() {
        try {
            val out = BufferedOutputStream(socket.output)
            while (scope.isActive) {
                val item = nextOutbound() ?: break
                when (item) {
                    is Outbound.Frame -> {
                        writeRecordSafely(out, LinkFraming.Type.FRAME, item.bytes)
                        out.flush()
                        touch()
                    }

                    Outbound.DigestDue -> {
                        if (writePendingDigest(out)) {
                            out.flush()
                            touch()
                        }
                    }

                    is Outbound.FileSend -> {
                        streamFile(out, item)
                    }
                }
            }
        } catch (e: IOException) {
            log("write loop ended for $nodeId: ${e.message}")
            callbacks.onLinkDown(nodeId)
        }
    }

    /**
     * Encodes and writes one record, but **drops** (rather than kills the link) a record the codec rejects —
     * e.g. a payload past [LinkFraming.MAX_PAYLOAD_BYTES], whose `require` throws *before* any bytes are
     * written, so the stream stays intact and the loop continues. A real socket [IOException] from the write
     * itself still propagates to end the write loop and bring the link down.
     */
    private fun writeRecordSafely(
        out: OutputStream,
        type: LinkFraming.Type,
        payload: ByteArray,
    ) {
        try {
            LinkFraming.write(out, type, payload)
        } catch (e: IllegalArgumentException) {
            log("dropping oversized $type record for $nodeId: ${e.message}")
        }
    }

    @Suppress("NestedBlockDepth") // open → header → (drain frames → read chunk → write chunk → pace) loop → end
    private suspend fun streamFile(
        out: OutputStream,
        item: Outbound.FileSend,
    ) {
        txInProgress = true // don't let a supervisor tear down mid transfer
        val startedAt = now()
        val meta = item.meta
        var cfg = PaceConfig() // read before every chunk below; the window rebases onto the first
        val window = PaceWindow(cfg, startedAt)
        try {
            // Opened before the header goes out: a source that has gone costs this one file, not the link, and the
            // offset is checked against the bytes actually there.
            val input = openSource(item.file, meta) ?: return
            input.use {
                val length = input.channel.size()
                // A resume (#116): the asker holds the bytes before the offset, so the stream starts there. Up to the
                // whole length — a prefix that is already complete gets an empty tail and its end — and anything
                // else is a whole file. FileInputStream reads from its channel's position.
                val offset = meta.offset.takeIf { it in 1..length } ?: 0L
                if (offset > 0) input.channel.position(offset)
                val bytes = length - offset
                // The stream's length rides the header so the receiver can show how far it has got (#115), and so
                // does the byte it starts at when it is the rest of a cut transfer.
                val header = FileHeaderWire(meta.kind.wire, meta.key, meta.mime, size = bytes, offset = offset.takeIf { it > 0 })
                LinkFraming.write(out, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(header))
                val buf = ByteArray(LinkFraming.FILE_CHUNK_BYTES)
                while (true) {
                    // Interleave live frames between chunks — and flush them NOW so they ride the pace gap ahead
                    // of the next chunk instead of trailing behind it in the socket buffer.
                    if (drainFramesInto(out)) out.flush()
                    cfg = pace()
                    window.rebase(cfg, now())
                    val n = input.read(buf, 0, cfg.chunkBytes.coerceIn(1, buf.size))
                    if (n == -1) break
                    LinkFraming.write(out, LinkFraming.Type.FILE_CHUNK, if (n == buf.size) buf else buf.copyOf(n))
                    out.flush() // hand the chunk to the stack so the pace below measures real fed bytes
                    touch()
                    // On a paced (BLE) link, hold the feed at ~link capacity so the stack TX queue stays shallow:
                    // interleaved frames sit near the wire head and the freed ACL budget carries reverse traffic.
                    val wait = window.fed(n, now())
                    if (wait > 0) delay(wait)
                }
                LinkFraming.write(out, LinkFraming.Type.FILE_END)
                out.flush()
                touch()
                if (offset > 0) metrics.onFileResumedOut()
                // The per-plane feed evidence (this same codec runs over the NAN NDP and BLE L2CAP sockets), with the
                // pace the feed ended on (0 = unbounded). On a paced link it times the hand-off to the stack, not the air:
                // the receiver's `rx … in <ms>ms` line is the drain (#117). A resume's offset rides last, so every parse of the line
                // before it holds — and `file <KIND>/<hash>` still counts the serves (ADR 2026-09.4tx5).
                val from = if (offset > 0) " from $offset" else ""
                log("file ${meta.kind.wire}/${meta.key} ${bytes}B in ${now() - startedAt}ms @${cfg.bytesPerSec} → $nodeId$from")
            }
        } finally {
            txInProgress = false
            notePending(meta.key, -1)
        }
    }

    /** [file] opened to stream, or null — logged, and the file skipped — when it is gone (an evicted blob, a cleared cache). */
    private fun openSource(
        file: File,
        meta: FileMeta,
    ): FileInputStream? =
        runCatching { FileInputStream(file) }
            .onFailure { log("source of ${meta.kind.wire}/${meta.key} unreadable, not sent → $nodeId: ${it.message}") }
            .getOrNull()

    /**
     * Writes the waiting digest with the newest id set [sendDigest] handed it, and empties the slot, so a digest
     * sent while this one is on the socket queues behind it as a fresh one. False when the slot was already
     * empty, which a marker never meets while [pendingDigest]'s invariant holds.
     */
    private fun writePendingDigest(out: OutputStream): Boolean {
        val ids = pendingDigest.getAndSet(null) ?: return false
        writeRecordSafely(out, LinkFraming.Type.DIGEST, LinkFraming.encodeDigest(DigestWire(ids)))
        return true
    }

    /** Next outbound item: a file stashed during a prior transfer, else the channel head. */
    private suspend fun nextOutbound(): Outbound? = stash.removeFirstOrNull() ?: outbound.receiveCatching().getOrNull()

    /**
     * Write any queued frames now (called between file chunks); stash any files for later. Returns whether any
     * record was actually written to [out] (a stashed file doesn't count), so the caller can flush it ahead of
     * the next chunk.
     */
    private fun drainFramesInto(out: OutputStream): Boolean {
        var wrote = false
        while (true) {
            val item = outbound.tryReceive().getOrNull() ?: break
            when (item) {
                is Outbound.Frame -> {
                    writeRecordSafely(out, LinkFraming.Type.FRAME, item.bytes)
                    wrote = true
                }

                Outbound.DigestDue -> {
                    wrote = writePendingDigest(out) || wrote
                }

                is Outbound.FileSend -> {
                    stash.addLast(item)
                }
            }
        }
        return wrote
    }

    // --- Inbound file reassembly (FileIntake, under [rxLock]) ---

    private fun beginRxFile(headerPayload: ByteArray) {
        val resume = synchronized(rxLock) { if (closed) null else intake.header(headerPayload) } ?: return
        // The rest of a cut transfer (#116): copy the kept prefix outside the lock — it can be megabytes, and a
        // teardown must not wait on it — then install it, or give it up if the link closed meanwhile.
        resume.copy()
        val installed =
            synchronized(rxLock) {
                if (closed) {
                    resume.abandon()
                    false
                } else {
                    intake.install(resume)
                }
            }
        if (installed) metrics.onFileResumedIn()
    }

    private fun appendRxFile(chunk: ByteArray) = synchronized(rxLock) { if (!closed) intake.chunk(chunk) }

    private fun endRxFile() {
        val finished = synchronized(rxLock) { if (closed) null else intake.end() } ?: return
        scope.launch(Dispatchers.IO) { intake.finalize(finished)?.let { callbacks.onFile(it) } }
    }

    private sealed interface Outbound {
        class Frame(
            val bytes: ByteArray,
        ) : Outbound

        // A digest is due; its ids are whatever [pendingDigest] holds when the writer gets here.
        data object DigestDue : Outbound

        class FileSend(
            val file: File,
            val meta: FileMeta,
        ) : Outbound
    }
}
