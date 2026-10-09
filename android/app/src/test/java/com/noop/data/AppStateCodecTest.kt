package com.noop.data

import com.noop.data.AppStateCodec.MigrationGroup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The whole-app-state half of a `.noopbak`: the `stores` block that turns a restore from "your rows
 * came back" into an actual device migration.
 *
 * These tests pin the two things that would silently ruin one:
 *  - the TYPE a preference is restored as (SharedPreferences is typed, JSON is not — an Int written
 *    back as a Long throws `ClassCastException` the first time the app reads it); and
 *  - the exclusion policy, in BOTH directions, so a key that must not travel can neither be written
 *    into a backup here nor pushed onto this device by a backup written somewhere else.
 *
 * Plain JVM (real org.json + java.util.zip, no Robolectric); the SharedPreferences bridge needs a
 * Context and is exercised by the live restore path on-device.
 */
class AppStateCodecTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── Round trip: every preference type survives with its type intact ──────────

    @Test fun everyPreferenceTypeRoundTripsAsItsOwnType() {
        val stores = mapOf(
            "noop_prefs" to mapOf<String, Any>(
                "noop.showSupport" to true,
                "noop.smartAlarmMinutes" to 30,
                "noop.hrvBaselineEpoch" to 1_750_000_000L,
                "wimhof.trackVolume" to 0.8f,
                "today.keyMetrics" to "charge,effort,rest",
                "noop.settings.collapsedSections" to setOf("Appearance", "Strap"),
            ),
        )
        val encoded = requireNotNull(AppStateCodec.encodeStores(stores))
        val back = AppStateCodec.decodeStores(JSONObject(encoded.toString()))["noop_prefs"]!!

        assertEquals(true, back["noop.showSupport"])
        assertEquals(30, back["noop.smartAlarmMinutes"])
        assertEquals(1_750_000_000L, back["noop.hrvBaselineEpoch"])
        // Via JSON a Float becomes a Double and back; 0.8f survives that exactly, so no delta.
        assertEquals(0.8f, back["wimhof.trackVolume"] as Float, 0.0f)
        assertEquals("charge,effort,rest", back["today.keyMetrics"])
        assertEquals(setOf("Appearance", "Strap"), back["noop.settings.collapsedSections"])
        assertEquals(stores["noop_prefs"]!!.size, back.size)
    }

    @Test fun intAndLongStayDistinctAcrossTheWire() {
        // Both are the bare JSON number `30`, so only the type bucket can tell them apart. Getting
        // this wrong throws ClassCastException on the first getInt/getLong after a restore.
        //
        // The `<String, Any>` is load-bearing: without it Kotlin unifies the literal `30` to Long to
        // give the map a common value type, so BOTH values reach the codec boxed as Longs and this
        // test silently stops testing anything.
        val encoded = requireNotNull(
            AppStateCodec.encodeStores(
                mapOf("noop_prefs" to mapOf<String, Any>("an.int" to 30, "a.long" to 30L)),
            ),
        )
        val back = AppStateCodec.decodeStores(encoded)["noop_prefs"]!!
        assertTrue("An Int must come back an Int", back["an.int"] is Int)
        assertTrue("A Long must come back a Long", back["a.long"] is Long)
    }

    @Test fun booleansAreNeverCoercedIntoNumbers() {
        val hand = JSONObject("""{"noop_prefs":{"i":{"noop.smartAlarmMinutes":true},"b":{"noop.showSupport":1}}}""")
        val back = AppStateCodec.decodeStores(hand)["noop_prefs"].orEmpty()
        assertNull("JSON true must never become an int", back["noop.smartAlarmMinutes"])
        assertNull("JSON 1 must never become a boolean", back["noop.showSupport"])
    }

    @Test fun unsupportedValueTypesAreDroppedNotGuessedAt() {
        val encoded = AppStateCodec.encodeStores(
            mapOf(
                "noop_prefs" to mapOf(
                    "good.key" to "kept",
                    "bad.double" to 1.5,          // prefs cannot hold a Double
                    "bad.null" to null,
                    "bad.set" to setOf(1, 2),     // a prefs string-set holds only strings
                ),
            ),
        )
        val back = AppStateCodec.decodeStores(encoded)["noop_prefs"]!!
        assertEquals(mapOf<String, Any>("good.key" to "kept"), back)
    }

    // ── Exclusion policy ────────────────────────────────────────────────────────

    @Test fun secretsAndDeviceBoundStateNeverEnterABackup() {
        val encoded = AppStateCodec.encodeStores(
            mapOf(
                "noop_prefs" to mapOf<String, Any>(
                    "noop.lastDeviceAddress" to "AA:BB:CC:DD:EE:FF",
                    "noop.lastDeviceModel" to "WHOOP5",
                    "noop.acceptedTermsVersion" to "3",
                    "noop.analyzeWatermark" to "9001:1750000000",
                    "noop.batteryLowAlerted" to true,
                    "wimhof.trackUri" to "content://media/external/audio/media/42",
                    "biofeedback.stOnsetLastFire" to 1_750_000_000L,
                    "today.keyMetrics" to "charge",
                ),
                "backup_sync" to mapOf<String, Any>(
                    "tree_uri" to "content://com.android.externalstorage.documents/tree/primary%3ABackups",
                    "keep" to 10,
                ),
            ),
        )
        val json = requireNotNull(encoded).toString()
        for (forbidden in listOf(
            "AA:BB:CC:DD:EE:FF", "lastDeviceModel", "acceptedTermsVersion", "analyzeWatermark",
            "batteryLowAlerted", "trackUri", "stOnsetLastFire", "tree_uri",
        )) {
            assertFalse("$forbidden must never appear in a backup", json.contains(forbidden))
        }
        assertEquals("charge", AppStateCodec.decodeStores(encoded)["noop_prefs"]!!["today.keyMetrics"])
        assertEquals(10, AppStateCodec.decodeStores(encoded)["backup_sync"]!!["keep"])
    }

    @Test fun genericExclusionsApplyToTheirOwnStoreOnly() {
        // "auto" is the Backup & Sync daily schedule, which must not travel without the folder Uri it
        // depends on. The same WORD in another file is a different setting and must survive — which is
        // the whole reason unnamespaced exclusions are scoped to one store.
        val encoded = AppStateCodec.encodeStores(
            mapOf(
                "backup_sync" to mapOf<String, Any>("auto" to true, "keep" to 7),
                "noop_scoring_guide_prefs" to mapOf<String, Any>("auto" to true),
            ),
        )
        val back = AppStateCodec.decodeStores(encoded)
        assertNull(back["backup_sync"]!!["auto"])
        assertEquals(7, back["backup_sync"]!!["keep"])
        assertEquals(true, back["noop_scoring_guide_prefs"]!!["auto"])
        assertFalse(AppStateCodec.isExcluded("noop_scoring_guide_prefs", "auto"))
        assertTrue(AppStateCodec.isExcluded("backup_sync", "auto"))
    }

    @Test fun theSecretPrefsFilesAreNotInTheBackupSet() {
        for (secret in AppStateCodec.SECRET_STORES) {
            assertFalse(
                "$secret holds Keystore-sealed secrets and must never be backed up",
                secret in AppStateCodec.BACKED_UP_STORES,
            )
        }
    }

    @Test fun excludedKeysAreAlsoRefusedOnTheWayIn() {
        // A hand-edited (or future-build) backup must not be able to push this device's strap bond,
        // consent record or scoring watermark back onto it.
        val hand = JSONObject(
            """{"noop_prefs":{"s":{"noop.lastDeviceAddress":"11:22:33:44:55:66","today.sections":"a,b"},
               "b":{"noop.onboarded":true}}}""",
        )
        val back = AppStateCodec.decodeStores(hand)["noop_prefs"]!!
        assertNull(back["noop.lastDeviceAddress"])
        assertNull(back["noop.onboarded"])
        assertEquals("Its innocent neighbours still decode", "a,b", back["today.sections"])
    }

    @Test fun unknownStoresAndBucketsAreDropped() {
        val hand = JSONObject(
            """{"some_other_app_prefs":{"s":{"k":"v"}},"noop_prefs":{"zz":{"k":"v"},"s":{"today.sections":"a"}}}""",
        )
        val decoded = AppStateCodec.decodeStores(hand)
        assertNull(decoded["some_other_app_prefs"])
        assertEquals(mapOf<String, Any>("today.sections" to "a"), decoded["noop_prefs"])
    }

    @Test fun nothingToCarryEncodesToNullAndAbsenceDecodesToEmpty() {
        assertNull(AppStateCodec.encodeStores(emptyMap()))
        assertNull("A store of only excluded keys is not a store", AppStateCodec.encodeStores(
            mapOf("noop_prefs" to mapOf("noop.onboarded" to true)),
        ))
        assertNull("An unlisted store contributes nothing", AppStateCodec.encodeStores(
            mapOf("noop_widget" to mapOf("battery" to 50)),
        ))
        assertTrue(AppStateCodec.decodeStores(null).isEmpty())
        assertTrue(AppStateCodec.decodeStores(JSONObject()).isEmpty())
    }

    // ── The two layers of settings.json coexist ─────────────────────────────────

    @Test fun flatKeysAndStoresRideTogetherInOnePayload() {
        val stores = AppStateCodec.encodeStores(
            mapOf("noop_prefs" to mapOf("today.keyMetrics" to "charge,effort")),
        )
        val json = requireNotNull(
            BackupSettingsCodec.encode(mapOf("profile.age" to 34, "units.system" to "metric"), stores),
        )

        // The flat cross-platform contract is untouched…
        val flat = BackupSettingsCodec.decode(json)
        assertEquals(34, flat["profile.age"])
        assertEquals("metric", flat["units.system"])
        // …and the app-state block rides beside it.
        val back = AppStateCodec.decodeStores(BackupSettingsCodec.storesOf(json))
        assertEquals("charge,effort", back["noop_prefs"]!!["today.keyMetrics"])
        assertEquals(AppStateCodec.SCHEMA_VERSION, JSONObject(json).getInt(AppStateCodec.SCHEMA_KEY))
    }

    @Test fun aPreStoresBackupStillDecodesItsFlatKeysAndCarriesNoStores() {
        // Every backup written before this feature, and every Apple-written one.
        val legacy = """{"profile.age":34,"units.system":"metric"}"""
        assertEquals(34, BackupSettingsCodec.decode(legacy)["profile.age"])
        assertNull(BackupSettingsCodec.storesOf(legacy))
        assertTrue(AppStateCodec.decodeStores(BackupSettingsCodec.storesOf(legacy)).isEmpty())
    }

    @Test fun anOlderReaderIgnoresTheStoresBlockEntirely() {
        // The compatibility claim in both directions: the flat decoder drops unknown keys, so a new
        // backup read by an older Choop (or the Apple importer) restores exactly the nine it knows.
        val stores = AppStateCodec.encodeStores(
            mapOf("noop_prefs" to mapOf("today.keyMetrics" to "charge")),
        )
        val json = requireNotNull(BackupSettingsCodec.encode(mapOf("profile.hrMax" to 188), stores))
        val asOldReaderSees = BackupSettingsCodec.decode(json)
        assertEquals(mapOf<String, Any>("profile.hrMax" to 188), asOldReaderSees)
    }

    // ── Container: the avatar entry round-trips through the real ZIP layer ───────

    private val sqliteMagic = byteArrayOf(
        0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
        0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
    )

    private fun fakeSqlite(payload: String): File =
        tmp.newFile().apply {
            outputStream().use { it.write(sqliteMagic); it.write(payload.toByteArray()) }
        }

    @Test fun threeEntryBackupStagesDbSettingsAndAvatar() {
        val liveDb = fakeSqlite("rows")
        val avatar = tmp.newFile("avatar-source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val settingsJson = requireNotNull(
            BackupSettingsCodec.encode(
                mapOf("profile.age" to 41),
                AppStateCodec.encodeStores(mapOf("noop_profile" to mapOf("avatar_present" to true))),
            ),
        )
        val backup = tmp.newFile("full.noopbak")
        DataBackup.writeBackupZip(liveDb, backup, settingsJson, avatar)

        val stagedDb = tmp.newFile()
        val stagedSettings = File(tmp.root, "staged-settings.json")
        val stagedAvatar = File(tmp.root, "staged-avatar.jpg")
        val result = DataBackup.stageBackupSqlite(
            backup.inputStream(), DataBackup.peekHeader(backup), stagedDb, stagedSettings, stagedAvatar,
        )

        assertEquals(DataBackup.StageResult.OK, result)
        assertEquals(liveDb.readBytes().toList(), stagedDb.readBytes().toList())
        assertEquals(avatar.readBytes().toList(), stagedAvatar.readBytes().toList())
        // The photo's bytes and its "a photo is set" flag must land together, or the store reports a
        // photo it cannot draw.
        val flag = AppStateCodec.decodeStores(
            BackupSettingsCodec.storesOf(stagedSettings.readText(Charsets.UTF_8)),
        )["noop_profile"]!!["avatar_present"]
        assertEquals(true, flag)
    }

    @Test fun aBackupWithNoPhotoStagesNoAvatarAndIsNotAnError() {
        val liveDb = fakeSqlite("rows")
        val backup = tmp.newFile("no-photo.noopbak")
        DataBackup.writeBackupZip(liveDb, backup, """{"profile.age":30}""")

        val stagedAvatar = File(tmp.root, "staged-avatar.jpg")
        val result = DataBackup.stageBackupSqlite(
            backup.inputStream(), DataBackup.peekHeader(backup), tmp.newFile(),
            File(tmp.root, "staged-settings.json"), stagedAvatar,
        )

        assertEquals(DataBackup.StageResult.OK, result)
        assertFalse("No avatar entry → nothing staged, no error", stagedAvatar.exists())
    }

    // ── Migration groups: what a restore checkbox actually covers ───────────────

    @Test fun theSettingsTheUserNamesLandInTheGroupTheyWouldLookUnder() {
        fun group(store: String, key: String) = AppStateCodec.groupOf(store, key)

        // "start page layouts" — the Today grid, the cards, the sections, and the journal setup.
        assertEquals(MigrationGroup.LAYOUT, group("noop_prefs", "today.keyMetrics"))
        assertEquals(MigrationGroup.LAYOUT, group("noop_prefs", "today.dashboardCards"))
        assertEquals(MigrationGroup.LAYOUT, group("noop_prefs", "today.sections"))
        assertEquals(MigrationGroup.LAYOUT, group("noop_prefs", "noop.journalCatalogV2"))
        assertEquals(MigrationGroup.LAYOUT, group("noop_today_cards", "noop.todayCard.newHere.dismissed"))

        // "theme choice"
        assertEquals(MigrationGroup.APPEARANCE, group("noop_prefs", "theme.family"))
        assertEquals(MigrationGroup.APPEARANCE, group("noop_prefs", "theme.appearance"))
        assertEquals(MigrationGroup.APPEARANCE, group("noop_prefs", "chart.style"))
        assertEquals(MigrationGroup.APPEARANCE, group("noop_prefs", "units.system"))
        assertEquals(MigrationGroup.APPEARANCE, group("", "effort.scale"))

        assertEquals(MigrationGroup.PROFILE, group("noop_profile", "weight_kg"))
        assertEquals(MigrationGroup.PROFILE, group("", "profile.age"))

        assertEquals(MigrationGroup.ALERTS, group("noop_notif_prefs", "notif.masterEnabled"))
        assertEquals(MigrationGroup.ALERTS, group("noop_smart_alarm", "alarm.enabled"))
        assertEquals(MigrationGroup.ALERTS, group("noop_prefs", "noop.smartAlarmEnabled"))
        assertEquals(MigrationGroup.ALERTS, group("noop_prefs", "noop.batteryAlerts"))

        // The caffeine CUTOFF NUDGE is an alert; the caffeine LOG is not. One trailing dot apart.
        assertEquals(MigrationGroup.ALERTS, group("noop_prefs", "noop.caffeine.cutoffNudge"))
        assertEquals(MigrationGroup.REST, group("noop_prefs", "noop.caffeineIntakes"))

        // Baselines and anything unclaimed fall to REST rather than being dropped.
        assertEquals(MigrationGroup.REST, group("noop_prefs", "noop.hrvBaselineEpoch"))
        assertEquals(MigrationGroup.REST, group("noop_prefs", "wimhof.history"))
        assertEquals(MigrationGroup.REST, group("noop_prefs", "a.key.invented.next.year"))
    }

    @Test fun groupsPresentReportsOnlyWhatThePayloadHolds() {
        val present = AppStateCodec.groupsPresent(
            mapOf("noop_prefs" to mapOf<String, Any>("theme.family" to "dusk", "today.sections" to "a")),
        )
        assertEquals(
            setOf(MigrationGroup.APPEARANCE, MigrationGroup.LAYOUT),
            present,
        )
    }

    @Test fun aPreStoresBackupReportsOnlyTheGroupsItsFlatKeysCover() {
        // The case that made a working restore look broken: every backup written before Choop carried
        // app state has the nine flat keys and nothing else, so profile and units come back and the
        // theme, layout and journal cannot. The restore has to be able to SAY that.
        val legacy = """{"profile.age":34,"units.system":"metric"}"""
        assertEquals(
            setOf(MigrationGroup.PROFILE, MigrationGroup.APPEARANCE),
            BackupSettingsBridge.groupsIn(legacy),
        )

        val current = requireNotNull(
            BackupSettingsCodec.encode(
                mapOf("profile.age" to 34),
                AppStateCodec.encodeStores(
                    mapOf("noop_prefs" to mapOf<String, Any>("theme.family" to "dusk", "today.sections" to "a")),
                ),
            ),
        )
        assertEquals(
            setOf(
                MigrationGroup.PROFILE,
                MigrationGroup.APPEARANCE,
                MigrationGroup.LAYOUT,
            ),
            BackupSettingsBridge.groupsIn(current),
        )
    }

    @Test fun everyGroupIsReachableFromSomeRealKey() {
        // A group nobody can produce is a checkbox that can never do anything. HISTORY and PHOTO are
        // the two that are not preferences at all (the database file and the avatar entry), so they
        // are excluded from this sweep by construction.
        val reachable = listOf(
            "noop_profile" to "weight_kg",
            "noop_prefs" to "theme.family",
            "noop_prefs" to "today.sections",
            "noop_notif_prefs" to "notif.masterEnabled",
            "noop_prefs" to "noop.hrvBaselineEpoch",
        ).mapTo(HashSet()) { (store, key) -> AppStateCodec.groupOf(store, key) }

        val expected = MigrationGroup.entries.toSet() -
            setOf(MigrationGroup.HISTORY, MigrationGroup.PHOTO)
        assertEquals(expected, reachable)
    }

    // ── Restore progress: one bar that only ever moves forward ──────────────────

    @Test fun theProgressBarNeverGoesBackwards() {
        // A bar that resets at each phase is worse than no bar. Every phase's range has to start
        // where the previous one ended, and each phase has to map its own 0..1 inside that range.
        val phases = DataBackup.Phase.entries
        var previousEnd = 0f
        for (phase in phases) {
            assertEquals("${phase.name} must start where the last phase ended", previousEnd, phase.overall(0f), 0.0001f)
            assertTrue("${phase.name} must make progress", phase.overall(1f) > phase.overall(0f))
            previousEnd = phase.overall(1f)
        }
        assertEquals("The last phase must finish the bar", 1f, previousEnd, 0.0001f)
    }

    @Test fun aPhaseFractionIsClampedIntoItsOwnRange() {
        val reading = DataBackup.Phase.READING
        // A provider that under- or over-reports the file size must not push the bar out of range.
        assertEquals(reading.overall(0f), reading.overall(-5f), 0.0001f)
        assertEquals(reading.overall(1f), reading.overall(9f), 0.0001f)
    }

    // ── The swap itself: replacing a live database file ─────────────────────────

    @Test fun overwritingAnExistingDatabaseReplacesItsContentInPlace() {
        // The restore swap must not depend on being able to DELETE the live database — that is what
        // File.copyTo(overwrite = true) does, and a refused delete is how a first-run restore failed
        // with "Tried to overwrite the destination, but failed to delete it".
        val dest = tmp.newFile("live.db").apply { writeBytes(ByteArray(4096) { 0xAB.toByte() }) }
        val inode = dest.canonicalPath
        val source = fakeSqlite("restored")

        DataBackup.overwriteWith(source, dest)

        assertEquals(source.readBytes().toList(), dest.readBytes().toList())
        assertEquals("The destination is replaced in place, not recreated elsewhere", inode, dest.canonicalPath)
    }

    @Test fun overwritingTruncatesAShorterPayloadOverALongerOne() {
        // Truncation is the half a naive "open and write" gets wrong: the tail of the OLD database
        // would survive past the end of the restored one, and the result would fail quick_check.
        val dest = tmp.newFile("long.db").apply { writeBytes(ByteArray(64 * 1024) { 0x7F }) }
        val source = tmp.newFile("short.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        DataBackup.overwriteWith(source, dest)

        assertEquals(3L, dest.length())
        assertEquals(listOf<Byte>(1, 2, 3), dest.readBytes().toList())
    }

    @Test fun overwritingCreatesTheDestinationAndItsDirectoryWhenAbsent() {
        // The fresh-install shape: no databases/ directory yet, nothing to overwrite.
        val dest = File(File(tmp.root, "databases"), "new.db")
        val source = fakeSqlite("first")

        DataBackup.overwriteWith(source, dest)

        assertTrue(dest.isFile)
        assertEquals(source.readBytes().toList(), dest.readBytes().toList())
    }

    @Test fun aLegacyBackupStillStagesWhenAskedForAllThreeSidecars() {
        val liveDb = fakeSqlite("legacy")
        val backup = tmp.newFile("legacy.noopbak")
        DataBackup.writeBackupZip(liveDb, backup) // pre-#1000 single-entry shape

        val stagedDb = tmp.newFile()
        val result = DataBackup.stageBackupSqlite(
            backup.inputStream(), DataBackup.peekHeader(backup), stagedDb,
            File(tmp.root, "s.json"), File(tmp.root, "a.jpg"),
        )
        assertEquals(DataBackup.StageResult.OK, result)
        assertEquals(liveDb.readBytes().toList(), stagedDb.readBytes().toList())
    }
}
