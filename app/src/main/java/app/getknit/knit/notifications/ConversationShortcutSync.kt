package app.getknit.knit.notifications

import android.util.Log
import app.getknit.knit.ui.OfferedConversations
import app.getknit.knit.ui.ShortcutWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether a thread opened from outside the app — a shortcut's or a notification's route, a notification's inline
 * Reply — is one the chat list still offers (ADR 2026-10.jbsa). A seam so the receiver and the nav host are tested
 * against a fake.
 */
fun interface ConversationGate {
    suspend fun isOffered(conversationId: String): Boolean
}

/**
 * Keeps the conversation shortcuts inside the chat list's universe and each one's face current (ADR 2026-10.jbsa).
 *
 * A shortcut is born of a notification and nothing else removed it: a deleted chat, a blocked peer, a left group kept
 * its place in the launcher's long-press menu and opened an empty thread, where one message made the person a contact
 * again (GitHub #33); a contact's new photo never reached it until their next message did. Rather than hook every
 * path that can take a thread away — eleven, and the next one would be missed — a pass compares what is published
 * with what [OfferedConversations] offers, and [ConversationShortcuts.apply] removes, disables, enables and refreshes
 * to match, never creating one.
 *
 * Passes run at start (a build that left stale shortcuts is cleaned on the update), when the app's window stops
 * ([requestPass] from `MainActivity.onStop` — every removal made in the app has happened by the time the launcher can
 * show its menu again), and a few seconds after the cheap inputs settle ([OfferedConversations.changes]: a block, a
 * renamed or re-pictured contact or group, both of which also arrive in the background). One at a time, under
 * [mutex]. The database is never opened for a phone with no shortcut: [offered] and [faces] are resolved on the
 * first pass that has something to keep, and the inputs are watched only once one has been published.
 */
internal class ConversationShortcutSync(
    private val shortcuts: ConversationShortcuts,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
    offered: Lazy<OfferedConversations>,
    faces: Lazy<ConversationFaces>,
    private val settleMs: Long = SETTLE_MS,
) : ConversationGate {
    private val offered by offered
    private val faces by faces
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)

    /** The rows the last pass found its shortcuts wearing: each DM's peer, each group, each cluster face's peer. */
    private val watchedByPass = MutableStateFlow(ShortcutWatch())

    /** What the watch reads: the last pass's rows, and a fresh push's thread before any pass has seen it. */
    internal val watch: Flow<ShortcutWatch> =
        combine(watchedByPass, shortcuts.pushedIds) { byPass, pushed -> byPass + ShortcutWatch.of(pushed) }

    /** The first pass, then the watch on the inputs once there is a shortcut to keep. Idempotent. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            pass()
            shortcuts.published.first { it }
            guarded("watch") {
                // Re-subscribed when the watched rows move (a pass found a new shortcut, a push named a new thread);
                // each subscription's first emission is the state it subscribed to, not a change.
                watch
                    .distinctUntilChanged()
                    .flatMapLatest { offered.changes(it).drop(1) }
                    .debounce(settleMs)
                    .collect { pass() }
            }
        }
    }

    /** One pass, soon, off the caller's thread. */
    fun requestPass() {
        scope.launch { pass() }
    }

    override suspend fun isOffered(conversationId: String): Boolean =
        withContext(Dispatchers.Default) {
            // A check that cannot be made lets the thread open, as every route did before this gate existed: the
            // gate is about a thread that is known to be gone, not a reason to refuse one when storage is slow.
            var answer = true
            guarded("isOffered") { answer = offered.isOffered(conversationId) }
            answer
        }

    /** Compares the published shortcuts and the posted notifications with what the chat list offers, and applies it. */
    suspend fun pass() {
        mutex.withLock {
            guarded("pass") {
                val snapshot = shortcuts.snapshot()
                // Every message notification pushes its conversation's shortcut, so the shortcuts are the whole set of
                // threads named outside the app; none at all, and there is nothing to keep.
                if (snapshot.states.isEmpty()) return@guarded
                val now = offered.snapshot()
                val current = snapshot.ids.filter { it in now.ids }.associateWith { faces.resolve(it, now.me, now.labels) }
                if (!shortcuts.apply(snapshot, current)) Log.i(TAG, "shortcut update refused (rate limit); next pass retries")
                notifier.retainConversations(offered = now.ids, refreshed = current, since = snapshot.epoch)
                watchedByPass.value =
                    ShortcutWatch.of(current.keys) +
                    ShortcutWatch(peerIds = current.values.flatMapTo(HashSet()) { face -> face.faces.map { it.nodeId } })
            }
        }
    }

    /** Runs [block], logging anything but a cancellation: a failed pass must never take the app scope down with it. */
    private suspend fun guarded(
        what: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A Keystore refusal on the database's first open lands here too (ADR 2026-10.47rw): the StorageGate
            // owns that, and the next pass tries again.
            Log.w(TAG, "shortcut $what failed", e)
        }
    }

    companion object {
        private const val TAG = "KnitShortcuts"

        /** How long the inputs must stay still before a pass: a profile flood re-pins many rows in a burst. */
        const val SETTLE_MS = 5_000L

        /** How long after process start the first pass waits: past the cold start, which has better uses for the disk. */
        const val START_DELAY_MS = 10_000L
    }
}
