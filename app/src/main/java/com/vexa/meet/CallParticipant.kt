package com.vexa.meet

data class CallParticipant(
    val userId: String,
    val status: String = "joined",
    val role: String? = null,
    val active: Boolean = true,
    val onHold: Boolean = false
)
