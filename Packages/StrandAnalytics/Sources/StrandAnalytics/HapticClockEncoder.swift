import Foundation

// HapticClockEncoder.swift — turn a wall-clock time into a sequence of strap buzzes you can "read"
// on your wrist without looking (#460, @jiale1029). PURE + unit-tested; the BLE layer maps each
// `HapticPulse` onto the strap's actual haptic command and schedules the gaps. No I/O here.
//
// Encoding (designed to stay countable — you never count past ~9, and the groups are spaced so they
// can't blur together):
//   HOUR (12-hour):  tens → LONG buzzes (0 or 1), then units → SHORT buzzes.   e.g. 11 → L · S ; 3 → S·S·S
//   ── long GAP ──
//   MINUTE (0–59):   tens → MEDIUM buzzes (0–5), short pause, units → SHORT buzzes.  e.g. 47 → M·M·M·M · S×7
//
// Read it as: "[long buzzes = how many tens of hours] [short = hour units] … pause … [medium = tens of
// minutes] [short = minute units]". 11:47 → L, S,  (gap)  M,M,M,M,  (short gap)  S,S,S,S,S,S,S.

/// One element of a haptic-clock playback schedule.
public enum HapticPulse: Equatable, Sendable {
    /// A long buzz — marks tens of the hour (so you never count past 12).
    case long
    /// A medium buzz — marks tens of minutes (0–5).
    case medium
    /// A short buzz — marks a unit (hour units, or minute units).
    case short
    /// A long pause separating the hour group from the minute group.
    case groupGap
    /// A short pause separating the tens sub-group from the units sub-group.
    case unitGap
}

public enum HapticClockEncoder {

    /// Convert a 24-hour `hour` (0–23) + `minute` (0–59) into a buzz schedule. Out-of-range inputs are
    /// clamped/wrapped so the encoder never traps — it always produces a readable (if odd) sequence.
    public static func pulses(hour24: Int, minute: Int) -> [HapticPulse] {
        let h12 = twelveHour(hour24)
        let m = min(max(minute, 0), 59)

        var out: [HapticPulse] = []

        // Hour: tens (10/11/12 → one LONG) then units (SHORT).
        let hourTens = h12 / 10            // 0 or 1
        let hourUnits = h12 % 10           // 0–9 (note: 10 → tens 1, units 0)
        out += Array(repeating: .long, count: hourTens)
        out += Array(repeating: .short, count: hourUnits)

        out.append(.groupGap)

        // Minute: tens (MEDIUM, 0–5), short pause, units (SHORT, 0–9).
        let minTens = m / 10               // 0–5
        let minUnits = m % 10              // 0–9
        out += Array(repeating: .medium, count: minTens)
        out.append(.unitGap)
        out += Array(repeating: .short, count: minUnits)

        return out
    }

    /// Convenience: schedule for a `Date` in the given calendar/time zone (defaults to the current ones).
    public static func pulses(for date: Date, calendar: Calendar = .current) -> [HapticPulse] {
        let comps = calendar.dateComponents([.hour, .minute], from: date)
        return pulses(hour24: comps.hour ?? 0, minute: comps.minute ?? 0)
    }

    /// Map any 24-hour value onto a 1–12 clock face. 0/24 → 12, 13 → 1, etc.
    static func twelveHour(_ hour24: Int) -> Int {
        let h = ((hour24 % 12) + 12) % 12   // 0–11, safe for negatives
        return h == 0 ? 12 : h
    }
}
