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

package net.octyl.beatdropper;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import com.google.common.io.ByteSink;
import com.google.common.io.MoreFiles;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.NonOptionArgumentSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.octyl.beatdropper.droppers.SampleModifier;
import net.octyl.beatdropper.droppers.SampleModifierFactories;
import net.octyl.beatdropper.droppers.SampleModifierFactory;
import net.octyl.beatdropper.util.ByteSinkConverter;
import net.octyl.beatdropper.util.ByteSourceConverter;
import net.octyl.beatdropper.util.NamedByteSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        SampleModifierFactory factory = SampleModifierFactories.getById(dropperFactoryId);

        OptionParser parser = factory.getParser();
        var sourceOpt = addSourceOpt(parser);
        var sinkOpt = addSinkOpt(parser);
        var rawFlagOpt = addRawFlag(parser);
        OptionSet options = parser.parse(Arrays.copyOfRange(args, 1, args.length));

        if (helpOptionPresent(options)) {
            try {
                parser.printHelpOn(System.err);
            } catch (IOException unexpected) {
                // no recovery for this...
            }
            return;
        }

        var source = sourceOpt.value(options);
        var sink = sinkOpt.value(options);
        var raw = options.has(rawFlagOpt);
        SampleModifier selector = factory.create(options);

        if (sink == null) {
            String sourceName = source.getName();
            Path sinkTarget =
                sourceName.startsWith("file:")
                    ? renameFile(Paths.get(sourceName.replaceFirst("file:", "")), selector)
                    : Paths.get(renameFile(sourceName.replace('/', '_'), selector));
            sink = MoreFiles.asByteSink(sinkTarget);
        }

        executeBeatDropping(source.getName(), new SelectionProcessor(source.getSource(), sink, selector, raw));
    }

    private static boolean helpOptionPresent(OptionSet options) {
        return options.specs().stream().anyMatch(OptionSpec::isForHelp);
    }

    private static void executeBeatDropping(String sourceName, SelectionProcessor processor) {
        try {
            processor.process();
        } catch (IOException e) {
            LOGGER.error("Error reading/writing audio", e);
        } catch (UnsupportedAudioFileException e) {
            System.err.println(sourceName + " is not a known audio file type.");
            System.exit(1);
        }
    }

    private static NonOptionArgumentSpec<NamedByteSource> addSourceOpt(OptionParser parser) {
        return parser.nonOptions("Input source.")
            .withValuesConvertedBy(new ByteSourceConverter());
    }

    private static ArgumentAcceptingOptionSpec<ByteSink> addSinkOpt(OptionParser parser) {
        return parser.acceptsAll(List.of("o", "output"), "Output sink.")
            .withRequiredArg()
            .withValuesConvertedBy(new ByteSinkConverter());
    }

    private static OptionSpec<Void> addRawFlag(OptionParser parser) {
        return parser.acceptsAll(List.of("r", "raw"), "Enable raw output");
    }

    private static Path renameFile(Path file, SampleModifier modifier) {
        String newFileName = renameFile(file.getFileName().toString(), modifier);
        return file.resolveSibling(newFileName);
    }

    private static String renameFile(String fileName, SampleModifier modifier) {
        String modStr = " [" + modifier.describeModification() + "]";
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1) {
            return fileName + modStr;
        }
        return fileName.substring(0, lastDot) + modStr + fileName.substring(lastDot);
    }

    private static void printHelp() {
        String formattedDroppers = SampleModifierFactories.formatAvailableForCli();
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
