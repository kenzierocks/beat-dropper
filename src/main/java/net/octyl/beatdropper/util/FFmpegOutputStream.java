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

package net.octyl.beatdropper.util;

import static com.google.common.base.Preconditions.checkState;
import static net.octyl.beatdropper.util.FFmpegMacros.av_err2str;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_get_name;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame;
import static org.bytedeco.ffmpeg.global.avformat.AVFMT_GLOBALHEADER;
import static org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_write_trailer;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2;
import static org.bytedeco.ffmpeg.global.avformat.avformat_new_stream;
import static org.bytedeco.ffmpeg.global.avformat.avformat_write_header;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF;
import static org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_STEREO;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable;
import static org.bytedeco.ffmpeg.global.avutil.av_get_bytes_per_sample;
import static org.bytedeco.ffmpeg.global.avutil.av_get_planar_sample_fmt;
import static org.bytedeco.ffmpeg.global.avutil.av_make_q;
import static org.bytedeco.ffmpeg.global.avutil.av_opt_set_int;
import static org.bytedeco.ffmpeg.global.avutil.av_rescale_q;
import static org.bytedeco.ffmpeg.global.swresample.swr_alloc;
import static org.bytedeco.ffmpeg.global.swresample.swr_init;
import static org.bytedeco.ffmpeg.presets.avutil.AVERROR_EAGAIN;


import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * An input stream based on piping another stream through FFmpeg.
 */
public class FFmpegOutputStream extends OutputStream {

    private final AutoCloser closer = new AutoCloser();
    // allocate just a pointer, no actual object
    private final AVFormatContext ctx = new AVFormatContext(null);
    private final AVCodecContext codecCtx;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AVStream audioStream;
    private final AVFrame outputFrame;
    private final AVFrame resampledFrame;
    private final SwrContext swrCtx;
    private final ByteBuffer outputBuffer;
    private long pts = 0;

    public FFmpegOutputStream(int codecId, String containerName, int sampleRate, AvioCallbacks avioCallbacks) throws IOException {
        int error = avformat_alloc_output_context2(ctx, null, containerName, null);
        if (error != 0) {
            throw new IllegalStateException("Error allocating output context: " + av_err2str(error));
        }
        closer.register(ctx, avformat::avformat_free_context);
        var avioCtx = closer.register(avioCallbacks).allocateContext(4096, true);
        checkState(avioCtx != null, "Unable to allocate IO context");
        closer.register(avioCtx);
        ctx.pb(avioCtx);
        try {
            var codec = avcodec_find_encoder(codecId);
            if (codec == null) {
                throw new IllegalStateException("Could not find encoder for " + avcodec_get_name(codecId));
            }
            var supportedFmts = codec.sample_fmts();
            int desiredFormat = IntStream.iterate(0, i -> i + 1)
                .map(supportedFmts::get)
                .takeWhile(fmt -> fmt != -1)
                .boxed()
                .max(Comparator.comparing(fmt -> {
                    int bytes = av_get_bytes_per_sample(fmt);
                    boolean planar = av_get_planar_sample_fmt(fmt) != 0;
                    // prefer 2 bytes / 16 bits (our current format)
                    if (bytes == 2) {
                        // prefer non-planar slightly
                        return Integer.MAX_VALUE - (planar ? 1 : 0);
                    }
                    // prefer more bytes, non-planar
                    return bytes * 2 - (planar ? 1 : 0);
                }))
                .orElseThrow(() -> new IllegalStateException("No formats available"));
            audioStream = avformat_new_stream(ctx, codec);
            checkState(audioStream != null, "Unable to allocate audio stream");
            audioStream.id(0);
            codecCtx = closer.register(avcodec_alloc_context3(codec), avcodec::avcodec_free_context);
            codecCtx
                .sample_fmt(desiredFormat)
                .bit_rate(128_000)
                .sample_rate(sampleRate)
                .channels(2)
                .channel_layout(AV_CH_LAYOUT_STEREO)
                .time_base(av_make_q(1, sampleRate));
            audioStream.time_base(av_make_q(1, sampleRate));
            if ((ctx.oformat().flags() & AVFMT_GLOBALHEADER) != 0) {
                codecCtx.flags(AV_CODEC_FLAG_GLOBAL_HEADER);
            }
            error = avcodec_open2(codecCtx, codec, (AVDictionary) null);
            if (error != 0) {
                throw new IllegalStateException("Unable to open codec: " + av_err2str(error));
            }
            avcodec_parameters_from_context(audioStream.codecpar(), codecCtx);

            swrCtx = closer.register(swr_alloc(), swresample::swr_free);
            av_opt_set_int(swrCtx, "in_channel_layout", AV_CH_LAYOUT_STEREO, 0);
            av_opt_set_int(swrCtx, "in_sample_rate", audioStream.codecpar().sample_rate(), 0);
            av_opt_set_int(swrCtx, "in_sample_fmt", AV_SAMPLE_FMT_S16, 0);

            av_opt_set_int(swrCtx, "out_channel_layout", audioStream.codecpar().channel_layout(), 0);
            av_opt_set_int(swrCtx, "out_sample_rate", audioStream.codecpar().sample_rate(), 0);
            av_opt_set_int(swrCtx, "out_sample_fmt", audioStream.codecpar().format(), 0);

            error = swr_init(swrCtx);
            if (error != 0) {
                throw new IllegalStateException("Error initializing swr: " + av_err2str(error));
            }

            outputFrame = closer.register(av_frame_alloc(), avutil::av_frame_free);
            checkState(outputFrame != null, "Unable to allocate output frame");
            outputFrame
                .channel_layout(AV_CH_LAYOUT_STEREO)
                .sample_rate(audioStream.codecpar().sample_rate())
                .format(AV_SAMPLE_FMT_S16)
                .nb_samples(codecCtx.frame_size());
            error = av_frame_get_buffer(outputFrame, 0);
            if (error != 0) {
                throw new IllegalStateException("Unable to get buffer: " + av_err2str(error));
            }

            resampledFrame = closer.register(av_frame_alloc(), avutil::av_frame_free);
            if (resampledFrame == null) {
                throw new IllegalStateException("Unable to allocate resampled frame");
            }
            resampledFrame
                .format(codecCtx.sample_fmt())
                .sample_rate(sampleRate)
                .channel_layout(codecCtx.channel_layout())
                .nb_samples(codecCtx.frame_size());

            outputBuffer = MemoryUtil.memAlloc(Short.BYTES * outputFrame.nb_samples() * codecCtx.channels());
            closer.register(outputBuffer, MemoryUtil::memFree);

            error = avformat_write_header(ctx, (AVDictionary) null);
            if (error != 0) {
                throw new IOException("Unable to write header: " + av_err2str(error));
            }
        } catch (Throwable t) {
            closeSilently(t);
            throw t;
        }
    }

