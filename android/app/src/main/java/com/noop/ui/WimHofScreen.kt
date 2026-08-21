package com.noop.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.noop.analytics.RetentionMode
import com.noop.analytics.WimHofConfig
import com.noop.analytics.WimHofEvent
import com.noop.analytics.WimHofPhase
import com.noop.analytics.WimHofSession
import com.noop.analytics.WimHofState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/*
 * WimHofScreen.kt — guided Wim Hof breathwork.
 *
 * Rounds of fast, inhale-led power breathing → a breath hold on EMPTY lungs → a recovery hold on FULL
 * lungs, repeated. The protocol itself lives in the pure, unit-tested [WimHofSession]; this file is the
 * clock, the pixels and the side effects.
 *
 * Deliberately its OWN screen rather than a fourth mode inside Breathe: Breathe is a downshift trainer
 * whose whole readout (RMSSD, a coherence estimate, "your parasympathetic branch is engaging") is
 * actively WRONG here — power breathing raises heart rate and suppresses HRV by design. Reusing that
 * chrome would have the app telling the user they were getting worse at the exact moment the method is
 * working. It also carries a safety gate that has no business appearing on the calm-breathing screen.
 *
 * The animation is DRAWN, not played: the same [LiquidVessel] the Breathe hero uses, driven by
 * [WimHofState.lungFill] — it fills through the inhale, empties through the exhale, sits empty through
 * the retention and full through the recovery hold. No video, no bundled asset, no APK growth.
 */

@Composable
fun WimHofScreen(viewModel: AppViewModel, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    var accepted by remember { mutableStateOf(WimHofPrefs.safetyAccepted(context)) }

    // The safety gate is a hard gate, not a dismissible banner: hyperventilation can make you faint,
    // and the difference between "on a sofa" and "in a bath" is the difference between a breathing
    // exercise and a drowning risk. Nothing else on this screen composes until it is acknowledged.
    if (!accepted) {
        WimHofSafetyGate(onAccept = {
            WimHofPrefs.acceptSafety(context)
            accepted = true
        })
        return
    }
    WimHofSessionScreen(viewModel = viewModel, onOpenSettings = onOpenSettings)
}

// ════════════════════════════════════════════════════════════════════════════
// Safety gate
// ════════════════════════════════════════════════════════════════════════════

/**
 * The points a user must see before their first session. Modelled on [RhythmConsent] — the same
 * clickwrap idiom the Rhythm visualization already uses for its own risk copy, so the app has one
 * recognisable "read this first" shape rather than two.
 */
internal object WimHofSafety {
    val points: List<Pair<String, String>> = listOf(
        "Never in or near water" to
            "This is the one rule with no exceptions. Never do this breathing in a bath, a pool, a lake, the sea, or a shower. People have drowned doing breathwork in water because a blackout gives no warning.",
        "Sit or lie down, always" to
            "Never practise while standing, walking, cycling, driving, or operating anything. Fast breathing lowers the carbon dioxide in your blood and can make you faint without warning. Sit or lie somewhere you would be safe if you passed out.",
        "Stop if it feels wrong" to
            "Tingling in the hands and face and a little light-headedness are ordinary. Pain, real dizziness, a racing panic or anything that frightens you is your cue to stop, breathe normally, and leave it for today.",
        "Not for everyone" to
            "Skip it if you are pregnant, or have epilepsy, a heart condition, high blood pressure, or a history of fainting — and talk to a qualified professional first. Choop cannot know your medical history and does not check it.",
        "This is not a medical device" to
            "A breathing timer with an animation and a counter. It gives no medical advice, monitors nothing for your safety, and cannot tell whether today is a good day for this. You are responsible for how you practise.",
    )
}

