package org.example.androidtunnel

import org.junit.Assert.*
import org.junit.Test

class StartupPolicyTest {
    @Test fun bootCompletedAlwaysRequestsStartup() {
        assertTrue(StartupPolicy.shouldStartOnBoot("android.intent.action.BOOT_COMPLETED"))
    }
    @Test fun otherBroadcastsDoNotStartService() {
        assertFalse(StartupPolicy.shouldStartOnBoot(null))
        assertFalse(StartupPolicy.shouldStartOnBoot("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertFalse(StartupPolicy.shouldStartOnBoot("org.example.androidtunnel.START"))
    }
    @Test fun actualRootUidIsRequired() {
        assertTrue(StartupPolicy.rootGranted(0, "0\n"))
        assertFalse(StartupPolicy.rootGranted(0, "2000"))
        assertFalse(StartupPolicy.rootGranted(1, "0"))
        assertFalse(StartupPolicy.rootGranted(0, "Permission denied"))
    }
}
