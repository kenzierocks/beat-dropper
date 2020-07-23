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
