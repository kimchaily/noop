package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import kotlinx.coroutines.launch

/**
 * Devices — pair and manage the bands NOOP reads from. A thin UI over
 * [DeviceRegistry]: every mutation goes through a [DesktopAppViewModel]
 * registry op. The current device list is reloaded after each mutation.
 *
 * Adapted from the Android [DevicesScreen]:
 *  - `collectAsStateWithLifecycle` → `collectAsState`
 *  - `LocalContext` / `NoopPrefs.showDayCycleBackground(context)` → removed
 *  - Add-a-device wizard (BLE scanning) → simplified to a manual add dialog
 *  - `SourceCoordinator` references → removed (single-source desktop)
 *  - Liquid hero → flat [NoopCard]
 *  - BLE scanner factories → removed (desktop BLE is TBD)
 */
@Composable
fun DevicesScreen(
    viewModel: DesktopAppViewModel,
) {
    val scope = rememberCoroutineScope()
    val live by viewModel.live.collectAsState()

    var devices by remember { mutableStateOf<List<PairedDeviceRow>?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PairedDeviceRow?>(null) }
    var removeTarget by remember { mutableStateOf<PairedDeviceRow?>(null) }

    fun reload() {
        scope.launch { devices = viewModel.pairedDevices() }
    }
    LaunchedEffect(Unit) { reload() }

    val all = devices.orEmpty()

    LazyScreenScaffold(
        title = "Devices",
        subtitle = "${all.size} paired",
        trailing = {
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add device", tint = Palette.accent)
            }
        },
    ) {
        if (all.isEmpty()) {
            item {
                DataPendingNote(
                    title = "No devices paired",
                    body = "Add a WHOOP strap or import data to get started.",
                )
            }
            return@LazyScreenScaffold
        }

        item {
            SectionHeader("Active Device", overline = "Current")
            val active = all.firstOrNull { it.status == DeviceStatus.active.name }
            if (active != null) {
                NoopCard(padding = 20.dp, tint = Palette.accent) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.Watch, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(28.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                active.nickname ?: "${active.brand} ${active.model}",
                                style = NoopType.headline,
                                color = Palette.textPrimary,
                            )
                            Text(
                                "${active.brand} ${active.model}",
                                style = NoopType.footnote,
                                color = Palette.textTertiary,
                            )
                        }
                        if (live.connected) {
                            StatePill("Connected", tone = StrandTone.Positive, pulsing = true)
                        } else if (live.bonded) {
                            StatePill("Bonded", tone = StrandTone.Warning)
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("All Devices", overline = "${all.size} total")
        }
        item {
            NoopCard(padding = 0.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    all.forEachIndexed { i, row ->
                        DeviceRow(
                            row = row,
                            isActive = row.status == DeviceStatus.active.name,
                            onSetActive = {
                                scope.launch { viewModel.setActiveDevice(row.id); reload() }
                            },
                            onRename = { renameTarget = row },
                            onRemove = { removeTarget = row },
                        )
                        if (i < all.lastIndex) {
                            HorizontalDivider(color = Palette.hairline, modifier = Modifier.padding(start = 50.dp))
                        }
                    }
                }
            }
        }
    }

    // --- Add device dialog ---
    if (showAddDialog) {
        AddDeviceDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { id, brand, model, nickname ->
                scope.launch {
                    viewModel.addPairedDevice(
                        PairedDeviceRow(
                            id = id,
                            brand = brand,
                            model = model,
                            nickname = nickname,
                            sourceKind = "liveBLE",
                            capabilities = "hr,hrv,spo2,skinTemp,steps,sleep,strainLoad",
                            status = DeviceStatus.active.name,
                            addedAt = System.currentTimeMillis() / 1000,
                            lastSeenAt = System.currentTimeMillis() / 1000,
                        ),
                    )
                    viewModel.setActiveDevice(id)
                    reload()
                    showAddDialog = false
                }
            },
        )
    }

    // --- Rename dialog ---
    renameTarget?.let { row ->
        var nickname by remember(row.id) { mutableStateOf(row.nickname ?: "") }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { androidx.compose.material3.Text("Rename device", style = NoopType.title2, color = Palette.textPrimary) },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { androidx.compose.material3.Text("Nickname") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.renamePairedDevice(row.id, nickname.ifBlank { null })
                        reload()
                    }
                    renameTarget = null
                }) { androidx.compose.material3.Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { androidx.compose.material3.Text("Cancel") }
            },
        )
    }

    // --- Remove confirmation ---
    removeTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { androidx.compose.material3.Text("Remove device", style = NoopType.title2, color = Palette.textPrimary) },
            text = {
                androidx.compose.material3.Text(
                    "Archive ${row.nickname ?: "${row.brand} ${row.model}"}? Its recorded data is kept.",
                    style = NoopType.body,
                    color = Palette.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.archivePairedDevice(row.id)
                        reload()
                    }
                    removeTarget = null
                }) { androidx.compose.material3.Text("Archive") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { androidx.compose.material3.Text("Cancel") }
            },
        )
    }
}

// MARK: - Device row

@Composable
private fun DeviceRow(
    row: PairedDeviceRow,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (isActive) Icons.Filled.Bluetooth else Icons.Filled.Sensors,
            contentDescription = null,
            tint = if (isActive) Palette.accent else Palette.textTertiary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.nickname ?: "${row.brand} ${row.model}",
                style = NoopType.body.copy(fontWeight = FontWeight.SemiBold),
                color = Palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${row.brand} ${row.model} · ${row.status}",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
        if (!isActive && row.status != DeviceStatus.archived.name) {
            TextButton(onClick = onSetActive) {
                androidx.compose.material3.Text("Set Active")
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Palette.textSecondary)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { androidx.compose.material3.Text("Rename") },
                    onClick = { menuExpanded = false; onRename() },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { androidx.compose.material3.Text("Archive") },
                    onClick = { menuExpanded = false; onRemove() },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                )
            }
        }
    }
}

// MARK: - Add device dialog

@Composable
private fun AddDeviceDialog(
    onDismiss: () -> Unit,
    onAdd: (id: String, brand: String, model: String, nickname: String?) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("WHOOP") }
    var model by remember { mutableStateOf("4.0") }
    var nickname by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Add Device", style = NoopType.title2, color = Palette.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { androidx.compose.material3.Text("Device ID") }, singleLine = true)
                OutlinedTextField(value = brand, onValueChange = { brand = it }, label = { androidx.compose.material3.Text("Brand") }, singleLine = true)
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { androidx.compose.material3.Text("Model") }, singleLine = true)
                OutlinedTextField(value = nickname, onValueChange = { nickname = it }, label = { androidx.compose.material3.Text("Nickname (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(id.ifBlank { "manual-${System.currentTimeMillis()}" }, brand, model, nickname.ifBlank { null }) },
                enabled = brand.isNotBlank() && model.isNotBlank(),
            ) { androidx.compose.material3.Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Cancel") }
        },
    )
}
