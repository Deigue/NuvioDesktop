package com.nuvio.app

/**
 * The application's orderly exit, reachable from anywhere that has a reason to close Nuvio on
 * the user's behalf (game mode's "close after starting a game"). Main.kt installs the handler
 * once the window exists; it is the same path the tray's Exit entry and the window close take,
 * so window geometry is flushed and the P2P engine shut down before the process goes.
 */
object DesktopApplicationExit {
    @Volatile
    private var handler: (() -> Unit)? = null

    fun install(exitApplication: () -> Unit) {
        handler = exitApplication
    }

    /** Runs the installed exit; false when nothing has been installed yet (before the window). */
    fun request(): Boolean {
        val exit = handler ?: return false
        exit()
        return true
    }
}
