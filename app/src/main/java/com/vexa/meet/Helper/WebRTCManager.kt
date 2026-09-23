package com.vexa.meet.Helper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.vexa.meet.CallParticipant
import com.vexa.meet.ActiveCallSession
import com.vexa.meet.CallForegroundService
import com.vexa.meet.ui.RoundedTextureViewRenderer
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SdpObserver
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

class WebRTCManager(
    private val context: Context,
    private var floatingView: RoundedTextureViewRenderer,
    private var fullScreenView: RoundedTextureViewRenderer,
    private val roomId: String,
    private var listener: Listener? = null
) {

    interface Listener {
        fun onConnectionLabelChanged(label: String, reconnecting: Boolean) = Unit
        fun onCallDisconnected() = Unit
        fun onRoomStateChanged(status: String, participantCount: Int) = Unit
        fun onParticipantsChanged(participants: List<CallParticipant>) = Unit
        fun onLocalVideoTrackReady(track: VideoTrack) = Unit
        fun onRemoteVideoTrackReady(userId: String, track: VideoTrack) = Unit
        fun onRemoteVideoTrackRemoved(userId: String) = Unit
        fun onCallError(message: String) = Unit
    }

    private data class PeerState(
        var connection: PeerConnection? = null,
        var connectionRegistration: ListenerRegistration? = null,
        var candidateRegistration: ListenerRegistration? = null,
        var offerCreated: Boolean = false,
        var answerCreated: Boolean = false,
        var remoteOfferSet: Boolean = false,
        var remoteAnswerSet: Boolean = false,
        val pendingRemoteCandidates: MutableList<IceCandidate> = mutableListOf(),
        val localCandidateKeys: MutableSet<String> = mutableSetOf(),
        val remoteCandidateKeys: MutableSet<String> = mutableSetOf()
    )

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var videoCapturer: VideoCapturer
    private var localVideoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private val eglBase = EglBase.create()
    private val firestore = FirebaseFirestore.getInstance()
    private var roomRegistration: ListenerRegistration? = null
    private var participantsRegistration: ListenerRegistration? = null
    private var isCaller = false
    private var micEnabled = true
    private var cameraEnabled = true
    private var speakerEnabled = true
    private var remoteVideoTrack: VideoTrack? = null
    private val remoteVideoTracksByUser = mutableMapOf<String, VideoTrack>()
    private var isLocalFullScreen = false
    private var isFrontCamera = true
    private var initialized = false
    private var released = false
    private var disconnectRequested = false
    private var signalingListening = false
    private var hasSeenRoomDocument = false
    private var localParticipantUpserted = false
    private var roomEnded = false
    private var localParticipantLeaving = false
    private var hostId: String? = null
    private var primaryRemoteUserId: String? = null
    private val participantId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?: "participant_${Build.MODEL.hashCode()}"
    private val peerStates = mutableMapOf<String, PeerState>()
    private val participants = mutableMapOf<String, CallParticipant>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerState = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioRouteHandler = Handler(Looper.getMainLooper())

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            applyAudioRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            applyAudioRoute()
        }
    }

    fun initialize(isCaller: Boolean) {
        if (initialized && !released) return
        this.isCaller = isCaller
        initialized = true
        released = false
        disconnectRequested = false
        roomEnded = false
        localParticipantLeaving = false
        localParticipantUpserted = false
        setupAudio()
        initializePeerConnectionFactory()
        initializeSurfaceViews()
        startLocalMedia()
        if (isCaller) {
            createRoomDocument()
        }
        listenForRoomState()
        listenForParticipants()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun eglContext(): EglBase.Context = eglBase.eglBaseContext

    fun setMicEnabled(enabled: Boolean): Boolean {
        micEnabled = enabled
        audioTrack?.setEnabled(enabled)
        audioManager.isMicrophoneMute = !enabled
        return micEnabled
    }

    fun setCameraEnabled(enabled: Boolean): Boolean {
        cameraEnabled = enabled
        localVideoTrack?.setEnabled(enabled)
        return cameraEnabled
    }

    fun switchCamera() {
        val capturer = videoCapturer as? CameraVideoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                this@WebRTCManager.isFrontCamera = isFrontCamera
                applyRendererMirroring()
                Log.d("WebRTC", "Camera switched. Front camera: $isFrontCamera")
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.e("WebRTC", "Camera switch failed: $errorDescription")
            }
        })
    }

    fun setSpeakerEnabled(enabled: Boolean): Boolean {
        speakerEnabled = enabled
        applyAudioRoute(forceSpeaker = enabled)
        return speakerEnabled
    }

    fun isSpeakerEnabled(): Boolean = speakerEnabled

    fun swapVideoSurfaces(): Boolean {
        isLocalFullScreen = !isLocalFullScreen
        attachVideoTracks()
        return isLocalFullScreen
    }

    fun reattachRenderers(
        floatingView: RoundedTextureViewRenderer,
        fullScreenView: RoundedTextureViewRenderer
    ) {
        if (released) return
        localVideoTrack?.removeSink(this.floatingView)
        localVideoTrack?.removeSink(this.fullScreenView)
        remoteVideoTrack?.removeSink(this.floatingView)
        remoteVideoTrack?.removeSink(this.fullScreenView)
        this.floatingView = floatingView
        this.fullScreenView = fullScreenView
        initializeSurfaceViews()
    }

    fun isLocalFullScreen(): Boolean = isLocalFullScreen

    fun forceSpeakerAfterConnection() {
        if (!speakerEnabled) return
        applyAudioRoute(forceSpeaker = true)
        audioRouteHandler.removeCallbacksAndMessages(null)
        audioRouteHandler.postDelayed({ applyAudioRoute(forceSpeaker = true) }, 1000)
        audioRouteHandler.postDelayed({ applyAudioRoute(forceSpeaker = true) }, 2200)
    }

    fun getLocalVideoTrack(): VideoTrack? = localVideoTrack

    fun getRemoteVideoTrack(userId: String): VideoTrack? = remoteVideoTracksByUser[userId]

    fun getConnectedRemoteUserIds(): List<String> {
        return peerStates.keys.sorted()
    }

    fun getParticipantsSnapshot(): List<CallParticipant> {
        return participants.values.sortedBy { it.userId }
    }

    private fun setupAudio() {
        previousAudioMode = audioManager.mode
        previousSpeakerState = audioManager.isSpeakerphoneOn

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        applyAudioRoute(forceSpeaker = true)
    }

    private fun applyAudioRoute(forceSpeaker: Boolean = speakerEnabled) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val earpiece = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            val bluetooth = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            val wired = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }

            val target = when {
                forceSpeaker -> speaker
                bluetooth != null -> bluetooth
                wired != null -> wired
                else -> earpiece
            }

            if (target != null) {
                audioManager.setCommunicationDevice(target)
            } else {
                audioManager.clearCommunicationDevice()
            }
        }

        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = forceSpeaker

        if (!forceSpeaker && hasBluetoothDevice()) {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }

        Log.d("WebRTC", "Audio route applied. speaker=$forceSpeaker")
    }

    private fun hasBluetoothDevice(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    private fun initializeSurfaceViews() {
        floatingView.init(eglBase.eglBaseContext, 18f * context.resources.displayMetrics.density)
        floatingView.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        fullScreenView.init(eglBase.eglBaseContext, 0f)
        fullScreenView.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        attachVideoTracks()
    }

    private fun createRoomDocument() {
        val roomRef = firestore.collection("calls").document(roomId)
        roomRef.set(
            hashMapOf(
                "roomId" to roomId,
                "callMode" to "group",
                "status" to "ringing",
                "hostId" to participantId,
                "participantIds" to listOf(participantId),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                "ended" to false
            ),
            SetOptions.merge()
        )
    }

    private fun listenForRoomState() {
        if (signalingListening) return
        signalingListening = true

        roomRegistration = firestore.collection("calls")
            .document(roomId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("WebRTC", "Firestore room listener error", error)
                    return@addSnapshotListener
                }

                if (snapshot == null || !snapshot.exists()) {
                    if (hasSeenRoomDocument && !disconnectRequested) {
                        handleRoomEnded("Room ended")
                    } else if (!isCaller) {
                        listener?.onCallError("Room not found")
                        handleRoomEnded("Room not found")
                    }
                    return@addSnapshotListener
                }

                hasSeenRoomDocument = true
                hostId = snapshot.getString("hostId") ?: hostId
                val status = snapshot.getString("status") ?: "ringing"
                if (status == "ended" || snapshot.getBoolean("ended") == true) {
                    if (!disconnectRequested) {
                        handleRoomEnded("Room ended")
                    }
                    return@addSnapshotListener
                }

                if (!localParticipantUpserted) {
                    upsertLocalParticipant(status)
                }

                val activeParticipants = participants.values.count { it.active }
                listener?.onRoomStateChanged(status, activeParticipants)
                updateConnectionLabel()
            }
    }

    private fun listenForParticipants() {
        participantsRegistration = firestore.collection("calls")
            .document(roomId)
            .collection("participants")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("WebRTC", "Participant listener error", error)
                    return@addSnapshotListener
                }

                if (snapshot == null) return@addSnapshotListener

                snapshot.documentChanges.forEach { change ->
                    val participant = parseParticipant(change.document) ?: return@forEach
                    when (change.type) {
                        DocumentChange.Type.ADDED -> {
                            participants[participant.userId] = participant
                            Log.d("WebRTC", "participant joined: ${participant.userId}")
                        }
                        DocumentChange.Type.MODIFIED -> {
                            participants[participant.userId] = participant
                        }
                        DocumentChange.Type.REMOVED -> {
                            participants.remove(participant.userId)
                            Log.d("WebRTC", "participant left: ${participant.userId}")
                        }
                    }
                }

                syncParticipants()
            }
    }

    private fun parseParticipant(document: com.google.firebase.firestore.DocumentSnapshot): CallParticipant? {
        val userId = document.id.takeIf { it.isNotBlank() } ?: return null
        val status = document.getString("status") ?: "joined"
        val role = document.getString("role")
        val active = document.getBoolean("active") ?: status !in setOf("ended", "left")
        return CallParticipant(userId = userId, status = status, role = role, active = active)
    }

    private fun syncParticipants() {
        val activeParticipants = participants.values
            .filter { it.active }
            .sortedBy { it.userId }
        Log.d("WebRTC", "remaining participant count: ${activeParticipants.size}")

        listener?.onParticipantsChanged(activeParticipants)
        listener?.onRoomStateChanged(
            status = if (roomEnded) "ended" else (if (activeParticipants.size > 1) "active" else "ringing"),
            participantCount = activeParticipants.size
        )

        val activeRemoteIds = activeParticipants.map { it.userId }.filter { it != participantId }.toSet()

        activeRemoteIds.forEach { remoteUserId ->
            ensurePeerConnection(remoteUserId)
        }

        peerStates.keys.toList().forEach { remoteUserId ->
            if (remoteUserId !in activeRemoteIds) {
                removePeerConnection(remoteUserId, dueToRemoteLeave = true)
            }
        }

        if (activeRemoteIds.isNotEmpty()) {
            val preferredPrimary = activeRemoteIds.sorted().first()
            if (primaryRemoteUserId != preferredPrimary) {
                primaryRemoteUserId = preferredPrimary
                attachVideoTracks()
            }
        } else {
            primaryRemoteUserId = null
            remoteVideoTrack = null
            attachVideoTracks()
        }

        updateConnectionLabel()
    }

    private fun ensurePeerConnection(remoteUserId: String) {
        if (released || remoteUserId == participantId || peerStates.containsKey(remoteUserId)) return

        val peerState = PeerState()
        val connection = createPeerConnection(remoteUserId, peerState)
        peerState.connection = connection
        peerStates[remoteUserId] = peerState

        addLocalTracks(connection)
        listenForConnection(remoteUserId, peerState)
        listenForCandidates(remoteUserId, peerState)

        if (shouldInitiate(remoteUserId)) {
            createOffer(remoteUserId, peerState)
        }

        updateConnectionLabel()
    }

    private fun createPeerConnection(
        remoteUserId: String,
        peerState: PeerState
    ): PeerConnection {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }

        val constraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        return peerConnectionFactory.createPeerConnection(rtcConfig, constraints, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val candidateKey = candidate.key()
                if (!peerState.localCandidateKeys.add(candidateKey)) return
                val candidateData = hashMapOf(
                    "fromUserId" to participantId,
                    "toUserId" to remoteUserId,
                    "sdp" to candidate.sdp,
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "createdAt" to FieldValue.serverTimestamp()
                )

                firestore.collection("calls")
                    .document(roomId)
                    .collection("connections")
                    .document(connectionId(remoteUserId))
                    .collection("candidates")
                    .add(candidateData)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d("WebRTC", "ICE Connection State for $remoteUserId: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> updateConnectionLabel()
                    PeerConnection.IceConnectionState.CHECKING -> listener?.onConnectionLabelChanged("Connecting", false)
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> listener?.onConnectionLabelChanged("Reconnecting", true)
                    PeerConnection.IceConnectionState.CLOSED -> {
                        removePeerConnection(remoteUserId, dueToRemoteLeave = true)
                    }
                    else -> Unit
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                transceiver?.receiver?.track()?.let { track ->
                    if (track is VideoTrack) {
                        peerState.connection?.let {
                            remoteVideoTrack = track
                            remoteVideoTracksByUser[remoteUserId] = track
                            listener?.onRemoteVideoTrackReady(remoteUserId, track)
                            if (remoteUserId == primaryRemoteUserId || primaryRemoteUserId == null) {
                                attachVideoTracks()
                            }
                            updateConnectionLabel()
                        }
                    }
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { track ->
                    remoteVideoTrack = track
                    remoteVideoTracksByUser[remoteUserId] = track
                    listener?.onRemoteVideoTrackReady(remoteUserId, track)
                    if (remoteUserId == primaryRemoteUserId || primaryRemoteUserId == null) {
                        attachVideoTracks()
                    }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        }) ?: throw RuntimeException("Failed to create PeerConnection")
    }

    private fun listenForConnection(remoteUserId: String, peerState: PeerState) {
        val connectionRef = firestore.collection("calls")
            .document(roomId)
            .collection("connections")
            .document(connectionId(remoteUserId))

        peerState.connectionRegistration = connectionRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("WebRTC", "Connection listener error for $remoteUserId", error)
                return@addSnapshotListener
            }

            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

            val status = snapshot.getString("status").orEmpty()
            if (status == "ended") {
                removePeerConnection(remoteUserId, dueToRemoteLeave = true)
                return@addSnapshotListener
            }

            val offerSdp = snapshot.getString("offerSdp")
            val answerSdp = snapshot.getString("answerSdp")

            if (!shouldInitiate(remoteUserId) && !peerState.remoteOfferSet && !offerSdp.isNullOrBlank()) {
                val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
                peerState.connection?.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        peerState.remoteOfferSet = true
                        drainPendingRemoteCandidates(remoteUserId, peerState)
                        createAnswer(remoteUserId, peerState)
                    }
                }, sessionDesc)
            }

            if (shouldInitiate(remoteUserId) && !peerState.remoteAnswerSet && !answerSdp.isNullOrBlank()) {
                val sessionDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                peerState.connection?.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        peerState.remoteAnswerSet = true
                        drainPendingRemoteCandidates(remoteUserId, peerState)
                    }
                }, sessionDesc)
            }
        }
    }

    private fun listenForCandidates(remoteUserId: String, peerState: PeerState) {
        peerState.candidateRegistration = firestore.collection("calls")
            .document(roomId)
            .collection("connections")
            .document(connectionId(remoteUserId))
            .collection("candidates")
            .whereEqualTo("toUserId", participantId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("WebRTC", "ICE candidate listener error for $remoteUserId", error)
                    return@addSnapshotListener
                }

                snapshot?.documentChanges?.forEach { change ->
                    if (change.type != DocumentChange.Type.ADDED) return@forEach
                    val data = change.document
                    val candidate = IceCandidate(
                        data.getString("sdpMid") ?: "",
                        data.getLong("sdpMLineIndex")?.toInt() ?: 0,
                        data.getString("sdp") ?: ""
                    )
                    val candidateKey = candidate.key()
                    if (peerState.localCandidateKeys.contains(candidateKey)) return@forEach
                    if (!peerState.remoteCandidateKeys.add(candidateKey)) return@forEach
                    addOrQueueRemoteCandidate(remoteUserId, peerState, candidate)
                }
            }
    }

    private fun createOffer(remoteUserId: String, peerState: PeerState) {
        if (peerState.offerCreated || released || peerState.connection == null) return
        peerState.offerCreated = true
        val constraints = mediaConstraints()
        peerState.connection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerState.connection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        firestore.collection("calls")
                            .document(roomId)
                            .collection("connections")
                            .document(connectionId(remoteUserId))
                            .set(
                                hashMapOf(
                                    "roomId" to roomId,
                                    "status" to "offer_sent",
                                    "participants" to listOf(participantId, remoteUserId),
                                    "initiatorId" to participantId,
                                    "responderId" to remoteUserId,
                                    "offerFrom" to participantId,
                                    "offerTo" to remoteUserId,
                                    "offerSdp" to desc.description,
                                    "offerType" to desc.type.canonicalForm(),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                ),
                                SetOptions.merge()
                            )
                    }
                }, desc)
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                peerState.offerCreated = false
                Log.e("WebRTC", "Create offer failed for $remoteUserId: $error")
            }
            override fun onSetFailure(error: String?) = Unit
        }, constraints)
    }

    private fun createAnswer(remoteUserId: String, peerState: PeerState) {
        if (peerState.answerCreated || released || peerState.connection == null) return
        peerState.answerCreated = true
        val constraints = mediaConstraints()
        peerState.connection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerState.connection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        firestore.collection("calls")
                            .document(roomId)
                            .collection("connections")
                            .document(connectionId(remoteUserId))
                            .set(
                                hashMapOf(
                                    "roomId" to roomId,
                                    "status" to "joined",
                                    "participants" to listOf(participantId, remoteUserId),
                                    "answerFrom" to participantId,
                                    "answerTo" to remoteUserId,
                                    "answerSdp" to desc.description,
                                    "answerType" to desc.type.canonicalForm(),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                ),
                                SetOptions.merge()
                            )
                    }
                }, desc)
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                peerState.answerCreated = false
                Log.e("WebRTC", "Create answer failed for $remoteUserId: $error")
            }
            override fun onSetFailure(error: String?) = Unit
        }, constraints)
    }

    private fun mediaConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    }

    private fun createCameraCapturer(): VideoCapturer {
        if (Camera2Enumerator.isSupported(context)) {
            val enumerator = Camera2Enumerator(context)
            createCapturer(enumerator, frontFacing = true)?.let { return it }
            createCapturer(enumerator, frontFacing = false)?.let { return it }
        }

        val enumerator = Camera1Enumerator(false)
        createCapturer(enumerator, frontFacing = true)?.let { return it }
        createCapturer(enumerator, frontFacing = false)?.let { return it }
        throw IllegalStateException("No camera available")
    }

    private fun startLocalMedia() {
        videoCapturer = createCameraCapturer()
        videoSource = peerConnectionFactory.createVideoSource(false)
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        videoCapturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("VIDEO_TRACK", videoSource)
        listener?.onLocalVideoTrackReady(localVideoTrack!!)
        attachVideoTracks()

        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        audioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK", audioSource)

        peerStates.values.forEach { state -> addLocalTracks(state.connection) }
    }

    private fun addLocalTracks(connection: PeerConnection?) {
        if (connection == null) return
        localVideoTrack?.let { connection.addTrack(it) }
        audioTrack?.let { connection.addTrack(it) }
    }

    private fun attachVideoTracks() {
        localVideoTrack?.removeSink(floatingView)
        localVideoTrack?.removeSink(fullScreenView)
        remoteVideoTrack?.removeSink(floatingView)
        remoteVideoTrack?.removeSink(fullScreenView)

        if (isLocalFullScreen) {
            localVideoTrack?.addSink(fullScreenView)
            remoteVideoTrack?.addSink(floatingView)
        } else {
            remoteVideoTrack?.addSink(fullScreenView)
            localVideoTrack?.addSink(floatingView)
        }
        applyRendererMirroring()
    }

    private fun applyRendererMirroring() {
        if (isLocalFullScreen) {
            fullScreenView.setMirror(isFrontCamera)
            floatingView.setMirror(false)
        } else {
            fullScreenView.setMirror(false)
            floatingView.setMirror(isFrontCamera)
        }
    }

    private fun createCapturer(enumerator: Camera2Enumerator, frontFacing: Boolean): VideoCapturer? {
        return enumerator.deviceNames.firstOrNull {
            if (frontFacing) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        }?.let { enumerator.createCapturer(it, null) }
    }

    private fun createCapturer(enumerator: Camera1Enumerator, frontFacing: Boolean): VideoCapturer? {
        return enumerator.deviceNames.firstOrNull {
            if (frontFacing) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        }?.let { enumerator.createCapturer(it, null) }
    }

    fun disconnect(cleanupRoom: Boolean = true) {
        try {
            disconnectRequested = true
            localParticipantLeaving = true
            audioRouteHandler.removeCallbacksAndMessages(null)
            roomRegistration?.remove()
            participantsRegistration?.remove()
            roomRegistration = null
            participantsRegistration = null

            if (cleanupRoom) {
                leaveParticipant()
            } else {
                markParticipantInactive()
            }

            peerStates.keys.toList().forEach { remoteUserId ->
                removePeerConnection(remoteUserId, dueToRemoteLeave = false)
            }

            peerStates.clear()
            remoteVideoTrack = null
            remoteVideoTracksByUser.clear()

        } catch (e: Exception) {
            Log.e("WebRTC", "Error during disconnect", e)
        } finally {
            restoreAudio()
        }
    }

    fun release() {
        if (released) return
        released = true
        if (ActiveCallSession.manager === this) {
            ActiveCallSession.clear()
            CallForegroundService.stop(context)
        }
        try {
            disconnectRequested = true
            roomRegistration?.remove()
            participantsRegistration?.remove()
            roomRegistration = null
            participantsRegistration = null
            peerStates.values.forEach { state ->
                state.connectionRegistration?.remove()
                state.candidateRegistration?.remove()
                state.connection?.close()
                state.connection = null
            }
            peerStates.clear()
            remoteVideoTracksByUser.clear()
            if (::videoCapturer.isInitialized) {
                runCatching { videoCapturer.stopCapture() }
                videoCapturer.dispose()
            }
            localVideoTrack?.dispose()
            audioTrack?.dispose()
            videoSource?.dispose()
            audioSource?.dispose()
            floatingView.release()
            fullScreenView.release()
            peerConnectionFactory.dispose()
            audioDeviceModule?.release()
            eglBase.release()
        } catch (e: Exception) {
            Log.e("WebRTC", "Error during release", e)
        } finally {
            restoreAudio()
        }
    }

    private fun removePeerConnection(remoteUserId: String, dueToRemoteLeave: Boolean) {
        val state = peerStates.remove(remoteUserId) ?: return
        state.connectionRegistration?.remove()
        state.candidateRegistration?.remove()
        state.connection?.close()
        state.connection = null
        remoteVideoTracksByUser.remove(remoteUserId)
        listener?.onRemoteVideoTrackRemoved(remoteUserId)

        if (primaryRemoteUserId == remoteUserId) {
            primaryRemoteUserId = peerStates.keys.sorted().firstOrNull()
            remoteVideoTrack = primaryRemoteUserId?.let { remoteVideoTracksByUser[it] }
            attachVideoTracks()
        }

        if (dueToRemoteLeave && !roomEnded) {
            firestore.collection("calls")
                .document(roomId)
                .collection("connections")
                .document(connectionId(remoteUserId))
                .set(
                    mapOf(
                        "status" to "ended",
                        "endedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
        }

        updateConnectionLabel()
    }

    private fun leaveParticipant() {
        val activeParticipants = participants.values.filter { it.active }.map { it.userId }.toSet()
        val remainingParticipantCount = activeParticipants.filter { it != participantId }.size
        Log.d("WebRTC", "participant left: $participantId")
        Log.d("WebRTC", "remaining participant count: $remainingParticipantCount")

        if (remainingParticipantCount < 2) {
            Log.d("WebRTC", "ending room: remaining participant count $remainingParticipantCount")
            endRoomForAll()
        } else {
            Log.d("WebRTC", "removing only participant: $participantId")
            removeOnlyLocalParticipant()
        }
    }

    private fun endRoomForAll() {
        roomEnded = true
        markRoomEnded()
        cleanupRoomData()
    }

    private fun removeOnlyLocalParticipant() {
        roomEnded = false
        markParticipantInactive()
        cleanupLocalParticipantConnections()
    }

    private fun addOrQueueRemoteCandidate(
        remoteUserId: String,
        peerState: PeerState,
        candidate: IceCandidate
    ) {
        if (peerState.remoteOfferSet || peerState.remoteAnswerSet) {
            peerState.connection?.addIceCandidate(candidate)
        } else {
            peerState.pendingRemoteCandidates += candidate
        }
    }

    private fun drainPendingRemoteCandidates(remoteUserId: String, peerState: PeerState) {
        if (peerState.pendingRemoteCandidates.isEmpty()) return
        peerState.pendingRemoteCandidates.forEach { peerState.connection?.addIceCandidate(it) }
        peerState.pendingRemoteCandidates.clear()
    }

    private fun updateConnectionLabel() {
        if (roomEnded) {
            listener?.onConnectionLabelChanged("Disconnected", false)
            return
        }

        val states = peerStates.values.mapNotNull { it.connection?.iceConnectionState() }
        if (states.isEmpty()) {
            listener?.onConnectionLabelChanged("Connecting", false)
            return
        }

        when {
            states.any { it == PeerConnection.IceConnectionState.CHECKING } ->
                listener?.onConnectionLabelChanged("Connecting", false)
            states.any { it == PeerConnection.IceConnectionState.DISCONNECTED || it == PeerConnection.IceConnectionState.FAILED } ->
                listener?.onConnectionLabelChanged("Reconnecting", true)
            states.any { it == PeerConnection.IceConnectionState.CONNECTED || it == PeerConnection.IceConnectionState.COMPLETED } ->
                listener?.onConnectionLabelChanged("Connected", false)
            else -> listener?.onConnectionLabelChanged("Connecting", false)
        }
    }

    private fun shouldInitiate(remoteUserId: String): Boolean {
        return participantId < remoteUserId
    }

    private fun connectionId(remoteUserId: String): String {
        val ordered = listOf(participantId, remoteUserId).sorted()
        return ordered.joinToString("__")
    }

    private fun IceCandidate.key(): String {
        return "${sdpMid.orEmpty()}|$sdpMLineIndex|$sdp"
    }

    private fun upsertLocalParticipant(roomStatus: String) {
        if (localParticipantUpserted || roomEnded) return
        localParticipantUpserted = true
        val participantStatus = if (isCaller) "joined" else "accepted"
        firestore.collection("calls")
            .document(roomId)
            .collection("participants")
            .document(participantId)
            .set(
                hashMapOf(
                    "userId" to participantId,
                    "status" to participantStatus,
                    "active" to true,
                    "role" to if (isCaller) "host" else "participant",
                    "joinedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )

        firestore.collection("calls")
            .document(roomId)
            .set(
                hashMapOf(
                    "participantIds" to FieldValue.arrayUnion(participantId),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "status" to if (isCaller) "ringing" else roomStatus
                ),
                SetOptions.merge()
            )

        if (isCaller) {
            listener?.onRoomStateChanged("ringing", participants.values.count { it.active } + 1)
        }
    }

    private fun markParticipantInactive() {
        val participantRef = firestore.collection("calls")
            .document(roomId)
            .collection("participants")
            .document(participantId)

        participantRef.set(
            mapOf(
                "status" to "left",
                "active" to false,
                "leftAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).addOnSuccessListener {
            participantRef.delete()
        }

        firestore.collection("calls")
            .document(roomId)
            .set(
                mapOf(
                    "participantIds" to FieldValue.arrayRemove(participantId),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
    }

    private fun cleanupLocalParticipantConnections() {
        val roomRef = firestore.collection("calls").document(roomId)
        roomRef.collection("connections")
            .whereArrayContains("participants", participantId)
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.documents.forEach { connectionDoc ->
                    connectionDoc.reference
                        .set(
                            mapOf(
                                "status" to "ended",
                                "endedAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                    connectionDoc.reference.collection("candidates")
                        .get()
                        .addOnSuccessListener { candidates ->
                            val batch = firestore.batch()
                            candidates.documents.forEach { batch.delete(it.reference) }
                            batch.delete(connectionDoc.reference)
                            batch.commit()
                        }
                        .addOnFailureListener {
                            Log.e("WebRTC", "Failed to cleanup candidates for participant $participantId", it)
                            connectionDoc.reference.delete()
                        }
                }
            }
            .addOnFailureListener {
                Log.e("WebRTC", "Failed to cleanup participant connections for $participantId", it)
            }
    }

    private fun markRoomEnded() {
        firestore.collection("calls")
            .document(roomId)
            .set(
                mapOf(
                    "status" to "ended",
                    "ended" to true,
                    "endedAt" to FieldValue.serverTimestamp(),
                    "endedBy" to participantId,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
    }

    private fun handleRoomEnded(reason: String) {
        if (roomEnded) return
        roomEnded = true
        try {
            listener?.onConnectionLabelChanged("Disconnected", false)
            listener?.onCallError(reason)
            listener?.onCallDisconnected()
            peerStates.keys.toList().forEach { removePeerConnection(it, dueToRemoteLeave = true) }
            participantsRegistration?.remove()
            roomRegistration?.remove()
            participantsRegistration = null
            roomRegistration = null
        } finally {
            // The call can end while its Activity is gone. Release the session and
            // notification even when there is no UI listener to run endCall().
            if (ActiveCallSession.manager === this) release()
        }
    }

    private fun cleanupRoomData() {
        val roomRef = firestore.collection("calls").document(roomId)
        val collections = listOf(
            "participants",
            "connections",
            "reactions",
            "callerCandidates",
            "calleeCandidates",
            "candidates"
        )

        fun deleteCollection(index: Int) {
            if (index >= collections.size) {
                roomRef.delete()
                return
            }

            val collectionName = collections[index]
            roomRef.collection(collectionName)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.isEmpty) {
                        deleteCollection(index + 1)
                        return@addOnSuccessListener
                    }

                    val batch = firestore.batch()
                    snapshot.documents.forEach { document ->
                        batch.delete(document.reference)
                    }
                    batch.commit()
                        .addOnCompleteListener { deleteCollection(index + 1) }
                }
                .addOnFailureListener {
                    Log.e("WebRTC", "Failed to cleanup $collectionName for room $roomId", it)
                    deleteCollection(index + 1)
                }
        }

        deleteConnectionCandidates(roomRef)
        deleteCollection(0)
    }

    private fun deleteConnectionCandidates(roomRef: com.google.firebase.firestore.DocumentReference) {
        roomRef.collection("connections")
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.documents.forEach { connectionDoc ->
                    connectionDoc.reference.collection("candidates")
                        .get()
                        .addOnSuccessListener { candidates ->
                            val batch = firestore.batch()
                            candidates.documents.forEach { batch.delete(it.reference) }
                            batch.commit()
                        }
                }
            }
    }

    private fun restoreAudio() {
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
        audioManager.isSpeakerphoneOn = previousSpeakerState
        audioManager.mode = previousAudioMode
        audioManager.isMicrophoneMute = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.e("WebRTC", "SDP create failed: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e("WebRTC", "SDP set failed: $error")
        }
    }
}
