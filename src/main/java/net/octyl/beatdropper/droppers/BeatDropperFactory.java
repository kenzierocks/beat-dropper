package net.octyl.beatdropper.droppers;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

public interface BeatDropperFactory {

    String getId();

    OptionParser getParser();

    BeatDropper create(OptionSet options);

}
