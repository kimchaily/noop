package com.noop.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// MARK: - AppTheme — the selectable "colour worlds" (each ships a full light AND dark scheme)
//
// The app used to carry exactly ONE colour identity (the WHOOP-reset blue `DarkTokens`/`LightTokens`),
// swapped only between light and dark. This adds a SECOND axis: a family of hand-tuned themes, each a
// cohesive design with its own canvas, accent, per-domain "colour worlds", metal ramp, scenic sky tint
// and typeface — and each available in BOTH light and dark. The Light/Dark/System toggle is unchanged;
// it now chooses the light or dark variant OF THE SELECTED FAMILY.
//
// HOW IT STAYS SAFE + SMALL: the ~70-token `PaletteTokens` contract is frozen (every screen reads
// `Palette.X`). A new theme is therefore built by `.copy()`-ing the WHOOP base tokens and overriding
// only its IDENTITY tokens — surfaces, accent/chrome, the four domain worlds, the metal (gold/titanium)
// ramp, the scenic sky and card fills. The functional DATA encodings (recovery red→green, HR zones,
// sleep stages, status) are inherited so every chart stays as legible + consistent as the default, and
// the separate Chart-colours axis (Titanium / Classic) keeps working on top of any theme.
//
// DAY-CYCLE INTEGRATION: the time-of-day LiquidSky already dissolves into `Palette.surfaceBase`, so it
// meets every theme's canvas with no seam automatically. On top of that each family carries a subtle
// [skyTint] that the sky is blended toward, so the day-cycle atmosphere reads as PART of the theme
// (warm under Ember, cool-green under Verdant, …) rather than a bolted-on blue sky. Signal keeps
// tint = null so its day-cycle is byte-identical to today.

