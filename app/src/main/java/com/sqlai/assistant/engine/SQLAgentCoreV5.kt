package com.sqlai.assistant.engine

import android.graphics.Bitmap
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.agent.AgentOS
import com.sqlai.assistant.agent.CriticAgent
import com.sqlai.assistant.ai.Action
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
import com.sqlai.assistant.device.FlowMacros
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
    private const val RECOVERY_RUNG_TIMEOUT_MS = 15_000L
    private const val CONFIRM_TIMEOUT_MS = 8_000L

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

        // ---- v7 M4: deterministic flows run BEFORE any LLM round-trip.
        val flowMatch = FlowMacros.match(task)
        if (flowMatch != null) {
            LogBus.log("[FLOW] matched ${flowMatch.macro.name}", LogLevel.SUCCESS)
            announce("Running flow ${flowMatch.macro.name}")
            if (FlowMacros.run(flowMatch, announce)) {
                LogBus.log("[FLOW] ${flowMatch.macro.name} complete", LogLevel.SUCCESS)
                StateBus.setState(AssistantState.IDLE)
                Speaker.postPriority("Done.")
                return
            }
            announce("Flow did not finish - continuing step by step")
        }

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
                        systemPromptOverride = CorePromptBuilder.agent(settings),
                        requireJson = true
                    )
                } ?: throw java.util.concurrent.TimeoutException("think timeout")
            } catch (e: Exception) {
                if (e.message?.contains("PARSE_ERROR") == true) {
                    // v7 M4: one repair already failed inside AiClient - feed
                    // the failure back so the next turn emits valid JSON only.
                    LogBus.log("Agent reply was not valid JSON - sending repair feedback", LogLevel.WARN)
                    conversation.add(
                        ChatMessage(
                            "user",
                            "PARSE_ERROR: your last reply was not a valid JSON plan. Reply with " +
                                "EXACTLY one raw JSON object {thought, reply, done, actions, expect} " +
                                "and nothing else - no prose, no markdown."
                        )
                    )
                    trim(conversation)
                    continue
                }
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
            var criticSkipReason: String? = null
            var recoveryHint = ""
            val actions = plan.actions
            if (actions.isNotEmpty()) {
                LogBus.log("Act #$step: ${actions.size} action(s)", LogLevel.INFO)
                usedCoordinateTap = actions.any { it.type == "tap" && it.x != null }
                // FEATURE #1: dismiss permission / crash / battery dialogs first.
                if (softStuck || step % 3 == 1) {
                    BlockerSweeper.sweep(accessibility, screen)
                }

                // ---- v7 M4 CRITIC: pre-validate + risky-action voice gate ----
                val pre = CriticAgent.preValidate(plan, screen)
                pre.problems.forEach { LogBus.log("[CRITIC] $it", LogLevel.WARN) }
                var exec = actions.filter { CriticAgent.isValid(it) }
                val dropped = actions.size - exec.size
                if (dropped > 0) {
                    LogBus.log("[CRITIC] dropped $dropped invalid action(s)", LogLevel.WARN)
                }
                val risky = exec.filter(CriticAgent::needsConfirm)
                if (risky.isNotEmpty() && settings.confirmRisky) {
                    val confirmed = AgentOS.askConfirm(
                        "This step will ${risky.first().type.replace('_', ' ')}. " +
                            "Say haan to confirm, nahi to skip.",
                        CONFIRM_TIMEOUT_MS
                    )
                    if (!confirmed) {
                        exec = exec.filterNot(CriticAgent::needsConfirm)
                        criticSkipReason =
                            "USER DECLINED the risky action - do NOT retry it, take a different path."
                        LogBus.log("[CRITIC] risky action declined by user", LogLevel.WARN)
                    }
                }
                if (exec.isEmpty()) {
                    if (criticSkipReason == null) {
                        criticSkipReason =
                            "ALL your actions were invalid - fix the action JSON fields first."
                    }
                    noProgressStreak++
                    stuckStreak++
                    LogBus.log("Nothing left to execute after the critic gate", LogLevel.WARN)
                } else {

                val budgetMs =
                    if (exec.any { it.type in LONG_ACTIONS }) LONG_STEP_TIMEOUT_MS
                    else STEP_TIMEOUT_MS
                val outcome = withTimeoutOrNull(budgetMs) {
                    withContext(Dispatchers.IO) {
                        DeviceController.execute(exec)
                    }
                    // Short settle so animations commit before we compare.
                    delay(250)
                    // G5: the screen CHANGED - drop the frame cache so the
                    // next think() sees FRESH pixels, not the pre-action shot.
                    accessibility.invalidateShotCache()
                    val after = accessibility.captureScreenDetailed(70)
                    // v7 M4: order-independent hash - dump order noise can
                    // never fake (or hide) a real UI change.
                    CriticAgent.screenHash(after) != CriticAgent.screenHash(screen)
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
                    BlockerSweeper.recover(accessibility)
                    if (stuckStreak == 2) {
                        announce("That step stalled - trying another way")
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
                        ScreenshotLog.captureAndSave("Step_${step}_${exec.first().type}")
                    }
                }
                // v7 M4: deterministic recovery ladder - one rung per stall.
                if ((timedOut || !delta) && stuckStreak in 1..7) {
                    recoveryHint = runRecoveryLadder(stuckStreak, step, accessibility, announce)
                }

                } // exec.isNotEmpty()
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
                // v7 M4: FAILED status is persisted (journal NEVER deleted) -
                // the next attempt resumes from the recorded position.
                TaskStateManager.markFailed(
                    SqlAiApp.instance,
                    "no progress after $NO_PROGRESS_LIMIT attempts"
                )
                ScreenshotLog.captureAndSave("TASK_FAILED")
                LogBus.log(
                    "Agent stopped: no progress after $NO_PROGRESS_LIMIT attempts " +
                        "(status=FAILED, journal kept for resume)",
                    LogLevel.WARN
                )
                Speaker.post(
                    "I could not complete that task: no progress after $NO_PROGRESS_LIMIT tries. " +
                        "Say it again to resume from where it stopped."
                )
                break
            }

            // ---- FEEDBACK for the next think ------------------------------
            val freshScreen = withContext(Dispatchers.IO) {
                accessibility.captureScreenDetailed(70)
            }
            // v7 M4: deterministic subgoal auto-mark - a remaining label seen
            // on the fresh screen is DONE even when the model forgot to emit
            // "milestone" (never depends on LLM honesty).
            if (delta || verifyOk) {
                val remaining = TaskStateManager.snapshot()?.remaining.orEmpty()
                val auto = CriticAgent.findCompletedSubgoal(remaining, freshScreen)
                if (auto != null) {
                    TaskStateManager.markCompleted(SqlAiApp.instance, auto)
                    LogBus.log("[CRITIC] subgoal auto-marked: $auto", LogLevel.SUCCESS)
                }
            }
            val core = when {
                criticSkipReason != null ->
                    "$criticSkipReason Current screen:\n$freshScreen"

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
            val fullCore = if (recoveryHint.isBlank()) core else "$recoveryHint\n$core"
            conversation.add(
                ChatMessage("user", if (stateLine.isBlank()) fullCore else "$stateLine\n\n$fullCore")
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

    /**
     * v7 M4 deterministic recovery ladder - one rung per consecutive stall:
     * 1 rescan, 2 scroll down x2, 3 scroll up x2, 4 alternate-path hint,
     * 5 BACK, 6 reopen foreground app, 7 blocker sweep. Every rung is capped
     * at 15 s, screenshot-logged and returns the hint appended to the model
     * feedback. No randomness, no guessing - the same stall sequence always
     * walks the same ladder.
     */
    private suspend fun runRecoveryLadder(
        rung: Int,
        step: Int,
        accessibility: SqlAccessibilityService,
        announce: (String) -> Unit
    ): String {
        val label = when (rung) {
            1 -> "rescan"
            2 -> "scroll down x2"
            3 -> "scroll up x2"
            4 -> "alternate path"
            5 -> "back"
            6 -> "reopen app"
            7 -> "blocker sweep"
            else -> return ""
        }
        LogBus.log("Recovery rung $rung/7: $label", LogLevel.WARN)
        ScreenshotLog.captureAndSave("Step_${step}_RECOVER_$rung")
        withTimeoutOrNull(RECOVERY_RUNG_TIMEOUT_MS) {
            when (rung) {
                1 -> {
                    accessibility.invalidateShotCache()
                    accessibility.captureScreenDetailed(70)
                }

                2 -> {
                    accessibility.scroll("down")
                    delay(350)
                    accessibility.scroll("down")
                }

                3 -> {
                    accessibility.scroll("up")
                    delay(350)
                    accessibility.scroll("up")
                }

                4 -> Unit // pure feedback hint - no device action this rung.

                5 -> {
                    accessibility.globalBack()
                    delay(400)
                    announce("Let me go back and try differently")
                }

                6 -> reopenForeground(accessibility)

                7 -> BlockerSweeper.recover(accessibility)
            }
        }
        return when (rung) {
            4 -> "RECOVERY: switch to an ALTERNATE label or pixel tap {x,y} from the screenshot NOW."
            else -> "RECOVERY DONE by system: $label. Resume from your CURRENT position - do NOT restart."
        }
    }

    /** Rung 6: home, then relaunch whatever app was in the foreground. */
    private suspend fun reopenForeground(accessibility: SqlAccessibilityService) {
        val pkg = accessibility.frontPackage()
        accessibility.globalHome()
        delay(450)
        if (pkg.isBlank()) return
        withContext(Dispatchers.IO) {
            DeviceController.execute(listOf(Action(type = "open_app", app = pkg)))
        }
        delay(800)
    }

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
        // v7 M4 BUG-3: index 0 holds the TASK header - drop the SECOND
        // oldest turn instead so the pinned goal survives history trimming.
        while (conversation.size > MAX_TURNS && conversation.size > 1) {
            conversation.removeAt(1)
        }
    }
}
