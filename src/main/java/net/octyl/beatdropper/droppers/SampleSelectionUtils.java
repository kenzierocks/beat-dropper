/*
 * This file is part of beat-dropper, licensed under the MIT License (MIT).
 *
 * Copyright (c) Kenzie Togami <https://octyl.net>
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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Streams;

import net.octyl.beatdropper.SampleSelection;

public class SampleSelectionUtils {

    public static long requestedTimeForOneBeat(int bpm) {
        // have: beats per minute
        // want: one beat's worth of samples
        // want: time for one beat
        // want: millis per beat
        // -- get: minutes per beat
        // double minPerBeat = 1.0 / bpm;
        // -- get: mills per beat (fix multiplication for accuracy)
        long millisPerBeat = (long) ((TimeUnit.MINUTES.toMillis(1) * 1.0) / bpm);
        return millisPerBeat;
    }

    /**
     * Generates selections to make `[start, end)` twice as fast.
     *
     * @param start
     *            the start of the range
     * @param end
     *            the end of the range
     * @return the selections to make
     */
    public static Stream<SampleSelection> doubleTime(int start, int end) {
        return Streams.stream(new AbstractIterator<SampleSelection>() {

            private AtomicInteger index = new AtomicInteger(start);

            @Override
            protected SampleSelection computeNext() {
                int i = index.getAndAdd(2);
                if (i >= end) {
                    return endOfData();
                }
                return SampleSelection.make(i, i + 1);
            }
        });
    }

}
