package io.legado.app.ui.book.read

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File

/** A local, copyright-free H.264 clip; tests exercise actual video decoding without network fixtures. */
internal fun syntheticVideoFixture(file: File, frameCount: Int = 60, patterned: Boolean = false): ByteArray {
    require(frameCount > 0)
    val width = 160
    val height = 96
    val frame = ByteArray(width * height * 3 / 2) { index -> if (index < width * height) 32 else 128.toByte() }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var started = false
    var track = -1
    var inputFrame = 0
    var finished = false
    val info = MediaCodec.BufferInfo()
    try {
        codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 5)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!finished) {
            check(SystemClock.elapsedRealtime() < deadline) { "Synthetic video encoder stalled" }
            if (inputFrame <= frameCount) {
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val end = inputFrame == frameCount
                    if (!end) {
                        if (patterned) {
                            for (y in 0 until height) for (x in 0 until width) {
                                frame[y * width + x] = if ((x / 16 + y / 16 + inputFrame / 5) % 2 == 0) 210.toByte() else 50
                            }
                        }
                        codec.getInputBuffer(input)!!.apply { clear(); put(frame) }
                    }
                    codec.queueInputBuffer(input, 0, if (end) 0 else frame.size, inputFrame * 200_000L,
                        if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    inputFrame++
                }
            }
            when (val output = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started)
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                else -> if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(started)
                        muxer.writeSampleData(track, codec.getOutputBuffer(output)!!, info)
                    }
                    finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                }
            }
        }
        muxer.stop()
        started = false
        return file.readBytes()
    } finally {
        codec.release()
        if (started) muxer.stop()
        muxer.release()
        file.delete()
    }
}
