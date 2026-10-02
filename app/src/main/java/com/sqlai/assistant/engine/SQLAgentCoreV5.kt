package com.sqlai.assistant.engine

import android.graphics.Bitmap
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.AgentParser
import com.sqlai.assistant.ai.AiClient
import com.sqlai.assistant.ai.ChatMessage
import com.sqlai.assistant.core.AssistantState
import com.sqlai.assistant.core.CorePromptBuilder
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.ScreenshotLog
import com.sqlai.assistant.core.StateBus
import com.sqlai.assistant.core.TaskStateManager
import com.sqlai.assistant.device.DeviceController
import com.sqlai.assistant.service.BlockerSweeper
import com.sqlai.assistant.service.SqlAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * SQLAgentCore V5 - the main ReAct (Reason + Act) engine with Visual
 * Grounding and anti-freeze recovery.
 *
 * Architecture:
 *
 *   PHASE 0  Autonomous goal decomposition - one planning call splits the
 *            user's goal into an ordered micro-goal tree (deep thinking
 *            before any touch happens).
 *
 *   LOOP     OBSERVE (screen text + screenshot) -> THINK (ReAct thought +
 *            coordinate plan) -> ACT (hard 5 s timeout) -> VERIFY (UI delta
 *            + expected value) -> FEEDBACK. Unlimited steps.
 *
 *   ANTI-FREEZE: every action batch runs inside `withTimeoutOrNull(5s)`.
 *            A stalled step NEVER blocks the agent: it is logged as a
 *            soft-failure, the screen is re-parsed fresh, BACK is pressed
 *            on repeated stalls to escape dead-ends, and the model is
 *            forced onto an alternate execution path.
 *
 *   VISUAL GROUNDING: screenshots are attached whenever the previous step
 *            failed, verification failed or coordinate taps were used, so
 *            Gemini Vision can emit exact pixel tap {x,y} targets that are
 *            dispatched through [CoordinateGestureExecutor].
 *
 *   INTERRUPT: [isPaused] stays false in v7 - user speech is answered by
 *            the ConversationAgent in PARALLEL, the loop never freezes.
 */
object SQLAgentCoreV5 {

    private const val STEP_TIMEOUT_MS = 15_000L // BUG #3: 15s per-step watchdog
    private const val LONG_STEP_TIMEOUT_MS = 180_000L // wa_call / wait_for workflows
    private val LONG_ACTIONS = setOf("wa_call", "call", "wait_for", "voice_note")
    private const val NO_PROGRESS_LIMIT = 20
    private const val AI_ERROR_LIMIT = 3
    private const val STATE_REFRESH_MS = 6_000L
    private const val MAX_TURNS = 22
    private const val MAX_THOUGHT_LOG = 180
    private const val THINK_TIMEOUT_MS = 25_000L
    private const val DECOMPOSE_TIMEOUT_MS = 15_000L

    /** After this many consecutive soft-fails, force BACK to escape. */
    private const val BACK_EVERY_N_STUCK = 4

    private const val DECOMPOSE_SYSTEM =
        "You are a task planner. Split the user's goal into short ordered micro-goals " +
            "(max 8). Reply with EXACTLY one raw JSON object and nothing else: " +
            "{\"subgoals\": [\"micro goal 1\", \"micro goal 2\"]}"

    private var lastStateRefresh = 0L

