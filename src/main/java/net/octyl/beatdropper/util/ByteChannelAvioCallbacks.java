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

import static org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_UNKNOWN;


import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;

import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int;
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int;
import org.bytedeco.ffmpeg.avformat.Write_packet_Pointer_BytePointer_int;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ByteChannelAvioCallbacks {

    private static final Logger LOGGER = LoggerFactory.getLogger(ByteChannelAvioCallbacks.class);

    static final class ReadCallback extends Read_packet_Pointer_BytePointer_int {

        private final ReadableByteChannel channel;

        ReadCallback(ReadableByteChannel channel) {
            this.channel = channel;
        }

        @Override
        public int call(Pointer opaque, BytePointer buf, int buf_size) {
            try {
                int read = channel.read(buf.limit(buf_size).asBuffer());
                if (read == -1) {
                    return AVERROR_EOF;
                }
                return read;
            } catch (Throwable t) {
                LOGGER.warn("Error reading from channel", t);
                return AVERROR_UNKNOWN;
            }
        }
    }

    static final class WriteCallback extends Write_packet_Pointer_BytePointer_int {

        private final WritableByteChannel channel;

        WriteCallback(WritableByteChannel channel) {
            this.channel = channel;
        }

        @Override
        public int call(Pointer opaque, BytePointer buf, int buf_size) {
            try {
                return channel.write(buf.limit(buf_size).asBuffer().asReadOnlyBuffer());
            } catch (Throwable t) {
                LOGGER.warn("Error writing to channel", t);
                return AVERROR_UNKNOWN;
            }
        }
    }

    static final class SeekCallback extends Seek_Pointer_long_int {

        private static final int SEEK_SET = 0;
        private static final int SEEK_CUR = 1;
        private static final int SEEK_END = 2;

        private final SeekableByteChannel channel;

        SeekCallback(SeekableByteChannel channel) {
            this.channel = channel;
        }

        @Override
        public long call(Pointer opaque, long offset, int whence) {
            try {
                switch (whence) {
                    case SEEK_SET -> channel.position(offset);
                    case SEEK_CUR -> channel.position(channel.position() + offset);
                    case SEEK_END -> channel.position(channel.size() + offset);
                    case AVSEEK_SIZE -> {
                        return channel.size();
                    }
                    default -> {
                        return AVERROR_UNKNOWN;
                    }
                }
                return 0;
            } catch (Throwable t) {
                LOGGER.warn("Error seeking in channel", t);
                return AVERROR_UNKNOWN;
            }
        }
    }

}
