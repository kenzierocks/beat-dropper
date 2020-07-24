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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.octyl.beatdropper.droppers.SampleModifier
import net.octyl.beatdropper.util.AvioCallbacks
import net.octyl.beatdropper.util.ChannelProvider
import net.octyl.beatdropper.util.FFmpegInputStream
import net.octyl.beatdropper.util.FFmpegOutputStream
import org.bytedeco.ffmpeg.global.avcodec
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
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
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
        openAudioInput().use { stream ->
            val format = stream.format
            Preconditions.checkState(format.channels == 2, "Must be 2 channel format")
            System.err.println("Loaded audio as $format (possibly via FFmpeg resampling)")
            runBlocking(Dispatchers.Default) {
                val flow = processAudioStream(stream)
                launch {
                    gather(stream.format.isBigEndian, flow)
                }
                withContext(Dispatchers.IO) {
                    writeToSink(stream.format.sampleRate)
                }
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
        rawInputStream.use {
            if (raw) {
                val fmt = AudioFormat(sampleRate, 16, 2, true,
                    ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN
                )
                AudioInputStream(
                    rawInputStream,
                    fmt,
                    AudioSystem.NOT_SPECIFIED.toLong()
                ).use { audio ->
                    Channels.newOutputStream(sink.openChannel()).use { output ->
                        AudioSystem.write(audio, AudioFileFormat.Type.AU, output)
                    }
                }
            } else {
                FFmpegOutputStream(
                    avcodec.AV_CODEC_ID_MP3,
                    "mp3",
                    sampleRate.toInt(),
                    AvioCallbacks.forChannel(sink.openChannel())
                ).use { ffmpeg ->
                    ByteStreams.copy(rawInputStream, ffmpeg)
                }
            }

            Unit
        }
    }

    private data class ChannelContent<C>(
        val left: C,
        val right: C
    ) {
        inline fun <NC> map(mapping: (from: C) -> NC): ChannelContent<NC> {
            return ChannelContent(mapping(left), mapping(right))
        }
    }

    @Throws(IOException::class)
    private fun processAudioStream(stream: AudioInputStream) = flow {
        coroutineScope {
            val bufferedStream = BufferedInputStream(stream)
            val dis: DataInput = when {
                stream.format.isBigEndian -> DataInputStream(bufferedStream)
                else -> LittleEndianDataInputStream(bufferedStream)
            }
            val sampleAmount = (modifier.requestedTimeLength() * stream.format.frameRate / 1000).toInt()
            val left = ShortArray(sampleAmount)
            val right = ShortArray(sampleAmount)
            var reading = true
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
                emit(modifyAsync(ChannelContent(left, right), read, numBatches))
                numBatches++
            }
        }
    }
        .flowOn(Dispatchers.IO)
        .buffer()
        .map { it.await() }

    private fun CoroutineScope.modifyAsync(samples: ChannelContent<ShortArray>,
                                           read: Int,
                                           batchNum: Int): Deferred<ChannelContent<ShortArray>> {
        val buffer = samples.map { it.copyOf(read) }

        // ensure no references to samples in task
        return async(Dispatchers.Default) {
            buffer.map { modifier.modifySamples(it, batchNum) }
        }
    }

    private suspend fun gather(bigEndian: Boolean, flow: Flow<ChannelContent<ShortArray>>) {
        rawOutputStream.use {
            val dos: DataOutput = when {
                bigEndian -> DataOutputStream(rawOutputStream)
                else -> LittleEndianDataOutputStream(rawOutputStream)
            }
            flow.collect { (left, right) ->
                sendChannels(left, right, dos)
            }
        }
    }

    private suspend fun sendChannels(left: ShortArray, right: ShortArray, dos: DataOutput) {
        check(left.size == right.size) {
            "channel sizes should be equal, ${left.size} != ${right.size}"
        }
        withContext(Dispatchers.IO) {
            for ((l, r) in left.zip(right)) {
                dos.writeShort(l.toInt())
                dos.writeShort(r.toInt())
            }
        }
    }
}
