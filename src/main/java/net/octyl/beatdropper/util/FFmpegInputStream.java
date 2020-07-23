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
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO;
import static org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_STEREO;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_clone;
import static org.bytedeco.ffmpeg.global.avutil.av_opt_set_int;
import static org.bytedeco.ffmpeg.global.swresample.swr_alloc;
import static org.bytedeco.ffmpeg.global.swresample.swr_init;
import static org.bytedeco.ffmpeg.presets.avutil.AVERROR_EAGAIN;


import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.lwjgl.system.MemoryUtil;

/**
 * An input stream based on piping another stream through FFmpeg.
 */
public class FFmpegInputStream extends InputStream {

    private static final ThreadFactory THREAD_FACTORY = new ThreadFactoryBuilder()
        .setNameFormat("ffmpeg-decoder-%d")
        .setDaemon(true)
        .build();

    private final AutoCloser closer = new AutoCloser();
    private final AVFormatContext ctx = closer.register(avformat_alloc_context(), avformat::avformat_free_context);

    {
        checkState(ctx != null, "Unable to allocate context");
    }

    private final AVCodecContext codecCtx;
    private final AVFrame frame;
    private final AVFrame targetFrame;
    private final AVPacket packet;
    private final int audioStreamIndex;
    private final AudioFormat audioFormat;
    private final SwrContext swrCtx;
    private final BlockingQueue<AVFrame> pendingFrames = new ArrayBlockingQueue<>(20);
    private final Thread sendingThread;
    private final AtomicBoolean closed = new AtomicBoolean();
    private ByteBuffer currentFrame;

