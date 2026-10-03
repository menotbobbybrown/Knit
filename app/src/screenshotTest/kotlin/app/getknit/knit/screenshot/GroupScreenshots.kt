package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.group.GroupDetailsScreenPreview
import app.getknit.knit.ui.group.MemberRowOnlinePreview
import app.getknit.knit.ui.group.MemberRowSelfPreview
import app.getknit.knit.ui.group.RenameGroupDialogPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.group`.

@PreviewTest
@ScreenShots
@Composable
fun GroupDetailsScreen() = GroupDetailsScreenPreview()

@PreviewTest
@ComponentShots
@Composable
fun MemberRowSelf() = MemberRowSelfPreview()

@PreviewTest
@ComponentShots
@Composable
fun MemberRowOnline() = MemberRowOnlinePreview()

@PreviewTest
@ComponentShots
@Composable
fun RenameGroupDialog() = RenameGroupDialogPreview()
