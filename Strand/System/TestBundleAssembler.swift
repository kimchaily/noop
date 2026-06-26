import Foundation
import StrandAnalytics

/// Assembles the Test Centre export bundle: gathers report.txt, meta.json, raw-capture and last-crash,
/// runs the redaction pass over EVERY file, applies the 20 MB cap, and hands the entries to
/// FileExport.exportBundle. This is the orchestrator behind the Report button.
///
/// The CRITICAL fix (spec section 5.3): today only the append(log:) sink scrubs (LiveState.swift:308),
/// so a serial embedded in raw-capture console text would ship unredacted. We re-run LiveState.redactPii
/// over every entry's text here, the single scrub point, and stamp meta.redaction = "v2" so a maintainer
/// can trust the scrub. Redaction stays the only scrub point; we just guarantee it covers the whole bundle.
enum TestBundleAssembler {

    /// The redaction stamp written into meta.json so a maintainer knows the whole-bundle scrub ran.
    static let redactionVersion = "v2"

    /// Re-run the redaction sink over every entry. Text entries are decoded as UTF-8, scrubbed via the same
    /// LiveState.redactPii used by the live sink, and re-encoded. A non-UTF-8 entry (none today) passes
    /// through untouched rather than risk corrupting binary. meta.json and report.txt have no PII shapes so
    /// they pass through byte-identical; raw-capture is where the embedded serials live.
    static func redactEntries(_ entries: [FileExport.BundleEntry]) -> [FileExport.BundleEntry] {
        entries.map { entry in
            guard let text = String(data: entry.data, encoding: .utf8) else { return entry }
            let scrubbed = LiveState.redactPii(text)
            return FileExport.BundleEntry(name: entry.name, data: Data(scrubbed.utf8))
        }
    }

    /// Hard cap the bundle at `capBytes` (20 MB default, under GitHub's 25 MB; spec section 5.4). The
    /// strap-log tail is already bounded, so only raw-capture can exceed. We keep the MOST-RECENT tail of
    /// raw-capture.jsonl (newest data is the most diagnostic) and trim from the front. Returns the capped
    /// entries plus whether any truncation happened, which the caller writes to meta.truncated.
    static func capEntries(_ entries: [FileExport.BundleEntry],
                           capBytes: Int = 20 * 1024 * 1024) -> (entries: [FileExport.BundleEntry], truncated: Bool) {
        let total = entries.reduce(0) { $0 + $1.data.count }
        guard total > capBytes else { return (entries, false) }
        // Budget for everything that is NOT raw-capture (kept whole), then give raw-capture the remainder.
        let rawName = "raw-capture.jsonl"
        let nonRaw = entries.filter { $0.name != rawName }.reduce(0) { $0 + $1.data.count }
        let budget = max(0, capBytes - nonRaw)
        var truncated = false
        let capped = entries.map { entry -> FileExport.BundleEntry in
            guard entry.name == rawName, entry.data.count > budget else { return entry }
            truncated = true
            // Keep the tail (most recent): the last `budget` bytes.
            let tail = entry.data.suffix(budget)
            return FileExport.BundleEntry(name: entry.name, data: Data(tail))
        }
        return (capped, truncated)
    }

    // MARK: - assemble (the entrypoint behind the Report button, the Group D integration seam)

    /// The build channel string for meta.json. Sideloaded iOS reads "sideload" with `signed=false`; an
    /// App Store / TestFlight iOS install reads "App Store"; macOS and Android are fixed per flavour. We
    /// never fabricate: the iOS read derives from IOSDiagnostics.isSideloaded.
    private static func buildProvenance() -> TestBundleMeta.Build {
        #if os(iOS)
        let sideloaded = IOSDiagnostics.capture().isSideloaded ?? true
        return TestBundleMeta.Build(channel: sideloaded ? "sideload" : "App Store", signed: !sideloaded)
        #elseif os(macOS)
        // The macOS build ships unsigned/un-notarized for anonymity (it's distributed via GitHub / brew).
        return TestBundleMeta.Build(channel: "GitHub", signed: false)
        #else
        return TestBundleMeta.Build(channel: "GitHub", signed: false)
        #endif
    }

    /// Gather the report files for `profile`, redact EVERY file, cap the bundle, then build and append
    /// meta.json (carrying the truncated flag from the cap) and redact-pass it too. Returns the final,
    /// already-redacted + already-capped entries ready for FileExport.exportBundle.
    ///
    /// Group B left this entrypoint to Group D (the Test Centre IA) on purpose: it is the one place that
    /// reaches into the live app (LiveState, TestCentre, the diagnostics) to gather the actual bytes, so
    /// it lives with the screen that binds the Report button. The pure primitives (redactEntries,
    /// capEntries) stay where Group B shipped them; this just composes them in the canonical order.
    @MainActor
    static func assemble(profile: TestDomain, live: LiveState) -> [FileExport.BundleEntry] {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        #if os(iOS)
        let platform = "iOS"
        #else
        let platform = "macOS"
        #endif

        // 1. report.txt: the same exportable strap log the strap-log card shares. Already redacted by the
        //    append(log:) sink, but the whole-bundle redactEntries pass below re-scrubs it anyway (5.3).
        var entries: [FileExport.BundleEntry] = [
            FileExport.BundleEntry(name: "report.txt", data: Data(live.exportableLogText().utf8)),
        ]

        // 2. Redact the gathered files, then cap (only raw-capture can exceed; report.txt is bounded).
        let redacted = redactEntries(entries)
        let (capped, truncated) = capEntries(redacted)
        entries = capped

        // 3. meta.json: the machine-readable tie. The questionnaire answers are whatever the tester saved
        //    for this profile; profileStartedAt is ISO8601 from TestCentre. Storage is left zeroed in
        //    Phase 1 (the DB-size probe is a later wire-up); we never fabricate a number we cannot read.
        let started = TestCentre.startedAt(profile).map { ISO8601DateFormatter().string(from: $0) }
        let meta = TestBundleMeta(
            schema: 1,
            appVersion: version,
            platform: platform,
            osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            strapModel: nil,
            source: ["Live Bluetooth"],
            testProfile: profile.id,
            profileStartedAt: started,
            questionnaire: TestCentre.answers(profile),
            build: buildProvenance(),
            storage: TestBundleMeta.Storage(dbBytes: 0, rows: [:], rawCaptureBytes: 0),
            redaction: redactionVersion,
            truncated: truncated)

        // meta.json has no PII shapes, so the redact pass leaves it byte-identical, but we route it through
        // the same sink so the whole bundle is guaranteed to have passed one scrub point (5.3).
        entries += redactEntries([FileExport.BundleEntry(name: "meta.json", data: meta.encoded())])
        return entries
    }
}
