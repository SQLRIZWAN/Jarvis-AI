package com.sqlai.assistant.engine

import android.graphics.Bitmap
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.Action
import com.sqlai.assistant.ai.AgentParser
import com.sqlai.assistant.ai.AiClient
import com.sqlai.assistant.ai.ChatMessage
import com.sqlai.assistant.core.CorePromptBuilder
import com.sqlai.assistant.core.AssistantState
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.StateBus
import com.sqlai.assistant.device.DeviceController
import com.sqlai.assistant.service.SqlAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Unkillable, UNLIMITED Observe -> Think -> Act -> Verify agent.
 *
 * There is NO hardcoded step budget: the loop keeps running until the model
 * reports `done=true` and the result verifies on the live screen. The only
 * abort paths are hard failures (AI unreachable, accessibility lost, or a
 * long no-progress streak where verification keeps failing) - never a fixed
 * action counter.
 *
 * Background execution is deliberately SILENT: status voice lines are spoken
 * only for the first update and the final outcome, and the overlay command
 * text is throttled so continuous action execution never spams notifications.
 */
object AgenticLoopEngine {

    private const val MAX_THOUGHT_LOG = 180
    private const val NO_PROGRESS_LIMIT = 20
    private const val AI_ERROR_LIMIT = 3
    private const val STATE_REFRESH_MS = 6_000L
    private const val MAX_TURNS = 20

    private var lastStateRefresh = 0L
    private var spokenReplies = 0

    suspend fun runTask(task: String, source: String) {
        val settings = SqlAiApp.settings.settings.first()
        val accessibility = SqlAccessibilityService.instance

        if (accessibility == null) {
            LogBus.log("Accessibility is off - agent cannot see the screen", LogLevel.ERROR)
            Speaker.speak("Please enable the accessibility service first.")
            return
        }

        spokenReplies = 0
        lastStateRefresh = 0L
        LogBus.log("[$source] Agent started (unlimited loop): \"$task\"", LogLevel.SUCCESS)
        val conversation = ArrayList<ChatMessage>()
        conversation.add(ChatMessage("user", "TASK: $task"))

        var lastReply = ""
        var previousVerifyOk = true
        var completed = false
        var step = 0
        var noProgressStreak = 0
        var aiErrors = 0

        refreshState(task, force = true)

        // ---------------------------------------------------------- main loop
        while (!completed) {
            step++

            // ---- OBSERVE --------------------------------------------------
            val screen = withContext(Dispatchers.IO) {
                accessibility.captureScreenDetailed(70)
            }
            val needImage = settings.supportsVision() && (step == 1 || !previousVerifyOk)
            val image = if (needImage) captureJpeg(accessibility) else null

            val observation = if (step == 1) {
                "TASK: $task\n\nLIVE SCREEN:\n$screen"
            } else {
                "AFTER MY LAST ACTIONS the screen is now:\n$screen"
            }
            conversation.add(ChatMessage("user", observation))
            trim(conversation)

            // ---- THINK ----------------------------------------------------
            val raw = try {
                aiErrors = 0
                AiClient.complete(
                    settings = settings,
                    screenContext = null,
                    history = conversation,
                    imageJpeg = image,
                    systemPromptOverride = CorePromptBuilder.agent(settings)
                )
            } catch (e: Exception) {
                aiErrors++
                LogBus.log("Agent AI error ($aiErrors/$AI_ERROR_LIMIT): ${e.message}", LogLevel.ERROR)
                if (aiErrors >= AI_ERROR_LIMIT) {
                    Speaker.speak("Sorry, the AI service is unavailable.")
                    break
                }
                withContext(Dispatchers.IO) { kotlinx.coroutines.delay(1200) }
                continue
            }
            conversation.add(ChatMessage("assistant", raw.take(2000)))

            val plan = AgentParser.parse(raw)
            LogBus.log("Think #$step: ${plan.thought.take(MAX_THOUGHT_LOG)}")
            if (plan.reply.isNotBlank() && !plan.reply.equals(lastReply, ignoreCase = true)) {
                lastReply = plan.reply
                if (spokenReplies == 0 || plan.done) {
                    spokenReplies++
                    Speaker.speak(plan.reply)
                }
            }

            // ---- ACT ------------------------------------------------------
            if (plan.actions.isNotEmpty()) {
                LogBus.log("Act #$step: ${plan.actions.size} action(s)")
                withContext(Dispatchers.IO) {
                    DeviceController.execute(plan.actions)
                }
                delayBriefly()
            }

            // ---- VERIFY ---------------------------------------------------
            val verifyOk = verify(accessibility, plan.expectType, plan.expectValue)
            previousVerifyOk = verifyOk
            noProgressStreak = if (verifyOk) 0 else noProgressStreak + 1

            if (plan.done) {
                if (verifyOk || plan.expectType == "none") {
                    completed = true
                    LogBus.log("Task COMPLETE after $step step(s): $task", LogLevel.SUCCESS)
                    if (lastReply.isBlank()) Speaker.speak("Task completed.")
                    break
                }
                LogBus.log("Model said done but verification failed - continuing", LogLevel.WARN)
            }

            if (noProgressStreak >= NO_PROGRESS_LIMIT) {
                LogBus.log(
                    "Agent stopped: no verification progress after $NO_PROGRESS_LIMIT attempts",
                    LogLevel.WARN
                )
                Speaker.speak("I could not complete that task.")
                break
            }

            // ---- FEEDBACK for the next think ------------------------------
            val freshScreen = withContext(Dispatchers.IO) {
                accessibility.captureScreenDetailed(70)
            }
            val feedback = when {
                plan.expectType == "none" || plan.expectValue.isBlank() ->
                    "No verification requested. Current screen:\n$freshScreen"

                verifyOk ->
                    "VERIFIED: \"${plan.expectValue}\" is visible. Continue the remaining task. Current screen:\n$freshScreen"

                else ->
                    "VERIFY FAILED: expected \"${plan.expectValue}\" was NOT found. Analyse why and retry differently. Current screen:\n$freshScreen"
            }
            conversation.add(ChatMessage("user", feedback))
            trim(conversation)
            refreshState(task, force = false)
        }

        spokenReplies = 0
        StateBus.setState(AssistantState.IDLE)
    }

    // ------------------------------------------------------------------ utils

    /**
     * Silent-mode status: updates the dashboard command only when the
     * overlay would actually change (throttled) so continuous execution
     * does not spam popups / redraws.
     */
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
        value: String
    ): Boolean {
        if (type == "none" || value.isBlank()) return true
        return try {
            when (type) {
                "text_visible" ->
                    accessibility.captureScreenText(150).contains(value, ignoreCase = true) ||
                        accessibility.captureScreenDetailed(120).contains(value, ignoreCase = true)

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
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 65, stream)
            bitmap.recycle()
            stream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun delayBriefly() {
        withTimeoutOrNull(900) {
            kotlinx.coroutines.delay(700)
        }
    }

    private fun trim(conversation: MutableList<ChatMessage>) {
        while (conversation.size > MAX_TURNS) conversation.removeAt(0)
    }
}
