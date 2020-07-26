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

package net.octyl.beatdropper

import joptsimple.ArgumentAcceptingOptionSpec
import joptsimple.NonOptionArgumentSpec
import joptsimple.OptionParser
import joptsimple.OptionSet
import joptsimple.OptionSpec
import net.octyl.beatdropper.droppers.SampleModifierFactories
import net.octyl.beatdropper.util.ChannelProvider
import net.octyl.beatdropper.util.ReadableByteChannelProviderConverter
import net.octyl.beatdropper.util.WritableByteChannelProviderConverter
import org.bytedeco.ffmpeg.global.avutil
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.system.exitProcess

object BeatDrop {
    private val LOGGER = LoggerFactory.getLogger(BeatDrop::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        if (isOldJava()) {
            System.err.println("Error: JRE 14 is required")
            exitProcess(1)
        }
        if (args.size < 2 || args[1] in setOf("-h", "--help")) {
            // length must be >= 2
            // dropper & file
            printHelp()
            return
        }
        val dropperFactoryId = args[0]
        val factory = SampleModifierFactories.getById(dropperFactoryId)
        val parser = factory.parser
        val sourceOpt = addSourceOpt(parser)
        val sinkOpt = addSinkOpt(parser)
        val rawFlagOpt = addRawFlag(parser)
        val debugFlagOpt = addDebugFlag(parser)
        val options = parser.parse(*args.copyOfRange(1, args.size))
        if (helpOptionPresent(options)) {
            try {
                parser.printHelpOn(System.err)
            } catch (unexpected: IOException) {
                // no recovery for this...
            }
            return
        }
        val source = sourceOpt.value(options)
        val sink = sinkOpt.value(options)
        val raw = options.has(rawFlagOpt)
        if (options.has(debugFlagOpt)) {
            avutil.av_log_set_level(avutil.AV_LOG_DEBUG)
        }
        executeBeatDropping(source.identifier, SelectionProcessor(source, sink, options, factory, raw))
    }

    private fun isOldJava(): Boolean {
        return try {
            val currentVersion = System.getProperty("java.specification.version")
            Runtime.Version.parse(currentVersion) < Runtime.Version.parse("14")
        } catch (e: NoClassDefFoundError) {
            // if Runtime.Version doesn't exist, it's also old
            true
        }
    }

    private fun helpOptionPresent(options: OptionSet): Boolean {
        return options.specs().stream().anyMatch { obj: OptionSpec<*> -> obj.isForHelp }
    }

    private fun executeBeatDropping(sourceName: String, processor: SelectionProcessor) {
        try {
            processor.process()
        } catch (e: IOException) {
            LOGGER.error("Error reading/writing audio", e)
        } catch (e: UnsupportedAudioFileException) {
            System.err.println(sourceName + " is not a known audio file type: " + e.message)
            exitProcess(1)
        }
    }

    private fun addSourceOpt(parser: OptionParser): NonOptionArgumentSpec<ChannelProvider<out ReadableByteChannel>> {
        return parser.nonOptions("Input source.")
            .withValuesConvertedBy(ReadableByteChannelProviderConverter())
    }

    private fun addSinkOpt(parser: OptionParser): ArgumentAcceptingOptionSpec<ChannelProvider<out WritableByteChannel>> {
        return parser.acceptsAll(listOf("o", "output"), "Output sink.")
            .withRequiredArg()
            .withValuesConvertedBy(WritableByteChannelProviderConverter())
    }

    private fun addRawFlag(parser: OptionParser): OptionSpec<Void> {
        return parser.acceptsAll(listOf("r", "raw"), "Enable raw output")
    }

    private fun addDebugFlag(parser: OptionParser): OptionSpec<Void> {
        return parser.acceptsAll(listOf("debug"), "Enable debug output")
    }

    private fun printHelp() {
        val formattedDroppers = SampleModifierFactories.formatAvailableForCli()
        System.err.println(
            """
            usage: beat-dropper <dropper> [dropper options] <file>
            
            For dropper options, see beat-dropper [dropper] --help.
            
            <dropper> may be any one of the following:
            """.trimIndent()
                + "\n" + formattedDroppers
        )
    }
}
