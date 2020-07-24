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

import net.octyl.beatdropper.SampleSelection

abstract class SampleSelector : SampleModifier {
    override suspend fun modifySamples(samples: ShortArray, batchNumber: Int): ShortArray {
        return extractSelection(samples, selectSamples(samples.size, batchNumber))
    }

    private fun extractSelection(buffer: ShortArray, ranges: Collection<SampleSelection>): ShortArray {
        val sizeOfAllSelections = ranges.stream().mapToInt { obj: SampleSelection -> obj.length }.sum()
        val selectedBuffer = ShortArray(sizeOfAllSelections)
        var index = 0
        for (range in ranges) {
            // copy from buffer[lowBound:highBound] to
            // selectedBuffer[index:index+length]
            System.arraycopy(buffer, range.lowBound, selectedBuffer, index, range.length)
            index += range.length
        }
        return selectedBuffer
    }

    protected abstract suspend fun selectSamples(samplesLength: Int, batchNumber: Int): Collection<SampleSelection>
}