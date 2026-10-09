package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.platform.MAX_IMAGE_PIXELS
import com.digifender.adminifender.core.data.platform.MAX_RENDER_PIXELS
import com.digifender.adminifender.core.data.platform.imageSampleSize
import com.digifender.adminifender.core.data.platform.renderScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentReadersTest {
    @Test
    fun aLetterPageIsRenderedAtAboutTwoHundredDpiWithinFourMegapixels() {
        // US Letter in points.
        val scale = renderScale(612, 792)
        assertEquals(200f / 72f, scale, 0.001f)
        assertTrue(612 * scale * 792 * scale <= MAX_RENDER_PIXELS)
    }

    @Test
    fun aHugePageIsScaledDownToTheBudgetEvenBelowItsOwnSize() {
        // A0 poster: 2384 x 3370 points would be 33 megapixels at 200 dpi, and is 8 megapixels even at 1:1.
        val scale = renderScale(2384, 3370)
        assertTrue(scale < 1f)
        assertTrue(2384L * 3370 * scale * scale <= MAX_RENDER_PIXELS * 1.001)
        // Degenerate sizes do not divide by zero.
        assertEquals(1f, renderScale(0, 100), 0.0f)
    }

    @Test
    fun anImageIsSampledOnlyWhenItExceedsSixteenMegapixels() {
        assertEquals(1, imageSampleSize(4000, 3000))
        assertEquals(1, imageSampleSize(4000, 4000))
        val sample = imageSampleSize(8000, 6000)
        assertTrue(sample >= 2)
        assertTrue(8000L / sample * (6000L / sample) <= MAX_IMAGE_PIXELS)
    }
}
