package com.sqlai.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG #1 / BUG #2 resume rules: a rephrased task, a failed attempt and a
 * process restart must NEVER lose the completed milestones - only success
 * clear or a brand-new run starts from zero.
 */
class TaskJournalResumeTest {

    private val t0 = 1_000_000L

    @Test
    fun rephrasedTaskKeepsCompletedMilestones() {
        val g1 = "Open WhatsApp"
        val g2 = "Send hi to Mohan"
        val first = TaskJournal.resume(
            null, "open whatsapp and send hi to mohan", listOf(g1, g2), t0
        )
        val progressed = TaskJournal.withMilestone(first, g1, t0 + 1_000)

        val rephrased = TaskJournal.resume(
            progressed, "whatsapp pe mohan ko message bhej do", listOf(g2), t0 + 2_000
        )

        assertEquals(listOf(g1), rephrased.completed)
        assertTrue(rephrased.remaining.contains(g2))
        assertEquals("whatsapp pe mohan ko message bhej do", rephrased.task)
        assertEquals(first.taskId, rephrased.taskId)
        assertEquals(TaskJournal.STATUS_RUNNING, rephrased.status)
    }

    @Test
    fun failureThenRetryResumesFromRecordedPosition() {
        val goals = listOf("Open Instagram", "Open profile", "Tap heart")
        val first = TaskJournal.resume(null, "like my latest reel", goals, t0)
        val progressed = TaskJournal.withMilestone(
            TaskJournal.withMilestone(first, "Open Instagram", t0 + 1),
            "Open profile", t0 + 2
        )

        // Failure = journal untouched (v7 never clears on failure).
        val retry = TaskJournal.resume(
            progressed, "reel like kar do", listOf("Tap heart"), t0 + 60_000
        )

        assertEquals(listOf("Open Instagram", "Open profile"), retry.completed)
        assertEquals(listOf("Tap heart"), retry.remaining)
    }

    @Test
    fun failedStatusResumesFreshAndFlipsBackToRunning() {
        val first = TaskJournal.resume(null, "open settings", listOf("a", "b"), t0)
        val progressed = TaskJournal.withMilestone(first, "a", t0 + 100)
        val failed = progressed.copy(
            status = TaskJournal.STATUS_FAILED,
            failReason = "no progress after 20 attempts",
            updatedAt = t0 + 200
        )

        val retry = TaskJournal.resume(failed, "open settings", listOf("a", "b"), t0 + 300)

        assertEquals(first.taskId, retry.taskId)
        assertEquals(TaskJournal.STATUS_RUNNING, retry.status)
        assertNull(retry.failReason)
        assertEquals(listOf("a"), retry.completed)
        assertEquals(listOf("b"), retry.remaining)
    }

    @Test
    fun staleFailedJournalOlderThan24hStartsNewOne() {
        val failed = TaskJournal.resume(null, "old", listOf("g1"), t0)
            .copy(
                status = TaskJournal.STATUS_FAILED,
                failReason = "stuck",
                updatedAt = t0
            )

        val next = TaskJournal.resume(
            failed, "old", listOf("g1"), t0 + 25 * 60 * 60 * 1000L
        )

        assertTrue(next.completed.isEmpty())
        assertNull(next.failReason)
        assertNotEquals(failed.taskId, next.taskId)
    }

    @Test
    fun processDeathRestoresJournalFromJson() {
        val first = TaskJournal.resume(
            null, "call Mohan after dinner", listOf("Open contacts", "Call Mohan"), t0
        )
        val progressed = TaskJournal.withMilestone(first, "Open contacts", t0 + 500)

        // Fresh decode = new process / fresh TaskStateManager instance.
        val restored = TaskJournal.decode(TaskJournal.encode(progressed))
        val resumed = TaskJournal.resume(
            restored, "mohan ko call laga do", listOf("Call Mohan"), t0 + 60_000
        )

        assertEquals(progressed.taskId, resumed.taskId)
        assertEquals(listOf("Open contacts"), resumed.completed)
        assertEquals(listOf("Call Mohan"), resumed.remaining)
    }

