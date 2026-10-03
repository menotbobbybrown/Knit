package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Executable
import java.lang.reflect.Modifier

class MeshMetricsTest {
    @Test
    fun `a fresh snapshot is all zero with no per-reason buckets`() {
        val snap = MeshMetrics().snapshot()
        assertEquals(0L, snap.frames.framesOriginated)
        assertEquals(0L, snap.frames.framesDropped)
        assertEquals(emptyMap<DropReason, Long>(), snap.frames.dropsByReason)
        assertEquals(emptyMap<ConnectFailReason, Long>(), snap.bluetooth.btConnectFailsByReason)
        assertEquals(0L, snap.nan.nanServesPeak)
        assertEquals(emptyMap<FastPathDrop, Long>(), snap.fast.fastDropsByReason)
    }

    @Test
    fun `fast-path counters surface in the snapshot and omit zero drop buckets`() {
        val metrics = MeshMetrics()
        metrics.onFastCompactSent()
        repeat(2) { metrics.onFastLegacySent() }
        metrics.onFastFragSent()
        metrics.onFastReassembled()
        metrics.onFastTooBig()
        metrics.onFastTranscodedSent()
        repeat(3) { metrics.onTranscodeFallback() }
        repeat(2) { metrics.onFastDropped(FastPathDrop.FRAG_TIMEOUT) }
        metrics.onFastDropped(FastPathDrop.UNKNOWN_TAG)
        repeat(4) { metrics.onBleLinkDupSkipped() }
        repeat(2) { metrics.onDigestReplaced() }

        val snap = metrics.snapshot()
        assertEquals(4L, snap.bluetooth.bleLinkDupSkipped)
        assertEquals(2L, snap.frames.digestsReplaced)
        assertEquals(1L, snap.fast.fastCompactSent)
        assertEquals(2L, snap.fast.fastLegacySent)
        assertEquals(1L, snap.fast.fastFragSent)
        assertEquals(1L, snap.fast.fastReassembled)
        assertEquals(1L, snap.fast.fastTooBig)
        assertEquals(1L, snap.fast.fastTranscodedSent)
        assertEquals(3L, snap.fast.transcodeFallbacks)
        assertEquals(
            mapOf(FastPathDrop.FRAG_TIMEOUT to 2L, FastPathDrop.UNKNOWN_TAG to 1L),
            snap.fast.fastDropsByReason,
        )
    }

    @Test
    fun `counters surface in the snapshot`() {
        val metrics = MeshMetrics()
        repeat(3) { metrics.onOriginated() }
        metrics.onDelivered()
        metrics.onRelayed()
        metrics.onBytesSent(128)
        metrics.onBytesSent(64)
        metrics.onKeyServed()

        val snap = metrics.snapshot()
        assertEquals(3L, snap.frames.framesOriginated)
        assertEquals(1L, snap.frames.framesDelivered)
        assertEquals(1L, snap.frames.framesRelayed)
        assertEquals(192L, snap.frames.bytesSent)
        assertEquals(1L, snap.keys.keysServed)
    }

    @Test
    fun `dropsByReason sums the total and omits zero buckets`() {
        val metrics = MeshMetrics()
        metrics.onDropped(DropReason.DECODE_FAILED)
        metrics.onDropped(DropReason.DECODE_FAILED)
        metrics.onDropped(DropReason.SIG_INVALID)

        val snap = metrics.snapshot()
        assertEquals(3L, snap.frames.framesDropped)
        assertEquals(
            mapOf(DropReason.DECODE_FAILED to 2L, DropReason.SIG_INVALID to 1L),
            snap.frames.dropsByReason,
        )
    }

    @Test
    fun `btConnectFailsByReason sums the total and omits zero buckets`() {
        val metrics = MeshMetrics()
        metrics.onBtConnectFailed(ConnectFailReason.TIMEOUT)
        metrics.onBtConnectFailed(ConnectFailReason.RADIO)
        metrics.onBtConnectFailed(ConnectFailReason.RADIO)

        val snap = metrics.snapshot()
        assertEquals(3L, snap.bluetooth.btConnectFails)
        assertEquals(
            mapOf(ConnectFailReason.TIMEOUT to 1L, ConnectFailReason.RADIO to 2L),
            snap.bluetooth.btConnectFailsByReason,
        )
    }

