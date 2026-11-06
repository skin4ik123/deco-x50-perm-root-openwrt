package com.internetdeco.decocontrol

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ConnectionRetryTest {
    private fun failure(code: String) = ConnectionFailure("10.0.0.2", 22, "ssh_banner", code, "fixture")
    @Test fun slowTransportSucceedsOnThirdAttempt() {
        val attempts = mutableListOf<Int>(); val waits = mutableListOf<Long>(); var slept = 0L
        val value = ConnectionRetry.establish(ConnectionOptions(), onAttempt = { attempts.add(it) }, onRetry = { _, delay, _ -> waits.add(delay) }, sleep = { slept += it }) {
            if (it < 3) throw failure("timeout")
            "authenticated"
        }
        assertEquals("authenticated", value); assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(listOf(2_000L, 5_000L), waits); assertEquals(7_000L, slept)
    }
    @Test fun credentialAndKeyErrorsNeverRetry() {
        for (code in listOf("authentication", "host_key", "algorithms", "address")) {
            var calls = 0
            try { ConnectionRetry.establish(ConnectionOptions(), sleep = { fail("Unexpected retry") }) { calls++; throw failure(code) } }
            catch (e: ConnectionFailure) { assertEquals(code, e.code) }
            assertEquals(1, calls)
        }
    }
    @Test fun cancellationStopsRetryWaitBeforeAnotherSocket() {
        var cancelled = false; var calls = 0
        try {
            ConnectionRetry.establish(ConnectionOptions(), checkCancelled = { if (cancelled) throw IOException("cancelled") }, sleep = { cancelled = true }) { calls++; throw failure("transport") }
            fail("Must cancel")
        } catch (e: IOException) { assertEquals("cancelled", e.message) }
        assertEquals(1, calls)
    }
    @Test fun configuredLimitIsRespected() {
        var calls = 0
        try { ConnectionRetry.establish(ConnectionOptions(attempts = 2), sleep = {}) { calls++; throw failure("refused") } }
        catch (e: ConnectionFailure) { assertEquals("refused", e.code) }
        assertEquals(2, calls)
    }
    @Test fun defaultsAllowPatientDecoSignIn() {
        val value = ConnectionOptions()
        assertEquals(90_000, value.handshakeTimeoutMs); assertEquals(30_000, value.tcpTimeoutMs); assertEquals(3, value.attempts)
        assertTrue(ConnectionState("10.0.0.2", phase = ConnectionPhase.BANNER).busy)
        assertFalse(ConnectionState("10.0.0.2", phase = ConnectionPhase.FAILED).busy)
    }
}
