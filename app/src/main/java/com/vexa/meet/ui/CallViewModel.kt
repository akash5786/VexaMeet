package com.vexa.meet.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallViewModel : ViewModel() {

    private val _callTimer = MutableLiveData("00:00")
    val callTimer: LiveData<String> = _callTimer

    private val _networkQuality = MutableLiveData("Ready")
    val networkQuality: LiveData<String> = _networkQuality

    private val _reconnecting = MutableLiveData(false)
    val reconnecting: LiveData<Boolean> = _reconnecting

    private var timerJob: Job? = null

    fun onCallStarted() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var seconds = 0
            while (true) {
                _callTimer.value = "%02d:%02d".format(seconds / 60, seconds % 60)
                delay(1000)
                seconds++
            }
        }
        _networkQuality.value = "Connecting"
        _reconnecting.value = false
    }

    fun onCallEnded() {
        timerJob?.cancel()
        timerJob = null
        _callTimer.value = "00:00"
        _networkQuality.value = "Ready"
        _reconnecting.value = false
    }

    fun setNetworkQuality(label: String, reconnecting: Boolean = false) {
        _networkQuality.value = label
        _reconnecting.value = reconnecting
    }

    override fun onCleared() {
        timerJob?.cancel()
        super.onCleared()
    }
}
