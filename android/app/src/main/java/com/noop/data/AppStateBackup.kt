package com.noop.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The WHOLE-APP-STATE half of a `.noopbak` — everything the user set that does NOT live in the
 * SQLite database.
 *
 * ## Why this exists
 *
 * A `.noopbak` used to be "the database, plus nine profile/display values" ([BackupSettingsCodec]).
 * That is lossless for measurements — every sample, night, workout, journal answer, lab marker and
 * paired-device row rides in the DB file — but Choop keeps a LOT of the user's own decisions in
 * SharedPreferences instead: the Today layout, journal renames and custom questions, the theme, the
 * Charge baseline anchor dates, wrist-alert rules, the smart alarm, Wim Hof settings and history,
 * caffeine log, the Updates inbox. None of that survived a move to a new phone, so a "restored"
 * install came back with the right numbers and a stranger's setup.
 *
 * The old nine-key whitelist was the wrong shape for that job: every feature added since had to
 * remember to add its key, and none did. So this is the inverse — a **deny-list**. Every preferences
 * file is captured whole and only the things that MUST NOT travel are named here. A new feature's
 * setting migrates because nobody had to do anything; a key that would be wrong on the new phone is
 * a one-line addition to [EXCLUDED_KEYS] with a reason beside it.
 *
 * ## What must not travel (and why)
 *
 *  - **Secrets.** The AI Coach API key and the Oura install key live in EncryptedSharedPreferences,
 *    sealed by a key in this phone's Android Keystore. They could not be decrypted elsewhere even if
 *    they were copied — and a `.noopbak` gets dropped in cloud folders and attached to GitHub issues,
 *    so a secret must never be in one. Whole files, excluded.
 *  - **Handles this install owns.** A SAF tree Uri (the Backup & Sync folder), a picked audio track
 *    Uri, the strap's MAC address: permission grants and bonds belong to one install on one phone.
 *    Carrying them across produces a setting that looks configured and silently does nothing.
 *  - **Sync cursors and one-shot flags.** Backfill/Health-Connect cursors, the analyze watermark, the
 *    "this heal already ran" markers. They describe work done against a database on a device; the
 *    restored install re-derives them, and [DataBackup.importFrom] deliberately clears the watermark
 *    so the imported history is re-scored.
 *  - **Latches and in-flight state.** "Battery low already alerted", the alarm deadline scheduled
 *    with the old phone's AlarmManager, a workout still running over there.
 *  - **Caches and diagnostics.** The day-digest cache and the analyze journal both document
 *    themselves as never-backed-up; losing them costs one recompute.
 *
 * ## Format
 *
 * A `"stores"` object inside the same `settings.json` entry, alongside (never instead of) the nine
 * flat cross-platform keys, so:
 *  - an OLDER Choop, or the Apple importer, reads a NEW backup and applies the nine as it always did
 *    (it drops unknown keys by design), and
 *  - a NEW Choop reads an OLD backup and applies the nine it finds, with no `stores` block.
 *
 * Values are grouped by TYPE rather than tagged one by one, because SharedPreferences is typed and
 * JSON is not: `getInt` on a value stored as a Long throws, so "put it back as whatever it was" has
 * to be exact. The type comes from the live prefs at export and rides along:
 *
 * ```json
 * { "profile.age": 34, "units.system": "metric",
 *   "schemaVersion": 2,
 *   "stores": {
 *     "noop_prefs": {
 *       "b":  { "noop.showSupport": true },
 *       "i":  { "noop.smartAlarmMinutes": 30 },
 *       "l":  { "noop.hrvBaselineEpoch": 1750000000 },
 *       "f":  { "wimhof.trackVolume": 0.8 },
 *       "s":  { "today.keyMetrics": "charge,effort,rest" },
 *       "ss": { "noop.settings.collapsedSections": ["Appearance"] }
 *     }
 *   }
 * }
 * ```
 *
 * Pure JSON + policy, no Context, so the whole thing is plain-JVM unit-testable; the
 * SharedPreferences boundary is [AppStateBridge].
 */
object AppStateCodec {

    /** Bumped when the SHAPE of the `stores` block changes. Absent/1 = flat-keys-only (pre-#1000+). */
    const val SCHEMA_VERSION = 2

    /** JSON key holding the per-store snapshot, beside the flat cross-platform keys. */
    const val STORES_KEY = "stores"

    /** JSON key holding [SCHEMA_VERSION]. */
    const val SCHEMA_KEY = "schemaVersion"

