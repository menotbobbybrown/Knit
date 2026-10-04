package app.getknit.knit

import app.getknit.knit.mesh.link.PaceConfig
import app.getknit.knit.mesh.link.PaceWindow
import app.getknit.knit.mesh.link.TransferPacePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [TransferPacePolicy] — the pure average-feed-rate limiter for a file streamed over a link. */
class TransferPacePolicyTest {
    // 1000 B/s makes the arithmetic exact: 1 byte "costs" 1 ms of budget.
    private val kbps1 = PaceConfig(bytesPerSec = 1000)

    @Test
    fun unboundedWhenRateIsZeroOrNegative() {
        assertEquals(0, TransferPacePolicy.delayMs(bytesSent = 10_000, elapsedMs = 0, config = PaceConfig(0)))
        assertEquals(0, TransferPacePolicy.delayMs(bytesSent = 10_000, elapsedMs = 0, config = PaceConfig(-1)))
    }

    @Test
    fun noDelayWhileUnderBudget() {
        // 500 B in 1000 ms is well under 1000 B/s — the feed is behind the target, so don't hold it.
        assertEquals(0, TransferPacePolicy.delayMs(bytesSent = 500, elapsedMs = 1000, config = kbps1))
        // Exactly on budget: 1000 B at 1000 ms → target 1000 ms, elapsed 1000 ms → 0.
        assertEquals(0, TransferPacePolicy.delayMs(bytesSent = 1000, elapsedMs = 1000, config = kbps1))
    }

    @Test
    fun delaysWhenAheadOfBudget() {
        // 1000 B "should" take 1000 ms at 1000 B/s; only 200 ms elapsed → wait the remaining 800 ms.
        assertEquals(800, TransferPacePolicy.delayMs(bytesSent = 1000, elapsedMs = 200, config = kbps1))
        // Nothing elapsed yet → the full budget for what's been sent.
        assertEquals(1000, TransferPacePolicy.delayMs(bytesSent = 1000, elapsedMs = 0, config = kbps1))
    }

    @Test
    fun delayGrowsWithBytesSentAtFixedElapsed() {
        val a = TransferPacePolicy.delayMs(bytesSent = 2000, elapsedMs = 100, config = kbps1)
        val b = TransferPacePolicy.delayMs(bytesSent = 4000, elapsedMs = 100, config = kbps1)
        assertTrue("more bytes fed at the same elapsed ⇒ a longer hold ($a < $b)", a < b)
        assertEquals(1900, a) // 2000 ms target − 100 ms elapsed
        assertEquals(3900, b) // 4000 ms target − 100 ms elapsed
    }

    @Test
    fun realisticBleCapPacesAWholeGifTail() {
        // A 200 KB WebP at 28 KB/s should take ~7.3 s; with nothing elapsed the feed is held to that budget.
        val cfg = PaceConfig(bytesPerSec = 28 * 1024)
        val wait = TransferPacePolicy.delayMs(bytesSent = 200L * 1024, elapsedMs = 0, config = cfg)
        assertTrue("≈7.15 s hold for a fully-buffered 200 KB feed, was $wait ms", wait in 7000..7500)
    }

    @Test
    fun aCodedLinksPaceHoldsEachTwoKibChunkForTwoSeconds() {
        // #114: a Coded S=8 link drains ~1 KB/s at the edge, so its feed runs at 1 KiB/s in 2 KiB chunks.
        val coded = PaceConfig(bytesPerSec = 1024, chunkBytes = 2048)
        val window = PaceWindow(coded, startedAt = 0)
        assertEquals(2000, window.fed(2048, now = 0))
        assertEquals(2000, window.fed(2048, now = 2000)) // 4 KiB owes 4 s; 2 s are spent
    }

    @Test
    fun aPaceChangeMidTransferRestartsTheWindow() {
        // 112 KiB fed at 28 KiB/s in no time owes 4 s. The link steps to Coded: the next chunk is charged at the
        // Coded rate from the step, not on top of the old window's debt.
        val fast = PaceConfig(bytesPerSec = 28 * 1024)
        val coded = PaceConfig(bytesPerSec = 1024, chunkBytes = 2048)
        val window = PaceWindow(fast, startedAt = 0)
        assertEquals(4000, window.fed(112 * 1024, now = 0))
        window.rebase(coded, now = 4000)
        assertEquals(2000, window.fed(2048, now = 4000))
    }

    @Test
    fun aSplitChangeChargesTheNextChunkAtTheNewShare() {
        // #117: a 16 KiB chunk at the whole 8 KiB/s budget owes 2 s, served before the next chunk. Two more feeds start:
        // the next 5460 B chunk is charged at the third share from there, 2 s again, not at the old rate.
        val alone = PaceConfig(bytesPerSec = 8192, chunkBytes = 16384)
        val third = PaceConfig(bytesPerSec = 2730, chunkBytes = 5460)
        val window = PaceWindow(alone, startedAt = 0)
        assertEquals(2000, window.fed(16384, now = 0))
        window.rebase(third, now = 2000)
        assertEquals(2000, window.fed(5460, now = 2000))
    }

    @Test
    fun aStepUpDoesNotPayOffTheSlowStretch() {
        // 60 s on Coded fed 60 KiB on budget. Stepping up to 28 KiB/s starts a fresh window, so the first fast chunk
        // owes its own ~571 ms rather than nothing (an average since the file began would let a burst through).
        val fast = PaceConfig(bytesPerSec = 28 * 1024)
        val coded = PaceConfig(bytesPerSec = 1024, chunkBytes = 2048)
        val window = PaceWindow(coded, startedAt = 0)
        assertEquals(0, window.fed(60 * 1024, now = 60_000))
        window.rebase(fast, now = 60_000)
        assertEquals(571, window.fed(16 * 1024, now = 60_000))
    }

    @Test
    fun anUnchangedPaceKeepsTheWindowRunning() {
        val window = PaceWindow(kbps1, startedAt = 0)
        assertEquals(1000, window.fed(1000, now = 0))
        window.rebase(PaceConfig(bytesPerSec = 1000), now = 500) // equal config: no restart
        assertEquals(1500, window.fed(1000, now = 500)) // 2000 ms target − 500 ms elapsed
    }
}
