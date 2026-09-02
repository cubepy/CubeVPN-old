package net.cubevpn.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The accents a reseller can pick from, beyond the three CubeVPN ships.
 *
 * The original three were hand-tuned, sixty-odd literal colours each. Writing three more that
 * way would be three more chances to get one wrong, and a seventh would be another. So these
 * are derived from a single hue: every colour in a scheme is the same hue at a fixed saturation
 * and value, and those fixed pairs are read off the violet palette so a generated accent sits at
 * the same distances from black, white and its own primary as the hand-tuned ones do.
 *
 * The three originals are left exactly as they were. They are what the app has always looked
 * like, and regenerating them to prove the generator works would change a look nobody asked to
 * change.
 */

/** Hue/saturation/value → colour. Value is 0..1, hue is degrees. */
private fun hsv(hue: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, s, v)))

/**
 * A complete accent from one hue.
 *
 * The saturation/value pairs below are the violet palette's, measured. They are what makes a
 * generated accent feel like part of the same set rather than a colour dropped into it.
 */
internal fun accentFromHue(theme: AccentTheme, hue: Float): AccentPalette {
    val deep = hsv(hue, 0.78f, 0.18f)
    val mid = hsv(hue, 0.81f, 0.85f)
    val bright = hsv(hue, 0.82f, 0.83f)
    val splash = hsv(hue, 0.76f, 0.10f)

    return AccentPalette(
        theme = theme,
        gradient = listOf(deep, mid, bright),
        glow = bright,
        // Never dips near-black: this drives the beam sweep, where a dark stop reads as a gap.
        glowStops = listOf(hsv(hue, 0.66f, 0.97f), hsv(hue, 0.50f, 0.94f), bright),
        splashBackground = splash,
        light = lightColorScheme(
            primary = hsv(hue, 0.80f, 0.93f),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = hsv(hue, 0.12f, 1.00f),
            onPrimaryContainer = hsv(hue, 0.85f, 0.36f),
            secondary = hsv(hue, 0.25f, 0.51f),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = hsv(hue, 0.08f, 0.97f),
            onSecondaryContainer = hsv(hue, 0.50f, 0.20f),
            background = hsv(hue, 0.03f, 0.99f),
            onBackground = hsv(hue, 0.40f, 0.15f),
            surface = Color(0xFFFFFFFF),
            onSurface = hsv(hue, 0.40f, 0.15f),
            surfaceVariant = hsv(hue, 0.06f, 0.96f),
            onSurfaceVariant = hsv(hue, 0.18f, 0.41f),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            outline = hsv(hue, 0.12f, 0.89f)
        ),
        dark = darkColorScheme(
            primary = hsv(hue, 0.66f, 0.97f),
            onPrimary = deep,
            primaryContainer = hsv(hue, 0.78f, 0.39f),
            onPrimaryContainer = hsv(hue, 0.14f, 1.00f),
            secondary = hsv(hue, 0.28f, 0.91f),
            onSecondary = hsv(hue, 0.70f, 0.27f),
            secondaryContainer = hsv(hue, 0.50f, 0.20f),
            onSecondaryContainer = hsv(hue, 0.09f, 0.97f),
            background = splash,
            onBackground = hsv(hue, 0.06f, 0.95f),
            surface = hsv(hue, 0.34f, 0.12f),
            onSurface = hsv(hue, 0.06f, 0.95f),
            surfaceVariant = hsv(hue, 0.50f, 0.20f),
            onSurfaceVariant = hsv(hue, 0.18f, 0.84f),
            error = Color(0xFFFF6B81),
            onError = Color(0xFF2A0A0F),
            outline = hsv(hue, 0.43f, 0.33f)
        ),
        amoled = darkColorScheme(
            primary = hsv(hue, 0.66f, 0.97f),
            onPrimary = deep,
            primaryContainer = hsv(hue, 0.80f, 0.32f),
            onPrimaryContainer = hsv(hue, 0.14f, 1.00f),
            secondary = hsv(hue, 0.28f, 0.91f),
            onSecondary = hsv(hue, 0.70f, 0.27f),
            secondaryContainer = hsv(hue, 0.55f, 0.14f),
            onSecondaryContainer = hsv(hue, 0.09f, 0.97f),
            // True black, which is the point of AMOLED: an unlit pixel costs nothing.
            background = Color(0xFF000000),
            onBackground = hsv(hue, 0.06f, 0.95f),
            surface = Color(0xFF000000),
            onSurface = hsv(hue, 0.06f, 0.95f),
            surfaceVariant = hsv(hue, 0.55f, 0.14f),
            onSurfaceVariant = hsv(hue, 0.18f, 0.84f),
            error = Color(0xFFFF6B81),
            onError = Color(0xFF2A0A0F),
            outline = hsv(hue, 0.45f, 0.26f)
        )
    )
}

// Hues chosen for separation, not variety: violet 285, aurora 199 and ember 18 already exist, so
// these fill the gaps a reseller would otherwise ask for — a green, a pink and a blue that is
// not the existing cyan.
internal val EmeraldPalette = accentFromHue(AccentTheme.EMERALD, 152f)
internal val RosePalette = accentFromHue(AccentTheme.ROSE, 340f)
internal val IndigoPalette = accentFromHue(AccentTheme.INDIGO, 232f)
