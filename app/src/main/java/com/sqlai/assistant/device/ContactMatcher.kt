package com.sqlai.assistant.device

/**
 * FEATURE 4 - intelligent contact matching.
 *
 * Given the target contact ("Mohan Sharma" / "mohan") and every clickable
 * label currently on screen, score each candidate and pick the best one.
 * The chosen label + score + reason is logged so on-device runs show WHY
 * a row was selected (kills the "random tap" accuracy complaint).
 *
 * Scoring:
 *   100  exact (case/space-insensitive)
 *    95  candidate starts with the target's first name (or vice versa)
 *    90  every word of the target appears in the candidate (all-words)
 *    85  first-name exact + surname similarity >= 0.6
 *    70-84  Levenshtein similarity on the full string
 *    0   no relation
 */
object ContactMatcher {

    data class Result(val label: String, val score: Int, val reason: String)

    fun score(target: String, candidate: String): Result {
        val t = norm(target)
        val c = norm(candidate)
        if (t.isEmpty() || c.isEmpty()) return Result(candidate, 0, "empty")
        if (t == c) return Result(candidate, 100, "exact match")

        val tw = t.split(' ').filter { it.isNotBlank() }
        val cw = c.split(' ').filter { it.isNotBlank() }

        // First-name handling: "mohan" vs "Mohan Sharma".
        val tFirst = tw.firstOrNull().orEmpty()
        val cFirst = cw.firstOrNull().orEmpty()
        if (tFirst.isNotEmpty() && tFirst == cFirst && tw.size == 1) {
            return Result(candidate, 95, "first-name exact")
        }
        if (tFirst.isNotEmpty() && cFirst.isNotEmpty() && tFirst == cFirst) {
            val sim = similarity(t, c)
            if (sim >= 0.6) return Result(candidate, 85, "first name + similar surname (sim=${"%.2f".format(sim)})")
        }

        // All target words present anywhere in the candidate.
        if (tw.isNotEmpty() && tw.all { w -> cw.any { it.contains(w) || w.contains(it) } }) {
            return Result(candidate, 90, "all name words present")
        }
        if (cw.isNotEmpty() && cw.all { w -> tw.any { it.contains(w) || w.contains(it) } }) {
            return Result(candidate, 88, "all candidate words present in target")
        }

        val sim = similarity(t, c)
        val pct = (sim * 100).toInt()
        return when {
            sim >= 0.75 -> Result(candidate, minOf(84, pct), "similar name (sim=${"%.2f".format(sim)})")
            sim >= 0.45 -> Result(candidate, minOf(70, pct), "loose match (sim=${"%.2f".format(sim)})")
            else -> Result(candidate, 0, "no relation")
        }
    }

    /** Pick the best candidate for [target]. Null when nothing scores >= [minScore]. */
    fun pick(
        target: String,
        candidates: List<String>,
        minScore: Int = 65
    ): Result? {
        return candidates
            .map { score(target, it) }
            .filter { it.score >= minScore }
            .maxByOrNull { it.score }
    }

    private fun norm(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Levenshtein similarity in [0,1] (1 = identical). */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val m = a.length
        val n = b.length
        var prev = IntArray(n + 1) { it }
        val cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        val dist = prev[n]
        return 1.0 - dist.toDouble() / maxOf(m, n)
    }
}
