package com.sqlai.assistant.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AiClient.isValidJsonPlan] contract for requireJson replies. */
class JsonPlanValidateTest {

    @Test
    fun fencedValidPlanWithActionsIsValid() {
        val raw = "```json\n{\"reply\":\"on it\",\"actions\":[{\"type\":\"wait\",\"ms\":100}]}\n```"

        assertTrue(AiClient.isValidJsonPlan(raw))
    }

    @Test
    fun proseWrappedJsonIsValid() {
        val raw = "Sure! Here is the plan: {\"reply\":\"hi\",\"actions\":[]} - done."

        assertTrue(AiClient.isValidJsonPlan(raw))
    }

    @Test
    fun emptyActionsArrayIsValid() {
        assertTrue(AiClient.isValidJsonPlan("""{"reply":"nothing to do","actions":[]}"""))
    }

    @Test
    fun missingActionsKeyIsInvalid() {
        assertFalse(AiClient.isValidJsonPlan("""{"reply":"hi"}"""))
    }

    @Test
    fun unbalancedBracesAreInvalid() {
        assertFalse(AiClient.isValidJsonPlan("""{"reply":"hi","actions":[{"type":"wait"""))
        assertFalse(AiClient.isValidJsonPlan("Sure, the plan is {\"reply\": \"hi\""))
    }

    @Test
    fun plainTextIsInvalid() {
        assertFalse(AiClient.isValidJsonPlan("I cannot open that app right now."))
        assertFalse(AiClient.isValidJsonPlan(""))
    }

    @Test
    fun actionsAsStringIsInvalid() {
        assertFalse(AiClient.isValidJsonPlan("""{"reply":"hi","actions":"none"}"""))
    }

    @Test
    fun actionsAsNullOrObjectIsInvalid() {
        assertFalse(AiClient.isValidJsonPlan("""{"reply":"hi","actions":null}"""))
        assertFalse(AiClient.isValidJsonPlan("""{"reply":"hi","actions":{"type":"tap"}}"""))
    }

    @Test
    fun proseWithoutJsonIsInvalid() {
        assertFalse(AiClient.isValidJsonPlan("```json\njust prose, no object\n```"))
    }
}
