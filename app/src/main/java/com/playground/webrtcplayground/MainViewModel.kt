package com.playground.webrtcplayground

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.playground.webrtcplayground.webrtc.WebRTCSessionState

class MainViewModel : ViewModel() {
    private val _sessionState = MutableLiveData<WebRTCSessionState>()
    val sessionState: LiveData<WebRTCSessionState> get() = _sessionState

    private val _isVideoCallReady = MutableLiveData<Boolean>(false)
    val isVideoCallReady get() = _isVideoCallReady

    fun updateSessionState(newState: WebRTCSessionState) {
        _sessionState.value = newState
    }

    fun updateVideoCallReady(value: Boolean) {
        _isVideoCallReady.value = value
    }
}
