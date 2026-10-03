package com.hush

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Python and Kotlin must turn the same MediaPipe points into the same dots (D-36). */
class LipDotsTest {
    private val golden = JSONObject(File("../training/golden_lips.json").readText())
    private val dots = frames(golden.getJSONArray("dots"))

    private fun frames(a: JSONArray) = (0 until a.length()).map { f ->
        val pts = a.getJSONArray(f)
        FloatArray(2 * pts.length()) { k -> pts.getJSONArray(k / 2).getDouble(k % 2).toFloat() }
    }

    @Test
    fun matchesGoldenFile() {
        val raw = frames(golden.getJSONArray("raw"))
        val w = golden.getInt("width")
        val h = golden.getInt("height")
        assertEquals(LipDots.LIPS.toList(), (0 until 40).map { golden.getJSONArray("lips_index").getInt(it) })
        raw.zip(dots).forEach { (r, expected) ->
            val got = LipDots.normalise(r, w, h)
            for (k in got.indices) assertEquals(expected[k], got[k], 1e-4f)
        }
    }

    @Test
    fun dtwMatchesPython() {
        // Reference values: training/dtw.py dtw_batch on the golden dots (float64).
        assertEquals(0.0793373f, Dtw.distance(dots, dots.reversed()), 1e-4f)
        assertEquals(0.0628530f, Dtw.distance(dots, dots.filterIndexed { i, _ -> i % 2 == 0 }), 1e-4f)
        assertEquals(0f, Dtw.distance(dots, dots), 1e-6f)
    }

    @Test
    fun ratioRule() {
        val g = listOf(Dtw.Guess("a", 1f), Dtw.Guess("b", 2f))
        assertEquals(true, Dtw.accept(g, 0.8f))
        assertEquals(false, Dtw.accept(listOf(Dtw.Guess("a", 1.7f), Dtw.Guess("b", 2f)), 0.8f))
    }
}
