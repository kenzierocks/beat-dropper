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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.octyl.beatdropper.StandardWindows
import net.octyl.beatdropper.Window
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayList
import java.util.stream.IntStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Doubles the time of selected beats in the pattern.
 *
 * @see PatternBeatDropper
 */
class WaltizifierV1 private constructor(private val bpm: Int, private val pattern: String) : SampleModifier {
    @AutoService(SampleModifierFactory::class)
    class Factory : FactoryBase("waltz-v1") {
        private val bpm: ArgumentAcceptingOptionSpec<Int> = SharedOptions.bpm(parser)
        private val pattern: ArgumentAcceptingOptionSpec<String> =
            opt("pattern", "Pattern of 1s and 0s for which beats to speed up.")

        override fun create(options: OptionSet): SampleModifier {
            return WaltizifierV1(bpm.value(options)!!, pattern.value(options))
        }
    }

    override suspend fun modifySamples(samples: ShortArray, batchNumber: Int): ShortArray {
        // samples here represent one beat
        val speed = pattern[batchNumber % pattern.length] == '1'
        return if (speed) {
            ola(samples)
        } else {
            samples
        }
    }

    companion object {
        private val WINDOW_FUNC: Window = StandardWindows.HANNING
        private val WINDOW = WINDOW_FUNC.window(WINDOW_FUNC.window(DoubleArray(1024) { 1.0 }))
            .map { BigDecimal.valueOf(it) }
            .toTypedArray()
        private val WIN_LEN = WINDOW.size
        private val WIN_LEN_HALF = WIN_LEN / 2
        private const val TOLERANCE = 512
        private const val STRETCH_FACT = 0.5
        private val SMALL_MIN = BigDecimal("0.0001")
        private fun getScaledIndex(i: Int, sfac: Double): Int {
            return ceil(i * sfac).toInt()
        }

        private val DTS_FACTOR = 2.0.pow(java.lang.Short.SIZE - 1.toDouble())
        private fun convertS2D(s: Short): Double {
            return s / DTS_FACTOR
        }

        private fun convertD2S(d: Double): Short {
            return (d * DTS_FACTOR).toInt().toShort()
        }

        private fun interp1(synwinIndex: IntArray, sfac: Double): IntArray {
            val out = IntArray(synwinIndex.size)
            for (i in out.indices) {
                out[i] = floor(synwinIndex[i] / sfac).toInt()
            }
            return out
        }
    }

    private suspend fun ola(samples: ShortArray): ShortArray {
        val inToOutIndex = IntStream.rangeClosed(0, samples.size)
            .map { sampI: Int -> getScaledIndex(sampI, STRETCH_FACT) }
            .toArray()
        val outputLength = inToOutIndex[samples.size]
        val synwinIndex = (0 until outputLength)
            .asSequence()
            .filter { i: Int -> i % WIN_LEN_HALF == 0 }
            .map { i: Int -> i + WIN_LEN_HALF }
            .toList()
            .toIntArray()
        // Find the indexes in the original array, accounting for stretch
        val anawinIndex = interp1(synwinIndex, STRETCH_FACT)

        val result = coroutineScope {
            // fix input samples for easy use in loop:
            val paddedSamples = padSamples(samples, anawinIndex[anawinIndex.size - 1] + WIN_LEN + TOLERANCE)
            val bigSamples = paddedSamples
                .map { BigDecimal.valueOf(it) }
                .toTypedArray()
            val tasks = ArrayList<Deferred<TaskResult>>()
            var delta = 0
            for (i in synwinIndex.indices) {
                val synStart = synwinIndex[i]
                val anaStart = anawinIndex[i] + delta + TOLERANCE
                tasks.add(async {
                    processWindow(outputLength + 2 * WIN_LEN, synStart, anaStart, WINDOW[i], bigSamples)
                })
                if (i < synwinIndex.size - 1) {
                    val natProg = paddedSamples.copyOfRange(
                        anaStart + WIN_LEN_HALF,
                        anaStart + WIN_LEN_HALF + WIN_LEN
                    )
                    val nextAna = paddedSamples.copyOfRange(
                        anawinIndex[i + 1] - TOLERANCE,
                        anawinIndex[i + 1] + WIN_LEN + TOLERANCE
                    )
                    val cc = crossCorrelate(nextAna, natProg, WIN_LEN)
                    val maxIndex = maxIndex(cc)
                    delta = 1 - maxIndex
                }
            }
            tasks.awaitAll().reduce { acc, taskResult -> acc.add(taskResult) }
        }
        var output = result.output
        val olWins = result.olWins

        // re-normalize by removing windows
        for (i in output.indices) {
            var winVal = olWins[i]
            if (winVal < SMALL_MIN) {
                winVal = BigDecimal.ONE
            }
            output[i] = output[i].divide(winVal, RoundingMode.HALF_EVEN)
        }

        // unpad output
        output = output.copyOfRange(WIN_LEN_HALF, WIN_LEN_HALF + outputLength)
        return d2sArray(output)
    }

