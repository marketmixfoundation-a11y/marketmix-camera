package site.marketmix.camera

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Combines the GL-encoded video with the separately recorded audio. */
object VideoMuxer {

    fun combine(video: File, audio: File?, out: File): Boolean {
        if (audio == null || !audio.exists() || audio.length() < 2048) {
            return try {
                video.copyTo(out, overwrite = true)
                true
            } catch (e: Exception) {
                false
            }
        }
        var muxer: MediaMuxer? = null
        var vEx: MediaExtractor? = null
        var aEx: MediaExtractor? = null
        return try {
            vEx = MediaExtractor().apply { setDataSource(video.absolutePath) }
            aEx = MediaExtractor().apply { setDataSource(audio.absolutePath) }
            val vTrack = findTrack(vEx, "video/")
            val aTrack = findTrack(aEx, "audio/")
            if (vTrack < 0) return false
            vEx.selectTrack(vTrack)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vOut = muxer.addTrack(vEx.getTrackFormat(vTrack))
            var aOut = -1
            if (aTrack >= 0) {
                aEx.selectTrack(aTrack)
                aOut = muxer.addTrack(aEx.getTrackFormat(aTrack))
            }
            muxer.start()
            copy(vEx, muxer, vOut)
            if (aOut >= 0) copy(aEx, muxer, aOut)
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                muxer?.stop()
            } catch (_: Exception) {
            }
            muxer?.release()
            vEx?.release()
            aEx?.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun copy(extractor: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buffer = ByteBuffer.allocateDirect(1 shl 20)
        val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }
}
