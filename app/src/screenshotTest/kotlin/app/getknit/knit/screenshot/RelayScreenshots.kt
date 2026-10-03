package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.relay.InternetRelayScreenEmptyPreview
import app.getknit.knit.ui.relay.InternetRelayScreenOnPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.relay`.

@PreviewTest
@ScreenShots
@Composable
fun InternetRelayScreenOn() = InternetRelayScreenOnPreview()

@PreviewTest
@ScreenShots
@Composable
fun InternetRelayScreenEmpty() = InternetRelayScreenEmptyPreview()
