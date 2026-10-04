package app.getknit.knit.mesh.link

/**
 * Pure, JVM-testable feed-pacing for a streamed file over a link — the average byte-rate limiter that keeps a
 * bulk transfer from monopolising a slow, shared channel.
 *
 * [FramedLink.streamFile] already interleaves live frames between file chunks, but on the Bluetooth **L2CAP CoC**
 * plane that interleaving is defeated below the app layer: `BluetoothSocket.getOutputStream().write()` buffers
 * into the local socket → BT-stack TX queue and returns, so the writer dumps a whole small blob into that queue
 * in a burst — a frame enqueued *afterwards* lands behind the entire file and only reaches the wire once it
 * drains ("text arrives only when the transfer completes"), and the saturated ACL starves the reverse direction
 * too. Pacing the file feed below what the air drains keeps that queue shallow: interleaved frames sit near the
 * wire head and the freed connection-event budget carries reverse traffic. The stack's queue holds hundreds of KB
 * before a write blocks, so the sender cannot see the drain; it is the receiver's `rx … in <ms>ms` line that
 * measures it, never the sender's `file … in` line, which reads size ÷ pace (#117).
 *
 * Kept free of Android and of a clock (the caller stamps `elapsedMs` from the link's injected `now`), like
 * [app.getknit.knit.mesh.bluetooth.ConnectBackoffPolicy], so the curve is asserted with the same unit-test
 * style. The Wi-Fi Aware NDP socket is fast and must not be throttled, so it runs unbounded
 * ([PaceConfig.bytesPerSec] ≤ 0).
 */
object TransferPacePolicy {
    /**
     * Milliseconds to wait before feeding the next chunk so the average feed rate holds at or below
     * [config].bytesPerSec, given [bytesSent] fed so far and [elapsedMs] since the transfer began. Returns 0
     * when unbounded ([config].bytesPerSec ≤ 0) or when the feed is still under budget (we've sent fewer bytes
     * than the elapsed time allows) — a delay is only imposed once the feed runs ahead of the target rate.
     */
    fun delayMs(
        bytesSent: Long,
        elapsedMs: Long,
        config: PaceConfig,
    ): Long {
        if (config.bytesPerSec <= 0) return 0 // unbounded (the NAN / default path)
        val targetMs = bytesSent * MS_PER_SEC / config.bytesPerSec
        return (targetMs - elapsedMs).coerceAtLeast(0L)
    }

    private const val MS_PER_SEC = 1000L
}

/** Tunable for [TransferPacePolicy]. */
data class PaceConfig(
    /**
     * Target average feed rate in bytes/second. **≤ 0 means unbounded** (no pacing) — the default, so a link
     * that doesn't opt in (Wi-Fi Aware) and every existing caller is unaffected. A positive value is the BLE
     * cap, chosen below measured L2CAP CoC throughput to leave reverse-direction headroom.
     */
    val bytesPerSec: Int = 0,
    /**
     * The most file bytes one `FILE_CHUNK` record carries, so the most a frame queued mid-transfer waits behind on
     * the feed side. Capped at [LinkFraming.FILE_CHUNK_BYTES]; a Coded link takes smaller chunks (ADR 2026-10.yvn6).
     */
    val chunkBytes: Int = LinkFraming.FILE_CHUNK_BYTES,
)

/**
 * One file feed's running pace: the bytes fed since its clock started, under the [PaceConfig] it started with. A
 * link's pace can change mid-transfer (a Bluetooth link stepping to or from the Coded PHY), and the average since the
 * file began would then be wrong both ways — a step down would owe nothing for seconds while the slower air backs up,
 * a step up would hold the feed to pay off the slow stretch. So [rebase] restarts the clock and the count whenever
 * the config changes, and the window paces from there.
 */
class PaceWindow(
    private var config: PaceConfig,
    private var startedAt: Long,
) {
    private var fed = 0L

    /** Restarts the window at [now] when [current] differs from the config it runs under; else leaves it be. */
    fun rebase(
        current: PaceConfig,
        now: Long,
    ) {
        if (current == config) return
        config = current
        startedAt = now
        fed = 0L
    }

    /** Counts [bytes] fed and returns the milliseconds to wait before the next chunk ([TransferPacePolicy.delayMs]). */
    fun fed(
        bytes: Int,
        now: Long,
    ): Long {
        fed += bytes
        return TransferPacePolicy.delayMs(fed, now - startedAt, config)
    }
}
