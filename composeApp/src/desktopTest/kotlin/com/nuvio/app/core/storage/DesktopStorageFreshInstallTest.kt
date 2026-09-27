package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A new HTPC user who has run the official Nuvio Desktop has its `%APPDATA%\Nuvio` directory; that
 * alone must not read as an upgrade, or they never see the setup wizard or new-install defaults.
 */
class DesktopStorageFreshInstallTest {
    @Test
    fun `an upstream Nuvio directory is not an HTPC install`() {
        val dir = Files.createTempDirectory("nuvio-upstream")
        Files.writeString(dir.resolve("nuvio_player_settings.properties"), "")
        Files.writeString(dir.resolve("nuvio_addons.properties"), "")
        Files.createDirectories(dir.resolve("Cache"))
        assertFalse(DesktopStorage.holdsHtpcOnlyStore(dir))
    }

    @Test
    fun `a pre-1_10 HTPC directory is recognised by its fork-only stores`() {
        val dir = Files.createTempDirectory("nuvio-htpc-legacy")
        Files.writeString(dir.resolve("nuvio_player_shortcuts.properties"), "")
        assertTrue(DesktopStorage.holdsHtpcOnlyStore(dir))
    }

    @Test
    fun `a missing directory holds nothing`() {
        val dir = Files.createTempDirectory("nuvio-none").resolve("absent")
        assertFalse(DesktopStorage.holdsHtpcOnlyStore(dir))
    }
}
