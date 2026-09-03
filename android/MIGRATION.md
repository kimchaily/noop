# Migrating to your own Choop build (data + updates)

You have the original **NOOP v8.2.2** app (from the now-deleted `NoopApp/noop` repo) installed on
your phone, and you want to move to a build **you** can keep updating — without losing the history
you've already collected. This fork ships as **Choop** (`com.kimchai.choop`), signed with your own
key, so from here on every future version installs as a normal in-place update.

## The one hard constraint: the signing key

Android only lets an APK **update over** an installed app when both are signed with the **same key**.
Your installed app is signed with the original maintainer's key, which is gone — so the switch to a
build you control costs a **one-time uninstall + reinstall**. And the app sets
`android:allowBackup="false"`, so **uninstalling wipes its local database**. That's why you export
your data *first*.

Good news: the app has a lossless, built-in **`.noopbak` export/restore**, and the restore validates
only that the file is a Choop/NOOP Room database (`room_master_table`) — **not** the signature or the
package name. So a backup taken from the original `com.noop.whoop` install imports cleanly into
`com.kimchai.choop` (same v8.2.2 source ⇒ identical DB schema). You lose nothing.

You pay the uninstall/reinstall **once**. Every Choop-to-Choop update afterwards is in-place.

---

## Do it in this order

### 1. Export your data from the app you have now — before anything else
In the installed app: **Settings → "Backup & restore" → "Export…"**. It writes
`noop-backup-<date>.noopbak` (a ZIP of the whole SQLite DB + your profile/display settings) to a
folder you pick.
- Move that file **off the app's sandbox**: to Downloads, then copy it to Google Drive / your PC /
  an SD card. Verify it's really there (it opens as a ZIP containing `noop-backup.sqlite`).
- **Second copy for safety:** also do the CSV export (Settings → Data sources → Export CSV). The
  `.noopbak` is the lossless restore path; CSV is a fallback and marks on-device-computed rows as
  APPROXIMATE.

> Do not skip or reorder this. Once you uninstall, un-exported data is unrecoverable.

### 2. Create your signing key — once
Run the helper (needs a JDK/`keytool`, which you already have if you build the app):

```bash
./android/tools/make-keystore.sh
```

It writes `choop-release.jks`, then prints (a) a `keystore.properties` block for local release
builds and (b) the four values to paste into GitHub Actions secrets. **Back up the `.jks` and its
password** in a password manager — lose them and you can never ship an in-place update again. Never
commit them (`*.jks` and `keystore.properties` are already git-ignored).

### 3. Give CI your key, so the pipeline signs with it
In **GitHub → repo → Settings → Secrets and variables → Actions**, add:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the one-line base64 the script printed |
| `ANDROID_KEYSTORE_PASSWORD` | your keystore password |
| `ANDROID_KEY_ALIAS` | `choop` |
| `ANDROID_KEY_PASSWORD` | your keystore password (same, unless you set a separate key password) |

With these set, the **Android Release APK** workflow signs `Choop-v<version>.apk` with *your* key. Without
them it falls back to the shared debug key — fine for a throwaway test, but **not** what you install
and keep, or you'd have to uninstall/reinstall again when you later switch to the real key.

### 4. Build the release APK with your key
GitHub → **Actions → "Android Release APK" → Run workflow**. When it's green, download the
`noop-android-apk` artifact and unzip it to get `Choop-v<version>.apk` (the Choop `full` release).
*(Or push a version tag like `v8.2.3` and the pipeline attaches the APK straight to that Release.)*

### 5. Switch phones over
- **Uninstall** the old NOOP app (your data is already exported in step 1).
- **Install** `Choop-v<version>.apk` — you'll appear as **Choop** on the home screen, installing alongside
  nothing else (new applicationId, so even the old app's leftovers don't collide). Enable "install
  from unknown sources" for your file manager if prompted.
- From now on, dropping a newer Choop APK on top updates **in place** — data intact, no uninstall.

### 6. Restore your data — at the START of setup
Open Choop. The **second step of onboarding** ("Coming from another phone?") offers **Restore a backup
(.noopbak)**; use it there, before pairing a strap or entering a profile. It validates the file (SQLite
header, Room origin, `PRAGMA quick_check`) and swaps in your database, then asks you to **fully close
and reopen** the app; the relaunch resumes at *pair your strap*. Your entire history — and your setup
— is back.

(Already past onboarding? The same restore lives in **Settings → "Backup & restore" → Import**.)

