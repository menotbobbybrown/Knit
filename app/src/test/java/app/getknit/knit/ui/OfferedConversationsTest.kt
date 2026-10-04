package app.getknit.knit.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.lora.LoraFacts
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The conversations a shortcut or a route from outside the app may name: the chat list's own universe
 * ([visibleConversations]), over a real in-memory messages table (ADR 2026-10.jbsa). A deleted chat, a blocked
 * peer, a left group and a request are all outside it; the Nearby room never is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OfferedConversationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var store: InMemoryMessages
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)

    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val acceptedFlow = MutableStateFlow(setOf("sam", "jose", CLIMBING))
    private val groupsFlow = MutableStateFlow(listOf(group(CLIMBING, listOf("me", "sam", "jose"))))
    private val peersFlow = MutableStateFlow(listOf(peer("sam", "Sam"), peer("jose", "José"), peer("river", "River")))

    @Before
    fun setUp() {
        store = InMemoryMessages(dispatcher)
        coEvery { identity.nodeId() } returns "me"
        every { settings.blockedNodeIds } returns blockedFlow
        every { settings.acceptedConversations } returns acceptedFlow
        every { groups.observeGroups() } returns groupsFlow
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
    }

    @After
    fun tearDown() = store.close()

    private fun offered() =
        OfferedConversations(context, store.repo, peers, groups, settings, identity, MutableStateFlow(LoraFacts()), commons = null)

    private suspend fun seed() {
        store.add(
            msg("sam", 10, "sam", body = "hey"),
            msg("jose", 20, "jose", body = "hola"),
            // A stranger who wrote first and was never answered: a request, not a thread.
            msg("river", 30, "river", body = "hi?"),
            msg("sam", 40, CLIMBING, body = "rope?"),
        )
    }

    @Test
    fun theChatListsThreadsAreOfferedAndARequestIsNot() =
        runTest(dispatcher) {
            seed()

            val ids = offered().snapshot().ids

            assertEquals(setOf(Conversations.NEARBY, "sam", "jose", CLIMBING), ids)
        }

    @Test
    fun aDeletedChatABlockedPeerAndALeftGroupLeaveTheUniverse() =
        runTest(dispatcher) {
            seed()
            val offered = offered()

            store.repo.deleteByConversation("sam")
            blockedFlow.value = setOf("jose")
            groupsFlow.value = listOf(group(CLIMBING, listOf("me", "sam", "jose"), left = true))

            val ids = offered.snapshot().ids
            assertEquals(setOf(Conversations.NEARBY), ids)
            assertTrue(offered.isOffered(Conversations.NEARBY))
            assertFalse(offered.isOffered("sam"))
        }

    @Test
    fun theWatchWakesOnlyForAWatchedRowsFaceNeverForAnyOtherPeer() =
        runTest(StandardTestDispatcher(dispatcher.scheduler)) {
            // The real peers table, so the watched read is the `IN (:ids)` query and not the whole table.
            val dao = store.db.peerDao()
            dao.upsert(peer("sam", "Sam"))
            dao.upsert(peer("river", "River"))
            val realPeers = PeerRepository(dao, settings, identity)
            every { groups.observeGroups(any<Set<String>>()) } answers {
                val ids = firstArg<Set<String>>()
                groupsFlow.map { list -> list.filter { it.groupId in ids } }
            }
            val offered =
                OfferedConversations(context, store.repo, realPeers, groups, settings, identity, MutableStateFlow(LoraFacts()), null)
            val changes = mutableListOf<Unit>()
            backgroundScope.launch {
                offered.changes(ShortcutWatch(peerIds = setOf("sam"), groupIds = setOf(CLIMBING))).collect {
                    changes +=
                        it
                }
            }
            runCurrent()
            assertEquals(1, changes.size)

            // Every inbound profile frame re-pins its row; only a name or a photo is a face.
            dao.upsert(peer("sam", "Sam", updatedAt = 99))
            runCurrent()
            assertEquals(1, changes.size)

            // A peer with no shortcut is not read at all, whatever changes about them.
            dao.upsert(peer("river", "River Salas", avatarHash = "new"))
            runCurrent()
            assertEquals(1, changes.size)

            dao.upsert(peer("sam", "Sam", avatarHash = "new"))
            runCurrent()
            assertEquals(2, changes.size)

            groupsFlow.value = groupsFlow.value.map { it.copy(photoHash = "decided-not-shown") }
            runCurrent()
            assertEquals("a decided photo is not a shown one (ADR 2026-09.nxcq)", 2, changes.size)

            groupsFlow.value = groupsFlow.value.map { it.copy(name = "Crag rats") }
            runCurrent()
            assertEquals(3, changes.size)
        }

    @Test
    fun anEmptyWatchNeverQueriesThePeersTable() =
        runTest(dispatcher) {
            // Room re-runs a live query on every write to its table: no shortcut, no subscription at all.
            val dao = mockk<PeerDao>()

            assertEquals(emptyList<Any>(), PeerRepository(dao, settings, identity).observeFaces(emptySet()).first())

            verify(exactly = 0) { dao.observeFaces(any()) }
        }

    private companion object {
        const val CLIMBING = "g-climbing"
    }
}
