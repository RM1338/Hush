package com.hush

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NpyTest {
    /** Record mode must write .npy that numpy reads as (frames, 40, 2) float32 with NaN for no-face frames. */
    @Test
    fun headerIsValid() {
        val f = File("build/npy-test.npy")
        writeNpy(f, listOf(FloatArray(80) { it.toFloat() }, null, FloatArray(80) { -1f }))
        val b = f.readBytes()
        val headerLen = (b[8].toInt() and 0xff) or ((b[9].toInt() and 0xff) shl 8)
        assertEquals(0, (10 + headerLen) % 64) // numpy requires 64-byte alignment
        assertEquals(10 + headerLen + 3 * 80 * 4, b.size)
        assertEquals('\n', b[10 + headerLen - 1].toInt().toChar())
    }
}
