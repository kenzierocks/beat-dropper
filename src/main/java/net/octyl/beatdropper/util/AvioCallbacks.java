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

import static org.bytedeco.ffmpeg.global.avformat.avio_alloc_context;
import static org.bytedeco.ffmpeg.global.avutil.av_malloc;


import java.nio.channels.Channel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;

import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int;
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int;
import org.bytedeco.ffmpeg.avformat.Write_packet_Pointer_BytePointer_int;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.checkerframework.checker.nullness.qual.Nullable;

public class AvioCallbacks implements AutoCloseable {

    public static AvioCallbacks forChannel(Channel channel) {
        return new AvioCallbacks(
            channel instanceof ReadableByteChannel
                ? new ByteChannelAvioCallbacks.ReadCallback((ReadableByteChannel) channel)
                : null,
            channel instanceof WritableByteChannel
                ? new ByteChannelAvioCallbacks.WriteCallback((WritableByteChannel) channel)
                : null,
            channel instanceof SeekableByteChannel
                ? new ByteChannelAvioCallbacks.SeekCallback((SeekableByteChannel) channel)
                : null,
            channel
        );
    }

    private final AutoCloser closer = new AutoCloser();
    private final @Nullable Read_packet_Pointer_BytePointer_int readCallback;
    private final @Nullable Write_packet_Pointer_BytePointer_int writeCallback;
    private final @Nullable Seek_Pointer_long_int seekCallback;

    /**
     * Stores a set of AVIO callbacks. This class will {@linkplain Pointer#retainReference() retain them}
     * until closed.
     *
     * @param resource the resource that is associated with the callbacks, and should be closed after them
     */
    public AvioCallbacks(@Nullable Read_packet_Pointer_BytePointer_int readCallback,
                         @Nullable Write_packet_Pointer_BytePointer_int writeCallback,
                         @Nullable Seek_Pointer_long_int seekCallback,
                         @Nullable AutoCloseable resource) {
        this.readCallback = readCallback == null ? null : closer.register(readCallback.retainReference());
        this.writeCallback = writeCallback == null ? null : closer.register(writeCallback.retainReference());
        this.seekCallback = seekCallback == null ? null : closer.register(seekCallback.retainReference());
        closer.register(resource);
    }

    public @Nullable Read_packet_Pointer_BytePointer_int getReadCallback() {
        return readCallback;
    }

    public @Nullable Write_packet_Pointer_BytePointer_int getWriteCallback() {
        return writeCallback;
    }

    public @Nullable Seek_Pointer_long_int getSeekCallback() {
        return seekCallback;
    }

    /**
     * Allocate a context with the callbacks in this object.
     *
     * <p>
     * Will <em>not</em> free the context automatically, that is up to you.
     * </p>
     *
     * @return AVIO context if allocated, {@code null} if failed
     */
    public @Nullable AVIOContext allocateContext(int bufferSize, boolean writable) {
        return avio_alloc_context(new BytePointer(av_malloc(bufferSize)), bufferSize, writable ? 1 : 0, null,
            getReadCallback(), getWriteCallback(), getSeekCallback());
    }

    @Override
    public void close() throws Exception {
        closer.close();
    }
}
