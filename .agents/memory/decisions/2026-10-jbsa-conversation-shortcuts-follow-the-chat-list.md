---
id: "2026-10.jbsa"
slug: conversation-shortcuts-follow-the-chat-list
title: "Conversation shortcuts follow the chat list"
date: 2026-10-04
topics: [notifications, contacts, ui]
---

# ADR 2026-10.jbsa — Conversation shortcuts follow the chat list

Status: Accepted (2026-10-04) — GitHub issue #33, branch `fix/conversation-shortcuts-33`. Amends ADR
2026-09.adgd (a pinned shortcut is now disabled, not left working).

**What was observed.** Issue #33: a contact the user deleted stayed in the menu the launcher shows on a long
press of the app icon, and tapping it "brought them back"; a contact's new photo never reached that menu. The
menu is the app's conversation shortcuts: one long-lived dynamic shortcut per conversation, keyed by its id,
which `MessageNotifier` published on every message notification so `setShortcutId` gets Android's conversation
treatment. A shortcut was written there and nowhere else, and removed only by Remove contact
(`Notifier.forgetConversation`, adgd) and the sign-out/restore wipes. Eleven paths take a thread away and kept
its shortcut: Delete chat (every kind), Block (profile, chat, Requests), a declined request, a left group, a
left commons, a removed relay and a relay invite that replaces a room, the Meshtastic room switched off or its
board forgotten, a DM's last message deleted, and a cleared verification that turns a thread back into a
request. Nothing checked the shortcut's route either (`MainActivity` → `RouteInbox` → `KnitApp`), so a stale
one opened:

- a removed contact's empty DM with a live composer — one message there is the authored signal, and they are a
  contact again (the "comes back" of the report);
- a blocked peer's DM, with a composer whose send nothing refuses;
- a left group (its row stays, `left = true`), where a send is our own signed frame and rejoins it (ADR
  2026-09.v6fu);
- a deleted group, read as a peer thread for want of a row, where a send filed a pending-key DM under `g-…`.

A notification's inline Reply had the same blind spot: a reply under a blocked peer's notification still in
the shade went to that peer. The share sheet never used these shortcuts — there is no share target — though
the old KDoc said it did.

**What changed.**

- **The shortcuts are derived, not hooked.** `ConversationShortcutSync` compares what is published with
  `OfferedConversations` — `visibleConversations`, the chat list's and search's predicate, over the same
  inputs — and `ConversationShortcuts.apply` removes the live copy of a gone conversation, disables a pinned
  one (its tap shows `chat_gone`), enables a pinned one whose conversation is back, and refreshes one whose
  face changed. The alternative a reader reaches for first, a `forgetConversation` at each removal site, means
  Notifier in five more ViewModels and repositories, and the twelfth path would be missed the way the eleven
  were.
- **A pass never creates a shortcut** (`planShortcuts`). Only a notification does
  (`ConversationShortcuts.push`), so nothing a removal took away comes back through a refresh, and nothing a
  notification never named appears.
- **A face is a fingerprint in the shortcut's extras** (`shortcutFingerprint`: title, kind, avatar palette,
  photo or cluster bytes; SHA-256, enum names and bytes only, golden-pinned). It survives process death
  without a table, and an unchanged face is never rewritten — `updateShortcuts` is rate-limited in the
  background, and it keeps the launcher's order, which `pushDynamicShortcut` would not. A refused update
  leaves the fingerprint stale, so the next pass retries it. A shortcut an older build published has none and
  is refreshed once.
- **One face resolver.** `InboundPipeline.resolveConversation` moved to `notifications/ConversationFaces`,
  which both the notification and the pass use, so a refreshed shortcut and the next notification cannot read
  apart (the Meshtastic room keeps its generic title in both).
- **Triggers.** A pass at start (ten seconds in, so 2.8.0's stale shortcuts are cleaned on the update), at
  `MainActivity.onStop` (every in-app removal has happened by the time the launcher can show its menu again,
  including those that only touch the messages table), and five seconds after the cheap inputs settle
  (`OfferedConversations.changes`: the blocked and accepted sets, the commons table, and the peer and group
  rows the shortcuts wear, read by `IN (:ids)` — `ShortcutWatch`: each DM's peer, each group, each cluster
  face, from the last pass and from every push in this process). The whole peers table is never watched: every
  inbound profile frame writes it, and a full read with the label index rebuilt over it, per frame, is the
  chat list's cost while it is on screen, not a background one; an empty watch subscribes to nothing. A label
  that moves because some other peer took the same name waits for the next pass. The messages table is read
  only inside a pass, for the same reason: its queries re-run on every message write.
- **A push wins over a pass.** Every push takes a stamp; a pass reads the stamp with the shortcuts, and leaves
  alone any id pushed after it, so a message that lands mid-pass keeps its shortcut and its notification.
- **The shade gets the same verdict** (`Notifier.retainConversations`): a gone conversation's notifications
  are cleared, and a refreshed one's next re-render draws the current face.
- **Routes and replies are gated on the same predicate** (`ConversationGate`). `KnitApp` opens the chat list
  with a toast instead of a thread the list no longer offers, `NotificationActionReceiver` sends nothing under
  one and clears its notification, and `ChatViewModel` never sends a `g-` id with no row as a DM. A check that
  fails (storage not open) lets the thread open, as before.

**What it costs.** A pass reads the chat list's inputs once — a few bounded queries — plus one face per
shortcut; the platform caps the shortcuts at a handful. A phone with no shortcut opens nothing: the sync's
inputs are lazy and the watch starts only once one is published (`ConversationShortcuts.published`). A deleted
DM's notification Reply is refused like a blocked one's — the chat is gone either way. A pinned shortcut stays
on the home screen, disabled; only the user can remove it. Delete chat on a group still leaves its id
accepted, so the group's next frame brings it back already accepted — a separate question, filed as #123.
Robolectric's `ShadowShortcutManager` makes `removeLongLivedShortcuts` a no-op and never clears `isEnabled`,
so the tests run on `FakeShortcutStore` behind the `ShortcutStore` seam, with one round trip through the real
store for the fingerprint, and `ConversationShortcutsPlatformTest` (androidTest) runs removal, refresh and
forget against the platform's own service — it passed on an API 34 emulator on 2026-10-04, as did the
end-to-end check there: a posted conversation notification's shortcut left at the next Home (`KnitShortcuts:
shortcuts: remove 1`). Whether `updateShortcuts` reaches a cached-only long-lived shortcut, and what a push
over a disabled pinned one does, are the device trial's to confirm. The regressions are
`ConversationShortcutsPlanTest`, `ConversationShortcutSyncTest`, `ConversationFacesTest`,
`OfferedConversationsTest`, `RouteInboxConversationTest`, `NotificationActionReceiverTest`'s gate cases and
`ChatViewModelTest.aGroupThreadWhoseRowIsGoneNeverSendsAsADm`, and on a device
`ConversationShortcutsPlatformTest`.
