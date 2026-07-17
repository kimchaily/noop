package com.noop.analytics

import com.noop.data.DayOwnershipRow
import com.noop.data.DeviceRegistry
import com.noop.data.DeviceRegistryDao
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.protocol.DeviceFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 1B-4 owner read-through, mirroring the Swift IntelligenceEngine I2 test (two devices share a
 * day → the day is read from exactly ONE owner). This exercises the pure, Room-free seam: the
 * [RegistryDayOwnerSource] priority mapping + the [DayOwnerResolver] it feeds. The full analyzeRecent
 * read-through is the same `DayOwnerResolver.resolve(...)` over these candidates (see IntelligenceEngine
 * .resolveDayOwner), so resolving to one owner here is what makes the engine read one source for the day.
 *
 * No Robolectric: a [DeviceRegistry] over an in-memory fake DAO (same pattern as DeviceRegistryTest).
 */
class RegistryDayOwnerSourceTest {

    /** Minimal in-memory DeviceRegistryDao — only the methods this test exercises hold state. */
    private class FakeDao : DeviceRegistryDao {
        val devices = LinkedHashMap<String, PairedDeviceRow>()
        val owners = LinkedHashMap<String, DayOwnershipRow>()
        override suspend fun pairedDevices() = devices.values.sortedBy { it.addedAt }
        override suspend fun activeDeviceId() =
            devices.values.firstOrNull { it.status == DeviceStatus.active.name }?.id
        override suspend fun upsertPairedDevice(row: PairedDeviceRow) { devices[row.id] = row }
        override suspend fun demoteActive() {
            for ((id, r) in devices) if (r.status == DeviceStatus.active.name)
                devices[id] = r.copy(status = DeviceStatus.paired.name)
        }
        override suspend fun promote(id: String, now: Long) {
            devices[id]?.let { devices[id] = it.copy(status = DeviceStatus.active.name, lastSeenAt = now) }
        }
        override suspend fun archiveDevice(id: String) {
            devices[id]?.let { devices[id] = it.copy(status = DeviceStatus.archived.name) }
        }
        override suspend fun renameDevice(id: String, nickname: String?) {}
        override suspend fun setPeripheralId(id: String, peripheralId: String?) {
            devices[id]?.let { devices[id] = it.copy(peripheralId = peripheralId) }
        }
        override suspend fun deviceForPeripheralId(peripheralId: String): PairedDeviceRow? =
            devices.values.firstOrNull { it.peripheralId == peripheralId }
        override suspend fun setDayOwner(row: DayOwnershipRow) { owners[row.day] = row }
        override suspend fun dayOwner(day: String) = owners[day]
        override suspend fun deleteHrFor(deviceId: String) {}
        override suspend fun deleteRrFor(deviceId: String) {}
        override suspend fun deleteSpo2For(deviceId: String) {}
        override suspend fun deleteSkinTempFor(deviceId: String) {}
        override suspend fun deleteRespFor(deviceId: String) {}
        override suspend fun deleteGravityFor(deviceId: String) {}
        override suspend fun deleteStepsFor(deviceId: String) {}
        override suspend fun deletePpgHrFor(deviceId: String) {}
        override suspend fun deleteEventsFor(deviceId: String) {}
        override suspend fun deleteBatteryFor(deviceId: String) {}
        override suspend fun deleteDailyMetricsFor(deviceId: String) {}
        override suspend fun deleteSleepSessionsFor(deviceId: String) {}
        override suspend fun deleteJournalFor(deviceId: String) {}
        override suspend fun deleteWorkoutsFor(deviceId: String) {}
        override suspend fun deleteAppleDailyFor(deviceId: String) {}
        override suspend fun deleteMetricSeriesFor(deviceId: String) {}
        override suspend fun deleteDayOwnershipFor(deviceId: String) {}
    }

    private fun registry(dao: FakeDao) = DeviceRegistry(
        dao,
        object : DeviceRegistry.Transactor {
            override suspend fun <R> run(block: suspend () -> R): R = block()
        },
    )

    private fun device(id: String, brand: String, kind: SourceKind, status: DeviceStatus) =
        PairedDeviceRow(
            id = id, brand = brand, model = brand, nickname = null,
            sourceKind = kind.name, capabilities = "hr,hrv",
            status = status.name, addedAt = 100, lastSeenAt = 100,
        )

    /** Resolve an owner the way IntelligenceEngine.resolveDayOwner does, given which devices have data. */
    private suspend fun resolveWith(
        src: IntelligenceEngine.DayOwnerSource,
        day: String,
        dataByDevice: Map<String, Boolean>,
    ): String? {
        src.lockedOwner(day)?.let { return it }
        val candidates = src.candidatePriorities().map { (id, priority) ->
            DayOwnerResolver.Candidate(id, priority, hasData = dataByDevice[id] ?: false)
        }
        return DayOwnerResolver.resolve(day, lockedOwner = null, candidates = candidates)
    }

    @Test
    fun twoDevicesOneDayResolvesToTheActiveStrapOnly() = runBlocking {
        val dao = FakeDao().apply {
            // Active live strap (priority 0) + an import (priority 2) — both have data for the day.
            devices["my-whoop"] = device("my-whoop", "WHOOP", SourceKind.liveBLE, DeviceStatus.active)
            devices["oura"] = device("oura", "Oura", SourceKind.cloudImport, DeviceStatus.paired)
        }
        val src = RegistryDayOwnerSource(registry(dao))

        // Priorities: active strap 0, import 2.
        val priorities = src.candidatePriorities().toMap()
        assertEquals(0, priorities["my-whoop"])
        assertEquals(2, priorities["oura"])

        // Both have data → exactly ONE owner, the active strap (so the engine reads only its streams).
        val owner = resolveWith(src, "2026-06-15", mapOf("my-whoop" to true, "oura" to true))
        assertEquals("my-whoop", owner)
    }

