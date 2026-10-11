package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.NoopApplication
import com.noop.data.BackupExport
import com.noop.data.BackupImport
import java.io.File

/**
 * Settings — theme switching, unit system, appearance mode, profile editor, backup
 * import/export, CSV export, and about info.
 *
 * Adapted from the Android [SettingsScreen]:
 *  - `LocalContext` / `SharedPreferences` → [NoopPrefs]
 *  - `Intent` / `Uri` / `PackageManager` → `java.awt.Desktop` for opening URLs
 *  - `rememberLauncherForActivityResult` / SAF → `java.awt.FileDialog` for backup import/export
 *  - `DataBackup` → [BackupImport] (import) + [BackupExport] (export)
 *  - `RawSensorExport` → [com.noop.data.RawSensorExport] (CSV export)
 *  - `BuildConfig` → hardcoded version string
 *  - Profile editor (age/sex/weight/height) → [ProfileStore] (java.util.prefs)
 */
@Composable
fun SettingsScreen(
    viewModel: DesktopAppViewModel,
    dbPath: String = "",
    onReinit: () -> Unit = {},
) {
    val themeFamily = remember { ThemePrefs.family }
    val appearanceMode = remember { AppearancePrefs.mode }
    val chartStyle = remember { ChartStylePrefs.style }
    val unitSystem = remember { UnitPrefs.system() }
    val tempUnit = remember { UnitPrefs.temperature() }
    val effortScale = remember { UnitPrefs.effortScale() }

    // Local mutable mirrors so toggles feel live.
    var currentTheme by remember { mutableStateOf(themeFamily) }
    var currentAppearance by remember { mutableStateOf(appearanceMode) }
    var currentChartStyle by remember { mutableStateOf(chartStyle) }
    var currentUnitSystem by remember { mutableStateOf(unitSystem) }
    var currentTempUnit by remember { mutableStateOf(tempUnit) }
    var currentEffortScale by remember { mutableStateOf(effortScale) }

    // Backup import/export state.
    var importStatus by remember { mutableStateOf<ImportStatus>(ImportStatus.Idle) }
    var exportStatus by remember { mutableStateOf<ExportStatus>(ExportStatus.Idle) }
    var csvExportStatus by remember { mutableStateOf<CsvExportStatus>(CsvExportStatus.Idle) }

    // Profile store (age/sex/weight/height).
    val profile = remember { ProfileStore.create() }
    var profileAge by remember { mutableStateOf(profile.age.toString()) }
    var profileSex by remember { mutableStateOf(profile.sex) }
    var profileWeight by remember { mutableStateOf(profile.weightKg.toString()) }
    var profileHeight by remember { mutableStateOf(profile.heightCm.toString()) }
    var profileHrMax by remember { mutableStateOf(profile.hrMaxOverride.toString()) }

    ScreenScaffold(
        title = "Settings",
        subtitle = "Customise your NOOP experience",
    ) {
        // --- Profile ---
        SectionHeader("Profile", overline = "User")
        NoopCard(padding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingRow(
                    icon = Icons.Filled.Person,
                    title = "Body Profile",
                    subtitle = "Drives HR zones, calorie estimates, and recovery baselines.",
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = profileAge,
                        onValueChange = { profileAge = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Age") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = profileWeight,
                        onValueChange = { profileWeight = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                        label = { Text("Weight (kg)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = profileHeight,
                        onValueChange = { profileHeight = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                        label = { Text("Height (cm)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = profileHrMax,
                        onValueChange = { profileHrMax = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("HR Max (0=auto)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf("male", "female", "nonbinary").forEach { s ->
                        ThemeChip(
                            label = s.replaceFirstChar { it.uppercase() },
                            selected = profileSex == s,
                            onClick = { profileSex = s; profile.sex = s },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                NoopButton(
                    text = "Save Profile",
                    kind = NoopButtonKind.Primary,
                    fullWidth = true,
                    onClick = {
                        profile.age = profileAge.toIntOrNull() ?: ProfileStore.DEFAULT_AGE
                        profile.sex = profileSex
                        profile.weightKg = profileWeight.toDoubleOrNull() ?: ProfileStore.DEFAULT_WEIGHT_KG
                        profile.heightCm = profileHeight.toDoubleOrNull() ?: ProfileStore.DEFAULT_HEIGHT_CM
                        profile.hrMaxOverride = profileHrMax.toIntOrNull() ?: 0
                    },
                )
                Text(
                    "HR Max: ${profile.hrMax} bpm" +
                        if (profile.hrMaxOverride > 0) " (manual)" else " (Tanaka auto, age ${profile.age})",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }

        // --- Appearance ---
        SectionHeader("Appearance", overline = "Theme")
        NoopCard(padding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingRow(
                    icon = Icons.Filled.Palette,
                    title = "Theme Family",
                    subtitle = currentTheme.label,
                )
                // Theme picker
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppTheme.entries.forEach { theme ->
                        ThemeChip(
                            label = theme.label,
                            selected = currentTheme == theme,
                            onClick = {
                                currentTheme = theme
                                ThemePrefs.set(theme)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                HorizontalDivider(color = Palette.hairline)

                SettingRow(
                    icon = Icons.Filled.Brightness6,
                    title = "Appearance",
                    subtitle = currentAppearance.label,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppearanceMode.entries.forEach { mode ->
                        ThemeChip(
                            label = mode.label,
                            selected = currentAppearance == mode,
                            onClick = {
                                currentAppearance = mode
                                AppearancePrefs.set(mode)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                HorizontalDivider(color = Palette.hairline)

                SettingRow(
                    icon = Icons.Filled.Palette,
                    title = "Chart Style",
                    subtitle = currentChartStyle.label,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ChartStyle.entries.forEach { style ->
                        ThemeChip(
                            label = style.label,
                            selected = currentChartStyle == style,
                            onClick = {
                                currentChartStyle = style
                                ChartStylePrefs.set(style)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // --- Units ---
        SectionHeader("Units", overline = "Display")
        NoopCard(padding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingRow(
                    icon = Icons.Filled.Straighten,
                    title = "Length & Mass",
                    subtitle = if (currentUnitSystem == UnitSystem.IMPERIAL) "Imperial (mi / lb)" else "Metric (km / kg)",
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    UnitSystem.entries.forEach { system ->
                        ThemeChip(
                            label = if (system == UnitSystem.IMPERIAL) "Imperial" else "Metric",
                            selected = currentUnitSystem == system,
                            onClick = {
                                currentUnitSystem = system
                                NoopPrefs.setUnitSystem(system)
                                // If no explicit temperature override, follow the system default.
                                currentTempUnit = system.temperatureMatching
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                HorizontalDivider(color = Palette.hairline)

                SettingRow(
                    icon = Icons.Filled.Straighten,
                    title = "Temperature",
                    subtitle = if (currentTempUnit == TemperatureUnit.FAHRENHEIT) "Fahrenheit" else "Celsius",
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TemperatureUnit.entries.forEach { unit ->
                        ThemeChip(
                            label = if (unit == TemperatureUnit.FAHRENHEIT) "°F" else "°C",
                            selected = currentTempUnit == unit,
                            onClick = {
                                currentTempUnit = unit
                                NoopPrefs.setTemperatureUnit(unit)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                HorizontalDivider(color = Palette.hairline)

                SettingRow(
                    icon = Icons.Filled.Sensors,
                    title = "Effort Scale",
                    subtitle = if (currentEffortScale == EffortScale.WHOOP) "WHOOP (0-21)" else "NOOP (0-100)",
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    EffortScale.entries.forEach { scale ->
                        ThemeChip(
                            label = if (scale == EffortScale.WHOOP) "0-21" else "0-100",
                            selected = currentEffortScale == scale,
                            onClick = {
                                currentEffortScale = scale
                                UnitPrefs.setEffortScale(scale)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // --- Data ---
        SectionHeader("Data", overline = "Backup")
        NoopCard(padding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingRow(
                    icon = Icons.Filled.CloudUpload,
                    title = "Import Backup",
                    subtitle = "Restore from a .noopbak file exported on iOS, macOS, or Android.",
                )
                ImportBackupButton(
                    status = importStatus,
                    onImport = { file ->
                        importStatus = ImportStatus.Working
                        // Run the import on a background thread so the UI stays responsive
                        // (extracting a 1 GB+ ZIP + copying the SQLite is multi-second).
                        Thread {
                            try {
                                // Close the DB so file handles are released before the swap.
                                NoopApplication.shutdown()
                                val result = BackupImport.import(file, dbPath)
                                when (result) {
                                    is BackupImport.Result.Success -> {
                                        // Reopen against the new file + rebuild the ViewModel.
                                        NoopApplication.init(dbPath)
                                        importStatus = ImportStatus.Success(result.rowsImported)
                                        onReinit()
                                    }
                                    is BackupImport.Result.Failure -> {
                                        // Reopen the original DB (the swap may have failed).
                                        NoopApplication.init(dbPath)
                                        importStatus = ImportStatus.Error(result.message)
                                    }
                                    BackupImport.Result.Cancelled -> {
                                        NoopApplication.init(dbPath)
                                        importStatus = ImportStatus.Idle
                                    }
                                }
                            } catch (e: Exception) {
                                try { NoopApplication.init(dbPath) } catch (_: Exception) {}
                                importStatus = ImportStatus.Error(e.message ?: "Unknown error")
                            }
                        }.start()
                    },
                )
                HorizontalDivider(color = Palette.hairline)
                SettingRow(
                    icon = Icons.Filled.CloudDownload,
                    title = "Export Backup",
                    subtitle = "Save a .noopbak file with all your data.",
                )
                ExportBackupButton(
                    status = exportStatus,
                    onExport = { destFile ->
                        exportStatus = ExportStatus.Working
                        Thread {
                            try {
                                NoopApplication.shutdown()
                                val result = BackupExport.export(dbPath, destFile)
                                NoopApplication.init(dbPath)
                                exportStatus = when (result) {
                                    is BackupExport.Result.Success -> ExportStatus.Success(result.filePath, result.sizeBytes)
                                    is BackupExport.Result.Failure -> ExportStatus.Error(result.message)
                                    BackupExport.Result.Cancelled -> ExportStatus.Idle
                                }
                                onReinit()
                            } catch (e: Exception) {
                                try { NoopApplication.init(dbPath) } catch (_: Exception) {}
                                exportStatus = ExportStatus.Error(e.message ?: "Unknown error")
                            }
                        }.start()
                    },
                )
                HorizontalDivider(color = Palette.hairline)
                SettingRow(
                    icon = Icons.Filled.TableChart,
                    title = "Export CSV",
                    subtitle = "Export raw sensor data (last 24h) to a CSV file for analysis.",
                )
                CsvExportButton(
                    status = csvExportStatus,
                    onExport = { destFile ->
                        csvExportStatus = CsvExportStatus.Working
                        Thread {
                            try {
                                val deviceId = viewModel.activeStrapId
                                val now = System.currentTimeMillis() / 1000
                                val lo = now - 24 * 3600L
                                val counts = com.noop.data.RawSensorExport.export(
                                    destFile, viewModel.repository, deviceId, lo, now,
                                )
                                csvExportStatus = CsvExportStatus.Success(counts)
                            } catch (e: Exception) {
                                csvExportStatus = CsvExportStatus.Error(e.message ?: "Unknown error")
                            }
                        }.start()
                    },
                )
                HorizontalDivider(color = Palette.hairline)
                SettingRow(
                    icon = Icons.Filled.Info,
                    title = "Storage",
                    subtitle = "All data is stored locally on this device.",
                )
            }
        }

        // --- About ---
        SectionHeader("About", overline = "Info")
        NoopCard(padding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingRow(
                    icon = Icons.Filled.Info,
                    title = "Version",
                    subtitle = "NOOP Desktop v8.2.2",
                )
                HorizontalDivider(color = Palette.hairline)
                SettingRow(
                    icon = Icons.Filled.Sensors,
                    title = "Active Device",
                    subtitle = viewModel.activeStrapId,
                )
                HorizontalDivider(color = Palette.hairline)
                SettingRow(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    title = "Data",
                    subtitle = "All data is stored locally on this device.",
                )
            }
        }
    }
}

// MARK: - Import status

/** State of the backup-import flow, surfaced in the Settings card. */
private sealed class ImportStatus {
    /** No import in flight. */
    object Idle : ImportStatus()
    /** Import is running (ZIP extraction + file copy). */
    object Working : ImportStatus()
    /** Import succeeded; [rows] is the per-table row count from the backup. */
    data class Success(val rows: Map<String, Int>) : ImportStatus()
    /** Import failed; [message] is user-facing. */
    data class Error(val message: String) : ImportStatus()
}

// MARK: - Import backup button

/**
 * The import trigger: a clickable row that opens a native file dialog filtered to `.noopbak`
 * files, then runs [BackupImport.import] on a background thread. The [status] drives the
 * button's label and any result / error message shown below it.
 */
@Composable
private fun ImportBackupButton(
    status: ImportStatus,
    onImport: (File) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoopButton(
            text = when (status) {
                ImportStatus.Working -> "Importing…"
                else -> "Select .noopbak file"
            },
            leadingIcon = Icons.Filled.CloudUpload,
            kind = if (status is ImportStatus.Error) NoopButtonKind.Destructive else NoopButtonKind.Secondary,
            fullWidth = true,
            enabled = status !is ImportStatus.Working,
            onClick = {
                val file = pickNoopbakFile() ?: return@NoopButton
                onImport(file)
            },
        )

        when (status) {
            ImportStatus.Working -> {
                Text(
                    "Extracting and validating the backup…",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            is ImportStatus.Success -> {
                val summary = formatImportSummary(status.rows)
                Text(
                    "Import complete! $summary",
                    style = NoopType.footnote,
                    color = Palette.chargeColor,
                )
            }
            is ImportStatus.Error -> {
                Text(
                    "Import failed: ${status.message}",
                    style = NoopType.footnote,
                    color = Palette.statusCritical,
                )
            }
            ImportStatus.Idle -> {}
        }
    }
}

/**
 * Open a native file dialog filtered to `.noopbak` files. Returns the chosen file or null
 * if the user cancelled. Runs on the Swing EDT (blocking) — called from a Compose click
 * handler which already runs on the main thread.
 */
private fun pickNoopbakFile(): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Import NOOP Backup", java.awt.FileDialog.LOAD)
    dialog.filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".noopbak") || name.endsWith(".sqlite") }
    dialog.file = "*.noopbak"
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir as String, file as String).takeIf { it.exists() }
}

/** Format the per-table row counts into a compact one-line summary. */
private fun formatImportSummary(rows: Map<String, Int>): String {
    if (rows.isEmpty()) return "Database restored."
    val parts = mutableListOf<String>()
    val hr = rows["hrSample"] ?: 0
    val daily = rows["dailyMetric"] ?: 0
    val sleep = rows["sleepSession"] ?: 0
    val workout = rows["workout"] ?: 0
    if (hr > 0) parts.add("$hr HR samples")
    if (daily > 0) parts.add("$daily daily metrics")
    if (sleep > 0) parts.add("$sleep sleep sessions")
    if (workout > 0) parts.add("$workout workouts")
    return parts.joinToString(", ") + "."
}

// MARK: - Setting row

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = NoopType.body.copy(fontWeight = FontWeight.SemiBold), color = Palette.textPrimary)
            Text(subtitle, style = NoopType.footnote, color = Palette.textTertiary)
        }
        if (trailing != null) trailing()
    }
}

// MARK: - Theme chip

@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) Palette.accent.copy(alpha = 0.12f) else Palette.surfaceInset
    val border = if (selected) Palette.accent.copy(alpha = 0.55f) else Palette.hairline
    val color = if (selected) Palette.accent else Palette.textSecondary
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(bg)
            .androidx_border(1.dp, border)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, style = NoopType.captionNumber, color = color)
    }
}

// Helper to avoid naming clash with Modifier.border
private fun Modifier.androidx_border(width: androidx.compose.ui.unit.Dp, color: androidx.compose.ui.graphics.Color): Modifier =
    this.border(width, color, androidx.compose.foundation.shape.RoundedCornerShape(50))

// MARK: - Export backup

/** State of the backup-export flow. */
private sealed class ExportStatus {
    object Idle : ExportStatus()
    object Working : ExportStatus()
    data class Success(val filePath: String, val sizeBytes: Long) : ExportStatus()
    data class Error(val message: String) : ExportStatus()
}

@Composable
private fun ExportBackupButton(
    status: ExportStatus,
    onExport: (File) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoopButton(
            text = when (status) {
                ExportStatus.Working -> "Exporting…"
                else -> "Export .noopbak file"
            },
            leadingIcon = Icons.Filled.CloudDownload,
            kind = NoopButtonKind.Secondary,
            fullWidth = true,
            enabled = status !is ExportStatus.Working,
            onClick = {
                val file = saveNoopbakFile() ?: return@NoopButton
                onExport(file)
            },
        )
        when (status) {
            ExportStatus.Working -> {
                Text(
                    "Checkpointing the database and compressing…",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            is ExportStatus.Success -> {
                val sizeStr = humanReadableSize(status.sizeBytes)
                Text(
                    "Backup saved! ($sizeStr) — ${File(status.filePath).name}",
                    style = NoopType.footnote,
                    color = Palette.chargeColor,
                )
            }
            is ExportStatus.Error -> {
                Text(
                    "Export failed: ${status.message}",
                    style = NoopType.footnote,
                    color = Palette.statusCritical,
                )
            }
            ExportStatus.Idle -> {}
        }
    }
}

/** Open a native save dialog for `.noopbak` files. Returns the chosen file or null if cancelled. */
private fun saveNoopbakFile(): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Export NOOP Backup", java.awt.FileDialog.SAVE)
    dialog.file = "noop-backup-${java.time.LocalDate.now()}.noopbak"
    dialog.filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".noopbak") }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir as String, file as String)
}

// MARK: - CSV export

/** State of the CSV-export flow. */
private sealed class CsvExportStatus {
    object Idle : CsvExportStatus()
    object Working : CsvExportStatus()
    data class Success(val counts: Map<String, Int>) : CsvExportStatus()
    data class Error(val message: String) : CsvExportStatus()
}

@Composable
private fun CsvExportButton(
    status: CsvExportStatus,
    onExport: (File) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoopButton(
            text = when (status) {
                CsvExportStatus.Working -> "Exporting CSV…"
                else -> "Export raw sensor CSV"
            },
            leadingIcon = Icons.Filled.TableChart,
            kind = NoopButtonKind.Secondary,
            fullWidth = true,
            enabled = status !is CsvExportStatus.Working,
            onClick = {
                val file = saveCsvFile() ?: return@NoopButton
                onExport(file)
            },
        )
        when (status) {
            CsvExportStatus.Working -> {
                Text(
                    "Querying raw sensor tables and writing CSV…",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            is CsvExportStatus.Success -> {
                val total = status.counts.values.sum()
                Text(
                    "CSV exported! $total samples across ${status.counts.size} streams.",
                    style = NoopType.footnote,
                    color = Palette.chargeColor,
                )
            }
            is CsvExportStatus.Error -> {
                Text(
                    "CSV export failed: ${status.message}",
                    style = NoopType.footnote,
                    color = Palette.statusCritical,
                )
            }
            CsvExportStatus.Idle -> {}
        }
    }
}

/** Open a native save dialog for `.csv` files. */
private fun saveCsvFile(): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Export Raw Sensor CSV", java.awt.FileDialog.SAVE)
    dialog.file = "noop-sensors-${java.time.LocalDate.now()}.csv"
    dialog.filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".csv") }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir as String, file as String)
}

/** Format a byte count into a human-readable string (e.g. "12.3 MB"). */
private fun humanReadableSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
