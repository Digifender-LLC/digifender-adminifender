package com.digifender.adminifender.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.digifender.adminifender.R

/*
 * "Calm ledger" (owner's design canvas, 2026-10-04): paper-toned surfaces, one ink-green accent, serif headings
 * over a sans body. Static schemes, no dynamic color. The canvas defines the light scheme; the dark one is derived
 * from it (ink ground, paper-tinted text, the same green as a lighter accent) and follows the system setting.
 */

private val PublicSans = FontFamily(
    Font(R.font.public_sans_regular, FontWeight.Normal),
    Font(R.font.public_sans_medium, FontWeight.Medium),
    Font(R.font.public_sans_semibold, FontWeight.SemiBold),
)
private val SourceSerif = FontFamily(Font(R.font.source_serif_4_semibold, FontWeight.SemiBold))

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5E57),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E9E4),
    onPrimaryContainer = Color(0xFF0C3B35),
    // Selected chips and the navigation indicator use the secondary container: the same pale green.
    secondary = Color(0xFF4A4D45),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E9E4),
    onSecondaryContainer = Color(0xFF0C3B35),
    tertiary = Color(0xFF2F4F80),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE5F3),
    onTertiaryContainer = Color(0xFF2F4F80),
    background = Color(0xFFF6F4EE),
    onBackground = Color(0xFF1C1E1A),
    surface = Color(0xFFF6F4EE),
    onSurface = Color(0xFF1C1E1A),
    surfaceVariant = Color(0xFFEFECE2),
    onSurfaceVariant = Color(0xFF5B5E55),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFBF8),
    surfaceContainer = Color(0xFFEFECE2),
    surfaceContainerHigh = Color(0xFFEFECE2),
    surfaceContainerHighest = Color(0xFFE9E6DB),
    outline = Color(0xFFCFCABB),
    outlineVariant = Color(0xFFE1DDD0),
    error = Color(0xFFA3392B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF5D9D3),
    onErrorContainer = Color(0xFF8F2C1E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCFC4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF1F5E57),
    onPrimaryContainer = Color(0xFFD5E9E4),
    secondary = Color(0xFFC8C6BB),
    onSecondary = Color(0xFF2F312B),
    secondaryContainer = Color(0xFF2C4A45),
    onSecondaryContainer = Color(0xFFD5E9E4),
    tertiary = Color(0xFFA9C2EA),
    onTertiary = Color(0xFF16305A),
    tertiaryContainer = Color(0xFF2F4F80),
    onTertiaryContainer = Color(0xFFDCE5F3),
    background = Color(0xFF141613),
    onBackground = Color(0xFFE6E3D8),
    surface = Color(0xFF141613),
    onSurface = Color(0xFFE6E3D8),
    surfaceVariant = Color(0xFF2A2C27),
    onSurfaceVariant = Color(0xFFB8B5A9),
    surfaceContainerLowest = Color(0xFF0F110E),
    surfaceContainerLow = Color(0xFF1B1D19),
    surfaceContainer = Color(0xFF22241F),
    surfaceContainerHigh = Color(0xFF2A2C27),
    surfaceContainerHighest = Color(0xFF32352F),
    outline = Color(0xFF8A877C),
    outlineVariant = Color(0xFF3A3C36),
    error = Color(0xFFF2A79B),
    onError = Color(0xFF5B140B),
    errorContainer = Color(0xFF7A2A1F),
    onErrorContainer = Color(0xFFF5D9D3),
)

private fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(fontFamily = PublicSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em)

private fun serif(size: Int, line: Int) = TextStyle(fontFamily = SourceSerif, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = line.sp)

private val LedgerType = Typography(
    displayLarge = serif(57, 64),
    displayMedium = serif(45, 52),
    displaySmall = serif(36, 44),
    headlineLarge = serif(32, 40),
    headlineMedium = serif(28, 36),
    headlineSmall = serif(24, 32),
    titleLarge = serif(22, 28),
    titleMedium = sans(16, 24, FontWeight.SemiBold),
    titleSmall = sans(14, 20, FontWeight.SemiBold),
    bodyLarge = sans(16, 24),
    bodyMedium = sans(14, 20),
    bodySmall = sans(12, 16),
    labelLarge = sans(14, 20, FontWeight.SemiBold),
    labelMedium = sans(12, 16, FontWeight.SemiBold, tracking = 0.04f),
    labelSmall = sans(11, 16, FontWeight.SemiBold, tracking = 0.04f),
)

private val LedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** One item state: a container and content color pair. The state is always also shown by a word and an icon. */
data class Tone(val container: Color, val content: Color)

class Tones(val overdue: Tone, val dueSoon: Tone, val waiting: Tone, val neutral: Tone)

private val LightTones = Tones(
    overdue = Tone(Color(0xFFF5D9D3), Color(0xFF8F2C1E)),
    dueSoon = Tone(Color(0xFFF6E5C0), Color(0xFF6B4500)),
    waiting = Tone(Color(0xFFDCE5F3), Color(0xFF2F4F80)),
    neutral = Tone(Color(0xFFE9E6DB), Color(0xFF4A4D45)),
)

private val DarkTones = Tones(
    overdue = Tone(Color(0xFF5A231B), Color(0xFFF5D9D3)),
    dueSoon = Tone(Color(0xFF4F3A10), Color(0xFFF6E5C0)),
    waiting = Tone(Color(0xFF263A5C), Color(0xFFDCE5F3)),
    neutral = Tone(Color(0xFF32352F), Color(0xFFD9D6CB)),
)

private val LocalTones = staticCompositionLocalOf { LightTones }

/** Status tones for the current scheme. */
object LedgerTones {
    val overdue: Tone @Composable get() = LocalTones.current.overdue
    val dueSoon: Tone @Composable get() = LocalTones.current.dueSoon
    val waiting: Tone @Composable get() = LocalTones.current.waiting
    val neutral: Tone @Composable get() = LocalTones.current.neutral
}

/** Cards sit flat on the paper with a 1dp hairline and no shadow. */
object LedgerCard {
    val border: BorderStroke @Composable get() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

    @Composable
    fun colors(): CardColors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
}

@Composable
fun AdminifenderTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTones provides if (dark) DarkTones else LightTones) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = LedgerType, shapes = LedgerShapes, content = content)
    }
}
