package com.sqlai.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PlanParser contract incl. the swipe x1/y1 regression (prompt schema keys). */
class PlanParserTest {

    @Test
    fun swipeX1Y1SchemaParsesIntoCoords() {
        val raw = """
            {"reply":"swiping","actions":[
              {"type":"swipe","x1":100,"y1":800,"x2":100,"y2":300,"duration_ms":250}
            ]}
        """.trimIndent()

        val action = PlanParser.parse(raw).actions.single()

        assertEquals("swipe", action.type)
        assertEquals(100, action.x ?: -1)
        assertEquals(800, action.y ?: -1)
        assertEquals(100, action.x2 ?: -1)
        assertEquals(300, action.y2 ?: -1)
        assertEquals(250, action.durationMs ?: -1)
    }

    @Test
    fun tapCoordsAndTypeAliasesParse() {
        val raw = """
            {"reply":"t","actions":[
              {"type":"TAP","x":45,"y":900},
              {"type":"open_app","package":"com.whatsapp"},
              {"type":"type_text","message":"hello"}
            ]}
        """.trimIndent()

        val actions = PlanParser.parse(raw).actions

        assertEquals("tap", actions[0].type)
        assertEquals(45, actions[0].x ?: -1)
        assertEquals(900, actions[0].y ?: -1)
        assertEquals("com.whatsapp", actions[1].app)
        assertEquals("hello", actions[2].text)
    }

    @Test
    fun emptyTypeEntriesAreSkipped() {
        val raw = """{"reply":"t","actions":[{"type":"  "},{"type":"wait","ms":500}]}"""

        val actions = PlanParser.parse(raw).actions

        assertEquals(1, actions.size)
        assertEquals("wait", actions[0].type)
        assertEquals(500, actions[0].ms ?: -1)
    }

    @Test
    fun actionRefFieldParsesForEveryActionType() {
        val raw = """
            {"reply":"t","actions":[
              {"type":"tap","ref":"r3"},
              {"type":"tap_text","ref":"r7","text":"Send"},
              {"type":"tap","x":10,"y":20}
            ]}
        """.trimIndent()

        val actions = PlanParser.parse(raw).actions

        assertEquals("r3", actions[0].ref)
        assertEquals("r7", actions[1].ref)
        assertEquals("Send", actions[1].text)
        assertNull(actions[2].ref)
    }

    @Test
    fun replyIsCleanedAndDefaulted() {
        val plan = PlanParser.parse("""{"reply":"  lots   of   space  "}""")
        assertEquals("lots of space", plan.reply)

        val fallback = PlanParser.parse("no json here")
        assertEquals("no json here", fallback.reply)
        assertTrue(fallback.actions.isEmpty())
    }

    @Test
    fun extractJsonHandlesFencesAndChatter() {
        val fenced = PlanParser.extractJsonObject("text ```json\n{\"a\":1}\n``` tail")
        assertEquals("""{"a":1}""", fenced)

        val noisy = PlanParser.extractJsonObject("Sure! Here you go: {\"a\": 1} hope it helps")
        assertEquals("""{"a": 1}""", noisy)
    }

    @Test
    fun extractJsonIgnoresBracesInsideStrings() {
        val raw = """prefix {"reply":"curly } brace { inside"} suffix"""
        assertEquals("""{"reply":"curly } brace { inside"}""", PlanParser.extractJsonObject(raw))
    }

    @Test
    fun extractJsonReturnsNullWhenUnbalanced() {
        assertNull(PlanParser.extractJsonObject("{\"a\": 1"))
        assertNull(PlanParser.extractJsonObject("no object here"))
    }
}
