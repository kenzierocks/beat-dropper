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

package net.octyl.beatdropper.droppers;

import static com.google.common.base.Preconditions.checkArgument;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import com.google.auto.service.AutoService;
import com.google.common.primitives.Doubles;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionSet;
import net.octyl.beatdropper.StandardWindows;
import net.octyl.beatdropper.Window;

/**
 * Doubles the time of selected beats in the pattern.
 *
 * @see PatternBeatDropper
 */
public class WaltizifierV1 implements SampleModifier {

    @AutoService(SampleModifierFactory.class)
    public static final class Factory extends FactoryBase {

        private final ArgumentAcceptingOptionSpec<Integer> bpm;
        private final ArgumentAcceptingOptionSpec<String> pattern;

        public Factory() {
            super("waltz-v1");
            this.bpm = SharedOptions.bpm(getParser());
            this.pattern = opt("pattern", "Pattern of 1s and 0s for which beats to speed up.");
        }

        @Override
        public SampleModifier create(OptionSet options) {
            return new WaltizifierV1(bpm.value(options), pattern.value(options));
        }

    }

    private final int bpm;
    private final String pattern;

    private WaltizifierV1(int bpm, String pattern) {
        this.bpm = bpm;
        this.pattern = pattern;
    }

    @Override
    public short[] modifySamples(short[] samples, int batchNumber) {
        // samples here represent one beat
        boolean speed = pattern.charAt(batchNumber % pattern.length()) == '1';

        if (speed) {
            return ola(samples);
        } else {
            return samples;
        }
    }

    private static final Window WINDOW_FUNC = StandardWindows.HANNING;
    private static final BigDecimal[] WINDOW;
    static {
        double[] win = new double[1024];
        Arrays.fill(win, 1);
        double[] win2 = WINDOW_FUNC.window(win);
        WINDOW = DoubleStream.of(win2).mapToObj(BigDecimal::valueOf).toArray(BigDecimal[]::new);
    }
    private static final int WIN_LEN = WINDOW.length;
    private static final int WIN_LEN_HALF = WIN_LEN / 2;
    private static final int TOLERANCE = 512;
    private static final double STRETCH_FACT = 0.5;
    private static final BigDecimal SMALL_MIN = new BigDecimal("0.0001");

    private static int getScaledIndex(int i, double sfac) {
        return (int) Math.ceil(i * sfac);
    }

    private short[] ola(short[] samples) {
        int[] inToOutIndex = IntStream.rangeClosed(0, samples.length)
                .map(sampI -> getScaledIndex(sampI, STRETCH_FACT))
                .toArray();
        int outputLength = inToOutIndex[samples.length];
        int[] synwinIndex = IntStream.range(0, outputLength)
                .filter(i -> i % WIN_LEN_HALF == 0)
                .map(i -> i + WIN_LEN_HALF)
                .toArray();
        // Find the indexes in the original array, accounting for stretch
        int[] anawinIndex = interp1(synwinIndex, STRETCH_FACT);

        // fix input samples for easy use in loop:
        double[] paddedSamples = padSamples(samples, anawinIndex[anawinIndex.length - 1] + WIN_LEN + TOLERANCE);
        BigDecimal[] bigSamples = Arrays.stream(paddedSamples)
                .mapToObj(BigDecimal::valueOf)
                .toArray(BigDecimal[]::new);

        List<Task> tasks = new ArrayList<>();

        int delta = 0;
        for (int i = 0; i < synwinIndex.length; i++) {
            int synStart = synwinIndex[i];
            int anaStart = anawinIndex[i] + delta + TOLERANCE;
            tasks.add(new Task(outputLength + 2 * WIN_LEN, synStart, anaStart, WINDOW[i], bigSamples));

            if (i < synwinIndex.length - 1) {
                double[] natProg = Arrays.copyOfRange(paddedSamples,
                        anaStart + WIN_LEN_HALF, anaStart + WIN_LEN_HALF + WIN_LEN);
                double[] nextAna = Arrays.copyOfRange(paddedSamples,
                        anawinIndex[i + 1] - TOLERANCE, anawinIndex[i + 1] + WIN_LEN + TOLERANCE);
                double[] cc = crossCorrelate(nextAna, natProg, WIN_LEN);
                int maxIndex = maxIndex(cc);
                delta = 1 - maxIndex;
            }
        }

        TaskResult result = tasks.parallelStream()
                .map(Task::processWindow)
                .reduce(TaskResult::add)
                .orElseThrow(() -> new IllegalStateException("No tasks"));
        BigDecimal[] output = result.output;
        BigDecimal[] olWins = result.olWins;

        // re-normalize by removing windows
        for (int i = 0; i < output.length; i++) {
            BigDecimal winVal = olWins[i];
            if (winVal.compareTo(SMALL_MIN) < 0) {
                winVal = BigDecimal.ONE;
            }
            output[i] = output[i].divide(winVal, RoundingMode.HALF_EVEN);
        }

        // unpad output
        output = Arrays.copyOfRange(output, WIN_LEN_HALF, WIN_LEN_HALF + outputLength);
        return d2sArray(output);
    }

