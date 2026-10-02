package com.sqlai.assistant.core

import android.content.Context
import android.content.SharedPreferences
import com.sqlai.assistant.SqlAiApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * BUG #2 / BUG #5 - persistent task state.
 *
 * Every agent task keeps its executed milestones (sub-steps that already
 * verified OK) plus the remaining ones. The snapshot survives:
 *  - Gemini-call interruptions (the wa_call workflow blocks the engine),
 *  - engine restarts / service restarts,
 *  - app process death (SharedPreferences).
 *
 * On recovery the engine rehydrates the snapshot, feeds
 * "COMPLETED: [...]; REMAINING: [...]" into every think() so the model
 * NEVER restarts from step 1.
 */
object TaskStateManager {

    private const val TAG = "TaskStateManager"
    private const val PREFS = "task_state"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val FRESH_MS = 24 * 60 * 60 * 1000L // survive restarts up to 24h

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
    private var current: Snapshot? = null

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Start (or resume) a task. Restores the snapshot if it is fresh and for the same task. */
    @Synchronized
    fun begin(context: Context, task: String, subgoals: List<String>): Snapshot {
        val stored = load(context)
        val fresh = stored != null &&
            System.currentTimeMillis() - updatedAt(context) < FRESH_MS
        val snapshot = if (fresh && stored != null && similar(stored.task, task)) {
            // Resume: keep completed milestones, merge any new subgoals.
            stored.copy(
                task = task,
                subgoals = (stored.subgoals + subgoals).distinct()
            )
        } else {
            Snapshot(task, subgoals.distinct(), emptyList(), null)
        }
        save(context, snapshot)
        LogBus.log(
            "[TASK] begin: ${snapshot.completed.size} done / " +
                "${snapshot.remaining.size} remaining", LogLevel.INFO
        )
        return snapshot
    }

    @Synchronized
    fun markCompleted(context: Context, milestone: String) {
        val s = current ?: load(context) ?: return
        if (milestone.isBlank() || milestone in s.completed) return
        val next = s.copy(completed = (s.completed + milestone).distinct())
        save(context, next)
        LogBus.log("[TASK] milestone done: $milestone (${next.completed.size}/${next.subgoals.size})", LogLevel.SUCCESS)
    }

    @Synchronized
    fun setPendingCallMessage(context: Context, message: String?) {
        val s = current ?: load(context) ?: return
        save(context, s.copy(pendingCallMessage = message))
    }

    /** Human-readable state block injected into every model think(). */
    fun stateBlock(): String {
        val s = current ?: return ""
        if (s.subgoals.isEmpty()) return ""
        val done = if (s.completed.isEmpty()) "(none)" else s.completed.joinToString(", ")
        val left = if (s.remaining.isEmpty()) "(none)" else s.remaining.joinToString(", ")
        return "TASK STATE - COMPLETED (never redo these): $done\nSTILL REMAINING: $left"
    }

    fun snapshot(): Snapshot? = current ?: load(SqlAiApp.instance)

    /** Clear on task success / unrecoverable failure. */
    @Synchronized
    fun clear(context: Context) {
        current = null
        prefs(context).edit().remove(KEY_SNAPSHOT).remove("updated_at").apply()
    }

    // --------------------------------------------------------------- storage

    private fun updatedAt(context: Context): Long =
        prefs(context).getLong("updated_at", 0L)

    private fun load(context: Context): Snapshot? {
        current?.let { return it }
        val raw = prefs(context).getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val sub = o.getJSONArray("subgoals").toStringList()
            val done = o.getJSONArray("completed").toStringList()
            val s = Snapshot(
                task = o.getString("task"),
                subgoals = sub,
                completed = done,
                pendingCallMessage = if (o.isNull("pending")) null else o.getString("pending")
            )
            current = s
            s
        } catch (e: Exception) {
            LogBus.log("[TASK] snapshot corrupt: ${e.message}", LogLevel.WARN)
            null
        }
    }

    private fun save(context: Context, s: Snapshot) {
        current = s
        val o = JSONObject()
            .put("task", s.task)
            .put("subgoals", JSONArray(s.subgoals))
            .put("completed", JSONArray(s.completed))
            .put("pending", s.pendingCallMessage ?: JSONObject.NULL)
        prefs(context).edit()
            .putString(KEY_SNAPSHOT, o.toString())
            .putLong("updated_at", System.currentTimeMillis())
            .apply()
    }

    private fun similar(a: String, b: String): Boolean {
        val x = a.trim().lowercase()
        val y = b.trim().lowercase()
        if (x == y) return true
        // Same task said differently in Hinglish/English - cheap containment test.
        return (x.length > 12 && y.contains(x)) || (y.length > 12 && x.contains(y))
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
}
