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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.receiveOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.nio.ByteBuffer

class FlowInputStream(flow: Flow<ByteBuffer>) : InputStream() {

    private val scope = CoroutineScope(Dispatchers.IO)
    @OptIn(FlowPreview::class)
    private val channel: ReceiveChannel<ByteBuffer> = flow.produceIn(scope)
    private var buffer: ByteBuffer? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun fillIfNeeded(): ByteBuffer? {
        if (!scope.isActive) {
            return null
        }
        var local = buffer
        if (local == null || !local.hasRemaining()) {
            buffer = runBlocking { channel.receiveOrNull() }
            local = buffer
        }
        return local
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val buffer = fillIfNeeded() ?: return -1
        val size = len.coerceAtMost(buffer.remaining())
        buffer.get(b, off, size)
        return size
    }

    override fun read(): Int {
        val buffer = fillIfNeeded() ?: return -1
        return buffer.get().toInt()
    }

    override fun close() {
        scope.cancel()
    }
}
