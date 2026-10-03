package app.getknit.knit.ui.chat

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.relay.AttachmentWait
import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.ui.theme.KnitTheme
import app.getknit.knit.ui.voice.VoiceNoteBubble
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A photo, file or voice note whose bytes are streaming in shows how far they have got (#115): a ring and "84 kB
 * of 204 kB" against a declared total, a still glyph and the count without one, the waiting line while no link
 * carries them, and nothing once the bytes are held. Every arriving case ends idle — the ring's motion is finite
 * (ADR 047). The photo is driven through the whole screen, since its placeholder lives in a private composable.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArrivalIndicatorTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    private fun photo(
        arrival: ArrivingFile?,
        ready: Boolean = false,
    ) = compose.setContent {
        KnitTheme {
            ChatScreenContent(
                conversationId = Conversations.NEARBY,
                state =
                    ChatUiState(
                        rows =
                            listOf(
                                ChatRow(
                                    id = "p1",
                                    body = "",
                                    mine = false,
                                    senderName = "Ada",
                                    senderNodeId = "ada",
                                    avatarHash = null,
                                    sentAt = NOW,
                                    received = false,
                                    attachmentHash = "h1",
                                    attachmentMime = "image/jpeg",
                                    attachmentReady = ready,
                                ),
                            ),
                        isRoom = true,
                        myNodeId = "me",
                    ),
                inputState = TextFieldState(""),
                pendingAttachment = null,
                replyingTo = null,
                now = NOW,
                onBack = {},
                onOpenProfile = {},
                onOpenGroupDetails = {},
                onSend = {},
                onAttachClick = {},
                onClearAttachment = {},
                onReceiveImage = {},
                onTyping = {},
                onMentionAdded = {},
                onStartReply = {},
                onCancelReply = {},
                onReact = { _, _ -> },
                onDeleteMessage = {},
                onBlock = {},
                onUnblock = {},
                onCopy = {},
                onSaveAttachment = { _, _, _ -> },
                arrivals = listOfNotNull(arrival).associateBy { it.key },
            )
        }
    }

    private fun file(
        arrival: ArrivingFile?,
        ready: Boolean = false,
    ) = compose.setContent {
        KnitTheme {
            FileAttachmentBubble(
                name = "report.pdf",
                mime = "application/pdf",
                declaredSize = 204_000,
                heldBytes = if (ready) 204_028 else null,
                ready = ready,
                flagged = false,
                onOpen = {},
                onLongClick = {},
                wait = AttachmentWait.Relay,
                hash = "h1",
                arrival = arrival,
            )
        }
    }

    private fun voice(
        arrival: ArrivingFile?,
        ready: Boolean = false,
    ) = compose.setContent {
        KnitTheme {
            VoiceNoteBubble(
                ready = ready,
                durationMs = 7_400,
                peaks = null,
                positionMs = null,
                playing = false,
                accent = MaterialTheme.colorScheme.primary,
                onToggle = {},
                onSeek = {},
                onLongClick = {},
                wait = AttachmentWait.Relay,
                hash = "h1",
                arrival = arrival,
            )
        }
    }

    private fun assertTags(
        progress: Boolean,
        arriving: Boolean,
    ) {
        compose
            .onNodeWithTag(
                "chat_attachment_progress",
                useUnmergedTree = true,
            ).run { if (progress) assertExists() else assertDoesNotExist() }
        compose
            .onNodeWithTag(
                "chat_attachment_arriving",
                useUnmergedTree = true,
            ).run { if (arriving) assertExists() else assertDoesNotExist() }
    }

    @Test
    fun aPhotoArrivingShowsTheRingAndHowFarItHasGot() {
        photo(ArrivingFile("h1", bytes = 84_000, total = 204_028))
        assertTags(progress = true, arriving = false)
        compose.onNodeWithText("${size(84_000)} of ${size(204_028)}").assertIsDisplayed()
        compose.onNodeWithText(PHOTO_LOADING).assertDoesNotExist()
        compose.waitForIdle()
    }

    @Test
    fun aPhotoArrivingWithNoTotalShowsTheCount() {
        photo(ArrivingFile("h1", bytes = 84_000, total = null))
        assertTags(progress = false, arriving = true)
        compose.onNodeWithText("${size(84_000)} received").assertIsDisplayed()
        compose.waitForIdle()
    }

    @Test
    fun aPhotoWithNothingArrivingKeepsItsWaitLine() {
        photo(arrival = null)
        assertTags(progress = false, arriving = false)
        compose.onNodeWithText(PHOTO_LOADING).assertIsDisplayed()
    }

    @Test
    fun aHeldPhotoShowsNoProgress() {
        // A stale sample must not outlive the bytes landing: the image arm takes over and draws no ring.
        photo(ArrivingFile("h1", bytes = 84_000, total = 204_028), ready = true)
        assertTags(progress = false, arriving = false)
        compose.onNodeWithText("${size(84_000)} of ${size(204_028)}").assertDoesNotExist()
    }

    @Test
    fun aFileArrivingAgainstATotalShowsTheRingAndHowFarItHasGot() {
        file(ArrivingFile("h1", bytes = 84_000, total = 204_028))
        assertTags(progress = true, arriving = false)
        // One node, one sentence: TalkBack reads the progress where it read the size and the wait.
        val said = "report.pdf, ${size(84_000)} of ${size(204_028)} · PDF"
        compose.onNode(hasContentDescription(said)).assertExists()
        compose.waitForIdle()
    }

    @Test
    fun aFileArrivingWithNoTotalShowsTheCountBesideTheDeclaredSize() {
        file(ArrivingFile("h1", bytes = 84_000, total = null))
        assertTags(progress = false, arriving = true)
        compose.onNode(hasContentDescription("report.pdf, ${size(84_000)} received · ${size(204_000)} · PDF")).assertExists()
        compose.waitForIdle()
    }

    @Test
    fun aFileWithNothingArrivingStillWaits() {
        file(arrival = null)
        assertTags(progress = false, arriving = false)
        val said = "report.pdf, ${size(204_000)} · PDF · Waiting for the file, Can also arrive over the Internet through your relays"
        compose.onNode(hasContentDescription(said)).assertExists()
    }

    @Test
    fun aHeldFileShowsNoProgress() {
        // A stale sample must not outlive the bytes landing.
        file(ArrivingFile("h1", bytes = 84_000, total = 204_028), ready = true)
        assertTags(progress = false, arriving = false)
        compose.onNode(hasContentDescription("report.pdf, ${size(204_028)} · PDF")).assertExists()
        compose.waitForIdle()
    }

    @Test
    fun aVoiceNoteArrivingShowsTheRingInPlaceOfItsWaitLines() {
        voice(ArrivingFile("h1", bytes = 38_000, total = 61_000))
        assertTags(progress = true, arriving = false)
        compose.onNodeWithText("${size(38_000)} of ${size(61_000)}").assertIsDisplayed()
        compose.onNodeWithText(VOICE_LOADING).assertDoesNotExist()
        // The bytes are on their way over a nearby link, so where else they could come from has nothing to add.
        compose.onNodeWithTag("chat_attachment_wait", useUnmergedTree = true).assertDoesNotExist()
        compose.waitForIdle()
    }

    @Test
    fun aVoiceNoteArrivingWithNoTotalShowsTheCount() {
        voice(ArrivingFile("h1", bytes = 38_000, total = null))
        assertTags(progress = false, arriving = true)
        compose.onNodeWithText("${size(38_000)} received").assertIsDisplayed()
        compose.waitForIdle()
    }

    @Test
    fun aVoiceNoteWithNothingArrivingKeepsItsWaitLines() {
        voice(arrival = null)
        assertTags(progress = false, arriving = false)
        compose.onNodeWithText(VOICE_LOADING).assertIsDisplayed()
        compose.onNodeWithTag("chat_attachment_wait", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aHeldVoiceNoteShowsNoProgress() {
        voice(ArrivingFile("h1", bytes = 38_000, total = 61_000), ready = true)
        assertTags(progress = false, arriving = false)
        compose.onNodeWithText("${size(38_000)} of ${size(61_000)}").assertDoesNotExist()
        compose.waitForIdle()
    }
}

private const val NOW = 1_700_000_000_000L
private const val PHOTO_LOADING = "Photo appears once a device that has it is reachable"
private const val VOICE_LOADING = "Voice message appears once a device that has it is reachable"