    suspend fun runTask(
        task: String,
        source: String,
        announce: (String) -> Unit = { Speaker.post(it) },
        shouldStop: () -> Boolean = { false },
        isPaused: () -> Boolean = { false }
    ) {
        val settings = SqlAiApp.settings.settings.first()
        val accessibility = SqlAccessibilityService.instance

        if (accessibility == null) {
            LogBus.log("Accessibility is off - agent cannot see the screen", LogLevel.ERROR)
            Speaker.post("Please enable the accessibility service first.")
            return
        }

        lastStateRefresh = 0L
        LogBus.log("[$source] V5 agent started: \"$task\"", LogLevel.SUCCESS)
        ScreenshotLog.nextTask()
        val conversation = ArrayList<ChatMessage>()
        conversation.add(ChatMessage("user", "TASK: $task"))

        // ------------------------------------------- PHASE 0: decompose goal
        val subgoals = decomposeGoal(settings, task)
        // BUG #2 / BUG #5: persistent task state - resumes instead of restarting.
        val taskState = TaskStateManager.begin(SqlAiApp.instance, task, subgoals)
        if (subgoals.isNotEmpty()) {
            val numbered = subgoals.mapIndexed { i, g -> "${i + 1}. $g" }.joinToString("\n")
            LogBus.log("Goal split into ${subgoals.size} micro-goals", LogLevel.SUCCESS)
            conversation.add(ChatMessage("user", "MICRO-GOAL PLAN (follow in order):\n$numbered"))
        }
        if (taskState.completed.isNotEmpty()) {
            conversation.add(ChatMessage("user", TaskStateManager.stateBlock()))
            LogBus.log(
                "[TASK] resuming: ${taskState.completed.size} milestone(s) already done",
                LogLevel.WARN
            )
        } else {
            subgoals.firstOrNull()?.let { announce(it) }
        }

        var lastReply = ""
        var previousVerifyOk = true
        var completed = false
        var step = 0
        var noProgressStreak = 0
        var aiErrors = 0
        var stuckStreak = 0
        var usedCoordinateTap = false

        refreshState(task, force = true)

        // ---------------------------------------------------------- main loop
        while (!completed && !shouldStop()) {
            // Interrupt hold: voice bridge paused us (user is talking).
            while (isPaused() && !shouldStop()) {
                delay(250)
            }
            if (shouldStop()) break
            step++

            // ---- OBSERVE --------------------------------------------------
            val screen = withContext(Dispatchers.IO) {
                accessibility.captureScreenDetailed(70)
            }
            val softStuck = stuckStreak > 0
            // BUG #4: vision grounding ALWAYS ON - every think() carries a
            // screenshot so coordinate taps are computed from fresh pixels.
            val needImage = settings.supportsVision()
            val image = if (needImage) captureJpeg(accessibility) else null

            val stateBlock = TaskStateManager.stateBlock()
            val observation = buildString {
                if (step == 1) append("TASK: $task\n\n") else append("AFTER MY LAST ACTIONS the screen is now:\n")
                if (stateBlock.isNotBlank()) append(stateBlock).append("\n\n")
                append("LIVE SCREEN:\n").append(screen)
            }
            conversation.add(ChatMessage("user", observation))
            trim(conversation)

            // ---- THINK ----------------------------------------------------
            val raw = try {
                aiErrors = 0
                withTimeoutOrNull(THINK_TIMEOUT_MS) {
                    AiClient.complete(
                        settings = settings,
                        screenContext = null,
                        history = conversation,
                        imageJpeg = image,
                        systemPromptOverride = CorePromptBuilder.agent(settings)
                    )
                } ?: throw java.util.concurrent.TimeoutException("think timeout")
            } catch (e: Exception) {
                aiErrors++
                LogBus.log("Agent AI error ($aiErrors/$AI_ERROR_LIMIT): ${e.message}", LogLevel.ERROR)
                if (aiErrors >= AI_ERROR_LIMIT) {
                    Speaker.post("Sorry, the AI service is unavailable.")
                    break
                }
                withContext(Dispatchers.IO) { delay(1200) }
                continue
            }
            conversation.add(ChatMessage("assistant", raw.take(2000)))

            val plan = AgentParser.parse(raw)
            LogBus.log("Think #$step: ${plan.thought.take(MAX_THOUGHT_LOG)}")
            if (plan.reply.isNotBlank() && !plan.reply.equals(lastReply, ignoreCase = true)) {
                lastReply = plan.reply
                // BUG #3: progress lines speak BEFORE the action (in sync);
                // the FINAL (done) reply is held and spoken right after the
                // action via postPriority - action first, voice follows 0-lag.
                if (!plan.done) announce(plan.reply)
            }

            // ---- ACT (15 s watchdog - NEVER freezes; long actions get more)
            var timedOut = false
            var delta = false
            val actions = plan.actions
            if (actions.isNotEmpty()) {
                LogBus.log("Act #$step: ${actions.size} action(s)", LogLevel.INFO)
                usedCoordinateTap = actions.any { it.type == "tap" && it.x != null }
                // FEATURE #1: dismiss permission / crash / battery dialogs first.
                if (softStuck || step % 3 == 1) {
                    BlockerSweeper.sweep(accessibility, screen)
                }
                val budgetMs =
                    if (actions.any { it.type in LONG_ACTIONS }) LONG_STEP_TIMEOUT_MS
                    else STEP_TIMEOUT_MS
                val outcome = withTimeoutOrNull(budgetMs) {
                    withContext(Dispatchers.IO) {
                        DeviceController.execute(actions)
                    }
                    // Short settle so animations commit before we compare.
                    delay(250)
                    // G5: the screen CHANGED - drop the frame cache so the
                    // next think() sees FRESH pixels, not the pre-action shot.
                    accessibility.invalidateShotCache()
                    val after = accessibility.captureScreenDetailed(70)
                    after != screen
                }
                if (outcome == null) {
                    timedOut = true
                    stuckStreak++
                    noProgressStreak++
                    LogBus.log(
                        "Step #$step TIMED OUT after ${budgetMs}ms - soft failure #$stuckStreak, recovering",
                        LogLevel.WARN
                    )
                    // FEATURE #2: keep a screenshot of every blocked step.
                    ScreenshotLog.captureAndSave("Step_${step}_TIMEOUT")
                    // FEATURE #1: auto-dismiss the blocker that froze the step.
                    val healed = BlockerSweeper.recover(accessibility)
                    if (stuckStreak == 2) {
                        announce("That step stalled - trying another way")
                    }
                    // Escape hatch: press BACK out of dead-ends/dialogs.
                    if (!healed && stuckStreak % BACK_EVERY_N_STUCK == 0) {
                        withContext(Dispatchers.IO) {
                            accessibility.globalBack()
                            delay(400)
                        }
                        LogBus.log("Recovery: pressed BACK after $stuckStreak stalled steps", LogLevel.WARN)
                        announce("Let me go back and try differently")
                    }
                } else {
                    delta = outcome
                    stuckStreak = if (delta) 0 else stuckStreak + 1
                    if (!delta) {
                        noProgressStreak++
                        LogBus.log("Step #$step produced NO UI change (soft failure #$stuckStreak)", LogLevel.WARN)
                    } else {
                        noProgressStreak = 0
                    }
                    // G1: save a step screenshot ONLY on failure - successful
                    // steps skip it (2 captures/step -> 1/step; timeouts keep theirs).
                    if (!delta || stuckStreak > 0) {
                        ScreenshotLog.captureAndSave("Step_${step}_${actions.first().type}")
                    }
                }
            } else {
                noProgressStreak = if (plan.done) noProgressStreak else 0
            }

            // ---- VERIFY ---------------------------------------------------
            val verifyOk = if (timedOut) false else
                verify(accessibility, plan.expectType, plan.expectValue, plan.expectArea)
            previousVerifyOk = verifyOk

            // BUG #2: persist the completed milestone so restarts / calls
            // never make the model redo it.
            if (verifyOk && plan.milestone.isNotBlank()) {
                TaskStateManager.markCompleted(SqlAiApp.instance, plan.milestone)
            }

            if (plan.done) {
                if (verifyOk || plan.expectType == "none") {
                    completed = true
                    TaskStateManager.clear(SqlAiApp.instance)
                    LogBus.log("Task COMPLETE after $step step(s): $task", LogLevel.SUCCESS)
                    // BUG #3: final line jumps the queue (spoken next, backlog cleared).
                    if (lastReply.isNotBlank()) Speaker.postPriority(lastReply)
                    if (lastReply.isBlank()) Speaker.postPriority("Task completed.")
                    break
                }
                LogBus.log("Model said done but verification failed - continuing", LogLevel.WARN)
            }

            if (noProgressStreak >= NO_PROGRESS_LIMIT) {
                // BUG #2 (v7): journal stays ACTIVE on failure - the next
                // attempt resumes from the recorded position, never step 1.
                LogBus.log(
                    "Agent stopped: no progress after $NO_PROGRESS_LIMIT attempts " +
                        "(journal kept for resume)",
                    LogLevel.WARN
                )
                Speaker.post("I could not complete that task. Say it again to resume.")
                break
            }

            // ---- FEEDBACK for the next think ------------------------------
            val freshScreen = withContext(Dispatchers.IO) {
                accessibility.captureScreenDetailed(70)
            }
            val core = when {
                timedOut ->
                    "TIMEOUT: your actions produced no valid UI change within the step watchdog. " +
                        "The screen was re-parsed fresh and any blocking dialog was dismissed. " +
                        "Do NOT repeat the same action and do NOT restart from step 1 - resume " +
                        "from your CURRENT position and take an ALTERNATE path (different button " +
                        "label, exact pixel tap {x,y} from the screenshot, BACK once). " +
                        "Current screen:\n$freshScreen"

                plan.actions.isNotEmpty() && !delta ->
                    "NO UI CHANGE detected after your actions (stalled $stuckStreak time(s)). " +
                        "Element matching may have failed - scroll, wait 1s, or re-scan, then " +
                        "switch to a DIFFERENT control (icon, content-description or pixel tap). " +
                        "Do NOT redo completed milestones. Current screen:\n$freshScreen"

                plan.expectType == "none" || plan.expectValue.isBlank() ->
                    "No verification requested. Current screen:\n$freshScreen"

                verifyOk ->
                    "VERIFIED: \"${plan.expectValue}\" is visible. Continue the remaining task. Current screen:\n$freshScreen"

                else ->
                    "VERIFY FAILED: expected \"${plan.expectValue}\" was NOT found. The element " +
                        "may need scrolling/waiting or a different label (content-description " +
                        "first, then exact text, then coordinates). Do NOT restart the task. " +
                        "Current screen:\n$freshScreen"
            }
            val stateLine = TaskStateManager.stateBlock()
            conversation.add(
                ChatMessage("user", if (stateLine.isBlank()) core else "$stateLine\n\n$core")
            )
            trim(conversation)
            refreshState(task, force = false)
        }

        StateBus.setState(AssistantState.IDLE)
        if (shouldStop()) {
            // v7: explicit user stop is one of the ONLY two clear() cases.
            TaskStateManager.clear(SqlAiApp.instance)
            LogBus.log("Agent stopped by user - task journal cleared", LogLevel.WARN)
        }
        if (completed) {
            stuckStreak = 0
        }
    }

