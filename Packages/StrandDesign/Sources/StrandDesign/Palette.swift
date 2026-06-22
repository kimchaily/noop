import SwiftUI

// MARK: - Hex Color Helper

public extension Color {
    /// Parse a hex string ("#0B0D12" / "0B0D12" RGB, or "#AARRGGBB"/"RRGGBBAA" RGBA) to sRGB
    /// components in 0...1. Shared by `Color(hex:)` and the dynamic `Color(light:dark:)` provider.
    static func sRGBComponents(hex: String) -> (r: Double, g: Double, b: Double, a: Double) {
        let raw = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: raw).scanHexInt64(&int)
        switch raw.count {
        case 8: // RRGGBBAA
            return (Double((int >> 24) & 0xFF) / 255.0, Double((int >> 16) & 0xFF) / 255.0,
                    Double((int >> 8) & 0xFF) / 255.0, Double(int & 0xFF) / 255.0)
        default: // RRGGBB (6) and any fallback
            return (Double((int >> 16) & 0xFF) / 255.0, Double((int >> 8) & 0xFF) / 255.0,
                    Double(int & 0xFF) / 255.0, 1.0)
        }
    }

    /// Create a Color from a hex string like "#0B0D12" or "0B0D12" (RGB) or "#AARRGGBB" / "RRGGBBAA".
    /// Supported lengths: 6 (RGB), 8 (RGBA).
    init(hex: String) {
        let c = Color.sRGBComponents(hex: hex)
        self.init(.sRGB, red: c.r, green: c.g, blue: c.b, opacity: c.a)
    }

    /// A colour that resolves to `light` or `dark` (both hex strings) per the active appearance.
    /// Backed by a `UIColor`/`NSColor` dynamic provider, so a single token automatically re-resolves
    /// at every one of its call sites when the colour scheme flips — no per-view environment plumbing.
    /// This is the whole light-theme strategy: only the token definitions change, never the call sites.
    init(light: String, dark: String) {
        #if canImport(UIKit)
        self.init(UIColor { trait in
            let c = Color.sRGBComponents(hex: trait.userInterfaceStyle == .dark ? dark : light)
            return UIColor(red: CGFloat(c.r), green: CGFloat(c.g), blue: CGFloat(c.b), alpha: CGFloat(c.a))
        })
        #elseif canImport(AppKit)
        self.init(nsColor: NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
            let c = Color.sRGBComponents(hex: isDark ? dark : light)
            return NSColor(srgbRed: CGFloat(c.r), green: CGFloat(c.g), blue: CGFloat(c.b), alpha: CGFloat(c.a))
        })
        #else
        self.init(hex: dark)
        #endif
    }
}

// MARK: - Strand Palette
//
// The "Titanium & Gold" re-skin: a premium dark theme built on a deep navy canvas with
// per-domain accent "colour worlds" (Charge = gold, Effort = amber, Rest = blue,
// Stress = blue→gold→orange). GOLD is the dominant brand anchor; titanium drives the
// neutral chrome (tiles, avatars, icons).
//
// PUBLIC API IS FROZEN: every property name below is depended on by screens across
// macOS / iOS, so the names never change — only the VALUES were re-themed. New
// Titanium & Gold tokens (gold ramp, titanium ramp, gradients) are ADDED at the end
// of the type; nothing existing was removed or renamed.

public enum StrandPalette {

    // MARK: Surfaces — deep navy canvas, tinted frosted cards
    // Background is a near-black navy (NOT pure black); cards float just above it.
    public static let surfaceBase    = Color(light: "#F2F2F7", dark: "#0E1014") // WHOOP-style dark blue-grey canvas
    public static let surfaceRaised  = Color(light: "#FFFFFF", dark: "#1C1E26") // WHOOP-style grey rounded card fill
    public static let surfaceOverlay = Color(light: "#FFFFFF", dark: "#1C1F26") // popovers / sheets / tooltips
    public static let surfaceInset   = Color(light: "#E9E9EE", dark: "#1F2229") // wells / chart insets / segmented track
    public static let hairline       = Color(light: "#D8D0BD", dark: "#21304A") // soft 1px border (stronger on light for card edges)
    public static let hairlineStrong = Color(light: "#C7BCA4", dark: "#2E3C57") // hover / emphasis border

