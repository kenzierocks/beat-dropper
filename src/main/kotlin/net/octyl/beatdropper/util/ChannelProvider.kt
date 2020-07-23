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

import java.io.IOException
import java.nio.channels.Channel
import java.nio.channels.FileChannel
import java.nio.file.OpenOption
import java.nio.file.Path

/**
 * Provider of a [Channel].
 */
interface ChannelProvider<C : Channel?> {
    abstract class Simple<C : Channel?> protected constructor(override val identifier: String) : ChannelProvider<C>

    /**
     * An easy way to identify the source of the byte channel.
     *
     * @return a string representing the source of the byte channel
     */
    val identifier: String

    @Throws(IOException::class)
    fun openChannel(): C

    companion object {
        fun forPath(path: Path, vararg options: OpenOption?): ChannelProvider<FileChannel> {
            return object : Simple<FileChannel>("file:" + path.toAbsolutePath().toString()) {
                @Throws(IOException::class)
                override fun openChannel(): FileChannel {
                    return FileChannel.open(path, *options)
                }
            }
        }
    }
}
