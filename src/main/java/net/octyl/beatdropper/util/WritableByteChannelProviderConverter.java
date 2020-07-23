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

import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;

import joptsimple.ValueConverter;
import joptsimple.util.PathConverter;

public class WritableByteChannelProviderConverter implements ValueConverter<ChannelProvider<? extends WritableByteChannel>> {
    private static final ChannelProvider<WritableByteChannel> STANDARD_OUT = new ChannelProvider.Simple<>("pipe:1") {
        @Override
        public WritableByteChannel openChannel() {
            return new PrintStreamWritableByteChannel(System.out);
        }
    };
    private final PathConverter delegate = new PathConverter();

    @Override
    public ChannelProvider<? extends WritableByteChannel> convert(String value) {
        if (value.equals("-")) {
            return STANDARD_OUT;
        }
        Path path = delegate.convert(value);
        return ChannelProvider.forPath(path);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Class<? extends ChannelProvider<WritableByteChannel>> valueType() {
        return (Class<? extends ChannelProvider<WritableByteChannel>>) (Object) ChannelProvider.class;
    }

    @Override
    public String valuePattern() {
        return "A path or `-` for stdout";
    }
}