    @Test
    fun `onNanServes keeps the session peak, not the last value`() {
        val metrics = MeshMetrics()
        metrics.onNanServes(3)
        metrics.onNanServes(5)
        metrics.onNanServes(2)
        assertEquals(5L, metrics.nan().nanServesPeak)
    }

    @Test
    fun `onFileSent splits by transport plane`() {
        val metrics = MeshMetrics()
        metrics.onFileSent(TransportKind.WifiAware)
        metrics.onFileSent(TransportKind.WifiAware)
        metrics.onFileSent(TransportKind.Bluetooth)
        metrics.onFileSent(TransportKind.Other)

        val snap = metrics.snapshot()
        assertEquals(2L, snap.files.filesSentNan)
        assertEquals(1L, snap.files.filesSentBt)
    }

    @Test
    fun `lora counters surface in the snapshot`() {
        val metrics = MeshMetrics()
        metrics.onLoraSessionUp()
        repeat(3) { metrics.onLoraSent() }
        metrics.onLoraFragSent()
        repeat(2) { metrics.onLoraReceived() }
        metrics.onLoraReassembled()
        metrics.onLoraTooBig()
        metrics.onLoraTranscoded()
        metrics.onLoraPadded()
        metrics.onLoraDroppedQueue()
        metrics.onLoraAirtimeHeld("BRIDGE")
        metrics.onLoraAirtimeHeld("BRIDGE")
        metrics.onLoraAirtimeHeld("LIVE")
        metrics.onLoraSuppressed()
        metrics.onLoraNak()
        metrics.onFileSent(TransportKind.LoRa) // no-op: LoRa carries no files

        val snap = metrics.snapshot()
        assertEquals(1L, snap.lora.loraSessionUps)
        assertEquals(3L, snap.lora.loraSent)
        assertEquals(1L, snap.lora.loraFragSent)
        assertEquals(2L, snap.lora.loraReceived)
        assertEquals(1L, snap.lora.loraReassembled)
        assertEquals(1L, snap.lora.loraTooBig)
        assertEquals(1L, snap.lora.loraTranscoded)
        assertEquals(1L, snap.lora.loraPadded)
        assertEquals(1L, snap.lora.loraDroppedQueue)
        // Held is not dropped: the two must never be read as one number, which is the point of the split.
        assertEquals(3L, snap.lora.loraAirtimeHeld)
        assertEquals(mapOf("BRIDGE" to 2L, "LIVE" to 1L), snap.lora.loraAirtimeHeldByBucket)
        assertEquals(1L, snap.lora.loraSuppressed)
        assertEquals(1L, snap.lora.loraNak)
    }

    @Test
    fun `the resume counters surface in the files group`() {
        val metrics = MeshMetrics()
        repeat(2) { metrics.onFileResumedOut() }
        metrics.onFileResumedIn()
        repeat(3) { metrics.onSpliceRefused() }

        val files = metrics.files()
        assertEquals(2L, files.filesResumedOut)
        assertEquals(1L, files.filesResumedIn)
        assertEquals(3L, files.splicesRefused)
        assertEquals(files, metrics.snapshot().files)
    }

    @Test
    fun `every snapshot group stays far below the JVM's parameter-slot limit`() {
        // A method may take at most 255 slots, a Long or Double two, and a data class's copy$default and synthetic
        // default constructor take every field. Past the limit the class fails to load and every test touching it
        // crashes, so a group is held to half: split one that grows past it.
        val classes = listOf(MeshMetrics.Snapshot::class.java) + MeshMetrics.Snapshot::class.java.declaredClasses
        assertTrue(classes.size > 1)
        for (cls in classes) {
            val members: List<Executable> = cls.declaredConstructors.toList() + cls.declaredMethods
            for (member in members) {
                val slots =
                    member.parameterTypes.sumOf { if (it == Long::class.java || it == Double::class.java) 2 else 1 } +
                        if (Modifier.isStatic(member.modifiers)) 0 else 1
                assertTrue("${cls.simpleName}.${member.name} takes $slots slots", slots <= GROUP_SLOT_BUDGET)
            }
        }
    }

    private companion object {
        const val GROUP_SLOT_BUDGET = 128
    }
}