    // ── Type buckets ────────────────────────────────────────────────────────────

    private const val T_BOOL = "b"
    private const val T_INT = "i"
    private const val T_LONG = "l"
    private const val T_FLOAT = "f"
    private const val T_STRING = "s"
    private const val T_STRING_SET = "ss"

    // ── What a restore is allowed to bring across, in the user's terms ──────────

    /**
     * The pieces of a backup a user can choose between when restoring.
     *
     * A `.noopbak` is all-or-nothing on disk, but applying it doesn't have to be: moving to a new
     * phone and wanting your history WITHOUT the old phone's alert rules is a reasonable thing to
     * want, as is taking a setup across without the data. So every restorable thing belongs to
     * exactly one group, the restore UI shows them as checkboxes, and only the ticked ones are
     * written.
     *
     * The grouping is by what the user would call it, not by which preferences file it lives in —
     * the Today layout and the journal catalog share a group because they are both "how my start
     * page is set up", even though one is `noop_prefs` keys and the other is a whole store.
     */
    enum class MigrationGroup(val title: String, val detail: String) {
        /** The database file itself — everything that was ever measured or logged. */
        HISTORY(
            "Measurements & history",
            "Every reading, night, workout, journal answer, lab marker and paired strap.",
        ),
        PROFILE(
            "Profile & body",
            "Age, sex, weight, height, waist, HR-max override and your step calibration.",
        ),
        /** Not a preference: the `avatar.jpg` entry's bytes. */
        PHOTO(
            "Profile photo",
            "The picture on your Today header.",
        ),
        APPEARANCE(
            "Appearance & units",
            "Theme, light/dark, chart colours, metric or imperial, and the Effort axis.",
        ),
        LAYOUT(
            "Today layout & journal",
            "Your Key Metrics, cards and sections, and your renamed, grouped and custom journal questions.",
        ),
        ALERTS(
            "Alerts & reminders",
            "Wrist alerts and buzz patterns, quiet hours, the smart alarm, wind-down and move reminders.",
        ),
        REST(
            "Baselines & everything else",
            "Charge baseline anchors, breathwork, caffeine, hydration, experiments and the remaining settings.",
        ),
        ;

        companion object {
            /** Everything — the default for a restore nobody has narrowed. */
            val ALL: Set<MigrationGroup> = entries.toSet()
        }
    }

    /** Keys that read as "appearance" to a user but carry no namespace saying so. */
    private val APPEARANCE_KEYS: Set<String> = setOf(
        "noop.showDayCycleBackground",
        "noop.vitalStateColours",
        "noop.appIconNavy",
        "noop.showSupport",
        "effort.scale",
    )

    /** Alert/reminder keys in the shared store, where the namespace doesn't group them. */
    private val ALERT_KEYS: Set<String> = setOf(
        "noop.batteryAlerts",
        "noop.illnessWatch",
        "noop.zoneCoaching",
        "noop.zoneCoachRecovery",
        "noop.buzzWhoop4WithAlarm",
        "noop.napDetectionEnabled",
        "noop.autoDetectWorkouts",
    )

    /**
     * Which group a restored preference belongs to. Total by construction — anything not claimed by
     * a more specific rule lands in [MigrationGroup.REST], so a key added later still restores (with
     * the rest of the settings) instead of being dropped for having no home.
     */
    fun groupOf(store: String, key: String): MigrationGroup = when {
        store == "noop_profile" -> MigrationGroup.PROFILE
        store == "noop_notif_prefs" ||
            store == "noop_inactivity_prefs" ||
            store == "noop_smart_alarm" ||
            store == "noop_wind_down" -> MigrationGroup.ALERTS
        store == "noop_today_cards" -> MigrationGroup.LAYOUT

        key.startsWith("today.") || key.startsWith("noop.journal") ||
            key == "noop.more.expandedSections" || key == "noop.settings.collapsedSections" ||
            key == "noop.settingsAdvancedOpen" -> MigrationGroup.LAYOUT

        key.startsWith("profile.") -> MigrationGroup.PROFILE

        key.startsWith("theme.") || key.startsWith("chart.") || key.startsWith("units.") ||
            key.startsWith("sleep.") || key in APPEARANCE_KEYS -> MigrationGroup.APPEARANCE

        // The trailing dot matters: `noop.caffeine.cutoffNudge` is an alert, while
        // `noop.caffeineIntakes` is the log itself and belongs with the rest.
        key.startsWith("noop.smartAlarm") || key.startsWith("noop.report.") ||
            key.startsWith("noop.caffeine.") || key in ALERT_KEYS -> MigrationGroup.ALERTS

        else -> MigrationGroup.REST
    }

