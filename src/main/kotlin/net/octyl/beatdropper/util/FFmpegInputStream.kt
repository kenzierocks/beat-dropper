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

import com.google.common.base.Throwables
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_STEREO
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_int
import org.bytedeco.ffmpeg.global.swresample.swr_alloc
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.presets.avutil
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.UnsupportedAudioFileException

/**
 * An input stream based on piping another stream through FFmpeg.
 */
class FFmpegInputStream(name: String, ioCallbacks: AvioCallbacks) : InputStream() {

    companion object {
        private val LOGGER = LoggerFactory.getLogger(FFmpegInputStream::class.java)
    }

    private val closer = AutoCloser()
    private val ctx = closer.register(avformat_alloc_context(), { s -> avformat_free_context(s) })
        ?: error("Unable to allocate context")
    private val codecCtx: AVCodecContext
    private val frame: AVFrame
    private val targetFrame: AVFrame
    private val packet: AVPacket
    private val audioStreamIndex: Int
    val audioFormat: AudioFormat
    private val swrCtx: SwrContext
    private val frameSequence: Iterator<ByteBuffer>
    private val closed = AtomicBoolean()
    private var currentFrame: ByteBuffer? = null
    private fun closeSilently(cause: Throwable) {
        try {
            close()
        } catch (suppress: Throwable) {
            cause.addSuppressed(suppress)
        }
    }

    init {
        val avioCtx = closer.register(ioCallbacks).allocateContext(4096, false)
            ?: error("Unable to allocate IO context")
        ctx.pb(closer.register(avioCtx))
        var error = avformat_open_input(ctx, name, null, null)
        check(error == 0) { "Error opening input: " + avErr2Str(error) }
        try {
            error = avformat_find_stream_info(ctx, null as AVDictionary?)
            check(error == 0) { "Error finding stream info: " + avErr2Str(error) }

            if (ctx.nb_streams() == 0) {
                throw UnsupportedAudioFileException("No streams detected by FFmpeg")
            }

            audioStreamIndex = (0 until ctx.nb_streams())
                .firstOrNull { streamIdx: Int ->
                    ctx.streams(streamIdx).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO
                }
                ?: throw UnsupportedAudioFileException("No audio stream detected by FFmpeg")

            val audioStream = ctx.streams(audioStreamIndex)

            // This format matches our requirements, signed stereo shorts
            audioFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                audioStream.codecpar().sample_rate().toFloat(),
                16,
                2,
                4,
                audioStream.codecpar().sample_rate().toFloat(),
                ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN
            )

            swrCtx = closer.register(swr_alloc(), { s -> swr_free(s) })
                ?: error("Unable to allocate swr context")

            av_opt_set_int(swrCtx, "in_channel_layout", audioStream.codecpar().channel_layout(), 0)
            av_opt_set_int(swrCtx, "in_sample_rate", audioStream.codecpar().sample_rate().toLong(), 0)
            av_opt_set_int(swrCtx, "in_sample_fmt", audioStream.codecpar().format().toLong(), 0)

            av_opt_set_int(swrCtx, "out_channel_layout", AV_CH_LAYOUT_STEREO, 0)
            av_opt_set_int(swrCtx, "out_sample_rate", audioStream.codecpar().sample_rate().toLong(), 0)
            av_opt_set_int(swrCtx, "out_sample_fmt", AV_SAMPLE_FMT_S16.toLong(), 0)

            error = swr_init(swrCtx)
            check(error == 0) { "Error initializing swr: " + avErr2Str(error) }

            val decoder = avcodec_find_decoder(audioStream.codecpar().codec_id())
                ?: throw UnsupportedAudioFileException("Not decode-able by FFmpeg")

            codecCtx = closer.register(
                avcodec_alloc_context3(decoder),
                { avctx -> avcodec_free_context(avctx) }
            ) ?: error("Unable to allocate codec context")

            error = avcodec_parameters_to_context(codecCtx, audioStream.codecpar())
            check(error == 0) { "Error passing parameters: " + avErr2Str(error) }

            error = avcodec_open2(codecCtx, decoder, null as AVDictionary?)
            check(error == 0) { "Error opening codec: " + avErr2Str(error) }

            frame = closer.register(
                av_frame_alloc(),
                { frame -> av_frame_free(frame) }
            ) ?: error("Unable to allocate frame")

            targetFrame = closer.register(
                av_frame_alloc(),
                { frame -> av_frame_free(frame) }
            ) ?: error("Unable to allocate target frame")
            targetFrame
                .channel_layout(AV_CH_LAYOUT_STEREO)
                .sample_rate(audioStream.codecpar().sample_rate())
                .format(AV_SAMPLE_FMT_S16)

            packet = closer.register(
                av_packet_alloc(),
                { pkt-> av_packet_free(pkt) }
            ) ?: error("Unable to allocate packet")

            codecCtx.pkt_timebase(audioStream.time_base())

            frameSequence = sequence<ByteBuffer> { decode() }.iterator()
        } catch (t: Throwable) {
            closeSilently(t)
            throw t
        }
    }

    private suspend fun SequenceScope<ByteBuffer>.decode() {
        try {
            // packet reading loop
            readPacket@ while (av_read_frame(ctx, packet) >= 0) {
                try {
                    if (packet.stream_index() != audioStreamIndex) {
                        continue
                    }
                    var error = avcodec_send_packet(codecCtx, packet)
                    check(error == 0) { "Error sending packet to decoder: " + avErr2Str(error) }
                    // audio frame reading loop
                    while (true) {
                        error = avcodec_receive_frame(codecCtx, frame)
                        if (error == avutil.AVERROR_EAGAIN() || error == AVERROR_EOF) {
                            continue@readPacket
                        }
                        check(error == 0) { "Error getting frame from decoder: " + avErr2Str(error) }
                        for (next in swrResampleSequence(swrCtx, frame, targetFrame)) {
                            val outputBuffer = MemoryUtil.memAlloc(next.nb_samples() * 4)
                            MemoryUtil.memCopy(
                                next.data(0).address(),
                                MemoryUtil.memAddress(outputBuffer),
                                outputBuffer.remaining().toLong()
                            )
                            yield(outputBuffer)
                        }
                    }
                } finally {
                    av_packet_unref(packet)
                }
            }
        } catch (t: Throwable) {
            closeSilently(t)
            LOGGER.error("FFmpeg input stream crashed!", t)
        }
    }

    private fun loadCurrentFrame(): ByteBuffer? {
        var localFrame = currentFrame
        if (localFrame == null || !localFrame.hasRemaining()) {
            if (localFrame != null) {
                MemoryUtil.memFree(localFrame)
                // paranoid, do not double-free
                currentFrame = null
            }
            localFrame = when {
                frameSequence.hasNext() -> frameSequence.next()
                else -> null
            }
        }
        return localFrame
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val buffer = loadCurrentFrame() ?: return -1
        val allowedLen = len.coerceAtMost(buffer.remaining())
        buffer[b, off, allowedLen]
        return allowedLen
    }

    override fun read(): Int {
        val buffer = loadCurrentFrame() ?: return -1
        return buffer.get().toInt()
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
        try {
            closer.close()
        } catch (e: Exception) {
            Throwables.propagateIfPossible(e, IOException::class.java)
            Throwables.throwIfUnchecked(e)
            throw RuntimeException(e)
        } finally {
            val localFrame = currentFrame
            if (localFrame != null) {
                MemoryUtil.memFree(localFrame)
                // paranoid, do not double-free
                currentFrame = null
            }
        }
    }
}
