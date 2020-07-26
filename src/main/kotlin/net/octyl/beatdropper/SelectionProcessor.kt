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
import joptsimple.OptionSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.octyl.beatdropper.droppers.SampleModifier
import net.octyl.beatdropper.droppers.SampleModifierFactory
import net.octyl.beatdropper.util.AvioCallbacks
import net.octyl.beatdropper.util.ChannelProvider
import net.octyl.beatdropper.util.FFmpegInputStream
import net.octyl.beatdropper.util.FFmpegOutputStream
import net.octyl.beatdropper.util.FlowInputStream
import net.octyl.beatdropper.util.Format
import net.octyl.beatdropper.util.internalFormat
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import java.io.BufferedInputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.coroutines.ContinuationInterceptor

class SelectionProcessor(
    private val source: ChannelProvider<out ReadableByteChannel>,
    private val sink: ChannelProvider<out WritableByteChannel>?,
    private val modifierOptionSet: OptionSet,
    private val modifierFactory: SampleModifierFactory,
    private val raw: Boolean
) {

    private fun loadSink(modifier: SampleModifier): ChannelProvider<out WritableByteChannel> {
        return sink ?: {
            val sourceName = source.identifier
            val sinkTarget = when {
                sourceName.startsWith("file:") ->
                    renameFile(Paths.get(sourceName.replaceFirst("file:".toRegex(), "")), raw, modifier)
                else ->
                    Paths.get(renameFile(sourceName.replace('/', '_'), raw, modifier))
            }

            ChannelProvider.forPath(
                sinkTarget,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
            )
        }()
    }

    private fun renameFile(file: Path, raw: Boolean, modifier: SampleModifier): Path {
        val newFileName = renameFile(file.fileName.toString(), raw, modifier)
        return file.resolveSibling(newFileName)
    }

    private fun renameFile(fileName: String, raw: Boolean, modifier: SampleModifier): String {
        val ext = when {
            raw -> "flac"
            else -> "mp3"
        }
        return "${fileName.substringBefore('.')} [${modifier.describeModification()}].$ext"
    }

    @Throws(IOException::class, UnsupportedAudioFileException::class)
    fun process() {
        openAudioInput().use { stream ->
            Preconditions.checkState(stream.format.channels == 2, "Must be 2 channel format")
            System.err.println("Loaded audio as ${stream.format} (possibly via FFmpeg resampling)")
            runBlocking(Dispatchers.Default) {
                val format = internalFormat(stream.format.sampleRate.toInt())
                val modifier = modifierFactory.create(
                    // we pipe mono data to the modifiers
                    format.copy(channelLayout = avutil.AV_CH_LAYOUT_MONO),
                    modifierOptionSet
                )
                try {
                    val flow = processAudioStream(stream, modifier, format)
                    val byteFlow = gather(stream.format.isBigEndian, flow)
                    withContext(Dispatchers.IO) {
                        FlowInputStream(byteFlow).buffered().use {
                            writeToSink(it, modifier, format.sampleRate)
                        }
                    }
                } finally {
                    (modifier as? AutoCloseable)?.close()
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
    private fun writeToSink(inputStream: InputStream, modifier: SampleModifier, sampleRate: Int) {
        val sink = loadSink(modifier)
        if (raw) {
            FFmpegOutputStream(
                avcodec.AV_CODEC_ID_FLAC,
                "flac",
                sampleRate,
                AvioCallbacks.forChannel(sink.openChannel())
            ).use { ffmpeg ->
                ByteStreams.copy(inputStream, ffmpeg)
            }
        } else {
            FFmpegOutputStream(
                avcodec.AV_CODEC_ID_MP3,
                "mp3",
                sampleRate,
                AvioCallbacks.forChannel(sink.openChannel())
            ).use { ffmpeg ->
                ByteStreams.copy(inputStream, ffmpeg)
            }
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
    private fun processAudioStream(stream: AudioInputStream,
                                   modifier: SampleModifier,
                                   format: Format) = flow {
        coroutineScope {
            assert(coroutineContext[ContinuationInterceptor] == Dispatchers.IO) {
                "Not on IO threads!"
            }
            val bufferedStream = BufferedInputStream(stream)
            val dis: DataInput = when {
                stream.format.isBigEndian -> DataInputStream(bufferedStream)
                else -> LittleEndianDataInputStream(bufferedStream)
            }
            val sampleAmount = modifier.sampleArraySize(format)
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
                emit(modifyAsync(modifier, ChannelContent(left, right), read, numBatches))
                numBatches++
            }
        }
    }
        .flowOn(Dispatchers.IO)
        .buffer(Channel.UNLIMITED)
        .map { it.await() }

    private fun CoroutineScope.modifyAsync(modifier: SampleModifier,
                                           samples: ChannelContent<ShortArray>,
                                           read: Int,
                                           batchNum: Int): Deferred<ChannelContent<ShortArray>> {
        val buffer = samples.map { it.copyOf(read) }

        // ensure no references to samples in task
        return async(Dispatchers.Default) {
            buffer.map { modifier.modifySamples(it, batchNum) }
        }
    }

    private fun gather(bigEndian: Boolean, flow: Flow<ChannelContent<ShortArray>>): Flow<ByteBuffer> {
        val order = when {
            bigEndian -> ByteOrder.BIG_ENDIAN
            else -> ByteOrder.LITTLE_ENDIAN
        }
        return flow.map { (left, right) ->
            encodeChannels(order, left, right)
        }
    }

    private fun encodeChannels(order: ByteOrder, left: ShortArray, right: ShortArray): ByteBuffer {
        check(left.size == right.size) {
            "channel sizes should be equal, ${left.size} != ${right.size}"
        }
        val buffer = ByteBuffer.allocate((left.size + right.size) * Short.SIZE_BYTES).order(order)
        for ((l, r) in left.zip(right)) {
            buffer.putShort(l)
            buffer.putShort(r)
        }
        buffer.flip()
        return buffer
    }
}
