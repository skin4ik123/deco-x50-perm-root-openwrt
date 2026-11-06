package com.internetdeco.decocontrol

/** Retries transport setup only. A remote command is never replayed here. */
object ConnectionRetry {
    fun retryable(error: Exception): Boolean = error is ConnectionFailure &&
        error.code in setOf("timeout", "refused", "route", "transport")

    fun <T> establish(
        options: ConnectionOptions,
        checkCancelled: () -> Unit = {},
        onAttempt: (Int) -> Unit = {},
        onRetry: (Int, Long, Exception) -> Unit = { _, _, _ -> },
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        attempt: (Int) -> T,
    ): T {
        for (number in 1..options.attempts) {
            checkCancelled()
            onAttempt(number)
            try {
                return attempt(number)
            } catch (error: Exception) {
                checkCancelled()
                if (number == options.attempts || !retryable(error)) throw error
                val waitMs = if (number == 1) 2_000L else 5_000L
                onRetry(number + 1, waitMs, error)
                var remaining = waitMs
                while (remaining > 0) {
                    checkCancelled()
                    val slice = minOf(remaining, 200L)
                    sleep(slice)
                    remaining -= slice
                }
            }
        }
        error("No connection attempt was made")
    }
}
