package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * A [CompositeMeshTransport] child the debug build can switch off while the app runs — the Diagnostics "Wi-Fi
 * Aware (debug)" switch and the bridge's `NANOFF` (`SettingsStore.debugNanOff`), so a trial can run the mesh on
 * Bluetooth alone without touching the phone's Wi-Fi. Wired around the Wi-Fi Aware child in debug builds only;
 * release hands the composite the bare transport.
 *
 * Off stops [inner] outright — its links, responder and session go, and its [neighbors]/[reachable] empty — and on
 * starts it again, but only while the mesh itself runs: [inner] is up exactly when [start] has been called without
 * a [stop] since **and** the switch reads on. A [start] that lands before the switch's first read waits for it, so
 * an off phone never attaches and detaches again at launch. A [pause] (the Wi-Fi Direct hand-over) is remembered
 * across an off spell and re-applied when [inner] comes back. While [inner] is down every send is dropped and
 * [expectBulkTransfer] declines, so the composite's file route falls straight to the link holder instead of waiting
 * out the bulk grace for a link that cannot come; [health] reads [TransportHealth.Unavailable], which the
 * composite's merge already treats as a radio that is off. Everything else — the cross-plane hints, the initiator
 * hold — passes through untouched.
 */
@Suppress("TooManyFunctions") // the gated half of the radio seam: lifecycle, sends and the bulk arm
class SwitchableTransport(
    private val inner: MeshTransport,
    off: Flow<Boolean>,
    scope: CoroutineScope,
    // Injected logger (the class stays Android-free): DI passes android.util.Log; tests default silent.
    private val log: (String) -> Unit = {},
) : MeshTransport by inner {
    private val lock = Any()

    // Guarded by [lock]: the mesh's own start/stop, the switch (null until its first read), and the hand-over pause.
    private var running = false
    private var switchedOff: Boolean? = null
    private var paused = false

    // Written under [lock]; read bare by the send gates, which only need a recent answer.
    @Volatile private var innerUp = false

    private val offState = MutableStateFlow(false)

    override val health: StateFlow<TransportHealth> =
        combine(inner.health, offState) { health, isOff -> if (isOff) TransportHealth.Unavailable else health }
            .stateIn(scope, SharingStarted.Eagerly, inner.health.value)

    init {
        scope.launch {
            off.distinctUntilChanged().collect { value ->
                synchronized(lock) {
                    switchedOff = value
                    offState.value = value
                    val wasUp = innerUp
                    reconcile()
                    if (wasUp != innerUp) log("debug switch: ${inner.kind} ${if (innerUp) "on → started" else "off → stopped"}")
                }
            }
        }
    }

    override fun start() {
        synchronized(lock) {
            running = true
            if (switchedOff == true) log("debug switch: ${inner.kind} is off — not starting")
            reconcile()
        }
    }

    override fun stop() {
        synchronized(lock) {
            running = false
            reconcile()
        }
    }

    override fun heal() {
        if (innerUp) inner.heal()
    }

    override fun pause() {
        synchronized(lock) {
            paused = true
            if (innerUp) inner.pause()
        }
    }

    override fun resume() {
        synchronized(lock) {
            paused = false
            if (innerUp) inner.resume()
        }
    }

    override fun expectBulkTransfer(nodeId: String): Boolean = innerUp && inner.expectBulkTransfer(nodeId)

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        if (innerUp) inner.send(wire, to)
    }

    override fun fastFanout(wire: WireEnvelope) {
        if (innerUp) inner.fastFanout(wire)
    }

    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        if (innerUp) inner.fastSend(wire, to)
    }

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean = innerUp && inner.sendFile(file, to, meta)

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        if (innerUp) inner.sendDigest(to, ids)
    }

    /** Brings [inner] to what the mesh and the switch want. Caller holds [lock]. */
    private fun reconcile() {
        val want = running && switchedOff == false
        if (want && !innerUp) {
            inner.start()
            innerUp = true
            // The transport's own stop() forgot the hand-over; a pause still in force goes back on.
            if (paused) inner.pause()
        } else if (!want && innerUp) {
            inner.stop()
            innerUp = false
        }
    }
}