    // MARK: Text — deep navy-ink on paper / cool off-white on navy
    public static let textPrimary    = Color(light: "#1A2230", dark: "#F4F6F8")
    public static let textSecondary  = Color(light: "#4C5564", dark: "#C8CFD8")
    public static let textTertiary   = Color(light: "#7C8696", dark: "#8A94A4")

    // MARK: Glow — ambient bloom behind heroes / charts (additive on dark; faint warm on light)
    public static let glowAmbient    = Color(light: "#F0E4C0", dark: "#3A2D0A")

    // MARK: Accent — chrome anchor (links, selection, focus, generic accent). On DARK this is the brand
    // GOLD; on LIGHT it shifts to the deep brand BLUE so gold is reserved for the recovery/Charge world
    // and the gold FAB — keeping the light theme from reading as wall-to-wall gold (the maintainer 2026-06-16).
    public static let accent         = Color(light: "#234F9E", dark: "#E8B84B") // deep blue (light) / brand gold (dark)
    public static let accentHover    = Color(light: "#1C3F80", dark: "#FCEBA8")
    public static let accentMuted    = Color(light: "#E4ECF6", dark: "#2A2210") // selected-row tint (pale blue / dark gold)
    /// Focus ring color (blue on light, gold on dark).
    public static let focusRing      = Color(light: "#2F6FCB", dark: "#E8B84B")
    /// Opacity for dimmed/disabled sections (shared so screens don't invent their own value).
    public static let disabledOpacity: Double = 0.45

    // MARK: - Chart style (data-viz colour mode) — Titanium (brand) or Classic (throwback)
    //
    // Set from `@AppStorage(ChartStyle.storageKey)` at the app root. The DATA-RAMP accessors below
    // (recoveryStops, strainStops, hrZones, sleepStageColor, stress gradient, status, metric, and the
    // DomainTheme worlds) branch on this — so flipping it re-colours every gauge/chart/scale to the
    // classic red→green readiness scale, in BOTH light and dark, with NO call-site changes. Chrome
    // (surfaces, text, accent) is never touched.
    public static var chartStyle: ChartStyle = .titanium
    @inline(__always) static var isClassic: Bool { chartStyle == .classic }

    // MARK: Classic (throwback) data ramps — the recognizable health-app scale. Light/dark tuned.
    // Recovery: red → orange → amber → lime → green.
    static let cRecovery000 = Color(light: "#CB3A2F", dark: "#E5483B")
    static let cRecovery030 = Color(light: "#D87328", dark: "#EE8B3C")
    static let cRecovery055 = Color(light: "#CFA528", dark: "#F2C53D")
    static let cRecovery078 = Color(light: "#74A53A", dark: "#A6D04E")
    static let cRecovery100 = Color(light: "#2E9E4F", dark: "#46B45A")
    static let cRecoveryStops: [Gradient.Stop] = [
        .init(color: cRecovery000, location: 0.00), .init(color: cRecovery030, location: 0.30),
        .init(color: cRecovery055, location: 0.55), .init(color: cRecovery078, location: 0.78),
        .init(color: cRecovery100, location: 1.00),
    ]
    // Strain: the classic light→deep blue cardiovascular ramp.
    static let cStrain000 = Color(light: "#5E92D6", dark: "#7FB2E8")
    static let cStrain033 = Color(light: "#3A74C4", dark: "#4A90E2")
    static let cStrain066 = Color(light: "#284F9C", dark: "#2F6FCB")
    static let cStrain100 = Color(light: "#1C3E80", dark: "#1E4FA0")
    static let cStrainStops: [Gradient.Stop] = [
        .init(color: cStrain000, location: 0.00), .init(color: cStrain033, location: 0.33),
        .init(color: cStrain066, location: 0.66), .init(color: cStrain100, location: 1.00),
    ]
    // Sleep: grey awake, blue light, deep indigo, purple REM.
    static let cSleepAwake = Color(light: "#8C95A3", dark: "#C9CCD6")
    static let cSleepLight = Color(light: "#3A80D6", dark: "#6FA8E8")
    static let cSleepDeep  = Color(light: "#203E73", dark: "#2A4C8F")
    static let cSleepREM   = Color(light: "#6A4FC0", dark: "#8E6FD6")
    // HR zones: grey → green → yellow → orange → red.
    static let cZone1 = Color(light: "#828D9B", dark: "#9AA7B5")
    static let cZone2 = Color(light: "#2E9E4F", dark: "#46B45A")
    static let cZone3 = Color(light: "#CFA528", dark: "#F2C53D")
    static let cZone4 = Color(light: "#D87328", dark: "#EE8B3C")
    static let cZone5 = Color(light: "#CB3A2F", dark: "#E5483B")
    // Stress: calm green → amber → red.
    static let cStressStops: [Gradient.Stop] = [
        .init(color: Color(light: "#2E9E4F", dark: "#46B45A"), location: 0.0),
        .init(color: Color(light: "#CFA528", dark: "#F2C53D"), location: 0.5),
        .init(color: Color(light: "#CB3A2F", dark: "#E5483B"), location: 1.0),
    ]