@Composable
private fun WimHofSafetyGate(onAccept: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(40.dp))
            Text("Before your first session", style = NoopType.title1, color = Palette.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Wim Hof breathing is powerful, and it carries a real risk. Please read these first.",
                style = NoopType.subhead, color = Palette.textSecondary,
            )
            Spacer(Modifier.height(20.dp))

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                WimHofSafety.points.forEach { (head, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(head, style = NoopType.headline, color = Palette.textPrimary)
                        Text(body, style = NoopType.footnote, color = Palette.textSecondary)
                    }
                }
                Text(
                    "Choop is not affiliated with Wim Hof or the Wim Hof Method. This is a general wellness timer, not instruction, training, or medical advice.",
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "I have read these, I will never practise in water or while standing, and I take responsibility for my own practice.",
                    style = NoopType.footnote, color = Palette.textPrimary,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAccept,
                enabled = checked,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.gold,
                    contentColor = Palette.goldDeepText,
                ),
            ) {
                Text("I understand", style = NoopType.body)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// The session screen
// ════════════════════════════════════════════════════════════════════════════

/** The hero card tokens, matching the Breathe hero so the two breathing screens read as siblings. */
private val WIM_HERO_RADIUS = 26.dp

@Composable
private fun WimHofSessionScreen(viewModel: AppViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    // The journal write MUST outlive this composable: a session banked on the way out of the screen
    // (the onDispose path below) would have its write cancelled instantly by a rememberCoroutineScope,
    // silently losing rounds the user really completed. The ViewModel's scope survives navigation.
    val scope = viewModel.viewModelScope
    val bpm by viewModel.bpm.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()

    // SharedPreferences isn't reactive: read once into state, mirror every write back into it.
    var config by remember { mutableStateOf(WimHofPrefs.config(context)) }
    var audioMode by remember { mutableStateOf(WimHofPrefs.audioMode(context)) }
    var showBpm by remember { mutableStateOf(WimHofPrefs.showBpm(context)) }

    var state by remember { mutableStateOf<WimHofState?>(null) }
    var paused by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<WimHofState?>(null) }
    var logLine by remember { mutableStateOf<String?>(null) }
    var bestEver by remember { mutableIntStateOf(WimHofPrefs.bestRetentionSeconds(context) ?: 0) }
    var startedAtEpochSec by remember { mutableStateOf(0L) }

    val audio = remember { WimHofAudioGuide(context) }
    DisposableEffect(Unit) { onDispose { audio.release() } }

    val running = state != null && state?.isDone == false

    // KEEP THE SCREEN ON while a session is live. A retention hold is done with the eyes shut and no
    // touch input for minutes at a time — without this the display sleeps mid-hold and the guide is
    // gone at exactly the moment it is needed. Cleared the instant the session ends or the screen goes
    // away, so it can never leak into the rest of the app.
    val activity = context as? Activity
    DisposableEffect(running, activity) {
        if (running) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    /** Bank the finished session: history, then the journal write. */
    fun finish(finished: WimHofState) {
        summary = finished
        state = null
        paused = false
        audio.stopBackgroundTrack()
        viewModel.stopHaptics()

        val targets = WimHofPrefs.targets(context)
        val slot = wimHofSlot(LocalTime.now(), WimHofPrefs.morningCutoffHour(context))
        val enabled = WimHofPrefs.autoJournalEnabled(context)
        scope.launch {
            val result = logWimHofRounds(
                repo = viewModel.repo,
                targets = targets,
                slot = slot,
                dayKey = wimHofJournalDayKey(LocalDate.now()),
                completedRounds = finished.completedRounds,
                enabled = enabled,
            )
            logLine = describeLogResult(result, context)
            WimHofPrefs.appendSession(
                context,
                WimHofSessionRecord(
                    startedAtEpochSec = startedAtEpochSec,
                    rounds = finished.config.rounds,
                    breathsPerRound = finished.config.breathsPerRound,
                    retentionSeconds = finished.retentionSeconds,
                    completedRounds = finished.completedRounds,
                    journaledTo = (result as? WimHofLogResult.Logged)?.canonical,
                ),
            )
            bestEver = WimHofPrefs.bestRetentionSeconds(context) ?: 0
        }
    }

    // ── The session clock ───────────────────────────────────────────────────
    // One coroutine ticking the pure engine. Everything visible — the vessel fill, the breath counter,
    // the hold timer — is derived from the state it returns, so nothing can drift out of step with
    // anything else.
    LaunchedEffect(running, paused) {
        if (!running || paused) return@LaunchedEffect
        var lastPhase = state?.phase
        var lastBreath = -1
        var lastInhale: Boolean? = null
        while (true) {
            delay(TICK_MS.toLong())
            val current = state ?: return@LaunchedEffect
            val next = WimHofSession.advance(current, WimHofEvent.Tick(TICK_MS))

            // Phase transitions: tone/speech, plus ONE strap buzz. Deliberately not per breath — at
            // ~27 breaths a minute a buzz per breath would swamp the BLE link and the strap's haptic
            // queue, and the transitions are the only moments you actually need to feel.
            if (next.phase != lastPhase) {
                when (next.phase) {
                    WimHofPhase.BREATHING -> {
                        audio.play(WimHofCue.RoundStart, audioMode, next.roundIndex + 1, next.config.rounds)
                        viewModel.buzz(loops = 1)
                    }
                    WimHofPhase.RETENTION -> {
                        audio.play(WimHofCue.RetentionStart, audioMode)
                        viewModel.buzz(loops = 2)
                    }
                    WimHofPhase.RECOVERY_HOLD -> {
                        audio.play(WimHofCue.RecoveryStart, audioMode)
                        viewModel.buzz(loops = 1)
                    }
                    WimHofPhase.DONE -> {
                        audio.play(WimHofCue.SessionComplete, audioMode)
                        viewModel.buzz(loops = 3)
                    }
                    WimHofPhase.PREPARE -> Unit
                }
                lastPhase = next.phase
                lastBreath = -1
                lastInhale = null
            }

            // Per-breath tone: fire on the half-cycle edges so the audible cue lands exactly where the
            // vessel turns around.
            if (next.phase == WimHofPhase.BREATHING) {
                val inhaling = next.isInhaling
                if (lastInhale != inhaling || next.breathsDone != lastBreath) {
                    if (lastInhale != inhaling) audio.playBreathTick(inhaling, audioMode)
                    lastInhale = inhaling
                    lastBreath = next.breathsDone
                }
            }

            if (next.isDone) {
                finish(next)
                return@LaunchedEffect
            }
            state = next
        }
    }

    // Leaving the screen mid-session still banks what was completed — the rounds really happened, and
    // silently dropping them would be the one behaviour a user could not recover from. Mirrors the
    // Breathe screen's teardown (#769: always clear strap haptics on the way out).
    DisposableEffect(Unit) {
        onDispose {
            state?.let { s ->
                if (!s.isDone) {
                    val stopped = WimHofSession.advance(s, WimHofEvent.Stop)
                    if (stopped.completedRounds > 0) finish(stopped)
                }
            }
            viewModel.stopHaptics()
            audio.stopBackgroundTrack()
        }
    }

    ScreenScaffold(
        title = "Wim Hof",
        subtitle = "Power breathing · breath hold · recovery",
        topBackground = { LiquidScreenSky() },
    ) {
        val s = state
        if (s != null) {
            WimHofRunningView(
                state = s,
                paused = paused,
                bpm = if (showBpm) bpm else null,
                onAdvance = {
                    val next = WimHofSession.advance(s, WimHofEvent.Advance)
                    if (next.isDone) finish(next) else state = next
                },
                onPause = { paused = !paused },
                onStop = { finish(WimHofSession.advance(s, WimHofEvent.Stop)) },
            )
            return@ScreenScaffold
        }

        summary?.let { done ->
            WimHofSummaryCard(
                state = done,
                logLine = logLine,
                bestEverSeconds = bestEver,
                onOpenSettings = onOpenSettings,
                onDismiss = { summary = null; logLine = null },
            )
        }

        WimHofSetupCard(
            config = config,
            audioMode = audioMode,
            showBpm = showBpm,
            bonded = live.bonded,
            onConfig = { config = it; WimHofPrefs.saveConfig(context, it) },
            onAudioMode = { audioMode = it; WimHofPrefs.setAudioMode(context, it) },
            onShowBpm = { showBpm = it; WimHofPrefs.setShowBpm(context, it) },
            onStart = {
                summary = null
                logLine = null
                startedAtEpochSec = System.currentTimeMillis() / 1000L
                audio.prepare(audioMode)
                WimHofPrefs.trackUri(context)?.let { uri ->
                    val ok = audio.startBackgroundTrack(uri, WimHofPrefs.trackVolume(context))
                    // A dead link (file moved, grant revoked) is cleared rather than retried forever.
                    if (!ok) WimHofPrefs.setTrackUri(context, null)
                }
                paused = false
                state = WimHofSession.start(config)
            },
        )

        WimHofJournalStatusCard(onOpenSettings = onOpenSettings)

        if (bestEver > 0) {
            NoopCard(padding = 14.dp, tint = Palette.restColor) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = Palette.restBright,
                        modifier = Modifier.size(16.dp).padding(end = 8.dp))
                    Text(
                        "Longest hold so far: ${mmss(bestEver)}",
                        style = NoopType.footnote, color = Palette.textSecondary, modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        WimHofSafetyReminder()
    }
}

/** How often the engine is ticked. Fast enough that the vessel animation reads as continuous. */
private const val TICK_MS = 50

// ── Running view ─────────────────────────────────────────────────────────────

@Composable
private fun WimHofRunningView(
    state: WimHofState,
    paused: Boolean,
    bpm: Int?,
    onAdvance: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    val tapToEnd = state.config.retention is RetentionMode.TapToEnd
    val canAdvance = state.phase == WimHofPhase.RETENTION || state.phase == WimHofPhase.PREPARE

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatePill(
            if (paused) "Paused" else "Round ${state.roundIndex + 1} / ${state.config.rounds}",
            tone = if (paused) StrandTone.Warning else StrandTone.Accent,
            pulsing = !paused,
        )
        Spacer(Modifier.weight(1f))
        Text(phaseTitle(state.phase), style = NoopType.captionNumber, color = Palette.textSecondary)
    }

    // The hero: the liquid vessel IS the breath animation. It rises through the inhale, falls through
    // the exhale, sits empty through the retention and full through the recovery hold, driven by the
    // single [WimHofState.lungFill] value so the picture can never disagree with the counter over it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WIM_HERO_RADIUS))
            .background(Palette.heroFill)
            .border(1.dp, Palette.heroHairline, RoundedCornerShape(WIM_HERO_RADIUS))
            .then(
                // The whole hero is the tap target for ending a hold — eyes closed, you should not have
                // to find a button. Only live when a tap actually means something.
                if (canAdvance && !paused) {
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onAdvance,
                    )
                } else {
                    Modifier
                },
            )
            .padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                contentAlignment = Alignment.Center,
            ) {
                LiquidVessel(
                    value = state.lungFill,
                    tint = phaseTint(state.phase),
                    animated = !paused,
                    modifier = Modifier.height(280.dp),
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clearAndSetSemantics {},
                ) {
                    Text(
                        heroValue(state),
                        style = NoopType.number(40f, weight = FontWeight.Bold).copy(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.5f),
                                offset = Offset(0f, 1f),
                                blurRadius = 6f,
                            ),
                        ),
                        color = Color.White,
                    )
                    Text(
                        heroUnit(state),
                        style = NoopType.footnote.copy(letterSpacing = 0.8.sp),
                        color = Palette.textTertiary,
                    )
                }
            }

            Text(
                text = phaseInstruction(state, tapToEnd),
                style = NoopType.subhead,
                color = phaseTint(state.phase),
            )

            if (bpm != null) {
                // Shown only on request. The caption is the point: during power breathing a climbing
                // heart rate is the method working, not a warning — without saying so, the number
                // reads as alarming at exactly the wrong moment.
                Text(
                    "$bpm bpm · a rising heart rate here is expected",
                    style = NoopType.caption, color = Palette.textTertiary,
                )
            }
        }
    }

    // Round progress across the whole session.
    LiquidTube(
        frac = sessionProgress(state),
        tint = Palette.restBright,
        animated = false,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), modifier = Modifier.fillMaxWidth()) {
        if (canAdvance) {
            Button(
                onClick = onAdvance,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.accent, contentColor = Palette.surfaceBase),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(
                    if (state.phase == WimHofPhase.PREPARE) "Start now" else "I need to breathe",
                    style = NoopType.headline,
                )
            }
        }
        OutlinedButton(
            onClick = onPause,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.textSecondary),
        ) { Text(if (paused) "Resume" else "Pause", style = NoopType.body) }
        OutlinedButton(
            onClick = onStop,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.statusCritical),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text("Stop", style = NoopType.body)
        }
    }

    Text(
        "Completed rounds so far: ${state.completedRounds}. Only rounds you carry through the recovery hold are counted and logged.",
        style = NoopType.footnote, color = Palette.textTertiary,
    )
}

