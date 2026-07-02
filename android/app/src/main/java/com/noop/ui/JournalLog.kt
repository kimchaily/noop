package com.noop.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.data.JournalEntry
import java.time.LocalDate

// MARK: - Native journal logging (pure helpers + the Insights logging card)

/** Native answers are written under this dedicated source id, NEVER under "my-whoop": journal's
 *  PK is (deviceId, day, question) and has no source column, so a CSV re-import would silently
 *  overwrite in-app answers (and clears could delete imported rows). */
const val JOURNAL_DEVICE_ID = "noop-journal"

/** Starter behaviour catalog (mirrors WHOOP's most popular journal questions, full-question
 *  phrasing matching the export style). Question strings are opaque exact-match labels to the
 *  effects engine, so imported question strings always take precedence (mergeJournalCatalog).
 *  They are DATA, not UI literals — stored verbatim in the journal table and never localised.
 *  Mirrors macOS JournalCatalogStore.starterQuestions value-for-value. */
val STARTER_JOURNAL_QUESTIONS: List<String> = listOf(
    "Did you drink any alcohol?",
    "Did you have caffeine late in the day?",
    "Did you view a screen in bed?",
    "Did you eat close to bedtime?",
    "Did you feel stressed?",
    "Did you use a sauna?",
    "Did you share your bed?",
    "Did you feel sick or ill?",
    "Did you take magnesium?",
    "Did you read before bed?",
)

/** Dedup/identity key for a question. Normalises ALL whitespace — leading/trailing AND internal
 *  runs collapse to a single space — then lowercases. A WHOOP export commonly leaves a trailing
 *  newline or non-breaking space on a journal cell; folding it here is what keeps an imported
 *  "Did you take magnesium?\n" from sitting beside the starter "Did you take magnesium?" as two
 *  separate rows (#224). The DISPLAYED string stays verbatim — only the match key is normalised —
 *  so the stored behaviour key the effects engine joins on is untouched.
 *  Kept value-for-value in step with macOS `JournalCatalogStore.norm` (JournalCatalog.swift). */
internal fun normJournalKey(s: String): String =
    // Collapse every run of whitespace to a single space, then trim + lowercase. Uses Kotlin's
    // `Char.isWhitespace()` (Unicode-aware — it includes non-breaking space U+00A0 etc.) rather than
    // a regex: the previous `Regex("(?U)\\s+")` compiled on the desktop JVM but THREW
    // PatternSyntaxException on Android's ICU engine (the `(?U)` inline flag is unsupported there),
    // crashing the Insights screen for anyone with journal entries to merge (#224/#267). Matches the
    // Swift `.whitespacesAndNewlines` normalisation value-for-value.
    buildString {
        var prevSpace = true // suppress leading whitespace
        for (c in s) {
            if (c.isWhitespace()) {
                if (!prevSpace) append(' ')
                prevSpace = true
            } else {
                append(c)
                prevSpace = false
            }
        }
    }.trim().lowercase()

/** Catalog = imported questions (exact strings → logged days join imported history), then starter
 *  defaults, then user customs. Case-insensitive dedupe, first casing wins, with `hidden` questions
 *  (starter/imported ones the user removed) filtered out. */
internal fun mergeJournalCatalog(
    imported: List<String>,
    custom: List<String>,
    hidden: List<String> = emptyList(),
    starter: List<String> = STARTER_JOURNAL_QUESTIONS,
): List<String> {
    val hiddenSet = hidden.map { normJournalKey(it) }.toHashSet()
    val out = ArrayList<String>()
    val seen = HashSet<String>()
    for (q in imported + starter + custom) {
        // Display text trims surrounding whitespace; the dedup key normalises ALL whitespace (see
        // normJournalKey) so an imported "…magnesium?\n" folds onto the starter (#224).
        val t = q.trim()
        val key = normJournalKey(q)
        if (t.isNotEmpty() && key !in hiddenSet && seen.add(key)) out.add(t)
    }
    return out
}

/** Union of imported + native entries; on a (day, question) collision the NATIVE row wins (the
 *  in-app answer is the user's most recent explicit action and stays editable). */
