package com.vexa.meet

import com.vexa.meet.Helper.WebRTCManager

object ActiveCallSession {
    var manager: WebRTCManager? = null
    var roomId: String = ""
    var micEnabled: Boolean = true
    var cameraEnabled: Boolean = true
    var speakerEnabled: Boolean = true

    val isActive: Boolean
        get() = manager != null && roomId.isNotBlank()

    fun setMuted(muted: Boolean) {
        micEnabled = !muted
        manager?.setMicEnabled(!muted)
    }

    fun endActiveCall() {
        val activeManager = manager
        clear()
        activeManager?.disconnect()
        activeManager?.release()
    }

    fun clear() {
        manager = null
        roomId = ""
        micEnabled = true
        cameraEnabled = true
        speakerEnabled = true
    }
}