    @Test
    fun importFillsTheDayWhenTheStrapHasNoData() = runBlocking {
        val dao = FakeDao().apply {
            devices["my-whoop"] = device("my-whoop", "WHOOP", SourceKind.liveBLE, DeviceStatus.active)
            devices["oura"] = device("oura", "Oura", SourceKind.cloudImport, DeviceStatus.paired)
        }
        val src = RegistryDayOwnerSource(registry(dao))
        // Strap collected nothing this day → the import (only candidate with data) owns it.
        val owner = resolveWith(src, "2026-06-15", mapOf("my-whoop" to false, "oura" to true))
        assertEquals("oura", owner)
    }

    @Test
    fun lockedOwnerWinsOutright() = runBlocking {
        val dao = FakeDao().apply {
            devices["my-whoop"] = device("my-whoop", "WHOOP", SourceKind.liveBLE, DeviceStatus.active)
            devices["oura"] = device("oura", "Oura", SourceKind.cloudImport, DeviceStatus.paired)
            owners["2026-06-15"] = DayOwnershipRow("2026-06-15", "oura", locked = true)
        }
        val src = RegistryDayOwnerSource(registry(dao))
        // Even though the active strap has data, the locked override pins the day to the import.
        val owner = resolveWith(src, "2026-06-15", mapOf("my-whoop" to true, "oura" to true))
        assertEquals("oura", owner)
    }

    /** A paired device with an explicit `model` (the [device] helper forces model == brand). */
    private fun deviceWithModel(id: String, brand: String, model: String, kind: SourceKind) =
        PairedDeviceRow(
            id = id, brand = brand, model = model, nickname = null,
            sourceKind = kind.name, capabilities = "hr,hrv,skinTemp",
            status = DeviceStatus.paired.name, addedAt = 100, lastSeenAt = 100,
        )

    /**
     * #938: skinTempFamily resolves the raw→°C scale from the registry model, and now returns
     * [DeviceFamily]? — null means "the registry genuinely doesn't know", NOT "assume WHOOP5". The Android
     * wizard persists the SHORT label "4.0"/"5.0 MG" (never the "WHOOP 4.0" displayName the old exact match
     * required), so both label forms must resolve; the bare seeded "WHOOP", an Oura "Ring 4", and an id
     * absent from the (frequently EMPTY on a `.noopbak` import) registry must surface null so
     * [IntelligenceEngine.analyzeRecent] falls back to its data-driven inference instead of a silent guess.
     */
    @Test
    fun skinTempFamilyResolvesConfidentLabelsAndNullsTheRest() = runBlocking {
        val dao = FakeDao().apply {
            devices["whoop-aa"] = deviceWithModel("whoop-aa", "WHOOP", "4.0", SourceKind.liveBLE)
            devices["whoop-parity"] = deviceWithModel("whoop-parity", "WHOOP", "WHOOP 4.0", SourceKind.liveBLE)
            devices["whoop-bb"] = deviceWithModel("whoop-bb", "WHOOP", "5.0 MG", SourceKind.liveBLE)
            devices["whoop-full5"] = deviceWithModel("whoop-full5", "WHOOP", "WHOOP 5.0 / MG", SourceKind.liveBLE)
            devices["my-whoop"] = deviceWithModel("my-whoop", "WHOOP", "WHOOP", SourceKind.liveBLE)
            devices["oura4"] = deviceWithModel("oura4", "Oura", "Oura Ring 4", SourceKind.cloudImport)
        }
        val src = RegistryDayOwnerSource(registry(dao))
        // Both 4.0 label forms → WHOOP4; both 5/MG forms → WHOOP5.
        assertEquals(DeviceFamily.WHOOP4, src.skinTempFamily("whoop-aa"))
        assertEquals(DeviceFamily.WHOOP4, src.skinTempFamily("whoop-parity"))
        assertEquals(DeviceFamily.WHOOP5, src.skinTempFamily("whoop-bb"))
        assertEquals(DeviceFamily.WHOOP5, src.skinTempFamily("whoop-full5"))
        // Ambiguous seed, an Oura Ring 4 (a "4" but no ".0"), and an unknown/absent id → null (defer to
        // the engine's data-driven inference), never a guessed default.
        assertNull(src.skinTempFamily("my-whoop"))
        assertNull(src.skinTempFamily("oura4"))
        assertNull(src.skinTempFamily("not-in-registry"))
    }

    @Test
    fun archivedDeviceIsNotACandidate() = runBlocking {
        val dao = FakeDao().apply {
            devices["my-whoop"] = device("my-whoop", "WHOOP", SourceKind.liveBLE, DeviceStatus.active)
            devices["old"] = device("old", "Polar", SourceKind.liveBLE, DeviceStatus.archived)
        }
        val src = RegistryDayOwnerSource(registry(dao))
        val ids = src.candidatePriorities().map { it.first }
        assertEquals(listOf("my-whoop"), ids) // archived 'old' excluded
        // With only the active strap and it having NO data, there is no owner (honest gap).
        assertNull(resolveWith(src, "2026-06-15", mapOf("my-whoop" to false)))
    }
}
