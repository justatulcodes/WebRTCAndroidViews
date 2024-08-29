package com.playground.webrtcplayground


sealed class CallAction {
    data class ToggleMicroPhone(
        val isEnabled: Boolean
    ) : CallAction()

    data class ToggleCamera(
        val isEnabled: Boolean
    ) : CallAction()

    data object FlipCamera : CallAction()

    data object LeaveCall : CallAction()
}

data class VideoCallControlAction(
    val icon: Int,
    val callAction: CallAction
)

fun buildDefaultCallControlActions(
    callMediaState: CallMediaState
): List<VideoCallControlAction> {
    val microphoneIcon = R.drawable.ic_mic
    val cameraIcon = R.drawable.ic_video
    return listOf(
        VideoCallControlAction(
            icon = microphoneIcon,
            callAction = CallAction.ToggleMicroPhone(callMediaState.isMicrophoneEnabled)
        ),
        VideoCallControlAction(
            icon = cameraIcon,
            callAction = CallAction.ToggleCamera(callMediaState.isCameraEnabled)
        ),
        VideoCallControlAction(
            icon = R.drawable.ic_switch_camera,
            callAction = CallAction.FlipCamera
        ),
        VideoCallControlAction(
            icon = R.drawable.ic_call_end,
            callAction = CallAction.LeaveCall
        )
    )
}
