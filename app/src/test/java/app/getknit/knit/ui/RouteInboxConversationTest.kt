package app.getknit.knit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which conversation a route from outside the app opens — the id the stale-route gate checks (ADR 2026-10.jbsa). */
class RouteInboxConversationTest {
    @Test
    fun aThreadRouteNamesItsConversationWithOrWithoutAQuery() {
        assertEquals("nearby", RouteInbox.conversationOf("chat/nearby"))
        assertEquals("g-climbing", RouteInbox.conversationOf("chat/g-climbing?messageId=msg-1"))
    }

    @Test
    fun anyOtherRouteNamesNone() {
        assertNull(RouteInbox.conversationOf("chatlist"))
        assertNull(RouteInbox.conversationOf("requests"))
        assertNull(RouteInbox.conversationOf("chat/"))
    }
}
