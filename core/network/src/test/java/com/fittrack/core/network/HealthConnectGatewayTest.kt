package com.fittrack.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [toHealthConnectReadError]'s classification rule only — the one
 * piece of new judgment-call logic from the M8 architecture review
 * (candidate 1). [AndroidHealthConnectGateway] itself wraps the Android SDK's
 * `HealthConnectClient`, which this repo has no fake/mock for (no
 * Mockito/MockK dependency, and hand-rolling a fake for that interface's
 * full surface was judged too high-risk to get right without a compiler in
 * this sandbox — see fitbit-HANDOFF.md for the same caveat elsewhere).
 */
class HealthConnectGatewayTest {

    @Test
    fun `security exception is classified as not retryable`() {
        val error = SecurityException("permission revoked").toHealthConnectReadError()

        assertFalse(error.retryable)
        assertEquals("HEALTH_CONNECT_READ_FAILED", error.code)
    }

    @Test
    fun `any other exception is classified as retryable`() {
        val error = IllegalStateException("transient platform hiccup").toHealthConnectReadError()

        assertTrue(error.retryable)
        assertEquals("HEALTH_CONNECT_READ_FAILED", error.code)
    }
}