    // MARK: Recovery / Charge gradient — the gold "Charge" colour world.
    // A single warm metal ramp: a deep bronze floor climbs through brand gold into a
    // bright champagne peak — no green anywhere; depleted reads as dim gold, not coral.
    // 0.00 bronze → 0.30 antique gold → 0.55 brand gold → 0.78 soft gold → 1.00 champagne.
    public static let recovery000 = Color(light: "#8F6212", dark: "#C8902F") // depleted — bronze
    public static let recovery030 = Color(light: "#A87718", dark: "#D9A23E") // low — antique gold
    public static let recovery055 = Color(light: "#C28E26", dark: "#E8B84B") // moderate — brand gold
    public static let recovery078 = Color(light: "#D2A23A", dark: "#F2CE6E") // primed — soft gold
    public static let recovery100 = Color(light: "#E0B44C", dark: "#FCEBA8") // peak — champagne (deepened on light)

    /// Ordered gradient stops for the recovery scale (Titanium gold ramp, or the Classic red→green).
    public static var recoveryStops: [Gradient.Stop] {
        isClassic ? cRecoveryStops : [
            .init(color: recovery000, location: 0.00),
            .init(color: recovery030, location: 0.30),
            .init(color: recovery055, location: 0.55),
            .init(color: recovery078, location: 0.78),
            .init(color: recovery100, location: 1.00),
        ]
    }

    /// The signature recovery gradient (bronze → champagne, or Classic red→green).
    public static var recoveryGradient: Gradient { Gradient(stops: recoveryStops) }

    // MARK: Strain / Effort ramp — the amber "Effort" colour world.
    // Deep ember → warm amber → bright amber → soft amber peak: heat/output, all in the
    // Effort accent family rather than veering into magenta.
    public static let strain000 = Color(light: "#7E460E", dark: "#9C5A14") // deep ember
    public static let strain033 = Color(light: "#A4621B", dark: "#C2762A") // warm amber
    public static let strain066 = Color(light: "#C2792E", dark: "#D98A3D") // bright amber
    public static let strain100 = Color(light: "#D89240", dark: "#F0A85A") // soft amber peak

    public static var strainStops: [Gradient.Stop] {
        isClassic ? cStrainStops : [
            .init(color: strain000, location: 0.00),
            .init(color: strain033, location: 0.33),
            .init(color: strain066, location: 0.66),
            .init(color: strain100, location: 1.00),
        ]
    }

    /// The strain gradient (output / heat, or the Classic blue ramp).
    public static var strainGradient: Gradient { Gradient(stops: strainStops) }

    // MARK: Sleep stages — the blue "Rest" colour world (Titanium); Classic adds a purple REM.
    public static var sleepAwake: Color { isClassic ? cSleepAwake : Color(light: "#97A2B2", dark: "#C2CCDA") }
    public static var sleepLight: Color { isClassic ? cSleepLight : Color(light: "#3A80D6", dark: "#4A90E2") }
    public static var sleepDeep:  Color { isClassic ? cSleepDeep  : Color(light: "#234F9E", dark: "#2F6FCB") }
    public static var sleepREM:   Color { isClassic ? cSleepREM   : Color(light: "#5790DA", dark: "#6FA8E8") }

    // MARK: HR zones — Titanium cool→warm (no green), or the Classic grey→green→yellow→orange→red.
    public static var zone1: Color { isClassic ? cZone1 : Color(light: "#3A80D6", dark: "#4A90E2") }
    public static var zone2: Color { isClassic ? cZone2 : Color(light: "#2E92B4", dark: "#3FA9C9") }
    public static var zone3: Color { isClassic ? cZone3 : Color(light: "#C28E26", dark: "#E8B84B") }
    public static var zone4: Color { isClassic ? cZone4 : Color(light: "#C2792E", dark: "#D98A3D") }
    public static var zone5: Color { isClassic ? cZone5 : Color(light: "#C84E1E", dark: "#E0662F") }

