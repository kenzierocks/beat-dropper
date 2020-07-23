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
import java.util.SortedSet

/**
 * Drops a specific percentage of the end of a beat.
 */
class PercentageBeatDropper private constructor(private val bpm: Int, private val percentage: Double) :
    SampleSelector() {
    @AutoService(SampleModifierFactory::class)
    class Factory : FactoryBase("percentage") {
        private val bpm: ArgumentAcceptingOptionSpec<Int> = SharedOptions.bpm(parser)
        private val percentage: ArgumentAcceptingOptionSpec<Double> =
            SharedOptions.percentage(parser, "Percentage of the beat to drop.")

        override fun create(options: OptionSet): SampleModifier {
            return PercentageBeatDropper(bpm.value(options), percentage.value(options) / 100.0)
        }
    }

    public override suspend fun selectSamples(samplesLength: Int, batchNumber: Int): SortedSet<SampleSelection> {
        // samples here represent one beat
        return sortedSetOf(SampleSelection(0, (percentage * samplesLength).toInt()))
    }

    override fun requestedTimeLength(): Long {
        return DropperUtils.requestedTimeForOneBeat(bpm)
    }

    override fun describeModification(): String {
        return "Percentage[bpm=$bpm,${percentage * 100}%]"
    }
}
