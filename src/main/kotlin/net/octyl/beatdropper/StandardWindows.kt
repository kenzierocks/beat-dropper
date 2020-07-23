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

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos

enum class StandardWindows : Window {
    HANNING {
        override fun apply(i: Int, nn: Int): Double {
            val result = cache(i, nn)
            return result
                ?: cache(i, nn, 0.5 * (1.0 - cos(2.0 * Math.PI * i.toDouble() / (nn - 1).toDouble())))
        }
    },
    HAMMING {
        override fun apply(i: Int, nn: Int): Double {
            val result = cache(i, nn)
            return result
                ?: cache(i, nn, 0.54 - 0.46 * cos(2.0 * Math.PI * i.toDouble() / (nn - 1).toDouble()))
        }
    };

    private val cache: MutableMap<Long, Double> = ConcurrentHashMap()
    protected fun cache(i: Int, nn: Int): Double? {
        return cache[key(i, nn)]
    }

    protected fun cache(i: Int, nn: Int, result: Double): Double {
        cache[key(i, nn)] = result
        return result
    }

    private fun key(i: Int, nn: Int): Long {
        return (i or (nn.toLong() shl Integer.SIZE).toInt()).toLong()
    }
}
