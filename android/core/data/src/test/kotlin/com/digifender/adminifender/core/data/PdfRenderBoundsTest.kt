package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.platform.renderSize
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRenderBoundsTest {
    @Test fun allPageShapesStayWithinFourMegapixels() {
        for ((width, height) in listOf(600 to 800, 10000 to 10000, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val rendered = renderSize(width, height)
            assertTrue(rendered.first > 0 && rendered.second > 0)
            assertTrue(rendered.first.toLong() * rendered.second <= 4_000_000)
        }
    }
}
