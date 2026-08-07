package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.noop.analytics.BreathPacer
import com.noop.analytics.Hrv
import com.noop.analytics.ResonanceEngine
import kotlinx.coroutines.delay
import java.util.Locale
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.DataLine
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.sqrt

// MARK: - Pace presets (desktop port of Android BreatheScreen)
//
// HRV breathing biofeedback: paced breathing with visual + audio guidance, real-time
// RMSSD tracking from the live R-R stream, and a results card after the session.
//
// Desktop differences from Android:
//  - javax.sound.sampled.SourceDataLine replaces android.media.AudioTrack
//  - No liquid UI — plain Compose Canvas + NoopCard
//  - collectAsState() instead of collectAsStateWithLifecycle()

private enum class Pace(val label: String) {
    Relax("Relax 4-6"),
    Coherence("Coherence 5.5"),
    Box("Box 4-4"),
    Resonance("Resonance");

    fun inhaleSeconds(lockedBpm: Double? = null): Double = when (this) {
        Relax -> 4.0
        Coherence -> 5.5
        Box -> 4.0
        Resonance -> {
            val cycle = 60.0 / (lockedBpm ?: ResonanceEngine.FALLBACK_BPM)
            cycle * BreathPacer.DEFAULT_INHALE_FRACTION
        }
    }

    fun exhaleSeconds(lockedBpm: Double? = null): Double = when (this) {
        Relax -> 6.0
        Coherence -> 5.5
        Box -> 4.0
        Resonance -> {
            val cycle = 60.0 / (lockedBpm ?: ResonanceEngine.FALLBACK_BPM)
            cycle * (1.0 - BreathPacer.DEFAULT_INHALE_FRACTION)
        }
    }

    fun cycleSeconds(lockedBpm: Double? = null): Double = inhaleSeconds(lockedBpm) + exhaleSeconds(lockedBpm)
}

