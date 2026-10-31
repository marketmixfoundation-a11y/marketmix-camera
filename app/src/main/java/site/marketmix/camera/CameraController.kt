package site.marketmix.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX analysis stream at 1280x720 in RGBA, which keeps the native
 * 16:9 aspect ratio and gives us a bitmap for the GL compositor.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (Bitmap, Int, Boolean) -> Unit,
    private val onError: (String) -> Unit
) {

    private var provider: ProcessCameraProvider? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analysis: ImageAnalysis? = null
    private var front = false

    fun start(useFront: Boolean) {
        front = useFront
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                bind()
            } catch (e: Exception) {
                onError(e.message ?: "Camera unavailable")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera() {
        front = !front
        bind()
    }

    private fun bind() {
        val p = provider ?: return
        try {
            analysis?.let { p.unbind(it) }
            val ia = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(Size(1280, 720))
                .build()
            ia.setAnalyzer(executor) { proxy -> handle(proxy) }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, selector, ia)
            analysis = ia
        } catch (e: Exception) {
            onError(e.message ?: "Could not start the camera")
        }
    }

    private fun handle(proxy: ImageProxy) {
        try {
            val bitmap = proxy.toBitmap()
            onFrame(bitmap, proxy.imageInfo.rotationDegrees, front)
        } catch (e: Exception) {
            onError(e.message ?: "Camera frame error")
        } finally {
            proxy.close()
        }
    }

    fun stop() {
        try {
            analysis?.clearAnalyzer()
            provider?.unbindAll()
        } catch (_: Exception) {
        }
        analysis = null
    }
}
