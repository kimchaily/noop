package com.noop.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import java.util.Locale

/*
 * WimHofAudio.kt — the spoken/toned guide, and the optional background track.
 *
 * NOTHING IS BUNDLED. Every version of the app is re-downloaded on update, so shipping audio would
 * cost the user that size forever. Instead:
 *   • tones are SYNTHESISED at runtime by the existing [BreathTonePlayer] (a sine wave into an
 *     AudioTrack — no files);
 *   • speech is the phone's OWN TextToSpeech engine (no dependency, no voice data, works offline);
 *   • a background track is the user's own file, referenced by content URI and streamed — LINKED,
 *     never imported into app storage.
 *
 * WHY THE TRACK IS NOT SYNCED, and why that is not a shortcut: a single recorded session has fixed
 * retention lengths, but ours are user-chosen or ended by a tap, and the round/breath counts are
 * settings. There is no cut of a recording that stays aligned with that past the first round. So the
 * app's own cues remain the single source of timing truth and the track plays underneath as ambience.
 * The UI says so plainly rather than implying a sync that cannot exist.
 */

/** A guidance moment. Each maps to a tone and (in Spoken mode) a short line. */
enum class WimHofCue {
    RoundStart,
    Inhale,
    Exhale,
    RetentionStart,
    RecoveryStart,
    RoundComplete,
    SessionComplete,
}

/**
 * Plays the guide for one session. Build it when the screen appears, [release] it when the screen goes
 * away — every resource here (TTS engine, MediaPlayer, tone tracks) is owned and freed by this object.
 *
 * Every playback path is best-effort: audio is a nicety, never load-bearing. A missing TTS engine, a
 * deleted background file or a wedged AudioTrack must leave the session running silently, never crash
 * it, so each entry point swallows its own failures.
 */
class WimHofAudioGuide(context: Context) {

    private val appContext = context.applicationContext

    /** Reused verbatim from the Breathe screen — same synthesised tones, same "honours silent mode"
     *  promise (it declines to play when the ringer is on silent/vibrate). */
    private val tones = BreathTonePlayer(appContext)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var track: MediaPlayer? = null

    /** Spin up the phone's TTS engine. Cheap no-op when the mode never speaks. */
    fun prepare(mode: WimHofAudioMode) {
        if (mode != WimHofAudioMode.Spoken || tts != null) return
        tts = runCatching {
            TextToSpeech(appContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    // Match the device language where a voice exists; fall back to the default voice
                    // rather than failing, so a German phone speaks German if it can and English if not.
                    runCatching { tts?.language = Locale.getDefault() }
                        .onFailure { runCatching { tts?.language = Locale.ENGLISH } }
                }
            }
        }.getOrNull()
    }

    /**
     * Play [cue] under [mode]. Spoken mode speaks AND tones, so a cue is never missed while the engine
     * is still warming up (TTS init is asynchronous and the first round can start before it lands).
     */
    fun play(cue: WimHofCue, mode: WimHofAudioMode, roundNumber: Int = 0, totalRounds: Int = 0) {
        if (mode == WimHofAudioMode.Off) return
        toneFor(cue)?.let { runCatching { tones.play(it) } }
        if (mode == WimHofAudioMode.Spoken && ttsReady) {
            speechFor(cue, roundNumber, totalRounds)?.let { line ->
                runCatching {
                    // QUEUE_FLUSH: a new phase supersedes the last line — a backed-up queue speaking
                    // "breathe in" during a retention would actively mislead.
                    tts?.speak(line, TextToSpeech.QUEUE_FLUSH, null, "wimhof-${cue.name}")
                }
            }
        }
    }

    /** Inhale/exhale ticks are frequent; only the tone plays, never speech (it would never keep up). */
    fun playBreathTick(inhale: Boolean, mode: WimHofAudioMode) {
        if (mode == WimHofAudioMode.Off) return
        runCatching { tones.play(if (inhale) BreathTone.Inhale else BreathTone.Exhale) }
    }

    // ── Optional background track ────────────────────────────────────────────

    /**
     * Start the user's linked track on a loop at [volume].
     *
     * Returns false when it could not play — the file was moved or deleted, or the persisted URI grant
     * was revoked. The caller clears the stored URI in that case, so a dead link doesn't silently fail
     * on every future session.
     */
    fun startBackgroundTrack(uriString: String, volume: Float): Boolean {
        stopBackgroundTrack()
        return runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(appContext, Uri.parse(uriString))
                isLooping = true
                setVolume(volume, volume)
                prepare()
                start()
            }
            track = player
            true
        }.getOrElse {
            // SecurityException (grant revoked), IOException (file gone), IllegalState — all mean the
            // same thing to the session: carry on without ambience.
            stopBackgroundTrack()
            false
        }
    }

    fun setBackgroundVolume(volume: Float) {
        runCatching { track?.setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f)) }
    }

    fun stopBackgroundTrack() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    /** Free everything. Idempotent, and safe to call from a Compose `onDispose`. */
    fun release() {
        stopBackgroundTrack()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        tones.release()
    }

    private fun toneFor(cue: WimHofCue): BreathTone? = when (cue) {
        WimHofCue.Inhale -> BreathTone.Inhale
        WimHofCue.Exhale -> BreathTone.Exhale
        // The phase changes all get the brighter note — they mark "something changed now", which is
        // what you need to hear with your eyes shut.
        WimHofCue.RoundStart, WimHofCue.RecoveryStart, WimHofCue.SessionComplete -> BreathTone.Inhale
        WimHofCue.RetentionStart, WimHofCue.RoundComplete -> BreathTone.Exhale
    }

    private fun speechFor(cue: WimHofCue, roundNumber: Int, totalRounds: Int): String? = when (cue) {
        WimHofCue.RoundStart ->
            if (roundNumber > 0 && totalRounds > 0) "Round $roundNumber of $totalRounds. Deep breaths in."
            else "Deep breaths in."
        WimHofCue.RetentionStart -> "Let the breath go, and hold."
        WimHofCue.RecoveryStart -> "Deep breath in, and hold."
        WimHofCue.RoundComplete -> "And release. Well done."
        WimHofCue.SessionComplete -> "Session complete. Rest here for a moment."
        // Per-breath cues are carried by the tone and the animation; speaking them would lag the pace.
        WimHofCue.Inhale, WimHofCue.Exhale -> null
    }
}