    /** The groups a decoded `stores` payload actually carries — what a restore of it could apply. */
    fun groupsPresent(stores: Map<String, Map<String, Any>>): Set<MigrationGroup> {
        val out = LinkedHashSet<MigrationGroup>()
        for ((store, values) in stores) {
            for (key in values.keys) out += groupOf(store, key)
        }
        return out
    }

    // ── Which preference files travel ───────────────────────────────────────────

    /**
     * Every SharedPreferences file a restore should reproduce. Named explicitly (rather than scanning
     * `shared_prefs/`) so adding a file is a decision someone made, and so a library that quietly
     * creates its own prefs file can never end up inside a user's backup.
     */
    val BACKED_UP_STORES: List<String> = listOf(
        "noop_prefs",                 // the big shared store: Today layout, journal catalog, baselines,
                                      // theme, units, automations, experiment setup, caffeine log…
        "noop_profile",               // age / sex / weight / height / waist / HR-max + step calibration
        "noop_notif_prefs",           // wrist alerts: master, quiet hours, per-app enable + buzz pattern
        "noop_inactivity_prefs",      // move reminders: threshold, re-nudge, active hours
        "noop_smart_alarm",           // smart alarm: enabled, target, window
        "noop_wind_down",             // wind-down reminder: enabled, sleep need, lead time
        "noop_auto_workout_prefs",    // auto-detect suggestions the user dismissed
        "noop_experiments",           // opt-in experimental probes (puffin / deep data / sleep V2)
        "noop_today_cards",           // which Today info-cards were dismissed into the inbox
        "noop_updates",               // the Updates inbox items + last-seeded What's New version
        "noop_donate_prefs",          // "don't show support surfaces" opt-out
        "noop_scoring_guide_prefs",   // whether the scoring-guide card was seen
        "noop_debug_export",          // the opt-in daily debug-log export: on/off and time of day
        "backup_sync",                // Backup & Sync retention count (the folder Uri is excluded below)
    )

    /**
     * Preference files that must NEVER be read into a backup, whatever they contain. Both are
     * EncryptedSharedPreferences sealed by this device's Keystore. Listed to make the omission
     * deliberate and greppable — [BACKED_UP_STORES] is the allow-list that actually decides.
     */
    val SECRET_STORES: List<String> = listOf(
        "noop_ai_secure_prefs",       // AI Coach API key (+ provider/model/consent, same file)
        "noop_oura_secure_prefs",     // Oura ring install key
    )

    // ── Which keys inside those files do NOT travel ─────────────────────────────

    /** Exact keys that must not travel, each with the reason it would be wrong on the new phone. */
    private val EXCLUDED_KEYS: Set<String> = setOf(
        // First-run gates: the new install runs its own onboarding, and consent is per-device.
        "noop.onboarded",
        "noop.acceptedTermsVersion",
        "noop.acceptedTermsAt",

        // This install's bond with a strap. A Bluetooth pairing belongs to one phone and cannot be
        // exported, so the remembered address is a reconnect target the new phone can't reach — it
        // has to bond once itself. Both halves of the pair go, since one without the other is dead
        // state. The paired-device ROWS still travel in the database: they own the deviceId every
        // sample is filed under, and dropping them would orphan the history.
        "noop.lastDeviceAddress",
        "noop.lastDeviceModel",

        // Set when a restore lands mid-onboarding so the relaunch resumes at "pair your strap"
        // instead of page one. Describes one install's first run; it must never ride into another.
        "noop.restoredPendingSetup",

        // Scoring watermarks / one-shot heals: derived from a database on a device. importFrom
        // clears the analyze watermark on purpose so imported history is re-scored.
        "noop.analyzeWatermark",
        "noop.chargeRescore.completedVersion",
        "noop.chargeRescore.completedFingerprint",
        "noop.chargeRescore.completedAt",
        "noop.effortRescore.v313.done",
        "noop.identityFusionHeal.done",
        "noop.tsHeal.v547.done",
        "noop.tsHeal.v547.pending",

        // Sync cursors — where THIS install got to, against sources the new one must re-authorise.
        "noop.hcLastSync",
        "noop.hcHrFrontierTs",
        "noop.lastSyncAtSec",

        // Notification / nudge latches: "already fired for this day/level". Restoring them suppresses
        // the first legitimate alert on the new phone.
        "noop.batteryLowAlerted",
        "noop.batteryFullAlerted",
        "noop.illnessLastNotifiedDay",
        "noop.report.lastMorningDay",
        "noop.report.lastWorkoutTs",
        "noop.lastJournalPromptDay",
        "noop.napHighWaterTs",
        "noop.napPending",
        "donate.lastShownTs",

        // Alarms actually scheduled with the OLD phone's AlarmManager. The settings travel; the
        // pending schedule is re-derived from them on the new one.
        "alarm.scheduledDeadlineMs",
        "alarm.scheduledWindowStartMs",

        // Move-reminder de-dup state (which bout was already buzzed for), same reasoning.
        "inactivity.lastBuzzAt",
        "inactivity.lastBuzzedBoutStart",
        "inactivity.lastBuzzedBoutEnd",
        "inactivity.lastProcessedGravityTs",

        // A content:// grant for a picked audio track — same problem as the tree Uri below.
        "wimhof.trackUri",
    )

