package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.network.CitationDto
import com.digifender.adminifender.core.data.network.ClaimDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSourcesTest {
    private fun cite(chunk: String, start: Int) = CitationDto(chunkId = chunk, start = start, end = start + 5)

    @Test fun `sources are numbered once each, in order of first use`() {
        val a = cite("s1:body-v1:0", 0)
        val b = cite("s2:body-v1:0", 10)
        val claims = listOf(ClaimDto("one", "evidence", listOf(a)), ClaimDto("two", "evidence", listOf(b, a.copy())), ClaimDto("three", "inference"))
        assertEquals(listOf(a, b), numberedCitations(claims))
    }

    @Test fun `different ranges in the same chunk are different sources`() {
        val claims = listOf(ClaimDto("x", "evidence", listOf(cite("c", 0), cite("c", 20))))
        assertEquals(2, numberedCitations(claims).size)
    }
}
