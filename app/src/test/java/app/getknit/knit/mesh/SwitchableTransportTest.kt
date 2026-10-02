package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The debug Wi-Fi Aware switch's wrapper: the inner transport runs exactly while the mesh runs and the switch is on. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableTransportTest {
    /** Records every call that reaches it, in order. */
    private class RecordingInner : MeshTransport {
        val calls = mutableListOf<String>()
        val healthState = MutableStateFlow(TransportHealth.Healthy)
        var armBulk = true

        override val neighbors = MutableStateFlow<Set<Peer>>(emptySet()).asStateFlow()
        override val health = healthState.asStateFlow()
        override val kind = TransportKind.WifiAware
        override val inbound: Flow<InboundFrame> = emptyFlow()
        override val incomingFiles: Flow<ReceivedFile> = emptyFlow()

        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun heal() {
            calls += "heal"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun resume() {
            calls += "resume"
        }

        override fun releaseInitiatorHold() {
            calls += "release"
        }

        override fun suppressDataPath(peers: Set<String>) {
            calls += "suppress"
        }

        override fun expectBulkTransfer(nodeId: String): Boolean {
            calls += "expectBulk"
            return armBulk
        }

        override suspend fun send(
            wire: WireEnvelope,
            to: Peer?,
        ) {
            calls += "send"
        }

        override fun fastFanout(wire: WireEnvelope) {
            calls += "fastFanout"
        }

        override fun fastSend(
            wire: WireEnvelope,
            to: Peer,
        ) {
            calls += "fastSend"
        }

        override suspend fun sendFile(
            file: File,
            to: Peer,
            meta: FileMeta,
        ): Boolean {
            calls += "sendFile"
            return true
        }

        override suspend fun sendDigest(
            to: Peer,
            ids: List<String>,
        ) {
            calls += "sendDigest"
        }
    }

    private val inner = RecordingInner()
    private val peer = Peer("peer0001")
    private val wire = WireEnvelope(sig = ByteArray(0), signed = ByteArray(0))
    private val meta = FileMeta(FileKind.ATTACHMENT, "hash", "image/webp")

    private fun TestScope.switchable(off: Flow<Boolean>) = SwitchableTransport(inner, off, backgroundScope)

    @Test
    fun startWhileOffNeverStartsTheInnerTransport() =
        runTest(UnconfinedTestDispatcher()) {
            val t = switchable(MutableStateFlow(true))
            t.start()
            assertEquals(emptyList<String>(), inner.calls)
            assertEquals(TransportHealth.Unavailable, t.health.value)
        }

    @Test
    fun switchingOffWhileRunningStopsTheInnerOnceAndOnStartsItAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(false)
            val t = switchable(off)
            t.start()
            assertEquals(listOf("start"), inner.calls)

            off.value = true
            assertEquals(listOf("start", "stop"), inner.calls)
            t.stop() // the mesh going down while already off stops nothing twice
            t.start()
            assertEquals(listOf("start", "stop"), inner.calls)

            off.value = false
            assertEquals(listOf("start", "stop", "start"), inner.calls)
        }

    @Test
    fun switchingOnWhileTheMeshIsStoppedStartsNothing() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(true)
            val t = switchable(off)
            off.value = false
            assertEquals(emptyList<String>(), inner.calls)

            t.start()
            t.stop()
            off.value = true
            assertEquals(listOf("start", "stop"), inner.calls)
        }

    @Test
    fun aStartBeforeTheSwitchsFirstReadWaitsForIt() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableSharedFlow<Boolean>(replay = 1)
            val t = switchable(off)
            t.start()
            assertEquals(emptyList<String>(), inner.calls)

            off.emit(false)
            assertEquals(listOf("start"), inner.calls)
        }

    @Test
    fun aStartBeforeAnOffFirstReadNeverTouchesTheInner() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableSharedFlow<Boolean>(replay = 1)
            val t = switchable(off)
            t.start()
            off.emit(true)
            assertEquals(emptyList<String>(), inner.calls)
        }

    @Test
    fun healthReadsUnavailableWhileOffAndTheInnersOwnOtherwise() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(false)
            inner.healthState.value = TransportHealth.Degraded
            val t = switchable(off)
            assertEquals(TransportHealth.Degraded, t.health.value)

            off.value = true
            assertEquals(TransportHealth.Unavailable, t.health.value)
            inner.healthState.value = TransportHealth.Healthy
            assertEquals(TransportHealth.Unavailable, t.health.value)

            off.value = false
            assertEquals(TransportHealth.Healthy, t.health.value)
        }

    @Test
    fun whileOffNoSendAndNoBulkArmReachesTheInner() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(true)
            val t = switchable(off)
            t.start()

            t.send(wire, null)
            t.send(wire, peer)
            t.fastFanout(wire)
            t.fastSend(wire, peer)
            t.sendDigest(peer, listOf("id"))
            t.heal()
            assertFalse(t.sendFile(File("unused"), peer, meta))
            // Declining is what sends the composite's file route straight to the link holder, with no bulk grace.
            assertFalse(t.expectBulkTransfer(peer.nodeId))
            assertEquals(emptyList<String>(), inner.calls)

            off.value = false
            assertTrue(t.expectBulkTransfer(peer.nodeId))
            t.fastFanout(wire)
            assertEquals(listOf("start", "expectBulk", "fastFanout"), inner.calls)
        }

    @Test
    fun aHandOverPauseHeldAcrossAnOffSpellGoesBackOnWithTheRestart() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(false)
            val t = switchable(off)
            t.start()
            off.value = true
            t.pause() // the Wi-Fi Direct hand-over begins while the plane is off: nothing to pause yet
            assertEquals(listOf("start", "stop"), inner.calls)

            off.value = false
            assertEquals(listOf("start", "stop", "start", "pause"), inner.calls)
            t.resume()
            assertEquals(listOf("start", "stop", "start", "pause", "resume"), inner.calls)
        }

    @Test
    fun theCrossPlaneHintsAndTheInitiatorHoldPassThroughWhileOff() =
        runTest(UnconfinedTestDispatcher()) {
            val t = switchable(MutableStateFlow(true))
            t.suppressDataPath(setOf(peer.nodeId))
            t.releaseInitiatorHold()
            assertEquals(listOf("suppress", "release"), inner.calls)
            assertEquals(TransportKind.WifiAware, t.kind)
        }
}
