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
// (warm under Ember, violet under Aurora, lime under Verdant, …) rather than a bolted-on blue sky. Signal keeps
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
    /** The liquid-hero card fill — the deep, translucent near-black the score vessels + white ring
     *  numbers float on (Today rings, Live HR, Sleep, Stress, …). It stays DARK in every scheme (those
     *  white numbers + dark-glass vessels need a dark ground for contrast) but is HUED to the family so
     *  the hero harmonises with the theme instead of a fixed neutral charcoal that clashes on a light
     *  or coloured canvas. Default = the original Signal near-black (so Signal is byte-identical). */
    val heroFill: Color = Color(red = 13f / 255f, green = 14f / 255f, blue = 20f / 255f, alpha = 0.80f),
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

    // ── Aurora — an electric violet-magenta nightscape. Pink charge, purple effort, blue-violet rest.
    // Deliberately the FARTHEST hue-family from Signal's steel blue and Verdant's lime — no teal/cyan
    // shared with any other theme, so it reads as a distinct "aurora borealis at midnight" identity.
    AURORA(
        storageValue = "aurora",
        label = "Aurora",
        blurb = "Electric violet-magenta nightscape — aurora-borealis intensity.",
        dark = DarkTokens.copy(
            surfaceBase = Color(0xFF140A24), surfaceRaised = Color(0xFF241639), surfaceOverlay = Color(0xFF1C1030),
            surfaceInset = Color(0xFF1E1233), hairline = Color(0xFF33204E), hairlineStrong = Color(0xFF44296A),
            textPrimary = Color(0xFFF7EEFB), textSecondary = Color(0xFFD3C0E3), textTertiary = Color(0xFF9A82AF),
            glowAmbient = Color(0xFF33123F),
            accent = Color(0xFFE23FD6), accentHover = Color(0xFFF07EE6), accentMuted = Color(0xFF3A1542), focusRing = Color(0xFFE23FD6),
            chargeColor = Color(0xFFFF5FAE), chargeDeep = Color(0xFFC22E80), chargeBright = Color(0xFFFF9CD4), chargeGlow = Color(0xFFFF5FAE),
            effortColor = Color(0xFF8C5CF0), effortDeep = Color(0xFF5B34B0), effortBright = Color(0xFFB89CFA), effortGlow = Color(0xFF8C5CF0),
            restColor = Color(0xFF5C6FE0), restDeep = Color(0xFF33409E), restBright = Color(0xFF9AA6F5), restGlow = Color(0xFF5C6FE0),
            stressColor = Color(0xFFE24FA0), stressDeep = Color(0xFF5C6FE0), stressBright = Color(0xFFFF3D6B), stressGlow = Color(0xFFE24FA0),
            scenicCenter = Color(0xFF241A3E), scenicEdge = Color(0xFF140A24), scenicStar = Color(0xFFE3D3F0),
            cardFillTop = Color(0xFF2A1A46), cardFillBottom = Color(0xFF160A28),
            gold = Color(0xFFE23FD6), goldLight = Color(0xFFF29CEC), goldDeep = Color(0xFFA82CA0),
            goldDeepText = Color(0xFF1F0A22), signalYellow = Color(0xFFEE7CE0),
            titaniumTop = Color(0xFFEDE6F2), titaniumMid = Color(0xFFC9BBD6), titaniumLow = Color(0xFF9A88AC), titaniumDeep = Color(0xFF6C5C7C),
        ),
        light = LightTokens.copy(
            surfaceBase = Color(0xFFF1E9F7), surfaceInset = Color(0xFFE4D6EE), hairline = Color(0xFFD8C6E6), hairlineStrong = Color(0xFFC2A8D6),
            textPrimary = Color(0xFF241531), textSecondary = Color(0xFF52405F), textTertiary = Color(0xFF83728F),
            glowAmbient = Color(0xFFF0DFF6),
            accent = Color(0xFFA020A0), accentHover = Color(0xFF7E1880), accentMuted = Color(0xFFF0DCF2), focusRing = Color(0xFFB02CB0),
            chargeColor = Color(0xFFC23E86), chargeDeep = Color(0xFF8E1E5C), chargeBright = Color(0xFFE070AA), chargeGlow = Color(0xFFC23E86),
            effortColor = Color(0xFF6B3EC2), effortDeep = Color(0xFF4A1E96), effortBright = Color(0xFF9C74E0), effortGlow = Color(0xFF6B3EC2),
            restColor = Color(0xFF3E4EBE), restDeep = Color(0xFF262E8E), restBright = Color(0xFF7482DE), restGlow = Color(0xFF3E4EBE),
            stressColor = Color(0xFFB0308A), stressDeep = Color(0xFF3E4EBE), stressBright = Color(0xFFC81E44), stressGlow = Color(0xFFB0308A),
            scenicCenter = Color(0xFFFAF3FD), scenicEdge = Color(0xFFECDEF3), scenicStar = Color(0xFFD4BEE0),
            cardFillTop = Color(0xFFFFFFFF), cardFillBottom = Color(0xFFF6EDFA),
            gold = Color(0xFFA020A0), goldLight = Color(0xFFD070D0), goldDeep = Color(0xFF7A1878),
            goldDeepText = Color(0xFF1F0A22), signalYellow = Color(0xFF9A2C9A),
        ),
        fontFamily = FontFamily.SansSerif,
        skyTint = Color(0xFFC23FD0), skyTintStrength = 0.20f,
        heroFill = Color(0xFF12081F).copy(alpha = 0.80f),
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
        heroFill = Color(0xFF160F08).copy(alpha = 0.80f),
    ),

    // ── Verdant — a warm moss/lime canvas: sun-baked earth, not cool forest. Pushed to the YELLOW-green
    // side of the wheel (lime/olive/rust) so it never overlaps Aurora's blue-violet or Signal's steel
    // blue — the only "green" theme, and unmistakably warm rather than the old cool teal-forest read.
    VERDANT(
        storageValue = "verdant",
        label = "Verdant",
        blurb = "Sun-baked moss and lime — warm, earthy, alive.",
        dark = DarkTokens.copy(
            surfaceBase = Color(0xFF12140A), surfaceRaised = Color(0xFF232717), surfaceOverlay = Color(0xFF1B1E11),
            surfaceInset = Color(0xFF1D2013), hairline = Color(0xFF2E331D), hairlineStrong = Color(0xFF3E4527),
            textPrimary = Color(0xFFF3F6E8), textSecondary = Color(0xFFCED6B8), textTertiary = Color(0xFF97A17E),
            glowAmbient = Color(0xFF2A3210),
            accent = Color(0xFF9FE23A), accentHover = Color(0xFFBFF06E), accentMuted = Color(0xFF283212), focusRing = Color(0xFF9FE23A),
            chargeColor = Color(0xFFAFE23A), chargeDeep = Color(0xFF6E9E1E), chargeBright = Color(0xFFD4F582), chargeGlow = Color(0xFFAFE23A),
            effortColor = Color(0xFFD8B23A), effortDeep = Color(0xFF9E7A1A), effortBright = Color(0xFFEFD684), effortGlow = Color(0xFFD8B23A),
            restColor = Color(0xFF4A8A6A), restDeep = Color(0xFF2E5E46), restBright = Color(0xFF7CB89A), restGlow = Color(0xFF4A8A6A),
            stressColor = Color(0xFFCC7A2E), stressDeep = Color(0xFF4A8A6A), stressBright = Color(0xFFD9432E), stressGlow = Color(0xFFCC7A2E),
            scenicCenter = Color(0xFF1E2412), scenicEdge = Color(0xFF12140A), scenicStar = Color(0xFFDCE6C2),
            cardFillTop = Color(0xFF232B14), cardFillBottom = Color(0xFF10150A),
            gold = Color(0xFF9FE23A), goldLight = Color(0xFFCFF080), goldDeep = Color(0xFF6E9E1E),
            goldDeepText = Color(0xFF0F1408), signalYellow = Color(0xFFDCE23A),
            titaniumTop = Color(0xFFEEF0E2), titaniumMid = Color(0xFFCCD0B6), titaniumLow = Color(0xFF9DA588), titaniumDeep = Color(0xFF6E765A),
        ),
        light = LightTokens.copy(
            surfaceBase = Color(0xFFEDF0DE), surfaceInset = Color(0xFFDEE3C8), hairline = Color(0xFFD0D6B4), hairlineStrong = Color(0xFFB8C093),
            textPrimary = Color(0xFF1B2010), textSecondary = Color(0xFF48512E), textTertiary = Color(0xFF78805A),
            glowAmbient = Color(0xFFE4EBC4),
            accent = Color(0xFF5C8A12), accentHover = Color(0xFF446C0C), accentMuted = Color(0xFFE2ECC6), focusRing = Color(0xFF6E9E1E),
            chargeColor = Color(0xFF6E9E1E), chargeDeep = Color(0xFF4C6E12), chargeBright = Color(0xFF9CC24A), chargeGlow = Color(0xFF6E9E1E),
            effortColor = Color(0xFFA07A18), effortDeep = Color(0xFF74560E), effortBright = Color(0xFFC6A34C), effortGlow = Color(0xFFA07A18),
            restColor = Color(0xFF1E7A5C), restDeep = Color(0xFF145440), restBright = Color(0xFF4CA684), restGlow = Color(0xFF1E7A5C),
            stressColor = Color(0xFFB0621E), stressDeep = Color(0xFF1E7A5C), stressBright = Color(0xFFC0402A), stressGlow = Color(0xFFB0621E),
            scenicCenter = Color(0xFFF6F8EC), scenicEdge = Color(0xFFE6EBD2), scenicStar = Color(0xFFC8D2A4),
            cardFillTop = Color(0xFFFFFFFF), cardFillBottom = Color(0xFFF2F6E4),
            gold = Color(0xFF5C8A12), goldLight = Color(0xFF94C24C), goldDeep = Color(0xFF446C0C),
            goldDeepText = Color(0xFF10150A), signalYellow = Color(0xFF7E9A18),
        ),
        fontFamily = FontFamily.SansSerif,
        skyTint = Color(0xFF6E9C1E), skyTintStrength = 0.18f,
        heroFill = Color(0xFF0E1007).copy(alpha = 0.80f),
    ),

    // ── Graphite — TRUE achromatic instrument (zero-saturation canvas) with a single warm brass
    // highlight, monospaced. Where the other three re-themed families lean into a saturated hue,
    // Graphite leans the opposite way — its whole identity IS the absence of colour, so it reads as
    // unmistakably different from Aurora/Verdant/Ember at a glance, like a black-and-white photo with
    // one point of warm light.
    GRAPHITE(
        storageValue = "graphite",
        label = "Graphite",
        blurb = "Pure monochrome instrument, one brass highlight, monospaced.",
        dark = DarkTokens.copy(
            surfaceBase = Color(0xFF0A0B0C), surfaceRaised = Color(0xFF1C1E20), surfaceOverlay = Color(0xFF15171A),
            surfaceInset = Color(0xFF17191C), hairline = Color(0xFF282B2E), hairlineStrong = Color(0xFF383C40),
            textPrimary = Color(0xFFF2F3F4), textSecondary = Color(0xFFBEC3C8), textTertiary = Color(0xFF80868D),
            glowAmbient = Color(0xFF1A1A16),
            accent = Color(0xFFC9A227), accentHover = Color(0xFFE0BE55), accentMuted = Color(0xFF2C2515), focusRing = Color(0xFFC9A227),
            chargeColor = Color(0xFFC9A227), chargeDeep = Color(0xFF8E7014), chargeBright = Color(0xFFE8CC72), chargeGlow = Color(0xFFC9A227),
            effortColor = Color(0xFF6E7E8C), effortDeep = Color(0xFF444E58), effortBright = Color(0xFF9EACB8), effortGlow = Color(0xFF6E7E8C),
            restColor = Color(0xFF8A8F94), restDeep = Color(0xFF5A5E62), restBright = Color(0xFFBBC0C4), restGlow = Color(0xFF8A8F94),
            stressColor = Color(0xFFB3552C), stressDeep = Color(0xFF6E7E8C), stressBright = Color(0xFFCC3A2A), stressGlow = Color(0xFFB3552C),
            scenicCenter = Color(0xFF141516), scenicEdge = Color(0xFF0A0B0C), scenicStar = Color(0xFFD8DADC),
            cardFillTop = Color(0xFF1E2022), cardFillBottom = Color(0xFF0D0E0F),
            gold = Color(0xFFC9A227), goldLight = Color(0xFFE0C066), goldDeep = Color(0xFF8E7014),
            goldDeepText = Color(0xFF0D0E0F), signalYellow = Color(0xFFD8B840),
            titaniumTop = Color(0xFFEEEFF0), titaniumMid = Color(0xFFC5C8CB), titaniumLow = Color(0xFF95999D), titaniumDeep = Color(0xFF64686C),
        ),
        light = LightTokens.copy(
            surfaceBase = Color(0xFFEDEDED), surfaceInset = Color(0xFFDCDCDC), hairline = Color(0xFFCBCBCB), hairlineStrong = Color(0xFFB0B0B0),
            textPrimary = Color(0xFF151617), textSecondary = Color(0xFF454749), textTertiary = Color(0xFF77797B),
            glowAmbient = Color(0xFFE0DCC8),
            accent = Color(0xFF8A6A12), accentHover = Color(0xFF6E520C), accentMuted = Color(0xFFE9E0C4), focusRing = Color(0xFF8A6A12),
            chargeColor = Color(0xFF8A6A12), chargeDeep = Color(0xFF5E480C), chargeBright = Color(0xFFBE9A3E), chargeGlow = Color(0xFF8A6A12),
            effortColor = Color(0xFF4E5C68), effortDeep = Color(0xFF34404A), effortBright = Color(0xFF7E8C98), effortGlow = Color(0xFF4E5C68),
            restColor = Color(0xFF6A6E72), restDeep = Color(0xFF484C50), restBright = Color(0xFF9A9EA2), restGlow = Color(0xFF6A6E72),
            stressColor = Color(0xFF8E4420), stressDeep = Color(0xFF4E5C68), stressBright = Color(0xFFA02818), stressGlow = Color(0xFF8E4420),
            scenicCenter = Color(0xFFF6F6F6), scenicEdge = Color(0xFFE6E6E6), scenicStar = Color(0xFFC8C8C8),
            cardFillTop = Color(0xFFFFFFFF), cardFillBottom = Color(0xFFF0F0F0),
            gold = Color(0xFF8A6A12), goldLight = Color(0xFFBE9A3E), goldDeep = Color(0xFF5E480C),
            goldDeepText = Color(0xFFF2F3F4), signalYellow = Color(0xFF6E520C),
        ),
        fontFamily = FontFamily.Monospace,
        skyTint = Color(0xFF4A4038), skyTintStrength = 0.24f,
        heroFill = Color(0xFF08090A).copy(alpha = 0.82f),
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
