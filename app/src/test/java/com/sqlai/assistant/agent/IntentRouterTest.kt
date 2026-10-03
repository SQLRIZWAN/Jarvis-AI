package com.sqlai.assistant.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG #5 routing contract: stop cancels, mid-task speech answers without
 * pausing, a new task queues behind the running one, idle text runs.
 */
class IntentRouterTest {

    @Test
    fun idleTextRunsThroughOperator() {
        assertTrue(IntentRouter.route("whatsapp kholo", false) is IntentRouter.Route.RunTask)
        assertTrue(IntentRouter.route("kya haal hai", false) is IntentRouter.Route.RunTask)
        assertTrue(IntentRouter.route("hello", false) is IntentRouter.Route.RunTask)
    }

    @Test
    fun cancelWordsWhileRunningCancelTheTask() {
        assertTrue(IntentRouter.route("stop", true) is IntentRouter.Route.Cancel)
        assertTrue(IntentRouter.route("SQL stop", true) is IntentRouter.Route.Cancel)
        assertTrue(IntentRouter.route("ruko!", true) is IntentRouter.Route.Cancel)
        assertTrue(IntentRouter.route("band karo", true) is IntentRouter.Route.Cancel)
    }

    @Test
    fun questionWhileRunningAnswersAsChat() {
        assertTrue(IntentRouter.route("kya tum sun rahe ho", true) is IntentRouter.Route.Chat)
        assertTrue(IntentRouter.route("what's on my screen", true) is IntentRouter.Route.Chat)
        assertTrue(IntentRouter.route("hello", true) is IntentRouter.Route.Chat)
    }

    @Test
    fun newTaskWhileRunningQueues() {
        val r1 = IntentRouter.route("whatsapp kholo", true)
        val r2 = IntentRouter.route("flashlight on karo", true)
        val r3 = IntentRouter.route("scroll down", true)

        assertTrue(r1 is IntentRouter.Route.QueueTask)
        assertTrue(r2 is IntentRouter.Route.QueueTask)
        assertTrue(r3 is IntentRouter.Route.QueueTask)
    }

    @Test
    fun ambiguousTextWhileRunningDefaultsToChatNeverPauses() {
        assertTrue(IntentRouter.route("hmm theek hai", true) is IntentRouter.Route.Chat)
        assertTrue(IntentRouter.route("aur batao", true) is IntentRouter.Route.Chat)
    }

    @Test
    fun cancelWhileIdleIsNotTreatedAsCancel() {
        // Nothing running -> the text itself goes to the operator (chat/task).
        assertTrue(IntentRouter.route("stop", false) is IntentRouter.Route.RunTask)
    }

    @Test
    fun riskyGateAnswerWordsRouteToChatWhileRunning() {
        // v7 M4: the haan/nahi voice gate latches on Intent.Chat - these
        // words must never become QueueTask/Cancel while a task is running.
        for (word in listOf("haan", "nahi", "yes", "no", "sure", "skip", "ok", "nope")) {
            assertTrue(
                "expected Chat for \"$word\"",
                IntentRouter.route(word, true) is IntentRouter.Route.Chat
            )
        }
    }
}