// ── Setup card ───────────────────────────────────────────────────────────────

@Composable
private fun WimHofSetupCard(
    config: WimHofConfig,
    audioMode: WimHofAudioMode,
    showBpm: Boolean,
    bonded: Boolean,
    onConfig: (WimHofConfig) -> Unit,
    onAudioMode: (WimHofAudioMode) -> Unit,
    onShowBpm: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    val fixed = config.retention is RetentionMode.Fixed
    NoopCard(padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Overline("Session")
                Spacer(Modifier.weight(1f))
                StatePill(
                    if (bonded) "Haptics on" else "Visual only",
                    tone = if (bonded) StrandTone.Positive else StrandTone.Neutral,
                )
            }

            StepperRow(
                label = "Rounds",
                value = config.rounds.toString(),
                onMinus = { onConfig(config.copy(rounds = config.rounds - 1).sanitised()) },
                onPlus = { onConfig(config.copy(rounds = config.rounds + 1).sanitised()) },
            )
            StepperRow(
                label = "Breaths per round",
                value = config.breathsPerRound.toString(),
                onMinus = { onConfig(config.copy(breathsPerRound = config.breathsPerRound - 1).sanitised()) },
                onPlus = { onConfig(config.copy(breathsPerRound = config.breathsPerRound + 1).sanitised()) },
            )
            // Shown as seconds per breath, so − and + move the displayed number the way the labels
            // suggest (− is a faster breath, + a slower one).
            StepperRow(
                label = "Seconds per breath",
                value = String.format(Locale.US, "%.1f", config.breathCycleMs / 1000.0),
                onMinus = { onConfig(config.copy(breathCycleMs = config.breathCycleMs - 100).sanitised()) },
                onPlus = { onConfig(config.copy(breathCycleMs = config.breathCycleMs + 100).sanitised()) },
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Overline("Breath hold")
                SegmentedPillControl(
                    items = listOf(false, true),
                    selection = fixed,
                    label = { if (it) "Timed" else "Tap to end" },
                    onSelect = { wantFixed ->
                        onConfig(
                            config.copy(
                                retention = if (wantFixed) {
                                    RetentionMode.Fixed(RetentionMode.DEFAULT_FIXED_TARGETS)
                                } else {
                                    RetentionMode.TapToEnd()
                                },
                            ).sanitised(),
                        )
                    },
                )
                Text(
                    if (fixed) {
                        "Counts down to ${(config.retention as RetentionMode.Fixed).targetsSeconds.joinToString(" · ") { mmss(it) }}, then moves on by itself."
                    } else {
                        "Hold as long as is comfortable and tap when you need to breathe. Your time is recorded."
                    },
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null,
                    tint = if (audioMode == WimHofAudioMode.Off) Palette.textTertiary else Palette.restBright,
                    modifier = Modifier.size(16.dp).padding(end = 10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Audio guide", style = NoopType.footnote, color = Palette.textSecondary)
                    Text(
                        when (audioMode) {
                            WimHofAudioMode.Tones -> "Soft tones at each phase · honours silent mode"
                            WimHofAudioMode.Spoken -> "Spoken cues from your phone's own voice, plus tones"
                            WimHofAudioMode.Off -> "Silent · the animation and strap buzzes still guide"
                        },
                        style = NoopType.caption, color = Palette.textTertiary, maxLines = 2,
                    )
                }
                SegmentedPillControl(
                    items = WimHofAudioMode.entries.toList(),
                    selection = audioMode,
                    label = { it.label },
                    onSelect = onAudioMode,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null,
                    tint = if (showBpm) Palette.restBright else Palette.textTertiary,
                    modifier = Modifier.size(16.dp).padding(end = 10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show heart rate", style = NoopType.footnote, color = Palette.textSecondary)
                    Text(
                        "Off by default: power breathing raises it on purpose",
                        style = NoopType.caption, color = Palette.textTertiary, maxLines = 1,
                    )
                }
                Switch(
                    checked = showBpm,
                    onCheckedChange = onShowBpm,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.textSecondary,
                        uncheckedTrackColor = Palette.surfaceInset,
                        uncheckedBorderColor = Palette.hairline,
                    ),
                    modifier = Modifier.semantics { contentDescription = "Show heart rate" },
                )
            }

            Text(
                "About ${estimateLine(config)} · ${config.rounds} rounds × ${config.breathsPerRound} breaths",
                style = NoopType.footnote, color = Palette.textTertiary,
            )

            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.accent, contentColor = Palette.surfaceBase),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("Start session", style = NoopType.headline)
            }
        }
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onMinus,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.textSecondary),
        ) { Text("−", style = NoopType.body) }
        Spacer(Modifier.width(10.dp))
        Text(
            value, style = NoopType.number(18f), color = Palette.textPrimary,
            modifier = Modifier.width(56.dp).semantics { contentDescription = "$label $value" },
        )
        Spacer(Modifier.width(10.dp))
        OutlinedButton(
            onClick = onPlus,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.textSecondary),
        ) { Text("+", style = NoopType.body) }
    }
}