    private class TaskResult private constructor(
        val output: Array<BigDecimal>,
        val olWins: Array<BigDecimal>
    ) {

        constructor(size: Int) : this(
            output = Array(size) { BigDecimal.ZERO },
            olWins = Array(size) { BigDecimal.ZERO }
        )

        fun add(other: TaskResult): TaskResult {
            val nOut = output.copyOf()
            val nOlWins = olWins.copyOf()
            for (i in nOut.indices) {
                nOut[i] = nOut[i].add(other.output[i])
                nOlWins[i] = nOlWins[i].add(other.olWins[i])
            }
            return TaskResult(nOut, nOlWins)
        }
    }

    private fun processWindow(
        size: Int,
        synStart: Int,
        anaStart: Int,
        windowValue: BigDecimal,
        bigSamples: Array<BigDecimal>
    ): TaskResult {
        val result = TaskResult(size)
        var synPos = synStart
        var anaPos = anaStart
        while (synPos < synStart + WIN_LEN) {
            val sample = bigSamples[anaPos]
            result.output[synPos] = result.output[synPos].add(sample.multiply(windowValue))
            result.olWins[synPos] = result.olWins[synPos].add(windowValue)
            synPos++
            anaPos++
        }
        return result
    }

    private fun maxIndex(cc: DoubleArray): Int {
        require(cc.isNotEmpty()) { "Must have at least one element." }
        var max = cc[0]
        var maxIndex = 0
        for (i in 1 until cc.size) {
            val test = cc[i]
            if (test > max) {
                max = test
                maxIndex = i
            }
        }
        return maxIndex
    }

    private fun crossCorrelate(u: DoubleArray, v: DoubleArray, winLen: Int): DoubleArray {
        return conv(u.reversedArray(), v, winLen, winLen + 1)
    }

    /**
     * Implementation of MATLAB's conv(u, v). Adjusts for size to avoid a copy
     * later.
     */
    private fun conv(u: DoubleArray, v: DoubleArray, startChop: Int, endChop: Int): DoubleArray {
        val m = u.size
        val n = v.size
        val result = DoubleArray(m + n - 1 - (startChop + endChop))
        for (k in startChop until result.size + startChop) {
            val start = max(k - n + 1, 0)
            val end = min(k, m - 1)
            val resIndex = k - startChop
            for (j in start..end) {
                result[resIndex] += u[j] * v[k - j]
            }
        }
        return result
    }

    private fun d2sArray(output: Array<BigDecimal>): ShortArray {
        val outShorts = ShortArray(output.size)
        for (i in output.indices) {
            outShorts[i] = convertD2S(output[i].toDouble())
        }
        return outShorts
    }

    private fun padSamples(samples: ShortArray, length: Int): DoubleArray {
        val padLeftAmount = WIN_LEN_HALF + TOLERANCE
        val out = DoubleArray(length + padLeftAmount)
        for (i in samples.indices) {
            out[i + padLeftAmount] = convertS2D(samples[i])
        }
        return out
    }

    override fun requestedTimeLength(): Long {
        return DropperUtils.requestedTimeForOneBeat(bpm)
    }

    override fun describeModification(): String {
        return "WaltzV1[bpm=$bpm,pattern=$pattern]"
    }
}
