# APK provenance — verifying the shipped NOOP release is built from this source

This note records a static reverse-engineering check confirming that the published
Android release **NOOP v8.2.2 (versionCode 261)** corresponds to the source in this
`android/` module — i.e. that development can safely continue from this repository.

A **byte-for-byte** reproduction is not expected (and not meaningful): the signing
signature and embedded build timestamps differ on every build, and the official APK is
signed with the maintainer's private key which is not in the repo. The check instead
verifies **structural and code identity**: same build toolchain, same app identity, same
manifest surface, same dependency set, and — because release builds ship **unminified**
(`isMinifyEnabled = false`, see `app/build.gradle.kts`) — the same real class names in the
DEX as in the Kotlin source.

Reference APK analysed: `NOOPv8.2.2.apk` (19 MB), decoded with `pyaxmlparser` for the
binary manifest and by extracting `Lcom/noop/…;` type descriptors from the four `classes*.dex`.

## 1. Build toolchain — matches exactly

| Fact | In the APK | In this repo | |
|---|---|---|---|
| Android Gradle Plugin | `8.5.2` (`META-INF/.../app-metadata.properties`) | `8.5.2` (`build.gradle.kts`) | ✅ |
| Kotlin | `1.9.24` (`kotlin-tooling-metadata.json`) | `1.9.24` (`build.gradle.kts`) | ✅ |
| Gradle | `8.7` (`kotlin-tooling-metadata.json`) | `8.7` (`gradle/wrapper/gradle-wrapper.properties`) | ✅ |
| Java source/target | `17` / `17` | `17` / `17` | ✅ |

## 2. App identity — matches exactly

| Fact | In the APK (binary manifest) | In this repo | |
|---|---|---|---|
| applicationId | `com.noop.whoop` | `com.noop.whoop` (`full` flavor) | ✅ |
| versionCode | `261` | `261` | ✅ |
| versionName | `8.2.2` | `8.2.2` | ✅ |
| minSdk / targetSdk | `26` / `34` | `26` / `34` | ✅ |
| App label / flavor | `NOOP` (full) | `full` → "NOOP" | ✅ |

The reference APK is the **`full`** flavour (the real app, `com.noop.whoop`), not the
`demo` flavour (`com.noop.whoop.demo`, label "NOOP Demo").

## 3. Manifest surface — matches exactly

Every first-party (`com.noop.*`) manifest component in the APK is declared in
`app/src/main/AndroidManifest.xml`, and all 43 permissions match:

- Activity: `com.noop.ui.MainActivity`
- Services: `com.noop.ble.WhoopConnectionService`, `com.noop.notif.NoopNotificationListener`
- Receivers: `com.noop.widget.NoopWidgetReceiver`, `com.noop.notif.PhoneCallReceiver`,
  `com.noop.alarm.SmartAlarmReceiver`, `SmartAlarmBootReceiver`, `WindDownReceiver`
- Provider: `androidx.core.content.FileProvider` (authority `${applicationId}.fileprovider`)

The remaining manifest entries in the APK (`androidx.glance.*`, `androidx.work.*`,
`androidx.room.MultiInstanceInvalidationService`, `androidx.health.platform.*`,
`androidx.profileinstaller.*`) and the `com.noop.whoop.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
are library-injected / AGP-generated for `targetSdk 34` — expected, not source drift.

## 4. Code — every shipped class exists in source

Because release builds are unminified, the DEX carries real class descriptors:

- **710** top-level `com.noop.*` classes in the DEX.
- **Every** `com.noop.*` package in the DEX exists in the source tree (`ai`, `alarm`,
  `analytics`, `ble`, `data`, `ingest`, `location`, `notif`, `oura`, `protocol`,
  `testcentre`, `ui`, `update`, `widget`).
- Only **4** DEX classes have no direct source declaration, and all four are
  **build-generated**, not missing code:
  - `com.noop.R` — resource class
  - `com.noop.data.WhoopDao_Impl`, `com.noop.data.WhoopDatabase_Impl` — Room (KSP) generated
  - `com.noop.ui.ComposableSingletons` — Compose compiler generated

No application class in the shipped APK is absent from this repository.

## 5. Dependencies — versions match exactly

Read from the APK's `META-INF/*.version` files and the okhttp UA string in the DEX,
compared to `app/build.gradle.kts`:

| Dependency | APK | Repo |
|---|---|---|
| androidx.core:core | 1.13.1 | 1.13.1 |
| androidx.activity:activity-compose | 1.9.0 | 1.9.0 |
| androidx.lifecycle:*-runtime-ktx | 2.8.2 | 2.8.2 |
| androidx.navigation:navigation-compose | 2.7.7 | 2.7.7 |
| androidx.room:room-runtime | 2.6.1 | 2.6.1 |
| androidx.glance:glance-appwidget | 1.1.1 | 1.1.1 |
| androidx.work:work-runtime-ktx | 2.9.0 | 2.9.0 |
| androidx.health.connect:connect-client | 1.1.0-alpha07 | 1.1.0-alpha07 |
| androidx.security:security-crypto | 1.1.0-alpha06 | 1.1.0-alpha06 |
| compose material3 / ui | 1.2.1 / 1.6.8 | via compose-bom 2024.06.00 |
| kotlinx-coroutines | 1.8.1 | 1.8.1 |
| com.squareup.okhttp3:okhttp | 4.12.0 | 4.12.0 |

The Compose `material3 1.2.1` and `ui 1.6.8` in the APK are exactly what the pinned
`androidx.compose:compose-bom:2024.06.00` resolves to.

## Conclusion

Toolchain, app identity, manifest, first-party code, and dependency set all match. The
published **NOOP v8.2.2 (261)** APK is built from this `android/` source; continuing
development from here reproduces the shipped app. Produce a fresh APK from CI with the
**Android Release APK** workflow (`.github/workflows/android-release.yml`, run via
*Actions → Run workflow*), which uploads `NOOP-full.apk` as a build artifact.
