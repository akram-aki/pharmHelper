package fr.fbing.boxdetector

import kotlin.math.max
import kotlin.math.min

/** Unit-cost Levenshtein edit distance (insert/delete/substitute). */
internal fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    val prev = IntArray(b.length + 1) { it }
    val curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
        }
        prev.indices.forEach { prev[it] = curr[it] }
    }
    return prev[b.length]
}

/** Normalized similarity in 0f..1f (`1 - editDistance / maxLen`). */
internal fun similarity(a: String, b: String): Float {
    if (a == b) return 1f
    val maxLen = max(a.length, b.length)
    if (maxLen == 0) return 0f
    return 1f - levenshtein(a, b).toFloat() / maxLen
}
