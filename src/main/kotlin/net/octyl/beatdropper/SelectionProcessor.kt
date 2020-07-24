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

package net.octyl.beatdropper

import com.google.common.base.Preconditions
import com.google.common.io.ByteStreams
import com.google.common.io.LittleEndianDataInputStream
import com.google.common.io.LittleEndianDataOutputStream
import kotlinx.coroutines.runBlocking
import net.octyl.beatdropper.droppers.SampleModifier
import net.octyl.beatdropper.util.AvioCallbacks
import net.octyl.beatdropper.util.ChannelProvider
import net.octyl.beatdropper.util.FFmpegInputStream
import net.octyl.beatdropper.util.FFmpegOutputStream
import org.bytedeco.ffmpeg.global.avcodec
import org.lwjgl.system.MemoryUtil
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.UncheckedIOException
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.util.ArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.function.Supplier
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException

class SelectionProcessor(
    private val source: ChannelProvider<out ReadableByteChannel>,
    private val sink: ChannelProvider<out WritableByteChannel>,
    private val modifier: SampleModifier,
    private val raw: Boolean
) {
    // extra I/O overhead -> 8 extra threads
    private val taskPool = Executors.newWorkStealingPool(
        (Runtime.getRuntime().availableProcessors() + 8).coerceAtMost(64)
    )
    private val rawOutputStream: OutputStream
    private val rawInputStream: InputStream

    init {
        val pipedOutputStream = PipedOutputStream()
        rawOutputStream = BufferedOutputStream(pipedOutputStream)
        try {
            rawInputStream = PipedInputStream(pipedOutputStream)
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    @Throws(IOException::class, UnsupportedAudioFileException::class)
    fun process() {
        rawInputStream.use {
            openAudioInput().use { stream ->
                val format = stream.format
                Preconditions.checkState(format.channels == 2, "Must be 2 channel format")
                System.err.println("Loaded audio as $format (possibly via FFmpeg resampling)")
                val future = CompletableFuture.runAsync(Runnable {
                    try {
                        rawOutputStream.use {
                            processAudioStream(stream)
                        }
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    }
                }, taskPool)
                writeToSink(format.sampleRate)
                future.join()
            }
        }
    }

    @Throws(IOException::class, UnsupportedAudioFileException::class)
    private fun openAudioInput(): AudioInputStream {
        // unwrap via FFmpeg
        val stream = FFmpegInputStream(
            source.identifier,
            AvioCallbacks.forChannel(source.openChannel())
        )
        return AudioInputStream(
            stream,
            stream.audioFormat,
            AudioSystem.NOT_SPECIFIED.toLong()
        )
    }

    @Throws(IOException::class)
    private fun writeToSink(sampleRate: Float) {
        if (raw) {
            val fmt = AudioFormat(sampleRate, 16, 2, true,
                ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN
            )
            AudioInputStream(
                rawInputStream,
                fmt,
                AudioSystem.NOT_SPECIFIED.toLong()
            ).use { audio -> Channels.newOutputStream(sink.openChannel()).use { output -> AudioSystem.write(audio, AudioFileFormat.Type.AU, output) } }
        } else {
            FFmpegOutputStream(
                avcodec.AV_CODEC_ID_MP3,
                "mp3",
                sampleRate.toInt(),
                AvioCallbacks.forChannel(sink.openChannel())
            ).use { ffmpeg -> ByteStreams.copy(rawInputStream, ffmpeg) }
        }
    }

    @Throws(IOException::class)
    private fun processAudioStream(stream: AudioInputStream) {
        val bufferedStream = BufferedInputStream(stream)
        val dis: DataInput = when {
            stream.format.isBigEndian -> DataInputStream(bufferedStream)
            else -> LittleEndianDataInputStream(bufferedStream)
        }
        val sampleAmount = (modifier.requestedTimeLength() * stream.format.frameRate / 1000).toInt()
        val left = ShortArray(sampleAmount)
        val right = ShortArray(sampleAmount)
        var reading = true
        val sampOut = ArrayList<CompletableFuture<ShortBuffer>>()
        var numBatches = 0
        while (reading) {
            var read = 0
            while (read < left.size) {
                try {
                    left[read] = dis.readShort()
                    right[read] = dis.readShort()
                } catch (e: EOFException) {
                    reading = false
                    break
                }
                read++
            }
            sampOut.add(wrapAndSubmit(left, read, numBatches))
            sampOut.add(wrapAndSubmit(right, read, numBatches))
            numBatches++
        }
        val dos: DataOutput = when {
            stream.format.isBigEndian -> DataOutputStream(rawOutputStream)
            else -> LittleEndianDataOutputStream(rawOutputStream)
        }
        var i = 0
        while (i < sampOut.size) {
            val bufLeft = sampOut[i].join()
            val bufRight = sampOut[i + 1].join()
            val cutLeft = unpackAndFree(bufLeft)
            val cutRight = unpackAndFree(bufRight)
            Preconditions.checkState(cutLeft.size == cutRight.size,
                "channel processing should be equal, %s left != %s right",
                cutLeft.size, cutRight.size)
            for (k in cutLeft.indices) {
                dos.writeShort(cutLeft[k].toInt())
                dos.writeShort(cutRight[k].toInt())
            }
            i += 2
        }
    }

    private fun wrapAndSubmit(samples: ShortArray, read: Int, batchNum: Int): CompletableFuture<ShortBuffer> {
        val inputBuffer = copy(samples, read)

        // ensure no references to samples in task
        return submit(batchNum, inputBuffer)
    }

    private fun submit(batchNum: Int, inputBuffer: ShortBuffer): CompletableFuture<ShortBuffer> {
        return CompletableFuture.supplyAsync(Supplier {
            val sampUnpack = unpackAndFree(inputBuffer)
            val result = runBlocking { modifier.modifySamples(sampUnpack, batchNum) }
            copy(result, result.size)
        }, taskPool)
    }

    private fun unpackAndFree(inputBuffer: ShortBuffer): ShortArray {
        val sampUnpack: ShortArray
        try {
            // unpack
            sampUnpack = ShortArray(inputBuffer.remaining())
            inputBuffer[sampUnpack]
        } finally {
            // free the memory always
            MemoryUtil.memFree(inputBuffer)
        }
        return sampUnpack
    }

    private fun copy(samples: ShortArray?, read: Int): ShortBuffer {
        val sampBuf = MemoryUtil.memAllocShort(read)
        sampBuf.put(samples, 0, read)
        sampBuf.flip()
        return sampBuf
    }
}
