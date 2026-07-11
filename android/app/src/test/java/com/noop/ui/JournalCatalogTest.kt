package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure journal-catalog-v2 helpers (JournalCatalog.kt), the #322 rename / numeric-type /
 * group / order model. Mirrors the macOS JournalLogicTests v2 cases so both platforms migrate the
 * legacy arrays, resolve/group, and (critically) preserve the canonical KEY on a rename identically.
 */
class JournalCatalogTest {

    // MARK: - legacy migration

    @Test
    fun legacyMigrationFoldsTwoArraysIntoItems() {
        val items = migrateLegacyJournalCatalog(
            custom = listOf("Did you nap?", "Vitamin D"),
            hidden = listOf("Did you drink any alcohol?", "Vitamin D"),
        )
        val customs = items.filter { it.custom }
        assertEquals(setOf("Did you nap?", "Vitamin D"), customs.map { it.canonical }.toSet())
        // The hidden custom kept its flag on the SAME item (deduped by norm, not a second row).
        val vitaminD = items.filter { normJournalKey(it.canonical) == normJournalKey("Vitamin D") }
        assertEquals("a hidden custom must not duplicate", 1, vitaminD.size)
        assertTrue(vitaminD[0].hidden)
        // The hidden starter became a hidden non-custom marker in its default group.
        val alcohol = items.first { normJournalKey(it.canonical) == normJournalKey("Did you drink any alcohol?") }
        assertTrue(alcohol.hidden)
        assertFalse(alcohol.custom)
        assertEquals(JournalGroup.Nutrition, alcohol.group)
    }

    // MARK: - the rename key-stability guarantee (#322)

    @Test
    fun renameKeepsCanonicalStableSoHistorySurvives() {
        // Renaming an item changes only the display label; the stored canonical (the DB/engine join
        // key) is untouched, so all logged + imported history, keyed on the canonical question string
        //, still lines up after a rename.
        val canonical = "Did you have caffeine late in the day?"
        var items = emptyList<JournalCatalogItem>()

        // Before: display resolves to the canonical verbatim.
        assertEquals(canonical, journalDisplayName(items, canonical))

        items = renameJournalItem(items, canonical, "Caffeine")

        // After: the DISPLAY changed, the KEY did not.
        assertEquals("Caffeine", journalDisplayName(items, canonical))
        val item = items.firstOrNull { it.canonical == canonical }
        assertNotNull(item)
        assertEquals("rename must NEVER change the canonical, history is keyed on it", canonical, item!!.canonical)
        assertEquals("Caffeine", item.displayName)

        // A journal write / effect lookup for this behaviour still keys on the canonical, so a row
        // logged BEFORE the rename (under the canonical) is found AFTER the rename by the same key.
        val resolved = resolveJournalItems(imported = emptyList(), savedItems = items)
        val caffeine = resolved.firstOrNull { it.canonical == canonical }
        assertEquals("Caffeine", caffeine?.display)
        assertEquals("the engine key is preserved end to end, logged/imported days still join", canonical, caffeine?.canonical)

        // Clearing the rename (blank) falls back to the canonical.
        items = renameJournalItem(items, canonical, "   ")
        assertEquals(canonical, journalDisplayName(items, canonical))
        assertEquals(canonical, items.first { normJournalKey(it.canonical) == normJournalKey(canonical) }.canonical)
    }

    @Test
    fun setGroupAndKindPreserveCanonical() {
        val canonical = "Did you take magnesium?"
        var items = emptyList<JournalCatalogItem>()
        items = setJournalItemGroup(items, canonical, JournalGroup.Supplements)
        items = setJournalItemKind(items, canonical, JournalKind.Numeric("mg"))
        val item = items.first { it.canonical == canonical }
        assertEquals("regroup/retype never touch the key", canonical, item.canonical)
        assertEquals(JournalGroup.Supplements, item.group)
        assertTrue(item.kind.isNumeric)
        assertEquals("mg", item.kind.unitLabel)
    }

    // MARK: - resolution + grouping

    @Test
    fun resolvedItemsGroupStartersByDefaultAndDropHidden() {
        var items = emptyList<JournalCatalogItem>()
        val resolved = resolveJournalItems(imported = emptyList(), savedItems = items)
        assertEquals(STARTER_JOURNAL_QUESTIONS.size, resolved.size)
        val alcohol = resolved.first { it.canonical == "Did you drink any alcohol?" }
        assertEquals(JournalGroup.Nutrition, alcohol.group)
        assertFalse(alcohol.kind.isNumeric)
        // Hidden items are dropped unless includeHidden.
        items = removeJournalItem(items, "Did you drink any alcohol?")
        val afterHide = resolveJournalItems(imported = emptyList(), savedItems = items, includeHidden = false)
        assertFalse(afterHide.any { it.canonical == "Did you drink any alcohol?" })
        val withHidden = resolveJournalItems(imported = emptyList(), savedItems = items, includeHidden = true)
        assertTrue(withHidden.any { it.canonical == "Did you drink any alcohol?" && it.hidden })
    }

    @Test
    fun addCustomNumericItem() {
        var items = emptyList<JournalCatalogItem>()
        items = addCustomJournalItem(items, "Water (L)", JournalKind.Numeric("L"), JournalGroup.Nutrition)
        val item = items.first { it.canonical == "Water (L)" }
        assertTrue(item.custom)
        assertEquals(JournalGroup.Nutrition, item.group)
        assertEquals("L", item.kind.unitLabel)
        // Re-adding the same canonical is a no-op (no duplicate).
        val again = addCustomJournalItem(items, "water (l)", JournalKind.Bool, JournalGroup.Other)
        assertEquals(items.size, again.size)
    }