internal fun mergeJournalEntries(
    imported: List<JournalEntry>,
    native: List<JournalEntry>,
): List<JournalEntry> {
    val byKey = LinkedHashMap<Pair<String, String>, JournalEntry>()
    for (e in imported) byKey[e.day to e.question] = e
    for (e in native) byKey[e.day to e.question] = e
    return byKey.values.sortedWith(compareBy({ it.day }, { it.question }))
}

/** Importer convention (WhoopCsvImporter.parseJournal): journal day = the wake/cycle day whose
 *  morning recovery the previous ~24 h affected. "Log today" = answers about yesterday / last
 *  night, attributed to TODAY's local day key; daysBack=1 edits yesterday; daysBack=-1 logs ahead
 *  for TOMORROW (today's activities inform tomorrow's recovery). */
internal fun journalDayKey(daysBack: Long = 0L, today: LocalDate = LocalDate.now()): String =
    today.minusDays(daysBack).toString()

// MARK: - Custom-question persistence
//
// Stored in the shared "noop_prefs" file under a "noop."-prefixed key, newline-joined (a string
// set loses order). Kept here rather than in NoopPrefs so the logging card is self-contained.

private const val JOURNAL_PREFS = "noop_prefs"
private const val JOURNAL_CUSTOM_KEY = "noop.journalCustomQuestions"
private const val JOURNAL_HIDDEN_KEY = "noop.journalHiddenQuestions"

private fun loadJournalList(context: Context, key: String): List<String> =
    (context.getSharedPreferences(JOURNAL_PREFS, Context.MODE_PRIVATE)
        .getString(key, "") ?: "")
        .split('\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun saveJournalList(context: Context, key: String, questions: List<String>) {
    context.getSharedPreferences(JOURNAL_PREFS, Context.MODE_PRIVATE)
        .edit().putString(key, questions.joinToString("\n")).apply()
}

internal fun loadCustomJournalQuestions(context: Context): List<String> =
    loadJournalList(context, JOURNAL_CUSTOM_KEY)

internal fun saveCustomJournalQuestions(context: Context, questions: List<String>) =
    saveJournalList(context, JOURNAL_CUSTOM_KEY, questions)

internal fun loadHiddenJournalQuestions(context: Context): List<String> =
    loadJournalList(context, JOURNAL_HIDDEN_KEY)

internal fun saveHiddenJournalQuestions(context: Context, questions: List<String>) =
    saveJournalList(context, JOURNAL_HIDDEN_KEY, questions)

// MARK: - The logging card (hosted at the top of Insights)

/**
 * Yes/no chips for the merged behaviour catalog + a free-text custom-question field. Tri-state:
 * no answer logged → neither chip filled; tapping the selected chip again clears the answer
 * (deletes the native row — imported rows are never touched). Day attribution follows the
 * importer's wake-day convention, with a Tomorrow/Today/Yesterday toggle for logging ahead or late.
 */