    // ------------------------------------------------------- goal decomposition

    /**
     * Autonomous deep-thinking pass: split the goal into an ordered micro-goal
     * tree before touching the screen. Best-effort - an unreachable planner
     * never blocks execution (the ReAct loop reasons inline).
     */
    private suspend fun decomposeGoal(
        settings: com.sqlai.assistant.core.AppSettings,
        task: String
    ): List<String> {
        if (!settings.apiKey.isNotBlank()) return emptyList()
        return try {
            val raw = withTimeoutOrNull(DECOMPOSE_TIMEOUT_MS) {
                AiClient.complete(
                    settings = settings,
                    screenContext = null,
                    history = listOf(ChatMessage("user", "Goal: $task")),
                    imageJpeg = null,
                    systemPromptOverride = DECOMPOSE_SYSTEM
                )
            } ?: return emptyList()
            val jsonText = com.sqlai.assistant.ai.PlanParser.extractJsonObject(raw) ?: return emptyList()
            val arr = JSONObject(jsonText).optJSONArray("subgoals") ?: return emptyList()
            (0 until arr.length())
                .mapNotNull { arr.optString(it).trim().ifBlank { null } }
                .take(8)
        } catch (e: Exception) {
            LogBus.log("Decompose skipped: ${e.message}", LogLevel.WARN)
            emptyList()
        }
    }