    /// HR zones indexed 1...5; index 0 mirrors zone1 for convenience.
    public static var hrZones: [Color] { [zone1, zone1, zone2, zone3, zone4, zone5] }

    // MARK: Status — Titanium gold/amber/orange, or the Classic green/amber/red.
    public static var statusPositive: Color { isClassic ? Color(light: "#2E9E4F", dark: "#46B45A") : Color(light: "#B07D17", dark: "#E8B84B") }
    public static var statusWarning:  Color { isClassic ? Color(light: "#CFA528", dark: "#F2C53D") : Color(light: "#C2792E", dark: "#D98A3D") }
    public static var statusCritical: Color { isClassic ? Color(light: "#CB3A2F", dark: "#E5483B") : Color(light: "#C84E1E", dark: "#E0662F") }

    // MARK: Per-metric accents — HRV / SpO₂ / energy / risk. Classic leans the traditional hues (purple HRV, red risk).
    public static var metricCyan:   Color { isClassic ? Color(light: "#2E92B4", dark: "#3FA9C9") : Color(light: "#2E92B4", dark: "#3FA9C9") }
    public static var metricPurple: Color { isClassic ? Color(light: "#6A4FC0", dark: "#8E6FD6") : Color(light: "#3A80D6", dark: "#4A90E2") }
    public static var metricAmber:  Color { isClassic ? Color(light: "#CFA528", dark: "#F2C53D") : Color(light: "#C2792E", dark: "#D98A3D") }
    public static var metricRose:   Color { isClassic ? Color(light: "#CB3A2F", dark: "#E5483B") : Color(light: "#C84E1E", dark: "#E0662F") }

    // MARK: - Titanium & Gold domain "colour worlds" (NEW)
    //
    // Each daily score owns a two-stop accent gradient (deep → bright) plus a glow.
    // These drive the layered gauges, frosted-card tints and scenic heroes. Charge
    // owns the brand gold; Effort the amber ramp; Rest the blue scale.

    // Each domain's accent / glow follows the chart style: Titanium (gold/amber/blue) or Classic
    // (Charge=green, Effort=blue, Rest=indigo, Stress=amber) so card tints + gauge tips + glows match
    // the data scale. The gauge ARC itself samples the recovery/strain/stress STOPS above, so it goes
    // full red→green / blue / green→red in Classic regardless of these.

    /// Charge (recovery) — gold world / Classic green.
    public static var chargeColor: Color  { isClassic ? Color(light: "#2E9E4F", dark: "#46B45A") : Color(light: "#0FA968", dark: "#19E68C") }
    public static var chargeDeep: Color    { isClassic ? Color(light: "#207A3C", dark: "#2E9E4F") : Color(light: "#8F6212", dark: "#C8902F") }
    public static var chargeBright: Color  { isClassic ? Color(light: "#5FBE6E", dark: "#86D98E") : Color(light: "#E0B44C", dark: "#FCEBA8") }
    public static var chargeGlow: Color    { isClassic ? Color(light: "#2E9E4F", dark: "#46B45A") : Color(light: "#C8902F", dark: "#E8B84B") }
    /// Diagonal accent pair for the Charge card wash + gauge stroke (deep → bright).
    public static var chargeGradient: Gradient { Gradient(colors: [chargeDeep, chargeBright]) }

    /// Effort (strain) — amber world / Classic blue.
    public static var effortColor: Color   { isClassic ? Color(light: "#3A74C4", dark: "#4A90E2") : Color(light: "#0A6CD6", dark: "#3B9BE8") }
    public static var effortDeep: Color    { isClassic ? Color(light: "#284F9C", dark: "#2F6FCB") : Color(light: "#7E460E", dark: "#9C5A14") }
    public static var effortBright: Color  { isClassic ? Color(light: "#5E92D6", dark: "#7FB2E8") : Color(light: "#D89240", dark: "#F0A85A") }
    public static var effortGlow: Color    { isClassic ? Color(light: "#3A74C4", dark: "#4A90E2") : Color(light: "#B26A1C", dark: "#D98A3D") }
    public static var effortGradient: Gradient { Gradient(colors: [effortDeep, effortBright]) }