// ── Summary + status cards ───────────────────────────────────────────────────

@Composable
private fun WimHofSummaryCard(
    state: WimHofState,
    logLine: String?,
    bestEverSeconds: Int,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    NoopCard(tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Air, contentDescription = null, tint = Palette.restBright,
                    modifier = Modifier.size(16.dp).padding(end = 8.dp))
                Overline("Session complete")
                Spacer(Modifier.weight(1f))
                StatePill(
                    "${state.completedRounds} / ${state.config.rounds} rounds",
                    tone = if (state.completedRounds >= state.config.rounds) StrandTone.Positive else StrandTone.Neutral,
                )
            }

            if (state.retentionSeconds.isEmpty()) {
                Text(
                    "No hold was completed this time. Nothing was logged.",
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
            } else {
                Text(
                    state.retentionSeconds
                        .mapIndexed { i, sec -> "Round ${i + 1}: ${mmss(sec)}" }
                        .joinToString("   "),
                    style = NoopType.subhead, color = Palette.textPrimary,
                )
                state.bestRetentionSeconds?.let { best ->
                    Text(
                        if (bestEverSeconds > 0 && best >= bestEverSeconds) {
                            "Best hold ${mmss(best)} — your longest yet."
                        } else {
                            "Best hold ${mmss(best)}."
                        },
                        style = NoopType.footnote, color = Palette.textSecondary,
                    )
                }
            }

            logLine?.let { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.statusPositive,
                        modifier = Modifier.size(14.dp).padding(end = 6.dp))
                    Text(line, style = NoopType.footnote, color = Palette.textSecondary,
                        modifier = Modifier.weight(1f))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.textSecondary),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Journal setup", style = NoopType.body)
                }
                TextButton(onClick = onDismiss) {
                    Text("Done", style = NoopType.body, color = Palette.accent)
                }
            }
        }
    }
}

