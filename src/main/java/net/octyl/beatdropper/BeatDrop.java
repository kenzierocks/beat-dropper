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
package net.octyl.beatdropper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import javax.sound.sampled.UnsupportedAudioFileException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import joptsimple.NonOptionArgumentSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;
import net.octyl.beatdropper.droppers.SampleSelector;
import net.octyl.beatdropper.droppers.SampleSelectorFactories;
import net.octyl.beatdropper.droppers.SampleSelectorFactory;

public class BeatDrop {

    private static final Logger LOGGER = LoggerFactory.getLogger(BeatDrop.class);

    public static void main(String[] args) {
        if (args.length < 2 || findString(Arrays.copyOf(args, 1), "-h", "--help")) {
            // length must be >= 2
            // dropper & file
            printHelp();
            return;
        }

        String dropperFactoryId = args[0];

        SampleSelectorFactory factory = SampleSelectorFactories.getById(dropperFactoryId);

        OptionParser parser = factory.getParser();
        NonOptionArgumentSpec<Path> sourceOpt = addSourceOpt(parser);
        OptionSet options = parser.parse(Arrays.copyOfRange(args, 1, args.length));

        if (helpOptionPresent(options)) {
            try {
                parser.printHelpOn(System.err);
            } catch (IOException unexpected) {
                // no recovery for this...
            }
            return;
        }

        Path source = sourceOpt.value(options);
        SampleSelector selector = factory.create(options);

        executeBeatDropping(source, selector);
    }

    private static boolean helpOptionPresent(OptionSet options) {
        return options.specs().stream().anyMatch(OptionSpec::isForHelp);
    }

    private static void executeBeatDropping(Path source, SampleSelector selector) {
        SelectionProcessor processor = new SelectionProcessor(source, selector);
        try {
            processor.process();
        } catch (IOException e) {
            LOGGER.error("Error reading/writing audio", e);
        } catch (UnsupportedAudioFileException e) {
            System.err.println(source + " is not a known audio file type.");
            System.exit(1);
        }
    }

    private static NonOptionArgumentSpec<Path> addSourceOpt(OptionParser parser) {
        return parser.nonOptions("Input file.")
                .withValuesConvertedBy(new PathConverter(PathProperties.READABLE));
    }

    private static void printHelp() {
        String formattedDroppers = SampleSelectorFactories.formatAvailableForCli();
        System.err.println(
                "usage: beat-dropper <dropper> [dropper options] <file>\n\n"
                        + "For dropper options, see beat-dropper [dropper] --help.\n\n"
                        + "<dropper> may be any one of the following:\n" + formattedDroppers);
    }

    private static boolean findString(String[] args, String... targets) {
        for (String a : args) {
            for (String target : targets) {
                if (a.equalsIgnoreCase(target)) {
                    return true;
                }
            }
        }
        return false;
    }

}
