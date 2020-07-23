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

package net.octyl.beatdropper;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;


import javax.sound.sampled.AudioFileFormat.Type;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.io.ByteStreams;
import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.io.LittleEndianDataOutputStream;
import net.octyl.beatdropper.droppers.SampleModifier;
import net.octyl.beatdropper.util.AvioCallbacks;
import net.octyl.beatdropper.util.ChannelProvider;
import net.octyl.beatdropper.util.FFmpegInputStream;
import net.octyl.beatdropper.util.FFmpegOutputStream;
import org.bytedeco.ffmpeg.global.avcodec;
import org.lwjgl.system.MemoryUtil;

public class SelectionProcessor {

    // extra I/O overhead -> 8 extra threads
    private final ExecutorService taskPool = Executors.newWorkStealingPool(
        Math.min(64, Runtime.getRuntime().availableProcessors() + 8)
    );
    private final OutputStream rawOutputStream;
    private final InputStream rawInputStream;
    private final ChannelProvider<? extends ReadableByteChannel> source;
    private final ChannelProvider<? extends WritableByteChannel> sink;
    private final SampleModifier modifier;
    private final boolean raw;

    public SelectionProcessor(ChannelProvider<? extends ReadableByteChannel> source,
                              ChannelProvider<? extends WritableByteChannel> sink,
                              SampleModifier selector,
                              boolean raw) {
        this.source = checkNotNull(source, "source");
        this.sink = checkNotNull(sink, "sink");
        this.modifier = checkNotNull(selector, "selector");
        this.raw = raw;
        var pipedOutputStream = new PipedOutputStream();
        this.rawOutputStream = new BufferedOutputStream(pipedOutputStream);
        try {
            this.rawInputStream = new PipedInputStream(pipedOutputStream);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void process() throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream stream = openAudioInput()) {
            AudioFormat format = stream.getFormat();
            checkState(format.getChannels() == 2, "Must be 2 channel format");
            System.err.println("Loaded audio as " + format + " (possibly via FFmpeg resampling)");

            var future = CompletableFuture.runAsync(() -> {
                try {
                    try {
                        processAudioStream(stream);
                    } finally {
                        rawOutputStream.close();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, taskPool);
            writeToSink(format.getSampleRate());
            future.join();
        } finally {
            rawInputStream.close();
        }
    }

    private AudioInputStream openAudioInput() throws IOException, UnsupportedAudioFileException {
        // unwrap via FFmpeg
        var stream = new FFmpegInputStream(
            source.getIdentifier(),
            AvioCallbacks.forChannel(source.openChannel())
        );
        return new AudioInputStream(
            stream,
            stream.getAudioFormat(),
            AudioSystem.NOT_SPECIFIED
        );
    }

    private void writeToSink(float sampleRate) throws IOException {
        if (raw) {
            AudioFormat fmt = new AudioFormat(sampleRate, 16, 2, true,
                ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN
            );
            try (AudioInputStream audio = new AudioInputStream(
                rawInputStream,
                fmt,
                AudioSystem.NOT_SPECIFIED
            );
                 var output = Channels.newOutputStream(sink.openChannel())) {
                AudioSystem.write(audio, Type.AU, output);
            }
        } else {
            try (var ffmpeg = new FFmpegOutputStream(
                avcodec.AV_CODEC_ID_MP3,
                "mpeg",
                (int) sampleRate,
                AvioCallbacks.forChannel(sink.openChannel())
            )) {
                ByteStreams.copy(rawInputStream, ffmpeg);
            }
        }
    }

    private void processAudioStream(AudioInputStream stream) throws IOException {
        var bufferedStream = new BufferedInputStream(stream);
        DataInput dis = stream.getFormat().isBigEndian()
            ? new DataInputStream(bufferedStream)
            : new LittleEndianDataInputStream(bufferedStream);
        int sampleAmount = (int) ((modifier.requestedTimeLength() * stream.getFormat().getFrameRate()) / 1000);
        short[] left = new short[sampleAmount];
        short[] right = new short[sampleAmount];
        boolean reading = true;
        var sampOut = new ArrayList<CompletableFuture<ShortBuffer>>();
        for (int numBatches = 0; reading; numBatches++) {
            int read = 0;
            while (read < left.length) {
                try {
                    left[read] = dis.readShort();
                    right[read] = dis.readShort();
                } catch (EOFException e) {
                    reading = false;
                    break;
                }
                read++;
            }

            sampOut.add(wrapAndSubmit(left, read, numBatches));
            sampOut.add(wrapAndSubmit(right, read, numBatches));
        }

        DataOutput dos = stream.getFormat().isBigEndian()
            ? new DataOutputStream(rawOutputStream)
            : new LittleEndianDataOutputStream(rawOutputStream);
        for (int i = 0; i < sampOut.size(); i += 2) {
            ShortBuffer bufLeft = sampOut.get(i).join();
            ShortBuffer bufRight = sampOut.get(i + 1).join();
            short[] cutLeft = unpackAndFree(bufLeft);
            short[] cutRight = unpackAndFree(bufRight);

            checkState(cutLeft.length == cutRight.length,
                "channel processing should be equal, %s left != %s right",
                cutLeft.length, cutRight.length);
            for (int k = 0; k < cutLeft.length; k++) {
                dos.writeShort(cutLeft[k]);
                dos.writeShort(cutRight[k]);
            }
        }
    }

    private CompletableFuture<ShortBuffer> wrapAndSubmit(short[] samples, int read, int batchNum) {
        ShortBuffer inputBuffer = copy(samples, read);

        // ensure no references to samples in task
        return submit(batchNum, inputBuffer);
    }

    private CompletableFuture<ShortBuffer> submit(int batchNum, ShortBuffer inputBuffer) {
        return CompletableFuture.supplyAsync(() -> {
            short[] sampUnpack = unpackAndFree(inputBuffer);
            short[] result = modifier.modifySamples(sampUnpack, batchNum);
            return copy(result, result.length);
        }, taskPool);
    }

    private short[] unpackAndFree(ShortBuffer inputBuffer) {
        short[] sampUnpack;
        try {
            // unpack
            sampUnpack = new short[inputBuffer.remaining()];
            inputBuffer.get(sampUnpack);
        } finally {
            // free the memory always
            MemoryUtil.memFree(inputBuffer);
        }
        return sampUnpack;
    }

    private ShortBuffer copy(short[] samples, int read) {
        ShortBuffer sampBuf = MemoryUtil.memAllocShort(read);
        sampBuf.put(samples, 0, read);
        sampBuf.flip();
        return sampBuf;
    }

}
