package app.getknit.knit.screenshot

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.getknit.knit.ui.chat.ChatFullscreenImageViewerPreview
import app.getknit.knit.ui.chat.ChatScreenDmPreview
import app.getknit.knit.ui.chat.ChatScreenGroupTypingPreview
import app.getknit.knit.ui.chat.ChatScreenReplyDraftPreview
import app.getknit.knit.ui.chat.ChatScreenRoomEmptyPreview
import app.getknit.knit.ui.chat.ChatSkeletonPreview
import app.getknit.knit.ui.chat.EmojiPickerSheetEmptySearchPreview
import app.getknit.knit.ui.chat.EmojiPickerSheetLoadingPreview
import app.getknit.knit.ui.chat.EmojiPickerSheetPreview
import app.getknit.knit.ui.chat.EmptyStatePreview
import app.getknit.knit.ui.chat.FileAttachmentArrivingPreview
import app.getknit.knit.ui.chat.LinkPreviewCardHiddenPreview
import app.getknit.knit.ui.chat.LinkPreviewCardPreview
import app.getknit.knit.ui.chat.LinkPreviewLoadingRowPreview
import app.getknit.knit.ui.chat.LocationCardPreview
import app.getknit.knit.ui.chat.LoraNoticePreview
import app.getknit.knit.ui.chat.MessageBubbleEmojiPreview
import app.getknit.knit.ui.chat.MessageBubbleLinkPreview
import app.getknit.knit.ui.chat.MessageBubbleMinePreview
import app.getknit.knit.ui.chat.MessageBubbleMineViaInternetPreview
import app.getknit.knit.ui.chat.MessageBubblePhotoArrivingPreview
import app.getknit.knit.ui.chat.MessageBubbleTheirsPreview
import app.getknit.knit.ui.chat.MessageBubbleTheirsViaInternetPreview
import app.getknit.knit.ui.chat.MessageBubbleWithMentionPreview
import app.getknit.knit.ui.chat.MessageDetailsScreenEmptyPreview
import app.getknit.knit.ui.chat.MessageDetailsScreenPreview
import app.getknit.knit.ui.chat.MessageInputPreview
import app.getknit.knit.ui.chat.ReactionRowPreview
import app.getknit.knit.ui.chat.StagedLinkTilePreview
import app.getknit.knit.ui.chat.StagedLocationTileFailedPreview
import app.getknit.knit.ui.chat.StagedLocationTileReadyPreview
import app.getknit.knit.ui.chat.SystemNoticePreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.chat`.

@PreviewTest
@ComponentShots
@Composable
fun MessageBubbleTheirs() = MessageBubbleTheirsPreview()

@PreviewTest
@ComponentShots
@Composable
fun MessageBubbleMine() = MessageBubbleMinePreview()

@PreviewTest
@ComponentShots
@Composable
fun MessageBubbleTheirsViaInternet() = MessageBubbleTheirsViaInternetPreview()

@PreviewTest
@ComponentShots
@Composable
fun MessageBubbleMineViaInternet() = MessageBubbleMineViaInternetPreview()

@PreviewTest
@ComponentShots
@Composable
fun MessageBubbleWithMention() = MessageBubbleWithMentionPreview()

@PreviewTest
@ComponentShots
@Composable
fun MessageBubblePhotoArriving() = MessageBubblePhotoArrivingPreview()

@PreviewTest
@ComponentShots
@Composable
fun MessageBubbleEmoji() = MessageBubbleEmojiPreview()

@PreviewTest
@ComponentShots
@Composable
fun ReactionRow() = ReactionRowPreview()

@PreviewTest
@ComponentShots
@Composable
fun MessageInput() = MessageInputPreview()

@PreviewTest
@ComponentShots
@Composable
fun EmptyState() = EmptyStatePreview()

@PreviewTest
@Preview(name = "Light", showBackground = true, heightDp = 420)
@Preview(name = "Dark", showBackground = true, heightDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ChatSkeleton() = ChatSkeletonPreview()

@PreviewTest
@ComponentShots
@Composable
fun LoraNotice() = LoraNoticePreview()

@PreviewTest
@ComponentShots
@Composable
fun SystemNotice() = SystemNoticePreview()

@PreviewTest
@ComponentShots
@Composable
fun MessageBubbleLink() = MessageBubbleLinkPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatFullscreenImageViewer() = ChatFullscreenImageViewerPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatScreenDm() = ChatScreenDmPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatScreenGroupTyping() = ChatScreenGroupTypingPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatScreenRoomEmpty() = ChatScreenRoomEmptyPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatScreenReplyDraft() = ChatScreenReplyDraftPreview()

@PreviewTest
@ComponentShots
@Composable
fun EmojiPickerSheet() = EmojiPickerSheetPreview()

@PreviewTest
@ComponentShots
@Composable
fun EmojiPickerSheetLoading() = EmojiPickerSheetLoadingPreview()

@PreviewTest
@ComponentShots
@Composable
fun EmojiPickerSheetEmptySearch() = EmojiPickerSheetEmptySearchPreview()

@PreviewTest
@ComponentShots
@Composable
fun FileAttachmentArriving() = FileAttachmentArrivingPreview()

@PreviewTest
@ComponentShots
@Composable
fun LinkPreviewCard() = LinkPreviewCardPreview()

@PreviewTest
@ComponentShots
@Composable
fun LinkPreviewCardHidden() = LinkPreviewCardHiddenPreview()

@PreviewTest
@ComponentShots
@Composable
fun StagedLinkTile() = StagedLinkTilePreview()

@PreviewTest
@ComponentShots
@Composable
fun LinkPreviewLoadingRow() = LinkPreviewLoadingRowPreview()

@PreviewTest
@ComponentShots
@Composable
fun LocationCard() = LocationCardPreview()

@PreviewTest
@ComponentShots
@Composable
fun StagedLocationTileReady() = StagedLocationTileReadyPreview()

@PreviewTest
@ComponentShots
@Composable
fun StagedLocationTileFailed() = StagedLocationTileFailedPreview()

@PreviewTest
@ScreenShots
@Composable
fun MessageDetailsScreen() = MessageDetailsScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun MessageDetailsScreenEmpty() = MessageDetailsScreenEmptyPreview()
