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

import java.util.Deque;
import java.util.LinkedList;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * {@link AutoCloseable}-compatible closer.
 */
public class AutoCloser implements AutoCloseable {

    public interface AutoCloseableHook<C> {
        void close(C reference) throws Exception;
    }

    // LIFO queue, the last thing registered is the first thing closed
    private final Deque<AutoCloseable> closeables = new LinkedList<>();

    public <C extends AutoCloseable> C register(@Nullable C autoCloseable) {
        if (autoCloseable != null) {
            this.closeables.addFirst(autoCloseable);
        }
        return autoCloseable;
    }

    public <C> C register(@Nullable C reference, AutoCloseableHook<C> closer) {
        if (reference != null) {
            register(() -> closer.close(reference));
        }
        return reference;
    }

    @Override
    public void close() throws Exception {
        Exception rethrow = null;
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception t) {
                if (rethrow == null) {
                    rethrow = t;
                } else {
                    rethrow.addSuppressed(t);
                }
            }
        }
        if (rethrow != null) {
            throw rethrow;
        }
    }
}