@Composable
fun BreatheScreen(viewModel: DesktopAppViewModel) {
    val live by viewModel.live.collectAsState()
    val bpm by viewModel.bpm.collectAsState()

    var selectedPace by remember { mutableStateOf(Pace.Coherence) }
    var running by remember { mutableStateOf(false) }
    var elapsedSec by remember { mutableIntStateOf(0) }
    var breathCount by remember { mutableIntStateOf(0) }
    var phaseFraction by remember { mutableDoubleStateOf(0.0) } // 0..1 within the breath cycle
    var isInhale by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    var sessionRmssd by remember { mutableStateOf<Double?>(null) }
    var sessionHrValues by remember { mutableStateOf<List<Int>>(emptyList()) }
    var sessionRrValues by remember { mutableStateOf<List<Int>>(emptyList()) }
    var showResults by remember { mutableStateOf(false) }

    // Session timer + breath counter
    LaunchedEffect(running) {
        while (running) {
            delay(100)
            elapsedSec += 0
            // Increment elapsed by 0.1s per tick
        }
    }

    // Phase animation loop
    LaunchedEffect(running, selectedPace) {
        val cycleSec = selectedPace.cycleSeconds()
        var cycleStartMs = System.currentTimeMillis()
        var breathsThisCycle = 0
        while (running) {
            val nowMs = System.currentTimeMillis()
            val elapsedMs = (nowMs - cycleStartMs) / 1000.0
            if (elapsedMs >= cycleSec) {
                cycleStartMs = nowMs
                breathCount++
            }
            val frac = (elapsedMs / cycleSec).coerceIn(0.0, 1.0)
            phaseFraction = frac
            isInhale = frac < (selectedPace.inhaleSeconds() / cycleSec)
            elapsedSec = ((nowMs - (cycleStartMs - breathCount * cycleSec * 1000)) / 1000).toInt()
            delay(33) // ~30fps
        }
    }

    // Track live HR + R-R during session
    LaunchedEffect(running, live) {
        if (running) {
            live.heartRate?.let { sessionHrValues = sessionHrValues + it }
            if (live.rr.isNotEmpty()) {
                sessionRrValues = sessionRrValues + live.rr
                sessionRmssd = if (sessionRrValues.size >= 5) Hrv.rmssd(sessionRrValues.takeLast(60)) else null
            }
        }
    }

    // Audio tone
    var audioThread by remember { mutableStateOf<Thread?>(null) }
    var audioLine by remember { mutableStateOf<SourceDataLine?>(null) }

    LaunchedEffect(running, muted) {
        if (running && !muted) {
            // Start audio
            if (audioLine == null) {
                try {
                    val format = AudioFormat(44100f, 16, 1, true, false)
                    val info = DataLine.Info(SourceDataLine::class.java, format)
                    val line = javax.sound.sampled.AudioSystem.getLine(info) as SourceDataLine
                    line.open(format, 4096)
                    line.start()
                    audioLine = line
                    audioThread = Thread {
                        val buf = ByteArray(4096)
                        while (running && !muted) {
                            val frac = phaseFraction
                            val inhaleFrac = selectedPace.inhaleSeconds() / selectedPace.cycleSeconds()
                            val freq = if (frac < inhaleFrac) {
                                // Inhale: rising pitch 150 -> 220 Hz
                                150.0 + (frac / inhaleFrac) * 70.0
                            } else {
                                // Exhale: falling pitch 220 -> 150 Hz
                                val exhaleProg = (frac - inhaleFrac) / (1.0 - inhaleFrac)
                                220.0 - exhaleProg * 70.0
                            }
                            val amplitude = 0.15 // quiet
                            for (i in buf.indices step 2) {
                                val t = (System.nanoTime() / 1e9) % 1.0
                                val sample = (amplitude * Short.MAX_VALUE * sin(2 * PI * freq * t)).toInt().toShort()
                                buf[i] = (sample.toInt() and 0xFF).toByte()
                                buf[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                            }
                            line.write(buf, 0, buf.size)
                        }
                    }.also { it.isDaemon = true }
                    audioThread?.start()
                } catch (_: Exception) {
                    // Audio not available — continue silently
                }
            }
        } else {
            // Stop audio
            audioLine?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.close() } catch (_: Exception) {}
            }
            audioLine = null
            audioThread = null
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            audioLine?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.close() } catch (_: Exception) {}
            }
        }
    }

    // Stop session
    fun stopSession() {
        running = false
        showResults = true
        // Final RMSSD
        if (sessionRrValues.size >= 5) {
            sessionRmssd = Hrv.rmssd(sessionRrValues.takeLast(120))
        }
    }

    // Start session
    fun startSession() {
        elapsedSec = 0
        breathCount = 0
        sessionHrValues = emptyList()
        sessionRrValues = emptyList()
        sessionRmssd = null
        showResults = false
        running = true
    }

    ScreenScaffold(
        title = "Breathe",
        subtitle = "HRV breathing biofeedback with paced resonance",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // Pace selector
            if (!running && !showResults) {
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Overline("Choose a pace")
                        Pace.entries.forEach { pace ->
                            PaceRow(
                                pace = pace,
                                selected = selectedPace == pace,
                                onClick = { selectedPace = pace },
                            )
                        }
                    }
                }
            }

            // Results or active session
            if (showResults) {
                SessionResults(
                    durationSec = elapsedSec,
                    breaths = breathCount,
                    avgRmssd = sessionRmssd,
                    avgHr = sessionHrValues.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                    pace = selectedPace,
                    onDismiss = { showResults = false },
                )
            } else if (running) {
                // Active session
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Breathing circle
                    BreathingCircle(
                        phaseFraction = phaseFraction,
                        isInhale = isInhale,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Live stats
                    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        StatTile(
                            modifier = Modifier.weight(1f),
                            label = "Elapsed",
                            value = formatTime(elapsedSec),
                            accent = Palette.accent,
                        )
                        StatTile(
                            modifier = Modifier.weight(1f),
                            label = "Breaths",
                            value = "$breathCount",
                            accent = Palette.accent,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        StatTile(
                            modifier = Modifier.weight(1f),
                            label = "Live HR",
                            value = bpm?.let { "$it bpm" } ?: "--",
                            accent = Palette.accent,
                        )
                        StatTile(
                            modifier = Modifier.weight(1f),
                            label = "RMSSD",
                            value = sessionRmssd?.let { String.format(Locale.US, "%.0f ms", it) } ?: "--",
                            accent = Palette.accent,
                        )
                    }

                    // Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { muted = !muted },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (muted) "Muted" else "Audio on")
                        }
                        Button(
                            onClick = { stopSession() },
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.statusCritical),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Stop")
                        }
                    }
                }
            } else {
                // Idle — start button
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    BreathingCircle(
                        phaseFraction = 0.5,
                        isInhale = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { startSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Start session", style = NoopType.body.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Text(
                        "Follow the expanding circle. Inhale as it grows, exhale as it shrinks. " +
                            "Your HRV (RMSSD) is tracked live from the strap's R-R intervals.",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// MARK: - Breathing circle

@Composable
private fun BreathingCircle(
    phaseFraction: Double,
    isInhale: Boolean,
    modifier: Modifier = Modifier,
) {
    val baseColor = if (isInhale) Palette.accent else Palette.restColor
    val scale = if (isInhale) {
        0.4 + phaseFraction * 0.6 // expand on inhale
    } else {
        // Exhale: phaseFraction goes from inhaleFrac to 1.0
        // We need to map it back to a 0..1 that shrinks
        1.0 - (phaseFraction - 0.5).coerceIn(0.0, 0.5) * 2.0 * 0.6 + 0.4
    }
    val animatedScale = (if (isInhale) 0.4 + phaseFraction * 0.6 else 1.0 - (phaseFraction - 0.5) * 2.0 * 0.6).coerceIn(0.4, 1.0)

    val glowColor = baseColor.copy(alpha = 0.15f)
    val ringColor = baseColor.copy(alpha = 0.3f)

    Box(
        modifier = modifier
            .height(280.dp)
            .clip(RoundedCornerShape(Metrics.cardRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(280.dp),
        ) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val maxRadius = (minOf(w, h) / 2f) * 0.8f
            val radius = (maxRadius * animatedScale).toFloat()

            // Outer glow
            drawCircle(glowColor, radius = radius * 1.3f, center = Offset(cx, cy))
            // Ring
            drawCircle(
                color = ringColor,
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = 4f),
            )
            // Fill
            drawCircle(baseColor.copy(alpha = 0.08f), radius = radius * 0.9f, center = Offset(cx, cy))

            // Phase text
            // (Text drawn via Canvas nativeCanvas would be Android-specific; use Compose Text instead)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (isInhale) "Inhale" else "Exhale",
                style = NoopType.display(28f),
                color = baseColor,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                String.format(Locale.US, "%.0f%%", animatedScale * 100),
                style = NoopType.number(16f, FontWeight.Medium),
                color = Palette.textTertiary,
            )
        }
    }
}

// MARK: - Pace row

@Composable
private fun PaceRow(
    pace: Pace,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) Palette.accent.copy(alpha = 0.12f) else Palette.surfaceInset
    val border = if (selected) Palette.accent.copy(alpha = 0.3f) else Palette.hairline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) Palette.accent else Palette.hairline),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(pace.label, style = NoopType.body, color = Palette.textPrimary)
            val cycleSec = pace.cycleSeconds()
            val brpm = 60.0 / cycleSec
            Text(
                String.format(Locale.US, "%.1f breaths/min \u00b7 %.0fs cycle", brpm, cycleSec),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
        if (selected) {
            IconButton(onClick = onClick) {
                Icon(Icons.Filled.Air, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(20.dp))
            }
        } else {
            androidx.compose.material3.IconButton(onClick = onClick) {
                Icon(Icons.Filled.Air, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// MARK: - Session results

@Composable
private fun SessionResults(
    durationSec: Int,
    breaths: Int,
    avgRmssd: Double?,
    avgHr: Int?,
    pace: Pace,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        NoopCard(tint = Palette.restColor) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Overline("Session complete")
                Text("${pace.label}", style = NoopType.title2, color = Palette.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    StatTile(
                        modifier = Modifier.weight(1f),
                        label = "Duration",
                        value = formatTime(durationSec),
                        accent = Palette.restColor,
                    )
                    StatTile(
                        modifier = Modifier.weight(1f),
                        label = "Breaths",
                        value = "$breaths",
                        accent = Palette.restColor,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    StatTile(
                        modifier = Modifier.weight(1f),
                        label = "Avg HR",
                        value = avgHr?.let { "$it bpm" } ?: "--",
                        accent = Palette.restColor,
                    )
                    StatTile(
                        modifier = Modifier.weight(1f),
                        label = "RMSSD",
                        value = avgRmssd?.let { String.format(Locale.US, "%.0f ms", it) } ?: "--",
                        caption = "Higher = better parasympathetic tone",
                        accent = Palette.restColor,
                    )
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Done", style = NoopType.body.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

// MARK: - Helpers

private fun formatTime(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}