    /**
     * Key PREFIXES that must not travel. Used where the keys are generated rather than declared, so
     * an exact list could not stay complete.
     */
    private val EXCLUDED_PREFIXES: List<String> = listOf(
        // Live stress-onset detector state (rolling baseline + last-fire latch), re-derived from data.
        "biofeedback.stOnset",
    )

    /**
     * Exclusions scoped to ONE store, for the files whose keys aren't namespaced.
     *
     * `auto`, `keep`, `tree_uri` are generic enough that another preferences file could reasonably
     * use the same word for something entirely different; excluding them globally would silently
     * drop that unrelated setting. Anything namespaced (`noop.*`, `alarm.*`, `inactivity.*`) is safe
     * in [EXCLUDED_KEYS], where one entry covers it wherever it lives.
     */
    private val EXCLUDED_STORE_KEYS: Map<String, Set<String>> = mapOf(
        // The Backup & Sync destination: a permission grant on a document tree this install holds.
        // The user re-picks the folder on the new phone — and "auto" without one would be a daily
        // schedule that silently writes nothing, so the switch waits for the folder too. `keep` (how
        // many snapshots to retain) is a real preference and does travel.
        "backup_sync" to setOf("tree_uri", "auto", "last_ms"),

        // Steps-engine FITTED outputs. Re-derived from the step samples that travel in the database;
        // the user's MANUAL calibration (step_ticks_per_step / steps_manual_*) does travel.
        "noop_profile" to setOf(
            "steps_calibration_coefficient",
            "steps_calibration_sample_days",
            "steps_calibration_confidence",
        ),
    )

    /** True when [key], in [store], must be left out of a backup. */
    fun isExcluded(store: String, key: String): Boolean =
        key in EXCLUDED_KEYS ||
            key in EXCLUDED_STORE_KEYS[store].orEmpty() ||
            EXCLUDED_PREFIXES.any { key.startsWith(it) }

    // ── Encode / decode ─────────────────────────────────────────────────────────

    /**
     * Encode a `store name -> (key -> value)` snapshot into the `stores` JSON object, dropping every
     * excluded key and every value whose type SharedPreferences cannot hold. Returns null when
     * nothing survived, so a device with no state to carry writes no `stores` block at all.
     */
    fun encodeStores(stores: Map<String, Map<String, Any?>>): JSONObject? {
        val out = JSONObject()
        for (storeName in BACKED_UP_STORES) {
            val values = stores[storeName] ?: continue
            val buckets = JSONObject()
            for ((key, value) in values) {
                if (isExcluded(storeName, key)) continue
                val (bucket, encoded) = bucketFor(value) ?: continue
                val into = buckets.optJSONObject(bucket) ?: JSONObject().also { buckets.put(bucket, it) }
                into.put(key, encoded)
            }
            if (buckets.length() > 0) out.put(storeName, buckets)
        }
        return if (out.length() == 0) null else out
    }

