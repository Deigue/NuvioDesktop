package com.nuvio.app.features.player

import com.nuvio.app.features.player.desktop.DesktopPlayerLaunchShield

internal actual fun holdPlayerHandoffShield() {
    DesktopPlayerLaunchShield.holdForHandoff()
}
