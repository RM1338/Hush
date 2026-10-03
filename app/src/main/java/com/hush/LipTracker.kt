package com.hush

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.Executors

/**
 * Front camera -> MediaPipe Face Landmarker -> one normalised lip frame (80 floats) per camera frame,
 * or null when no face is found. Started when the app opens so there's no cold start on button press (D-21).
 */
class LipTracker(private val context: Context, private val onFrame: (FloatArray?) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    private var landmarker: FaceLandmarker? = null
    private var frames = 0
    private var busyMs = 0L
    private var fpsSince = SystemClock.uptimeMillis()

    @Volatile var fps = 0f
        private set

    fun start(owner: LifecycleOwner, preview: Preview.SurfaceProvider) {
        executor.execute {
            landmarker = FaceLandmarker.createFromOptions(
                context,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    // CPU on purpose: it's what every cheap phone has, and what we measure (D-36).
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(Delegate.CPU).build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumFaces(1)
                    .build(),
            )
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val size = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(size)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(executor, ::analyse) }
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview }
            providerFuture.get().apply {
                unbindAll()
                bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyse(image: ImageProxy) {
        val t0 = SystemClock.uptimeMillis()
        image.use {
            val lm = landmarker ?: return
            // Analysis frames are not mirrored, same as recorded videos, so dots match training.
            val rotation = it.imageInfo.rotationDegrees
            val result = lm.detectForVideo(
                BitmapImageBuilder(it.toBitmap()).build(),
                ImageProcessingOptions.builder().setRotationDegrees(rotation).build(),
                it.imageInfo.timestamp / 1_000_000,
            )
            val upright = rotation % 180 != 0
            val w = if (upright) it.height else it.width
            val h = if (upright) it.width else it.height
            val face = result.faceLandmarks().firstOrNull()
            onFrame(
                face?.let { pts ->
                    val xy = FloatArray(2 * LipDots.N)
                    LipDots.LIPS.forEachIndexed { i, idx -> xy[2 * i] = pts[idx].x(); xy[2 * i + 1] = pts[idx].y() }
                    LipDots.normalise(xy, w, h)
                },
            )
        }
        frames++
        val now = SystemClock.uptimeMillis()
        busyMs += now - t0
        if (now - fpsSince >= 1000) {
            fps = frames * 1000f / (now - fpsSince)
            // fps well below 1000/ms-per-frame means the camera is the limit (e.g. low light), not us.
            Log.i("Hush", "lip tracking %.1f fps, %d ms per frame".format(fps, busyMs / frames))
            frames = 0
            busyMs = 0
            fpsSince = now
        }
    }

    fun close() {
        executor.execute { landmarker?.close() }
        executor.shutdown()
    }

    companion object {
        /** 640×480 per ARCHITECTURE.md. Drop to 320×240 to mimic a low-end phone (D-36). */
        val ANALYSIS_SIZE = Size(640, 480)
    }
}
