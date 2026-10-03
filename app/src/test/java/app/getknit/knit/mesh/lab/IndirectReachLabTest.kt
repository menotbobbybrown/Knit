package app.getknit.knit.mesh.lab

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The radio mesh's indirect-reach tier end to end (ADR 2026-10.fw8g), through the real pipeline: a peer whose
 * own frames another phone hands us is held as heard via that phone, and a peer we hold a link to never is.
 * The field report: an iPhone two hops out got DMs through two Pixels and acked them the same way, while the
 * reading phone's Diagnostics listed it only as Known.
 *
 * A line is the one shape whose answer is fixed: every frame between the ends crosses the middle, so the hop
 * each end records cannot depend on which copy won a race.
 */
@RunWith(RobolectricTestRunner::class)
class IndirectReachLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    @Test
    fun theEndsOfALineHearEachOtherThroughTheMiddle() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            // Acquaintance alone already records each end via Bob: a profile is signed, fresh, and crosses the
            // middle. So each step below reads the stamp first, moves the clock a millisecond (LabClock is wall time
            // plus an offset, so any later stamp is strictly newer) and waits for a newer one — the DMs' own
            // evidence, the field report's shape, not the handshake's.
            val thread: (LabNode) -> String = { it.dmWith(if (it === alice) carol else alice) }
            assertTrue(carol.sendDm(alice, "from the far end"))
            // The reply waits for the first DM to land, or both ends would initiate a session at once.
            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob), conversation = thread)

            val carolSawAlice = heardAt(carol, alice)
            lab.clock.advance(1)
            assertTrue(alice.sendDm(carol, "and back"))
            lab.assertConverged(listOf(alice, carol), atLeast = 2, carriers = listOf(bob), conversation = thread)
            lab.await(1) { if (heardAt(carol, alice) > carolSawAlice && via(carol, alice) == bob.nodeId) 1 else 0 }

            val aliceSawCarol = heardAt(alice, carol)
            lab.clock.advance(1)
            assertTrue(carol.sendDm(alice, "once more"))
            lab.assertConverged(listOf(alice, carol), atLeast = 3, carriers = listOf(bob), conversation = thread)
            lab.await(1) { if (heardAt(alice, carol) > aliceSawCarol && via(alice, carol) == bob.nodeId) 1 else 0 }

            // Bob hears both ends over their own links and nobody else is on the line, so no frame of anyone's
            // can reach him second-hand first: a first-seen frame from an end always came from that end.
            assertEquals(emptySet<String>(), bob.manager.heardIndirectly.value.keys)
            assertEquals(setOf(carol.nodeId), alice.manager.heardIndirectly.value.keys)
            assertEquals(setOf(alice.nodeId), carol.manager.heardIndirectly.value.keys)
        }

    /** When [reader] last heard [author] second-hand, or −1 for never. */
    private fun heardAt(
        reader: LabNode,
        author: LabNode,
    ): Long =
        reader.manager.heardIndirectly.value[author.nodeId]
            ?.heardAt ?: -1L

    /** The neighbour that last handed [reader] a frame of [author]'s. */
    private fun via(
        reader: LabNode,
        author: LabNode,
    ): String? =
        reader.manager.heardIndirectly.value[author.nodeId]
            ?.via
}