    @Override
    public void write(int b) throws IOException {
        outputBuffer.put((byte) b);
        if (!outputBuffer.hasRemaining()) {
            flush();
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        while (true) {
            int size = Math.min(outputBuffer.remaining(), len);
            outputBuffer.put(b, off, size);
            if (!outputBuffer.hasRemaining()) {
                flush();
            }
            off += size;
            len -= size;
            if (len == 0) {
                // we have consumed all the data
                return;
            }
            // otherwise, loop to fill our buffer again
        }
    }

    @Override
    public void flush() throws IOException {
        if (outputBuffer.hasRemaining()) {
            // buffer must be full, otherwise the frame is too small
            return;
        }

        outputBuffer.flip();
        int error = av_frame_make_writable(outputFrame);
        if (error != 0) {
            throw new IOException("Unable to make output frame writeable: " + av_err2str(error));
        }
        var length = outputBuffer.remaining();
        MemoryUtil.memCopy(
            MemoryUtil.memAddress(outputBuffer),
            outputFrame.data(0).address(),
            length
        );
        outputBuffer.clear();
        outputFrame.pts(pts);
        pts += length / (Short.BYTES * codecCtx.channels());

        var convertedFrames = new SwrResampleIterator(swrCtx, outputFrame, resampledFrame);

        while (convertedFrames.hasNext()) {
            var next = convertedFrames.next();
            next.pts(pts);
            flushFrame(next);
        }
    }

    private void flushFrame(@Nullable AVFrame frame) throws IOException {
        int error = avcodec_send_frame(codecCtx, frame);
        if (error != 0) {
            throw new IOException("Unable to encode frame: " + av_err2str(error));
        }

        while (true) {
            var outputPacket = new AVPacket();
            error = avcodec_receive_packet(codecCtx, outputPacket);
            if (error == AVERROR_EAGAIN() || error == AVERROR_EOF) {
                // need more input!
                break;
            }
            if (error < 0) {
                throw new IOException("Error encoding audio frame: " + av_err2str(error));
            }

            av_packet_rescale_ts(outputPacket, codecCtx.time_base(), audioStream.time_base());
            outputPacket.stream_index(audioStream.index());
            error = av_interleaved_write_frame(ctx, outputPacket);
            av_packet_unref(outputPacket);
            if (error < 0) {
                throw new IOException("Error writing audio frame: " + av_err2str(error));
            }
        }
    }

    private void closeSilently(Throwable cause) {
        try {
            close();
        } catch (Throwable suppress) {
            cause.addSuppressed(suppress);
        }
    }

    @Override
    public void close() throws IOException {
        // check if already closed
        if (closed.get()) {
            return;
        }
        // try to acquire exclusive close "lock"
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Throwable suppressed = null;
        try {
            flush();
            flushFrame(null);
            int error = av_interleaved_write_frame(ctx, null);
            if (error < 0) {
                throw new IOException("Error writing audio frame: " + av_err2str(error));
            }
            av_write_trailer(ctx);
        } catch (Throwable t) {
            suppressed = t;
        }
        try {
            closer.close();
        } catch (Throwable t) {
            if (suppressed != null) {
                t.addSuppressed(suppressed);
            }
            throw Rethrow.rethrowIO(t);
        }
        if (suppressed != null) {
            throw Rethrow.rethrowIO(suppressed);
        }
    }
}