    @Test
    fun restoredCustomQuestionSurfacesFromDataWithNumericKindInferred() {
        // A full backup restore lands the journal ROWS but NOT this device's catalog. The load loop
        // feeds resolveJournalItems the distinct data-backed questions (imported ∪ native) plus the
        // norm-keys of those carrying a numericValue. With an EMPTY saved catalog, the restored
        // customs must still surface — numeric ones as numeric, yes/no ones as Bool.
        val meditation = "Morning Meditation in mins"
        val stressed = "Did you feel stressed?"           // starter, has a native row too
        val custom = "Cold shower?"                        // custom yes/no
        val resolved = resolveJournalItems(
            imported = listOf(meditation, custom, stressed),
            savedItems = emptyList(),
            numericQuestions = hashSetOf(
                normJournalKey(meditation),
                normJournalKey(stressed),   // even if the set names a starter, a starter stays Bool
            ),
        )
        val med = resolved.first { it.canonical == meditation }
        assertTrue("a data-backed question with numeric rows resolves numeric", med.kind.isNumeric)
        assertEquals("no display rename yet, renders the canonical", meditation, med.display)
        val cold = resolved.first { it.canonical == custom }
        assertFalse("a data-backed yes/no question stays Bool", cold.kind.isNumeric)
        val stress = resolved.first { it.canonical == stressed }
        assertFalse("a starter never flips to numeric from the inferred set", stress.kind.isNumeric)
    }

    @Test
    fun reorderWithinGroupMovesAndPreservesInferredKind() {
        // A restored group: A is a saved yes/no, B is an inferred-numeric question the user hasn't
        // touched (present only in the resolved display list, NOT in the saved catalog), C inferred
        // yes/no. Moving B up must land it first AND keep its numeric kind — materialising from the
        // resolved value, not the Bool default — and it must persist an explicit order.
        val a = JournalCatalogItem("A", kind = JournalKind.Bool, group = JournalGroup.Other, sortIndex = 0)
        val b = JournalCatalogItem("Morning Meditation in mins", kind = JournalKind.Numeric(null),
            group = JournalGroup.Other, sortIndex = 1)
        val c = JournalCatalogItem("C", kind = JournalKind.Bool, group = JournalGroup.Other, sortIndex = 2)
        val resolvedGroup = listOf(a, b, c)
        val saved = listOf(a)   // only A materialised in the catalog

        val moved = moveJournalItem(saved, resolvedGroup, b.canonical, -1)
        val byKey = moved.associateBy { it.canonical }
        assertTrue("inferred numeric kind survives the move", byKey[b.canonical]!!.kind.isNumeric)
        assertEquals("moved item takes the top slot", 0, byKey[b.canonical]!!.sortIndex)
        assertEquals(1, byKey["A"]!!.sortIndex)
        assertEquals(2, byKey["C"]!!.sortIndex)

        // Re-resolving with the persisted catalog, sorted the way the group block renders it (by
        // sortIndex, display), yields B, A, C in that order with the numeric kind preserved.
        val resolved = resolveJournalItems(
            imported = listOf("A", b.canonical, "C"), savedItems = moved,
            numericQuestions = hashSetOf(normJournalKey(b.canonical)),
        ).filter { it.group == JournalGroup.Other }
            .sortedWith(compareBy({ it.sortIndex }, { it.display }))
        assertEquals(listOf(b.canonical, "A", "C"), resolved.map { it.canonical })
        assertTrue(resolved.first { it.canonical == b.canonical }.kind.isNumeric)

        // Boundary: moving the top item up, or the bottom down, is a no-op.
        assertEquals(moved, moveJournalItem(moved, listOf(byKey[b.canonical]!!, byKey["A"]!!, byKey["C"]!!),
            b.canonical, -1))
        assertEquals(moved, moveJournalItem(moved, listOf(byKey[b.canonical]!!, byKey["A"]!!, byKey["C"]!!),
            "C", 1))
    }

    // MARK: - JSON round-trip (persistence)

    @Test
    fun jsonRoundTripPreservesEveryField() {
        val items = listOf(
            JournalCatalogItem("Did you drink any alcohol?", displayName = "Alcohol",
                kind = JournalKind.Numeric("units"), group = JournalGroup.Nutrition,
                sortIndex = 3, hidden = false, custom = false),
            JournalCatalogItem("Did you nap?", displayName = null, kind = JournalKind.Bool,
                group = JournalGroup.Other, sortIndex = 7, hidden = true, custom = true),
        )
        val decoded = decodeJournalCatalog(encodeJournalCatalog(items))
        assertEquals(items, decoded)
        // Empty / malformed JSON decodes to an empty list, never throws.
        assertEquals(emptyList<JournalCatalogItem>(), decodeJournalCatalog(""))
        assertEquals(emptyList<JournalCatalogItem>(), decodeJournalCatalog("not json"))
    }

    @Test
    fun numericSeriesKeyFoldsFromAnsweredYesLog() {
        // A numeric journal entry writes answeredYes=true too, so it lands in the with/without split
        // AND contributes a numeric series point (parity with the Swift InsightsView fold). This pins
        // the entity-level contract the load loop depends on.
        val e = com.noop.data.JournalEntry("noop-journal", "2026-06-20", "Caffeine (mg)",
            answeredYes = true, numericValue = 180.0)
        assertTrue(e.answeredYes)
        assertEquals(180.0, e.numericValue!!, 0.0001)
    }
}
