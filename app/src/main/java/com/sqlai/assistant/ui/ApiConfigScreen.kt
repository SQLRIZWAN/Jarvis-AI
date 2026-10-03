package com.sqlai.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.AiClient
import com.sqlai.assistant.ai.AiException
import com.sqlai.assistant.ai.GeminiModel
import com.sqlai.assistant.ai.GeminiModelFetcher
import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.AiProvider
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.ui.theme.SqlError
import com.sqlai.assistant.ui.theme.SqlSuccess
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConfigScreen() {
    val scope = AppCrashHandler.safeScope(rememberCoroutineScope())
    val settings by SqlAiApp.settings.settings.collectAsState(initial = null)

    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // ---- Gemini dynamic model list ----
    var geminiModels by remember { mutableStateOf<List<GeminiModel>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var autoFetchDone by remember { mutableStateOf(false) }

    val currentProviderValue = settings?.provider ?: AiProvider.GROQ

    // Auto-pick the newest flash model when the saved one is blank/unknown.
    fun autoSelectGeminiModel(fetched: List<GeminiModel>) {
        if (fetched.isEmpty()) return
        if (model.isNotBlank() && fetched.any { it.id == model }) return
        val best = GeminiModelFetcher.pickBestFlash(fetched) ?: return
        model = best
        scope.launch { SqlAiApp.settings.setModel(best) }
    }

    LaunchedEffect(currentProviderValue, settings?.apiKey) {
        val key = settings?.apiKey.orEmpty()
        if (currentProviderValue == AiProvider.GEMINI &&
            key.length >= 20 && !autoFetchDone && geminiModels.isEmpty()
        ) {
            autoFetchDone = true
            fetchingModels = true
            try {
                geminiModels = GeminiModelFetcher.fetch(key)
                modelFetchError = null
                autoSelectGeminiModel(geminiModels)
                LogBus.log("Loaded ${geminiModels.size} Gemini models", LogLevel.SUCCESS)
            } catch (e: Exception) {
                modelFetchError = e.message
                LogBus.log("Gemini model fetch failed: ${e.message}", LogLevel.WARN)
            }
            fetchingModels = false
        }
    }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!loaded) {
            apiKey = s.apiKey
            model = s.model
            baseUrl = s.baseUrlOverride
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
        Text("API Provider", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "All providers below have a free tier - paste your key and go",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )

        SectionCard(title = "Provider") {
            val currentProvider = settings?.provider ?: AiProvider.GROQ
            ExposedDropdownMenuBox(
                expanded = providerMenuOpen,
                onExpandedChange = { providerMenuOpen = it }
            ) {
                OutlinedTextField(
                    value = currentProvider.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("LLM Provider") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuOpen)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = providerMenuOpen,
                    onDismissRequest = { providerMenuOpen = false }
                ) {
                    AiProvider.entries.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.label) },
                            onClick = {
                                providerMenuOpen = false
                                scope.launch {
                                    SqlAiApp.settings.setProvider(provider)
                                    SqlAiApp.settings.setModel(provider.defaultModel)
                                    model = provider.defaultModel
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    scope.launch { SqlAiApp.settings.setApiKey(it) }
                },
                label = { Text("API Key") },
                placeholder = { Text(settings?.provider?.keyHint ?: "gsk_...") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = "Toggle key visibility"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    scope.launch { SqlAiApp.settings.setModel(it) }
                },
                label = { Text("Model") },
                placeholder = { Text(settings?.provider?.defaultModel ?: "") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ---------------- Gemini: live model dropdown ----------------
            if (currentProviderValue == AiProvider.GEMINI) {
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            fetchingModels = true
                            scope.launch {
                                try {
                                    geminiModels = GeminiModelFetcher.fetch(apiKey)
                                    modelFetchError = null
                                    autoSelectGeminiModel(geminiModels)
                                    LogBus.log(
                                        "Loaded ${geminiModels.size} Gemini models",
                                        LogLevel.SUCCESS
                                    )
                                } catch (e: Exception) {
                                    modelFetchError = e.message
                                    LogBus.log("Model fetch failed: ${e.message}", LogLevel.ERROR)
                                }
                                fetchingModels = false
                            }
                        },
                        enabled = !fetchingModels && apiKey.isNotBlank()
                    ) {
                        Text(
                            if (geminiModels.isEmpty()) "Fetch available models"
                            else "Refresh models (${geminiModels.size})"
                        )
                    }
                    if (fetchingModels) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(
                            Modifier
                                .width(16.dp)
                                .height(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                if (geminiModels.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = modelMenuOpen,
                        onExpandedChange = { modelMenuOpen = it }
                    ) {
                        OutlinedTextField(
                            value = geminiModels.firstOrNull { it.id == model }?.displayName
                                ?: model,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Select Gemini model") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuOpen)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = modelMenuOpen,
                            onDismissRequest = { modelMenuOpen = false }
                        ) {
                            geminiModels.forEach { geminiModel ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(geminiModel.displayName, fontSize = 14.sp)
                                            Text(
                                                geminiModel.id,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    },
                                    onClick = {
                                        modelMenuOpen = false
                                        model = geminiModel.id
                                        scope.launch { SqlAiApp.settings.setModel(geminiModel.id) }
                                        LogBus.log("Gemini model: ${geminiModel.id}", LogLevel.SUCCESS)
                                    }
                                )
                            }
                        }
                    }
                }

                modelFetchError?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message,
                        fontSize = 11.sp,
                        color = SqlError
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    scope.launch { SqlAiApp.settings.setBaseUrl(it) }
                },
                label = { Text("Base URL override (optional)") },
                placeholder = { Text("leave empty for default") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val s = settings ?: return@launch
                            testResult = try {
                                val reply = AiClient.test(s)
                                LogBus.log("API test OK: $reply", LogLevel.SUCCESS)
                                true to "Connected: $reply"
                            } catch (e: AiException) {
                                LogBus.log("API test failed: ${e.message}", LogLevel.ERROR)
                                false to e.message.orEmpty()
                            } catch (e: Exception) {
                                LogBus.log("API test failed: ${e.message}", LogLevel.ERROR)
                                false to (e.message ?: "Unknown error")
                            }
                            testing = false
                        }
                    },
                    enabled = !testing
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            Modifier
                                .width(16.dp)
                                .height(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Test connection")
                    }
                }

                Spacer(Modifier.width(10.dp))

                OutlinedButton(onClick = {
                    scope.launch {
                        SqlAiApp.settings.setApiKey("")
                        apiKey = ""
                        testResult = null
                    }
                }) {
                    Text("Clear key")
                }
            }

            testResult?.let { (ok, message) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (ok) SqlSuccess else SqlError
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = if (ok) SqlSuccess else SqlError
                    )
                }
            }
        }

        SectionCard(title = "Free key links") {
            ProviderLink("Groq", "console.groq.com/keys")
            ProviderLink("Gemini", "aistudio.google.com/apikey")
            ProviderLink("OpenRouter", "openrouter.ai/keys")
            ProviderLink("Together", "api.together.ai/settings/api-keys")
            ProviderLink("Hugging Face", "huggingface.co/settings/tokens")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Keys are stored only on this device (DataStore).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProviderLink(name: String, url: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(110.dp))
        Text(url, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}
