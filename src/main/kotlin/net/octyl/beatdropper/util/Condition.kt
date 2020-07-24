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
