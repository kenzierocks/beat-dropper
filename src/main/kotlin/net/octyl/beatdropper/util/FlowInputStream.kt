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
