/*
 * This file is part of beat-dropper, licensed under the MIT License (MIT).
 *
 * Copyright (c) Kenzie Togami <https://octyl.net>
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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioFileFormat.Type;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.lwjgl.system.MemoryUtil;

import com.google.common.base.Throwables;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import net.octyl.beatdropper.droppers.SampleModifier;

public class SelectionProcessor {

    private static final FFmpegExecutor ffExecutor;
    static {
        try {
            ffExecutor = new FFmpegExecutor();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final ExecutorService taskPool = Executors.newWorkStealingPool();
    private final ByteArrayDataOutput output = ByteStreams.newDataOutput();
    private final Path source;
    private final SampleModifier modifier;

    public SelectionProcessor(Path source, SampleModifier selector) {
        this.source = checkNotNull(source, "source");
        this.modifier = checkNotNull(selector, "selector");
    }

    public void process() throws IOException, UnsupportedAudioFileException {
        AudioInputStream stream = AudioSystem.getAudioInputStream(source.toFile());
        AudioFormat format = stream.getFormat();
        AudioFormat decodedFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                format.getSampleRate(),
                16,
                format.getChannels(),
                format.getChannels() * 2,
                format.getSampleRate(),
                true);
        AudioInputStream din = AudioSystem.getAudioInputStream(decodedFormat, stream);

        int channels = decodedFormat.getChannels();
        int frameSize = decodedFormat.getFrameSize();
        System.err.println(decodedFormat);

        if (frameSize == -1) {
            frameSize = 2;
        }

        processAudioStream(din, channels, frameSize);
        writeToFile(format.getSampleRate(), renameFile(source));
    }

    private Path renameFile(Path file) {
        String newFileName = renameFile(file.getFileName().toString());
        return file.resolveSibling(newFileName);
    }

    private String renameFile(String fileName) {
        String modStr = " [" + modifier.describeModification() + "]";
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1) {
            return fileName + modStr;
        }
        return fileName.substring(0, lastDot) + modStr + fileName.substring(lastDot);
    }

    private void writeToFile(float sampleRate, Path target) throws IOException {
        Path temporaryFile = Files.createTempFile("beat-dropper", ".wav");
        try {
            AudioFormat fmt = new AudioFormat(sampleRate, 16, 2, true, true);
            AudioInputStream audio = new AudioInputStream(
                    new ByteArrayInputStream(output.toByteArray()),
                    fmt,
                    AudioSystem.NOT_SPECIFIED);
            AudioSystem.write(audio, Type.WAVE, temporaryFile.toFile());
            ffExecutor.createJob(new FFmpegBuilder()
                    .addInput(temporaryFile.toString())
                    .overrideOutputFiles(true)
                    .addOutput(target.toString())
                    .done())
                    .run();
        } finally {
            Files.delete(temporaryFile);
        }
    }

    private void processAudioStream(AudioInputStream stream, int channels, int frameSize) throws IOException {
        DataInputStream dis = new DataInputStream(new BufferedInputStream(stream));
        int sampleAmount = (int) ((modifier.requestedTimeLength() * stream.getFormat().getFrameRate()) / 1000);
        short[] left = new short[sampleAmount];
        short[] right = new short[sampleAmount];
        boolean reading = true;
        List<CompletableFuture<ShortBuffer>> sampOut = new ArrayList<>();
        for (int numBatches = 0; reading; numBatches++) {
            int read = 0;
            if (channels == 1) {
                while (read < left.length) {
                    try {
                        short nextShort = dis.readShort();
                        left[read] = nextShort;
                        right[read] = nextShort;
                    } catch (EOFException e) {
                        reading = false;
                        break;
                    }
                    read++;
                }
            } else {
                while (read < left.length * 2) {
                    short[] buf = read % 2 == 0 ? left : right;
                    try {
                        buf[read / 2] = dis.readShort();
                    } catch (EOFException e) {
                        reading = false;
                        break;
                    }
                    read++;
                }
            }

            sampOut.add(wrapAndSubmit(left, numBatches));
            sampOut.add(wrapAndSubmit(right, numBatches));
        }

        for (int i = 0; i < sampOut.size(); i += 2) {
            ShortBuffer bufLeft = getFuture(sampOut.get(i));
            ShortBuffer bufRight = getFuture(sampOut.get(i + 1));
            short[] cutLeft = unpackAndFree(bufLeft);
            short[] cutRight = unpackAndFree(bufRight);

            checkState(cutLeft.length == cutRight.length,
                    "channel processing should be equal, %s left != %s right",
                    cutLeft.length, cutRight.length);
            for (int k = 0; k < cutLeft.length; k++) {
                output.writeShort(cutLeft[k]);
                output.writeShort(cutRight[k]);
            }
        }
    }

    private static <T> T getFuture(CompletableFuture<T> ftr) {
        try {
            return ftr.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            Throwables.throwIfUnchecked(t);
            throw new RuntimeException(t);
        }
    }

    private CompletableFuture<ShortBuffer> wrapAndSubmit(short[] samples, int batchNum) {
        ShortBuffer inputBuffer = copy(samples);

        // ensure no references to samples in task
        return submit(batchNum, inputBuffer);
    }

    private CompletableFuture<ShortBuffer> submit(int batchNum, ShortBuffer inputBuffer) {
        return CompletableFuture.supplyAsync(() -> {
            short[] sampUnpack = unpackAndFree(inputBuffer);
            short[] result = modifier.modifySamples(sampUnpack, batchNum);
            return copy(result);
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

    private ShortBuffer copy(short[] samples) {
        ShortBuffer sampBuf = MemoryUtil.memAllocShort(samples.length);
        sampBuf.put(samples);
        sampBuf.flip();
        return sampBuf;
    }

}
