package com.fittrack.feature.coach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeUrlTest {
    @Test
    fun `allows absolute https URL`() {
        assertTrue("https://example.org/evidence?id=42".isSafeHttpsUrl())
    }

    @Test
    fun `rejects non https and relative URLs`() {
        assertFalse("http://example.org".isSafeHttpsUrl())
        assertFalse("javascript:alert(1)".isSafeHttpsUrl())
        assertFalse("/local/path".isSafeHttpsUrl())
    }

    @Test
    fun `rejects URL containing credentials`() {
        assertFalse("https://user:secret@example.org/evidence".isSafeHttpsUrl())
    }
}
