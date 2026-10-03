package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.blocked.BlockedUserRowPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.blocked`.

@PreviewTest
@ComponentShots
@Composable
fun BlockedUserRow() = BlockedUserRowPreview()
