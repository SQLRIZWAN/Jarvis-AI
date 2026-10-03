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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sqlai.assistant.BuildConfig
import com.sqlai.assistant.R
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.agent.GuardAgent
import com.sqlai.assistant.agent.SelfTestSuite
import com.sqlai.assistant.ai.GeminiLiveAudioEngine
import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.AssistantState
import com.sqlai.assistant.core.AudioManagerController
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.PermissionHelper
import com.sqlai.assistant.core.StateBus
import com.sqlai.assistant.engine.AssistantEngine
import com.sqlai.assistant.engine.SQLAgentEngineV4
import com.sqlai.assistant.service.AutonomousCallBridgeService
import com.sqlai.assistant.service.CallStateMachine
import com.sqlai.assistant.service.ListeningService
import com.sqlai.assistant.ui.theme.SqlCyan
import com.sqlai.assistant.ui.theme.SqlError
import com.sqlai.assistant.ui.theme.SqlSuccess
import com.sqlai.assistant.ui.theme.SqlWarn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * v6.0 dashboard - Material 3 home screen.
 *
 * New over v5.4:
 *  - "SQL AI v6.0 Pro" header (auto app_name resValue from versionName),
 *  - live status badges: Background Service / Accessibility / Call Bridge,
 *  - mic waveform visualizer driven by the live RMS level,
 *  - live metric cards: mic owner, call state, Gemini live state, task.
 */
@Composable
fun DashboardScreenV6() {
    val context = LocalContext.current
    val settings by SqlAiApp.settings.settings.collectAsState(initial = null)
    val state by StateBus.state.collectAsState()
    val lastCommand by StateBus.lastCommand.collectAsState()
    val micLevel by StateBus.level.collectAsState()
    val logs by LogBus.logs.collectAsState()
    val micOwner by AudioManagerController.micOwner.collectAsState()
    val liveState by GeminiLiveAudioEngine.state.collectAsState()
    val taskState by SQLAgentEngineV4.taskState.collectAsState()

    var permissionTick by remember { mutableStateOf(0) }
    var callStateName by remember { mutableStateOf(CallStateMachine.current().name) }
    var bridgeActive by remember { mutableStateOf(AutonomousCallBridgeService.isRunning) }
    var serviceActive by remember { mutableStateOf(ListeningService.isActive) }
    LaunchedEffect(Unit) {
        while (true) {
            permissionTick++
            callStateName = CallStateMachine.current().name
            bridgeActive = AutonomousCallBridgeService.isRunning
            serviceActive = ListeningService.isActive
            delay(2500)
        }
    }
    val statuses = remember(permissionTick, settings) {
        PermissionHelper.statuses(context)
    }

    var manualCommand by remember { mutableStateOf("") }
    var selfTestTick by remember { mutableStateOf(0) }
    val logListState = rememberLazyListState()
    val scope = AppCrashHandler.safeScope(rememberCoroutineScope())

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.app_name),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    StateBadge(state = state)
                }
                Text(
                    "v${BuildConfig.VERSION_NAME}  ·  24/7 agentic phone assistant",
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

        // --------------------------------------------- status badges (v6)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatusChip(
                label = "Service",
                ok = serviceActive,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                label = "Accessibility",
                ok = PermissionHelper.isAccessibilityEnabled(context),
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                label = "Call bridge",
                ok = bridgeActive,
                modifier = Modifier.weight(1f)
            )
        }

        // ------------------------------------- voice orb + mic waveform
        SectionCard {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VoiceOrb(state = state, level = micLevel)
                MicWaveform(level = micLevel, active = state == AssistantState.LISTENING ||
                    state == AssistantState.PROCESSING)
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

        // ------------------------------------------------ live metrics (v6)
        SectionCard(title = "Live metrics") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                MetricCard(
                    label = "Mic owner",
                    value = micOwner.name,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "Call state",
                    value = callStateName,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                MetricCard(
                    label = "Live engine",
                    value = liveState.name,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "Task",
                    value = if (taskState.running) {
                        taskState.task.takeIf { it.isNotBlank() }?.take(26) ?: "Running"
                    } else "Idle",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ------------------------------------------- self test (v7 M7)
        SectionCard(title = "Self test (v7 M7)") {
            val report = remember(selfTestTick) { SelfTestSuite.run() }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                MetricCard(
                    label = "Checks",
                    value = "${report.passed}/${report.total}",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "Status",
                    value = if (report.allPass) "PASS" else "FAIL ${report.failed.size}",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "Injections blocked",
                    value = "${GuardAgent.blockedCount.get()}",
                    modifier = Modifier.weight(1f)
                )
            }
            if (!report.allPass) {
                Spacer(Modifier.height(8.dp))
                report.failed.forEach { c ->
                    Text(
                        "FAIL ${c.name}: ${c.detail}",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { selfTestTick++ }) {
                Text("Re-run checks")
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

/**
 * v6.0 mic waveform - 24 animated bars driven by the live RMS level
 * (StateBus.level). Flat when idle, symmetric envelope while speaking.
 */
@Composable
private fun MicWaveform(level: Float, active: Boolean) {
    val animatedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(160, easing = LinearEasing),
        label = "wave"
    )
    val color = when {
        active -> SqlCyan
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bars = 24
        for (i in 0 until bars) {
            val envelope = 0.30f + 0.70f * abs(sin(i / bars.toDouble() * PI)).toFloat()
            val h = (5f + animatedLevel * 38f * envelope).dp
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(h)
                    .background(
                        color.copy(alpha = if (active) 0.9f else 0.55f),
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

/** v6.0 metric tile - one glance at an engine's live state. */
@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
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

/** Modern status pill used in the header. */
@Composable
private fun StateBadge(state: AssistantState) {
    val (label, color) = when (state) {
        AssistantState.LISTENING -> "LISTENING" to SqlCyan
        AssistantState.PROCESSING -> "WORKING" to SqlWarn
        AssistantState.IDLE -> "READY" to SqlSuccess
        AssistantState.ERROR -> "ERROR" to SqlError
        AssistantState.DISABLED -> "OFF" to MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f)
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