/** Tells the user, before they start, exactly where their rounds are going to be written. */
@Composable
private fun WimHofJournalStatusCard(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val enabled = WimHofPrefs.autoJournalEnabled(context)
    val targets = WimHofPrefs.targets(context)
    val cutoff = WimHofPrefs.morningCutoffHour(context)
    val slot = wimHofSlot(LocalTime.now(), cutoff)
    val canonical = targets.canonicalFor(slot)
    val slotWord = if (slot == WimHofSlot.MORNING) "morning" else "evening"

    NoopCard(padding = 14.dp) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Journal", style = NoopType.footnote, color = Palette.textSecondary)
                Text(
                    when {
                        !enabled -> "Auto-logging is off. Completed rounds won't be written."
                        canonical == null -> "No $slotWord item picked yet — completed rounds won't be logged."
                        else -> "Completed rounds will be added to \"$canonical\" ($slotWord)."
                    },
                    style = NoopType.caption,
                    color = if (enabled && canonical != null) Palette.textTertiary else Palette.statusWarning,
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text("Change", style = NoopType.body, color = Palette.accent)
            }
        }
    }
}

@Composable
private fun WimHofSafetyReminder() {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.statusWarning.copy(alpha = 0.08f), shape)
            .border(1.dp, Palette.statusWarning.copy(alpha = 0.25f), shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.statusWarning)
        Text(
            "Sit or lie down. Never in or near water, never while driving or standing — this breathing can make you faint without warning. Stop if anything feels wrong.",
            style = NoopType.footnote, color = Palette.textSecondary,
        )
    }
}

