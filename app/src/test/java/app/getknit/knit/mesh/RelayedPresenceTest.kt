package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radio mesh's indirect-reach tracker (ADR 2026-10.fw8g). The report it answers: an iPhone two hops out was
 * getting DMs through two Pixels and acking them the same way, and Diagnostics listed it only as Known — no tier
 * held a peer whose own frames another phone handed us. Every exclusion below is a way the tier could claim
 * someone who is not there.
 */
class RelayedPresenceTest {
    private var now = 10 * 24 * 60 * 60_000L

    private fun tracker(cap: Int = RELAYED_CAP) = RelayedPresence(selfId = { SELF }, clock = { now }, cap = cap)

    private fun env(
        senderId: String = AUTHOR,
        type: String = FrameType.CHAT,
        sentAt: Long = now - 60_000L,
    ) = RelayEnvelope(type = type, id = "id-$senderId-$sentAt", senderId = senderId, sentAt = sentAt, payload = ByteArray(0))

    private fun signed() = WireEnvelope(sig = byteArrayOf(1), signed = byteArrayOf(2))

    private suspend fun RelayedPresence.hear(
        env: RelayEnvelope = env(),
        from: String = HOP,
        kind: TransportKind = TransportKind.WifiAware,
        wire: WireEnvelope = signed(),
    ) = note(wire, env, from, kind)

    @Test
    fun aFreshSignedFrameAnotherPhoneHandedUsCountsWithItsHop() =
        runTest {
            val t = tracker()
            t.hear()
            assertEquals(mapOf(AUTHOR to HOP), indirectPeers(t.heard.value, now))
            assertEquals(
                now,
                t.heard.value
                    .getValue(AUTHOR)
                    .heardAt,
            )
        }

    @Test
    fun theLatestHopWins() =
        runTest {
            val t = tracker()
            t.hear(from = HOP)
            now += 1_000L
            t.hear(env = env(sentAt = now), from = "kai", kind = TransportKind.Bluetooth)
            assertEquals(mapOf(AUTHOR to "kai"), indirectPeers(t.heard.value, now))
        }

    @Test
    fun aFrameItsAuthorHandedUsIsNotIndirect() =
        runTest {
            // A link or a sighting — and every source that cannot name a hop (a BLE side page, a NAN fast-frame
            // whose hop is unknown, a custody replay) reports the author as its source, so it lands here too.
            val t = tracker()
            t.hear(from = AUTHOR)
            assertTrue(t.heard.value.isEmpty())
        }

    @Test
    fun theOtherPlanesKeepTheirOwnTrackers() =
        runTest {
            val t = tracker()
            t.hear(kind = TransportKind.LoRa)
            t.hear(from = "spool:wss://spool.example/spool/v1")
            assertTrue("LoRa and the spool are long-range reach, not the radio mesh", t.heard.value.isEmpty())
        }

    @Test
    fun theLabsOtherKindStillCounts() =
        runTest {
            // The mesh-in-a-box transports report no radio; the rule names what it excludes, not what it admits.
            val t = tracker()
            t.hear(kind = TransportKind.Other)
            assertEquals(setOf(AUTHOR), t.heard.value.keys)
        }

    @Test
    fun ourOwnFramesLoopingBackNeverCount() =
        runTest {
            val t = tracker()
            t.hear(env = env(senderId = SELF))
            assertTrue(t.heard.value.isEmpty())
        }

    @Test
    fun onlyASignatureVerifiedFrameNamesItsAuthor() =
        runTest {
            val t = tracker()
            // The unsigned door: a v3 sealed tick, opened (and so authenticated) only later.
            t.hear(wire = WireEnvelope(sig = ByteArray(0), signed = byteArrayOf(2)))
            // A blob request passes verifyInbound unchecked.
            t.hear(env = env(type = FrameType.BLOB_REQ))
            assertTrue(t.heard.value.isEmpty())
        }

    @Test
    fun aReServedFrameSaysWhereItHasBeenNotWhereItsAuthorIs() =
        runTest {
            val t = tracker()
            t.hear(env = env(sentAt = now - PRESENCE_FRESH_MS - 1))
            // Stricter than the LoRa rule on purpose: on this plane other people's old profiles are handed over
            // on every new link (KeyExchange.serveKey, custody re-serves).
            t.hear(env = env(type = FrameType.PROFILE, sentAt = now - PRESENCE_FRESH_MS - 1))
            assertTrue(t.heard.value.isEmpty())
            t.hear(env = env(type = FrameType.PROFILE, sentAt = now - PRESENCE_FRESH_MS))
            assertEquals("a fresh publish still counts", setOf(AUTHOR), t.heard.value.keys)
        }

    @Test
    fun theLingerIsAppliedAtReadAndTheSweepOnlyBoundsMemory() =
        runTest {
            val t = tracker()
            t.hear()
            now += PRESENCE_LINGER_MS
            assertEquals("right at the linger", setOf(AUTHOR), indirectPeers(t.heard.value, now).keys)
            now += 1
            assertTrue("aged out before any sweep ran", indirectPeers(t.heard.value, now).isEmpty())
            assertEquals("still held until the sweep", setOf(AUTHOR), t.heard.value.keys)
            t.sweep()
            assertTrue(t.heard.value.isEmpty())
        }

    @Test
    fun aStoppedMeshForgetsEveryoneAndHearsNothingUntilItStarts() =
        runTest {
            val t = tracker()
            t.hear()
            t.stop()
            assertTrue(t.heard.value.isEmpty())
            // A frame the cancelled session was still verifying lands after the clear: it must not resurrect anyone.
            t.hear()
            assertTrue(t.heard.value.isEmpty())
            t.start()
            t.hear()
            assertEquals(setOf(AUTHOR), t.heard.value.keys)
        }

    @Test
    fun aFullTableDropsItsStalestAuthor() =
        runTest {
            val t = tracker(cap = 2)
            t.hear(env = env(senderId = "a"))
            now += 1_000L
            t.hear(env = env(senderId = "b"))
            now += 1_000L
            t.hear(env = env(senderId = "c"))
            assertEquals(setOf("b", "c"), t.heard.value.keys)
            now += 1_000L
            t.hear(env = env(senderId = "b"))
            assertEquals("refreshing a held author never evicts", setOf("b", "c"), t.heard.value.keys)
        }

    private companion object {
        const val SELF = "self"
        const val AUTHOR = "iphone"
        const val HOP = "alex"
    }
}
