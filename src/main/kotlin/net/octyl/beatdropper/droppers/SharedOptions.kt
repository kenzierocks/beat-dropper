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

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import joptsimple.ArgumentAcceptingOptionSpec
import joptsimple.OptionParser
import joptsimple.ValueConverter

object SharedOptions {
    fun bpm(parser: OptionParser): ArgumentAcceptingOptionSpec<Int> {
        return parser.acceptsAll(ImmutableList.of("bpm"), "BPM of the song.")
            .withRequiredArg()
            .withValuesConvertedBy(PositiveValueConverter("BPM"))
    }

    fun sampleSize(parser: OptionParser): ArgumentAcceptingOptionSpec<Int> {
        return parser.acceptsAll(ImmutableList.of("sample-size"), "Size of each sample to consider.")
            .withRequiredArg()
            .withValuesConvertedBy(PositiveValueConverter("Sample size"))
    }

    fun measureSize(parser: OptionParser): ArgumentAcceptingOptionSpec<Int> {
        return parser.acceptsAll(ImmutableList.of("measure-size"), "Size of a measure in beats.")
            .withRequiredArg()
            .withValuesConvertedBy(PositiveValueConverter("Measure size"))
    }

    fun percentage(parser: OptionParser, description: String): ArgumentAcceptingOptionSpec<Double> {
        return parser.acceptsAll(ImmutableList.of("percent", "percentage"), description)
            .withRequiredArg()
            .withValuesConvertedBy(PercentageValueConverter.INSTANCE)
    }

    fun seed(parser: OptionParser): ArgumentAcceptingOptionSpec<String> {
        return parser.accepts("seed", "Random seed. Hashcode will be taken.")
            .withRequiredArg()
    }

    fun pattern(parser: OptionParser, itemPlural: String): ArgumentAcceptingOptionSpec<String> {
        return parser.accepts("pattern", "Pattern of 1s and 0s for which $itemPlural to use.")
            .withRequiredArg()
    }

    private class PositiveValueConverter(private val desc: String) : ValueConverter<Int> {
        override fun convert(value: String): Int {
            return try {
                val intVal = value.toInt()
                Preconditions.checkArgument(1 <= intVal, "$desc must be in the range [1, infinity).")
                intVal
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("$desc is an integer.")
            }
        }

        override fun valueType(): Class<out Int> {
            return Int::class.javaPrimitiveType!!
        }

        override fun valuePattern(): String {
            return "[1, infinity)"
        }
    }

    private enum class PercentageValueConverter : ValueConverter<Double> {
        INSTANCE;

        override fun convert(value: String): Double {
            return try {
                val percentage = value.toDouble()
                require(percentage in 0.0..100.0) { "Percentage must be in the range [0, 100]." }
                percentage
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Percentage is a number.")
            }
        }

        override fun valueType(): Class<out Double> {
            return Double::class.javaPrimitiveType!!
        }

        override fun valuePattern(): String {
            return "[0, 100]"
        }
    }
}
