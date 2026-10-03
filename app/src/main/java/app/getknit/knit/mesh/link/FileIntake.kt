package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.isValidBlobHash
import app.getknit.knit.mesh.transferExtForMime
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * The receiving end of one link's file stream: `FILE_HEADER`, `FILE_CHUNK` and `FILE_END` records in, whole files
 * out (knit-ios's `FileIntake` is its twin). One file streams at a time per socket, so no record names its file.
 *
 * - **A header** starts a file. One that arrives mid-file drops the file it interrupts; one that does not decode
 *   leaves the chunks behind it unread.
 * - **A chunk** joins the file being kept; one that takes it past [MAX_INCOMING_FILE_BYTES] drops it, and its
 *   later chunks and its `FILE_END` do nothing.
 * - **An end** finishes the file; [finalize] then moves it into the cache under a safe name for its owner to
 *   announce. The hash check over the bytes is the store's (`MeshBlobStore.saveIncoming`).
 * - **A cut** — the link closed mid-file — hands an attachment's bytes to [PartialBlobs] instead of deleting
 *   them, and a header whose `offset` names how many the asker kept splices the rest onto a copy of them (work item
 *   #116, ADR 2026-10.wtyc). An avatar is pushed, never asked for, so it is never kept or resumed.
 *
 * [arrivingFile] is the published "a header is in and no end yet" snapshot `MeshTransport.arrivingFiles` reads
 * (ADR 2026-09.4tx5, 2026-10.y9qh): one reference, so a reader never pairs one file's key with another's count.
 *
 * Not thread-safe by itself: its owner ([FramedLink]) makes every call under one lock — the chunk writes included,
 * so a `close()` from the transport's thread never races a write on the stream it closes. The one step that can
 * move megabytes, [Resume.copy], runs between [header] and [install] outside that lock, so a link's teardown never
 * waits on a copy; the owner re-takes the lock and installs, or [Resume.abandon]s it if the link closed meanwhile.
 */
internal class FileIntake(
    private val cacheDir: File,
    private val partials: PartialBlobs,
    private val peer: String,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
) {
    /** A whole file, its `FILE_END` in: [finalize] it outside the owner's lock. */
    class Finished(
        val temp: File,
        val meta: FileMeta,
    )

    /**
     * A resumed header's prefix, still to be copied: the owner runs [copy] outside its lock, then hands this to
     * [install] (or [abandon]s it). [meta]'s offset is the byte the stream starts at.
     */
    class Resume internal constructor(
        internal val temp: File,
        internal val meta: FileMeta,
        internal val size: Long?,
        private val prefix: PartialBlobs.Prefix,
    ) {
        internal val epoch: Long = prefix.epoch
        internal var out: OutputStream? = null
            private set
        internal var copied = false
            private set

        /**
         * Copies exactly the first `offset` bytes of the kept prefix into a fresh temp file and leaves that stream
         * open, so the chunks append to the very stream the prefix went into (reopening it would truncate it).
         */
        fun copy() {
            copied =
                runCatching {
                    val stream = BufferedOutputStream(FileOutputStream(temp))
                    out = stream
                    prefix.use { copyExactly(it.input, stream, meta.offset) }
                }.getOrDefault(false)
        }

        /** Gives the copy up: the prefix closed, the temp deleted. Safe to call more than once. */
        fun abandon() {
            runCatching { prefix.close() }
            runCatching { out?.close() }
            temp.delete()
        }
    }

    private sealed interface State {
        data object Idle : State

        // A header we will not keep, until the next header: its chunks are dropped and its end yields nothing.
        data object Skipping : State

        // A resumed header whose prefix is being copied outside the owner's lock.
        class Opening(
            val resume: Resume,
        ) : State

        class Keeping(
            val temp: File,
            val out: OutputStream,
            val meta: FileMeta,
            // The epoch of the prefix this stream was spliced onto, or null for one from byte 0 (PartialBlobs.keep).
            val basedOn: Long?,
            // The blob's bytes in so far, a resumed prefix included, so the ceiling counts the whole file.
            var bytes: Long,
            val startedAt: Long,
        ) : State
    }

    private var state: State = State.Idle

    private val arriving = AtomicReference<ArrivingFile?>(null)

    /** The file streaming in right now, with the bytes in so far and the total its header declared. */
    val arrivingFile: ArrivingFile? get() = arriving.get()

    /**
     * A `FILE_HEADER`. Returns a [Resume] when the stream is the rest of a file whose prefix we kept — the owner
     * copies it outside its lock and calls [install] — or null when the header was taken (or refused) here.
     */
    fun header(payload: ByteArray): Resume? {
        discard()
        val header = LinkFraming.decodeFileHeader(payload) ?: return skip()
        // No offset is a whole file, and so is an explicit 0: a stream from the first byte splices onto nothing.
        val offset = header.offset?.takeIf { it != 0L }
        val meta = FileMeta(FileKind.fromWire(header.kind), header.key, header.mime, offset ?: 0)
        if (offset == null) {
            begin(meta, header.size)
            return null
        }
        val prefix =
            offset
                .takeIf { meta.kind == FileKind.ATTACHMENT && isValidBlobHash(meta.key) && it in 1..MAX_INCOMING_FILE_BYTES }
                ?.let { partials.open(meta.key, it) }
        if (prefix == null) {
            log("rx ${label(meta)} refused ← $peer from $offset: ${partials.length(meta.key)}B kept")
            return skip()
        }
        // A temp file that will not open fails the link as it always has; the prefix it would have copied is closed.
        val temp = runCatching { File.createTempFile(RX_PREFIX, RX_SUFFIX, cacheDir) }.onFailure { prefix.close() }.getOrThrow()
        val resume = Resume(temp, meta, header.size, prefix)
        state = State.Opening(resume)
        return resume
    }

    /**
     * Takes a [Resume] the owner has copied: the file is kept from its offset on (true), or skipped if the copy
     * failed (false).
     */
    fun install(resume: Resume): Boolean {
        val opening = state as? State.Opening
        val out = resume.out
        if (opening?.resume !== resume || !resume.copied || out == null) {
            resume.abandon()
            if (opening?.resume === resume) {
                log("rx ${label(resume.meta)} refused ← $peer from ${resume.meta.offset}: the kept prefix would not copy")
                skip()
            }
            return false
        }
        val meta = resume.meta
        state = State.Keeping(resume.temp, out, meta, resume.epoch, meta.offset, now())
        // The size is the tail's (WIRE_COMPAT rule 2), so the whole is the offset plus it: the ring resumes where it was.
        val total =
            resume.size
                ?.takeIf { it >= 0 }
                ?.let { meta.offset + it }
                ?.takeIf { it in 1..MAX_INCOMING_FILE_BYTES }
        arriving.set(ArrivingFile(meta.key, meta.offset, total))
        // The resume's device oracle: the offset rides last, so every parse of the line before it holds.
        log("rx ${label(meta)} ${resume.size ?: "?"}B ← $peer from ${meta.offset}")
        return true
    }

    /** A `FILE_CHUNK`: appended to the file being kept, which it may take past the ceiling. */
    fun chunk(bytes: ByteArray) {
        val keeping = state as? State.Keeping ?: return
        val next = keeping.bytes + bytes.size
        if (next > MAX_INCOMING_FILE_BYTES) {
            log("incoming file from $peer exceeds ceiling; aborting")
            abort(keeping)
            return
        }
        if (runCatching { keeping.out.write(bytes) }.isFailure) {
            abort(keeping)
            return
        }
        keeping.bytes = next
        arriving.updateAndGet { it?.copy(bytes = next) }
    }

    /** A `FILE_END`: the file being kept, whole, or null when there is none. */
    fun end(): Finished? {
        val current = state
        if (current is State.Opening) current.resume.abandon() // never: the reader installs before its next record
        val keeping = current as? State.Keeping
        state = State.Idle
        arriving.set(null)
        if (keeping == null) return null
        if (runCatching { keeping.out.close() }.isFailure) {
            keeping.temp.delete()
            return null
        }
        // How long the bytes took to cross: the sender's `file …` line times only its feed into the stack (#114).
        val onAir = keeping.bytes - keeping.meta.offset
        log("rx ${label(keeping.meta)} ${onAir}B in ${now() - keeping.startedAt}ms ← $peer${from(keeping.meta)}")
        return Finished(keeping.temp, keeping.meta)
    }

    /**
     * The link closed. An attachment cut mid-stream is handed to [PartialBlobs] so the next ask resumes from where
     * its bytes stopped; anything else is dropped. A resume still copying is the reader's to abandon at [install].
     */
    fun cut() {
        val keeping = state as? State.Keeping
        state = State.Idle
        arriving.set(null)
        if (keeping == null) return
        val flushed = runCatching { keeping.out.close() }.isSuccess
        if (flushed && keeping.meta.kind == FileKind.ATTACHMENT) {
            partials.keep(keeping.meta.key, keeping.temp, keeping.basedOn) // deletes the temp itself if it refuses
        } else {
            keeping.temp.delete()
        }
    }

    /**
     * Moves a finished file into the cache under a safe name and returns it to announce (an avatar by node, an
     * attachment by hash), or null when it is refused. Runs outside the owner's lock: it copies the whole file.
     */
    fun finalize(finished: Finished): ReceivedFile? {
        val meta = finished.meta
        // [meta.key] is peer-supplied and interpolated into the filename: reject anything but a 64-hex
        // content hash so a "../" can't escape the cache dir (path traversal → arbitrary in-sandbox write).
        if (!isValidBlobHash(meta.key)) {
            finished.temp.delete()
            log("Rejecting ${meta.kind} from $peer: malformed blob key")
            return null
        }
        return runCatching {
            val dest = destinationFor(meta)
            val cacheRoot = cacheDir.canonicalPath + File.separator
            if (!dest.canonicalPath.startsWith(cacheRoot)) {
                dest.delete()
                error("path escapes cache dir")
            }
            runCatching { finished.temp.copyTo(dest, overwrite = true) }.onFailure { dest.delete() }.getOrThrow()
            ReceivedFile(peer, dest.absolutePath, meta.kind, meta.key, meta.mime, resumedFrom = meta.offset)
        }.onFailure {
            log("Failed saving ${meta.kind} from $peer: ${it.message}")
        }.also { finished.temp.delete() }
            .getOrNull()
    }

    private fun destinationFor(meta: FileMeta): File =
        when (meta.kind) {
            FileKind.AVATAR -> {
                cacheDir.listFiles { f -> f.name.startsWith("avatar-$peer-") }?.forEach { it.delete() }
                File(cacheDir, "avatar-$peer-${meta.key}.jpg")
            }

            // A name of its own: two links finishing one blob at once (a clique's first ask reaches every holder)
            // must never copy over the file the other's hash check is reading.
            FileKind.ATTACHMENT -> {
                File.createTempFile("attach-${meta.key}-", ".${transferExtForMime(meta.mime)}", cacheDir)
            }
        }

    private fun begin(
        meta: FileMeta,
        size: Long?,
    ) {
        val temp = File.createTempFile(RX_PREFIX, RX_SUFFIX, cacheDir)
        state = State.Keeping(temp, BufferedOutputStream(FileOutputStream(temp)), meta, basedOn = null, bytes = 0L, startedAt = now())
        // The declared size is the sender's label, never a bound: one past the ceiling describes a stream this
        // link aborts anyway, so it is shown as no total at all (#115).
        arriving.set(ArrivingFile(meta.key, 0L, size?.takeIf { it in 1..MAX_INCOMING_FILE_BYTES }))
        // The receive side's oracle that the total crossed. `rx`, not `file`: a bare `file <KIND>/<hash>` grep
        // counts the serves (ADR 2026-09.4tx5's device check), and the key is the peer's until finalize checks it.
        log("rx ${label(meta)} ${size ?: "?"}B ← $peer")
    }

    /** A header mid-file drops the file it interrupts: a sender that moves on without an end abandoned it. */
    private fun discard() {
        val current = state
        if (current is State.Keeping) {
            runCatching { current.out.close() }
            current.temp.delete()
        } else if (current is State.Opening) {
            current.resume.abandon()
        }
        state = State.Idle
        arriving.set(null)
    }

    private fun abort(keeping: State.Keeping) {
        runCatching { keeping.out.close() }
        keeping.temp.delete()
        skip()
    }

    private fun skip(): Resume? {
        state = State.Skipping
        arriving.set(null)
        return null
    }

    /** A file's kind and key for a log line, the key only once it reads as a content hash (it is the peer's). */
    private fun label(meta: FileMeta): String = "${meta.kind.wire}/${meta.key.takeIf { isValidBlobHash(it) } ?: "<bad key>"}"

    private fun from(meta: FileMeta): String = if (meta.offset > 0) " from ${meta.offset}" else ""

    internal companion object {
        // Receive-side ceiling on a file, matching the send cap (AttachmentStore.MAX_BYTES = 8 MiB) plus
        // headroom for E2E framing (GCM IV+tag) — refuses an unbounded malicious stream that exhausts disk.
        const val MAX_INCOMING_FILE_BYTES = 8L * 1024 * 1024 + 64 * 1024

        private const val RX_PREFIX = "link-rx-"
        private const val RX_SUFFIX = ".tmp"
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** Copies exactly [n] bytes of [input] to [out]; false when the input ends first. */
        private fun copyExactly(
            input: InputStream,
            out: OutputStream,
            n: Long,
        ): Boolean {
            val buf = ByteArray(COPY_BUFFER_BYTES)
            var left = n
            while (left > 0) {
                val read = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (read == -1) return false
                out.write(buf, 0, read)
                left -= read
            }
            return true
        }
    }
}
