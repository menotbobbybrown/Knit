package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.invite.ShareKnitDialogPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.invite`.

@PreviewTest
@ComponentShots
@Composable
fun ShareKnitDialog() = ShareKnitDialogPreview()
