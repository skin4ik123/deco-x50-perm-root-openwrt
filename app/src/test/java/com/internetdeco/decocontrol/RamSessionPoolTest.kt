package com.internetdeco.decocontrol

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

/** Synthetic sessions only; no Android runtime or network connection. */
class RamSessionPoolTest {
    private data class Peer(val name: String) { var closes = 0 }
    private fun pool() = RamSessionPool<Peer> { it.closes++ }

    private inline fun <reified E : Throwable> failure(action: () -> Unit): E {
        try {
            action()
            fail("Expected ${E::class.java.simpleName}")
        } catch (problem: Throwable) {
            if (problem !is E) throw problem
            return problem
        }
        error("unreachable")
    }

    @Test fun terminalReceivesPeerOnlyAfterPinCommitCompletes() {
        val pool = pool()
        val peer = Peer("ram")
        pool.stage("operation-a|192.168.1.1", peer)
        assertSame(peer, pool.verificationSession("operation-a|192.168.1.1"))
        assertNull(pool.terminalSession("operation-a|192.168.1.1"))
        var committed = false
        pool.confirm("operation-a|192.168.1.1", peer) {
            assertNull(pool.terminalSession("operation-a|192.168.1.1"))
            committed = true
        }
        assertTrue(committed)
        assertSame(peer, pool.terminalSession("operation-a|192.168.1.1"))
        assertNull(pool.verificationSession("operation-a|192.168.1.1"))
        pool.closeAll()
        assertEquals(1, peer.closes)
    }

    @Test fun failedNonceReverificationCannotReuseOldVerifiedTerminal() {
        val pool = pool()
        val previous = Peer("previous verified")
        val foreign = Peer("wrong Wi-Fi")
        pool.stage("operation", previous)
        pool.confirm("operation") {}
        pool.begin("operation")
        assertEquals(1, previous.closes)
        assertNull(pool.terminalSession("operation"))
        pool.stage("operation", foreign)
        // Native rejects the operation nonce, so the controller discards the candidate.
        pool.discardUnverified("operation")
        assertNull(pool.terminalSession("operation"))
        assertNull(pool.verificationSession("operation"))
        assertEquals(1, foreign.closes)
        pool.begin("operation")
        pool.closeAll()
        assertEquals(1, previous.closes)
        assertEquals(1, foreign.closes)
    }

    @Test fun vaultCommitFailureClosesCandidateAndPublishesNothing() {
        val peer = Peer("new")
        val pool = pool()
        val vaultFailure = IOException("Synthetic pin persistence failure")
        pool.begin("operation")
        pool.stage("operation", peer)
        assertSame(vaultFailure, failure<IOException> {
            pool.confirm("operation", peer) { throw vaultFailure }
        })
        assertNull(pool.terminalSession("operation"))
        assertNull(pool.verificationSession("operation"))
        pool.closeAll()
        assertEquals(1, peer.closes)
    }

    @Test fun discardingCandidatePreservesVerifiedPeerAndSuccessfulReplacementClosesIt() {
        val pool = pool()
        val verified = Peer("verified")
        val discarded = Peer("discarded")
        val replacement = Peer("replacement")
        pool.stage("operation", verified)
        pool.confirm("operation") {}
        pool.stage("operation", discarded)
        pool.discardUnverified("operation")
        assertSame(verified, pool.terminalSession("operation"))
        assertEquals(0, verified.closes)
        assertEquals(1, discarded.closes)
        pool.stage("operation", replacement)
        pool.confirm("operation", replacement) {}
        assertSame(replacement, pool.terminalSession("operation"))
        assertEquals(1, verified.closes)
        pool.closeAll()
        assertEquals(1, replacement.closes)
    }

