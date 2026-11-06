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
    private lateinit var blurLabel: TextView
    private var blurStep = Math.round(LensPreset.DEFAULT.blur * BLUR_MAX)
    private lateinit var lensButtons: Map<LensPreset, Button>
    private lateinit var smoothLabel: TextView
    private var edgePercent = DEFAULT_SMOOTH

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

    /** Set while a take is being opened or closed off the main thread. */
    @Volatile
    private var recordBusy = false
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
        blurLabel = findViewById(R.id.blur_value)
        smoothLabel = findViewById(R.id.smooth_value)
        val invertBox = findViewById<CheckBox>(R.id.invert)
        val flipButton = findViewById<Button>(R.id.flip)

        val renderer = glView.renderer
        renderer.rotationDeg = 90

        findViewById<Button>(R.id.blur_minus).setOnClickListener { setBlur(blurStep - BLUR_STEP) }
        findViewById<Button>(R.id.blur_plus).setOnClickListener { setBlur(blurStep + BLUR_STEP) }

        lensButtons = mapOf(
            LensPreset.WIDE_35 to findViewById(R.id.lens_35),
            LensPreset.STANDARD_50 to findViewById(R.id.lens_50),
            LensPreset.PORTRAIT_85 to findViewById(R.id.lens_85),
            LensPreset.TELE_135 to findViewById(R.id.lens_135)
        )
        lensButtons.forEach { (preset, button) ->
            button.setOnClickListener { applyLens(preset) }
        }
        applyLens(LensPreset.DEFAULT)

        invertBox.setOnCheckedChangeListener { _, checked ->
            renderer.invert = checked
            glView.requestRender()
        }

        findViewById<SeekBar>(R.id.smooth).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    setSmooth(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            }
        )
        setSmooth(edgePercent)

        flipButton.setOnClickListener {
            if (recording) return@setOnClickListener
            front = !front
            renderer.mirror = front
            segmentBusy.set(false)
            segmenter.reset()
            controller?.switchCamera()
            glView.requestRender()
        }

        recordButton.setOnClickListener {
            if (recordBusy) return@setOnClickListener
            if (recording) stopRecording() else startRecording()
        }

        segmenter = Segmenter()
        applyInsets()
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        startCameraIfAllowed()
    }

    override fun onPause() {
        if (recording) {
            recording = false
            handler.removeCallbacks(ticker)
            status.text = getString(R.string.saving)
            // The render thread halts below and the encoder is fed from it, so
            // a take still open has to be closed before leaving the foreground.
            detachTake()?.let { status.text = getString(finishTake(it)) }
        }
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

    /** Blur step on the 0-10 scale the panel shows. */
    private fun setBlur(step: Int) {
        blurStep = step.coerceIn(0, BLUR_MAX)
        blurLabel.text = blurStep.toString()
        glView.renderer.blurAmount = blurStep / BLUR_MAX.toFloat()
        glView.requestRender()
    }

    /**
     * Picks the focus character. The blur step follows the preset so the
     * preview matches the label straight away; the +/- buttons still move it
     * from there without leaving the lens.
     */
    private fun applyLens(preset: LensPreset) {
        glView.renderer.preset = preset
        setBlur(Math.round(preset.blur * BLUR_MAX))
        lensButtons.forEach { (lens, button) ->
            val selected = lens == preset
            button.setBackgroundResource(
                if (selected) R.drawable.bg_pill_on else R.drawable.bg_pill
            )
            button.alpha = if (selected) 1f else 0.75f
        }
    }

    /** How far the cut line is feathered, in percent of its full range. */
    private fun setSmooth(percent: Int) {
        edgePercent = percent.coerceIn(0, 100)
        smoothLabel.text = getString(R.string.percent, edgePercent)
        glView.renderer.edgeSoftness = edgePercent / 100f
        glView.requestRender()
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
        // The front sensor reports a rotation that puts the picture on its
        // head on this phone, so it gets half a turn beyond what it asks for.
        renderer.rotationDeg = if (isFront) (rotationDegrees + 180) % 360 else rotationDegrees
        renderer.mirror = isFront

        val wantsMask = segmentBusy.compareAndSet(false, true)
        val small = if (wantsMask) Bitmap.createScaledBitmap(frame, MASK_SIZE, MASK_SIZE, true) else null

        renderer.submitFrame(frame)
        glView.requestRender()

        if (small != null) {
            segmentExecutor.execute {
                try {
                    segmenter.segment(small, maskBytes, rotationDegrees)
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
        if (!hasPermission(Manifest.permission.CAMERA)) {
            ensurePermissions()
            return
        }
        recordBusy = true
        clipName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        ioExecutor.execute {
            val started = beginRecording(clipName)
            runOnUiThread {
                recordBusy = false
                if (started) {
                    recording = true
                    recordStartMs = System.currentTimeMillis()
                    recordButton.setBackgroundResource(R.drawable.bg_record_stop)
                    recordButton.contentDescription = getString(R.string.stop)
                    status.text = getString(R.string.recording)
                    handler.postDelayed(ticker, 500)
                } else {
                    status.text = getString(R.string.record_failed)
                }
            }
        }
    }

    /** Everything about starting a take that would stall the interface. */
    private fun beginRecording(clip: String): Boolean {
        val video = File(cacheDir, "raw_$clip.mp4").apply { delete() }
        val audioFile = File(cacheDir, "raw_$clip.m4a").apply { delete() }
        val combined = File(cacheDir, "final_$clip.mp4").apply { delete() }

        val audioRecorder = AudioRecorder(this, audioFile)
        val audioOk = if (hasPermission(Manifest.permission.RECORD_AUDIO)) audioRecorder.start() else false

        val enc = VideoEncoder(BlurRenderer.OUT_W, BlurRenderer.OUT_H, video)
        try {
            enc.start()
        } catch (e: Exception) {
            if (audioOk) audioRecorder.stop()
            return false
        }

        val latch = CountDownLatch(1)
        glView.queueEvent {
            rendererStarted = glView.renderer.startEncoder(enc.inputSurface)
            latch.countDown()
        }
        val ready = try {
            latch.await(ENCODER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!ready || !rendererStarted) {
            if (audioOk) audioRecorder.stop()
            glView.queueEvent { glView.renderer.stopEncoder() }
            enc.stop()
            video.delete()
            audioFile.delete()
            return false
        }

        encoder = enc
        audio = if (audioOk) audioRecorder else null
        if (!audioOk) audioFile.delete()
        videoTemp = video
        audioTemp = if (audioOk) audioFile else null
        finalTemp = combined
        return true
    }

    private var rendererStarted = false

    /** A finished recording, detached from the activity's fields. */
    private class Take(
        val encoder: VideoEncoder,
        val audio: AudioRecorder?,
        val video: File?,
        val audioFile: File?,
        val combined: File?,
        val clip: String
    )

    private fun detachTake(): Take? {
        val enc = encoder ?: return null
        encoder = null
        val take = Take(enc, audio, videoTemp, audioTemp, finalTemp, clipName)
        audio = null
        videoTemp = null
        audioTemp = null
        finalTemp = null
        return take
    }

    /** Closes the encoder and writes the clip. Blocks for seconds, so keep it off the main thread. */
    private fun finishTake(take: Take): Int {
        take.audio?.stop()
        val latch = CountDownLatch(1)
        glView.queueEvent {
            glView.renderer.stopEncoder()
            latch.countDown()
        }
        try {
            latch.await(ENCODER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        val videoOk = take.encoder.stop()

        var uri: Uri? = null
        if (videoOk && take.video != null && take.combined != null) {
            if (VideoMuxer.combine(take.video, take.audioFile, take.combined)) {
                uri = Gallery.save(this, take.combined, "MarketMix_${take.clip}.mp4")
            }
        }
        take.video?.delete()
        take.audioFile?.delete()
        take.combined?.delete()
        return if (uri != null) R.string.saved else R.string.save_failed
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        recordBusy = true
        handler.removeCallbacks(ticker)
        recordButton.setBackgroundResource(R.drawable.bg_record)
        recordButton.contentDescription = getString(R.string.record)
        status.text = getString(R.string.saving)

        val take = detachTake() ?: run {
            recordBusy = false
            return
        }
        ioExecutor.execute {
            val message = finishTake(take)
            runOnUiThread {
                recordBusy = false
                if (!recording) status.text = getString(message)
            }
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
        private const val ENCODER_TIMEOUT_SECONDS = 2L
        private const val BLUR_MAX = 10
        private const val BLUR_STEP = 1
        private const val DEFAULT_SMOOTH = 35
    }
}
