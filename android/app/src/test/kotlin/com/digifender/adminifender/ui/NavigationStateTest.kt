package com.digifender.adminifender.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStateTest {
    private val id = "12345678-1234-4234-8234-123456789abc"

    @Test fun allFeatureRoutesRoundTripOnlyTheirIdsAndFixedScopeMarkers() {
        val routes = listOf(Route.ConcernRoute(id), Route.SourceRoute(id), Route.DeleteRoute(id),
            Route.ChatRoute(id, "source"), Route.ChatRoute(id, "concern"), Route.ExportsRoute, Route.PendingEditsRoute, Route.HealthRoute)
        assertEquals(routes, decode(encode(routes)))
    }

    @Test fun malformedSavedRoutesAndUnknownScopesAreIgnored() {
        assertEquals(emptyList<Route>(), decode("s:Private message body|h:all:$id|h:source:not-an-id|unknown:$id|d:../../file"))
    }

    @Test fun savedNavigationRemainsBounded() {
        assertEquals(20, decode(encode(List(40) { Route.SourceRoute(id) })).size)
    }

    @Test fun lateExportPickerNeverTargetsAnotherSnapshotOrSession() {
        val selected = ExportCopySelection(id, 2)
        assertTrue(selected.matches(id, 2, signedIn = true, busy = false))
        assertFalse(selected.matches(null, 2, signedIn = true, busy = false))
        assertFalse(selected.matches("different-export", 2, signedIn = true, busy = false))
        assertFalse(selected.matches(id, 3, signedIn = true, busy = false))
        assertFalse(selected.matches(id, 2, signedIn = false, busy = false))
        assertFalse(selected.matches(id, 2, signedIn = true, busy = true))
    }
}
