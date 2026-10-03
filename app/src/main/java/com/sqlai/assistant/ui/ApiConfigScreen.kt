package com.sqlai.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val scope = AppCrashHandler.safeScope(rememberCoroutineScope())
    val settings by SqlAiApp.settings.settings.collectAsState(initial = null)

    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var showAllKeys by remember { mutableStateOf(false) }
    val keyDrafts = remember { mutableStateMapOf<String, String>() }
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
            apiKey = s.keyFor(s.provider)
            model = s.model
            baseUrl = s.baseUrlOverride
            loaded = true
        }
        AiProvider.entries.forEach { p ->
            if (p.name !in keyDrafts) keyDrafts[p.name] = s.keyFor(p)
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
            "Set keys for every provider in one place - the failover pool uses them automatically",
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
                                apiKey = keyDrafts[provider.name] ?: ""
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
                    keyDrafts[currentProviderValue.name] = it
                    scope.launch {
                        SqlAiApp.settings.setApiKey(it)
                        SqlAiApp.settings.setProviderKey(currentProviderValue, it)
                    }
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
                        SqlAiApp.settings.setProviderKey(currentProviderValue, "")
                        apiKey = ""
                        keyDrafts[currentProviderValue.name] = ""
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

        SectionCard(title = "All API keys (set together)") {
            Text(
                "Paste keys for any providers you have - failover pool uses them in order. " +
                    "Tap a link to open the free key page in your browser.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Keys stay on this device only (DataStore).",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Text(
                    if (showAllKeys) "Hide keys" else "Show keys",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showAllKeys = !showAllKeys }
                )
            }
            Spacer(Modifier.height(8.dp))
            AiProvider.entries.forEach { p ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        p.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        providerUrlCaption(p),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            openProviderUrl(context, providerUrl(p))
                        }
                    )
                }
                OutlinedTextField(
                    value = keyDrafts[p.name] ?: "",
                    onValueChange = { v ->
                        keyDrafts[p.name] = v
                        scope.launch {
                            SqlAiApp.settings.setProviderKey(p, v)
                            if (p == currentProviderValue) {
                                SqlAiApp.settings.setApiKey(v)
                                apiKey = v
                            }
                        }
                    },
                    placeholder = { Text(p.keyHint) },
                    singleLine = true,
                    visualTransformation = if (showAllKeys) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showAllKeys = !showAllKeys }) {
                            Icon(
                                imageVector = if (showAllKeys) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = "Toggle key visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private fun providerUrlCaption(p: AiProvider): String = when (p) {
    AiProvider.GROQ -> "console.groq.com/keys"
    AiProvider.GEMINI -> "aistudio.google.com/apikey"
    AiProvider.OPENROUTER -> "openrouter.ai/keys"
    AiProvider.TOGETHER -> "api.together.ai/keys"
    AiProvider.HUGGINGFACE -> "huggingface.co/tokens"
    AiProvider.DEEPSEEK -> "platform.deepseek.com/api_keys"
    AiProvider.OLLAMA -> "ollama.com (local, no key)"
}

private fun providerUrl(p: AiProvider): String = when (p) {
    AiProvider.GROQ -> "https://console.groq.com/keys"
    AiProvider.GEMINI -> "https://aistudio.google.com/apikey"
    AiProvider.OPENROUTER -> "https://openrouter.ai/keys"
    AiProvider.TOGETHER -> "https://api.together.ai/settings/api-keys"
    AiProvider.HUGGINGFACE -> "https://huggingface.co/settings/tokens"
    AiProvider.DEEPSEEK -> "https://platform.deepseek.com/api_keys"
    AiProvider.OLLAMA -> "https://ollama.com"
}

private fun openProviderUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        LogBus.log("Could not open $url: ${e.message}", LogLevel.WARN)
    }
}