    /// Rest (sleep) — blue world / Classic indigo.
    public static var restColor: Color     { isClassic ? Color(light: "#3A80D6", dark: "#6FA8E8") : Color(light: "#5A68C8", dark: "#8E8CF0") }
    public static var restDeep: Color      { isClassic ? Color(light: "#203E73", dark: "#2A4C8F") : Color(light: "#234F9E", dark: "#2F6FCB") }
    public static var restBright: Color    { isClassic ? Color(light: "#6A4FC0", dark: "#8E6FD6") : Color(light: "#5790DA", dark: "#6FA8E8") }
    public static var restGlow: Color      { isClassic ? Color(light: "#3A80D6", dark: "#6FA8E8") : Color(light: "#3A80D6", dark: "#4A90E2") }
    public static var restGradient: Gradient { Gradient(colors: [restDeep, restBright]) }

    /// Stress — blue→gold→orange world / Classic green→amber→red.
    public static var stressColor: Color   { isClassic ? Color(light: "#CFA528", dark: "#F2C53D") : Color(light: "#B88421", dark: "#E8B84B") }
    public static var stressDeep: Color    { isClassic ? Color(light: "#2E9E4F", dark: "#46B45A") : Color(light: "#3A80D6", dark: "#4A90E2") }
    public static var stressBright: Color  { isClassic ? Color(light: "#CB3A2F", dark: "#E5483B") : Color(light: "#C84E1E", dark: "#E0662F") }
    public static var stressGlow: Color    { isClassic ? Color(light: "#CFA528", dark: "#F2C53D") : Color(light: "#B88421", dark: "#E8B84B") }
    /// 3-stop gauge ramp: calm → balanced → high.
    public static var stressGradient: Gradient { Gradient(colors: [stressDeep, stressColor, stressBright]) }

    // MARK: Scenic background (NEW) — detail-screen hero gradient + starfield.
    /// Radial canvas: lit center → deep edge. Used by `ScenicHeroBackground` (warm-lit on light).
    public static let scenicCenter     = Color(light: "#FBF6EA", dark: "#15243C")
    public static let scenicEdge       = Color(light: "#EDE6D6", dark: "#0A1322")
    /// Star tint for the scenic starfield (very faint on light; the hero suppresses stars there).
    public static let scenicStar       = Color(light: "#D8CDB6", dark: "#C8CFD8")

    /// Frosted-card tint endpoints (white→warm on light; the accent wash sits over them).
    public static let cardFillTop      = Color(light: "#FFFFFF", dark: "#15243C")
    public static let cardFillBottom   = Color(light: "#FAF7F0", dark: "#0B1424")

    // MARK: - Titanium & Gold core tokens (NEW)
    //
    // The brand gold ramp (buttons, ring fills, FAB, active chrome) and the neutral
    // titanium ramp (tiles, avatars, icon plates). Same names + hexes on Android so
    // Apple and Android match byte-for-byte.

    /// Brand gold — primary accent. Gold FILLS stay bright (dark text on them is legible in both schemes);
    /// only a hair deeper on light so the fill doesn't wash out against white.
    public static let gold          = Color(light: "#DBA52A", dark: "#E8B84B")
    /// Bright champagne — gold highlight / hover.
    public static let goldLight     = Color(light: "#ECC766", dark: "#FCEBA8")
    /// Deep bronze — gold shadow / low stop.
    public static let goldDeep      = Color(light: "#9A6B12", dark: "#C8902F")
    /// Near-black brown — text / icons placed ON gold surfaces (scheme-invariant; gold fills stay gold).
    public static let goldDeepText  = Color(hex: "#3A2708")
    /// The bright core dot at a gauge arc tip / sparkline head. White reads as a highlight on the dark
    /// canvas; on light it would vanish into the white card, so it flips to a deep ink that reads as a
    /// crisp centre on the (deepened) coloured tip bead.
    public static let tipCore       = Color(light: "#241B06", dark: "#FFFFFF")
    /// High-vis signal yellow — sparing emphasis (badges / alerts); deepened on light to stay visible.
    public static let signalYellow  = Color(light: "#E8A800", dark: "#FFD63D")
    /// 135–155° gold ramp for buttons, ring fills, FAB (light → gold → deep).
    public static let goldGradient  = Gradient(colors: [goldLight, gold, goldDeep])

