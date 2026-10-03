package com.sqlai.assistant.agent

import com.sqlai.assistant.ai.Action
import com.sqlai.assistant.ai.AgentPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Critic contract: order-independent screen hashes, risky-action gating,
 * pre-flight problem list and deterministic subgoal auto-marking.
 */
class CriticAgentTest {

    private val liveScreen = listOf(
        "FOREGROUND_APP=com.whatsapp",
        "\"Send\" [924,1780 148x96] clickable",
        "\"Message\" [36,1780 600x96] editable",
        "desc=\"Attach\" [40,1700 90x90] clickable"
    ).joinToString("\n")

    private fun plan(actions: List<Action>, done: Boolean = false) = AgentPlan(
        thought = "t",
        reply = "r",
        done = done,
        actions = actions,
        expectType = "none",
        expectValue = ""
    )

    // ------------------------------------------------------------- screenHash

    @Test
    fun identicalElementsInDifferentOrderHashIdentically() {
        val a = listOf(
            "FOREGROUND_APP=com.whatsapp",
            "\"Send\" [924,1780 148x96] clickable",
            "\"Message\" [36,1780 600x96] editable"
        ).joinToString("\n")
        val b = listOf(
            "FOREGROUND_APP=com.whatsapp",
            "\"Message\" [36,1780 600x96] editable",
            "\"Send\" [924,1780 148x96] clickable"
        ).joinToString("\n")

        assertEquals(CriticAgent.screenHash(a), CriticAgent.screenHash(b))
    }

    @Test
    fun changedCoordinateProducesDifferentHash() {
        val before = "FOREGROUND_APP=com.whatsapp\n\"Send\" [924,1780 148x96] clickable"
        val after = "FOREGROUND_APP=com.whatsapp\n\"Send\" [930,1780 148x96] clickable"

        assertNotEquals(CriticAgent.screenHash(before), CriticAgent.screenHash(after))
    }

    @Test
    fun changedLabelProducesDifferentHash() {
        val before = "FOREGROUND_APP=com.whatsapp\n\"Send\" [924,1780 148x96] clickable"
        val after = "FOREGROUND_APP=com.whatsapp\n\"Sent\" [924,1780 148x96] clickable"

        assertNotEquals(CriticAgent.screenHash(before), CriticAgent.screenHash(after))
    }

    @Test
    fun foregroundAppHeaderIsIgnored() {
        val whatsapp = listOf(
            "FOREGROUND_APP=com.whatsapp",
            "\"Send\" [924,1780 148x96] clickable",
            "\"Message\" [36,1780 600x96] editable"
        ).joinToString("\n")
        val instagram = listOf(
            "FOREGROUND_APP=com.instagram.android",
            "\"Send\" [924,1780 148x96] clickable",
            "\"Message\" [36,1780 600x96] editable"
        ).joinToString("\n")

        assertEquals(CriticAgent.screenHash(whatsapp), CriticAgent.screenHash(instagram))
    }

    @Test
    fun repeatedCallsAreDeterministicFullHex() {
        val first = CriticAgent.screenHash(liveScreen)
        val second = CriticAgent.screenHash(liveScreen)
        val third = CriticAgent.screenHash(liveScreen)

        assertEquals(first, second)
        assertEquals(second, third)
        assertTrue(Regex("[0-9a-f]{64}").matches(first))
    }

    // ----------------------------------------------------------- needsConfirm

    @Test
    fun riskyActionTypesNeedConfirmation() {
        assertTrue(CriticAgent.needsConfirm(Action(type = "send_message")))
        assertTrue(CriticAgent.needsConfirm(Action(type = "delete")))
        assertTrue(CriticAgent.needsConfirm(Action(type = "pay")))
        assertTrue(CriticAgent.needsConfirm(Action(type = "wa_call")))
    }

    @Test
    fun safeActionTypesNeverNeedConfirmation() {
        assertFalse(CriticAgent.needsConfirm(Action(type = "tap")))
        assertFalse(CriticAgent.needsConfirm(Action(type = "open_app")))
    }

    // ----------------------------------------------------------- preValidate

    @Test
    fun cleanPlanAgainstLiveScreenHasNoProblems() {
        val check = CriticAgent.preValidate(
            plan(
                listOf(
                    Action(type = "open_app", app = "com.whatsapp"),
                    Action(type = "tap", x = 924, y = 1780)
                )
            ),
            liveScreen
        )

        assertTrue(check.problems.isEmpty())
        assertTrue(check.confirmNeeded.isEmpty())
    }

    @Test
    fun unknownActionTypeIsReported() {
        val check = CriticAgent.preValidate(plan(listOf(Action(type = "teleport"))), liveScreen)

        assertEquals(listOf("unknown action type teleport"), check.problems)
    }

    @Test
    fun blankActionTypeIsReportedOnce() {
        val check = CriticAgent.preValidate(plan(listOf(Action(type = "  "))), liveScreen)

        assertEquals(listOf("blank action type"), check.problems)
    }

    @Test
    fun tapWithoutRefOrCoordinatesIsReported() {
        val check = CriticAgent.preValidate(plan(listOf(Action(type = "tap"))), liveScreen)

        assertEquals(listOf("tap without ref or coordinates"), check.problems)
    }

    @Test
    fun blankScreenIsReported() {
        val check = CriticAgent.preValidate(plan(emptyList()), "")

        assertEquals(listOf("no live screen"), check.problems)
    }

    @Test
    fun screenUnavailableMessageIsReported() {
        val check = CriticAgent.preValidate(
            plan(emptyList()),
            "Screen unavailable (accessibility off)"
        )

        assertEquals(listOf("no live screen"), check.problems)
    }

    @Test
    fun riskyActionGoesToConfirmNeededNotProblems() {
        val risky = Action(type = "call", text = "9876543210")

        val check = CriticAgent.preValidate(plan(listOf(risky)), liveScreen)

        assertTrue(check.problems.isEmpty())
        assertEquals(listOf(risky), check.confirmNeeded)
    }

    @Test
    fun doneWithPendingActionsIsReported() {
        val check = CriticAgent.preValidate(
            plan(listOf(Action(type = "wait", ms = 500)), done = true),
            liveScreen
        )

        assertEquals(listOf("done with pending actions"), check.problems)
    }

    @Test
    fun malformedGestureActionsAreReportedInListOrder() {
        val check = CriticAgent.preValidate(
            plan(
                listOf(
                    Action(type = "tap_text"),
                    Action(type = "swipe", x = 10, y = 10),
                    Action(type = "scroll", direction = "left")
                )
            ),
            liveScreen
        )

        assertEquals(
            listOf(
                "tap_text without text",
                "swipe without coordinates",
                "bad scroll direction"
            ),
            check.problems
        )
    }

    // ---------------------------------------------------- findCompletedSubgoal

    @Test
    fun firstMatchingLabelWinsCaseInsensitively() {
        val remaining = listOf("Open Settings", "Tap Wi-Fi")
        val screenAfter = "open settings then tap wi-fi"

        assertEquals(
            "Open Settings",
            CriticAgent.findCompletedSubgoal(remaining, screenAfter)
        )
    }

    @Test
    fun labelsShorterThanFourCharactersAreSkipped() {
        val remaining = listOf("OK", "Tap Wi-Fi")

        assertEquals(
            "Tap Wi-Fi",
            CriticAgent.findCompletedSubgoal(remaining, "ok tap wi-fi")
        )
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(CriticAgent.findCompletedSubgoal(listOf("Open Settings"), "home screen"))
        assertNull(CriticAgent.findCompletedSubgoal(emptyList(), "home screen"))
    }
}
