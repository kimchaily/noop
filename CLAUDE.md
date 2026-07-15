# Working in this repo (Choop — a personal fork of NoopApp/noop)

## Release & change-delivery flow — FOLLOW THIS EVERY TIME (including in a fresh session)

This fork ships an Android app in **two channels that install side by side**:

- **Stable** — `com.kimchai.choop`, updated from GitHub Releases tagged `vX.Y.Z`.
- **Preview** — `com.kimchai.choop.preview` ("Choop Preview"), updated from **pre-releases** tagged `vX.Y.Z-pre`.

**Never push code straight to `main`, and never cut a stable release before a preview has been approved by the user.** The flow is always:

1. **Branch.** Make every change on a feature branch (e.g. `claude/...`), never directly on `main`.
2. **Preview first — from the branch.** Cut a PREVIEW APK *from that branch*: Actions → **"Android Release APK"** (`.github/workflows/android-release.yml`), with `channel = preview` and `ref = <the branch>`.
   - Use `mode = release-manual` with an explicit `version`, or `mode = release-auto` with a `bump`.
   - The version **MUST be higher than the currently installed preview**, or the in-app "Check for updates" (which compares `versionName` numerically, newest pre-release wins) reports "up to date" and never offers it.
   - This publishes `vX.Y.Z-pre` (a pre-release) with `Choop-Preview-vX.Y.Z.apk` attached.
3. **User tests the preview.** The user installs it and feeds it their data via a `.noopbak` import (Settings → Backup & restore → Import). **Wait for the user's explicit OK.**
4. **Only then: merge + stable.** Once the user confirms the preview is good, merge the branch into `main` and cut a **stable** release (`channel = stable`, or push a `vX.Y.Z` tag — a tag WITHOUT `-pre` is the stable channel). Only now do stable users get it.

Do not skip step 3. **Previews come from branches; stable comes from `main`; `main` is only ever updated after a preview is approved.**

## Build / test notes

- A full Gradle build does **not** run in the Claude Code sandbox — the Android Gradle Plugin can't be fetched through the agent proxy. **The release workflow's `build-and-test` job is the compile + unit-test gate**; rely on a green CI run, not a local build. (Kotlin logic can still be reviewed and reasoned about locally, and pure functions verified by hand.)
- After a `.noopbak` import the app clears its analyze watermark and runs a **full background rescore** (sleep staging + recovery/strain scoring over ~3 weeks of 1 Hz data). Dashboard tiles can take **1–2 minutes** to populate after the mandated restart — this is a one-time cost, not a hang.

## Diagnose before shipping

When a user reports a metric showing "No data", don't ship speculative fixes. The `.noopbak` is a ZIP containing a SQLite DB (`noop-backup.sqlite`); ask the user to run read-only aggregate queries against it (device rows, per-stream counts + value ranges, sleep-session counts, daily-metric non-null counts) so the root cause is confirmed against real data first.