    // ------------------------------------------------------------------ utils

    private fun refreshState(task: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (force || now - lastStateRefresh >= STATE_REFRESH_MS) {
            lastStateRefresh = now
            StateBus.setState(AssistantState.PROCESSING)
            StateBus.setCommand(task)
        }
    }

    private fun verify(
        accessibility: SqlAccessibilityService,
        type: String,
        value: String,
        area: String = ""
    ): Boolean {
        if (type == "none" || value.isBlank()) return true
        return try {
            when (type) {
                "text_visible" -> {
                    val seen = accessibility.captureScreenText(150)
                        .contains(value, ignoreCase = true) ||
                        accessibility.captureScreenDetailed(120)
                            .contains(value, ignoreCase = true)
                    if (!seen) return false
                    // BUG #4: when the model pins a region, the element must
                    // actually be THERE - kills false-positives on old screens.
                    if (area.isBlank()) return true
                    val match = accessibility.findBestMatch(value) ?: return false
                    if (match.confidence < 80) return false
                    val rect = android.graphics.Rect()
                    match.node.getBoundsInScreen(rect)
                    if (rect.isEmpty) return false
                    val dm = accessibility.resources.displayMetrics
                    val cy = rect.centerY()
                    when (area) {
                        "top", "upper" -> cy < dm.heightPixels / 3
                        "middle", "center" -> cy in dm.heightPixels / 3..(dm.heightPixels * 2 / 3)
                        "bottom", "lower" -> cy > dm.heightPixels * 2 / 3
                        else -> true
                    }
                }

                "app_foreground" -> {
                    val front = accessibility.frontPackage()
                    front.equals(value, ignoreCase = true) || front.contains(value, ignoreCase = true)
                }

                else -> true
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun captureJpeg(accessibility: SqlAccessibilityService): ByteArray? {
        val bitmap: Bitmap = withTimeoutOrNull(6000) {
            accessibility.captureScreenshot()
        } ?: return null
        return try {
            // G5: downscale BEFORE encode - uploads smaller/faster so the
            // model gets its answer sooner ("dimag slow" was partly latency).
            var src = bitmap
            var scaled: Bitmap? = null
            if (bitmap.width > 1280) {
                val h = (bitmap.height.toLong() * 1280 / bitmap.width).toInt().coerceAtLeast(1)
                scaled = Bitmap.createScaledBitmap(bitmap, 1280, h, false)
                src = scaled
            }
            val stream = ByteArrayOutputStream()
            src.compress(Bitmap.CompressFormat.JPEG, 65, stream)
            scaled?.recycle()
            // G1: do NOT recycle the frame itself - service-owned (shared cache).
            stream.toByteArray()
        } catch (t: Throwable) {
            null
        }
    }

    private fun trim(conversation: MutableList<ChatMessage>) {
        while (conversation.size > MAX_TURNS) conversation.removeAt(0)
    }
}
