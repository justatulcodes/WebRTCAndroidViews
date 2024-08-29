package com.playground.webrtcplayground.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.playground.webrtcplayground.CallMediaState
import com.playground.webrtcplayground.MainActivity
import com.playground.webrtcplayground.MainViewModel
import com.playground.webrtcplayground.R
import com.playground.webrtcplayground.components.VideoTextureViewRenderer
import com.playground.webrtcplayground.databinding.FragmentVideoCallBinding
import com.playground.webrtcplayground.webrtc.WebRTCSessionState
import com.playground.webrtcplayground.webrtc.sessions.WebRtcSessionManager
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack


class VideoCallFragment : Fragment() {

    private lateinit var binding : FragmentVideoCallBinding
    private val viewModel: MainViewModel by activityViewModels()
    private var remoteVideoTrack: VideoTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteRenderer: VideoTextureViewRenderer? = null
    private var localRenderer: VideoTextureViewRenderer? = null
    private lateinit var sessionManager: WebRtcSessionManager
    private var callMediaState = CallMediaState()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        binding = FragmentVideoCallBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = (requireActivity() as MainActivity).sessionManager
        setupCallControls()
        setupVideoRenderers()
        observeVideoTracks()

        viewModel.sessionState.observe(viewLifecycleOwner) { sessionState->
            if(sessionState == WebRTCSessionState.HungUp) {
                Toast.makeText(requireContext(), "Client hung up the call!", Toast.LENGTH_LONG).show()
                viewModel.updateVideoCallReady(false)
                (requireActivity() as MainActivity).reInitializeSessionManager()
                (requireActivity() as MainActivity).navigateToStageScreen()
            }
            else if(sessionState == WebRTCSessionState.Offline) {
                Toast.makeText(requireContext(), "Connection Failure", Toast.LENGTH_LONG).show()
                viewModel.updateVideoCallReady(false)
                (requireActivity() as MainActivity).navigateToStageScreen()
        }


        }

        sessionManager.onSessionScreenReady()
    }

    private fun setupVideoRenderers() {
        remoteRenderer = binding.remoteVideoContainer
        localRenderer = binding.localVideoContainer

        remoteRenderer?.init(sessionManager.peerConnectionFactory.eglBaseContext, null)
        localRenderer?.init(sessionManager.peerConnectionFactory.eglBaseContext, null)
    }

    private fun observeVideoTracks() {
        lifecycleScope.launch {
            sessionManager.remoteVideoTrackFlow.collect { videoTrack ->
                updateRemoteVideoTrack(videoTrack)
            }
        }

        lifecycleScope.launch {
            sessionManager.localVideoTrackFlow.collect { videoTrack ->
                updateLocalVideoTrack(videoTrack)
            }
        }
    }

    private fun updateRemoteVideoTrack(videoTrack: VideoTrack?) {
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteVideoTrack = videoTrack
        videoTrack?.addSink(remoteRenderer)
        binding.remoteVideoContainer.visibility = if (videoTrack != null) View.VISIBLE else View.GONE
    }

    private fun updateLocalVideoTrack(videoTrack: VideoTrack?) {
        localVideoTrack?.removeSink(localRenderer)
        localVideoTrack = videoTrack
        videoTrack?.addSink(localRenderer)
        updateLocalVideoVisibility()
    }

    private fun updateLocalVideoVisibility() {
        binding.localVideoContainer.visibility =
            if (localVideoTrack != null && callMediaState.isCameraEnabled) View.VISIBLE else View.GONE
    }

    private fun setupCallControls() {
        val videoCallControls = binding.videoCallControls

        val micButton = createControlButton(R.drawable.ic_mic)
        val cameraButton = createControlButton(R.drawable.ic_video)
        val flipCameraButton = createControlButton(R.drawable.ic_switch_camera)
        val leaveCallButton = createControlButton(R.drawable.ic_call_end)

        micButton.setOnClickListener {
            val enabled = !callMediaState.isMicrophoneEnabled
            callMediaState = callMediaState.copy(isMicrophoneEnabled = enabled)
            sessionManager.enableMicrophone(enabled)
            updateMicButtonState(micButton, enabled)
        }

        cameraButton.setOnClickListener {
            val enabled = !callMediaState.isCameraEnabled
            callMediaState = callMediaState.copy(isCameraEnabled = enabled)
            sessionManager.enableCamera(enabled)
            updateCameraButtonState(cameraButton, enabled)
            updateLocalVideoVisibility()
        }

        flipCameraButton.setOnClickListener {
            sessionManager.flipCamera()
        }

        leaveCallButton.setOnClickListener {
            sessionManager.disconnect()
            requireActivity().finish()
        }

        videoCallControls.addView(micButton)
        videoCallControls.addView(cameraButton)
        videoCallControls.addView(flipCameraButton)
        videoCallControls.addView(leaveCallButton)
    }

    private fun createControlButton(@DrawableRes iconResId: Int): ImageButton {
        return (LayoutInflater.from(requireContext()).inflate(R.layout.call_control_btn, binding.root, false) as ImageButton).apply {
            setImageResource(iconResId)
        }
    }

    private fun updateMicButtonState(button: ImageButton, enabled: Boolean) {
        button.setImageResource(if (enabled) R.drawable.ic_mic else R.drawable.ic_mic_off)
    }

    private fun updateCameraButtonState(button: ImageButton, enabled: Boolean) {
        button.setImageResource(if (enabled) R.drawable.ic_video else R.drawable.ic_video_off)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        remoteVideoTrack?.removeSink(remoteRenderer)
        localVideoTrack?.removeSink(localRenderer)

        }
        companion object {
        fun newInstance() = VideoCallFragment()
    }

}