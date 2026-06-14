#if os(iOS)
import Foundation
import ActivityKit

/// Starts, updates, and ends the live-HR Live Activity. The activity appears on the Lock Screen and
/// in the Dynamic Island while the strap is bonded and streaming heart rate.
@MainActor
final class LiveActivityController {
    private var activity: Activity<NOOPActivityAttributes>?
    private var lastPush: Date = .distantPast
    /// Cached `ActivityAuthorizationInfo` — `update` runs at ~1 Hz off the live HR stream, and
    /// instantiating this system bridge per tick is needless allocation. ActivityKit's auth status
    /// only changes via Settings, so caching for the controller's lifetime is safe.
    private let authInfo = ActivityAuthorizationInfo()
    /// Synchronous gate against concurrent `Activity.request` calls. The `else` branch below is
    /// re-entered while the first request is still in flight (it hasn't assigned `self.activity`
    /// yet), so without this guard two close-together HR samples could both fire `Activity.request`
    /// and create duplicate Live Activities.
    private var isStarting = false

    /// Drive the activity from the latest live values. Lazily starts when the strap is bonded and a
    /// heart rate is present; ends when the strap goes offline. Throttled to ~once every 2s so we
    /// stay well under the Live Activity update budget.
    func update(bpm: Int?, recovery: Int?, bonded: Bool) {
        guard authInfo.areActivitiesEnabled else { return }

        // Re-adopt an activity that outlived a previous app session. ActivityKit keeps Live Activities
        // alive across launches/relaunches, but a fresh controller starts with `activity == nil`, so
        // without recovering the handle here we can neither update nor END an already-showing activity
        // — which made the #336 opt-out a no-op (#341: toggle off, heart stays) and risked spawning a
        // duplicate on the start path below. Done on the HR tick rather than in `init` because
        // `Activity.activities` isn't reliably hydrated at the instant of process launch.
        if activity == nil { activity = Activity<NOOPActivityAttributes>.activities.first }

        // User opt-out (#336): if the in-app toggle is off, never start — and end any activity that's
        // already showing (the user just turned it off; this fires on the next ~1 Hz HR tick).
        guard UnitPrefs.liveActivityEnabled() else {
            if activity != nil { Task { await end() } }
            return
        }

        if !bonded {
            Task { await end() }
            return
        }
        guard bpm != nil else { return }

        let state = NOOPActivityAttributes.ContentState(bpm: bpm, recovery: recovery, bonded: bonded)

        if let activity {
            guard Date().timeIntervalSince(lastPush) > 2 else { return }
            lastPush = Date()
            Task { await activity.update(ActivityContent(state: state, staleDate: nil)) }
        } else {
            // Set the start gate SYNCHRONOUSLY before any await so a second `update` arriving on the
            // main actor while `Activity.request` is still in flight bails here instead of issuing a
            // second request. The 2-second throttle above only guards the update path.
            guard !isStarting else { return }
            isStarting = true
            do {
                activity = try Activity.request(
                    attributes: NOOPActivityAttributes(title: "Live HR"),
                    content: ActivityContent(state: state, staleDate: nil),
                    pushType: nil
                )
                lastPush = Date()
            } catch {
                activity = nil
            }
            isStarting = false
        }
    }

    func end() async {
        // End every NOOP Live Activity, not just our cached handle — covers a straggler from a prior
        // session we never re-adopted (#341) and any rare duplicate. Iterating the live list is the
        // only way to reach activities this controller instance never started.
        for act in Activity<NOOPActivityAttributes>.activities {
            await act.end(nil, dismissalPolicy: .immediate)
        }
        self.activity = nil
    }
}
#endif