    /// Brushed-titanium ramp (top highlight → mid body → low → deep) for tiles, avatars and icon plates.
    /// Shifted to a MID-grey ramp on light so brushed-metal tiles stay visible against white cards.
    public static let titaniumTop   = Color(light: "#DDE1E6", dark: "#F1F3F5")
    public static let titaniumMid   = Color(light: "#BBC2C9", dark: "#C9CFD4")
    public static let titaniumLow   = Color(light: "#98A0A8", dark: "#969DA4")
    public static let titaniumDeep  = Color(hex: "#6B737B")
    /// 150° titanium ramp for tiles / avatars / icon plates.
    public static let titaniumGradient = Gradient(colors: [titaniumTop, titaniumMid, titaniumLow, titaniumDeep])

    // MARK: - Sampling helpers

    /// Sample the recovery gradient (bronze → champagne) at a recovery score 0...100.
    /// Returns the exact interpolated color used everywhere recovery is tinted.
    public static func recoveryColor(_ score: Double) -> Color {
        sample(stops: recoveryStops, at: score / 100.0)
    }

    /// Sample the strain ("Effort") gradient at a value on NOOP's 0...100 Effort scale.
    public static func strainColor(_ strain: Double) -> Color {
        sample(stops: strainStops, at: strain / 100.0)
    }

    /// Effort tint sampled by a 0...1 fraction (e.g. value/scaleMax), spreading the full ember→amber
    /// ramp. Prefer this for gauge tips / value-tinted accents so a high Effort reads as bright amber
    /// rather than ember. `strainColor(_:)` stays for callers holding a 0...100 value.
    public static func effortTint(fraction: Double) -> Color {
        sample(stops: strainStops, at: min(max(fraction, 0), 1))
    }

    /// The state word for a recovery score, per spec §9.3.
    /// DEPLETED · LOW · MODERATE · PRIMED · PEAK
    public static func recoveryState(_ score: Double) -> String {
        switch score {
        case ..<25:  return "DEPLETED"
        case ..<50:  return "LOW"
        case ..<70:  return "MODERATE"
        case ..<88:  return "PRIMED"
        default:     return "PEAK"
        }
    }

    /// HR-zone color for a 0...5 zone index (clamped).
    public static func hrZoneColor(_ zone: Int) -> Color {
        let z = max(1, min(5, zone))
        return hrZones[z]
    }

    /// Color for a sleep stage by canonical name (awake/light/deep/rem).
    public static func sleepStageColor(_ stage: SleepStage) -> Color {
        switch stage {
        case .awake: return sleepAwake
        case .light: return sleepLight
        case .deep:  return sleepDeep
        case .rem:   return sleepREM
        }
    }

    // MARK: - Linear gradient stop interpolation

    /// Interpolate a set of gradient stops at a normalized position 0...1.
    /// Clamps out-of-range positions to the end stops.
    public static func sample(stops: [Gradient.Stop], at position: Double) -> Color {
        guard let first = stops.first else { return .clear }
        guard stops.count > 1 else { return first.color }
        let t = min(max(position, 0.0), 1.0)

        // Find the bracketing pair.
        var lower = stops[0]
        var upper = stops[stops.count - 1]
        for i in 0..<(stops.count - 1) {
            let a = stops[i]
            let b = stops[i + 1]
            if t >= a.location && t <= b.location {
                lower = a
                upper = b
                break
            }
        }
        let span = upper.location - lower.location
        let localT = span > 0 ? (t - lower.location) / span : 0
        return interpolate(lower.color, upper.color, localT)
    }

    /// Linear-interpolate two colors in sRGB space.
    static func interpolate(_ a: Color, _ b: Color, _ t: Double) -> Color {
        let ca = a.rgbaComponents
        let cb = b.rgbaComponents
        let tt = min(max(t, 0.0), 1.0)
        return Color(
            .sRGB,
            red:   ca.r + (cb.r - ca.r) * tt,
            green: ca.g + (cb.g - ca.g) * tt,
            blue:  ca.b + (cb.b - ca.b) * tt,
            opacity: ca.a + (cb.a - ca.a) * tt
        )
    }
}

// MARK: - Sleep stage enum (shared with Hypnogram)

public enum SleepStage: String, CaseIterable, Sendable {
    case awake
    case light
    case deep
    case rem

