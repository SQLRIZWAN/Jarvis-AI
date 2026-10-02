package com.sqlai.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AgentParser contract: raw model JSON -> AgentPlan, garbage -> safe fallback. */
class AgentParserTest {

    @Test
    fun fullPlanParsesAllFields() {
        val raw = """
            {
              "thought": "Chat list is visible, opening Mohan",
              "reply": "Opening   WhatsApp now",
              "done": false,
              "milestone": "Open WhatsApp",
              "actions": [
                {"type": "open_app", "app": "whatsapp"},
                {"type": "tap_text", "text": "Send"}
              ],
              "expect": {"type": "text_visible", "value": "Online", "area": "top"}
            }
        """.trimIndent()

        val plan = AgentParser.parse(raw)

        assertEquals("Chat list is visible, opening Mohan", plan.thought)
        assertEquals("Opening WhatsApp now", plan.reply)
        assertFalse(plan.done)
        assertEquals("Open WhatsApp", plan.milestone)
        assertEquals(2, plan.actions.size)
        assertEquals("open_app", plan.actions[0].type)
        assertEquals("whatsapp", plan.actions[0].app)
        assertEquals("text_visible", plan.expectType)
        assertEquals("Online", plan.expectValue)
        assertEquals("top", plan.expectArea)
    }

    @Test
    fun markdownFencedJsonParses() {
        val raw = """
            ```json
            {"thought":"t","reply":"done","done":true,"actions":[],"expect":{"type":"none"}}
            ```
        """.trimIndent()

        val plan = AgentParser.parse(raw)

        assertTrue(plan.done)
        assertEquals("done", plan.reply)
        assertTrue(plan.actions.isEmpty())
        assertEquals("none", plan.expectType)
        assertEquals("", plan.milestone)
    }

    @Test
    fun noJsonFallsBackToSafeThinkingPlan() {
        val plan = AgentParser.parse("I cannot answer right now")

        assertEquals("Thinking...", plan.reply)
        assertFalse(plan.done)
        assertTrue(plan.actions.isEmpty())
        assertEquals("none", plan.expectType)
    }

    @Test
    fun malformedJsonFallsBackWithoutCrashing() {
        val plan = AgentParser.parse("""{"thought": }""")

        assertEquals("Working on it", plan.reply)
        assertFalse(plan.done)
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun missingExpectDefaultsToNone() {
        val plan = AgentParser.parse("""{"thought":"x","reply":"ok","actions":[]}""")

        assertEquals("none", plan.expectType)
        assertEquals("", plan.expectValue)
        assertEquals("", plan.expectArea)
    }
}
