package com.sqlai.assistant.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sqlai.assistant.BuildConfig
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.AssistantLanguage
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.PermissionHelper
import com.sqlai.assistant.core.VoiceGender
import com.sqlai.assistant.engine.HistoryStore
import com.sqlai.assistant.engine.Speaker
import com.sqlai.assistant.service.ListeningService
import com.sqlai.assistant.ui.theme.SqlError
import com.sqlai.assistant.ui.theme.SqlSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenApi: () -> Unit = {},
    onOpenAccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = AppCrashHandler.safeScope(rememberCoroutineScope())
    val settings by SqlAiApp.settings.settings.collectAsState(initial = null)

    var wakeWord by remember { mutableStateOf("sql") }
    var autoReplyTemplate by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    var genderMenuOpen by remember { mutableStateOf(false) }
    var languageMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!loaded) {
            wakeWord = s.wakeWord
            autoReplyTemplate = s.autoReplyTemplate
            loaded = true
        }
    }

    var permTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            permTick++
            delay(2500)
        }
    }
    val statuses = remember(permTick, settings) {
        PermissionHelper.statuses(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // ------------------------------------------- voice & language (v6)
        SectionCard(title = "Voice & Language") {
            GroupLabel("Wake word")
            OutlinedTextField(
                value = wakeWord,
                onValueChange = {
                    wakeWord = it
                    scope.launch { SqlAiApp.settings.setWakeWord(it) }
                },
                label = { Text("Say this before every command") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Example: \"${wakeWord.ifBlank { "sql" }} open whatsapp\"",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(Modifier.height(10.dp))
            ToggleRow(
                title = "Offline wake word (Vosk)",
                subtitle = "On-device detection - works without internet, auto-fallback",
                checked = settings?.wakeEngine != "speech",
                onCheckedChange = { v ->
                    scope.launch { SqlAiApp.settings.setWakeEngine(if (v) "vosk" else "speech") }
                }
            )
            if (settings?.wakeEngine != "speech") {
                ToggleRow(
                    title = "Hindi wake model",
                    subtitle = "Use the Hindi acoustic model for the wake word",
                    checked = settings?.wakeModelLang == "hi",
                    onCheckedChange = { v ->
                        scope.launch { SqlAiApp.settings.setWakeModelLang(if (v) "hi" else "en") }
                    }
                )
            }

            GroupLabel("Language (commands + replies)")
            ExposedDropdownMenuBox(
                expanded = languageMenuOpen,
                onExpandedChange = { languageMenuOpen = it }
            ) {
                OutlinedTextField(
                    value = settings?.language?.label ?: AssistantLanguage.ENGLISH.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Assistant language") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuOpen)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = languageMenuOpen,
                    onDismissRequest = { languageMenuOpen = false }
                ) {
                    AssistantLanguage.entries.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.label) },
                            onClick = {
                                languageMenuOpen = false
                                scope.launch { SqlAiApp.settings.setLanguage(lang) }
                                LogBus.log("Language: ${lang.label}", LogLevel.SUCCESS)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { scope.launch { Speaker.speak("Namaste! SQL AI taiyaar hai.") } }) {
                    Text("Test voice")
                }
                OutlinedButton(onClick = {
                    ListeningService.start(context)
                    LogBus.log("Listening restarted (language applied)")
                }) {
                    Text("Apply to mic")
                }
            }


            GroupLabel("Voice & replies")
            ExposedDropdownMenuBox(
                expanded = genderMenuOpen,
                onExpandedChange = { genderMenuOpen = it }
            ) {
                OutlinedTextField(
                    value = settings?.voiceGender?.label ?: VoiceGender.MALE.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Voice gender") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = genderMenuOpen)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = genderMenuOpen,
                    onDismissRequest = { genderMenuOpen = false }
                ) {
                    VoiceGender.entries.forEach { gender ->
                        DropdownMenuItem(
                            text = { Text(gender.label) },
                            onClick = {
                                genderMenuOpen = false
                                scope.launch { SqlAiApp.settings.setVoiceGender(gender) }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            ToggleRow(
                title = "Gemini Live voice (native audio)",
                subtitle = "Natural human voice streamed from Gemini instead of Android TTS",
                checked = settings?.geminiLiveVoice == true,
                onCheckedChange = { v ->
                    scope.launch { SqlAiApp.settings.setGeminiLiveVoice(v) }
                }
            )
            if (settings?.geminiLiveVoice == true) {
                if (settings?.provider == com.sqlai.assistant.core.AiProvider.GEMINI &&
                    settings?.apiKey?.isNotBlank() == true
                ) {
                    Text(
                        "Live voice model: ${settings?.liveModel}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(
                        "Live voice needs the Google Gemini provider + API key. " +
                            "Falls back to Android TTS otherwise.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                LiveVoicePicker(
                    selected = settings?.liveVoiceName.orEmpty(),
                    gender = settings?.voiceGender ?: com.sqlai.assistant.core.VoiceGender.MALE,
                    onSelect = { voice ->
                        scope.launch { SqlAiApp.settings.setLiveVoiceName(voice) }
                    }
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Pitch: ${"%.2f".format(settings?.pitch ?: 1f)}",
                fontSize = 12.sp
            )
            Slider(
                value = settings?.pitch ?: 1f,
                onValueChange = { v -> scope.launch { SqlAiApp.settings.setPitch(v) } },
                valueRange = 0.5f..2.0f
            )

            Text(
                "Speed: ${"%.1f".format(settings?.ttsSpeed ?: 1f)}x",
                fontSize = 12.sp
            )
            Slider(
                value = settings?.ttsSpeed ?: 1f,
                onValueChange = { v -> scope.launch { SqlAiApp.settings.setTtsSpeed(v) } },
                valueRange = 0.5f..2.0f
            )

            Spacer(Modifier.height(6.dp))
            ToggleRow(
                title = "Voice replies (TTS)",
                subtitle = "Speak the assistant's answer out loud",
                checked = settings?.ttsEnabled == true,
                onCheckedChange = { v -> scope.launch { SqlAiApp.settings.setTtsEnabled(v) } }
            )
        }

        // ------------------------------------------ call & auto-reply (v6)
        SectionCard(title = "Call & Auto-Reply") {
            ToggleRow(
                title = "WhatsApp / SMS auto-reply",
                subtitle = "Reply automatically to incoming messages",
                checked = settings?.autoReplyEnabled == true,
                onCheckedChange = { v ->
                    scope.launch { SqlAiApp.settings.setAutoReplyEnabled(v) }
                }
            )
            if (settings?.autoReplyEnabled == true) {
                OutlinedTextField(
                    value = autoReplyTemplate,
                    onValueChange = {
                        autoReplyTemplate = it
                        scope.launch { SqlAiApp.settings.setAutoReplyTemplate(it) }
                    },
                    label = { Text("Fallback reply (when AI is offline)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Needs Notification Access + Accessibility ON.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(Modifier.height(8.dp))

            ToggleRow(
                title = "Call assistant (live voice on call)",
                subtitle = "Auto-receive calls, speak + listen during the call",
                checked = settings?.callAssistantEnabled == true,
                onCheckedChange = { v ->
                    scope.launch { SqlAiApp.settings.setCallAssistantEnabled(v) }
                }
            )
            if (settings?.callAssistantEnabled == true) {
                Text(
                    "Needs Phone + Answer calls permissions. Also answers WhatsApp calls " +
                        "by tapping the Answer button.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(Modifier.height(8.dp))

            ToggleRow(
                title = "24/7 listening service",
                subtitle = "Keep the microphone service alive in background",
                checked = settings?.listenServiceEnabled == true,
                onCheckedChange = { v ->
                    scope.launch {
                        SqlAiApp.settings.setListenServiceEnabled(v)
                        if (v) ListeningService.start(context) else ListeningService.stop(context)
                    }
                }
            )
        }

        // ---------------------------------------- api & model config (v6)
        SectionCard(title = "API & Model Config") {
            ConfigRow("Provider", settings?.provider?.label ?: "-")
            ConfigRow("Chat model", settings?.model?.ifBlank { "-" } ?: "-")
            ConfigRow(
                "API key",
                settings?.apiKey?.takeIf { it.isNotBlank() }
                    ?.let { "set (\u2026${it.takeLast(4)})" } ?: "not set"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenApi,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Edit provider / key / model")
            }
        }

        // ------------------------------------------ permissions check (v6)
        SectionCard(title = "Permissions Check") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                statuses.forEach { perm ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(perm.title, fontSize = 13.sp)
                        Text(
                            if (perm.granted) "Granted" else "Missing",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (perm.granted) SqlSuccess else SqlError
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenAccess,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Access tab")
            }
        }

        // ---------------------------------------------------- system (v6)
        SectionCard(title = "System") {
            GroupLabel("Agent (vision)")
            ToggleRow(
                title = "Screen vision (screenshot)",
                subtitle = "Let the AI actually SEE the screen (Gemini/vision models)",
                checked = settings?.screenVisionEnabled == true,
                onCheckedChange = { v ->
                    scope.launch { SqlAiApp.settings.setScreenVisionEnabled(v) }
                }
            )
            Text(
                "Unlimited steps - the Think -> Act -> Verify loop keeps running " +
                    "until the task is 100% verified complete.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )

            GroupLabel("Automation")
            ToggleRow(
                title = "Live screen context",
                subtitle = "Send the current screen text to the AI",
                checked = settings?.screenContextEnabled == true,
                onCheckedChange = { v -> scope.launch { SqlAiApp.settings.setScreenContextEnabled(v) } }
            )
            ToggleRow(
                title = "Floating overlay bubble",
                subtitle = "Show status above other apps (needs overlay permission)",
                checked = settings?.overlayEnabled == true,
                onCheckedChange = { v -> scope.launch { SqlAiApp.settings.setOverlayEnabled(v) } }
            )
            ToggleRow(
                title = "Restart after reboot",
                subtitle = "Auto-start listening when the phone restarts",
                checked = settings?.bootRestartEnabled == true,
                onCheckedChange = { v -> scope.launch { SqlAiApp.settings.setBootRestartEnabled(v) } }
            )

            GroupLabel("Shortcuts")
            SettingsLinkRow("Battery optimization", onClick = {
                try {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                } catch (e: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            })
            SettingsLinkRow("Notification access", onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            SettingsLinkRow("Accessibility", onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            SettingsLinkRow("Default assistant", onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                } catch (e: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            })
            SettingsLinkRow("All files access", onClick = {
                try {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                } catch (e: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            })

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    HistoryStore.clear()
                    LogBus.clear()
                    LogBus.log("Conversation memory cleared", LogLevel.WARN)
                }
            }) {
                Text("Clear conversation memory")
            }
        }

        // ------------------------------------------------------- about
        SectionCard(title = "About") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Version", fontSize = 13.sp)
                Text(BuildConfig.VERSION_NAME, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Text(
                "SQL AI v6.0 Pro - Agentic phone-control assistant (Jarvis-AI)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** v6.0 - small bold sub-heading inside a settings card. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

/** v6.0 - read-only "label : value" row for the API summary card. */
@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsLinkRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, fontSize = 13.5.sp)
        Button(onClick = onClick) {
            Text("Open", fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveVoicePicker(
    selected: String,
    gender: com.sqlai.assistant.core.VoiceGender,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val auto = if (gender == com.sqlai.assistant.core.VoiceGender.FEMALE) {
        "Kore (female, auto)"
    } else {
        "Puck (male, auto)"
    }
    Spacer(Modifier.height(8.dp))
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = selected.ifBlank { auto },
            onValueChange = {},
            readOnly = true,
            label = { Text("Gemini Live voice") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                "" to auto,
                "Puck" to "Puck (male, upbeat)",
                "Charon" to "Charon (male, low)",
                "Fenrir" to "Fenrir (male, firm)",
                "Kore" to "Kore (female)",
                "Aoede" to "Aoede (female, breezy)",
                "Leda" to "Leda (female, youth)",
                "Orus" to "Orus (male, firm)"
            ).forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontSize = 14.sp) },
                    onClick = {
                        open = false
                        onSelect(id)
                    }
                )
            }
        }
    }
}
