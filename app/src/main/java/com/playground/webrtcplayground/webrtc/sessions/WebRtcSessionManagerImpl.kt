package com.playground.webrtcplayground.webrtc.sessions

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService
import com.playground.webrtcplayground.webrtc.SignalingClient
import com.playground.webrtcplayground.webrtc.SignalingCommand
import com.playground.webrtcplayground.webrtc.audio.AudioHandler
import com.playground.webrtcplayground.webrtc.audio.AudioSwitchHandler
import com.playground.webrtcplayground.webrtc.peer.StreamPeerConnection
import com.playground.webrtcplayground.webrtc.peer.StreamPeerConnectionFactory
import com.playground.webrtcplayground.webrtc.peer.StreamPeerType
import com.playground.webrtcplayground.webrtc.utils.stringify
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerationAndroid
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import java.util.UUID

private const val ICE_SEPARATOR = '$'

class WebRtcSessionManagerImpl(
  private val context: Context,
  override val signalingClient: SignalingClient,
  override val peerConnectionFactory: StreamPeerConnectionFactory
) : WebRtcSessionManager
{
  private val logger by taggedLogger("Call:LocalWebRtcSessionManager")

  private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private var isSessionActive = false
  private var peerConnection: StreamPeerConnection? = null

  // used to send local video track to the fragment
  private val _localVideoTrackFlow = MutableSharedFlow<VideoTrack>()
  override val localVideoTrackFlow: SharedFlow<VideoTrack> = _localVideoTrackFlow

  // used to send remote video track to the sender
  private val _remoteVideoTrackFlow = MutableSharedFlow<VideoTrack>()
  override val remoteVideoTrackFlow: SharedFlow<VideoTrack> = _remoteVideoTrackFlow

  // declaring video constraints and setting OfferToReceiveVideo to true
  // this step is mandatory to create valid offer and answer
  private val mediaConstraints = MediaConstraints().apply {
    mandatory.addAll(
      listOf(
        MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"),
        MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
      )
    )
  }

  // getting front camera
  private val videoCapturer: VideoCapturer by lazy { buildCameraCapturer() }
  private val cameraManager by lazy { context.getSystemService<CameraManager>() }
  private val cameraEnumerator: Camera2Enumerator by lazy {
    Camera2Enumerator(context)
  }

  private val resolution: CameraEnumerationAndroid.CaptureFormat
    get() {
      val frontCamera = cameraEnumerator.deviceNames.firstOrNull { cameraName ->
        cameraEnumerator.isFrontFacing(cameraName)
      } ?: cameraEnumerator.deviceNames.firstOrNull()
      ?: error("No camera found!")

      val supportedFormats = cameraEnumerator.getSupportedFormats(frontCamera) ?: emptyList()
      return supportedFormats.firstOrNull { it.width == 720 || it.width == 480 || it.width == 360 }
        ?: supportedFormats.maxByOrNull { it.width }
        ?: error("No supported camera resolution found!")
    }


  // we need it to initialize video capturer
  private val surfaceTextureHelper = SurfaceTextureHelper.create(
    "SurfaceTextureHelperThread",
    peerConnectionFactory.eglBaseContext
  )

  private val videoSource by lazy {
    peerConnectionFactory.makeVideoSource(videoCapturer.isScreencast).apply {
      videoCapturer.initialize(surfaceTextureHelper, context, this.capturerObserver)
      videoCapturer.startCapture(resolution.width, resolution.height, 30)
    }
  }

  private val localVideoTrack: VideoTrack by lazy {
    peerConnectionFactory.makeVideoTrack(
      source = videoSource,
      trackId = "Video${UUID.randomUUID()}"
    )
  }

  /** Audio properties */

  private val audioHandler: AudioHandler by lazy {
    AudioSwitchHandler(context)
  }

  private val audioManager by lazy {
    context.getSystemService<AudioManager>()
  }

  private val audioConstraints: MediaConstraints by lazy {
    buildAudioConstraints()
  }

  private val audioSource by lazy {
    peerConnectionFactory.makeAudioSource(audioConstraints)
  }

  private val localAudioTrack: AudioTrack by lazy {
    peerConnectionFactory.makeAudioTrack(
      source = audioSource,
      trackId = "Audio${UUID.randomUUID()}"
    )
  }

  private var offer: String? = null

  init {
    sessionManagerScope.launch {
      signalingClient.signalingCommandFlow
        .collect { commandToValue ->
          when (commandToValue.first) {
            SignalingCommand.OFFER -> handleOffer(commandToValue.second)
            SignalingCommand.ANSWER -> handleAnswer(commandToValue.second)
            SignalingCommand.ICE -> handleIce(commandToValue.second)
            SignalingCommand.HANGUP -> handleHangup()
            else -> Unit
          }
        }
    }
  }

  override fun onSessionScreenReady() {
    if (isSessionActive) {
      logger.d { "Session is already active, ignoring onSessionScreenReady" }
      return
    }

    setupAudio()
    createNewPeerConnection()
    peerConnection?.connection?.addTrack(localVideoTrack)
    peerConnection?.connection?.addTrack(localAudioTrack)
    sessionManagerScope.launch {
      // sending local video track to show local video from start
      _localVideoTrackFlow.emit(localVideoTrack)
      if (offer != null) {
        sendAnswer()
      } else {
        sendOffer()
      }
      isSessionActive = true
    }
  }

  private fun createNewPeerConnection() {
    peerConnection = peerConnectionFactory.makePeerConnection(
      coroutineScope = sessionManagerScope,
      configuration = peerConnectionFactory.rtcConfig,
      type = StreamPeerType.SUBSCRIBER,
      mediaConstraints = mediaConstraints,
      onIceCandidateRequest = { iceCandidate, _ ->
        signalingClient.sendCommand(
          SignalingCommand.ICE,
          "${iceCandidate.sdpMid}$ICE_SEPARATOR${iceCandidate.sdpMLineIndex}$ICE_SEPARATOR${iceCandidate.sdp}"
        )
      },
      onVideoTrack = { rtpTransceiver ->
        val track = rtpTransceiver?.receiver?.track() ?: return@makePeerConnection
        if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
          val videoTrack = track as VideoTrack
          sessionManagerScope.launch {
            _remoteVideoTrackFlow.emit(videoTrack)
          }
        }
      }
    )
  }

  private fun cleanupPeerConnection() {
    peerConnection?.connection?.close()
    peerConnection = null
    offer = null
  }

  override fun flipCamera() {
    (videoCapturer as? Camera2Capturer)?.switchCamera(null)
  }

  override fun enableMicrophone(enabled: Boolean) {
    audioManager?.isMicrophoneMute = !enabled
  }

  override fun enableCamera(enabled: Boolean) {
    if (enabled) {
      videoCapturer.startCapture(resolution.width, resolution.height, 30)
    } else {
      videoCapturer.stopCapture()
    }
  }

  override fun disconnect() {

    sessionManagerScope.launch {
      signalingClient.sendCommand(SignalingCommand.HANGUP, "The client hung up the call!")
    }

    // dispose audio & video tracks.
    remoteVideoTrackFlow.replayCache.forEach { videoTrack ->
      videoTrack.dispose()
    }
    localVideoTrackFlow.replayCache.forEach { videoTrack ->
      videoTrack.dispose()
    }
    localAudioTrack.dispose()
    localVideoTrack.dispose()

    // dispose audio handler and video capturer.
    audioHandler.stop()
    videoCapturer.stopCapture()
    videoCapturer.dispose()

    // dispose peer connection
    cleanupPeerConnection()
    isSessionActive = false

    // dispose signaling clients and socket.
    signalingClient.dispose()
  }

  private suspend fun sendOffer() {
    val peerConnection = peerConnection ?: return
    val offer = peerConnection.createOffer().getOrThrow()
    val result = peerConnection.setLocalDescription(offer)
    result.onSuccess {
      signalingClient.sendCommand(SignalingCommand.OFFER, offer.description)
    }
    logger.d { "[SDP] send offer: ${offer.stringify()}" }
  }

  private suspend fun sendAnswer() {
    val peerConnection = peerConnection ?: return
    peerConnection.setRemoteDescription(
      SessionDescription(SessionDescription.Type.OFFER, offer)
    )
    val answer = peerConnection.createAnswer().getOrThrow()
    val result = peerConnection.setLocalDescription(answer)
    result.onSuccess {
      signalingClient.sendCommand(SignalingCommand.ANSWER, answer.description)
    }
    logger.d { "[SDP] send answer: ${answer.stringify()}" }
  }

  private fun handleOffer(sdp: String) {
    logger.d { "[SDP] handle offer: $sdp" }
    offer = sdp
  }

  private suspend fun handleAnswer(sdp: String) {
    logger.d { "[SDP] handle answer: $sdp" }
    peerConnection?.setRemoteDescription(
      SessionDescription(SessionDescription.Type.ANSWER, sdp)
    )
  }

  private suspend fun handleIce(iceMessage: String) {
    val iceArray = iceMessage.split(ICE_SEPARATOR)
    peerConnection?.addIceCandidate(
      IceCandidate(
        iceArray[0],
        iceArray[1].toInt(),
        iceArray[2]
      )
    )
  }

  private fun handleHangup() {
    sessionManagerScope.launch {
//      _callStateFlow.emit(CallState.HUNG_UP)
      disconnect()
    }
  }

  private fun buildCameraCapturer(): VideoCapturer {
    val manager = cameraManager ?: throw RuntimeException("CameraManager was not initialized!")

    val ids = manager.cameraIdList
    val cameraId = ids.firstOrNull { id ->
      val characteristics = manager.getCameraCharacteristics(id)
      characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
    } ?: ids.firstOrNull() ?: throw RuntimeException("No camera found!")

    return Camera2Capturer(context, cameraId, null)
  }

  private fun buildAudioConstraints(): MediaConstraints {
    val mediaConstraints = MediaConstraints()
    val items = listOf(
      MediaConstraints.KeyValuePair(
        "googEchoCancellation",
        true.toString()
      ),
      MediaConstraints.KeyValuePair(
        "googAutoGainControl",
        true.toString()
      ),
      MediaConstraints.KeyValuePair(
        "googHighpassFilter",
        true.toString()
      ),
      MediaConstraints.KeyValuePair(
        "googNoiseSuppression",
        true.toString()
      ),
      MediaConstraints.KeyValuePair(
        "googTypingNoiseDetection",
        true.toString()
      )
    )

    return mediaConstraints.apply {
      with(optional) {
        add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        addAll(items)
      }
    }
  }

  private fun setupAudio() {
    logger.d { "[setupAudio] #sfu; no args" }
    audioHandler.start()
    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val devices = audioManager?.availableCommunicationDevices ?: return
      val deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

      val device = devices.firstOrNull { it.type == deviceType } ?: return

      val isCommunicationDeviceSet = audioManager?.setCommunicationDevice(device)
      logger.d { "[setupAudio] #sfu; isCommunicationDeviceSet: $isCommunicationDeviceSet" }
    }
  }
}