    @Test
    fun afterSuccessClearNextRunStartsFresh() {
        val first = TaskJournal.resume(null, "set wifi", listOf("Open settings"), t0)
        val progressed = TaskJournal.withMilestone(first, "Open settings", t0 + 100)

        // clear() removes the journal - next begin must not inherit progress.
        val storedAfterClear: String? = null
        val next = TaskJournal.resume(
            TaskJournal.decode(storedAfterClear), "set wifi", listOf("Open settings"), t0 + 5_000
        )

        assertTrue(next.completed.isEmpty())
        assertNotEquals(progressed.taskId, next.taskId)
    }

    @Test
    fun staleJournalOlderThan24hStartsNewOne() {
        val first = TaskJournal.resume(null, "old task", listOf("step one"), t0)
        val progressed = TaskJournal.withMilestone(first, "step one", t0 + 100)

        val later = TaskJournal.resume(
            progressed, "old task", listOf("step one"), t0 + 25 * 60 * 60 * 1000L
        )

        assertTrue(later.completed.isEmpty())
        assertNotEquals(first.taskId, later.taskId)
    }

    @Test
    fun nonRunningJournalIsNotResumed() {
        val superseded = TaskJournal.resume(null, "old", listOf("a"), t0)
            .copy(status = TaskJournal.STATUS_SUPERSEDED)

        val next = TaskJournal.resume(superseded, "new task", listOf("b"), t0 + 10)

        assertTrue(next.completed.isEmpty())
        assertEquals(listOf("b"), next.remaining.map { it })
        assertNotEquals(superseded.taskId, next.taskId)
    }

    @Test
    fun newSubgoalsMergeWithoutLosingProofAndAttempts() {
        val first = TaskJournal.resume(null, "t", listOf("g1", "g2"), t0)
        val proven = first.copy(
            subgoals = first.subgoals.map {
                if (it.label == "g1") it.copy(status = TaskJournal.GOAL_DONE, proof = "seen", attempts = 2)
                else it
            }
        )

        val merged = TaskJournal.resume(proven, "t", listOf("g2", "g3"), t0 + 1_000)

        assertEquals(listOf("g1", "g2", "g3"), merged.subgoals.map { it.label })
        assertEquals("seen", merged.subgoals.first { it.label == "g1" }.proof)
        assertEquals(2, merged.subgoals.first { it.label == "g1" }.attempts)
        assertEquals(listOf("g1"), merged.completed)
    }

    @Test
    fun stateBlockShowsCompletedRemainingAndPosition() {
        val journal = TaskJournal.resume(null, "open settings", listOf("a", "b"), t0)
            .copy(step = 3, lastPackage = "com.android.settings")
        val done = TaskJournal.withMilestone(journal, "a", t0 + 1)

        val block = TaskJournal.stateBlock(done)

        assertTrue(block.contains("COMPLETED (never redo these)"))
        assertTrue(block.contains("STILL REMAINING"))
        assertTrue(block.contains("LAST POSITION"))
        assertTrue(block.contains("package=com.android.settings"))
        assertTrue(block.contains("step=3"))
        assertTrue(block.contains("a") && block.contains("b"))
    }

    @Test
    fun pinnedBlockAlwaysCarriesTaskHeader() {
        val journal = TaskJournal.resume(null, "send invoice to Rahul", listOf("a"), t0)

        val pin = TaskJournal.pinnedBlock(journal)

        assertTrue(pin.startsWith("TASK: send invoice to Rahul"))
        assertTrue(pin.contains("STILL REMAINING"))
        assertEquals("", TaskJournal.pinnedBlock(null))
        assertEquals("", TaskJournal.stateBlock(null))
    }
}
