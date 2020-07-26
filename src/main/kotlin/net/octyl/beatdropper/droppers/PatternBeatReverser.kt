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
import net.octyl.beatdropper.util.ArrayUtil
import net.octyl.beatdropper.util.Format

/**
 * Reverses beats in a pattern.
 */
class PatternBeatReverser private constructor(private val bpm: Int, private val pattern: String?) : SampleModifier {
    @AutoService(SampleModifierFactory::class)
    class Factory : FactoryBase("pattern-reverse-beats") {
        private val bpm: ArgumentAcceptingOptionSpec<Int> = SharedOptions.bpm(parser)
        private val pattern: ArgumentAcceptingOptionSpec<String> = SharedOptions.pattern(parser, "beats")

        override fun create(format: Format, options: OptionSet): SampleModifier {
            return PatternBeatReverser(bpm.value(options), pattern.value(options))
        }
    }

    override suspend fun modifySamples(samples: ShortArray, batchNumber: Int): ShortArray {
        val reverse = pattern!![batchNumber % pattern.length] == '1'
        var out: ShortArray = samples
        if (reverse) {
            out = ArrayUtil.reverse(out.clone())
        }
        return out
    }

    override fun requestedTimeLength(): Long {
        return DropperUtils.requestedTimeForOneBeat(bpm)
    }

    override fun describeModification(): String {
        return "PatternReverseBeat[bpm=$bpm,pattern=$pattern]"
    }
}
