package com.sqlai.assistant.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.ui.theme.SqlSuccess
import kotlinx.coroutines.launch

@Composable
fun PromptScreen() {
    val scope = rememberCoroutineScope()
    val settings by SqlAiApp.settings.settings.collectAsState(initial = null)

    var prompt by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!loaded) {
            prompt = s.systemPrompt
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
        Text("System Prompt", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Tune how SQL AI thinks, speaks and which actions it is allowed to take",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )

        SectionCard(title = "Behaviour & personality") {
            OutlinedTextField(
                value = prompt,
                onValueChange = {
                    prompt = it
                    saved = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp
                )
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    scope.launch {
                        SqlAiApp.settings.setSystemPrompt(prompt)
                        saved = true
                        LogBus.log("System prompt updated", LogLevel.SUCCESS)
                    }
                }) {
                    Text(if (saved) "Saved" else "Save prompt")
                }
                Spacer(Modifier.padding(horizontal = 6.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        SqlAiApp.settings.resetPrompt()
                        prompt = com.sqlai.assistant.core.AppSettings.DEFAULT_SYSTEM_PROMPT
                        saved = true
                        LogBus.log("System prompt reset to default", LogLevel.WARN)
                    }
                }) {
                    Text("Reset")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${prompt.length} chars",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (saved) {
                Text("Stored on device", fontSize = 11.sp, color = SqlSuccess)
            }
        }

        SectionCard(title = "Tips") {
            Text(
                "Keep the JSON action contract intact or the assistant will not be able to " +
                    "control the phone. You can change tone, language, safety rules and the " +
                    "wake-word behaviour from here.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}
