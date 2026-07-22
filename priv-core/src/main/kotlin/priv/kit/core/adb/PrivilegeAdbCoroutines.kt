package priv.kit.core.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException

internal suspend fun <T> cancellableAdbCall(
    cancel: () -> Unit,
    block: () -> T,
): T = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        val close = { runCatching(cancel) }
        continuation.invokeOnCancellation { close() }
        try {
            val result = block()
            if (continuation.isActive) {
                continuation.resume(result) { _, _, _ -> close() }
            }
        } catch (throwable: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(throwable)
        }
    }
}
