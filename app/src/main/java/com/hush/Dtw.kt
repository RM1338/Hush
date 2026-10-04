package com.hush

import kotlin.math.min
import kotlin.math.sqrt

/** Mirror of training/dtw.py. A clip = list of frames, each frame = 80 normalised dot coords. */
object Dtw {
    /** DTW cost with Euclidean frame distance, divided by (len(a) + len(b)). */
    fun distance(a: List<FloatArray>, b: List<FloatArray>): Float {
        val n = a.size
        val m = b.size
        var prev = FloatArray(m) { Float.POSITIVE_INFINITY }
        var cur = FloatArray(m)
        for (i in 0 until n) {
            for (j in 0 until m) {
                val best = when {
                    i == 0 && j == 0 -> 0f
                    i == 0 -> cur[j - 1]
                    j == 0 -> prev[j]
                    else -> min(min(prev[j], cur[j - 1]), prev[j - 1])
                }
                cur[j] = frameDist(a[i], b[j]) + best
            }
            prev = cur.also { cur = prev }
        }
        return prev[m - 1] / (n + m)
    }

    private fun frameDist(x: FloatArray, y: FloatArray): Float {
        var s = 0f
        for (k in x.indices) {
            val d = x[k] - y[k]
            s += d * d
        }
        return sqrt(s)
    }

    data class Guess(val phraseId: String, val distance: Float)

    /** Every taught phrase ranked by the mean distance to its 2 nearest takes (D-47, mirrors dtw.phrase_distance). */
    fun rank(clip: List<FloatArray>, templates: Map<String, List<List<FloatArray>>>): List<Guess> {
        val c = features(clip)
        return templates.map { (id, takes) -> Guess(id, takes.map { distance(c, features(it)) }.sorted().take(2).average().toFloat()) }
            .sortedBy { it.distance }
    }

    /** Mirror of dtw.features: subtract the clip's average mouth shape, keep only the movement (D-49). */
    fun features(clip: List<FloatArray>): List<FloatArray> {
        val mean = FloatArray(clip[0].size)
        clip.forEach { f -> for (k in f.indices) mean[k] += f[k] / clip.size }
        return clip.map { f -> FloatArray(f.size) { k -> f[k] - mean[k] } }
    }

    /** D-46: accept the best guess only if best ÷ second-best ≤ [ratio]. */
    fun accept(ranked: List<Guess>, ratio: Float): Boolean =
        ranked.isNotEmpty() && (ranked.size == 1 || ranked[0].distance <= ratio * ranked[1].distance)
}
