package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.signout.SignOutDialogPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.signout`.

@PreviewTest
@ComponentShots
@Composable
fun SignOutDialog() = SignOutDialogPreview()
