package app.getknit.knit.notifications

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.identity.PeerLabelIndex
import app.getknit.knit.ui.OfferedConversations
import app.getknit.knit.ui.ShortcutWatch
import app.getknit.knit.ui.theme.ThemePreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The conversation shortcuts kept inside the chat list's universe (ADR 2026-10.jbsa, GitHub issue #33): a pass after a
 * deleted or blocked chat takes it out of the launcher's long-press menu, a pinned one is disabled and comes back with
 * its chat, a new photo or name reaches a shortcut without a message, and nothing a pass does ever makes a shortcut a
 * notification did not. Over [FakeShortcutStore], whose semantics are the platform's where Robolectric's are not; the
 * one round trip through the real store is the fingerprint's.
 */
@RunWith(AndroidJUnit4::class)
class ConversationShortcutSyncTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val themePrefs = mockk<ThemePreferences> { every { dynamicColor } returns MutableStateFlow(false) }
    private val store = FakeShortcutStore()
    private val shortcuts = ConversationShortcuts(app, themePrefs, store)
    private val notifier = mockk<Notifier>(relaxed = true)

    /** What the chat list offers now, and each conversation's face; the tests move both. */
    private var offeredIds = setOf<String>()
    private val facesNow = mutableMapOf<String, NotifConversation>()
    private val offered =
        mockk<OfferedConversations> {
            coEvery { snapshot() } answers { OfferedConversations.Snapshot(ME, offeredIds, PeerLabelIndex.EMPTY) }
            coEvery { isOffered(any()) } answers { firstArg<String>() in offeredIds }
        }
    private val faces =
        mockk<ConversationFaces> {
            coEvery { resolve(any(), any(), any()) } answers { facesNow.getValue(firstArg()) }
        }
    private var offeredRead = false

    private fun sync(scope: TestScope) =
        ConversationShortcutSync(
            shortcuts = shortcuts,
            notifier = notifier,
            scope = scope.backgroundScope,
            offered =
                lazy {
                    offeredRead = true
                    offered
                },
            faces = lazy { faces },
        )

    /** What a message notification does: publishes the conversation's shortcut, drawn as the notification is. */
    private fun notified(
        conversation: NotifConversation,
        into: ConversationShortcuts = shortcuts,
    ) {
        facesNow[conversation.conversationId] = conversation
        val title = into.titleOf(conversation.kind, conversation.title)
        into.push(conversation, title, into.avatarOf(conversation, title))
    }

    private fun dm(
        id: String,
        name: String,
        photo: ByteArray? = null,
    ) = NotifConversation(id, name, photo, ConversationKind.DM)

    @Test
    fun aDeletedChatLeavesTheMenuAndTheOneStillOfferedStays() =
        runTest {
            notified(dm(ALICE, "Alice"))
            notified(dm(BOB, "Bob"))
            offeredIds = setOf(ALICE)

            sync(this).pass()

            assertEquals(setOf(ALICE), store.records.keys)
        }

    @Test
    fun theShadeIsHandedTheSameVerdict() =
        runTest {
            notified(dm(ALICE, "Alice"))
            notified(dm(BOB, "Bob"))
            offeredIds = setOf(ALICE)

            sync(this).pass()

            verify {
                notifier.retainConversations(
                    offered = setOf(ALICE),
                    refreshed = mapOf(ALICE to facesNow.getValue(ALICE)),
                    since = any(),
                )
            }
        }

    @Test
    fun aContactsNewPhotoAndNameReachTheirShortcutWithoutAMessage() =
        runTest {
            notified(dm(ALICE, "Alice"))
            offeredIds = setOf(ALICE)
            facesNow[ALICE] = dm(ALICE, "Alice Liddell", photo = PHOTO)

            sync(this).pass()

            assertEquals("Alice Liddell", store.label(ALICE))
        }

    @Test
    fun aPassWithNothingChangedWritesNothing() =
        runTest {
            notified(dm(ALICE, "Alice", photo = PHOTO))
            offeredIds = setOf(ALICE)

            sync(this).pass()

            assertEquals(0, store.updates)
        }

    @Test
    fun anUpdateTheRateLimitRefusedIsMadeByTheNextPass() =
        runTest {
            notified(dm(ALICE, "Alice"))
            offeredIds = setOf(ALICE)
            facesNow[ALICE] = dm(ALICE, "Alice Liddell")
            val sync = sync(this)
            store.refuseUpdates = true
            sync.pass()
            assertEquals("Alice", store.label(ALICE))

            store.refuseUpdates = false
            sync.pass()

            assertEquals("Alice Liddell", store.label(ALICE))
        }

    @Test
    fun aPinnedShortcutOfAGoneChatIsDisabledAndComesBackWithIt() =
        runTest {
            notified(dm(BOB, "Bob"))
            store.pin(BOB)
            val sync = sync(this)

            offeredIds = emptySet()
            sync.pass()
            assertFalse(store.records.getValue(BOB).enabled)
            assertFalse(store.records.getValue(BOB).live)

            offeredIds = setOf(BOB)
            sync.pass()
            assertTrue(store.records.getValue(BOB).enabled)
        }

    @Test
    fun aPassNeverMakesAShortcutForAnOfferedChatThatHasNone() =
        runTest {
            notified(dm(ALICE, "Alice"))
            offeredIds = setOf(ALICE, BOB)
            facesNow[BOB] = dm(BOB, "Bob")

            sync(this).pass()

            assertEquals(setOf(ALICE), store.records.keys)
        }

    @Test
    fun aShortcutPushedWhileThePassReadItsInputsIsLeftAsPushed() =
        runTest {
            notified(dm(BOB, "Bob"))
            // Bob's chat was deleted, and his next message lands while the pass is reading the chat list: the read
            // still says gone, but the push is newer than anything the pass knows.
            coEvery { offered.snapshot() } answers {
                notified(dm(BOB, "Bob"))
                OfferedConversations.Snapshot(ME, emptySet(), PeerLabelIndex.EMPTY)
            }

            sync(this).pass()

            assertEquals(setOf(BOB), store.records.keys)
        }

    @Test
    fun aPhoneWithNoShortcutNeverOpensTheChatListsInputs() =
        runTest {
            sync(this).pass()

            assertFalse(offeredRead)
            coVerify(exactly = 0) { offered.snapshot() }
        }

    @Test
    fun forgettingAConversationTakesItsLiveShortcutAndDisablesAPinnedOne() {
        notified(dm(ALICE, "Alice"))
        notified(dm(BOB, "Bob"))
        store.pin(BOB)

        shortcuts.forget(listOf(ALICE, BOB))

        assertFalse(ALICE in store.records)
        assertFalse(store.records.getValue(BOB).enabled)
    }

    @Test
    fun theWatchReadsTheShortcutsOwnRowsAndNoOthers() =
        runTest {
            // The watch must never read the whole peers table: every profile frame writes it.
            val group =
                NotifConversation(
                    GROUP,
                    null,
                    null,
                    ConversationKind.GROUP,
                    listOf(NotifFace(CAROL, "Carol", null), NotifFace(DAN, "Dan", null)),
                )
            notified(dm(ALICE, "Alice"))
            notified(group)
            notified(dm(BOB, "Bob"))
            val sync = sync(this)
            assertEquals("a push is watched before any pass", ShortcutWatch(setOf(ALICE, BOB), setOf(GROUP)), sync.watch.first())

            offeredIds = setOf(ALICE, GROUP)
            sync.pass()

            // Bob's chat went, but this process pushed him: watching one row too many is harmless.
            assertEquals(ShortcutWatch(setOf(ALICE, BOB, CAROL, DAN), setOf(GROUP)), sync.watch.first())
        }

    @Test
    fun theGateAnswersByTheChatListsUniverse() =
        runTest {
            offeredIds = setOf(ALICE)
            val sync = sync(this)

            assertTrue(sync.isOffered(ALICE))
            assertFalse(sync.isOffered(BOB))
        }

    @Test
    fun aPushedShortcutCarriesThroughThePlatformTheFingerprintAPassComputes() {
        // The real store: an unchanged face must read as unchanged after the round trip, or every pass rewrites
        // every shortcut and spends the background rate limit on nothing.
        val platform = ConversationShortcuts(app, themePrefs)
        val alice = dm(ALICE, "Alice", photo = PHOTO)
        notified(alice, into = platform)

        val state = platform.snapshot().states.single()

        assertEquals(shortcutFingerprint("Alice", alice, "static"), state.fingerprint)
        assertTrue(planShortcuts(listOf(state), mapOf(ALICE to shortcutFingerprint("Alice", alice, "static"))).isEmpty)
    }

    private companion object {
        const val ME = "me"
        const val ALICE = "alice"
        const val BOB = "bob"
        const val CAROL = "carol"
        const val DAN = "dan"
        const val GROUP = "g-climbing"
        val PHOTO = byteArrayOf(1, 2, 3)
    }
}
