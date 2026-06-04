package com.vexa.meet

object ActiveCallActions {
    interface Listener {
        fun onNotificationMuteToggled(muted: Boolean)
        fun onNotificationEndCall()
    }

    var listener: Listener? = null
}