    @Test fun operationIdentityIsolatesTwoPeersAtTheSameIpAddress() {
        val pool = pool()
        val a = Peer("first operation")
        val b = Peer("second operation")
        val keyA = "operation-a|192.168.1.1"
        val keyB = "operation-b|192.168.1.1"
        pool.stage(keyA, a)
        pool.confirm(keyA) {}
        pool.stage(keyB, b)
        assertNull(pool.terminalSession(keyB))
        pool.closeMatching("operation-a|")
        assertNull(pool.terminalSession(keyA))
        assertSame(b, pool.verificationSession(keyB))
        assertEquals(1, a.closes)
        assertEquals(0, b.closes)
        pool.closeAll()
        assertEquals(1, b.closes)
    }

    @Test fun lateIdentityProofCannotConfirmAReplacementCandidate() {
        val pool = pool()
        val checked = Peer("checked")
        val unchecked = Peer("replacement")
        pool.stage("operation", checked)
        val expected = pool.verificationSession("operation")!!
        pool.stage("operation", unchecked)
        assertEquals(1, checked.closes)
        var committed = false
        failure<IllegalStateException> {
            pool.confirm("operation", expected) { committed = true }
        }
        assertFalse(committed)
        assertNull(pool.terminalSession("operation"))
        assertSame(unchecked, pool.verificationSession("operation"))
        pool.discardUnverified("operation")
        assertEquals(1, unchecked.closes)
    }

    @Test fun reentrantInvalidationDuringCommitNeverResurrectsThePreviousPeer() {
        val pool = pool()
        val checked = Peer("checked")
        val replacement = Peer("replacement")
        pool.stage("operation", checked)
        failure<IllegalStateException> {
            pool.confirm("operation", checked) {
                pool.begin("operation")
                pool.stage("operation", replacement)
            }
        }
        assertNull(pool.terminalSession("operation"))
        assertSame(replacement, pool.verificationSession("operation"))
        assertEquals(1, checked.closes)
        pool.closeAll()
        assertEquals(1, checked.closes)
        assertEquals(1, replacement.closes)
    }

    @Test fun beginWaitsForCommitThenClosesPublishedPeerExactlyOnce() {
        val pool = pool()
        val peer = Peer("ram")
        val enteredCommit = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val attemptedBegin = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        pool.stage("operation", peer)
        try {
            val confirm = workers.submit {
                pool.confirm("operation", peer) {
                    enteredCommit.countDown()
                    check(releaseCommit.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(enteredCommit.await(5, TimeUnit.SECONDS))
            val begin = workers.submit {
                attemptedBegin.countDown()
                pool.begin("operation")
            }
            assertTrue(attemptedBegin.await(5, TimeUnit.SECONDS))
            failure<TimeoutException> { begin.get(100, TimeUnit.MILLISECONDS) }
            releaseCommit.countDown()
            confirm.get(5, TimeUnit.SECONDS)
            begin.get(5, TimeUnit.SECONDS)
            assertNull(pool.terminalSession("operation"))
            assertNull(pool.verificationSession("operation"))
            assertEquals(1, peer.closes)
        } finally {
            releaseCommit.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            pool.closeAll()
        }
    }

    @Test fun equalButDistinctPeersAreOwnedByIdentityAndCloseFailuresDoNotLeakOthers() {
        val a = Peer("same name")
        val b = Peer("same name")
        assertEquals(a, b)
        val pool = RamSessionPool<Peer> {
            it.closes++
            if (it === a) throw IOException("Synthetic close failure")
        }
        pool.stage("a", a)
        pool.stage("a", a) // Idempotent staging does not close the current candidate.
        failure<IllegalArgumentException> { pool.stage("duplicate", a) }
        pool.stage("b", b)
        failure<IOException> { pool.closeAll() }
        assertEquals(1, a.closes)
        assertEquals(1, b.closes)
        assertNull(pool.verificationSession("a"))
        assertNull(pool.verificationSession("b"))
        pool.closeAll()
        assertEquals(1, a.closes)
        assertEquals(1, b.closes)
    }
}
