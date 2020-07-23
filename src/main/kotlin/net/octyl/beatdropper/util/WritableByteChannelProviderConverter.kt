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

package net.octyl.beatdropper.util

import joptsimple.ValueConverter
import joptsimple.util.PathConverter
import java.nio.channels.WritableByteChannel
import java.nio.file.StandardOpenOption

class WritableByteChannelProviderConverter : ValueConverter<ChannelProvider<out WritableByteChannel>> {

    companion object {
        private val STANDARD_OUT: ChannelProvider<WritableByteChannel> =
            object : ChannelProvider.Simple<WritableByteChannel>("pipe:1") {
                override fun openChannel(): WritableByteChannel {
                    return PrintStreamWritableByteChannel(System.out)
                }
            }
    }

    private val delegate = PathConverter()

    override fun convert(value: String): ChannelProvider<out WritableByteChannel> {
        if (value == "-") {
            return STANDARD_OUT
        }
        val path = delegate.convert(value)
        return ChannelProvider.forPath(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun valueType() = ChannelProvider::class.java as Class<out ChannelProvider<WritableByteChannel>>

    override fun valuePattern() = "A path or `-` for stdout"
}
