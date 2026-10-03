package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.contacts.ContactRowSelectedOnlinePreview
import app.getknit.knit.ui.contacts.ContactRowUnselectedOfflinePreview
import app.getknit.knit.ui.contacts.ContactsScreenEmptyPreview
import app.getknit.knit.ui.contacts.ContactsScreenLoadingPreview
import app.getknit.knit.ui.contacts.ContactsScreenPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.contacts`.

@PreviewTest
@ScreenShots
@Composable
fun ContactsScreen() = ContactsScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun ContactsScreenEmpty() = ContactsScreenEmptyPreview()

@PreviewTest
@ScreenShots
@Composable
fun ContactsScreenLoading() = ContactsScreenLoadingPreview()

@PreviewTest
@ComponentShots
@Composable
fun ContactRowSelectedOnline() = ContactRowSelectedOnlinePreview()

@PreviewTest
@ComponentShots
@Composable
fun ContactRowUnselectedOffline() = ContactRowUnselectedOfflinePreview()
