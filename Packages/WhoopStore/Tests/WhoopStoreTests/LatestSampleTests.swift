import XCTest
import WhoopProtocol
@testable import WhoopStore

final class LatestSampleTests: XCTestCase {
    func testLatestHRSampleTs() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.upsertDevice(id: "d", mac: nil, name: nil)
        // No rows yet → nil.
        let empty = try await store.latestHRSampleTs(deviceId: "d")
        XCTAssertNil(empty)
        // Insert HR rows at ts 100 and 250; latest = 250.
        let s = Streams(hr: [HRSample(ts: 100, bpm: 60), HRSample(ts: 250, bpm: 61)])
        _ = try await store.insert(s, deviceId: "d")
        let latest = try await store.latestHRSampleTs(deviceId: "d")
        XCTAssertEqual(latest, 250)
    }

    /// The stuck-strap watchdog frontier must advance on a PPG-only offload too (#156): a v26
    /// WHOOP 5 night with no measured HR still has a real data frontier from its PPG rows.
    func testLatestHRSampleTsIncludesPpgFallbackRows() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.upsertDevice(id: "d", mac: nil, name: nil)
        _ = try await store.insert(Streams(hr: [HRSample(ts: 100, bpm: 60)]), deviceId: "d")
        _ = try await store.insert(
            Streams(ppgHr: [PpgHrSample(ts: 300, bpm: 62, conf: 0.8)]), deviceId: "d")

        let latest = try await store.latestHRSampleTs(deviceId: "d")
        XCTAssertEqual(latest, 300)
    }
}
