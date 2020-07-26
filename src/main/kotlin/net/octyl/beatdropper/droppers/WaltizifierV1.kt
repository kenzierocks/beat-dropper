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

package net.octyl.beatdropper.droppers

import com.google.auto.service.AutoService
import joptsimple.ArgumentAcceptingOptionSpec
import joptsimple.OptionSet
import net.octyl.beatdropper.util.AutoCloser
import net.octyl.beatdropper.util.Format
import net.octyl.beatdropper.util.Stretcher
import net.octyl.beatdropper.util.checkAv
import net.octyl.beatdropper.util.setFrom
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable

/**
 * Doubles the time of selected beats in the pattern.
 *
 * @see PatternBeatDropper
 */
class WaltizifierV1 private constructor(
    private val format: Format,
    private val bpm: Int,
    private val pattern: String
) : SampleModifier {
    @AutoService(SampleModifierFactory::class)
    class Factory : FactoryBase("waltz-v1") {
        private val bpm: ArgumentAcceptingOptionSpec<Int> = SharedOptions.bpm(parser)
        private val pattern: ArgumentAcceptingOptionSpec<String> =
            opt("pattern", "Pattern of 1s and 0s for which beats to speed up.")

        override fun create(format: Format, options: OptionSet): SampleModifier {
            return WaltizifierV1(format, bpm.value(options)!!, pattern.value(options))
        }
    }

    override suspend fun modifySamples(samples: ShortArray, batchNumber: Int): ShortArray {
        // samples here represent one beat
        val speed = pattern[batchNumber % pattern.length] == '1'
        return if (speed) {
            speedify(samples, batchNumber)
        } else {
            samples
        }
    }

    // TODO For some reason this is inconsistent between both sides. What to do in that situation?
    private fun speedify(samples: ShortArray, batchNumber: Int): ShortArray {
        AutoCloser().use { closer ->
            val graph = closer.register(Stretcher(format, format, 2.0, samples.size))
            val frame = closer.register(av_frame_alloc() ?: error("Unable to allocate frame")) {
                av_frame_free(it)
            }
            frame.setFrom(format).nb_samples(samples.size)
            av_frame_get_buffer(frame, 0)

            checkAv(av_frame_make_writable(frame)) { "Unable to make frame writable: $it" }

            frame.data(0).limit((samples.size * Short.SIZE_BYTES).toLong())
                .asByteBuffer()
                .asShortBuffer()
                .put(samples)
            frame.pts((samples.size * batchNumber).toLong())

            val frames = (graph.pushFrame(frame) + graph.pushFinalFrame(frame.pts()))
                .map { outputFrame ->
                    val array = ShortArray(outputFrame.nb_samples())
                    outputFrame.data(0).limit((outputFrame.nb_samples() * Short.SIZE_BYTES).toLong())
                        .asByteBuffer()
                        .asShortBuffer()
                        .get(array)
                    array
                }
                .toList()
            val bigBuffer = ShortArray(samples.size / 2)
            var offset = 0
            for (resultFrame in frames) {
                val copyAmount = resultFrame.size.coerceAtMost(
                    bigBuffer.size - offset
                )
                if (copyAmount == 0) {
                    break
                }
                resultFrame.copyInto(bigBuffer, offset, endIndex = copyAmount)
                offset += copyAmount
            }
            return bigBuffer
        }
    }

    override fun requestedTimeLength(): Long {
        return DropperUtils.requestedTimeForOneBeat(bpm)
    }

    override fun describeModification(): String {
        return "WaltzV1[bpm=$bpm,pattern=$pattern]"
    }
}
