package app.getknit.knit.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ArrivingFile]'s share against its declared total, the merge across links, and the chat's sampler (#115). */
@OptIn(ExperimentalCoroutinesApi::class)
class ArrivingFileTest {
    @Test
    fun theShareInIsTheBytesOverTheDeclaredTotal() {
        assertEquals(0f, ArrivingFile("h", 0, 200).fraction!!, 0f)
        assertEquals(0.42f, ArrivingFile("h", 84, 200).fraction!!, 1e-6f)
        assertEquals(1f, ArrivingFile("h", 200, 200).fraction!!, 0f)
    }

    @Test
    fun aTotalThatHoldsNothingIsNoTotal() {
        // A label, never a bound: none declared, nothing declared, or overrun by the bytes — the count alone shows.
        assertNull(ArrivingFile("h", 10, null).fraction)
        assertNull(ArrivingFile("h", 0, 0).fraction)
        assertNull(ArrivingFile("h", 201, 200).fraction)
    }

    @Test
    fun twoLinksStreamingOneBlobCountTheCopyFurthestAlong() {
        val merged =
            listOf(
                ArrivingFile("a", 10, 100),
                ArrivingFile("b", 5, null),
                ArrivingFile("a", 60, 100),
                ArrivingFile("a", 30, 100),
            ).furthestByKey()
        assertEquals(mapOf("a" to ArrivingFile("a", 60, 100), "b" to ArrivingFile("b", 5, null)), merged)
    }

    @Test
    fun theSamplerReadsTwiceASecondWhileAFileStreamsInAndEveryTwoSecondsWhileNoneDoes() =
        runTest {
            var reads = 0
            var links: Map<String, ArrivingFile> = emptyMap()
            val seen = mutableListOf<Map<String, ArrivingFile>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                arrivalTicker {
                    reads++
                    links
                }.collect { seen += it }
            }
            assertEquals("the first read is at once", 1, reads)

            advanceTimeBy(ARRIVAL_IDLE_MS - 1)
            runCurrent()
            assertEquals("nothing streaming: the long wait", 1, reads)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, reads)

            val streaming = mapOf("h" to ArrivingFile("h", 10, 100))
            links = streaming
            advanceTimeBy(ARRIVAL_IDLE_MS)
            runCurrent()
            assertEquals(3, reads)
            advanceTimeBy(ARRIVAL_ACTIVE_MS)
            runCurrent()
            assertEquals("a file streaming in: the short one", 4, reads)

            assertEquals("an unchanged sample is dropped", listOf(emptyMap(), streaming), seen)
        }
}
