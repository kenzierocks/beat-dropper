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
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.WritableByteChannel
import java.nio.channels.spi.AbstractInterruptibleChannel

internal class PrintStreamWritableByteChannel(private val out: PrintStream) : AbstractInterruptibleChannel(),
    WritableByteChannel {
    private var buf = ByteArray(0)
    private val writeLock = Any()

    @Throws(IOException::class)
    override fun write(src: ByteBuffer): Int {
        if (out.checkError()) {
            close()
        }
        if (!isOpen) {
            throw ClosedChannelException()
        }
        val len = src.remaining()
        var totalWritten = 0
        synchronized(writeLock) {
            while (totalWritten < len) {
                val bytesToWrite = (len - totalWritten).coerceAtMost(TRANSFER_SIZE)
                if (buf.size < bytesToWrite) {
                    buf = ByteArray(bytesToWrite)
                }
                src[buf, 0, bytesToWrite]
                try {
                    begin()
                    out.write(buf, 0, bytesToWrite)
                } finally {
                    end(bytesToWrite > 0)
                }
                totalWritten += bytesToWrite
            }
            return totalWritten
        }
    }

    override fun implCloseChannel() {
        out.close()
    }

    companion object {
        private const val TRANSFER_SIZE = 8192
    }
}
