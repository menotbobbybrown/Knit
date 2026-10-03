package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.yourmesh.YourMeshScreenFreshPreview
import app.getknit.knit.ui.yourmesh.YourMeshScreenPopulatedPreview
import app.getknit.knit.ui.yourmesh.YourMeshScreenRadiosOffPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.yourmesh`.

@PreviewTest
@ScreenShots
@Composable
fun YourMeshScreenPopulated() = YourMeshScreenPopulatedPreview()

@PreviewTest
@ScreenShots
@Composable
fun YourMeshScreenFresh() = YourMeshScreenFreshPreview()

@PreviewTest
@ScreenShots
@Composable
fun YourMeshScreenRadiosOff() = YourMeshScreenRadiosOffPreview()
