package net.octyl.beatdropper.droppers;

import com.google.common.collect.ImmutableList;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;

public abstract class FactoryBase implements BeatDropperFactory {

    private final OptionParser parser = new OptionParser();
    private final String id;

    protected FactoryBase(String id) {
        this.id = id;
        parser.acceptsAll(ImmutableList.of("h", "help"), "Help for this dropper.")
                .forHelp();
    }

    protected final ArgumentAcceptingOptionSpec<String> opt(String option, String desc) {
        return parser.accepts(option, desc).withRequiredArg();
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final OptionParser getParser() {
        return parser;
    }

}
