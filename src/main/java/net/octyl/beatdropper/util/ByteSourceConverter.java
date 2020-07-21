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

import java.io.InputStream;
import java.nio.file.Path;

import com.google.common.io.ByteSource;
import com.google.common.io.MoreFiles;
import joptsimple.ValueConverter;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;

public class ByteSourceConverter implements ValueConverter<NamedByteSource> {

    private final NamedByteSource standardInSource = NamedByteSource.of("stream:stdin", new ByteSource() {
        @Override
        public InputStream openStream() {
            return System.in;
        }
    });
    private final PathConverter delegate = new PathConverter(PathProperties.READABLE);

    @Override
    public NamedByteSource convert(String value) {
        if (value.equals("-")) {
            return standardInSource;
        }
        Path path = delegate.convert(value);
        return NamedByteSource.of("file:" + path.toAbsolutePath().toString(), MoreFiles.asByteSource(path));
    }

    @Override
    public Class<? extends NamedByteSource> valueType() {
        return NamedByteSource.class;
    }

    @Override
    public String valuePattern() {
        return "A path or `-` for stdin";
    }
}
