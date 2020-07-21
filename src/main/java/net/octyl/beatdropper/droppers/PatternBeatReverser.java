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

import com.google.auto.service.AutoService;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionSet;
import net.octyl.beatdropper.util.ArrayUtil;

/**
 * Reverses beats in a pattern.
 */
public class PatternBeatReverser implements SampleModifier {

    @AutoService(SampleModifierFactory.class)
    public static final class Factory extends FactoryBase {

        private final ArgumentAcceptingOptionSpec<Integer> bpm;
        private final ArgumentAcceptingOptionSpec<String> pattern;

        public Factory() {
            super("pattern-reverse-beats");
            this.bpm = SharedOptions.bpm(getParser());
            this.pattern = SharedOptions.pattern(getParser(), "beats");
        }

        @Override
        public SampleModifier create(OptionSet options) {
            return new PatternBeatReverser(bpm.value(options), pattern.value(options));
        }
    }

    private final int bpm;
    private final String pattern;

    private PatternBeatReverser(int bpm, String pattern) {
        this.bpm = bpm;
        this.pattern = pattern;
    }

    @Override
    public short[] modifySamples(short[] samples, int batchNumber) {
        boolean reverse = pattern.charAt(batchNumber % pattern.length()) == '1';
        short[] out = samples;
        if (reverse) {
            out = ArrayUtil.reverse(out.clone());
        }
        return out;
    }

    @Override
    public long requestedTimeLength() {
        return SampleSelectionUtils.requestedTimeForOneBeat(bpm);
    }

    @Override
    public String describeModification() {
        return "PatternReverseBeat[bpm=" + bpm + ",pattern=" + pattern + "]";
    }

}