    public FFmpegInputStream(String name, AvioCallbacks ioCallbacks) throws UnsupportedAudioFileException {
        var avioCtx = closer.register(ioCallbacks).allocateContext(4096, false);
        checkState(avioCtx != null, "Unable to allocate IO context");
        ctx.pb(closer.register(avioCtx));
        int error = avformat_open_input(ctx, name, null, null);
        if (error != 0) {
            throw new IllegalStateException("Error opening input: " + av_err2str(error));
        }
        try {
            error = avformat_find_stream_info(ctx, (AVDictionary) null);
            if (error != 0) {
                throw new IllegalStateException("Error finding stream info: " + av_err2str(error));
            }
            if (ctx.nb_streams() == 0) {
                throw new UnsupportedAudioFileException("No streams detected by FFmpeg");
            }

            var audioStreamIndexOpt = IntStream.range(0, ctx.nb_streams())
                .filter(streamIdx -> ctx.streams(streamIdx).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO)
                .findFirst();
            if (audioStreamIndexOpt.isEmpty()) {
                throw new UnsupportedAudioFileException("No audio stream detected by FFmpeg");
            }
            audioStreamIndex = audioStreamIndexOpt.getAsInt();
            var audioStream = ctx.streams(audioStreamIndex);

            // This format matches our requirements, signed stereo shorts
            audioFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                audioStream.codecpar().sample_rate(),
                16,
                2,
                4,
                audioStream.codecpar().sample_rate(),
                ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN
            );

            swrCtx = closer.register(swr_alloc(), swresample::swr_free);
            av_opt_set_int(swrCtx, "in_channel_layout", audioStream.codecpar().channel_layout(), 0);
            av_opt_set_int(swrCtx, "in_sample_rate", audioStream.codecpar().sample_rate(), 0);
            av_opt_set_int(swrCtx, "in_sample_fmt", audioStream.codecpar().format(), 0);

            av_opt_set_int(swrCtx, "out_channel_layout", AV_CH_LAYOUT_STEREO, 0);
            av_opt_set_int(swrCtx, "out_sample_rate", audioStream.codecpar().sample_rate(), 0);
            av_opt_set_int(swrCtx, "out_sample_fmt", AV_SAMPLE_FMT_S16, 0);

            error = swr_init(swrCtx);
            if (error != 0) {
                throw new IllegalStateException("Error initializing swr: " + av_err2str(error));
            }

            var decoder = avcodec_find_decoder(audioStream.codecpar().codec_id());
            if (decoder == null) {
                throw new UnsupportedAudioFileException("Not decode-able by FFmpeg");
            }
            codecCtx = closer.register(avcodec_alloc_context3(decoder), avcodec::avcodec_free_context);
            if (codecCtx == null) {
                throw new IllegalStateException("Unable to allocate codec context");
            }
            error = avcodec_parameters_to_context(codecCtx, audioStream.codecpar());
            if (error != 0) {
                throw new IllegalStateException("Error passing parameters: " + av_err2str(error));
            }
            error = avcodec_open2(codecCtx, decoder, (AVDictionary) null);
            if (error != 0) {
                throw new IllegalStateException("Error opening codec: " + av_err2str(error));
            }
            frame = closer.register(av_frame_alloc(), avutil::av_frame_free);
            if (frame == null) {
                throw new IllegalStateException("Unable to allocate frame");
            }
            targetFrame = closer.register(av_frame_alloc(), avutil::av_frame_free);
            if (targetFrame == null) {
                throw new IllegalStateException("Unable to target frame");
            }
            targetFrame
                .channel_layout(AV_CH_LAYOUT_STEREO)
                .sample_rate(audioStream.codecpar().sample_rate())
                .format(AV_SAMPLE_FMT_S16);
            packet = closer.register(av_packet_alloc(), avcodec::av_packet_free);
            if (packet == null) {
                throw new IllegalStateException("Unable to allocate packet");
            }

            codecCtx.pkt_timebase(audioStream.time_base());

            sendingThread = THREAD_FACTORY.newThread(this::decode);
            sendingThread.start();
            closer.register(sendingThread, t -> {
                t.interrupt();
                if (t != Thread.currentThread()) {
                    t.join();
                }
            });
        } catch (Throwable t) {
            closeSilently(t);
            throw t;
        }
    }

    private void closeSilently(Throwable cause) {
        try {
            close();
        } catch (Throwable suppress) {
            cause.addSuppressed(suppress);
        }
    }

    public AudioFormat getAudioFormat() {
        return audioFormat;
    }

    private void decode() {
        try {
            // packet reading loop
            readPacket:
            while (av_read_frame(ctx, packet) >= 0) {
                try {
                    if (packet.stream_index() != audioStreamIndex) {
                        continue;
                    }
                    int error = avcodec_send_packet(codecCtx, packet);
                    if (error != 0) {
                        throw new IllegalStateException("Error sending packet to decoder: " + av_err2str(error));
                    }
                    // audio frame reading loop
                    while (true) {
                        error = avcodec_receive_frame(codecCtx, frame);
                        if (error == AVERROR_EAGAIN() || error == AVERROR_EOF) {
                            continue readPacket;
                        }
                        if (error != 0) {
                            throw new IllegalStateException("Error getting frame from decoder: " + av_err2str(error));
                        }
                        var convertedFrames = new SwrResampleIterator(swrCtx, frame, targetFrame);
                        while (convertedFrames.hasNext()) {
                            var converted = convertedFrames.next();
                            var copy = av_frame_clone(converted);
                            while (!pendingFrames.offer(copy, 50, TimeUnit.MILLISECONDS)) {
                                if (closed.get()) {
                                    return;
                                }
                            }
                        }
                    }
                } finally {
                    av_packet_unref(packet);
                }
            }
        } catch (Throwable t) {
            closeSilently(t);
            Throwables.throwIfUnchecked(t);
            throw new RuntimeException(t);
        }
    }

    private ByteBuffer loadCurrentFrame() {
        if (currentFrame == null || !currentFrame.hasRemaining()) {
            if (currentFrame != null) {
                MemoryUtil.memFree(currentFrame);
                // paranoid, do not double-free
                currentFrame = null;
            }
            try {
                AVFrame next;
                do {
                    if (!sendingThread.isAlive()) {
                        next = null;
                        break;
                    }
                    next = pendingFrames.poll(50, TimeUnit.MILLISECONDS);
                }
                while (next == null);

                if (next == null) {
                    return null;
                }

                var outputBuffer = MemoryUtil.memAlloc(next.nb_samples() * 4);
                MemoryUtil.memCopy(
                    next.data(0).address(),
                    MemoryUtil.memAddress(outputBuffer),
                    outputBuffer.remaining()
                );
                currentFrame = outputBuffer;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        return currentFrame;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        var buffer = loadCurrentFrame();
        if (buffer == null) {
            return -1;
        }
        int allowedLen = Math.min(len, buffer.remaining());
        buffer.get(b, off, allowedLen);
        return allowedLen;
    }

    @Override
    public int read() {
        var buffer = loadCurrentFrame();
        if (buffer == null) {
            return -1;
        }
        return buffer.get();
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
        try {
            closer.close();
        } catch (Exception e) {
            Throwables.propagateIfPossible(e, IOException.class);
            Throwables.throwIfUnchecked(e);
            throw new RuntimeException(e);
        } finally {
            if (currentFrame != null) {
                MemoryUtil.memFree(currentFrame);
            }
        }
    }
}
