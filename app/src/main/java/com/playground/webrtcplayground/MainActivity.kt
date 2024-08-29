package com.playground.webrtcplayground

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.playground.webrtcplayground.databinding.ActivityMainBinding
import com.playground.webrtcplayground.fragments.StageFragment
import com.playground.webrtcplayground.fragments.VideoCallFragment
import com.playground.webrtcplayground.webrtc.SignalingClient
import com.playground.webrtcplayground.webrtc.peer.StreamPeerConnectionFactory
import com.playground.webrtcplayground.webrtc.sessions.WebRtcSessionManager
import com.playground.webrtcplayground.webrtc.sessions.WebRtcSessionManagerImpl
import io.getstream.log.taggedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {

    lateinit var sessionManager: WebRtcSessionManager
    private lateinit var binding : ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val REQUEST_CODE = 100

    val requiredPermissions = arrayOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_CODE)


        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initializeSessionManager()

        setupObservers()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, StageFragment.newInstance())
                .commit()
        }

    }

    private fun setupObservers() {
        viewModel.isVideoCallReady.observe(this) { isVideoCallStarted ->
            if (isVideoCallStarted) {
                navigateToVideoCall()
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            observeState()
        }
    }

    private fun initializeSessionManager() {
        sessionManager = WebRtcSessionManagerImpl(
            context = this,
            signalingClient = SignalingClient(),
            peerConnectionFactory = StreamPeerConnectionFactory(this)
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // All required permissions are granted
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.CAMERA) &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.RECORD_AUDIO)) {
                    AlertDialog.Builder(this)
                        .setTitle("Permissions Required")
                        .setMessage("Camera and Microphone permissions are required for this app. Please grant them in settings.")
                        .setPositiveButton("Open Settings") { _, _ ->
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            startActivity(intent)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }else{
                    Toast.makeText(this, "Permission required for video calling", Toast.LENGTH_SHORT).show()
                    ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_CODE)
                }

            }
        }
    }


    private suspend fun observeState() {
        sessionManager.signalingClient.sessionStateFlow.collect() {
            Log.e("MainActivity", "observeState: ${it.name}", )
            viewModel.updateSessionState(it)
        }
    }

    private fun navigateToVideoCall() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, VideoCallFragment.newInstance())
            .addToBackStack(null)
            .commit()
    }

    fun reInitializeSessionManager() {
        sessionManager.signalingClient.dispose()
        initializeSessionManager()
        setupObservers()
    }

    fun navigateToStageScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, StageFragment.newInstance())
            .addToBackStack(null)
            .commit()
    }
    override fun onDestroy() {
        super.onDestroy()
        sessionManager.signalingClient.dispose()

    }

}