### 7. Re-grant what a fresh install can't carry over
See the full inventory below. The short version: **re-pair your strap**, re-grant Bluetooth /
Notifications / Health Connect / exact-alarm permissions, **re-enter your AI Coach API key**, re-pick
your Backup & Sync folder, and re-add the home-screen widget. Everything else comes back with the
backup.

---

## What a `.noopbak` carries (and the five things it can't)

A `.noopbak` is a ZIP with three entries, and between them they are the whole app:

| Entry | What's in it |
|---|---|
| `noop-backup.sqlite` | The database: every HR / HRV / SpO2 / skin-temp / step / respiratory / gravity sample, every sleep session and stage, workouts, daily metrics, the journal (answers *and* your renamed, regrouped, custom questions' history), lab markers, mood, hydration, naps, sleep marks, live sessions, Apple/Health-Connect rows, dismissed workouts and nights, **and the paired-device registry** — which strap owns which day. |
| `settings.json` | Everything you set that isn't a measurement: profile (age, sex, weight, height, waist, HR-max override), units and the Effort axis, theme + light/dark + chart style, the Today layout (Key Metrics grid, Your-cards dashboard, section order, dismissed cards), the journal catalog (renames, groups, numeric types, custom and hidden questions), **the Charge/HRV baseline anchor dates**, smart alarm, wind-down, wrist alerts (per-app buzz patterns, quiet hours, call alerts), move reminders, nap detection, cycle/illness/hydration toggles, caffeine log and cutoff, Wim Hof settings **and session history**, breathing/biofeedback settings, the experiment you're running, the Updates inbox, manual step calibration, and the experimental opt-ins. |
| `avatar.jpg` | Your profile photo, when you've set one. |

**The five things that genuinely cannot travel**, because they belong to one install on one phone
rather than to you:

1. **The Bluetooth pairing.** A bond is negotiated between a strap and a phone; there is no format
   that exports one. Your straps and all their readings come across — you just bond once on the new
   phone, and it picks up exactly where the old one left off. (Moving to a *different* strap at the
   same time? Add it in Settings → Devices rather than just pairing it, so its readings stay separate
   from your old strap's.)
2. **The AI Coach API key** and the Oura install key. Both are sealed by the phone's hardware
   Keystore — they could not be decrypted elsewhere even if they were copied, and a backup file gets
   put in cloud folders and attached to bug reports, so they are deliberately never written into one.
3. **Android runtime permissions** (Bluetooth, notifications, Health Connect, exact alarms) and the
   **Backup & Sync folder**. A permission grant is the OS's record about an install; the new one asks
   for its own. Your retention setting survives, so you only re-pick the folder.
4. **Terms acceptance.** A consent record with a timestamp, made on a device by a person — so the new
   install asks once rather than inheriting someone else's answer.
5. **Home-screen widgets.** Placed by the launcher, not by Choop.

Deliberately left out because they'd be *wrong*, not because they couldn't be copied: sync cursors and
"already alerted" latches (restoring them suppresses the first real alert on the new phone), alarms
already scheduled with the old phone's AlarmManager, the analyze watermark and one-shot repair markers
(a restore re-scores your history on purpose), and the day-digest / analyze-journal caches, which are
recomputed work rather than data.

### The backup has to be written by a build that carries all this

A `.noopbak` can only restore what is inside it, and the settings/photo half only started being
written in the version that added this section. A backup exported by an **older** Choop contains the
database and the nine profile/display keys and genuinely nothing else — so restoring one brings back
your history, weight and units, and leaves the theme, Today layout and journal at their defaults.
That is the file, not the restore.

Choop says so rather than leaving you to guess: the restore reports **"Not in this backup"** and
names what was missing. When you see that, the fix is to update the **old** phone first, export a
fresh `.noopbak` from it, and restore that.

### Choosing what to bring across

The first-run restore lists what it can migrate as checkboxes — measurements & history, profile &
body, profile photo, appearance & units, Today layout & journal, alerts & reminders, and baselines &
everything else — all ticked. Untick anything you would rather keep as it is on this phone: taking a
history across without the old phone's alert rules is a normal thing to want, and so is taking a
setup across without the data (untick "Measurements & history" and the database is not touched at
all). A restore from Settings has no picker and always brings everything.

### Restore FIRST, before anything else

On a fresh install the restore is now the **second step of onboarding**, before Bluetooth, before your
profile, before any source import — because a restore *replaces* the store rather than adding to it.
Pair a strap or import a WHOOP export first and the restore overwrites what you just did. The other
imports (WHOOP export, Health Connect, Apple Health) stay later in the flow, where they belong: those
are additive.

A restore swaps the database file underneath the app, so it ends with "fully close and reopen Choop".
On that relaunch onboarding resumes at *pair your strap* — the one thing left — instead of starting
over. Expect the dashboard to fill in over a minute or two: a restore deliberately re-scores your
recent history in the background.

---

## About the strap history during the switch (read before you unpair)

Three facts about how NOOP/WHOOP handle history decide whether you lose anything:

1. **The strap records to its own memory whether or not any app is connected.** Turning your phone's
   Bluetooth off doesn't stop the strap recording — it only pauses live streaming. So a gap while
   you migrate is buffered on the strap and offloaded on the next history sync.
2. **History offload is consume-on-read.** When the connected app receives a history chunk it *acks*
   it, and the strap then **frees (deletes) that history** from its buffer (see `Backfiller` — "the
   strap frees acked history"). So a given slice of history can only ever be drained **once**, by
   **one** app. Whichever app syncs it first owns it; the other app can never pull it from the strap
   afterwards.
3. **A WHOOP 5.0/MG bonds to one app at a time.** You cannot have the old app and Choop both paired
   to the same strap simultaneously — you must unpair/forget it from the old app (and the official
   WHOOP app) before Choop can pair. Having both *installed* is fine; both *paired* is not.

**What this means for the move:**
- **Import replaces the whole database** (`DataBackup` swaps the SQLite file — it is not a row-merge).
  So always **import your `.noopbak` BEFORE you pair/sync Choop with the strap**. If you pair first
  and let Choop backfill, then import, the import wipes what Choop just pulled.
- **Stop the old app from stealing the gap.** Before you turn Bluetooth back on, **disable or
  uninstall the old app** (and forget the strap in it). Otherwise its background auto-reconnect can
  grab the strap and drain the buffered gap into the old app — after which Choop can't get it.
- With the old app out of the way, when Choop finally pairs it drains the buffered gap into Choop —
  **as long as it's still within the strap's on-device retention window**, which is limited (order of
  days). Don't let the migration sit for a week.

**Recommended, lossless sequence** (this is the safe version of "BT off → export → switch → backfill"):
1. Open the **old** app connected to the strap and let it finish one last history sync (drains the
   strap buffer into the old app's DB).
2. **Export** the `.noopbak` (+ a CSV copy) and move it off-device.
3. **Disable or uninstall** the old app and **forget** the strap in Bluetooth settings, so nothing
   can auto-reconnect and drain the strap.
4. Install **Choop**, then **import** the `.noopbak` (no pairing needed for import).
5. **Now** pair Choop with the strap. It offloads anything the strap buffered since step 1, forward
   from there.

> Your three-app-variant question, resolved: you can't pair both apps at once (fact 3), and you can't
> "backfill the same gap into both" (fact 2 — it's consumed once). The only way both apps end up with
> the same data is export→import, never dual streaming.

---

## Keeping it updated after the move
1. Make your changes; bump `versionCode` **and** `versionName` in `android/app/build.gradle.kts`.
2. **Push a version tag** (e.g. `git tag v8.2.3 && git push origin v8.2.3`). The **Android Release
   APK** workflow builds the APK, creates the GitHub **Release** for that tag, and attaches
   `Choop-v<version>.apk` to it. (Or run the workflow manually and grab the artifact — but a tagged Release
   is what powers in-app update discovery.)
3. In the app, **Settings → About → "Check for updates"** reads this repo's *latest* Release
   (`api.github.com/repos/kimchaily/noop/releases/latest`), and if it's newer than the installed
   `versionName` it links you to the Release page to download the new `Choop-v<version>.apk`. Install it
   over Choop — in-place, data preserved. (The check is manual-only; nothing is sent, nothing
   auto-updates.)

For an ongoing off-device safety net, turn on **Settings → "Backup & Sync"**: an opt-in daily
`.noopbak` written into a folder you choose (point it at a Drive/Dropbox sync folder). Nothing leaves
the phone except the file your own sync client uploads.

## Channels: main ⇒ Stable, any branch ⇒ Preview (enforced)

Edge-Canary style: next to stable **Choop** you install **"Choop Preview"**
(`com.kimchai.choop.preview`, the `preview` product flavor) to try a branch before it ships. Both
are signed with the **same key** (no second keystore/secret — the separate `applicationId` keeps
them apart), and each has its **own sandbox**: separate database, settings and permissions, so
nothing can mix.

**The channel is DERIVED FROM WHERE THE WORKFLOW RUNS — you never pick it — which is what makes
stable/preview mixing impossible:**

| You run *Android Release APK* on… | Channel | What you get |
|---|---|---|
| the **`main`** branch | **stable** | `mode: build` → a stable dry-run artifact; `mode: release-auto`/`release-manual` → the next **stable release** (bump + tag + GitHub Release) |
| **any other branch** | **preview** | only `mode: build` is allowed → a published **`vX.Y.Z.<run#>-pre` pre-release** with `Choop-Preview-vX.Y.Z.<run#>.apk` attached; a stable release mode is **rejected** ("stable releases are cut from main only") |
| a pushed tag `v<x>` | stable | releases that tag |
| a pushed tag `v<x>-pre` | preview | releases that tag as a pre-release |

### The everyday flow (exactly your four rules)
1. **Test a branch as preview:** push the branch → Actions → *Android Release APK* → **"Use workflow
   from": your branch** → **mode `build`** (channel is auto `preview`) → it publishes a pre-release →
   open **Choop Preview → Settings → About → Check for updates → Update**. It downloads and installs
   itself; no browser, no file manager.
2. **Like it → merge:** open a PR, merge to `main`.
3. **Cut the stable release:** Actions → *Android Release APK* → **"Use workflow from": `main`** →
   **mode `release-auto`** (channel is auto `stable`) → the next stable release.

Three things make branch previews land cleanly:
  - They are **published pre-releases**, not artifacts — that is what lets the app discover them.
    (An artifact needs a logged-in GitHub session to download, so the app could never fetch one.)
  - The version is `<highest published base>.<run number>`, so it is **always strictly newer** than
    the installed preview — even when the branch's own version lags `main` — while still sorting
    below the next stable patch (`8.2.29.1300` < `8.2.30`). `versionCode` is `1000 + run#` for the
    same reason, so Android never rejects the install as a downgrade.
  - The branch must **contain the channel work** (this section's commits) — a dispatch runs the
    *branch's* copy of the workflow, so for a branch cut before this, merge `main` into it first.
- **Update isolation, enforced on both ends:** stable's "Check for updates" reads `/releases/latest`,
  which GitHub keeps free of pre-releases — stable never sees a preview build. Choop Preview reads
  the full release list and keeps **only** entries flagged `prerelease` (`UpdateCheck.newestPreviewRelease`),
  so it can never be offered a stable build. And when it downloads, it takes only the
  `Choop-Preview-…apk` asset (`UpdateCheck.pickApk`) — a stable APK has a different applicationId and
  would install a *second* app rather than update Preview.
- **Old previews are pruned automatically:** the workflow keeps the newest 8 pre-releases and deletes
  older ones (stable releases are never touched).

### Updating from inside the app
"Check for updates" now downloads the channel-correct APK into the app's own cache and hands it to
Android's package installer. You confirm the install once (and, the first time, grant Choop "install
unknown apps"). Nothing installs on its own, nothing is sent, and the signature check still applies —
an APK signed with a different key is refused by Android, as it should be.

### Release notes
Every release's notes are generated by `android/tools/release-notes.py` from the PR (its title and the
`- bullet` lines in its body), falling back to commit subjects. The same text goes to three places, so
they can never disagree: the **Updates inbox** row (headline), **Settings → About → What's new** (full
bullets, compiled into the APK), and the **GitHub release body** — which is what the update card shows
you before you install.
- **The strap stays with STABLE.** History offload is consume-on-read and a 5.0/MG bonds to one app
  (see above) — if Preview drains the strap, stable can never get that slice. Feed Preview with a
  `.noopbak` **import** from stable instead; that covers UI/feature testing. Pair Preview only to
  deliberately test BLE changes, knowing that window's history lands in Preview.
- Don't enable **Health Connect writeback** in both apps at once, or each will import the other's
  contributed rows as an external source.

## Why "Choop" and not "com.noop.whoop"
The `applicationId` is just an install identifier — it is not a claim of authorship, and nothing
verifies the reverse-DNS name for a sideloaded APK. This fork uses `com.kimchai.choop` so it's
clearly *your* build with *your* signature, and so it could coexist with the original if you ever
reinstalled that. The code `namespace` stays `com.noop` (all sources are `package com.noop.*`), so
the rename is a two-line change with no source churn. The upstream project name "NOOP" is retained
in the licence, attribution, and disclaimer text, which document the code's origin and the WHOOP
trademark position — that lineage is unchanged.
