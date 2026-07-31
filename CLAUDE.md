# Working in this repo (Choop — a personal fork of NoopApp/noop)

## Release & change-delivery flow — FOLLOW THIS EVERY TIME (including in a fresh session)

This fork ships an Android app in **two channels that install side by side**:

- **Stable** — `com.kimchai.choop`, updated from GitHub Releases tagged `vX.Y.Z`.
- **Preview** — `com.kimchai.choop.preview` ("Choop Preview"), updated from **pre-releases** tagged `vX.Y.Z-pre`.

**Never push code straight to `main`, and never cut a stable release before a preview has been approved by the user.** The flow is always:

1. **Branch.** Make every change on a feature branch (e.g. `claude/...`), never directly on `main`.
2. **Preview first — from the branch.** Actions → **"Android Release APK"** (`.github/workflows/android-release.yml`) → **"Use workflow from": the branch** → `mode = build`.
   - **The channel is derived from the ref — you never pick it.** Any non-`main` ref ⇒ preview; `main` ⇒ stable. A stable release mode on a branch is rejected.
   - A branch run **publishes a `vX.Y.Z.<run#>-pre` pre-release** with `Choop-Preview-vX.Y.Z.<run#>.apk` attached, so the user updates **from inside the app** (Settings → About → Check for updates → Update). No sideloading, no browser.
   - Versioning is automatic and always strictly newer: `<highest published base>.<run number>`. Never hand-pick a preview version.
3. **User tests the preview.** They update in-app (or import a `.noopbak` if it's a fresh install). **Wait for the user's explicit OK.**
4. **Only then: merge + stable.** Once the user confirms the preview is good, merge the branch into `main` and cut a **stable** release on `main` (`mode = release-auto`, or push a `vX.Y.Z` tag — a tag WITHOUT `-pre` is the stable channel). Only now do stable users get it.

**Release notes are generated, not hand-written.** `android/tools/release-notes.py` writes them into **both** `AppChangelog.kt` (compiled into the APK → the Updates inbox row and Settings → About → What's new) **and** the GitHub release body (→ what "Check for updates" shows), so the three surfaces can't drift.

The text comes from the PR: its **title** becomes the headline, and the `- **Lead-in.** detail` bullets under **`## What this PR does`** become the changelog verbatim — that section only, so the "How it was tested" bullets stay with reviewers instead of shipping to users. A preview cut reads the branch's **open** PR (never a merged one — branch names get reused); a stable cut on `main` reads the PR it just merged. With no PR at all it falls back to commit subjects since the last **stable** tag. Write that section as user-facing prose and it needs no further editing.

Do not skip step 3. **Previews come from branches; stable comes from `main`; `main` is only ever updated after a preview is approved.**

## Bringing `main` into a branch — rebase, never a merge commit

When a feature branch needs to pick up newer `main` — a merge-conflict notice, a "base branch recovered" notice, a stale branch before a preview cut, or just catching up — **rebase the branch onto `main`. Do not merge `main` into the branch.** Branch history stays linear and the diff stays exactly the branch's own work.

```
git fetch origin main
git rebase origin/main            # resolve conflicts, git rebase --continue
git push --force-with-lease -u origin <branch>
```

- **Always `--force-with-lease`**, never a bare `--force` — a rebase rewrites the branch's commits, so the push must be a forced update, and the lease is what stops it from clobbering someone else's push.
- `git pull` on a feature branch means `git pull --rebase`. Never let it create a merge commit.
- This applies to the `main` → branch direction only. Landing a finished branch **into** `main` is still a normal PR merge (step 4 of the release flow above) — don't rebase-push over `main`.
- Fall back to a merge only when a rebase genuinely can't work — e.g. the branch has already been merged somewhere, or is shared and being built on by someone else — and say explicitly why the merge was used.

## Build / test notes

- A full Gradle build does **not** run in the Claude Code sandbox — the Android Gradle Plugin can't be fetched through the agent proxy. Rely on a green CI run, not a local build. (Kotlin logic can still be reviewed and reasoned about locally, and pure functions verified by hand.)
- **Two different workflows — dispatch BOTH, they are not interchangeable:**
  - `.github/workflows/android.yml` → job `build-and-test` → `./gradlew testFullDebugUnitTest`. **This is the only unit-test gate.** It is also the only thing that COMPILES the test sourceset, so new/changed tests are unverified until this is green.
  - `.github/workflows/android-release.yml` → single job `release-apk` → `assembleFullRelease`. Compiles main code and produces the installable APK, but runs **no** tests and never compiles `src/test`. A green run here says nothing about tests.
- `android-release.yml` derives the channel from the branch and **rejects release modes on a branch** — from a feature branch the only valid `mode` is `apk-only`, which hands back a `Choop-Preview-v*.apk` artifact to sideload (no tag, no GitHub Release).
- After a `.noopbak` import the app clears its analyze watermark and runs a **full background rescore** (sleep staging + recovery/strain scoring over ~3 weeks of 1 Hz data). Dashboard tiles can take **1–2 minutes** to populate after the mandated restart — this is a one-time cost, not a hang.

## Diagnose before shipping

When a user reports a metric showing "No data", don't ship speculative fixes. The `.noopbak` is a ZIP containing a SQLite DB (`noop-backup.sqlite`); ask the user to run read-only aggregate queries against it (device rows, per-stream counts + value ranges, sleep-session counts, daily-metric non-null counts) so the root cause is confirmed against real data first.
