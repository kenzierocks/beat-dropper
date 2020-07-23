/*
 * This file is part of beat-dropper, licensed under the MIT License (MIT).
 *
 * Copyright (c) Octavia Togami <https://octyl.net>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package net.octyl.beatdropper.util

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_CAP_VARIABLE_FRAME_SIZE
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_QSCALE
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_get_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avformat.AVFMT_GLOBALHEADER
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_STEREO
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.FF_QP2LAMBDA
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.ffmpeg.global.avutil.av_get_bytes_per_sample
import org.bytedeco.ffmpeg.global.avutil.av_get_planar_sample_fmt
import org.bytedeco.ffmpeg.global.avutil.av_make_q
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_int
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.lwjgl.system.MemoryUtil
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An input stream based on piping another stream through FFmpeg.
 */
class FFmpegOutputStream(
    codecId: Int,
    containerName: String,
    sampleRate: Int,
    avioCallbacks: AvioCallbacks
) :
    OutputStream() {
    private val closer = AutoCloser()

    // allocate just a pointer, no actual object
    private val ctx = AVFormatContext(null)
    private val codecCtx: AVCodecContext
    private val closed = AtomicBoolean()
    private val audioStream: AVStream
    private val outputFrame: AVFrame
    private val resampledFrame: AVFrame
    private val swrCtx: SwrContext
    private val outputBuffer: ByteBuffer
    private var pts: Long = 0

    init {
        var error = avformat_alloc_output_context2(ctx, null, containerName, null)
        check(error == 0) { "Error allocating output context: " + avErr2Str(error) }
        closer.register(ctx, { s-> avformat_free_context(s) })
        val avioCtx = closer.register(avioCallbacks).allocateContext(4096, true)
            ?: error("Unable to allocate IO context")
        closer.register(avioCtx)
        ctx.pb(avioCtx)
        try {
            val codec = avcodec_find_encoder(codecId)
                ?: error("Could not find encoder for " + avcodec_get_name(codecId))

            val supportedFmts = codec.sample_fmts()
            val desiredFormat = generateSequence(0L) { it + 1 }
                .map { supportedFmts[it] }
                .takeWhile { fmt -> fmt != -1 }
                .maxBy { fmt ->
                    val bytes = av_get_bytes_per_sample(fmt)
                    val planar = when (av_get_planar_sample_fmt(fmt)) {
                        0 -> 0
                        else -> 1
                    }
                    val baseScore = when(bytes) {
                        // prefer 2 bytes / 16 bits (our current format)
                        2 -> Int.MAX_VALUE
                        else -> bytes * 2
                    }

                    // prefer non-planar slightly
                    baseScore - planar
                } ?: error("No formats available")

            audioStream = avformat_new_stream(ctx, codec)
                ?: error("Unable to allocate audio stream")
            audioStream.id(0)
            audioStream.time_base(av_make_q(1, sampleRate))

            codecCtx = closer.register(
                avcodec_alloc_context3(codec),
                { avctx -> avcodec_free_context(avctx) }
            ) ?: error("Unable to allocate codec context")
            codecCtx
                .sample_fmt(desiredFormat)
                .sample_rate(sampleRate)
                .channels(2)
                .channel_layout(AV_CH_LAYOUT_STEREO)
                .time_base(av_make_q(1, sampleRate))

            // set -q:a 2
            codecCtx.flags(codecCtx.flags() or AV_CODEC_FLAG_QSCALE)
            codecCtx.global_quality(FF_QP2LAMBDA * 2)

            if (ctx.oformat().flags() and AVFMT_GLOBALHEADER != 0) {
                codecCtx.flags(AV_CODEC_FLAG_GLOBAL_HEADER)
            }

            error = avcodec_open2(codecCtx, codec, null as AVDictionary?)
            check(error == 0) { "Unable to open codec: " + avErr2Str(error) }

            avcodec_parameters_from_context(audioStream.codecpar(), codecCtx)

            swrCtx = closer.register(
                swresample.swr_alloc(),
                { s -> swresample.swr_free(s) }
            ) ?: error("Unable to allocate swr context")

            av_opt_set_int(swrCtx, "in_channel_layout", AV_CH_LAYOUT_STEREO, 0)
            av_opt_set_int(swrCtx, "in_sample_rate", audioStream.codecpar().sample_rate().toLong(), 0)
            av_opt_set_int(swrCtx, "in_sample_fmt", AV_SAMPLE_FMT_S16.toLong(), 0)

            av_opt_set_int(swrCtx, "out_channel_layout", audioStream.codecpar().channel_layout(), 0)
            av_opt_set_int(swrCtx, "out_sample_rate", audioStream.codecpar().sample_rate().toLong(), 0)
            av_opt_set_int(swrCtx, "out_sample_fmt", audioStream.codecpar().format().toLong(), 0)

            error = swresample.swr_init(swrCtx)
            check(error == 0) { "Error initializing swr: " + avErr2Str(error) }

            val nbSamples = when {
                (codec.capabilities() and AV_CODEC_CAP_VARIABLE_FRAME_SIZE) != 0 -> 8192
                else -> codecCtx.frame_size()
            }
            outputFrame = closer.register(
                av_frame_alloc(),
                { frame -> av_frame_free(frame) }
            ) ?: error("Unable to allocate output frame")

            outputFrame
                .channel_layout(AV_CH_LAYOUT_STEREO)
                .sample_rate(audioStream.codecpar().sample_rate())
                .format(AV_SAMPLE_FMT_S16)
                .nb_samples(nbSamples)

            error = av_frame_get_buffer(outputFrame, 0)
            check(error == 0) { "Unable to get output buffer: " + avErr2Str(error) }

            resampledFrame = closer.register(
                av_frame_alloc(),
                { frame -> av_frame_free(frame) }
            ) ?: error("Unable to allocate resample frame")

            resampledFrame
                .format(codecCtx.sample_fmt())
                .sample_rate(sampleRate)
                .channel_layout(codecCtx.channel_layout())
                .nb_samples(nbSamples)

            error = av_frame_get_buffer(resampledFrame, 0)
            check(error == 0) { "Unable to get resample buffer: " + avErr2Str(error) }

            outputBuffer = MemoryUtil.memAlloc(Short.SIZE_BYTES * outputFrame.nb_samples() * codecCtx.channels())
            closer.register(outputBuffer, { ptr -> MemoryUtil.memFree(ptr) })

            error = avformat_write_header(ctx, null as AVDictionary?)
            if (error != 0) {
                throw IOException("Unable to write header: " + avErr2Str(error))
            }
        } catch (t: Throwable) {
            closeSilently(t)
            throw t
        }
    }

    @Throws(IOException::class)
    override fun write(b: Int) {
        outputBuffer.put(b.toByte())
        if (!outputBuffer.hasRemaining()) {
            flush()
        }
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        var currentOffset = off
        var currentLen = len
        while (true) {
            val size = currentLen.coerceAtMost(outputBuffer.remaining())
            outputBuffer.put(b, currentOffset, size)
            if (!outputBuffer.hasRemaining()) {
                flush()
            }
            currentOffset += size
            currentLen -= size
            if (currentLen == 0) {
                // we have consumed all the data
                return
            }
            // otherwise, loop to fill our buffer again
        }
    }

    @Throws(IOException::class)
    override fun flush() {
        if (outputBuffer.hasRemaining()) {
            // buffer must be full, otherwise the frame is too small
            return
        }
        outputBuffer.flip()
        val error = av_frame_make_writable(outputFrame)
        if (error != 0) {
            throw IOException("Unable to make output frame writeable: " + avErr2Str(error))
        }
        val length = outputBuffer.remaining()
        MemoryUtil.memCopy(
            MemoryUtil.memAddress(outputBuffer),
            outputFrame.data(0).address(),
            length.toLong()
        )
        outputBuffer.clear()
        outputFrame.pts(pts)
        pts += length / (java.lang.Short.BYTES * codecCtx.channels()).toLong()
        for (next in swrResampleSequence(swrCtx, outputFrame, resampledFrame)) {
            flushFrame(next)
        }
    }

    @Throws(IOException::class)
    private fun flushFrame(frame: AVFrame?) {
        var error = avcodec_send_frame(codecCtx, frame)
        if (error != 0) {
            throw IOException("Unable to encode frame: " + avErr2Str(error))
        }
        while (true) {
            val outputPacket = AVPacket()
            error = avcodec_receive_packet(codecCtx, outputPacket)
            if (error == org.bytedeco.ffmpeg.presets.avutil.AVERROR_EAGAIN() || error == AVERROR_EOF) {
                // need more input!
                break
            }
            if (error < 0) {
                throw IOException("Error encoding audio frame: " + avErr2Str(error))
            }
            av_packet_rescale_ts(outputPacket, codecCtx.time_base(), audioStream.time_base())
            outputPacket.stream_index(audioStream.index())
            error = av_interleaved_write_frame(ctx, outputPacket)
            av_packet_unref(outputPacket)
            if (error < 0) {
                throw IOException("Error writing audio frame: " + avErr2Str(error))
            }
        }
    }

    private fun closeSilently(cause: Throwable) {
        try {
            close()
        } catch (suppress: Throwable) {
            cause.addSuppressed(suppress)
        }
    }

    @Throws(IOException::class)
    override fun close() {
        // check if already closed
        if (closed.get()) {
            return
        }
        // try to acquire exclusive close "lock"
        if (!closed.compareAndSet(false, true)) {
            return
        }
        var suppressed: Throwable? = null
        try {
            flush()
            flushFrame(null)
            val error = av_interleaved_write_frame(ctx, null)
            if (error < 0) {
                throw IOException("Error writing audio frame: " + avErr2Str(error))
            }
            av_write_trailer(ctx)
        } catch (t: Throwable) {
            suppressed = t
        }
        try {
            closer.close()
        } catch (t: Throwable) {
            if (suppressed != null) {
                t.addSuppressed(suppressed)
            }
            throw Rethrow.rethrowIO(t)
        }
        if (suppressed != null) {
            throw Rethrow.rethrowIO(suppressed)
        }
    }
}
