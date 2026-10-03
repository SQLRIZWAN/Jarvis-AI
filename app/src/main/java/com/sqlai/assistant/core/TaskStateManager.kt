package com.sqlai.assistant.core

import android.content.Context
import android.content.SharedPreferences
import com.sqlai.assistant.SqlAiApp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * v7 TASK JOURNAL - persistent resume state (BUG #1 / BUG #2 / BUG #3).
 *
 * The journal decides resume - NOT task-string similarity. Any ACTIVE
 * journal (status=running, updated_at < 24h) is resumed with its completed
 * milestones no matter how the user rephrases the task, and progress is
 * NEVER deleted on failure: clear() runs only on verified success or an
 * explicit user stop. Storage: SharedPreferences "task_journal" + org.json
 * (the app's existing pattern - no Room/KSP).
 *
 * Every model turn gets [stateBlock] (COMPLETED / STILL REMAINING / LAST
 * POSITION) and the system prompt gets [pinnedTaskBlock], so history trim
 * can never drop the original TASK header.
 *
 * The pure logic + JSON codec live in [TaskJournal] (JVM unit-testable).
 */
object TaskStateManager {

    private const val PREFS = "task_journal"
    private const val KEY_JOURNAL = "journal"

    /** Public view kept identical for existing call sites (SQLAgentCoreV5). */
    data class Snapshot(
        val task: String,
        val subgoals: List<String>,
        val completed: List<String>,
        val pendingCallMessage: String?
    ) {
        val remaining: List<String>
            get() = subgoals.filter { it !in completed }
    }

    @Volatile
    private var current: TaskJournal.Journal? = null

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------- lifecycle

    /** Start (or resume) a task from the ACTIVE journal - wording never matters. */
    @Synchronized
    fun begin(context: Context, task: String, subgoals: List<String>): Snapshot {
        val stored = TaskJournal.decode(prefs(context).getString(KEY_JOURNAL, null))
        val journal = TaskJournal.resume(
            stored, task, subgoals.distinct(), System.currentTimeMillis()
        )
        persist(context, journal)
        LogBus.log(
            "[TASK] begin ${journal.taskId.take(8)}: ${journal.completed.size} done / " +
                "${journal.remaining.size} remaining",
            LogLevel.INFO
        )
        return snapshotOf(journal)
    }

    @Synchronized
    fun markCompleted(context: Context, milestone: String) {
        val journal = currentOrStored(context) ?: return
        if (milestone.isBlank() || milestone in journal.completed) return
        val next = TaskJournal.withMilestone(journal, milestone, System.currentTimeMillis())
        persist(context, next)
        LogBus.log(
            "[TASK] milestone done: $milestone (${next.completed.size}/${next.subgoals.size})",
            LogLevel.SUCCESS
        )
    }

    /**
     * v7 M4: persist FAILED status + reason. The journal is NEVER deleted on
     * failure - the next attempt resumes from the recorded position, and the
     * reason is injected into the state block so the model avoids it.
     */
    @Synchronized
    fun markFailed(context: Context, reason: String) {
        val journal = currentOrStored(context) ?: return
        persist(
            context,
            journal.copy(
                status = TaskJournal.STATUS_FAILED,
                failReason = reason.take(160),
                updatedAt = System.currentTimeMillis()
            )
        )
        LogBus.log("[TASK] FAILED: $reason (journal kept for resume)", LogLevel.WARN)
    }

    @Synchronized
    fun setPendingCallMessage(context: Context, message: String?) {
        val journal = currentOrStored(context) ?: return
        persist(
            context,
            journal.copy(pendingCallMessage = message, updatedAt = System.currentTimeMillis())
        )
    }

    /** Human-readable state block injected into every model turn. */
    fun stateBlock(): String = TaskJournal.stateBlock(current)

    /** TASK + state pinned into the system prompt (survives history trim). */
    fun pinnedTaskBlock(): String = TaskJournal.pinnedBlock(current)

    fun snapshot(): Snapshot? =
        (current ?: stored(SqlAiApp.instance))?.let { snapshotOf(it) }

    /** Clear ONLY on verified success or explicit user stop - NEVER on failure. */
    @Synchronized
    fun clear(context: Context) {
        current = null
        prefs(context).edit().remove(KEY_JOURNAL).apply()
    }

    // --------------------------------------------------------------- storage

    private fun currentOrStored(context: Context): TaskJournal.Journal? =
        current ?: stored(context)

    private fun stored(context: Context): TaskJournal.Journal? {
        current?.let { return it }
        val journal = TaskJournal.decode(prefs(context).getString(KEY_JOURNAL, null))
            ?: return null
        current = journal
        return journal
    }

    private fun persist(context: Context, journal: TaskJournal.Journal) {
        current = journal
        prefs(context).edit()
            .putString(KEY_JOURNAL, TaskJournal.encode(journal))
            .apply()
    }

    private fun snapshotOf(j: TaskJournal.Journal): Snapshot = Snapshot(
        task = j.task,
        subgoals = j.subgoals.map { it.label },
        completed = j.completed,
        pendingCallMessage = j.pendingCallMessage
    )
}

/**
 * Pure journal logic + JSON codec - no Android imports so the resume rules
 * are unit-testable on the JVM.
 */
object TaskJournal {

    const val STATUS_RUNNING = "running"
    const val STATUS_DONE = "done"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"
    const val STATUS_SUPERSEDED = "superseded"

    const val GOAL_PENDING = "pending"
    const val GOAL_DONE = "done"
    const val GOAL_BLOCKED = "blocked"

    private const val FRESH_MS = 24 * 60 * 60 * 1000L

    /** One ordered micro-goal with its verification status. */
    data class Subgoal(
        val label: String,
        val status: String = GOAL_PENDING,
        val proof: String? = null,
        val attempts: Int = 0
    )

    /** Full journal schema (persisted as JSON). */
    data class Journal(
        val taskId: String,
        val task: String,
        val createdAt: Long,
        val updatedAt: Long,
        val status: String,
        val step: Int = 0,
        val lastPackage: String? = null,
        val lastAction: String? = null,
        val lastScreenHash: String? = null,
        val subgoals: List<Subgoal> = emptyList(),
        val pendingCallMessage: String? = null,
        val failReason: String? = null
    ) {
        val completed: List<String>
            get() = subgoals.filter { it.status == GOAL_DONE }.map { it.label }

        val remaining: List<String>
            get() = subgoals.filter { it.status != GOAL_DONE }.map { it.label }
    }

    /**
     * Resume the ACTIVE journal (running OR failed + fresh) regardless of
     * wording - rephrased tasks keep their completed milestones, and a FAILED
     * attempt flips back to running with its position intact (v7 M4: never
     * restart from step 1). Anything else starts a brand-new journal with a
     * fresh UUID.
     */
    fun resume(
        stored: Journal?,
        task: String,
        subgoals: List<String>,
        now: Long
    ): Journal {
        val active = stored != null &&
            (stored.status == STATUS_RUNNING || stored.status == STATUS_FAILED) &&
            now - stored.updatedAt < FRESH_MS
        if (!active) {
            return Journal(
                taskId = UUID.randomUUID().toString(),
                task = task,
                createdAt = now,
                updatedAt = now,
                status = STATUS_RUNNING,
                subgoals = subgoals.distinct().map { Subgoal(it) }
            )
        }
        val merged = LinkedHashMap<String, Subgoal>()
        stored.subgoals.forEach { merged[it.label] = it }
        subgoals.distinct().forEach {
            if (!merged.containsKey(it)) merged[it] = Subgoal(it)
        }
        return stored.copy(
            task = task,
            status = STATUS_RUNNING,
            failReason = null,
            updatedAt = now,
            subgoals = merged.values.toList()
        )
    }

    /** Mark a milestone done (adds it as a done sub-goal when new). */
    fun withMilestone(journal: Journal, milestone: String, now: Long): Journal {
        val label = milestone.trim()
        if (label.isEmpty()) return journal
        val subs = if (journal.subgoals.any { it.label == label }) {
            journal.subgoals.map {
                if (it.label == label) it.copy(status = GOAL_DONE) else it
            }
        } else {
            journal.subgoals + Subgoal(label, GOAL_DONE)
        }
        return journal.copy(updatedAt = now, subgoals = subs)
    }

    /** COMPLETED (never redo) + STILL REMAINING + LAST POSITION (+ last failure). */
    fun stateBlock(journal: Journal?): String {
        if (journal == null) return ""
        val done = journal.completed.ifEmpty { listOf("(none)") }.joinToString(", ")
        val left = journal.remaining.ifEmpty { listOf("(none)") }.joinToString(", ")
        val fail = journal.failReason
            ?.takeIf { it.isNotBlank() }
            ?.let { "\nLAST FAILURE: $it (resume from here, do NOT restart)" }
            .orEmpty()
        return "TASK STATE - COMPLETED (never redo these): $done\n" +
            "STILL REMAINING: $left\n" +
            "LAST POSITION: package=${journal.lastPackage ?: "?"}, step=${journal.step}$fail"
    }

    /** TASK header + state - appended to the system prompt so trim-proof. */
    fun pinnedBlock(journal: Journal?): String {
        if (journal == null) return ""
        val state = stateBlock(journal)
        return if (state.isEmpty()) "TASK: ${journal.task}" else "TASK: ${journal.task}\n$state"
    }

    fun encode(journal: Journal): String = JSONObject()
        .put("taskId", journal.taskId)
        .put("task", journal.task)
        .put("createdAt", journal.createdAt)
        .put("updatedAt", journal.updatedAt)
        .put("status", journal.status)
        .put("step", journal.step)
        .put("lastPackage", journal.lastPackage ?: JSONObject.NULL)
        .put("lastAction", journal.lastAction ?: JSONObject.NULL)
        .put("lastScreenHash", journal.lastScreenHash ?: JSONObject.NULL)
        .put(
            "subgoals",
            JSONArray().also { arr ->
                journal.subgoals.forEach { g ->
                    arr.put(
                        JSONObject()
                            .put("label", g.label)
                            .put("status", g.status)
                            .put("proof", g.proof ?: JSONObject.NULL)
                            .put("attempts", g.attempts)
                    )
                }
            }
        )
        .put("pendingCallMessage", journal.pendingCallMessage ?: JSONObject.NULL)
        .put("failReason", journal.failReason ?: JSONObject.NULL)
        .toString()

    /** Decode persisted JSON - corrupt/blank input degrades gracefully to null. */
    fun decode(raw: String?): Journal? {
        if (raw.isNullOrBlank()) return null
        return try {
            val o = JSONObject(raw)
            val task = o.optString("task").trim()
            if (task.isEmpty()) return null
            val subs = mutableListOf<Subgoal>()
            val arr = o.optJSONArray("subgoals")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val label = s.optString("label").trim()
                    if (label.isEmpty()) continue
                    subs.add(
                        Subgoal(
                            label = label,
                            status = s.optString("status", GOAL_PENDING)
                                .ifBlank { GOAL_PENDING },
                            proof = if (s.isNull("proof")) null
                                else s.optString("proof").takeIf { it.isNotBlank() },
                            attempts = s.optInt("attempts", 0)
                        )
                    )
                }
            }
            Journal(
                taskId = o.optString("taskId").ifBlank { UUID.randomUUID().toString() },
                task = task,
                createdAt = o.optLong("createdAt", 0L),
                updatedAt = o.optLong("updatedAt", 0L),
                status = o.optString("status", STATUS_RUNNING).ifBlank { STATUS_RUNNING },
                step = o.optInt("step", 0),
                lastPackage = o.optStringOrNull("lastPackage"),
                lastAction = o.optStringOrNull("lastAction"),
                lastScreenHash = o.optStringOrNull("lastScreenHash"),
                subgoals = subs,
                pendingCallMessage = if (o.isNull("pendingCallMessage")) null
                    else o.optString("pendingCallMessage"),
                failReason = o.optStringOrNull("failReason")
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
}
