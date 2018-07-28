package net.octyl.beatdropper.droppers;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.ValueConverter;

public class SharedBeatDropOptions {

    private enum BpmValueConverter implements ValueConverter<Integer> {
        INSTANCE;

        @Override
        public Integer convert(String value) {
            try {
                int bpm = Integer.parseInt(value);
                checkArgument(1 <= bpm, "BPM must be in the range [1, infinity).");
                return bpm;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("BPM is an integer.");
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
                .withValuesConvertedBy(BpmValueConverter.INSTANCE);
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

}