    private static final class TaskResult {

        public final BigDecimal[] output;
        public final BigDecimal[] olWins;

        public TaskResult(int size) {
            output = new BigDecimal[size];
            Arrays.fill(output, BigDecimal.ZERO);
            olWins = new BigDecimal[size];
            Arrays.fill(olWins, BigDecimal.ZERO);
        }

        private TaskResult(BigDecimal[] output, BigDecimal[] olWins) {
            this.output = output;
            this.olWins = olWins;
        }

        public TaskResult add(TaskResult other) {
            BigDecimal[] nOut = output.clone();
            BigDecimal[] nOlWins = olWins.clone();
            for (int i = 0; i < nOut.length; i++) {
                nOut[i] = nOut[i].add(other.output[i]);
                nOlWins[i] = nOlWins[i].add(other.olWins[i]);
            }
            return new TaskResult(nOut, nOlWins);
        }
    }

    private static final class Task {

        private final int size;
        private final int synStart;
        private final int anaStart;
        private final BigDecimal windowValue;
        private final BigDecimal[] bigSamples;

        public Task(int size, int synStart, int anaStart, BigDecimal windowValue, BigDecimal[] bigSamples) {
            this.size = size;
            this.synStart = synStart;
            this.anaStart = anaStart;
            this.windowValue = windowValue;
            this.bigSamples = bigSamples;
        }

        public TaskResult processWindow() {
            TaskResult result = new TaskResult(size);
            for (int synPos = synStart, anaPos = anaStart; synPos < synStart + WIN_LEN; synPos++, anaPos++) {
                BigDecimal sample = bigSamples[anaPos];
                result.output[synPos] = result.output[synPos].add(sample.multiply(windowValue));
                result.olWins[synPos] = result.olWins[synPos].add(windowValue);
            }
            return result;
        }
    }

    private int maxIndex(double[] cc) {
        if (cc.length == 0) {
            throw new IllegalArgumentException("Must have at least one element.");
        }
        double max = cc[0];
        int maxIndex = 0;
        for (int i = 1; i < cc.length; i++) {
            double test = cc[i];
            if (test > max) {
                max = test;
                maxIndex = i;
            }
        }
        return maxIndex;
    }

    private double[] crossCorrelate(double[] u, double[] v, int winLen) {
        double[] reversedU = u.clone();
        Collections.reverse(Doubles.asList(reversedU));
        return conv(reversedU, v, winLen, winLen + 1);
    }

    /**
     * Implementation of MATLAB's conv(u, v). Adjusts for size to avoid a copy
     * later.
     */
    private double[] conv(double[] u, double[] v, int startChop, int endChop) {
        int m = u.length;
        int n = v.length;
        double[] result = new double[m + n - 1 - (startChop + endChop)];
        for (int k = startChop; k < result.length + startChop; k++) {
            int start = Math.max(0, k - n + 1);
            int end = Math.min(k, m - 1);
            int resIndex = k - startChop;
            for (int j = start; j <= end; j++) {
                result[resIndex] += u[j] * v[k - j];
            }
        }
        return result;
    }

    private short[] d2sArray(BigDecimal[] output) {
        short[] outShorts = new short[output.length];
        for (int i = 0; i < output.length; i++) {
            outShorts[i] = SHORT(output[i].doubleValue());
        }
        return outShorts;
    }

    private double[] padSamples(short[] samples, int length) {
        int padLeftAmount = WIN_LEN_HALF + TOLERANCE;
        double[] out = new double[length + padLeftAmount];
        for (int i = 0; i < samples.length; i++) {
            out[i + padLeftAmount] = DOUBLE(samples[i]);
        }
        return out;
    }

    private static final double DTS_FACTOR = Math.pow(2, Short.SIZE - 1);

    private static double DOUBLE(short s) {
        return s / DTS_FACTOR;
    }

    private static short SHORT(double d) {
        return (short) (d * DTS_FACTOR);
    }

    private static int[] interp1(int[] synwinIndex, double sfac) {
        int[] out = new int[synwinIndex.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (int) Math.floor(synwinIndex[i] / sfac);
        }
        return out;
    }

    @Override
    public long requestedTimeLength() {
        return SampleSelectionUtils.requestedTimeForOneBeat(bpm);
    }

    @Override
    public String describeModification() {
        return "WaltzV1[bpm=" + bpm + ",pattern=" + pattern + "]";
    }

}
