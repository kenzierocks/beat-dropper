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

import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;

import joptsimple.ValueConverter;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;

public class ReadableByteChannelProviderConverter implements ValueConverter<ChannelProvider<? extends ReadableByteChannel>> {

    private static final ChannelProvider<ReadableByteChannel> STANDARD_IN = new ChannelProvider.Simple<>("pipe:0") {
        @Override
        public ReadableByteChannel openChannel() {
            return Channels.newChannel(System.in);
        }
    };
    private final PathConverter delegate = new PathConverter(PathProperties.READABLE);

    @Override
    public ChannelProvider<? extends ReadableByteChannel> convert(String value) {
        if (value.equals("-")) {
            return STANDARD_IN;
        }
        Path path = delegate.convert(value);
        return ChannelProvider.forPath(path);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Class<? extends ChannelProvider<ReadableByteChannel>> valueType() {
        return (Class<? extends ChannelProvider<ReadableByteChannel>>) (Object) ChannelProvider.class;
    }

    @Override
    public String valuePattern() {
        return "A path or `-` for stdin";
    }
}
