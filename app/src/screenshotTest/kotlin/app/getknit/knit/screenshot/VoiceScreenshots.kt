package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.voice.VoiceNoteBubblePreview
import app.getknit.knit.ui.voice.VoiceNoteLoadingPreview
import app.getknit.knit.ui.voice.VoiceRecordingBarLockedPreview
import app.getknit.knit.ui.voice.VoiceRecordingBarPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.voice`.

@PreviewTest
@ComponentShots
@Composable
fun VoiceNoteBubble() = VoiceNoteBubblePreview()

@PreviewTest
@ComponentShots
@Composable
fun VoiceNoteLoading() = VoiceNoteLoadingPreview()

@PreviewTest
@ComponentShots
@Composable
fun VoiceRecordingBar() = VoiceRecordingBarPreview()

@PreviewTest
@ComponentShots
@Composable
fun VoiceRecordingBarLocked() = VoiceRecordingBarLockedPreview()
