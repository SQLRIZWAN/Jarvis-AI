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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.core.CorePromptBuilder
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.ui.theme.SqlSuccess
import kotlinx.coroutines.launch

/**
 * Prompt tab v1.2: users only edit their PERSONAL CONTEXT (name, preferences,
 * custom style). The core system prompt - JSON schema, tool definitions and
 * reasoning rules - is immutable in app code (CorePromptBuilder) and shown
 * read-only here so it can never be edited or deleted by mistake.
 */
@Composable
fun PromptScreen() {
    val scope = rememberCoroutineScope()
    val settings by SqlAiApp.settings.settings.collectAsState(initial = null)

    var userName by remember { mutableStateOf("") }
    var preferences by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!loaded) {
            userName = s.userName
            preferences = s.userPreferences
            instructions = s.userInstructions
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
        Text("Memory & Prompt", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Tell SQL AI about yourself - the core brain stays protected",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )

        // ---------------------------------------------- protected core prompt
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = SqlSuccess,
                        modifier = Modifier.height(16.dp)
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(
                        "Core System Prompt - PROTECTED",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "v1.2",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "The JSON action schema, tool definitions and reasoning rules are " +
                        "hardcoded in the app and cannot be edited - so the agent can " +
                        "never break. Only your personal context below is appended at runtime.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append(CorePromptBuilder.CORE_AGENT.take(420))
                        append(" ...")
                    },
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 8
                )
            }
        }

        // ------------------------------------------------- editable context
        SectionCard(title = "My details (editable)") {
            OutlinedTextField(
                value = userName,
                onValueChange = {
                    userName = it
                    saved = false
                },
                label = { Text("What should I call you?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = preferences,
                onValueChange = {
                    preferences = it
                    saved = false
                },
                label = { Text("Personal preferences") },
                placeholder = { Text("e.g. Reply in short sentences, I work night shifts...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                minLines = 3
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = instructions,
                onValueChange = {
                    instructions = it
                    saved = false
                },
                label = { Text("Custom style instructions") },
                placeholder = { Text("e.g. Always greet me with 'Haanji', never use emojis") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                minLines = 3
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    scope.launch {
                        SqlAiApp.settings.setUserName(userName)
                        SqlAiApp.settings.setUserPreferences(preferences)
                        SqlAiApp.settings.setUserInstructions(instructions)
                        saved = true
                        LogBus.log("User memory saved", LogLevel.SUCCESS)
                    }
                }) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.height(16.dp))
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(if (saved) "Saved" else "Save memory")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "stored on device only",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (saved) {
                Text("Appended to the core prompt at runtime", fontSize = 11.sp, color = SqlSuccess)
            }
        }

        SectionCard(title = "How it works") {
            Text(
                "Every AI call = [Immutable Core Prompt] + [Your details] + [Language] + " +
                    "[Live screen]. Edit freely above - the brain of the agent is always safe.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}
