package site.marketmix.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var glView: CameraGLView
    private lateinit var status: TextView
    private lateinit var recordButton: Button

    private lateinit var segmenter: Segmenter
    private var controller: CameraController? = null
    private var cameraStarted = false
    private var front = false

    private val segmentExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val segmentBusy = AtomicBoolean(false)
    private val maskBytes = ByteArray(MASK_SIZE * MASK_SIZE)
    private val handler = Handler(Looper.getMainLooper())

    private var recording = false
    private var recordStartMs = 0L
    private var encoder: VideoEncoder? = null
    private var audio: AudioRecorder? = null
    private var videoTemp: File? = null
    private var audioTemp: File? = null
    private var finalTemp: File? = null
    private var clipName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        glView = findViewById(R.id.camera_view)
        status = findViewById(R.id.status)
        recordButton = findViewById(R.id.record)
        val blurBar = findViewById<SeekBar>(R.id.blur)
        val invertBox = findViewById<CheckBox>(R.id.invert)
        val flipButton = findViewById<Button>(R.id.flip)

        val renderer = glView.renderer
        renderer.rotationDeg = 90
        renderer.blurAmount = blurBar.progress / 100f

        blurBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                renderer.blurAmount = value / 100f
                glView.requestRender()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        invertBox.setOnCheckedChangeListener { _, checked ->
            renderer.invert = checked
            glView.requestRender()
        }

        flipButton.setOnClickListener {
            if (recording) return@setOnClickListener
            front = !front
            renderer.mirror = front
            segmentBusy.set(false)
            segmenter.reset()
            controller?.switchCamera()
            glView.requestRender()
        }

        recordButton.setOnClickListener { if (recording) stopRecording() else startRecording() }

        segmenter = Segmenter(this)
        applyInsets()
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        startCameraIfAllowed()
    }

    override fun onPause() {
        if (recording) stopRecording()
        cameraStarted = false
        controller?.stop()
        controller = null
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        segmentExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun applyInsets() {
        val bar = findViewById<View>(R.id.top_bar)
        val panel = findViewById<View>(R.id.panel)
        val barTop = bar.paddingTop
        val panelBottom = panel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = barTop + bars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = panelBottom + bars.bottom)
            insets
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val missing = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) missing += Manifest.permission.CAMERA
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) missing += Manifest.permission.RECORD_AUDIO
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (hasPermission(Manifest.permission.CAMERA)) {
            startCameraIfAllowed()
        } else {
            status.text = getString(R.string.permission_needed)
        }
    }

    private fun startCameraIfAllowed() {
        if (cameraStarted || !hasPermission(Manifest.permission.CAMERA)) return
        cameraStarted = true
        val c = CameraController(
            context = this,
            lifecycleOwner = this,
            onFrame = { frame, rotation, isFront -> onCameraFrame(frame, rotation, isFront) },
            onError = { message -> runOnUiThread { status.text = getString(R.string.camera_error, message) } }
        )
        controller = c
        c.start(front)
    }

    private fun onCameraFrame(frame: Bitmap, rotationDegrees: Int, isFront: Boolean) {
        val renderer = glView.renderer
        renderer.rotationDeg = rotationDegrees
        renderer.mirror = isFront

        val wantsMask = segmentBusy.compareAndSet(false, true)
        val small = if (wantsMask) Bitmap.createScaledBitmap(frame, MASK_SIZE, MASK_SIZE, true) else null

        renderer.submitFrame(frame)
        glView.requestRender()

        if (small != null) {
            segmentExecutor.execute {
                try {
                    segmenter.segment(small, maskBytes)
                    renderer.submitMask(MaskTools.toBitmap(maskBytes, MASK_SIZE))
                    glView.requestRender()
                } catch (_: Exception) {
                } finally {
                    small.recycle()
                    segmentBusy.set(false)
                }
            }
        }
    }

    private fun startRecording() {
        if (recording) return
        if (!hasPermission(Manifest.permission.CAMERA)) {
            ensurePermissions()
            return
        }
        clipName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val video = File(cacheDir, "raw_$clipName.mp4").apply { delete() }
        val audioFile = File(cacheDir, "raw_$clipName.m4a").apply { delete() }
        val combined = File(cacheDir, "final_$clipName.mp4").apply { delete() }

        val audioRecorder = AudioRecorder(this, audioFile)
        val audioOk = if (hasPermission(Manifest.permission.RECORD_AUDIO)) audioRecorder.start() else false

        val enc = VideoEncoder(BlurRenderer.OUT_W, BlurRenderer.OUT_H, video)
        try {
            enc.start()
        } catch (e: Exception) {
            if (audioOk) audioRecorder.stop()
            status.text = getString(R.string.record_failed)
            return
        }

        val latch = CountDownLatch(1)
        glView.queueEvent {
            rendererStarted = glView.renderer.startEncoder(enc.inputSurface)
            latch.countDown()
        }
        val ready = try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!ready || !rendererStarted) {
            if (audioOk) audioRecorder.stop()
            glView.queueEvent { glView.renderer.stopEncoder() }
            enc.stop()
            video.delete()
            audioFile.delete()
            status.text = getString(R.string.record_failed)
            return
        }

        encoder = enc
        audio = if (audioOk) audioRecorder else null
        if (!audioOk) audioFile.delete()
        videoTemp = video
        audioTemp = if (audioOk) audioFile else null
        finalTemp = combined
        recording = true
        recordStartMs = System.currentTimeMillis()
        recordButton.setBackgroundResource(R.drawable.bg_record_stop)
        recordButton.contentDescription = getString(R.string.stop)
        status.text = getString(R.string.recording)
        handler.postDelayed(ticker, 500)
    }

    private var rendererStarted = false

    private fun stopRecording() {
        if (!recording) return
        recording = false
        handler.removeCallbacks(ticker)
        recordButton.setBackgroundResource(R.drawable.bg_record)
        recordButton.contentDescription = getString(R.string.record)

        audio?.stop()
        audio = null

        val enc = encoder ?: return
        encoder = null
        val latch = CountDownLatch(1)
        glView.queueEvent {
            glView.renderer.stopEncoder()
            latch.countDown()
        }
        try {
            latch.await(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        val videoOk = enc.stop()

        val video = videoTemp
        val audioFile = audioTemp
        val combined = finalTemp
        videoTemp = null
        audioTemp = null
        finalTemp = null
        status.text = getString(R.string.saving)

        ioExecutor.execute {
            var uri: Uri? = null
            if (videoOk && video != null && combined != null) {
                if (VideoMuxer.combine(video, audioFile, combined)) {
                    uri = Gallery.save(this, combined, "MarketMix_$clipName.mp4")
                }
            }
            video?.delete()
            audioFile?.delete()
            combined?.delete()
            val message = if (uri != null) getString(R.string.saved) else getString(R.string.save_failed)
            runOnUiThread { if (!recording) status.text = message }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            val seconds = (System.currentTimeMillis() - recordStartMs) / 1000
            status.text = String.format(
                Locale.US, "%s %d:%02d", getString(R.string.recording), seconds / 60, seconds % 60
            )
            if (seconds >= MAX_SECONDS) {
                stopRecording()
            } else {
                handler.postDelayed(this, 500)
            }
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val MASK_SIZE = 256
        private const val MAX_SECONDS = 300L
    }
}
