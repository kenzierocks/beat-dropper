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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.ValueConverter;

public class SharedOptions {

    private static final class PositieValueConverter implements ValueConverter<Integer> {

        private final String desc;

        public PositieValueConverter(String desc) {
            this.desc = desc;
        }

        @Override
        public Integer convert(String value) {
            try {
                int intVal = Integer.parseInt(value);
                checkArgument(1 <= intVal, desc + " must be in the range [1, infinity).");
                return intVal;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(desc + " is an integer.");
            }
        }

        @Override
        public Class<? extends Integer> valueType() {
            return int.class;
        }

        @Override
        public String valuePattern() {
            return "[1, infinity)";
        }
    }

    public static ArgumentAcceptingOptionSpec<Integer> bpm(OptionParser parser) {
        return parser.acceptsAll(ImmutableList.of("bpm"), "BPM of the song.")
                .withRequiredArg()
                .withValuesConvertedBy(new PositieValueConverter("BPM"));
    }

    public static ArgumentAcceptingOptionSpec<Integer> sampleSize(OptionParser parser) {
        return parser.acceptsAll(ImmutableList.of("sample-size"), "Size of each sample to consider.")
                .withRequiredArg()
                .withValuesConvertedBy(new PositieValueConverter("Sample size"));
    }

    private enum PercentageValueConverter implements ValueConverter<Double> {
        INSTANCE;

        @Override
        public Double convert(String value) {
            try {
                double percentage = Double.parseDouble(value);
                checkArgument(0 <= percentage && percentage <= 100,
                        "Percentage must be in the range [0, 100].");
                return percentage;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Percentage is a number.");
            }
        }

        @Override
        public Class<? extends Double> valueType() {
            return double.class;
        }

        @Override
        public String valuePattern() {
            return "[0, 100]";
        }
    }

    public static ArgumentAcceptingOptionSpec<Double> percentage(OptionParser parser, String description) {
        return parser.acceptsAll(ImmutableList.of("percent", "percentage"), description)
                .withRequiredArg()
                .withValuesConvertedBy(PercentageValueConverter.INSTANCE);
    }

    public static ArgumentAcceptingOptionSpec<String> seed(OptionParser parser) {
        return parser.accepts("seed", "Random seed. Hashcode will be taken.")
                .withRequiredArg();
    }

}
