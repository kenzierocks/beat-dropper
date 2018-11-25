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

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.Collection;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionSet;
import net.octyl.beatdropper.SampleSelection;

/**
 * Doubles the time of selected beats in the pattern.
 *
 * @see PatternBeatDropper
 */
public class WaltizifierV1 extends SampleSelector {

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
    private int counter = 0;

    private WaltizifierV1(int bpm, String pattern) {
        this.bpm = bpm;
        this.pattern = pattern;
    }

    @Override
    public Collection<SampleSelection> selectSamples(int samplesLength) {
        // samples here represent one beat
        boolean speed = pattern.charAt(counter % pattern.length()) == '1';
        counter++;

        if (speed) {
            return SampleSelectionUtils.doubleTime(0, samplesLength).collect(toImmutableList());
        } else {
            return ImmutableList.of(SampleSelection.make(0, samplesLength));
        }
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
