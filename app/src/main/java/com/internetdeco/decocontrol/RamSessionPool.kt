package com.internetdeco.decocontrol

import java.util.IdentityHashMap

/**
 * Owns RAM SSH peers while device identity and the operation nonce are checked.
 * A staged peer is available only for verification. Terminal access starts after
 * [confirm] has successfully committed the pin/receipt in its beforePublish callback.
 *
 * All operations, including the commit callback and close hook, are serialized.
 * Callbacks should finish promptly. Use the expected-session confirm overload
 * when verification work happens outside this lock, so a late result cannot
 * confirm a replacement connection. Keys should include the operation identity.
 */
class RamSessionPool<T : Any>(private val close: (T) -> Unit) {
    private class Held<T>(val value: T) { var closed = false }
    private class Entry<T> {
        var candidate: Held<T>? = null
        var verified: Held<T>? = null
    }

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry<T>>()
    private val owned = IdentityHashMap<T, Held<T>>()

    /** Invalidate every previous connection before starting a new verification. */
    fun begin(key: String): Unit = synchronized(lock) {
        val previous = entries.remove(key)
        retireAll(listOfNotNull(previous?.candidate, previous?.verified))
    }

    /** Takes ownership of a fresh peer. Replacing a candidate closes that candidate. */
    fun stage(key: String, session: T): Unit = synchronized(lock) {
        val existing = entries[key]
        if (existing?.candidate?.value === session) return@synchronized
        require(!owned.containsKey(session)) { "RAM session is already owned by this pool" }
        val entry = existing ?: Entry<T>().also { entries[key] = it }
        val previous = entry.candidate
        val candidate = Held(session)
        entry.candidate = candidate
        owned[session] = candidate
        try {
            previous?.let(::retire)
        } catch (failure: Throwable) {
            abort(candidate, failure)
        }
    }

    /** Unverified peer for read-only identity/nonce checks; never terminal access. */
    fun verificationSession(key: String): T? = synchronized(lock) {
        entries[key]?.candidate?.value
    }

    fun terminalSession(key: String): T? = synchronized(lock) {
        entries[key]?.verified?.value
    }

    /** For callers that serialize verification themselves for this operation key. */
    fun confirm(key: String, beforePublish: () -> Unit): Unit = synchronized(lock) {
        val candidate = checkNotNull(entries[key]?.candidate) { "No staged RAM session" }
        confirmHeld(key, candidate, beforePublish)
    }

    /** Rejects a late proof for a connection replaced during asynchronous checks. */
    fun confirm(key: String, expectedSession: T, beforePublish: () -> Unit): Unit = synchronized(lock) {
        val candidate = checkNotNull(entries[key]?.candidate) { "No staged RAM session" }
        check(candidate.value === expectedSession) { "RAM verification session was replaced" }
        confirmHeld(key, candidate, beforePublish)
    }

    fun discardUnverified(key: String): Unit = synchronized(lock) {
        entries[key]?.candidate?.let(::retire)
        Unit
    }

    fun closeMatching(prefix: String): Unit = synchronized(lock) {
        val matching = entries.filterKeys { it.startsWith(prefix) }.values
            .flatMap { listOfNotNull(it.candidate, it.verified) }
        retireAll(matching)
    }

    fun closeAll(): Unit = synchronized(lock) {
        retireAll(entries.values.flatMap { listOfNotNull(it.candidate, it.verified) })
    }

    private fun confirmHeld(key: String, candidate: Held<T>, beforePublish: () -> Unit) {
        val entry = checkNotNull(entries[key])
        try {
            beforePublish()
            // A callback can re-enter this monitor. Never resurrect a discarded peer.
            check(entries[key] === entry && entry.candidate === candidate && !candidate.closed) {
                "RAM verification was superseded before publication"
            }
            val previous = entry.verified
            entry.candidate = null
            entry.verified = candidate
            previous?.let(::retire)
        } catch (failure: Throwable) {
            abort(candidate, failure)
        }
    }

    /** Detach before invoking user code, so even a throwing/reentrant closer is safe. */
    private fun retire(session: Held<T>) {
        if (session.closed) return
        session.closed = true
        owned.remove(session.value)
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.candidate === session) entry.candidate = null
            if (entry.verified === session) entry.verified = null
            if (entry.candidate == null && entry.verified == null) iterator.remove()
        }
        close(session.value)
    }

    private fun retireAll(sessions: List<Held<T>>) {
        var failure: Throwable? = null
        for (session in sessions) {
            try {
                retire(session)
            } catch (problem: Throwable) {
                if (failure == null) failure = problem
                else if (failure !== problem) failure.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }

    private fun abort(session: Held<T>, failure: Throwable): Nothing {
        try {
            retire(session)
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}
