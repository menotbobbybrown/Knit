package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.donate.DonateScreenPreview
import app.getknit.knit.ui.donate.PlatformRowPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.donate`.

@PreviewTest
@ComponentShots
@Composable
fun PlatformRow() = PlatformRowPreview()

@PreviewTest
@ScreenShots
@Composable
fun DonateScreen() = DonateScreenPreview()
