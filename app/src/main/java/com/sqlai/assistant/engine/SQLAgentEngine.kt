package com.sqlai.assistant.engine

import android.graphics.Bitmap
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.Action
import com.sqlai.assistant.ai.AgentParser
import com.sqlai.assistant.ai.AiClient
import com.sqlai.assistant.ai.ChatMessage
import com.sqlai.assistant.core.AppSettings
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
 * Autonomous ReAct agent:
 *
 *   THINK  -> LLM analyses the live screen + task
 *   ACT    -> executes 1-3 concrete device actions
 *   VERIFY -> re-reads the screen against the model's `expect`
 *   LOOP   -> on failure, feeds the fresh screen back and re-plans
 *
 * Runs multi-step flows (e.g. "open Instagram, like my latest reel") until the
 * model marks the task `done` or [AppSettings.agentMaxSteps] is exhausted.
 */
object SQLAgentEngine {

    private const val MAX_TURNS = 16
    private const val MAX_THOUGHT_LOG = 180

    suspend fun runTask(task: String, source: String) {
        val settings = SqlAiApp.settings.settings.first()
        val accessibility = SqlAccessibilityService.instance

        if (accessibility == null) {
            LogBus.log("Accessibility is off - agent cannot see the screen", LogLevel.ERROR)
            Speaker.speak("Please enable the accessibility service first.")
            return
        }

        LogBus.log("[$source] Agent started: \"$task\"", LogLevel.SUCCESS)
        val conversation = ArrayList<ChatMessage>()
        conversation.add(ChatMessage("user", "TASK: $task"))

        var lastReply = ""
        var previousVerifyOk = true
        var completed = false

        for (step in 1..settings.agentMaxSteps) {
            StateBus.setState(AssistantState.PROCESSING)
            StateBus.setCommand("step $step/$max: $task")
            LogBus.log("Agent step $step/${settings.agentMaxSteps} - thinking...")

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
                AiClient.complete(
                    settings = settings,
                    screenContext = null,
                    history = conversation,
                    imageJpeg = image,
                    systemPromptOverride = AppSettings.AGENT_SYSTEM_PROMPT
                )
            } catch (e: Exception) {
                LogBus.log("Agent AI error: ${e.message}", LogLevel.ERROR)
                Speaker.speak("Sorry, the AI service failed.")
                return
            }
            conversation.add(ChatMessage("assistant", raw.take(2000)))

            val plan = AgentParser.parse(raw)
            LogBus.log("Think: ${plan.thought.take(MAX_THOUGHT_LOG)}")
            if (plan.reply.isNotBlank() && !plan.reply.equals(lastReply, ignoreCase = true)) {
                lastReply = plan.reply
                Speaker.speak(plan.reply)
            }

            // ---- ACT ------------------------------------------------------
            if (plan.actions.isNotEmpty()) {
                LogBus.log("Act: ${plan.actions.size} action(s)")
                withContext(Dispatchers.IO) {
                    DeviceController.execute(plan.actions)
                }
                delayBriefly()
            }

            // ---- VERIFY ---------------------------------------------------
            val verifyOk = verify(accessibility, plan.expectType, plan.expectValue)
            previousVerifyOk = verifyOk

            if (plan.done) {
                if (verifyOk || plan.expectType == "none") {
                    completed = true
                    LogBus.log("Task COMPLETE: $task", LogLevel.SUCCESS)
                    if (lastReply.isBlank()) Speaker.speak("Task completed.")
                    break
                }
                LogBus.log("Model said done but verification failed - continuing", LogLevel.WARN)
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
        }

        if (!completed) {
            LogBus.log("Agent stopped at step budget for: \"$task\"", LogLevel.WARN)
            Speaker.speak("I could not fully complete that task.")
        }
        StateBus.setState(AssistantState.IDLE)
    }

    // ------------------------------------------------------------------ utils

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
