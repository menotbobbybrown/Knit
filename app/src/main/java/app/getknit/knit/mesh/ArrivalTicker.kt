package app.getknit.knit.mesh

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * Samples [read] — the links' [MeshTransport.arrivingFiles] — for [MeshController.arrivals] (#115). A link raises
 * no event per chunk, and a Wi-Fi Aware link moves hundreds of chunks a second, so the chat samples instead of
 * listening: every [ARRIVAL_ACTIVE_MS] while anything is streaming in, every [ARRIVAL_IDLE_MS] while nothing is,
 * so a chat whose photo has no holder in reach wakes four times less often. Cold: nothing reads the links until a
 * chat collects it, and the chat collects only while an attachment it shows is still on its way. Equal samples
 * are dropped, so a stalled link costs no recomposition.
 */
internal fun arrivalTicker(read: () -> Map<String, ArrivingFile>): Flow<Map<String, ArrivingFile>> =
    flow {
        while (true) {
            val sample = read()
            emit(sample)
            delay(if (sample.isEmpty()) ARRIVAL_IDLE_MS else ARRIVAL_ACTIVE_MS)
        }
    }.distinctUntilChanged()

/** The sampling period while a file is streaming in: often enough that a ring fills smoothly. */
internal const val ARRIVAL_ACTIVE_MS = 500L

/** The sampling period while nothing is: how soon a newly started transfer shows its ring. */
internal const val ARRIVAL_IDLE_MS = 2_000L
