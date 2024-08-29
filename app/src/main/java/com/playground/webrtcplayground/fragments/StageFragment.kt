package com.playground.webrtcplayground.fragments

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.playground.webrtcplayground.MainViewModel
import com.playground.webrtcplayground.R
import com.playground.webrtcplayground.databinding.FragmentStageBinding
import com.playground.webrtcplayground.webrtc.WebRTCSessionState


class StageFragment : Fragment() {

    private lateinit var binding : FragmentStageBinding
    private val viewModel : MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        binding = FragmentStageBinding.inflate(layoutInflater, container, false)

        viewModel.sessionState.observe(viewLifecycleOwner) { state ->

            val callBtnText = when(state) {
                WebRTCSessionState.Active -> {
                    viewModel.updateVideoCallReady(true)
                    "Session active"
                }
                WebRTCSessionState.Creating -> {
                    viewModel.updateVideoCallReady(true)
                    "Creating session"
                }
                WebRTCSessionState.Ready -> {
                    viewModel.updateVideoCallReady(true)
                    "Ready to start session"
                }
                WebRTCSessionState.Impossible -> {
                    viewModel.updateVideoCallReady(false)
                    "Second peer is missing"
                }
                WebRTCSessionState.Offline -> {
                    viewModel.updateVideoCallReady(false)
                    "Start session"
                }
                else -> {
                    viewModel.updateVideoCallReady(false)
                    "Trying to connect..."
                }
            }

            binding.btnStartCall.text = callBtnText

        }

        viewModel.isVideoCallReady.observe(viewLifecycleOwner) {
            binding.btnStartCall.isEnabled = it

            if(it) {
                binding.btnStartCall.setOnClickListener {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, VideoCallFragment.newInstance())
                        .addToBackStack(null)
                        .commit()
                }
            }else{
                binding.btnStartCall.setOnClickListener {}
            }
        }


        return binding.root

    }

    companion object {
        fun newInstance() = StageFragment()
    }

}