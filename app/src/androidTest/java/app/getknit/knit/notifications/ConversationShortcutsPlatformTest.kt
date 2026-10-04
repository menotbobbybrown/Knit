package app.getknit.knit.notifications

import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.ui.theme.NightMode
import app.getknit.knit.ui.theme.ThemeMode
import app.getknit.knit.ui.theme.ThemePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ConversationShortcuts] over the platform's own shortcut service (ADR 2026-10.jbsa) — the semantics
 * `FakeShortcutStore` stands in for on the JVM, where Robolectric's shadow drops `removeLongLivedShortcuts`: a pass
 * takes a gone conversation's dynamic shortcut away, refreshes an offered one in place, and the fingerprint survives
 * the round trip so an unchanged face is never rewritten. A cached or a pinned shortcut needs the system's
 * conversation notification or the launcher's consent, so those halves are the device trial's.
 */
@RunWith(AndroidJUnit4::class)
class ConversationShortcutsPlatformTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val themePrefs =
        ThemePreferences(
            flowOf(false),
            flowOf(ThemeMode.System),
            NightMode { },
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    private val shortcuts = ConversationShortcuts(context, themePrefs)

    @Before
    fun setUp() = ConversationShortcuts.wipeAll(context)

    @After
    fun tearDown() = ConversationShortcuts.wipeAll(context)

    private fun notified(conversation: NotifConversation) {
        val title = shortcuts.titleOf(conversation.kind, conversation.title)
        shortcuts.push(conversation, title, shortcuts.avatarOf(conversation, title))
    }

    private fun dm(
        id: String,
        name: String,
    ) = NotifConversation(id, name, null, ConversationKind.DM)

    private fun labels(): Map<String, String> =
        ShortcutManagerCompat
            .getShortcuts(
                context,
                ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED or
                    ShortcutManagerCompat.FLAG_MATCH_PINNED,
            ).associate { it.id to it.shortLabel.toString() }

    @Test
    fun aPassRemovesAGoneConversationAndRefreshesAnOfferedOneInPlace() {
        notified(dm(ALICE, "Alice"))
        notified(dm(BOB, "Bob"))
        val snapshot = shortcuts.snapshot()
        assertTrue(snapshot.states.all { it.live && it.enabled && it.fingerprint != null })

        val refused = !shortcuts.apply(snapshot, mapOf(ALICE to dm(ALICE, "Alice Liddell")))

        assertEquals(mapOf(ALICE to "Alice Liddell"), labels())
        assertTrue("the app is in the foreground under instrumentation: no rate limit", !refused)
        // The refreshed shortcut now reads as current: the next pass has nothing to do.
        val after = shortcuts.snapshot()
        assertTrue(
            planShortcuts(after.states, mapOf(ALICE to shortcutFingerprint("Alice Liddell", dm(ALICE, "Alice Liddell"), "static"))).isEmpty,
        )
    }

    @Test
    fun forgettingAConversationTakesItsShortcut() {
        notified(dm(ALICE, "Alice"))
        notified(dm(BOB, "Bob"))

        shortcuts.forget(listOf(BOB))

        assertEquals(setOf(ALICE), labels().keys)
    }

    private companion object {
        const val ALICE = "platform-test-alice"
        const val BOB = "platform-test-bob"
    }
}
