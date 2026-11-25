package com.ovpn.autodelay

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

object VpnStateBus {
    val statusText = MutableStateFlow("Ready")
    val logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    @Volatile var loadedProfiles: List<OvpnProfileItem> = emptyList()
    @Volatile var username: String = ""
    @Volatile var password: String = ""

    fun log(msg: String) {
        logs.tryEmit(msg)
    }
}
