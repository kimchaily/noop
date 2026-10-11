package com.noop.analytics

import com.noop.ingest.ExerciseTypes

/** One selectable activity. [exerciseType] is a Health Connect EXERCISE_TYPE_* constant. */
data class Sport(val exerciseType: Int, val name: String, val isDistanceSport: Boolean)

object WorkoutSport {
    // Health-Connect-typed sports + the EXTRA sports HC has no type for (e.g. Padel, #77/#152), which
    // ride a fallback HC type but keep their own Choop label. The extras are inserted just before
    // "Other" so the picker still ends on the generic catch-all. Shared by the picker (live + manual)
    // AND the HC writeback (Sport.exerciseType).
    val all: List<Sport> = buildList {
        ExerciseTypes.NAMES.forEach { (type, name) ->
            if (name == "Other") return@forEach // re-appended last, after the extras
            add(Sport(type, name, isDistanceSport = type in ExerciseTypes.DISTANCE_TYPES))
        }
        ExerciseTypes.EXTRA.forEach { (name, fallbackType) ->
            add(Sport(fallbackType, name, isDistanceSport = false))
        }
        ExerciseTypes.NAMES.entries.firstOrNull { it.value == "Other" }?.let { (type, name) ->
            add(Sport(type, name, isDistanceSport = type in ExerciseTypes.DISTANCE_TYPES))
        }
    }
    fun nameFor(type: Int) = ExerciseTypes.nameFor(type)

    /** The default when none is chosen ("Other"). */
    val default: Sport get() = all.first { it.name == "Other" }
}
