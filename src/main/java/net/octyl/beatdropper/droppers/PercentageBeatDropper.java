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

import java.util.SortedSet;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSortedSet;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionSet;
import net.octyl.beatdropper.SampleSelection;

/**
 * Drops a specific percentage of the end of a beat.
 */
public class PercentageBeatDropper implements BeatDropper {

    @AutoService(BeatDropperFactory.class)
    public static final class Factory extends FactoryBase {

        private final ArgumentAcceptingOptionSpec<Integer> bpm;
        private final ArgumentAcceptingOptionSpec<Double> percentage;

        public Factory() {
            super("percentage");
            this.bpm = SharedBeatDropOptions.bpm(getParser());
            this.percentage = SharedBeatDropOptions.percentage(getParser(), "Percentage of the beat to drop.");
        }

        @Override
        public BeatDropper create(OptionSet options) {
            return new PercentageBeatDropper(bpm.value(options), percentage.value(options) / 100.0);
        }

    }

    private final int bpm;
    private final double percentage;

    public PercentageBeatDropper(int bpm, double percentage) {
        this.bpm = bpm;
        this.percentage = percentage;
    }

    @Override
    public SortedSet<SampleSelection> selectSamples(int samplesLength) {
        // samples here represent one beat
        return ImmutableSortedSet.of(SampleSelection.make(0, (int) (percentage * samplesLength)));
    }

    @Override
    public long requestedTimeLength() {
        return BeatDropUtils.requestedTimeForOneBeat(bpm);
    }

    @Override
    public String describeModification() {
        return "Percentage[bpm=" + bpm + "," + (percentage * 100) + "%]";
    }

}
