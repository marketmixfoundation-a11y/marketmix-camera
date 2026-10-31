package site.marketmix.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/**
 * H.264 encoder fed directly from the GL compositor, so the exported
 * video contains the blurred background for every frame.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val file: File
) {

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxerStarted = false
    private var drain: Thread? = null
    private var running = false

    lateinit var inputSurface: Surface
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        running = true
        drain = Thread({ drainLoop() }, "video-drain").also { it.start() }
    }

    /** Signals end of stream and returns true when a playable file was written. */
    fun stop(): Boolean {
        val wasRunning = running
        if (wasRunning) {
            try {
                codec.signalEndOfInputStream()
            } catch (_: Exception) {
            }
        }
        running = false
        drain?.join(3000)
        drain = null
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
        val started = muxerStarted
        muxer?.let {
            try {
                if (muxerStarted) it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        muxer = null
        return started && file.exists() && file.length() > 1024
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var eos = false
        while (!eos) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                break
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = muxer?.addTrack(codec.outputFormat) ?: -1
                        if (track >= 0) {
                            muxer?.start()
                            muxerStarted = true
                        }
                    }
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && muxerStarted &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer?.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
        }
    }
}
