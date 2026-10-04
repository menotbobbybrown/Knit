package app.getknit.knit.mesh

import java.io.File

/**
 * Content-addressed image-blob storage, abstracted so [BlobExchange] stays free of Android/Room
 * dependencies (and unit-testable). The app's implementation is backed by the encrypted database (see
 * `MeshBlobStore`); the methods are `suspend` because they read/write that database. Mesh file transfers
 * are inherently file-based (streamed over the data-path socket), so [fileFor]/[saveIncoming] still trade
 * in [File]s — the implementation
 * materializes short-lived temp files for in-flight transfers.
 */
interface BlobStore {
    suspend fun has(hash: String): Boolean

    /** A local (temp) file for [hash] suitable for sending, or null if not held. */
    suspend fun fileFor(hash: String): File?

    /** The mime type of the held blob [hash], or null if not held. */
    suspend fun mimeFor(hash: String): String?

    /** Persists a received blob (read from [srcPath]) and returns a file the transport can forward, or null. */
    suspend fun saveIncoming(
        hash: String,
        mime: String,
        srcPath: String,
    ): File?

    /**
     * Whether [hash]'s bytes are in hand and on their way into the store right now: checked against the hash,
     * screened, not yet held. A read of live state, like `MeshTransport.arrivingFiles` (ADR 2026-09.4tx5) — the
     * screen runs before the insert (#109), so between a file's end on the link and [has] there can be a whole
     * classification. The default is never.
     */
    fun storing(hash: String): Boolean = false
}
