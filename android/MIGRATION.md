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

### 6. Restore your data
Open Choop → **Settings → "Backup & restore" → Import** → pick your `.noopbak`. It validates the
file (SQLite header, Room origin, `PRAGMA quick_check`) and swaps in your database, then asks you to
**fully close and reopen** the app. Your entire history is back.

### 7. Re-grant what a fresh install can't carry over
These are tied to the OS / the old signature and don't travel in a `.noopbak`:
- **Re-pair your WHOOP strap** and re-grant runtime permissions: Bluetooth, Notifications, and (if you
  use them) Health Connect and exact-alarm access.
- **Re-enter your AI Coach API key** — it lives in the Android Keystore, which is scoped to the
  app + signature, so it does not survive the reinstall.
- Re-add the **home-screen widget** and re-pick your app-icon variant if you'd changed it.

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
| the **`main`** branch | **stable** | `mode: apk-only` → a stable dry-run artifact; `mode: release-auto`/`release-manual` → the next **stable release** (bump + tag + GitHub Release) |
| **any other branch** | **preview** | only `mode: apk-only` is allowed → a `Choop-Preview-v<version>-<sha>.apk` **artifact**; a release mode is **rejected** ("releases are cut from main only") |
| a pushed tag `v<x>` | stable | releases that tag |
| a pushed tag `v<x>-pre` | preview | the one escape hatch to *publish* a preview release |

### The everyday flow (exactly your four rules)
1. **Test a branch as preview:** push the branch → Actions → *Android Release APK* → **"Use workflow
   from": your branch** → **mode `apk-only`** (channel is auto `preview`) → download the
   `Choop-Preview-…apk` artifact → sideload it over Choop Preview.
2. **Like it → merge:** open a PR, merge to `main`.
3. **Cut the stable release:** Actions → *Android Release APK* → **"Use workflow from": `main`** →
   **mode `release-auto`** (channel is auto `stable`) → the next stable release.

Two things make branch previews install cleanly:
  - Preview-channel builds carry `versionCode = 1000 + run#` — strictly increasing across all runs
    and branches — so a branch build always installs *over* any older Choop Preview, even when the
    branch's own version lags main (Android would otherwise refuse it as a downgrade).
  - The branch must **contain the channel work** (this section's commits) — a dispatch runs the
    *branch's* copy of the workflow, so for a branch cut before this, merge `main` into it first.
- **Update isolation:** stable's "Check for updates" reads `/releases/latest`, which GitHub keeps
  free of pre-releases — stable never sees a preview build. Choop Preview reads the full release
  list (including pre-releases). Note: **branch previews are artifacts, not published releases**, so
  you sideload them; Choop Preview only auto-updates from an explicitly published `-pre` release.
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
