package app.getknit.knit.ui

import app.getknit.knit.mesh.MeshController

/**
 * How we can currently get a frame to a node — each backed by evidence, which is the whole point of the
 * four tiers. Ordered strongest first; a node is classified by the best evidence it has.
 *
 * One rule for every surface that draws a presence dot with a label beside it — Diagnostics' four sections
 * and the Profile status line — so a peer Diagnostics lists under "Reachable long-range" can never read as
 * "Offline" on their profile. Both derive from [reachOf] over the same four inputs.
 */
enum class Reach {
    /** A short-range radio (BLE/NAN) has sighted this peer's **own** radio. The only honest "connected". */
    Direct,

    /**
     * Another phone handed us this peer's own recent, signed traffic over the radio mesh within the linger
     * (ADR 2026-10.fw8g). A few radio hops out and live, but not sighted: we know who carried its last frame,
     * not that the next one will take the same way.
     */
    Indirect,

    /**
     * Something carried this peer's own recent traffic to us within the linger: a LoRa board put its frames
     * on air (which may have been a gateway relaying for a peer with no board of its own), or it pushed
     * into a scope we share on a connected spool. A path, not proximity, and not a route — neither plane
     * knows how far away it is.
     */
    LongRange,

    /** Known — we hold a profile — but nothing currently reaches it. */
    Known,
}

/**
 * Classifies one node by the best evidence it has: [nearby] is [MeshController.neighbors] (the short-range
 * planes, the only ones that sight the peer's own radio), [indirect] is the radio mesh's relayed authors
 * ([app.getknit.knit.mesh.indirectPeers] over [MeshController.heardIndirectly]), [reachable] is
 * [MeshController.reachable] (every plane, long-range included) and [spoolPresent] is
 * [app.getknit.knit.mesh.spool.spoolPresentPeers]. The mesh is a pure flood network with no routing table,
 * so no tier claims a *route* — only that something reached us from that node, or could carry a frame back.
 */
fun reachOf(
    nodeId: String,
    nearby: Set<String>,
    indirect: Set<String>,
    reachable: Set<String>,
    spoolPresent: Set<String>,
): Reach =
    when (nodeId) {
        in nearby -> Reach.Direct
        in indirect -> Reach.Indirect
        in reachable, in spoolPresent -> Reach.LongRange
        else -> Reach.Known
    }
