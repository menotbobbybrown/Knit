package app.getknit.knit.data.peer

/**
 * The `(nodeId, name, avatarHash)` projection of a peer row — what a conversation's face outside the app is drawn
 * from — read for a handful of ids by the conversation-shortcut watch (ADR 2026-10.jbsa) instead of hydrating every
 * row on every profile frame. A Room POJO projection, not an entity: no schema, no migration.
 */
data class PeerFace(
    val nodeId: String,
    val name: String,
    val avatarHash: String?,
)
