package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.review.RateReviewDialogPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.review`.

@PreviewTest
@ComponentShots
@Composable
fun RateReviewDialog() = RateReviewDialogPreview()