    /**
     * Decode a `stores` object back to `store name -> (key -> value)`, with every value carrying the
     * exact type SharedPreferences must store it as.
     *
     * Every failure degrades to "fewer keys", never to an error: a restore whose database half is
     * fine must not be failed by a settings entry someone hand-edited. Unknown store names and
     * unknown type buckets are dropped, and the exclusion policy is applied on the way IN as well as
     * out — so a backup written by a future build that decided to carry the strap MAC cannot push it
     * onto this device.
     */
    fun decodeStores(obj: JSONObject?): Map<String, Map<String, Any>> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Map<String, Any>>()
        for (storeName in BACKED_UP_STORES) {
            val buckets = obj.optJSONObject(storeName) ?: continue
            val values = LinkedHashMap<String, Any>()
            for (bucket in listOf(T_BOOL, T_INT, T_LONG, T_FLOAT, T_STRING, T_STRING_SET)) {
                val entries = buckets.optJSONObject(bucket) ?: continue
                val keys = entries.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isExcluded(storeName, key)) continue
                    decodeValue(bucket, entries.opt(key))?.let { values[key] = it }
                }
            }
            if (values.isNotEmpty()) out[storeName] = values
        }
        return out
    }

    /** The type bucket and JSON form for a SharedPreferences value, or null if it isn't one. */
    private fun bucketFor(value: Any?): Pair<String, Any>? = when (value) {
        is Boolean -> T_BOOL to value
        is Int -> T_INT to value
        is Long -> T_LONG to value
        is Float -> T_FLOAT to value.toDouble()
        is String -> T_STRING to value
        is Set<*> -> {
            // A prefs string-set can only hold strings; anything else means the value was not one.
            val strings = value.filterIsInstance<String>()
            if (strings.size != value.size) null else T_STRING_SET to JSONArray(strings)
        }
        else -> null
    }

    /**
     * Read one value back as the exact type its bucket declares. JSON has one number type, so an Int
     * written as `30` and a Long written as `30` are indistinguishable in the file — the bucket is
     * what tells them apart, and it must win, or the restored pref throws a ClassCastException the
     * first time the app reads it. A JSON boolean is not a [Number] on the JVM, so `true` can never
     * be coerced into an int here (the same guard the flat-key codec makes explicitly).
     */
    private fun decodeValue(bucket: String, raw: Any?): Any? = when (bucket) {
        T_BOOL -> raw as? Boolean
        T_INT -> (raw as? Number)?.toInt()
        T_LONG -> (raw as? Number)?.toLong()
        T_FLOAT -> (raw as? Number)?.toFloat()
        T_STRING -> raw as? String
        T_STRING_SET -> (raw as? JSONArray)?.let { array ->
            val set = LinkedHashSet<String>()
            for (i in 0 until array.length()) (array.opt(i) as? String)?.let(set::add)
            set
        }
        else -> null
    }
}

/**
 * The SharedPreferences boundary for [AppStateCodec]: read every backed-up preferences file for an
 * export, and write a restored snapshot back.
 *
 * Kept separate from the codec so the codec stays plain-JVM testable (this needs a real Context).
 */
object AppStateBridge {

    /** Every backed-up preferences file of this device, as `store name -> (key -> value)`. */
    fun snapshot(context: Context): Map<String, Map<String, Any?>> {
        val app = context.applicationContext
        val out = LinkedHashMap<String, Map<String, Any?>>()
        for (name in AppStateCodec.BACKED_UP_STORES) {
            val values = runCatching {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).all
            }.getOrNull().orEmpty()
            if (values.isNotEmpty()) out[name] = values
        }
        return out
    }

    /**
     * Write a decoded snapshot back into this device's preferences files.
     *
     * Only the [groups] the user ticked are written; everything else in [stores] is left alone.
     *
     * MERGES rather than replaces: a key the backup doesn't carry keeps whatever this install has.
     * That is what makes restoring onto a phone that has already been used safe — an excluded key
     * (this install's strap bond, its sync cursors) is left exactly as it was instead of being wiped
     * by a backup that was never allowed to contain it.
     */
    fun apply(
        context: Context,
        stores: Map<String, Map<String, Any>>,
        groups: Set<AppStateCodec.MigrationGroup> = AppStateCodec.MigrationGroup.ALL,
    ) {
        val app = context.applicationContext
        for ((name, values) in stores) {
            if (name !in AppStateCodec.BACKED_UP_STORES || values.isEmpty()) continue
            runCatching {
                val editor = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                for ((key, value) in values) {
                    // The user's choice is enforced HERE rather than at decode, so one decoded
                    // payload can answer both "what does this backup carry" and "write this subset".
                    if (AppStateCodec.groupOf(name, key) !in groups) continue
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                editor.apply()
            }
        }
    }
}
