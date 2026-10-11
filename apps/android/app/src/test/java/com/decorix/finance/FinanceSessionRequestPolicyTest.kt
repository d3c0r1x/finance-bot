package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class FinanceSessionRequestPolicyTest {
    @Test fun staleGenerationDoesNotEnterProtectedBlock() {
        val sessionLock = Any()
        val generation = AtomicLong(1L)
        generation.set(2L)
        var entered = false

        val failure = runCatching {
            FinanceSessionRequestPolicy.performIfCurrent(
                expectedGeneration = 1L,
                sessionLock = sessionLock,
                currentGeneration = generation::get,
            ) { entered = true }
        }.exceptionOrNull()

        assertFalse("Stale session must not enter the side-effect block", entered)
        assertTrue(failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
    }

    @Test fun replacementDuringProtectedBlockWaitsUntilBlockCompletes() {
        val sessionLock = Any()
        val generation = AtomicLong(1L)
        val blockEntered = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val blockFailure = AtomicReference<Throwable?>(null)
        val replacementFailure = AtomicReference<Throwable?>(null)

        val protectedCall = Thread({
            try {
                FinanceSessionRequestPolicy.performIfCurrent(
                    expectedGeneration = 1L,
                    sessionLock = sessionLock,
                    currentGeneration = generation::get,
                ) {
                    blockEntered.countDown()
                    check(releaseBlock.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release protected block" }
                    assertEquals("Session changed inside protected block", 1L, generation.get())
                }
            } catch (failure: Throwable) {
                blockFailure.set(failure)
            }
        }, "session-policy-protected-call")
        protectedCall.start()

        assertTrue("Protected block did not start", blockEntered.await(2, TimeUnit.SECONDS))
        val replacement = Thread({
            replacementStarted.countDown()
            try {
                synchronized(sessionLock) { generation.set(2L) }
            } catch (failure: Throwable) {
                replacementFailure.set(failure)
            }
        }, "session-policy-replacement")
        replacement.start()

        try {
            assertTrue("Replacement thread did not start", replacementStarted.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (replacement.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals("Replacement must wait on the session lock", Thread.State.BLOCKED, replacement.state)
            assertEquals("Replacement committed before protected block ended", 1L, generation.get())
        } finally {
            releaseBlock.countDown()
            protectedCall.join(5_000)
            replacement.join(5_000)
        }

        assertFalse("Protected call thread did not finish", protectedCall.isAlive)
        assertFalse("Replacement thread did not finish", replacement.isAlive)
        assertEquals(null, blockFailure.get())
        assertEquals(null, replacementFailure.get())
        assertEquals(2L, generation.get())
    }
}
