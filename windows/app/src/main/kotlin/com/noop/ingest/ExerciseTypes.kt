package com.noop.ingest

/**
 * Desktop port stub of the Android `ExerciseTypes` object.
 *
 * The Android twin (com.noop.ingest.ExerciseTypes) references
 * `androidx.health.connect.client.records.ExerciseSessionRecord` constants for its int keys.
 * Health Connect is not available on the desktop JVM, so this stub inlines the same int
 * constants (the stable Health Connect EXERCISE_TYPE_* values) as plain ints and drops the
 * `androidx.health.connect` import. The data structure (NAMES, EXTRA, DISTANCE_TYPES, nameFor)
 * is identical so [com.noop.analytics.WorkoutSport] compiles unchanged.
 *
 * When a desktop importer (e.g. a CSV/TCX importer) is added, it should write these same
 * int values into the workout table's `sportType` column so the picker reads back correctly.
 */
object ExerciseTypes {

    // Health Connect EXERCISE_TYPE_* constants (stable int values from the HC API).
    private const val OTHER_WORKOUT = 0
    private const val RUNNING = 8
    private const val WALKING = 9
    private const val HIKING = 10
    private const val BIKING = 11
    private const val SWIMMING_OPEN_WATER = 12
    private const val ROWING = 13
    private const val RUNNING_TREADMILL = 14
    private const val BIKING_STATIONARY = 15
    private const val SWIMMING_POOL = 16
    private const val ROWING_MACHINE = 17
    private const val ELLIPTICAL = 18
    private const val STRENGTH_TRAINING = 19
    private const val WEIGHTLIFTING = 20
    private const val HIGH_INTENSITY_INTERVAL_TRAINING = 21
    private const val YOGA = 22
    private const val PILATES = 23
    private const val BOXING = 25
    private const val BASKETBALL = 26
    private const val SOCCER = 27
    private const val BASEBALL = 28
    private const val BADMINTON = 29
    private const val TENNIS = 30
    private const val SQUASH = 31
    private const val RACQUETBALL = 32
    private const val TABLE_TENNIS = 33
    private const val VOLLEYBALL = 34
    private const val MARTIAL_ARTS = 35
    private const val DANCING = 36
    private const val GOLF = 37
    private const val ROCK_CLIMBING = 38
    private const val STRETCHING = 39
    private const val SKIING = 41
    private const val SNOWBOARDING = 42

    /** Ordered for the picker: common / distance first, then the rest, then Other. */
    val NAMES: Map<Int, String> = linkedMapOf(
        RUNNING to "Running",
        WALKING to "Walking",
        HIKING to "Hiking",
        BIKING to "Cycling",
        SWIMMING_OPEN_WATER to "Open-water swim",
        ROWING to "Rowing",
        RUNNING_TREADMILL to "Treadmill run",
        BIKING_STATIONARY to "Indoor cycle",
        SWIMMING_POOL to "Pool swim",
        ROWING_MACHINE to "Row machine",
        ELLIPTICAL to "Elliptical",
        STRENGTH_TRAINING to "Strength",
        WEIGHTLIFTING to "Weightlifting",
        HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
        YOGA to "Yoga",
        PILATES to "Pilates",
        BOXING to "Boxing",
        BASKETBALL to "Basketball",
        SOCCER to "Soccer",
        BASEBALL to "Baseball",
        BADMINTON to "Badminton",
        TENNIS to "Tennis",
        SQUASH to "Squash",
        RACQUETBALL to "Racquetball",
        TABLE_TENNIS to "Table tennis",
        VOLLEYBALL to "Volleyball",
        MARTIAL_ARTS to "Martial arts",
        DANCING to "Dancing",
        GOLF to "Golf",
        ROCK_CLIMBING to "Climbing",
        STRETCHING to "Stretching",
        SKIING to "Skiing",
        SNOWBOARDING to "Snowboarding",
        OTHER_WORKOUT to "Other",
    )

    /**
     * Sports the user can pick that Health Connect has NO dedicated type for, so they ride on a
     * fallback type while keeping their own NOOP label.
     */
    val EXTRA: List<Pair<String, Int>> = listOf(
        "Padel" to OTHER_WORKOUT,
        "Pickleball" to OTHER_WORKOUT,
        "Bowling" to OTHER_WORKOUT,
        "Treadmill walk" to WALKING,
        "Bodybuilding" to STRENGTH_TRAINING,
    )

    /** Types where a route makes sense -> GPS defaults on. */
    val DISTANCE_TYPES: Set<Int> = setOf(
        RUNNING,
        WALKING,
        HIKING,
        BIKING,
        SWIMMING_OPEN_WATER,
        ROWING,
        SKIING,
        SNOWBOARDING,
    )

    fun nameFor(type: Int): String = NAMES[type] ?: "Workout"
}
