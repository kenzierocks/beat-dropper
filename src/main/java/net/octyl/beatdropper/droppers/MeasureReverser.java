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

import java.util.List;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionSet;
import net.octyl.beatdropper.SampleSelection;

/**
 * Reverses each measure.
 */
public class MeasureReverser extends SampleSelector {

    @AutoService(SampleModifierFactory.class)
    public static final class Factory extends FactoryBase {

        private final ArgumentAcceptingOptionSpec<Integer> bpm;
        private final ArgumentAcceptingOptionSpec<Integer> measureSize;

        public Factory() {
            super("reverse-measure");
            this.bpm = SharedOptions.bpm(getParser());
            this.measureSize = SharedOptions.measureSize(getParser());
        }

        @Override
        public SampleModifier create(OptionSet options) {
            return new MeasureReverser(bpm.value(options), measureSize.value(options));
        }

    }

    private final int bpm;
    private final int measureSize;

    private MeasureReverser(int bpm, int measureSize) {
        this.bpm = bpm;
        this.measureSize = measureSize;
    }

    @Override
    public List<SampleSelection> selectSamples(int samplesLength, int batchNumber) {
        // samples here represent one measure
        // get the beat selections
        ImmutableList<SampleSelection> byBeat = buildMeasure(samplesLength);
        return byBeat.reverse();
    }

    private ImmutableList<SampleSelection> buildMeasure(int samplesLength) {
        ImmutableList.Builder<SampleSelection> measure = ImmutableList.builderWithExpectedSize(measureSize);
        int beatSize = samplesLength / measureSize;
        for (int i = 0; i < samplesLength; i += beatSize) {
            measure.add(SampleSelection.make(i, i + beatSize));
        }
        return measure.build();
    }

    @Override
    public long requestedTimeLength() {
        return SampleSelectionUtils.requestedTimeForOneBeat(bpm) * measureSize;
    }

    @Override
    public String describeModification() {
        return "ReverseMeasure[bpm=" + bpm + ",msize=" + measureSize + "]";
    }

}
