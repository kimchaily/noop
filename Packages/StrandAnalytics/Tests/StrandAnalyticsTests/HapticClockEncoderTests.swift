import XCTest
@testable import StrandAnalytics

/// Pins the #460 "haptic clock" encoding: a wall-clock time → a countable buzz schedule. Pure value
/// logic, so no strap/BLE seam is needed. The BLE layer (separately) maps each pulse onto a real buzz.
final class HapticClockEncoderTests: XCTestCase {

    private func counts(_ p: [HapticPulse]) -> (long: Int, medium: Int, short: Int) {
        (p.filter { $0 == .long }.count,
         p.filter { $0 == .medium }.count,
         p.filter { $0 == .short }.count)
    }

    func test_11_47() {
        // 11:47 → hour 11 = 1 long + 1 short; minute 47 = 4 medium + 7 short.
        let p = HapticClockEncoder.pulses(hour24: 11, minute: 47)
        let c = counts(p)
        XCTAssertEqual(c.long, 1)
        XCTAssertEqual(c.medium, 4)
        XCTAssertEqual(c.short, 1 + 7)
        // Structure: hour group, then a groupGap, then the minute group with a unitGap inside it.
        XCTAssertEqual(p, [.long, .short, .groupGap, .medium, .medium, .medium, .medium, .unitGap,
                           .short, .short, .short, .short, .short, .short, .short])
    }

    func test_3_oclock_is_three_shorts_no_long() {
        // 3:00 → hour 3 = 0 long + 3 short; minute 0 = 0 medium + 0 short.
        let p = HapticClockEncoder.pulses(hour24: 15, minute: 0)  // 3 PM
        let c = counts(p)
        XCTAssertEqual(c.long, 0)
        XCTAssertEqual(c.short, 3)
        XCTAssertEqual(c.medium, 0)
        XCTAssertEqual(p, [.short, .short, .short, .groupGap, .unitGap])
    }

    func test_ten_oclock_is_one_long_zero_units() {
        // 10:00 → hour 10 = tens 1 (LONG), units 0.
        let p = HapticClockEncoder.pulses(hour24: 10, minute: 0)
        let c = counts(p)
        XCTAssertEqual(c.long, 1)
        XCTAssertEqual(c.short, 0)
    }

    func test_twelve_hour_wrap() {
        // 0:00 and 24/midnight map to 12; 12:00 noon stays 12; 13 → 1.
        XCTAssertEqual(HapticClockEncoder.twelveHour(0), 12)
        XCTAssertEqual(HapticClockEncoder.twelveHour(12), 12)
        XCTAssertEqual(HapticClockEncoder.twelveHour(13), 1)
        XCTAssertEqual(HapticClockEncoder.twelveHour(23), 11)
    }

    func test_midnight_12_05() {
        // 00:05 → 12:05 → hour 12 = 1 long + 2 short; minute 05 = 0 medium + 5 short.
        let c = counts(HapticClockEncoder.pulses(hour24: 0, minute: 5))
        XCTAssertEqual(c.long, 1)
        XCTAssertEqual(c.short, 2 + 5)
        XCTAssertEqual(c.medium, 0)
    }

    func test_out_of_range_is_clamped_not_trapped() {
        // Negative / overflow inputs must still produce a finite schedule (no crash, no infinite array).
        XCTAssertFalse(HapticClockEncoder.pulses(hour24: -1, minute: 75).isEmpty)
        XCTAssertFalse(HapticClockEncoder.pulses(hour24: 99, minute: -10).isEmpty)
        // Minute clamps to 0–59: 75 → 59 → 5 medium + 9 short.
        let c = counts(HapticClockEncoder.pulses(hour24: 6, minute: 75))
        XCTAssertEqual(c.medium, 5)
    }
}
