package com.digifender.adminifender.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Stroke icons from the Calm Ledger canvas on a 24-unit grid, tinted with the current content color. */
object Glyphs {
    private fun stroke(name: String, width: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
        }.build()

    // Status pills (2-unit strokes).
    val alert = stroke("alert", 2f, "M12 4l9 16H3z", "M12 10v4", "M12 17v0.01")
    val clock = stroke("clock", 2f, "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0 -17z", "M12 7.5V12l3 2")
    val hourglass = stroke("hourglass", 2f, "M7 4h10", "M7 20h10", "M8 4c0 5 8 5 8 8s-8 3-8 8")
    val check = stroke("check", 2f, "M5 12.5l4.5 4.5L19 7.5")

    // Navigation and inline icons (1.75-unit strokes).
    val tray = stroke("tray", 1.75f, "M4 13l2-8h12l2 8", "M4 13v6h16v-6h-5l-1 2h-4l-1-2z")
    val search = stroke("search", 1.75f, "M11 4.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0 -13z", "M16 16l4.5 4.5")
    val ask = stroke("ask", 1.75f, "M5 5h14v10H10l-5 4z")
    val pulse = stroke("pulse", 1.75f, "M3 12h4l2 -6l4 12l2 -6h6")
    val sliders = stroke("sliders", 1.75f, "M4 7h10", "M18 7h2", "M4 17h4", "M12 17h8", "M16 5a2 2 0 1 0 0 4a2 2 0 1 0 0 -4z", "M10 15a2 2 0 1 0 0 4a2 2 0 1 0 0 -4z")
    val shield = stroke("shield", 1.75f, "M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6z")
    val cloud = stroke("cloud", 1.75f, "M7 18h10a4 4 0 0 0 0 -8a5.5 5.5 0 0 0 -10.7 1.5A3.3 3.3 0 0 0 7 18z")
    val mail = stroke("mail", 1.75f, "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V7a2 2 0 0 1 2 -2z", "M3 7l9 6 9-6")
    val paperclip = stroke("paperclip", 1.75f, "M20 12l-8 8a5 5 0 0 1 -7 -7l9 -9a3.5 3.5 0 0 1 5 5l-9 9a2 2 0 0 1 -3 -3l8 -8")
    val phone = stroke("phone", 1.75f, "M9.5 2.5h5a2.5 2.5 0 0 1 2.5 2.5v14a2.5 2.5 0 0 1 -2.5 2.5h-5a2.5 2.5 0 0 1 -2.5 -2.5v-14a2.5 2.5 0 0 1 2.5 -2.5z", "M11 18.5h2")
    val back = stroke("back", 1.75f, "M15 5l-7 7 7 7")
    val send = stroke("send", 2f, "M12 19V5", "M6 11l6-6 6 6")
}