// ── Pure display helpers (kept internal so the unit tests can reach them) ─────

internal fun mmss(totalSeconds: Int): String =
    String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)

private fun phaseTitle(phase: WimHofPhase): String = when (phase) {
    WimHofPhase.PREPARE -> "Settle in"
    WimHofPhase.BREATHING -> "Power breathing"
    WimHofPhase.RETENTION -> "Breath hold"
    WimHofPhase.RECOVERY_HOLD -> "Recovery hold"
    WimHofPhase.DONE -> "Done"
}

private fun phaseInstruction(state: WimHofState, tapToEnd: Boolean): String = when (state.phase) {
    WimHofPhase.PREPARE -> "Sit or lie down. Loosen your shoulders."
    WimHofPhase.BREATHING -> if (state.isInhaling) "Breathe in — fully" else "Let it go"
    WimHofPhase.RETENTION ->
        if (tapToEnd) "Hold — empty. Tap when you need to breathe." else "Hold — empty"
    WimHofPhase.RECOVERY_HOLD -> "Deep breath in — hold"
    WimHofPhase.DONE -> "Rest"
}

private fun phaseTint(phase: WimHofPhase): Color = when (phase) {
    WimHofPhase.BREATHING -> Palette.restBright
    WimHofPhase.RETENTION -> Palette.metricPurple
    WimHofPhase.RECOVERY_HOLD -> Palette.statusPositive
    else -> Palette.textSecondary
}

