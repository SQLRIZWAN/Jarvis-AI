package com.sqlai.assistant.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FlowMacros pure-JVM contract: step JSON, placeholders, builtins and matching. */
class FlowMacroTest {

    @Test
    fun parseStepsReadsAllFields() {
        val json = """
            [
              {"type":"open_app","text":"whatsapp"},
              {"type":"tap","x":120,"y":640},
              {"type":"wait","ms":1500},
              {"type":"swipe","x":500,"y":900,"direction":"up"}
            ]
        """.trimIndent()

        val steps = FlowMacros.parseSteps(json)
        assertNotNull(steps)
        val parsed = steps!!

        assertEquals(4, parsed.size)
        assertEquals("open_app", parsed[0].type)
        assertEquals("whatsapp", parsed[0].text)
        assertEquals("tap", parsed[1].type)
        assertEquals(120, parsed[1].x ?: -1)
        assertEquals(640, parsed[1].y ?: -1)
        assertEquals("wait", parsed[2].type)
        assertEquals(1500L, parsed[2].ms ?: -1L)
        assertEquals("swipe", parsed[3].type)
        assertEquals(500, parsed[3].x ?: -1)
        assertEquals(900, parsed[3].y ?: -1)
        assertEquals("up", parsed[3].direction)
    }

    @Test
    fun parseStepsRejectsBlankAndInvalidJson() {
        assertNull(FlowMacros.parseSteps(""))
        assertNull(FlowMacros.parseSteps("   "))
        assertNull(FlowMacros.parseSteps("not json at all"))
        assertNull(FlowMacros.parseSteps("""{"type":"wait","ms":500}"""))
    }

    @Test
    fun parseStepsRejectsElementWithoutType() {
        assertNull(FlowMacros.parseSteps("""[{"text":"wifi"}]"""))
        assertNull(FlowMacros.parseSteps("""[{"type":"wait"},{"x":10,"y":20}]"""))
    }

    @Test
    fun parseStepsRejectsEmptyType() {
        assertNull(FlowMacros.parseSteps("""[{"type":""}]"""))
        assertNull(FlowMacros.parseSteps("""[{"type":"   "}]"""))
    }

    @Test
    fun substituteFillsKnownPlaceholder() {
        val step = FlowMacros.Step(type = "open_app", text = "{app}")

        val out = FlowMacros.substitute(step, mapOf("app" to "chrome"))

        assertEquals("chrome", out.text)
        assertEquals("open_app", out.type)
    }

    @Test
    fun substituteKeepsUnknownPlaceholder() {
        val step = FlowMacros.Step(type = "open_settings", text = "{wifi} for {user}")

        val out = FlowMacros.substitute(step, mapOf("wifi" to "network"))

        assertEquals("network for {user}", out.text)
        assertEquals(step, FlowMacros.substitute(step, emptyMap()))
    }

    @Test
    fun builtinsHaveExactlyTheFourNames() {
        val names = FlowMacros.builtins().map { it.name }

        assertEquals(4, names.size)
        assertEquals(
            setOf("settings_wifi", "wa_open_chat", "reel_like", "app_open"),
            names.toSet()
        )
    }

    @Test
    fun builtinsOnlyUseSupportedStepTypes() {
        val supported = setOf(
            "open_app", "open_settings", "wait_for", "wait", "tap",
            "tap_pct", "tap_text", "swipe", "back", "home"
        )

        FlowMacros.builtins().forEach { macro ->
            assertTrue("no steps in ${macro.name}", macro.steps.isNotEmpty())
            macro.steps.forEach { step -> assertTrue("bad ${step.type}", step.type in supported) }
        }
    }

    @Test
    fun appOpenBuiltinCarriesAppPlaceholder() {
        val appOpen = FlowMacros.builtins().first { it.name == "app_open" }

        val step = appOpen.steps.single()
        assertEquals("open_app", step.type)
        assertEquals("{app}", step.text)
        assertTrue(appOpen.triggers.isEmpty())
    }

    @Test
    fun matchFlowCommandSelectsMacro() {
        val hit = FlowMacros.match("SQL flow settings_wifi")
        assertNotNull(hit)
        val matched = hit!!

        assertEquals("settings_wifi", matched.macro.name)
        assertTrue(matched.params.isEmpty())
    }

    @Test
    fun matchTriggerPhraseSelectsMacro() {
        val hit = FlowMacros.match("wifi on karo")
        assertNotNull(hit)
        val matched = hit!!

        assertEquals("settings_wifi", matched.macro.name)
        assertTrue(matched.params.isEmpty())
    }

    @Test
    fun matchOpenAppFormCapturesAppParam() {
        val hit = FlowMacros.match("open chrome app")
        assertNotNull(hit)
        val matched = hit!!

        assertEquals("app_open", matched.macro.name)
        assertEquals("chrome", matched.params["app"])
    }

    @Test
    fun matchFlowAppOpenFormCapturesAppParam() {
        val hit = FlowMacros.match("flow app_open instagram")
        assertNotNull(hit)
        val matched = hit!!

        assertEquals("app_open", matched.macro.name)
        assertEquals("instagram", matched.params["app"])
    }

    @Test
    fun matchRequiresWordBoundaries() {
        assertNull(FlowMacros.match("wifi online"))
        assertNull(FlowMacros.match("chalu"))
        assertNull(FlowMacros.match(""))
    }
}
