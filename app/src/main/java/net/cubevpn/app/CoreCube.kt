package net.cubevpn.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The connect screen's centerpiece: a slowly turning translucent cube whose six faces
 * breathe apart around a glowing core, with the current IP + city readout sitting at the
 * heart of it (drawn flat over the 3D scene, so it stays readable while the shell spins).
 *
 * State language:
 *  - disconnected: shell nearly closed, dim core, slow turn
 *  - connecting:   fast turn + fast breathing
 *  - connected:    shell breathes wide open around a bright pulsing core
 *
 * Replaces the earth globe (EarthSection/DotGlobeSection) — a rotating planet is what
 * every VPN app ships; the cube is the brand.
 */
@Composable
fun CoreCubeSection(modifier: Modifier = Modifier) {
    val conn by VpnState.state.collectAsState()
    val connectedAt by VpnState.connectedAt.collectAsState()
    val connected = conn == Connection.CONNECTED
    val lang = LocalLang.current
    val accent = LocalAccent.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val hazeState = LocalHazeState.current
    val surfaceColor = MaterialTheme.colorScheme.surface

    // Same lookup policy as the globe had: through the tunnel once connected, direct
    // when disconnected, and no lookup at all while the kill switch is blocking traffic.
    var loc by remember { mutableStateOf(TehranFallback) }
    val ctx = LocalContext.current
    val killSwitchOn by ConfigStore.get(ctx).killSwitch.collectAsState()
    LaunchedEffect(conn, killSwitchOn) {
        when (conn) {
            Connection.CONNECTED -> {
                delay(1200)
                repeat(5) { attempt ->
                    val l = LocationFetcher.fetch(throughProxy = true)
                    if (l != null) {
                        loc = l
                        return@LaunchedEffect
                    }
                    delay(1500L + attempt * 500L)
                }
            }
            Connection.DISCONNECTED -> {
                if (killSwitchOn) {
                    loc = TehranFallback
                    return@LaunchedEffect
                }
                repeat(3) {
                    val l = LocationFetcher.fetch(throughProxy = false)
                    if (l != null) {
                        loc = l
                        return@LaunchedEffect
                    }
                    delay(1000)
                }
            }
            else -> {}
        }
    }

    // Connection state drives speeds and openness; each retargets smoothly on change.
    val speedMul: State<Float> = animateFloatAsState(
        when (conn) {
            Connection.CONNECTING -> 2.6f
            Connection.CONNECTED -> 1f
            else -> 0.45f
        },
        tween(700), label = "cubeSpeed"
    )
    val breathMul: State<Float> = animateFloatAsState(
        if (conn == Connection.CONNECTING) 2.1f else 1f,
        tween(700), label = "cubeBreathSpeed"
    )
    val gapAmp: State<Float> = animateFloatAsState(
        when (conn) {
            Connection.CONNECTED -> 1f
            Connection.CONNECTING -> 0.5f
            else -> 0.13f
        },
        tween(900, easing = FastOutSlowInEasing), label = "cubeGap"
    )
    val coreBright: State<Float> = animateFloatAsState(
        when (conn) {
            Connection.CONNECTED -> 1f
            Connection.CONNECTING -> 0.55f
            else -> 0.32f
        },
        tween(700), label = "coreBright"
    )

    var spin by remember { mutableFloatStateOf(0.6f) }
    var tiltDrag by remember { mutableFloatStateOf(0f) }
    var breath by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                    spin += dt * 0.42f * speedMul.value
                    breath += dt * (2f * PI.toFloat() / 3.6f) * breathMul.value
                }
                last = now
            }
        }
    }

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val introAlpha by animateFloatAsState(if (appeared) 1f else 0f, tween(550), label = "cubeIntroA")
    val introScale by animateFloatAsState(if (appeared) 1f else 0.96f, tween(550, easing = FastOutSlowInEasing), label = "cubeIntroS")

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val side = min(maxWidth.value, maxHeight.value).dp
            Box(
                Modifier
                    .size(side)
                    .then(globeFrameRateModifier())
                    .graphicsLayer {
                        alpha = introAlpha
                        scaleX = introScale
                        scaleY = introScale
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            spin += drag.x / (size.width * 0.5f) * 1.4f
                            tiltDrag = (tiltDrag + drag.y / (size.height * 0.5f) * 0.8f)
                                .coerceIn(-0.45f, 0.45f)
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize().clipToBounds()) {
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val unit = this.size.minDimension * 0.155f
                    val gap = 1f + 1.05f * gapAmp.value * (0.5f - 0.5f * cos(breath))
                    val bright = coreBright.value
                    val glow = accent.glow

                    // the living core, behind the shell
                    val pulse = 1f + 0.12f * sin(breath)
                    val coreR = unit * 1.15f * pulse
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to Color.White.copy(alpha = 0.60f * bright),
                            0.38f to glow.copy(alpha = 0.42f * bright),
                            1f to Color.Transparent,
                            center = Offset(cx, cy), radius = coreR
                        ),
                        radius = coreR, center = Offset(cx, cy)
                    )

                    drawCubeShell(cx, cy, unit, spin, -0.30f + tiltDrag, gap, glow, isDark)
                }

                // IP readout, drawn flat over the scene — the same data the globe tooltip showed
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Crossfade(targetState = loc, animationSpec = tween(400), label = "coreIp") { l ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                l.ip,
                                color = if (isDark) Color.White else Color(0xFF17102B),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    shadow = Shadow(
                                        color = if (isDark) accent.glow.copy(alpha = 0.9f)
                                        else Color.White.copy(alpha = 0.9f),
                                        blurRadius = 14f
                                    )
                                ),
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "${l.city}, ${l.country}",
                                color = if (isDark) Color(0xFFC9C2DD) else Color(0xFF4A4460),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    shadow = Shadow(
                                        color = if (isDark) Color(0xB3000000) else Color(0xB3FFFFFF),
                                        blurRadius = 8f
                                    )
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // status pill — same look and wording the globe section had
        val markerColor = if (isDark) Color(0xFF4BF0A4) else Color(0xFF0E9E55)
        val pillColor by animateColorAsState(
            targetValue = if (connected) markerColor else if (isDark) Color(0xFF7E8AA0) else Color(0xFF5B6677),
            animationSpec = tween(450),
            label = "cubePillColor"
        )
        val pop = remember { Animatable(1f) }
        var firstState by remember { mutableStateOf(true) }
        LaunchedEffect(connected) {
            if (firstState) firstState = false
            else {
                pop.snapTo(0.82f)
                pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 340f))
            }
        }
        Box(
            Modifier
                .graphicsLayer {
                    alpha = introAlpha
                    scaleX = pop.value
                    scaleY = pop.value
                }
                .clip(RoundedCornerShape(50))
                .then(
                    if (hazeState != null) Modifier.hazeEffect(hazeState) {
                        blurRadius = 16.dp
                        backgroundColor = surfaceColor
                        tints = listOf(HazeTint(surfaceColor.copy(alpha = 0.25f)))
                        noiseFactor = 0f
                    } else Modifier
                )
                .background(pillColor.copy(alpha = 0.14f))
                .border(1.dp, pillColor.copy(alpha = 0.40f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Crossfade(targetState = connected, animationSpec = tween(350), label = "cubeStatusText") { isOn ->
                val nowTick = rememberTick(isOn)
                val text = if (isOn && connectedAt > 0L) {
                    val secs = ((nowTick - connectedAt) / 1000L).coerceAtLeast(0L)
                    val clock = localizeDigits(fmtHMS(secs), lang)
                    val time = if (lang == Lang.FA) "‪$clock‬" else clock
                    Strings.get(lang, "conn_connected_for").format(time)
                } else Strings.get(lang, "conn_disconnected")
                Text(
                    text,
                    color = pillColor,
                    fontWeight = FontWeight.Light,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
    }
}

/** normal axis (x,y,z) for each of the six faces; u/v span axes are derived from it. */
private val FACE_NORMALS = arrayOf(
    floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f),
    floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
    floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f)
)

private class ProjectedFace(
    val path: Path,
    val avgZ: Float,
    val facing: Float
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCubeShell(
    cx: Float,
    cy: Float,
    unit: Float,
    spin: Float,
    tilt: Float,
    gap: Float,
    glow: Color,
    isDark: Boolean
) {
    val sy = sin(spin); val cyr = cos(spin)
    val sx = sin(tilt); val cxr = cos(tilt)
    val f = 7.5f

    fun rotate(x: Float, y: Float, z: Float): FloatArray {
        val x1 = x * cyr + z * sy
        val z1 = -x * sy + z * cyr
        val y2 = y * cxr - z1 * sx
        val z2 = y * sx + z1 * cxr
        return floatArrayOf(x1, y2, z2)
    }

    val faces = ArrayList<ProjectedFace>(6)
    for (n in FACE_NORMALS) {
        // u/v are the two axes perpendicular to the face normal
        val u = if (n[0] == 0f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
        val v = if (n[2] == 0f) floatArrayOf(0f, 0f, 1f) else floatArrayOf(0f, 1f, 0f)

        val corners = arrayOf(
            floatArrayOf(1f, 1f), floatArrayOf(1f, -1f),
            floatArrayOf(-1f, -1f), floatArrayOf(-1f, 1f)
        )
        val path = Path()
        var zSum = 0f
        corners.forEachIndexed { i, (a, b) ->
            val px = n[0] * gap + u[0] * a + v[0] * b
            val py = n[1] * gap + u[1] * a + v[1] * b
            val pz = n[2] * gap + u[2] * a + v[2] * b
            val r = rotate(px, py, pz)
            zSum += r[2]
            val s = f / (f - r[2])
            val sxp = cx + r[0] * s * unit
            val syp = cy - r[1] * s * unit
            if (i == 0) path.moveTo(sxp, syp) else path.lineTo(sxp, syp)
        }
        path.close()

        val rn = rotate(n[0], n[1], n[2])
        faces += ProjectedFace(path, zSum / 4f, rn[2])
    }

    faces.sortBy { it.avgZ } // far faces first, near faces drawn over them
    val edgeW = 1.2.dp.toPx()
    for (face in faces) {
        val facing = face.facing.coerceIn(-1f, 1f)
        val front = ((facing + 1f) / 2f) // 0 = facing away, 1 = facing viewer
        val fillAlpha = (if (isDark) 0.045f else 0.06f) + 0.075f * front
        val edgeAlpha = 0.22f + 0.55f * front
        drawPath(face.path, glow.copy(alpha = fillAlpha))
        drawPath(face.path, glow.copy(alpha = edgeAlpha), style = Stroke(width = edgeW))
    }
}