/** The big number over the vessel: breaths while breathing, a clock while holding. */
private fun heroValue(state: WimHofState): String = when (state.phase) {
    WimHofPhase.PREPARE ->
        ((state.config.prepareSeconds * 1000 - state.phaseElapsedMs + 999) / 1000).coerceAtLeast(0).toString()
    WimHofPhase.BREATHING -> "${(state.breathsDone + 1).coerceAtMost(state.config.breathsPerRound)}"
    WimHofPhase.RETENTION -> {
        val elapsed = state.phaseElapsedMs / 1000
        when (state.config.retention) {
            // Tap-to-end counts UP: the achieved time is the number worth seeing afterwards.
            is RetentionMode.TapToEnd -> mmss(elapsed)
            // Timed counts DOWN to the target, so the end of the hold is visible.
            is RetentionMode.Fixed -> mmss(
                (WimHofSession.retentionBudgetSeconds(state.config, state.roundIndex) - elapsed)
                    .coerceAtLeast(0),
            )
        }
    }
    WimHofPhase.RECOVERY_HOLD ->
        ((state.config.recoveryHoldSeconds * 1000 - state.phaseElapsedMs + 999) / 1000)
            .coerceAtLeast(0).toString()
    WimHofPhase.DONE -> "✓"
}

private fun heroUnit(state: WimHofState): String = when (state.phase) {
    WimHofPhase.PREPARE -> "STARTING"
    WimHofPhase.BREATHING -> "OF ${state.config.breathsPerRound}"
    WimHofPhase.RETENTION -> "HOLD"
    WimHofPhase.RECOVERY_HOLD -> "SECONDS"
    WimHofPhase.DONE -> "COMPLETE"
}

/** 0..1 through the whole session, by completed rounds plus progress within the current one. */
internal fun sessionProgress(state: WimHofState): Double {
    val rounds = state.config.rounds.coerceAtLeast(1)
    val within = when (state.phase) {
        WimHofPhase.BREATHING -> 0.25
        WimHofPhase.RETENTION -> 0.6
        WimHofPhase.RECOVERY_HOLD -> 0.85
        else -> 0.0
    }
    return ((state.completedRounds + within) / rounds).coerceIn(0.0, 1.0)
}

private fun estimateLine(config: WimHofConfig): String {
    val seconds = WimHofSession.estimatedDurationSeconds(config)
    val minutes = (seconds + 59) / 60
    return "$minutes min"
}

/** One honest sentence about what the journal write did (or why it did nothing). */
internal fun describeLogResult(result: WimHofLogResult, context: android.content.Context): String =
    when (result) {
        is WimHofLogResult.Logged -> {
            val name = journalDisplayName(loadJournalCatalogItems(context), result.canonical)
            "Added ${result.added} to \"$name\" for ${result.dayKey} (now ${formatRounds(result.total)})."
        }
        is WimHofLogResult.NoCompletedRounds ->
            "Nothing logged — no round was carried through to its recovery hold."
        is WimHofLogResult.NoTarget ->
            "Nothing logged — pick your ${if (result.slot == WimHofSlot.MORNING) "morning" else "evening"} journal item in Settings first."
        is WimHofLogResult.Disabled ->
            "Nothing logged — auto-logging is switched off in Settings."
    }

private fun formatRounds(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
