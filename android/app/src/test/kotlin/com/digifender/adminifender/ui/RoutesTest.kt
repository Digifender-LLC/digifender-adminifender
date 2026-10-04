package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.repository.ChatScope
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {
    private val id = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"

    @Test
    fun routesRoundTripThroughTheSavedStack() {
        val stack = listOf(Route.ConcernRoute(id), Route.ChatRoute(ChatScope(ChatScope.Kind.CONCERN, id)), Route.SourceRoute(id), Route.ChatRoute(ChatScope.ALL))
        assertEquals(stack, decode(encode(stack)))
        assertEquals(emptyList<Route>(), decode(""))
    }

    @Test
    fun onlyKnownKindsWithWellFormedIdsSurvive() {
        assertEquals(emptyList<Route>(), decode("c:../../etc"))
        assertEquals(emptyList<Route>(), decode("s:${id.uppercase()}"))
        assertEquals(emptyList<Route>(), decode("t:service:$id"))
        assertEquals(emptyList<Route>(), decode("x:$id"))
        assertEquals(listOf(Route.SourceRoute(id)), decode("bogus|s:$id"))
    }
}