enum class AppTheme(
    val storageValue: String,
    val label: String,
    /** One-line description shown under the swatch in the picker. */
    val blurb: String,
    val dark: PaletteTokens,
    val light: PaletteTokens,
    /** The family's typeface — applied app-wide via [NoopType]. Built-in generic families so the theme
     *  works fully offline with no bundled/downloaded font assets. */
    val fontFamily: FontFamily,
    /** A subtle hue the day-cycle sky is blended toward so it reads as part of the theme (null = leave
     *  the realistic sky untouched, as Signal does). */
    val skyTint: Color? = null,
    /** How strongly the sky is pulled toward [skyTint] (0 = none). Kept low so the day-cycle keeps its
     *  realistic dawn→day→dusk→night motion; it is only warmed/cooled to match the theme. */
    val skyTintStrength: Float = 0f,
) {
    // ── Signal — the WHOOP-reset blue. The default; identical to the app's existing look. ──────────
    SIGNAL(
        storageValue = "signal",
        label = "Signal",
        blurb = "The signature deep-blue instrument. Clean grotesque type.",
        dark = DarkTokens,
        light = LightTokens,
        fontFamily = FontFamily.SansSerif,
    ),

    // ── Aurora — a cool indigo canvas washed with aurora teal ↔ violet. ────────────────────────────
    AURORA(
        storageValue = "aurora",
        label = "Aurora",
        blurb = "Indigo night lit by aurora teal and violet.",
        dark = DarkTokens.copy(
            surfaceBase = Color(0xFF0E1222), surfaceRaised = Color(0xFF1B2137), surfaceOverlay = Color(0xFF171C2F),
            surfaceInset = Color(0xFF191F33), hairline = Color(0xFF283355), hairlineStrong = Color(0xFF36447A),
            glowAmbient = Color(0xFF11253E),
            accent = Color(0xFF43D6C4), accentHover = Color(0xFF7CE9DC), accentMuted = Color(0xFF10322F), focusRing = Color(0xFF43D6C4),
            chargeColor = Color(0xFF3FE0A8), chargeDeep = Color(0xFF159E74), chargeBright = Color(0xFF6FF0C8), chargeGlow = Color(0xFF3FE0A8),
            effortColor = Color(0xFF7C6CF0), effortDeep = Color(0xFF5546C0), effortBright = Color(0xFFA99AF6), effortGlow = Color(0xFF7C6CF0),
            restColor = Color(0xFF5B7BE0), restDeep = Color(0xFF33489E), restBright = Color(0xFF8AA4F0), restGlow = Color(0xFF5B7BE0),
            stressColor = Color(0xFFE0709A), stressDeep = Color(0xFF43D6C4), stressBright = Color(0xFFF06A54), stressGlow = Color(0xFFE0709A),
            scenicCenter = Color(0xFF1A2340), scenicEdge = Color(0xFF0E1222), scenicStar = Color(0xFFC6D2F0),
            cardFillTop = Color(0xFF16264A), cardFillBottom = Color(0xFF0B1430),
            gold = Color(0xFF43D6C4), goldLight = Color(0xFF86ECDE), goldDeep = Color(0xFF1E9E90),
            goldDeepText = Color(0xFF05201C), signalYellow = Color(0xFF56E0CE),
            titaniumTop = Color(0xFFE9F1F2), titaniumMid = Color(0xFFBCCFD0), titaniumLow = Color(0xFF8CA6A8), titaniumDeep = Color(0xFF5E7B7C),
        ),
        light = LightTokens.copy(
            surfaceBase = Color(0xFFEAF0F4), surfaceInset = Color(0xFFDCE6EC), hairline = Color(0xFFCDDBE2), hairlineStrong = Color(0xFFB4C7D0),
            textPrimary = Color(0xFF142030), textSecondary = Color(0xFF46586A), textTertiary = Color(0xFF7890A0),
            glowAmbient = Color(0xFFD6ECEA),
            accent = Color(0xFF0E9E8E), accentHover = Color(0xFF0A7E72), accentMuted = Color(0xFFDCF1EE), focusRing = Color(0xFF16A99A),
            chargeColor = Color(0xFF16A57E), chargeDeep = Color(0xFF0C7E5E), chargeBright = Color(0xFF4CC9A0), chargeGlow = Color(0xFF16A57E),
            effortColor = Color(0xFF6A5AD8), effortDeep = Color(0xFF4A3AB0), effortBright = Color(0xFF8E7EEC), effortGlow = Color(0xFF6A5AD8),
            restColor = Color(0xFF3A72D0), restDeep = Color(0xFF23509E), restBright = Color(0xFF6A96E0), restGlow = Color(0xFF3A72D0),
            stressColor = Color(0xFFC85A86), stressDeep = Color(0xFF3A72D0), stressBright = Color(0xFFC84E3E), stressGlow = Color(0xFFC85A86),
            scenicCenter = Color(0xFFF4FAFB), scenicEdge = Color(0xFFE4EEF0), scenicStar = Color(0xFFCADCE0),
            cardFillTop = Color(0xFFFFFFFF), cardFillBottom = Color(0xFFF2F8F9),
            gold = Color(0xFF12A99A), goldLight = Color(0xFF5FD8C8), goldDeep = Color(0xFF0A7E72),
            goldDeepText = Color(0xFF04231F), signalYellow = Color(0xFF10A090),
        ),
        fontFamily = FontFamily.SansSerif,
        skyTint = Color(0xFF3A4AC0), skyTintStrength = 0.16f,
    ),

    // ── Ember — a warm charcoal canvas of sunset amber, ember orange and dusty rose, in serif. ──────
    EMBER(
        storageValue = "ember",
        label = "Ember",
        blurb = "Warm sunset amber and ember rose, set in serif.",
        dark = DarkTokens.copy(
            surfaceBase = Color(0xFF17120E), surfaceRaised = Color(0xFF2A231D), surfaceOverlay = Color(0xFF201A15),
            surfaceInset = Color(0xFF231C16), hairline = Color(0xFF3E2E20), hairlineStrong = Color(0xFF573F2C),
            textPrimary = Color(0xFFF7F1E8), textSecondary = Color(0xFFD8CBBB), textTertiary = Color(0xFFA0917E),
            glowAmbient = Color(0xFF3A2308),
            accent = Color(0xFFF0913A), accentHover = Color(0xFFF6B268), accentMuted = Color(0xFF3A2410), focusRing = Color(0xFFF0913A),
            chargeColor = Color(0xFFF0B84A), chargeDeep = Color(0xFFB77E18), chargeBright = Color(0xFFF7D07E), chargeGlow = Color(0xFFF0B84A),
            effortColor = Color(0xFFE8703A), effortDeep = Color(0xFFB04A20), effortBright = Color(0xFFF29A6A), effortGlow = Color(0xFFE8703A),
            restColor = Color(0xFFC58AA0), restDeep = Color(0xFF8A566C), restBright = Color(0xFFE0AEC0), restGlow = Color(0xFFC58AA0),
            stressColor = Color(0xFFE8703A), stressDeep = Color(0xFFF0B84A), stressBright = Color(0xFFD9483A), stressGlow = Color(0xFFE8703A),
            scenicCenter = Color(0xFF2A1D12), scenicEdge = Color(0xFF17120E), scenicStar = Color(0xFFE8D6B8),
            cardFillTop = Color(0xFF3A2810), cardFillBottom = Color(0xFF1E1408),
            gold = Color(0xFFF0913A), goldLight = Color(0xFFF7C078), goldDeep = Color(0xFFB05E14),
            goldDeepText = Color(0xFF2E1A06), signalYellow = Color(0xFFFFC24A),
            titaniumTop = Color(0xFFF3EDE2), titaniumMid = Color(0xFFD2C6B4), titaniumLow = Color(0xFFA89A85), titaniumDeep = Color(0xFF7A6E5C),
        ),
        light = LightTokens.copy(
            surfaceBase = Color(0xFFF3E7D6), surfaceInset = Color(0xFFE8DAC4), hairline = Color(0xFFDBC9AC), hairlineStrong = Color(0xFFC9B08C),
            textPrimary = Color(0xFF2A1E12), textSecondary = Color(0xFF5C4A38), textTertiary = Color(0xFF8C7862),
            glowAmbient = Color(0xFFF3DCB0),
            accent = Color(0xFFC26A16), accentHover = Color(0xFF9E540E), accentMuted = Color(0xFFF3E1C6), focusRing = Color(0xFFD07A1C),
            chargeColor = Color(0xFFC28A1E), chargeDeep = Color(0xFF946212), chargeBright = Color(0xFFE0B44C), chargeGlow = Color(0xFFC28A1E),
            effortColor = Color(0xFFC25A22), effortDeep = Color(0xFF923E14), effortBright = Color(0xFFDE8A50), effortGlow = Color(0xFFC25A22),
            restColor = Color(0xFFA05A74), restDeep = Color(0xFF743F52), restBright = Color(0xFFC286A0), restGlow = Color(0xFFA05A74),
            stressColor = Color(0xFFC25A22), stressDeep = Color(0xFFC28A1E), stressBright = Color(0xFFC0392C), stressGlow = Color(0xFFC25A22),
            scenicCenter = Color(0xFFFBF0DE), scenicEdge = Color(0xFFEEDFC8), scenicStar = Color(0xFFDFC69C),
            cardFillTop = Color(0xFFFFFBF2), cardFillBottom = Color(0xFFF8EEDC),
            gold = Color(0xFFD08A20), goldLight = Color(0xFFEBB556), goldDeep = Color(0xFF9A5E10),
            goldDeepText = Color(0xFF3A2506), signalYellow = Color(0xFFE09800),
        ),
        fontFamily = FontFamily.Serif,
        skyTint = Color(0xFFE07030), skyTintStrength = 0.18f,
    ),

    // ── Verdant — a deep forest canvas of emerald, moss and pine. ───────────────────────────────────
    VERDANT(
        storageValue = "verdant",
        label = "Verdant",
        blurb = "Deep forest emerald, moss and pine.",
        dark = DarkTokens.copy(
            surfaceBase = Color(0xFF0E1512), surfaceRaised = Color(0xFF1B2621), surfaceOverlay = Color(0xFF161F1A),
            surfaceInset = Color(0xFF18211C), hairline = Color(0xFF25382E), hairlineStrong = Color(0xFF335040),
            glowAmbient = Color(0xFF0E2E1E),
            accent = Color(0xFF4CC98A), accentHover = Color(0xFF7CDFAC), accentMuted = Color(0xFF123024), focusRing = Color(0xFF4CC98A),
            chargeColor = Color(0xFF46D08A), chargeDeep = Color(0xFF1E9660), chargeBright = Color(0xFF7CE8B0), chargeGlow = Color(0xFF46D08A),
            effortColor = Color(0xFFA8C24A), effortDeep = Color(0xFF6E8A22), effortBright = Color(0xFFC8DE74), effortGlow = Color(0xFFA8C24A),
            restColor = Color(0xFF3FA0A0), restDeep = Color(0xFF1E6E6E), restBright = Color(0xFF6FC6C6), restGlow = Color(0xFF3FA0A0),
            stressColor = Color(0xFFE0A030), stressDeep = Color(0xFF4CC98A), stressBright = Color(0xFFE0662F), stressGlow = Color(0xFFE0A030),
            scenicCenter = Color(0xFF16241C), scenicEdge = Color(0xFF0E1512), scenicStar = Color(0xFFC2E0CE),
            cardFillTop = Color(0xFF123626), cardFillBottom = Color(0xFF081C13),
            gold = Color(0xFF4CC98A), goldLight = Color(0xFF86E4B4), goldDeep = Color(0xFF1E8E5E),
            goldDeepText = Color(0xFF032014), signalYellow = Color(0xFF9AD84A),
            titaniumTop = Color(0xFFEDF2EE), titaniumMid = Color(0xFFC6D2CB), titaniumLow = Color(0xFF95A69C), titaniumDeep = Color(0xFF677A70),
        ),
        light = LightTokens.copy(
            surfaceBase = Color(0xFFE6EEE6), surfaceInset = Color(0xFFD8E2D8), hairline = Color(0xFFC8D6C8), hairlineStrong = Color(0xFFAEC2AE),
            textPrimary = Color(0xFF142018), textSecondary = Color(0xFF44584C), textTertiary = Color(0xFF748A7C),
            glowAmbient = Color(0xFFD2E8D6),
            accent = Color(0xFF14915E), accentHover = Color(0xFF0E7048), accentMuted = Color(0xFFDBEEE2), focusRing = Color(0xFF1AA068),
            chargeColor = Color(0xFF148E5A), chargeDeep = Color(0xFF0C6A42), chargeBright = Color(0xFF48C088), chargeGlow = Color(0xFF148E5A),
            effortColor = Color(0xFF6E8A1E), effortDeep = Color(0xFF4E6212), effortBright = Color(0xFF9AB840), effortGlow = Color(0xFF6E8A1E),
            restColor = Color(0xFF1E8080), restDeep = Color(0xFF135A5A), restBright = Color(0xFF4CA8A8), restGlow = Color(0xFF1E8080),
            stressColor = Color(0xFFC28A1E), stressDeep = Color(0xFF148E5A), stressBright = Color(0xFFC84E1E), stressGlow = Color(0xFFC28A1E),
            scenicCenter = Color(0xFFF2F8F2), scenicEdge = Color(0xFFE2ECE2), scenicStar = Color(0xFFCADCCC),
            cardFillTop = Color(0xFFFBFDF9), cardFillBottom = Color(0xFFF0F6EE),
            gold = Color(0xFF149E64), goldLight = Color(0xFF5FCE94), goldDeep = Color(0xFF0C6A42),
            goldDeepText = Color(0xFF042012), signalYellow = Color(0xFF7CA818),
        ),
        fontFamily = FontFamily.SansSerif,
        skyTint = Color(0xFF1E7A5A), skyTintStrength = 0.15f,
    ),

    // ── Graphite — a near-monochrome instrument in monospaced type. Steel accents, minimal colour. ──
    GRAPHITE(
        storageValue = "graphite",
        label = "Graphite",
        blurb = "Near-monochrome instrument, monospaced, steel accents.",
        dark = DarkTokens.copy(
            surfaceBase = Color(0xFF0D0F11), surfaceRaised = Color(0xFF1E2124), surfaceOverlay = Color(0xFF17191C),
            surfaceInset = Color(0xFF191B1E), hairline = Color(0xFF2A2E33), hairlineStrong = Color(0xFF3A3F45),
            textPrimary = Color(0xFFF2F4F6), textSecondary = Color(0xFFC0C6CC), textTertiary = Color(0xFF868D95),
            glowAmbient = Color(0xFF1C1E22),
            accent = Color(0xFF9AA6B2), accentHover = Color(0xFFC2CCD6), accentMuted = Color(0xFF23272C), focusRing = Color(0xFF9AA6B2),
            chargeColor = Color(0xFF7FBFA8), chargeDeep = Color(0xFF4C8874), chargeBright = Color(0xFFAEDCCA), chargeGlow = Color(0xFF7FBFA8),
            effortColor = Color(0xFF8AA2C0), effortDeep = Color(0xFF566E8C), effortBright = Color(0xFFB4C6DE), effortGlow = Color(0xFF8AA2C0),
            restColor = Color(0xFF8C96A2), restDeep = Color(0xFF5A636E), restBright = Color(0xFFB6BEC8), restGlow = Color(0xFF8C96A2),
            stressColor = Color(0xFFC2A06A), stressDeep = Color(0xFF8AA2C0), stressBright = Color(0xFFC87A5A), stressGlow = Color(0xFFC2A06A),
            scenicCenter = Color(0xFF16191C), scenicEdge = Color(0xFF0D0F11), scenicStar = Color(0xFFCED4DA),
            cardFillTop = Color(0xFF23272C), cardFillBottom = Color(0xFF101315),
            gold = Color(0xFF9AA6B2), goldLight = Color(0xFFC8D0D8), goldDeep = Color(0xFF636D77),
            goldDeepText = Color(0xFF0D0F11), signalYellow = Color(0xFFD8C088),
            titaniumTop = Color(0xFFEDEFF1), titaniumMid = Color(0xFFC2C7CC), titaniumLow = Color(0xFF8F969D), titaniumDeep = Color(0xFF5E656C),
        ),
        light = LightTokens.copy(
            surfaceBase = Color(0xFFE9EBED), surfaceInset = Color(0xFFDBDEE1), hairline = Color(0xFFCBCFD3), hairlineStrong = Color(0xFFB0B6BC),
            textPrimary = Color(0xFF16191C), textSecondary = Color(0xFF464C52), textTertiary = Color(0xFF787E85),
            glowAmbient = Color(0xFFDDE0E3),
            accent = Color(0xFF565E66), accentHover = Color(0xFF3C434A), accentMuted = Color(0xFFDCDFE3), focusRing = Color(0xFF636D77),
            chargeColor = Color(0xFF3E8A70), chargeDeep = Color(0xFF2A6450), chargeBright = Color(0xFF6EB49A), chargeGlow = Color(0xFF3E8A70),
            effortColor = Color(0xFF52708E), effortDeep = Color(0xFF3A5068), effortBright = Color(0xFF80A0C0), effortGlow = Color(0xFF52708E),
            restColor = Color(0xFF5A636E), restDeep = Color(0xFF3E4650), restBright = Color(0xFF8A929C), restGlow = Color(0xFF5A636E),
            stressColor = Color(0xFF9A7A32), stressDeep = Color(0xFF52708E), stressBright = Color(0xFFB0522E), stressGlow = Color(0xFF9A7A32),
            scenicCenter = Color(0xFFF4F6F7), scenicEdge = Color(0xFFE4E7E9), scenicStar = Color(0xFFCBD0D5),
            cardFillTop = Color(0xFFFCFCFD), cardFillBottom = Color(0xFFF0F2F4),
            gold = Color(0xFF636D77), goldLight = Color(0xFF98A2AC), goldDeep = Color(0xFF444C54),
            goldDeepText = Color(0xFFF2F4F6), signalYellow = Color(0xFFB08820),
        ),
        fontFamily = FontFamily.Monospace,
        skyTint = Color(0xFF3A4048), skyTintStrength = 0.22f,
    );

    /** The token set for the resolved scheme (dark or light) of this family. */
    fun tokens(dark: Boolean): PaletteTokens = if (dark) this.dark else this.light

    companion object {
        val DEFAULT = SIGNAL
        fun fromStorage(raw: String?): AppTheme = entries.firstOrNull { it.storageValue == raw } ?: DEFAULT
    }
}

// MARK: - ThemePrefs — the persisted, live theme-family selection
//
// Mirrors [AppearancePrefs]/[ChartStylePrefs]: persisted in `noop_prefs` and mirrored in snapshot state
// so switching family re-resolves every `Palette.*` read AND the app typeface live, with no flash.
// [load] runs once from MainActivity before first composition.

object ThemePrefs {
    private const val FILE = "noop_prefs"
    private const val KEY = "theme.family"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Live theme family read by [NoopTheme]; defaults to Signal until [load] runs. */
    var family by mutableStateOf(AppTheme.DEFAULT)
        private set

    fun load(ctx: Context) {
        family = AppTheme.fromStorage(prefs(ctx).getString(KEY, AppTheme.DEFAULT.storageValue))
    }

    fun set(ctx: Context, value: AppTheme) {
        family = value
        prefs(ctx).edit().putString(KEY, value.storageValue).apply()
    }
}
