import XCTest
@testable import WhoopProtocol

/// PPG-derived per-second HR from the WHOOP 5.0 v26 optical buffer (issue #156).
///
/// The estimator is windowed autocorrelation of the detrended 24 Hz waveform. These tests drive it with
/// SYNTHETIC signals (a clean pulse → known HR; white noise → nothing) so they are deterministic and
/// need no capture fixtures, plus the Streams decode-tolerance check for the new `ppg_hr` key.
final class PpgHrTests: XCTestCase {
    private let fs = PpgHr.sampleRateHz   // 24

    /// One second (`fs` samples) of a `bpm`-Hz sine, ADC-count amplitude, at sample-phase offset
    /// `startSample` so consecutive seconds form a continuous waveform.
    private func sineSecond(bpm: Double, startSample: Int, amp: Double = 1000) -> [Int] {
        let f = bpm / 60.0
        return (0..<fs).map { i in
            Int(amp * sin(2 * Double.pi * f * Double(startSample + i) / Double(fs)))
        }
    }

    /// 30 consecutive 1 s records of a clean `bpm` pulse, ts starting at `base`.
    private func sineRecords(bpm: Double, seconds: Int = 30, base: Int = 1_780_000_000)
        -> [(ts: Int, samples: [Int])] {
        (0..<seconds).map { s in
            (ts: base + s, samples: sineSecond(bpm: bpm, startSample: s * fs))
        }
    }

    func testEstimateRecoversKnownHr() throws {
        // 8 s window of a clean 70 bpm pulse → ~70 bpm at high confidence.
        var sig = [Int]()
        for s in 0..<8 { sig.append(contentsOf: sineSecond(bpm: 70, startSample: s * fs)) }
        let est = try XCTUnwrap(PpgHr.estimate(sig))
        XCTAssertEqual(est.bpm, 70, accuracy: 2.0)
        XCTAssertGreaterThan(est.conf, 0.5)
    }

    func testDeriveSineSeriesIsAround70() {
        let series = PpgHr.derivePpgHr(records: sineRecords(bpm: 70))
        XCTAssertFalse(series.isEmpty)
        // Every second's estimate lands at 70±2 with confidence over 0.5.
        for s in series {
            XCTAssertEqual(s.bpm, 70, accuracy: 2)
            XCTAssertGreaterThan(s.conf, 0.5)
        }
        // Ascending, one per estimable second.
        XCTAssertEqual(series.map(\.ts), series.map(\.ts).sorted())
    }

    func testWhiteNoiseProducesNoEstimates() {
        // Deterministic LCG so the test never flakes — a flat, non-pulsatile signal must not yield HR.
        var state: UInt64 = 0x9E3779B97F4A7C15
        func next() -> Int {
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return Int(Int32(truncatingIfNeeded: state >> 33)) % 1000
        }
        let records: [(ts: Int, samples: [Int])] = (0..<30).map { s in
            (ts: 1_780_000_000 + s, samples: (0..<fs).map { _ in next() })
        }
        let series = PpgHr.derivePpgHr(records: records)
        XCTAssertTrue(series.isEmpty, "white noise must not fabricate an HR (got \(series.count))")
    }

    func testEstimateRejectsTooShortWindow() {
        // < 3 s of samples → nil (can't resolve a low HR).
        XCTAssertNil(PpgHr.estimate(sineSecond(bpm: 70, startSample: 0)))   // 1 s only
    }

    func testGapBreaksRunsButBothSidesEstimate() {
        // Two 5 s runs of 60 bpm separated by a 100 s gap — both runs produce estimates, the gap none.
        var recs = sineRecords(bpm: 60, seconds: 5, base: 1_780_000_000)
        recs += sineRecords(bpm: 60, seconds: 5, base: 1_780_000_200)
        let series = PpgHr.derivePpgHr(records: recs)
        XCTAssertFalse(series.isEmpty)
        XCTAssertTrue(series.allSatisfy { abs($0.bpm - 60) <= 2 })
        // No estimate lands inside the gap.
        XCTAssertFalse(series.contains { $0.ts > 1_780_000_005 && $0.ts < 1_780_000_200 })
    }

    /// Streams decode tolerance: a JSON missing `ppg_hr` still decodes (defaults to empty), and a
    /// present `ppg_hr` round-trips. Mirrors the decodeIfPresent guard for the other biometric keys.
    func testStreamsDecodeToleratesMissingAndPresentPpgHr() throws {
        let dec = JSONDecoder()
        // Missing key → empty.
        let s1 = try dec.decode(Streams.self, from: Data(#"{"hr":[]}"#.utf8))
        XCTAssertTrue(s1.ppgHr.isEmpty)
        // Present key → decoded under the snake_case CodingKey.
        let json = #"{"ppg_hr":[{"ts":1780000000,"bpm":70.0,"conf":0.91}]}"#
        let s2 = try dec.decode(Streams.self, from: Data(json.utf8))
        XCTAssertEqual(s2.ppgHr, [PpgHrSample(ts: 1_780_000_000, bpm: 70, conf: 0.91)])
        // Round-trip encode → decode is identity.
        let round = try dec.decode(Streams.self, from: JSONEncoder().encode(s2))
        XCTAssertEqual(round.ppgHr, s2.ppgHr)
    }
}
