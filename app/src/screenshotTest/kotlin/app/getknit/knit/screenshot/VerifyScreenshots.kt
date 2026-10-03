package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.verify.EncryptionSectionStandalonePreview
import app.getknit.knit.ui.verify.EncryptionSectionUnverifiedPreview
import app.getknit.knit.ui.verify.EncryptionSectionVerifiedPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.verify`.

@PreviewTest
@ComponentShots
@Composable
fun EncryptionSectionVerified() = EncryptionSectionVerifiedPreview()

@PreviewTest
@ComponentShots
@Composable
fun EncryptionSectionUnverified() = EncryptionSectionUnverifiedPreview()

@PreviewTest
@ComponentShots
@Composable
fun EncryptionSectionStandalone() = EncryptionSectionStandalonePreview()
