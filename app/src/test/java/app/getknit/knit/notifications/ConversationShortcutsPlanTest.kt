package app.getknit.knit.notifications

import app.getknit.knit.data.message.ConversationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pass over the published conversation shortcuts (ADR 2026-10.jbsa): what goes, what is disabled, what is
 * refreshed, and the one thing it never does — make a shortcut for a conversation that has none.
 */
class ConversationShortcutsPlanTest {
    private fun live(
        id: String,
        fingerprint: String? = "f-$id",
    ) = ShortcutState(id, live = true, pinned = false, enabled = true, fingerprint = fingerprint)

    private fun pinned(
        id: String,
        enabled: Boolean = true,
        live: Boolean = false,
        fingerprint: String? = "f-$id",
    ) = ShortcutState(id, live = live, pinned = true, enabled = enabled, fingerprint = fingerprint)

    @Test
    fun aGoneConversationsLiveShortcutIsRemovedAndAnOfferedOneIsLeftAlone() {
        val plan = planShortcuts(listOf(live("alice"), live("bob")), offered = mapOf("alice" to "f-alice"))

        assertEquals(ShortcutPlan(remove = listOf("bob")), plan)
    }

    @Test
    fun aPinnedShortcutOfAGoneConversationIsDisabledNotRemoved() {
        val plan = planShortcuts(listOf(pinned("bob")), offered = emptyMap())

        assertEquals(ShortcutPlan(disable = listOf("bob")), plan)
    }

    @Test
    fun aShortcutBothLiveAndPinnedLosesTheLiveCopyAndHasThePinnedOneDisabled() {
        val plan = planShortcuts(listOf(pinned("bob", live = true)), offered = emptyMap())

        assertEquals(ShortcutPlan(remove = listOf("bob"), disable = listOf("bob")), plan)
    }

    @Test
    fun anAlreadyDisabledPinnedShortcutIsNotDisabledAgain() {
        val plan = planShortcuts(listOf(pinned("bob", enabled = false)), offered = emptyMap())

        assertTrue(plan.isEmpty)
    }

    @Test
    fun aDisabledShortcutWhoseConversationIsBackIsEnabledAndRefreshed() {
        // The platform will not flip `enabled` through an update, and the face may have moved while it was gone.
        val plan = planShortcuts(listOf(pinned("bob", enabled = false)), offered = mapOf("bob" to "f-bob"))

        assertEquals(ShortcutPlan(enable = listOf("bob"), update = listOf("bob")), plan)
    }

    @Test
    fun onlyAChangedFingerprintIsUpdated() {
        val plan =
            planShortcuts(
                listOf(live("alice"), live("bob")),
                offered = mapOf("alice" to "f-alice", "bob" to "new-photo"),
            )

        assertEquals(ShortcutPlan(update = listOf("bob")), plan)
    }

    @Test
    fun aShortcutAnOlderBuildPublishedIsRefreshedOnce() {
        val plan = planShortcuts(listOf(live("alice", fingerprint = null)), offered = mapOf("alice" to "f-alice"))

        assertEquals(ShortcutPlan(update = listOf("alice")), plan)
    }

    @Test
    fun anOfferedConversationWithNoShortcutIsNeverGivenOne() {
        // Only a notification makes a shortcut; a refresh that made one would bring back what a removal took away.
        val plan = planShortcuts(listOf(live("alice")), offered = mapOf("alice" to "f-alice", "carol" to "f-carol"))

        assertTrue(plan.isEmpty)
    }

    @Test
    fun anIdPushedSinceThePassReadItsInputsIsLeftToThePush() {
        val plan =
            planShortcuts(
                listOf(live("bob"), pinned("carol"), live("dave")),
                offered = mapOf("dave" to "changed"),
                skip = setOf("bob", "carol", "dave"),
            )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun theFingerprintMovesWithTheTitleThePhotoTheFacesAndThePaletteAndWithNothingElse() {
        val base = NotifConversation("g-1", "Climbing", null, ConversationKind.GROUP, listOf(NotifFace("a", "Ann", byteArrayOf(1))))
        val print = shortcutFingerprint("Climbing", base, "static")

        assertEquals(print, shortcutFingerprint("Climbing", base.copy(), "static"))
        assertEquals(print, shortcutFingerprint("Climbing", base.copy(faces = listOf(NotifFace("a", "Ann", byteArrayOf(1)))), "static"))
        assertNotEquals(print, shortcutFingerprint("Bouldering", base, "static"))
        assertNotEquals(print, shortcutFingerprint("Climbing", base.copy(avatarBytes = byteArrayOf(9)), "static"))
        assertNotEquals(print, shortcutFingerprint("Climbing", base.copy(faces = listOf(NotifFace("a", "Ann", byteArrayOf(2)))), "static"))
        assertNotEquals(print, shortcutFingerprint("Climbing", base.copy(faces = listOf(NotifFace("a", "Anna", byteArrayOf(1)))), "static"))
        assertNotEquals(print, shortcutFingerprint("Climbing", base, "wallpaper"))
    }

    @Test
    fun theFingerprintIsAGoldenValueSoARestartNeverReadsEveryShortcutAsStale() {
        // Enum names and bytes only — an identity hash in here would change with every process.
        val print = shortcutFingerprint("Alice", NotifConversation("alice", "Alice", byteArrayOf(1, 2, 3), ConversationKind.DM), "static")

        assertEquals("dd6250fed804542fb19d25f7434a974c", print)
    }
}
