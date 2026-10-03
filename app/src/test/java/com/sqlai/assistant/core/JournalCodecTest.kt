package com.sqlai.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Journal JSON round-trip - corrupt or partial input must never crash. */
class JournalCodecTest {

    private fun sampleJournal(): TaskJournal.Journal = TaskJournal.Journal(
        taskId = "8f2c1a99-1234-4abc-9def-000000000001",
        task = "WhatsApp pe Rahul ko invoice bhej do",
        createdAt = 1_000L,
        updatedAt = 2_500L,
        status = TaskJournal.STATUS_RUNNING,
        step = 4,
        lastPackage = "com.whatsapp",
        lastAction = "tap_text",
        lastScreenHash = "abc123",
        subgoals = listOf(
            TaskJournal.Subgoal("Open WhatsApp", TaskJournal.GOAL_DONE, "seen", 1),
            TaskJournal.Subgoal("Open Rahul chat", TaskJournal.GOAL_PENDING),
            TaskJournal.Subgoal("Blocked on pin", TaskJournal.GOAL_BLOCKED)
        ),
        pendingCallMessage = "hello"
    )

    @Test
    fun roundTripKeepsEveryField() {
        val original = sampleJournal()
        val decoded = TaskJournal.decode(TaskJournal.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun nullFieldsSurviveRoundTrip() {
        val minimal = TaskJournal.Journal(
            taskId = "t1",
            task = "task",
            createdAt = 0L,
            updatedAt = 1L,
            status = TaskJournal.STATUS_RUNNING
        )
        assertEquals(minimal, TaskJournal.decode(TaskJournal.encode(minimal)))
    }

    @Test
    fun corruptJsonReturnsNull() {
        assertNull(TaskJournal.decode("{task: broken"))
        assertNull(TaskJournal.decode("[]"))
        assertNull(TaskJournal.decode("not json at all"))
        assertNull(TaskJournal.decode("""{"subgoals": "oops"}"""))
    }

    @Test
    fun failReasonSurvivesRoundTrip() {
        val original = sampleJournal().copy(
            status = TaskJournal.STATUS_FAILED,
            failReason = "no progress after 20 attempts"
        )

        val decoded = TaskJournal.decode(TaskJournal.encode(original))

        assertEquals(original, decoded)
        assertEquals("no progress after 20 attempts", decoded!!.failReason)
    }

    @Test
    fun legacyJsonWithoutFailReasonStillDecodes() {
        // pre-v7 M4 payloads carry no failReason key at all.
        val legacy =
            """{"taskId":"t","task":"old task","createdAt":1,"updatedAt":2,"status":"running"}"""

        val decoded = TaskJournal.decode(legacy)

        assertEquals("old task", decoded!!.task)
        assertNull(decoded.failReason)
    }

    @Test
    fun blankInputReturnsNull() {
        assertNull(TaskJournal.decode(null))
        assertNull(TaskJournal.decode(""))
        assertNull(TaskJournal.decode("   "))
    }

    @Test
    fun partialJsonGetsSafeDefaults() {
        val decoded = TaskJournal.decode("""{"task":"half written","updatedAt":42}""")
        assertTrue(decoded != null)
        assertEquals("half written", decoded!!.task)
        assertEquals(TaskJournal.STATUS_RUNNING, decoded.status)
        assertEquals(0, decoded.step)
        assertTrue(decoded.subgoals.isEmpty())
        assertTrue(decoded.taskId.isNotBlank())
    }

    @Test
    fun missingTaskFieldReturnsNull() {
        assertNull(TaskJournal.decode("""{"updatedAt":42,"subgoals":[]}"""))
    }
}
