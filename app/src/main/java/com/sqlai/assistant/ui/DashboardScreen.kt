package com.sqlai.assistant.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.core.AssistantState
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.PermissionHelper
import com.sqlai.assistant.core.StateBus
import com.sqlai.assistant.engine.AssistantEngine
import com.sqlai.assistant.service.ListeningService
import com.sqlai.assistant.ui.theme.SqlCyan
import com.sqlai.assistant.ui.theme.SqlError
import com.sqlai.assistant.ui.theme.SqlSuccess
import com.sqlai.assistant.ui.theme.SqlWarn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val settings by SqlAiApp.settings.settings.collectAsState(initial = null)
    val state by StateBus.state.collectAsState()
    val lastCommand by StateBus.lastCommand.collectAsState()
    val micLevel by StateBus.level.collectAsState()
    val logs by LogBus.logs.collectAsState()

    var permissionTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            permissionTick++
            delay(2500)
        }
    }
    val statuses = remember(permissionTick, settings) {
        PermissionHelper.statuses(context)
    }

    var manualCommand by remember { mutableStateOf("") }
    val logListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logListState.animateScrollToItem(logs.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // ---------------------------------------------------------- header
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("SQL AI", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "24/7 phone-control assistant",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Switch(
                checked = settings?.assistantEnabled == true,
                onCheckedChange = { checked ->
                    scope.launch {
                        SqlAiApp.settings.setAssistantEnabled(checked)
                        if (checked) ListeningService.start(context) else ListeningService.stop(context)
                    }
                }
            )
        }

        // ------------------------------------------------------ voice orb
        SectionCard {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VoiceOrb(state = state, level = micLevel)
                Text(
                    text = when (state) {
                        AssistantState.DISABLED -> "Assistant disabled"
                        AssistantState.IDLE -> "Ready - say \"SQL ...\""
                        AssistantState.LISTENING -> if (lastCommand.isEmpty()) "Listening for wake word" else lastCommand
                        AssistantState.PROCESSING -> "Working: $lastCommand"
                        AssistantState.ERROR -> "Error - check permissions"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ListeningService.start(context) }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Start")
                    }
                    Button(
                        onClick = { ListeningService.stop(context) },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = SqlError
                        )
                    ) {
                        Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                }
            }
        }

        // ------------------------------------------------- manual command
        SectionCard(title = "Type a command (test)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualCommand,
                    onValueChange = { manualCommand = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("e.g. open whatsapp") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val cmd = manualCommand.trim()
                        if (cmd.isNotEmpty()) {
                            AssistantEngine.execute(cmd, "typed")
                            manualCommand = ""
                        }
                    }
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }
        }

        // ------------------------------------------------ service statuses
        SectionCard(title = "System health") {
            val rows = listOf(
                statuses.firstOrNull { it.id == "accessibility" },
                statuses.firstOrNull { it.id == "overlay" },
                statuses.firstOrNull { it.id == "notification_listener" },
                statuses.firstOrNull { it.id == "battery" },
                statuses.firstOrNull { it.id == "mic" }
            ).filterNotNull()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { item ->
                            StatusChip(
                                label = item.title,
                                ok = item.granted,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                val ready = statuses.all { it.granted }
                Text(
                    text = if (ready) "All systems go - assistant fully operational"
                    else "Some access missing - open the Access tab",
                    fontSize = 12.sp,
                    color = if (ready) SqlSuccess else SqlWarn
                )
            }
        }

        // -------------------------------------------------- log monitor
        SectionCard(title = "Activity log") {
            LazyColumn(
                state = logListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val color = when (entry.level) {
                            LogLevel.ERROR -> SqlError
                            LogLevel.WARN -> SqlWarn
                            LogLevel.SUCCESS -> SqlSuccess
                            LogLevel.INFO -> SqlCyan
                        }
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(color, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${entry.time}  ${entry.message}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            maxLines = 2
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${logs.size} events",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                IconButton(onClick = { LogBus.clear() }) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = "Clear log",
                        Modifier.size(18.dp)
                    )
                }
            }
        }

        // -------------------------------------------- default assistant
        SectionCard(title = "Default assistant") {
            Text(
                "Long-press HOME / power button to summon SQL AI once it is set as the system assistant.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(onClick = {
                val granted = PermissionHelper.isAccessibilityEnabled(context)
                val intent = if (granted) {
                    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                } else {
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }) {
                Text("Open assistant settings")
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** Pulsing mic orb showing live listening intensity. */
@Composable
private fun VoiceOrb(state: AssistantState, level: Float) {
    val active = state == AssistantState.LISTENING || state == AssistantState.PROCESSING
    val infinite = rememberInfiniteTransition(label = "orb")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val scale by animateFloatAsState(
        targetValue = when {
            state == AssistantState.PROCESSING -> 1.18f
            state == AssistantState.LISTENING -> 1f + level * 0.35f + pulse * 0.08f
            active -> 1f
            else -> 0.85f
        },
        label = "scale"
    )

    val color = when (state) {
        AssistantState.PROCESSING -> SqlWarn
        AssistantState.LISTENING -> SqlCyan
        AssistantState.DISABLED -> MaterialTheme.colorScheme.outline
        AssistantState.ERROR -> SqlError
        else -> SqlSuccess
    }

    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(96.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(
                    color = if (active) color.copy(alpha = 0.18f + pulse * 0.10f)
                    else color.copy(alpha = 0.10f),
                    shape = CircleShape
                )
        )
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(40.dp)
        )
    }
}