@Composable
fun JournalLogCard(
    catalog: List<String>,
    answers: Map<String, Boolean>,
    dayOffset: Long,
    onDayOffset: (Long) -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    onClear: (String) -> Unit,
    onAddCustom: (String) -> Unit,
    customQuestions: List<String> = emptyList(),
    hidden: List<String> = emptyList(),
    onRemoveQuestion: (String) -> Unit = {},
    onRestoreQuestion: (String) -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    val customKeys = remember(customQuestions) { customQuestions.map { normJournalKey(it) }.toHashSet() }
    fun isCustom(q: String) = normJournalKey(q) in customKeys

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Header: title/overline on the left, the Tomorrow/Today/Yesterday toggle (or Edit/Done) on the right.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Inlined (not SectionHeader) so the title stays single-line in this crowded header row:
            // with Edit + the three day chips on the right, SectionHeader's wrapping title squeezed
            // "Journal" onto two lines (#443). Same tokens as SectionHeader (Overline + title2).
            Column(modifier = Modifier.weight(1f)) {
                Overline("Log")
                Text(
                    "Journal",
                    style = NoopType.title2,
                    color = Palette.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (editing) {
                JournalChip("Done", selected = true) { editing = false }
            } else {
                JournalChip("Edit", selected = false) { editing = true }
                Spacer(Modifier.width(6.dp))
                // Chronological left→right: Yesterday · Today · Tomorrow (#443).
                JournalChip("Yesterday", selected = dayOffset == 1L) { onDayOffset(1L) }
                Spacer(Modifier.width(6.dp))
                JournalChip("Today", selected = dayOffset == 0L) { onDayOffset(0L) }
                Spacer(Modifier.width(6.dp))
                JournalChip("Tomorrow", selected = dayOffset == -1L) { onDayOffset(-1L) }
            }
        }
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        editing ->
                            "Remove a question to tidy your list. Custom questions are deleted; the " +
                                "built-in ones are hidden and can be restored below."
                        dayOffset == -1L ->
                            "Logging ahead for tomorrow: today's activities inform tomorrow's " +
                                "recovery, just as yesterday's are reflected in today's. Tomorrow's " +
                                "answers line up with tomorrow's morning."
                        else ->
                            "Answers are about the night and day leading into this morning, the " +
                                "same attribution a WHOOP export uses, so logged and imported days " +
                                "line up."
                    },
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                catalog.forEach { q ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            q,   // data, not a UI literal — rendered verbatim
                            style = NoopType.body,
                            color = Palette.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (editing) {
                            JournalRemoveButton(isCustom = isCustom(q)) { onRemoveQuestion(q) }
                        } else {
                            JournalChip("Yes", selected = answers[q] == true) {
                                if (answers[q] == true) onClear(q) else onAnswer(q, true)
                            }
                            Spacer(Modifier.width(6.dp))
                            JournalChip("No", selected = answers[q] == false) {
                                if (answers[q] == false) onClear(q) else onAnswer(q, false)
                            }
                        }
                    }
                }
                // Hidden built-in questions — only while editing, each with a restore action.
                if (editing && hidden.isNotEmpty()) {
                    JournalDivider()
                    Text("Hidden", style = NoopType.caption, color = Palette.textTertiary)
                    hidden.forEach { q ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(q, style = NoopType.body, color = Palette.textTertiary, modifier = Modifier.weight(1f))
                            JournalChip("Restore", selected = false) { onRestoreQuestion(q) }
                        }
                    }
                }
                JournalDivider()
                var draft by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = {
                            Text("Add a custom question…", style = NoopType.body, color = Palette.textTertiary)
                        },
                        singleLine = true,
                        textStyle = NoopType.body,
                        colors = journalFieldColors(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    JournalChip("Add", selected = draft.isNotBlank()) {
                        val t = draft.trim()
                        if (t.isNotEmpty()) {
                            onAddCustom(t)
                            draft = ""
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(Palette.hairline),
    )
}

/** A pill chip — filled with the accent when selected, hairline-bordered otherwise. Shared by the
 *  day toggle, the yes/no answers, and the "Add" action so they read identically. */
@Composable
private fun JournalChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = NoopType.caption,
        color = if (selected) Palette.surfaceBase else Palette.textSecondary,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Palette.accent else Palette.surfaceInset)
            .border(1.dp, if (selected) Palette.accent else Palette.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** Edit-mode remove control: "Delete" for a custom question, "Hide" for a built-in one. Red-tinted
 *  text chip so it reads as removal; the label is self-describing for accessibility. */
@Composable
private fun JournalRemoveButton(isCustom: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        if (isCustom) "Delete" else "Hide",
        style = NoopType.caption,
        color = Palette.statusCritical,
        modifier = Modifier
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.statusCritical.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun journalFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.textPrimary,
    unfocusedTextColor = Palette.textPrimary,
    disabledTextColor = Palette.textTertiary,
    cursorColor = Palette.accent,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.hairline,
    disabledBorderColor = Palette.hairline,
    focusedContainerColor = Palette.surfaceInset,
    unfocusedContainerColor = Palette.surfaceInset,
    disabledContainerColor = Palette.surfaceInset,
)
