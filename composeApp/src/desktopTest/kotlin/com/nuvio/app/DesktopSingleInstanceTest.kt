package com.nuvio.app

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSingleInstanceTest {

    /**
     * File locks are per process, so a second claim from this JVM sees `OverlappingFileLockException`
     * rather than a lock held by another process. The guard treats both as "someone owns it", so this
     * still exercises the whole secondary path: port-file lookup, loopback connect, activate, ack.
     */
    @Test
    fun `a second claim of the same directory signals the first and reports secondary`() {
        val dir = Files.createTempDirectory("nuvio-single-instance")
        val activated = CountDownLatch(1)
        DesktopSingleInstance.onActivate = { activated.countDown() }
        try {
            assertEquals(DesktopSingleInstance.Outcome.Primary, DesktopSingleInstance.claim(dir))
            assertTrue(Files.exists(dir.resolve("instance.port")), "primary must publish its port")

            val second = DesktopSingleInstance.claim(dir)
            assertEquals(DesktopSingleInstance.Outcome.Secondary(signalled = true), second)
            assertTrue(activated.await(5, TimeUnit.SECONDS), "primary never received the activate request")
        } finally {
            DesktopSingleInstance.onActivate = null
        }
    }
}
