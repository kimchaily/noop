package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.noop.data.DailyMetric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Locale

// MARK: - Coach (desktop port of Android CoachScreen)
//
// AI Coach with bring-your-own-key: OpenAI / Anthropic / custom OpenAI-compatible local LLM.
// Two states: setup (no key) → chat (key saved). Uses java.net.http.HttpClient for API calls.
//
// Desktop differences from Android:
//  - java.net.http.HttpClient replaces OkHttp
//  - NoopPrefs (java.util.prefs) replaces SharedPreferences
//  - No liquid UI — plain NoopCard surfaces
//  - collectAsState() instead of collectAsStateWithLifecycle()

// MARK: - Types

enum class AiProvider(val displayName: String, val baseUrl: String, val modelsPath: String) {
    OPENAI("OpenAI", "https://api.openai.com", "/v1/models"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com", "/v1/models"),
    CUSTOM("Custom (Local LLM)", "", "/v1/models"),
}

data class ChatMsg(val role: String, val content: String)

// MARK: - CoachViewModel

class CoachViewModel(
    private val recentDays: kotlinx.coroutines.flow.StateFlow<List<DailyMetric>>,
    private val activeStrapId: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = HttpClient.newHttpClient()

    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _provider = MutableStateFlow(
        AiProvider.valueOf(NoopPrefs.getString("coach.provider", AiProvider.OPENAI.name) ?: AiProvider.OPENAI.name)
    )
    val provider = _provider.asStateFlow()

    private val _model = MutableStateFlow(NoopPrefs.getString("coach.model", "gpt-4o-mini") ?: "gpt-4o-mini")
    val model = _model.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels = _availableModels.asStateFlow()

    private val _refreshingModels = MutableStateFlow(false)
    val refreshingModels = _refreshingModels.asStateFlow()

    private val _customBaseUrl = MutableStateFlow(NoopPrefs.getString("coach.customUrl", "") ?: "")
    val customBaseUrl = _customBaseUrl.asStateFlow()

    private val _customConnected = MutableStateFlow(false)
    val customConnected = _customConnected.asStateFlow()

    private val _consent = MutableStateFlow(NoopPrefs.getBoolean("coach.consent", false))
    val consent = _consent.asStateFlow()

    private val _systemPrompt = MutableStateFlow(
        NoopPrefs.getString("coach.systemPrompt", null) ?: DEFAULT_SYSTEM_PROMPT
    )
    val systemPrompt = _systemPrompt.asStateFlow()

    val hasCustomPrompt: Boolean get() = NoopPrefs.getString("coach.systemPrompt", null) != null

    private val _keyVersion = MutableStateFlow(0)
    val keyVersion = _keyVersion.asStateFlow()

    fun isConfigured(): Boolean {
        val p = _provider.value
        if (p == AiProvider.CUSTOM) return _customConnected.value
        return hasKey()
    }

    fun hasKey(): Boolean = !NoopPrefs.getString("coach.apikey", null).isNullOrBlank()

    fun saveKey(key: String) {
        NoopPrefs.edit().putString("coach.apikey", key.trim()).apply()
        _keyVersion.value++
    }

    fun selectProvider(provider: AiProvider) {
        _provider.value = provider
        NoopPrefs.edit().putString("coach.provider", provider.name).apply()
    }

    fun selectModel(model: String) {
        _model.value = model
        NoopPrefs.edit().putString("coach.model", model).apply()
    }

    fun setCustomBaseUrl(url: String) {
        _customBaseUrl.value = url
        NoopPrefs.edit().putString("coach.customUrl", url).apply()
    }

    fun connectCustom() {
        val url = _customBaseUrl.value.trim()
        if (url.isBlank()) return
        scope.launch {
            _refreshingModels.value = true
            try {
                val models = fetchModels(url, null)
                _availableModels.value = models
                _customConnected.value = models.isNotEmpty()
                if (models.isNotEmpty() && _model.value !in models) {
                    selectModel(models.first())
                }
            } catch (e: Exception) {
                _error.value = "Could not connect: ${e.message}"
            } finally {
                _refreshingModels.value = false
            }
        }
    }

    fun disconnect() {
        NoopPrefs.edit().putString("coach.apikey", null).apply()
        _customConnected.value = false
        _messages.value = emptyList()
        _keyVersion.value++
    }

    fun refreshModels() {
        val p = _provider.value
        val baseUrl = if (p == AiProvider.CUSTOM) _customBaseUrl.value.trim() else p.baseUrl
        if (baseUrl.isBlank()) return
        val key = NoopPrefs.getString("coach.apikey", null)
        if (p != AiProvider.CUSTOM && key.isNullOrBlank()) return

        scope.launch {
            _refreshingModels.value = true
            try {
                val models = fetchModels(baseUrl, key)
                _availableModels.value = models
            } catch (e: Exception) {
                _error.value = "Could not fetch models: ${e.message}"
            } finally {
                _refreshingModels.value = false
            }
        }
    }

    fun setConsent(enabled: Boolean) {
        _consent.value = enabled
        NoopPrefs.edit().putBoolean("coach.consent", enabled).apply()
    }

    fun setSystemPrompt(prompt: String) {
        _systemPrompt.value = prompt
        if (prompt.trim() == DEFAULT_SYSTEM_PROMPT.trim()) {
            NoopPrefs.edit().putString("coach.systemPrompt", null).apply()
        } else {
            NoopPrefs.edit().putString("coach.systemPrompt", prompt).apply()
        }
    }

    fun resetSystemPrompt() {
        NoopPrefs.edit().putString("coach.systemPrompt", null).apply()
        _systemPrompt.value = DEFAULT_SYSTEM_PROMPT
    }

    fun clearError() { _error.value = null }

    fun send(input: String) {
        if (input.isBlank()) return
        val p = _provider.value
        val baseUrl = if (p == AiProvider.CUSTOM) _customBaseUrl.value.trim() else p.baseUrl
        val key = NoopPrefs.getString("coach.apikey", null)
        val model = _model.value
        val consent = _consent.value
        val sysPrompt = _systemPrompt.value
        val days = recentDays.value

        _messages.value = _messages.value + ChatMsg("user", input)
        _error.value = null
        _sending.value = true

        scope.launch {
            try {
                val fullSystemPrompt = buildSystemPrompt(sysPrompt, if (consent) days else emptyList())
                val response = callChatApi(baseUrl, key, model, fullSystemPrompt, _messages.value)
                _messages.value = _messages.value + ChatMsg("assistant", response)
            } catch (e: Exception) {
                _error.value = "Request failed: ${e.message}"
            } finally {
                _sending.value = false
            }
        }
    }

    private suspend fun fetchModels(baseUrl: String, key: String?): List<String> = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder()
            .uri(URI(baseUrl + AiProvider.OPENAI.modelsPath))
            .header("Content-Type", "application/json")
            .apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) return@withContext emptyList()
        parseModels(resp.body())
    }

    private fun parseModels(body: String): List<String> {
        return try {
            // Simple JSON parsing without a JSON library
            val regex = """"id"\s*:\s*"([^"]+)"""".toRegex()
            regex.findAll(body).map { it.groupValues[1] }.filter { !it.contains("whisper") && !it.contains("tts") && !it.contains("dall-e") }.toList()
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun callChatApi(
        baseUrl: String,
        key: String?,
        model: String,
        systemPrompt: String,
        history: List<ChatMsg>,
    ): String = withContext(Dispatchers.IO) {
        val messagesJson = buildString {
            append("""{"role":"system","content":${escapeJson(systemPrompt)}}""")
            history.forEach { msg ->
                append(",")
                append("""{"role":"${msg.role}","content":${escapeJson(msg.content)}}""")
            }
        }
        val body = """{"model":"$model","messages":[$messagesJson],"temperature":0.7,"max_tokens":1024}"""
        val req = HttpRequest.newBuilder()
            .uri(URI(baseUrl + "/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
        }
        extractContent(resp.body())
    }

    private fun extractContent(body: String): String {
        return try {
            val regex = """"content"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
            val match = regex.find(body)
            match?.groupValues?.let {
                // Check if this is the message content (first occurrence in choices)
                val grp = it[1]
                // The first "content" after "choices" is the assistant reply
                val choicesIdx = body.indexOf("\"choices\"")
                val contentIdx = body.indexOf("\"content\"", choicesIdx)
                if (contentIdx >= 0) {
                    val afterContent = body.substring(contentIdx)
                    val m2 = regex.find(afterContent)
                    m2?.groupValues?.get(1)
                } else grp
            }?.unescapeJson() ?: "No response content."
        } catch (e: Exception) {
            "Could not parse response."
        }
    }

    private fun buildSystemPrompt(base: String, days: List<DailyMetric>): String {
        if (days.isEmpty()) return base
        val sb = StringBuilder(base)
        sb.append("\n\n--- User's recent metrics (last 7 days, most recent first) ---")
        days.takeLast(7).reversed().forEach { d ->
            sb.append("\n${d.day}: ")
            val parts = mutableListOf<String>()
            d.recovery?.let { parts.add("Charge ${String.format(Locale.US, "%.0f%%", it)}") }
            d.strain?.let { parts.add("Effort ${String.format(Locale.US, "%.1f", it / 21.0 * 100)}%") }
            d.avgHrv?.let { parts.add("HRV ${String.format(Locale.US, "%.0f", it)}ms") }
            d.restingHr?.let { parts.add("RHR ${it}bpm") }
            d.totalSleepMin?.let { parts.add("Sleep ${String.format(Locale.US, "%.1f", it / 60.0)}h") }
            d.efficiency?.let { parts.add("Eff ${String.format(Locale.US, "%.0f%%", it)}") }
            if (parts.isNotEmpty()) sb.append(parts.joinToString(", "))
        }
        return sb.toString()
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun String.unescapeJson(): String =
        replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"").replace("\\\\", "\\")

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are NOOP Coach, an AI health and fitness coach integrated into the NOOP " +
            "strap companion app. You help the user understand their recovery, strain, sleep, " +
            "HRV and overall health based on their WHOOP strap data. Be concise, practical, " +
            "and encouraging. When the user shares metrics, reference them specifically. " +
            "If you don't have data, say so honestly."
    }
}

// MARK: - CoachScreen

@Composable
fun CoachScreen(viewModel: DesktopAppViewModel) {
    val coachVm = remember { CoachViewModel(viewModel.recentDays, viewModel.activeStrapId) }
    val keyVersion by coachVm.keyVersion.collectAsState()
    val provider by coachVm.provider.collectAsState()
    val customConnected by coachVm.customConnected.collectAsState()
    val configured = remember(keyVersion, provider, customConnected) { coachVm.isConfigured() }

    ScreenScaffold(
        title = "Coach",
        subtitle = "Ask about your recovery, strain, sleep and HRV, grounded in your own numbers.",
    ) {
        if (!configured) {
            CoachSetup(coachVm)
        } else {
            CoachChat(coachVm)
        }
    }
}

// MARK: - Setup

@Composable
private fun CoachSetup(vm: CoachViewModel) {
    val provider by vm.provider.collectAsState()
    val model by vm.model.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val refreshingModels by vm.refreshingModels.collectAsState()
    val customBaseUrl by vm.customBaseUrl.collectAsState()
    val error by vm.error.collectAsState()
    var keyInput by remember { mutableStateOf("") }
    val isCustom = provider == AiProvider.CUSTOM

    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
                Text("Connect a provider", style = NoopType.headline, color = Palette.textPrimary)
            }
            Text(
                if (isCustom)
                    "Point the coach at any OpenAI-compatible server: a local model (Ollama, LM " +
                        "Studio, llama.cpp) keeps everything on your device; an API key is optional."
                else
                    "Bring your own API key. It is stored on this device and only used to " +
                        "send your question plus a short summary of your metrics to the provider you pick.",
                style = NoopType.subhead, color = Palette.textSecondary,
            )

            // Provider choice
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Overline("Provider")
                SegmentedPillControl(
                    items = AiProvider.entries,
                    selection = provider,
                    label = { it.displayName },
                    onSelect = { vm.selectProvider(it) },
                )
            }

            // Server URL for Custom
            if (isCustom) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Overline("Server URL")
                    OutlinedTextField(
                        value = customBaseUrl,
                        onValueChange = { vm.setCustomBaseUrl(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("http://localhost:11434/v1", style = NoopType.body, color = Palette.textTertiary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            }

            // Model dropdown
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Overline("Model")
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { vm.refreshModels() },
                        enabled = !refreshingModels && (if (isCustom) customBaseUrl.isNotBlank() else vm.hasKey()),
                    ) {
                        if (refreshingModels) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.size(4.dp))
                        Text("Refresh", style = NoopType.footnote)
                    }
                }
                ModelDropdown(
                    models = availableModels,
                    selected = model,
                    onSelect = { vm.selectModel(it) },
                )
            }

            // API Key
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Overline(if (isCustom) "API Key (optional)" else "API Key")
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (isCustom) "Only if your server requires one" else "Paste your ${provider.displayName} key", style = NoopType.body, color = Palette.textTertiary) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(14.dp),
                )
            }

            // Connect / Save
            if (isCustom) {
                NoopButton(
                    label = "Connect",
                    enabled = customBaseUrl.isNotBlank(),
                    onClick = {
                        if (keyInput.isNotBlank()) vm.saveKey(keyInput)
                        vm.connectCustom()
                    },
                )
            } else {
                NoopButton(
                    label = "Save key",
                    enabled = keyInput.isNotBlank(),
                    onClick = { vm.saveKey(keyInput) },
                )
            }

            error?.let {
                Text(it, style = NoopType.footnote, color = Palette.statusCritical)
            }

            PrivacyNote(local = isCustom)
        }
    }
}

// MARK: - Chat

@Composable
private fun CoachChat(vm: CoachViewModel) {
    val messages by vm.messages.collectAsState()
    val sending by vm.sending.collectAsState()
    val error by vm.error.collectAsState()
    val provider by vm.provider.collectAsState()
    val model by vm.model.collectAsState()
    val consent by vm.consent.collectAsState()
    val systemPrompt by vm.systemPrompt.collectAsState()
    val hasCustom by remember { mutableStateOf(vm.hasCustomPrompt) }
    var input by remember { mutableStateOf("") }
    var promptExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Provider strip + disconnect
        NoopCard(padding = 14.dp, tint = Palette.chargeColor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatePill(title = "${provider.displayName} \u00b7 $model", tone = StrandTone.Accent, showsDot = true)
                Spacer(Modifier.weight(1f))
                Text(
                    "Disconnect",
                    style = NoopType.caption,
                    color = Palette.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { vm.disconnect() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // Consent toggle
        NoopCard(padding = 14.dp, tint = Palette.chargeColor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Let the coach use my data", style = NoopType.subhead, color = Palette.textPrimary)
                    Text(
                        if (consent) "On: your recovery, sleep, HRV and workouts are shared with the provider for tailored coaching."
                        else "Off: the coach answers generally and sends none of your metrics.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = consent,
                    onCheckedChange = { vm.setConsent(it) },
                )
            }
        }

        // System prompt editor (collapsible)
        NoopCard(padding = 14.dp, tint = Palette.chargeColor) {
            Column(verticalArrangement = Arrangement.spacedBy(if (promptExpanded) 10.dp else 0.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { promptExpanded = !promptExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Coach instructions", style = NoopType.subhead, color = Palette.textPrimary)
                        Text(
                            if (hasCustom) "Customised. Your edited instructions frame every reply."
                            else "Edit how the coach thinks and talks. Takes effect on your next message.",
                            style = NoopType.footnote, color = Palette.textTertiary,
                        )
                    }
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(20.dp))
                }
                if (promptExpanded) {
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { vm.setSystemPrompt(it) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp),
                        textStyle = NoopType.body,
                        singleLine = false,
                        shape = RoundedCornerShape(14.dp),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.resetSystemPrompt() }, enabled = hasCustom) {
                            Text("Reset to default", style = NoopType.footnote, color = if (hasCustom) Palette.accent else Palette.textTertiary)
                        }
                    }
                }
            }
        }

        // Transcript or empty state
        if (messages.isEmpty()) {
            NoopCard(padding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ask anything about your recent recovery, strain, sleep or HRV.", style = NoopType.subhead, color = Palette.textSecondary)
                    SuggestedPrompts(onPick = { input = it })
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 400.dp),
            ) {
                items(messages) { msg -> ChatBubble(msg) }
                if (sending) item { ThinkingBubble() }
            }
        }

        // Error
        error?.let {
            Text(it, style = NoopType.subhead, color = Palette.statusCritical)
        }

        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.surfaceOverlay)
                .border(1.dp, Palette.hairline, RoundedCornerShape(18.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    if (error != null) vm.clearError()
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask your coach\u2026", style = NoopType.body, color = Palette.textTertiary) },
                textStyle = NoopType.body,
                singleLine = false,
                maxLines = 4,
                enabled = !sending,
                shape = RoundedCornerShape(14.dp),
            )
            val sendEnabled = input.isNotBlank() && !sending
            val sendColor = if (sendEnabled) Palette.accent else Palette.textTertiary
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(sendColor.copy(alpha = if (sendEnabled) 0.15f else 0.05f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (sendEnabled) {
                            vm.send(input)
                            input = ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = sendColor)
                } else {
                    Icon(Icons.Filled.Send, contentDescription = "Send", tint = sendColor, modifier = Modifier.size(18.dp))
                }
            }
        }

        PrivacyNote(local = provider == AiProvider.CUSTOM)
    }
}

// MARK: - Components

@Composable
private fun ModelDropdown(models: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.surfaceInset)
                .border(1.dp, Palette.hairline, RoundedCornerShape(14.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (models.isEmpty()) selected else selected,
                style = NoopType.body,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Palette.textTertiary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (models.isEmpty()) {
                DropdownMenuItem(text = { Text(selected) }, onClick = { expanded = false })
            } else {
                models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m, style = NoopType.body) },
                        onClick = { onSelect(m); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMsg) {
    val isUser = msg.role == "user"
    val bg = if (isUser) Palette.accent.copy(alpha = 0.12f) else Palette.surfaceInset
    val align = if (isUser) Alignment.End else Alignment.Start
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(msg.content, style = NoopType.body, color = Palette.textPrimary)
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.surfaceInset)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("\u2026", style = NoopType.body, color = Palette.textTertiary)
        }
    }
}

@Composable
private fun SuggestedPrompts(onPick: (String) -> Unit) {
    val prompts = listOf(
        "How's my recovery today?",
        "What's my HRV trend?",
        "Am I overtraining?",
        "How can I improve my sleep?",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        prompts.forEach { p ->
            Text(
                p,
                style = NoopType.body,
                color = Palette.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(p) }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PrivacyNote(local: Boolean) {
    Text(
        if (local)
            "Your data stays on your device. The coach runs locally or on your own server."
        else
            "Your API key is stored on this device. Questions + a short metrics summary are sent to the provider you choose.",
        style = NoopType.footnote,
        color = Palette.textTertiary,
    )
}

@Composable
private fun NoopButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) Palette.accent else Palette.accent.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (enabled) onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = NoopType.body.copy(fontWeight = FontWeight.SemiBold), color = if (enabled) Color.White else Palette.textTertiary)
    }
}
