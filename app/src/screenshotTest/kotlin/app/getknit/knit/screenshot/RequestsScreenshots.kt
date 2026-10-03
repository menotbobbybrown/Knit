package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.requests.MessageRequestsScreenContentEmptyPreview
import app.getknit.knit.ui.requests.MessageRequestsScreenContentPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.requests`.

@PreviewTest
@ScreenShots
@Composable
fun MessageRequestsScreenContent() = MessageRequestsScreenContentPreview()

@PreviewTest
@ScreenShots
@Composable
fun MessageRequestsScreenContentEmpty() = MessageRequestsScreenContentEmptyPreview()
