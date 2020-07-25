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

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

interface Condition {
    suspend fun await()

    suspend fun signal()
}

private class ConditionImpl(
    private val mutex: Mutex
) : Condition {
    private var waiter: Continuation<Unit>? = null

    // These mutex checks aren't perfect, but they're "good enough" for now
    override suspend fun await() {
        require(mutex.isLocked) { "Awaiting condition requires a locked mutex" }
        require(waiter == null) { "Another coroutine is already awaiting this condition" }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                waiter = cont
                mutex.unlock()
            }
        } finally {
            mutex.lock()
        }
    }

    override suspend fun signal() {
        require(mutex.isLocked) { "Signaling condition requires a locked mutex" }
        waiter?.resume(Unit)
    }
}

fun Mutex.newCondition(): Condition = ConditionImpl(this)
