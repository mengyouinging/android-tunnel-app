package org.example.androidtunnel

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsSanitizerTest {
    @Test fun replacesKnownSecretsAndCredentialLikeValues() {
        val safe = DiagnosticsSanitizer.sanitize(
            "password=hunter2 token=abc123 uuid=123e4567-e89b-12d3-a456-426614174000 private_key=abc123",
            listOf("hunter2", "abc123")
        )
        assertFalse(safe.contains("hunter2"))
        assertFalse(safe.contains("abc123"))
        assertFalse(safe.contains("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(safe.contains("present"))
        val keySafe = DiagnosticsSanitizer.sanitize("PrivateKey = ${"A".repeat(43)}=")
        assertFalse(keySafe.contains("A".repeat(43)))
    }

    @Test fun fileNameContainsOnlyVersionAndTimestamp() {
        assertEquals("清连-诊断-0.2.4-20261127141500.txt",
            DiagnosticsSanitizer.fileName("0.2.4", "20261127-141500"))
    }

    @Test fun unsafeVersionCharactersAreRemoved() {
        assertEquals("清连-诊断-024-20261127141500.txt",
            DiagnosticsSanitizer.fileName("0/2:4", "20261127-141500"))
    }
}
