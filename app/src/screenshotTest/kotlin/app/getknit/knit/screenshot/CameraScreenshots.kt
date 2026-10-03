package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.camera.CameraDeniedFamilyLinkPreview
import app.getknit.knit.ui.camera.CameraDeniedPreview
import app.getknit.knit.ui.camera.CameraUnavailablePreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.camera`.

@PreviewTest
@ComponentShots
@Composable
fun CameraDenied() = CameraDeniedPreview()

@PreviewTest
@ComponentShots
@Composable
fun CameraDeniedFamilyLink() = CameraDeniedFamilyLinkPreview()

@PreviewTest
@ComponentShots
@Composable
fun CameraUnavailable() = CameraUnavailablePreview()
