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
import java.util.Random
import java.util.SortedSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Drops a specific percentage of the beats, randomly.
 */
class RandomBeatDropper private constructor(
        private val bpm: Int,
        private val percentage: Double,
        private val seed: String?
) : SampleSelector() {
    @AutoService(SampleModifierFactory::class)
    class Factory : FactoryBase("random") {
        private val bpm: ArgumentAcceptingOptionSpec<Int> = SharedOptions.bpm(parser)
        private val percentage: ArgumentAcceptingOptionSpec<Double> = SharedOptions.percentage(parser, "Percentage of the beats to drop.")
        private val seed: ArgumentAcceptingOptionSpec<String> = SharedOptions.seed(parser)
        override fun create(format: Format, options: OptionSet): SampleModifier {
            return RandomBeatDropper(
                    bpm.value(options),
                    percentage.value(options) / 100.0,
                    seed.value(options)
            )
        }
    }

    private val batchPasses: MutableMap<Int, Boolean> = ConcurrentHashMap()
    public override suspend fun selectSamples(samplesLength: Int, batchNumber: Int): SortedSet<SampleSelection> {
        val passes = batchPasses.computeIfAbsent(batchNumber) { k: Int ->
            val rng = Random(seed.hashCode().toLong())
            rng.doubles().skip(k.toLong()).findFirst().orElseThrow() < percentage
        }
        return sortedSetOf(SampleSelection(0, if (passes) samplesLength else 0))
    }

    override fun requestedTimeLength(): Long {
        return DropperUtils.requestedTimeForOneBeat(bpm)
    }

    override fun describeModification(): String {
        return "Random[bpm=$bpm,${percentage * 100}%,seed=$seed]"
    }
}
