package com.hush

import kotlin.math.sqrt

/** Mirror of training/extract.py `normalise()`. Must match training/golden_lips.json (LipDotsTest). */
object LipDots {
    /** MediaPipe FACE_LANDMARKS_LIPS: outer ring then inner ring, from the left mouth corner. */
    val LIPS = intArrayOf(
        61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185,
        78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191,
    )
    const val N = 40
    private val LEFT = LIPS.indexOf(61)
    private val RIGHT = LIPS.indexOf(291)

    /**
     * One frame: [xy] = 40 lip points as x0,y0,x1,y1… in MediaPipe 0..1 coords, image [width]×[height].
     * Returns 80 floats: mouth-centred, scaled by corner distance, rotated so the corners are level.
     */
    fun normalise(xy: FloatArray, width: Int, height: Int): FloatArray {
        fun px(i: Int) = xy[2 * i] * width.toDouble()
        fun py(i: Int) = xy[2 * i + 1] * height.toDouble()
        val cx = (px(LEFT) + px(RIGHT)) / 2
        val cy = (py(LEFT) + py(RIGHT)) / 2
        val vx = px(RIGHT) - px(LEFT)
        val vy = py(RIGHT) - py(LEFT)
        val w = sqrt(vx * vx + vy * vy)
        val cos = vx / w
        val sin = vy / w
        val out = FloatArray(2 * N)
        for (i in 0 until N) {
            val dx = px(i) - cx
            val dy = py(i) - cy
            out[2 * i] = ((dx * cos + dy * sin) / w).toFloat()
            out[2 * i + 1] = ((-dx * sin + dy * cos) / w).toFloat()
        }
        return out
    }
}
