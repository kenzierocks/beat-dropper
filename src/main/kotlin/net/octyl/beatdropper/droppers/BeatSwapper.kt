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
import net.octyl.beatdropper.SampleSelection
import net.octyl.beatdropper.util.Format

/**
 * Swaps beats in a measure.
 */
class BeatSwapper private constructor(
    private val bpm: Int,
    private val measureSize: Int,
    private val pattern: String
) : SampleSelector() {
    @AutoService(SampleModifierFactory::class)
    class Factory : FactoryBase("swapper") {
        private val bpm: ArgumentAcceptingOptionSpec<Int> = SharedOptions.bpm(parser)
        private val measureSize: ArgumentAcceptingOptionSpec<Int> = SharedOptions.measureSize(parser)
        private val pattern: ArgumentAcceptingOptionSpec<String> =
            opt("pattern", "Pattern of beats to output, e.g. `1:4:3:2`.")

        override fun create(format: Format, options: OptionSet): SampleModifier {
            return BeatSwapper(bpm.value(options), measureSize.value(options), pattern.value(options))
        }
    }

    companion object {
        private fun patternToIndexes(pattern: String): IntArray {
            return try {
                pattern.split(':')
                    .map { it.toInt() - 1 }
                    .toIntArray()
            } catch (e: NumberFormatException) {
                error("Invalid pattern `$pattern`")
            }
        }
    }

    private val patternIndex: IntArray

    init {
        val indexes = patternToIndexes(pattern)
        require(indexes.none { it >= measureSize }) { "Invalid pattern `$pattern`" }
        patternIndex = indexes
    }

    public override suspend fun selectSamples(samplesLength: Int, batchNumber: Int): List<SampleSelection> {
        // samples here represent one measure
        // get the beat selections
        val byBeat = DropperUtils.buildMeasure(measureSize, samplesLength)
        // pick selections by pattern
        return patternIndex.map { byBeat[it] }
    }

    override fun requestedTimeLength(): Long {
        return DropperUtils.requestedTimeForOneBeat(bpm) * measureSize
    }

    override fun describeModification(): String {
        return "Swap[bpm=$bpm,msize=$measureSize,pattern=$pattern]"
    }

}
