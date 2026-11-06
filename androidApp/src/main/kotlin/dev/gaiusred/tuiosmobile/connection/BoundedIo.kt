package dev.gaiusred.tuiosmobile.connection

import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val networkWorkers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Cancellation closes the owner immediately, even when Java I/O ignores thread interruption. */
internal suspend fun <T> boundedIo(timeoutMillis: Long, abort: () -> Unit,
    discard: (T) -> Unit = {}, block: () -> T): T = withTimeout(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { abort() }
        networkWorkers.launch {
            try {
                val result = block()
                continuation.resume(result) { _, value, _ -> discard(value) }
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
    }
}