    /// Display label.
    public var label: String {
        switch self {
        case .awake: return "Awake"
        case .light: return "Light"
        case .deep:  return "Deep"
        case .rem:   return "REM"
        }
    }

    /// Vertical band order (top = awake, bottom = deep) for hypnogram layout.
    public var bandRank: Int {
        switch self {
        case .awake: return 0
        case .rem:   return 1
        case .light: return 2
        case .deep:  return 3
        }
    }
}

// MARK: - Color component extraction

extension Color {
    /// Resolve to sRGB RGBA components in 0...1. Works on macOS 13+ via platform color bridge.
    var rgbaComponents: (r: Double, g: Double, b: Double, a: Double) {
        #if canImport(AppKit)
        let ns = NSColor(self).usingColorSpace(.sRGB) ?? NSColor(self)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ns.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (Double(r), Double(g), Double(b), Double(a))
        #elseif canImport(UIKit)
        let ui = UIColor(self)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (Double(r), Double(g), Double(b), Double(a))
        #else
        return (0, 0, 0, 1)
        #endif
    }
}

#if DEBUG
#Preview("Palette") {
    ScrollView {
        VStack(alignment: .leading, spacing: 24) {
            swatchRow("Surfaces", [
                ("base", StrandPalette.surfaceBase),
                ("raised", StrandPalette.surfaceRaised),
                ("overlay", StrandPalette.surfaceOverlay),
                ("inset", StrandPalette.surfaceInset),
                ("hairline", StrandPalette.hairline),
                ("hairline.strong", StrandPalette.hairlineStrong),
            ])
            swatchRow("Text", [
                ("primary", StrandPalette.textPrimary),
                ("secondary", StrandPalette.textSecondary),
                ("tertiary", StrandPalette.textTertiary),
            ])
            swatchRow("Accent", [
                ("accent", StrandPalette.accent),
                ("hover", StrandPalette.accentHover),
                ("muted", StrandPalette.accentMuted),
            ])
            swatchRow("Gold", [
                ("gold", StrandPalette.gold),
                ("light", StrandPalette.goldLight),
                ("deep", StrandPalette.goldDeep),
                ("deepText", StrandPalette.goldDeepText),
                ("signal", StrandPalette.signalYellow),
            ])
            swatchRow("Titanium", [
                ("top", StrandPalette.titaniumTop),
                ("mid", StrandPalette.titaniumMid),
                ("low", StrandPalette.titaniumLow),
                ("deep", StrandPalette.titaniumDeep),
            ])
            VStack(alignment: .leading, spacing: 8) {
                Text("RECOVERY GRADIENT").font(.caption).foregroundStyle(StrandPalette.textTertiary)
                LinearGradient(gradient: StrandPalette.recoveryGradient, startPoint: .leading, endPoint: .trailing)
                    .frame(height: 36).clipShape(RoundedRectangle(cornerRadius: 8))
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("STRAIN RAMP").font(.caption).foregroundStyle(StrandPalette.textTertiary)
                LinearGradient(gradient: StrandPalette.strainGradient, startPoint: .leading, endPoint: .trailing)
                    .frame(height: 36).clipShape(RoundedRectangle(cornerRadius: 8))
            }
            swatchRow("Sleep stages", [
                ("awake", StrandPalette.sleepAwake),
                ("light", StrandPalette.sleepLight),
                ("deep", StrandPalette.sleepDeep),
                ("REM", StrandPalette.sleepREM),
            ])
            swatchRow("HR zones", [
                ("Z1", StrandPalette.zone1), ("Z2", StrandPalette.zone2),
                ("Z3", StrandPalette.zone3), ("Z4", StrandPalette.zone4),
                ("Z5", StrandPalette.zone5),
            ])
        }
        .padding(24)
    }
    .frame(width: 520, height: 760)
    .background(StrandPalette.surfaceBase)
    .preferredColorScheme(.dark)
}

@ViewBuilder
private func swatchRow(_ title: String, _ items: [(String, Color)]) -> some View {
    VStack(alignment: .leading, spacing: 8) {
        Text(title.uppercased())
            .font(.caption)
            .foregroundStyle(StrandPalette.textTertiary)
        HStack(spacing: 10) {
            ForEach(items, id: \.0) { name, color in
                VStack(spacing: 6) {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(color)
                        .frame(width: 64, height: 48)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(StrandPalette.hairline, lineWidth: 1))
                    Text(name).font(.system(size: 9)).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
    }
}
#endif
