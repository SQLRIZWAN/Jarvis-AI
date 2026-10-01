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
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.engine.HistoryStore
import com.sqlai.assistant.engine.Speaker
import com.sqlai.assistant.service.ListeningService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SqlAiApp.settings.settings.collectAsState(initial = null)

    var wakeWord by remember { mutableStateOf("sql") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!loaded) {
            wakeWord = s.wakeWord
            loaded = true
        }
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

        // ------------------------------------------------------ wake word
        SectionCard(title = "Wake word") {
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
        }

        // ----------------------------------------------------- behaviour
        SectionCard(title = "Assistant behaviour") {
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
            ToggleRow(
                title = "Voice replies (TTS)",
                subtitle = "Speak the assistant's answer out loud",
                checked = settings?.ttsEnabled == true,
                onCheckedChange = { v -> scope.launch { SqlAiApp.settings.setTtsEnabled(v) } }
            )
            ToggleRow(
                title = "Live screen context",
                subtitle = "Send the current screen text to the AI for smarter actions",
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
        }

        // --------------------------------------------------------- voice
        SectionCard(title = "Voice output speed") {
            val speed = settings?.ttsSpeed ?: 1f
            Slider(
                value = speed,
                onValueChange = { v -> scope.launch { SqlAiApp.settings.setTtsSpeed(v) } },
                valueRange = 0.5f..2.0f
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${"%.1f".format(speed)}x", fontSize = 12.sp)
                OutlinedButton(onClick = { scope.launch { Speaker.speak("SQL AI is ready") } }) {
                    Text("Test voice")
                }
            }
        }

        // ------------------------------------------------------ system
        SectionCard(title = "System") {
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
                "SQL AI - open-source phone-control assistant (Jarvis-AI)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
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
