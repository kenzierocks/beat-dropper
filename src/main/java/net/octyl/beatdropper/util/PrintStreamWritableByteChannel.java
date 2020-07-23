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

package net.octyl.beatdropper.util;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;

class PrintStreamWritableByteChannel extends AbstractInterruptibleChannel implements WritableByteChannel {
    private final PrintStream out;
    private static final int TRANSFER_SIZE = 8192;
    private byte[] buf = new byte[0];
    private final Object writeLock = new Object();

    PrintStreamWritableByteChannel(PrintStream out) {
        this.out = out;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (out.checkError()) {
            close();
        }
        if (!isOpen()) {
            throw new ClosedChannelException();
        }

        int len = src.remaining();
        int totalWritten = 0;
        synchronized (writeLock) {
            while (totalWritten < len) {
                int bytesToWrite = Math.min((len - totalWritten),
                    TRANSFER_SIZE);
                if (buf.length < bytesToWrite) {
                    buf = new byte[bytesToWrite];
                }
                src.get(buf, 0, bytesToWrite);
                try {
                    begin();
                    out.write(buf, 0, bytesToWrite);
                } finally {
                    end(bytesToWrite > 0);
                }
                totalWritten += bytesToWrite;
            }
            return totalWritten;
        }
    }

    @Override
    protected void implCloseChannel() {
        out.close();
    }
}
