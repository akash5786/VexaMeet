package com.vexa.meet

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.PictureInPictureParams
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.vexa.meet.Helper.WebRTCManager
import com.vexa.meet.databinding.ActivityMeetingBinding
import com.vexa.meet.ui.CallViewModel
import com.vexa.meet.ui.MeetingEntryScreen
import com.vexa.meet.ui.MeetingEntryScreens
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import kotlin.math.max
import kotlin.math.min
import java.util.UUID
import kotlin.random.Random

class MeetingActivity : AppCompatActivity(), WebRTCManager.Listener, ActiveCallActions.Listener {

    private lateinit var binding: ActivityMeetingBinding
    private val viewModel: CallViewModel by viewModels()

    private var webRTCManager: WebRTCManager? = null
    private var isInCall = false
    private var currentRoomId = ""
    private var controlsVisible = true
    private var remoteZoomed = true
    private var micEnabled = true
    private var cameraEnabled = true
    private var speakerEnabled = true
    private var localPreviewLarge = false
    private var isLocalVideoFullScreen = false
    private var showingSoloVideo = false
    private var previewTransitionRunning = false
    private var previewTransitionGeneration = 0
    private var isGroupGridMode = false
    private var isEndingCall = false
    private var pendingInviteRoomId: String? = null
    private var pendingStartMeeting = false
    private var entryScreen by mutableStateOf(MeetingEntryScreen.HOME)
    private var entryRoomId by mutableStateOf("")
    private var entryError by mutableStateOf<String?>(null)
    private var entryBusy by mutableStateOf(false)
    private var joinAttempt = 0
    private var permissionRequestInFlight = false
    private var forceUpdateDialogShowing = false
    private var updateCheckInFlight by mutableStateOf(false)
    private var updateRequired by mutableStateOf(false)
    private val firestore = FirebaseFirestore.getInstance()
    private var reactionRegistration: ListenerRegistration? = null
    private var reactionListenerStartedAt = 0L
    private val seenReactionIds = mutableSetOf<String>()
    private val favoriteReactions = listOf("\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDD25", "\uD83D\uDC4D", "\uD83D\uDE0D")
    private val fullEmojiPicker = arrayOf(
        "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDD25", "\uD83D\uDC4D", "\uD83D\uDE0D", "\uD83D\uDC4F",
        "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83E\uDD73", "\uD83D\uDE4C", "\uD83D\uDC99", "\uD83D\uDC9A",
        "\uD83D\uDC9C", "\uD83D\uDCAF", "\u2B50", "\uD83C\uDF89", "\uD83D\uDE0E", "\uD83E\uDD29",
        "\uD83D\uDE0A", "\uD83D\uDE09", "\uD83E\uDD17", "\uD83E\uDD70", "\uD83D\uDE31", "\uD83E\uDD14",
        "\uD83D\uDE4F", "\u270C\uFE0F", "\uD83E\uDD1D", "\uD83D\uDCAA", "\uD83D\uDC51", "\u2728"
    )
    private var localVideoTrack: VideoTrack? = null
    private val remoteVideoTracks = mutableMapOf<String, VideoTrack>()
    private val remoteVideoViews = mutableMapOf<String, com.vexa.meet.ui.RoundedTextureViewRenderer>()
    private var groupLocalRenderer: com.vexa.meet.ui.RoundedTextureViewRenderer? = null
    private var currentParticipantsSnapshot = emptyList<CallParticipant>()
    private var lastSystemTopInset = 0
    private var lastSystemBottomInset = 0
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable {
        if (isInCall && controlsVisible && !binding.etReactionText.hasFocus() && !isInPictureInPictureModeCompat()) {
            setControlsVisible(false)
        }
    }
    private val fullScreenTimeoutHandler = Handler(Looper.getMainLooper())
    private val fullScreenTimeoutRunnable = Runnable {
        if (isInCall && !controlsVisible && !isInPictureInPictureModeCompat()) {
            setControlsVisible(true)
        }
    }

    private lateinit var gestureDetector: GestureDetector

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRequestInFlight = false
        if (hasCallPermissions()) {
            performPendingMeetingAction()
        } else {
            pendingStartMeeting = false
            pendingInviteRoomId = null
            entryBusy = false
            Toast.makeText(this, "Camera and audio permissions are required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        entryScreen = if (savedInstanceState?.getBoolean("entry_join") == true) MeetingEntryScreen.JOIN else MeetingEntryScreen.HOME
        entryRoomId = savedInstanceState?.getString("entry_room_id").orEmpty()
        pendingStartMeeting = savedInstanceState?.getBoolean("pending_start") ?: false
        permissionRequestInFlight = savedInstanceState?.getBoolean("permission_request") ?: false
        pendingInviteRoomId = if (savedInstanceState != null) savedInstanceState.getString("pending_invite") else extractRoomIdFromIntent(intent)
        startApp()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("entry_join", entryScreen == MeetingEntryScreen.JOIN)
        outState.putString("entry_room_id", entryRoomId)
        outState.putBoolean("pending_start", pendingStartMeeting)
        outState.putString("pending_invite", pendingInviteRoomId)
        outState.putBoolean("permission_request", permissionRequestInFlight)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInviteIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        if (::binding.isInitialized && !isInCall) {
            if (entryScreen == MeetingEntryScreen.JOIN) autoFillRoomIdFromClipboard()
            checkForAppUpdate()
            if ((pendingInviteRoomId != null || pendingStartMeeting) && !permissionRequestInFlight) checkPermissions()
        }
        if (isInCall && !isInPictureInPictureModeCompat()) {
            webRTCManager?.onCallForegrounded()
            webRTCManager?.reattachRenderers(binding.localView, binding.remoteView)
            setControlsVisible(true)
            enterImmersiveMode()
            renderCallLayout()
        }
    }

    private fun hasCallPermissions(): Boolean {
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        return hasCamera && hasAudio
    }

    private fun checkPermissions() {
        if (updateCheckInFlight || updateRequired) return
        if (permissionRequestInFlight) return
        if (hasCallPermissions()) {
            performPendingMeetingAction()
        } else {
            val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
            permissionRequestInFlight = true
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startApp() {
        binding = ActivityMeetingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvParticipantName.text = SpannableString(getString(R.string.app_name)).apply {
            setSpan(ForegroundColorSpan(Color.WHITE), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@MeetingActivity, R.color.brand_wordmark_blue)),
                4, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        binding.remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        binding.localPreviewContainer.outlineProvider = ViewOutlineProvider.BACKGROUND
        binding.localPreviewContainer.clipToOutline = true
        binding.localPreviewContainer.setOnClickListener {
            if (isInCall) swapVideos()
        }
        binding.groupVideoGrid.isVisible = false
        binding.callControls.isVisible = false
        binding.btnCopyRoomId.isVisible = false
        updateParticipants(count = 0)
        setupEntryScreens()

        applyInsets()
        setupObservers()
        setupGestures()
        setupButtons()
        setupReactionControls()
        setupLocalPreviewDrag()
        setupBackHandling()
        ActiveCallActions.listener = this
        checkForAppUpdate()
        if (!restoreActiveCallIfNeeded()) {
            showEntryScreen(entryScreen)
            if (pendingInviteRoomId != null || pendingStartMeeting) {
                entryBusy = true
                checkPermissions()
            }
        } else {
            pendingStartMeeting = false
            consumePendingInvite()
        }
    }

    private fun setupEntryScreens() {
        binding.entryScreens.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.entryScreens.setContent {
            MeetingEntryScreens(
                screen = entryScreen,
                roomId = entryRoomId,
                error = entryError,
                busy = entryBusy || updateCheckInFlight || updateRequired,
                onRoomIdChange = { entryRoomId = it; entryError = null },
                onShowJoin = {
                    showEntryScreen(MeetingEntryScreen.JOIN)
                    autoFillRoomIdFromClipboard()
                },
                onBack = {
                    cancelPendingJoin()
                    showEntryScreen(MeetingEntryScreen.HOME)
                },
                onStart = {
                    if (!entryBusy) {
                        pendingInviteRoomId = null
                        pendingStartMeeting = true
                        entryBusy = true
                        checkPermissions()
                    }
                },
                onJoin = ::requestJoinFromEntry
            )
        }
    }

    private fun requestJoinFromEntry() {
        if (entryBusy) return
        val roomId = entryRoomId.trim()
        entryError = when {
            roomId.isEmpty() -> getString(R.string.room_id_required)
            !isValidRoomId(roomId) -> getString(R.string.room_id_invalid)
            else -> null
        }
        if (entryError != null) return
        entryRoomId = roomId
        pendingInviteRoomId = roomId
        pendingStartMeeting = false
        entryBusy = true
        checkPermissions()
    }

    private fun performPendingMeetingAction() {
        if (updateCheckInFlight || updateRequired) return
        if (pendingStartMeeting) {
            pendingStartMeeting = false
            startCallAsCaller()
        } else {
            consumePendingInvite()
        }
    }

    private fun cancelPendingJoin() {
        joinAttempt++
        pendingInviteRoomId = null
        pendingStartMeeting = false
        entryBusy = false
        entryError = null
    }

    private fun showEntryScreen(screen: MeetingEntryScreen) {
        entryScreen = screen
        entryError = null
        controlsVisible = true
        binding.entryScreens.isVisible = true
        binding.root.keepScreenOn = false
        binding.root.setBackgroundColor(Color.rgb(8, 13, 21))
        binding.root.children.filter { it !== binding.entryScreens }.forEach {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        exitImmersiveMode()
        val controller = WindowCompat.getInsetsController(window, binding.root)
        if (screen == MeetingEntryScreen.HOME) {
            binding.entryScreens.clearFocus()
            controller.hide(WindowInsetsCompat.Type.ime())
        }
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun hideEntryScreens() {
        entryBusy = false
        entryError = null
        binding.entryScreens.clearFocus()
        WindowCompat.getInsetsController(window, binding.root).hide(WindowInsetsCompat.Type.ime())
        binding.entryScreens.isVisible = false
        binding.root.keepScreenOn = true
        binding.root.setBackgroundColor(ContextCompat.getColor(this, R.color.video_background))
        binding.root.children.filter { it !== binding.entryScreens }.forEach {
            it.importantForAccessibility = if (it === binding.reactionLayer) {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
        }
    }

    private fun applyInsets() {
        val topBarStartTop = binding.topBar.paddingTop
        val controlsBottomMargin = (binding.callControls.layoutParams as ConstraintLayout.LayoutParams).bottomMargin
        val reactionBottomMargin = (binding.reactionPanel.layoutParams as ConstraintLayout.LayoutParams).bottomMargin
        val previewEndMargin = (binding.localPreviewContainer.layoutParams as ConstraintLayout.LayoutParams).rightMargin
        val previewBottomMargin = (binding.localPreviewContainer.layoutParams as ConstraintLayout.LayoutParams).bottomMargin

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val keyboardLift = if (imeVisible) max(0, ime.bottom - bars.bottom) else 0
            lastSystemTopInset = bars.top
            lastSystemBottomInset = bars.bottom
            binding.root.updatePadding(
                top = if (controlsVisible) lastSystemTopInset else 0,
                bottom = if (controlsVisible && !imeVisible) lastSystemBottomInset else 0
            )
            binding.topBar.updatePadding(top = topBarStartTop)
            binding.callControls.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = controlsBottomMargin + keyboardLift
            }
            binding.entryScreens.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = keyboardLift
            }
            binding.reactionPanel.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = reactionBottomMargin
            }
            binding.localPreviewContainer.updateLayoutParams<ConstraintLayout.LayoutParams> {
                rightMargin = previewEndMargin + bars.right
                bottomMargin = previewBottomMargin + if (imeVisible) 0 else bars.bottom
            }
            insets
        }
    }

    private fun setupObservers() {
        viewModel.callTimer.observe(this) { binding.tvCallTimer.text = it }
        viewModel.networkQuality.observe(this) { binding.tvNetworkQuality.text = it }
        viewModel.reconnecting.observe(this) { visible ->
            binding.reconnectingPanel.fadeVisible(visible)
        }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleFullScreen(e)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleRemoteZoom()
                return true
            }
        })

        listOf(
            binding.videoTapLayer,
            binding.remoteView,
            binding.remoteScrim,
            binding.groupVideoGrid
        ).forEach { videoContainer ->
            attachVideoTapTarget(videoContainer)
        }
        listOf(
            binding.topBar,
            binding.localPreviewContainer,
            binding.reactionPanel,
            binding.callControls
        ).forEach { panel ->
            panel.isClickable = true
            panel.setOnTouchListener { view, _ ->
                view.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }

    private fun setupButtons() {
        binding.btnCopyRoomId.setOnClickListener {
            animateClick(it)
            shareCurrentRoom()
        }

        binding.btnDisconnect.setOnClickListener {
            animateClick(it)
            endCall()
        }

        binding.btnMic.setOnClickListener {
            animateClick(it)
            setMicEnabled(!micEnabled)
        }

        binding.btnCamera.setOnClickListener {
            animateClick(it)
            cameraEnabled = !cameraEnabled
            ActiveCallSession.cameraEnabled = cameraEnabled
            webRTCManager?.setCameraEnabled(cameraEnabled)
            binding.btnCamera.setImageResource(if (cameraEnabled) R.drawable.ic_videocam else R.drawable.ic_videocam_off)
            updateCameraDisabledUi()
            binding.btnCamera.contentDescription = if (cameraEnabled) "Turn camera off" else "Turn camera on"
        }

        binding.btnSwitchCamera.setOnClickListener {
            animateClick(it)
            webRTCManager?.switchCamera()
        }

        binding.btnSpeaker.setOnClickListener {
            animateClick(it)
            speakerEnabled = !(webRTCManager?.isSpeakerEnabled() ?: speakerEnabled)
            speakerEnabled = webRTCManager?.setSpeakerEnabled(speakerEnabled) ?: speakerEnabled
            ActiveCallSession.speakerEnabled = speakerEnabled
            binding.btnSpeaker.setImageResource(if (speakerEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
            binding.btnSpeaker.contentDescription = if (speakerEnabled) "Use earpiece" else "Use speaker"
        }
    }

    private fun setupReactionControls() {
        binding.quickReactionRow.removeAllViews()
        favoriteReactions.forEach { reaction ->
            val button = TextView(this).apply {
                text = reaction
                textSize = 24f
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                contentDescription = "Send reaction $reaction"
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setControlTouchBoundary()
                setOnClickListener {
                    animateClick(this)
                    sendReaction(reaction, isText = false)
                }
            }
            binding.quickReactionRow.addView(button)
        }

        val pickerButton = TextView(this).apply {
            text = "+"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            contentDescription = "Open emoji picker"
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginStart = dp(4)
                marginEnd = dp(2)
            }
            setControlTouchBoundary()
            setOnClickListener {
                animateClick(this)
                showEmojiPicker()
            }
        }
        binding.quickReactionRow.addView(pickerButton)

        binding.quickReactionRow.setControlTouchBoundary()
        binding.reactionPanel.setControlTouchBoundary()
        binding.etReactionText.setControlTouchBoundary()
        binding.btnSendReactionText.setControlTouchBoundary()
        binding.btnSendReactionText.setOnClickListener {
            animateClick(it)
            val text = binding.etReactionText.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                sendReaction(text.take(32), isText = true)
                binding.etReactionText.text?.clear()
            }
        }
        binding.etReactionText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                setControlsVisible(true)
                autoHideHandler.removeCallbacks(autoHideRunnable)
            } else {
                scheduleControlsAutoHide()
            }
        }
    }

    private fun showEmojiPicker() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Send reaction")
            .setItems(fullEmojiPicker) { dialog, which ->
                sendReaction(fullEmojiPicker[which], isText = false)
                dialog.dismiss()
            }
            .show()
    }

    private fun startReactionSync() {
        if (currentRoomId.isBlank() || reactionRegistration != null) return

        reactionListenerStartedAt = System.currentTimeMillis() - 2000L
        seenReactionIds.clear()
        cleanOldReactions()

        reactionRegistration = firestore.collection("calls")
            .document(currentRoomId)
            .collection("reactions")
            .whereGreaterThan("createdAtMs", reactionListenerStartedAt)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                snapshot.documentChanges.forEach { change ->
                    if (change.type != DocumentChange.Type.ADDED) return@forEach
                    val document = change.document
                    if (!seenReactionIds.add(document.id)) return@forEach

                    val value = document.getString("value")?.takeIf { it.isNotBlank() } ?: return@forEach
                    val isText = document.getBoolean("text") ?: false
                    showFloatingReaction(value, isText)
                }
            }
    }

    private fun stopReactionSync() {
        reactionRegistration?.remove()
        reactionRegistration = null
        reactionListenerStartedAt = 0L
        seenReactionIds.clear()
        binding.reactionLayer.removeAllViews()
    }

    private fun sendReaction(value: String, isText: Boolean) {
        if (!isInCall || currentRoomId.isBlank()) return

        val reactionId = UUID.randomUUID().toString()
        seenReactionIds.add(reactionId)
        showFloatingReaction(value, isText)

        val data = hashMapOf(
            "value" to value,
            "text" to isText,
            "senderId" to senderId(),
            "roomId" to currentRoomId,
            "createdAtMs" to System.currentTimeMillis(),
            "timestamp" to FieldValue.serverTimestamp()
        )

        firestore.collection("calls")
            .document(currentRoomId)
            .collection("reactions")
            .document(reactionId)
            .set(data)

        cleanOldReactions()
    }

    private fun cleanOldReactions() {
        if (currentRoomId.isBlank()) return
        val cutoff = System.currentTimeMillis() - REACTION_TTL_MS
        firestore.collection("calls")
            .document(currentRoomId)
            .collection("reactions")
            .whereLessThan("createdAtMs", cutoff)
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.documents.forEach { it.reference.delete() }
            }
    }

    private fun showFloatingReaction(value: String, isText: Boolean) {
        val copies = if (isText) Random.nextInt(4, 7) else Random.nextInt(8, 11)
        repeat(copies) { index ->
            binding.reactionLayer.postDelayed({
                binding.reactionLayer.post {
                    addFloatingReactionView(value, isText)
                }
            }, (index * Random.nextLong(45L, 95L)))
        }
    }

    private fun addFloatingReactionView(value: String, isText: Boolean) {
        val layer = binding.reactionLayer
        val overlayWidth = layer.width
        val overlayHeight = layer.height
        if (overlayWidth == 0 || overlayHeight == 0) return

        val reactionView = TextView(this).apply {
            text = value
            gravity = android.view.Gravity.CENTER
            includeFontPadding = false
            alpha = 0f
            scaleX = 0.4f
            scaleY = 0.4f
            maxLines = 1
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 3f, Color.argb(130, 0, 0, 0))
            textSize = if (isText) Random.nextInt(22, 35).toFloat() else Random.nextInt(34, 59).toFloat()
            if (isText) {
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(Color.argb(172, 16, 20, 28))
                    setStroke(dp(1), Color.argb(85, 255, 255, 255))
                }
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
        }

        val width = if (isText) FrameLayout.LayoutParams.WRAP_CONTENT else dp(86)
        val height = if (isText) FrameLayout.LayoutParams.WRAP_CONTENT else dp(86)
        val params = FrameLayout.LayoutParams(width, height)
        layer.addView(reactionView, params)

        reactionView.measure(
            View.MeasureSpec.makeMeasureSpec(overlayWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(overlayHeight, View.MeasureSpec.AT_MOST)
        )

        val measuredWidth = max(reactionView.measuredWidth, dp(48))
        val measuredHeight = max(reactionView.measuredHeight, dp(48))
        val startCenterX = Random.nextInt((overlayWidth * 0.10f).toInt(), (overlayWidth * 0.90f).toInt())
        val startCenterY = Random.nextInt((overlayHeight * 0.55f).toInt(), (overlayHeight * 0.90f).toInt())
        val endCenterY = Random.nextInt((overlayHeight * 0.05f).toInt(), (overlayHeight * 0.35f).toInt())
        val endCenterX = (startCenterX + Random.nextInt(-dp(96), dp(96))).coerceIn(
            (overlayWidth * 0.06f).toInt(),
            (overlayWidth * 0.94f).toInt()
        )

        val startX = (startCenterX - measuredWidth / 2f).coerceIn(0f, (overlayWidth - measuredWidth).toFloat())
        val startY = (startCenterY - measuredHeight / 2f).coerceIn(0f, (overlayHeight - measuredHeight).toFloat())
        val endX = (endCenterX - measuredWidth / 2f).coerceIn(0f, (overlayWidth - measuredWidth).toFloat())
        val endY = (endCenterY - measuredHeight / 2f).coerceIn(0f, (overlayHeight - measuredHeight).toFloat())
        val curveOffset = Random.nextInt(-dp(180), dp(180)).toFloat()
        val secondCurveOffset = Random.nextInt(-dp(120), dp(120)).toFloat()
        val rotationStart = Random.nextInt(-20, 21).toFloat()
        val rotationEnd = Random.nextInt(-20, 21).toFloat()
        val duration = Random.nextLong(1800L, 3001L)

        reactionView.x = startX
        reactionView.y = startY
        reactionView.rotation = rotationStart

        val path = Path().apply {
            moveTo(startX, startY)
            cubicTo(
                (startX + curveOffset).coerceIn(0f, overlayWidth.toFloat()),
                startY - (overlayHeight * 0.20f),
                (endX + secondCurveOffset).coerceIn(0f, overlayWidth.toFloat()),
                startY - (overlayHeight * 0.42f),
                endX,
                endY
            )
        }

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(reactionView, View.X, View.Y, path),
                ObjectAnimator.ofFloat(reactionView, View.ALPHA, 0f, 1f, 1f, 0f),
                ObjectAnimator.ofFloat(reactionView, View.SCALE_X, 0.4f, 1.3f, 1.05f, 0.9f),
                ObjectAnimator.ofFloat(reactionView, View.SCALE_Y, 0.4f, 1.3f, 1.05f, 0.9f),
                ObjectAnimator.ofFloat(reactionView, View.ROTATION, rotationStart, rotationEnd)
            )
            this.duration = duration
            interpolator = DecelerateInterpolator(1.35f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    layer.removeView(reactionView)
                }
            })
            start()
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isInCall) {
                    minimizeActiveCall()
                } else if (entryScreen == MeetingEntryScreen.JOIN) {
                    cancelPendingJoin()
                    showEntryScreen(MeetingEntryScreen.HOME)
                } else {
                    cancelPendingJoin()
                    moveTaskToBack(true)
                }
            }
        })
    }

    private fun startCallAsCaller() {
        if (isInCall) return
        currentRoomId = generateRoomId()
        confirmCurrentRoomInBackground(status = "Invite ready")
        startCall(isCaller = true)
    }

    private fun confirmCurrentRoomInBackground(status: String) {
        if (currentRoomId.isBlank()) return
        binding.btnCopyRoomId.isVisible = true
        binding.tvRoomStatus.text = status
    }

    private fun blockStartDuringAnotherCall(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val anotherCall = ActiveCallSession.isActive || audioManager.mode in setOf(
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION
        )
        if (!anotherCall) return false
        cancelPendingJoin()
        val message = getString(R.string.already_in_another_call)
        entryError = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        return true
    }

    private fun startCall(isCaller: Boolean) {
        if (isInCall) return
        if (ActiveCallSession.isActive && ActiveCallSession.roomId == currentRoomId) {
            restoreActiveCallIfNeeded()
            return
        }
        if (blockStartDuringAnotherCall()) return
        if (!isCaller) {
            val attempt = ++joinAttempt
            entryBusy = true
            validateRoomBeforeJoining { roomReady ->
                if (attempt != joinAttempt || isFinishing || isDestroyed) return@validateRoomBeforeJoining
                if (roomReady) {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        startCallInternal(isCaller)
                    } else {
                        // Revalidate on return instead of starting camera capture in the background.
                        pendingInviteRoomId = currentRoomId
                    }
                } else {
                    resetJoinAttempt()
                }
            }
            return
        }
        startCallInternal(isCaller)
    }

    private fun startCallInternal(isCaller: Boolean) {
        // Room validation is asynchronous; another call may have started while it ran.
        if (blockStartDuringAnotherCall()) return
        if (updateRequired || updateCheckInFlight) {
            pendingStartMeeting = isCaller
            if (!isCaller) pendingInviteRoomId = currentRoomId
            return
        }
        hideEntryScreens()
        isInCall = true
        isEndingCall = false
        stopReactionSync()
        micEnabled = true
        cameraEnabled = true
        speakerEnabled = true
        viewModel.onCallStarted()
        CallForegroundService.start(this, currentRoomId, muted = false)

        binding.btnCopyRoomId.isVisible = true
        binding.tvRoomStatus.text = if (isCaller) "Waiting for peer" else "Joining room"
        updateParticipants(count = 1)
        binding.callControls.fadeVisible(true)
        binding.reactionPanel.fadeVisible(true)
        binding.localPreviewContainer.isVisible = false
        binding.btnMic.setImageResource(R.drawable.ic_mic)
        binding.btnCamera.setImageResource(R.drawable.ic_videocam)
        binding.btnSpeaker.setImageResource(R.drawable.ic_volume_up)
        enterImmersiveMode()
        scheduleControlsAutoHide()

        webRTCManager = WebRTCManager(
            context = applicationContext,
            floatingView = binding.localView,
            fullScreenView = binding.remoteView,
            roomId = currentRoomId,
            listener = this
        ).also {
            ActiveCallSession.manager = it
            ActiveCallSession.roomId = currentRoomId
            ActiveCallSession.micEnabled = micEnabled
            ActiveCallSession.cameraEnabled = cameraEnabled
            ActiveCallSession.speakerEnabled = speakerEnabled
            it.initialize(isCaller)
            localVideoTrack = it.getLocalVideoTrack()
            currentParticipantsSnapshot = it.getParticipantsSnapshot()
        }
        startReactionSync()
        renderCallLayout()
    }

    private fun endCall() {
        isEndingCall = true
        ActiveCallSession.clear()
        CallForegroundService.stop(this)
        webRTCManager?.disconnect(cleanupRoom = true)
        webRTCManager?.release()
        webRTCManager = null
        releaseGroupRenderers()
        remoteVideoTracks.clear()
        localVideoTrack = null
        currentParticipantsSnapshot = emptyList()
        stopReactionSync()
        resetUiAfterCall()
    }

    private fun restoreActiveCallIfNeeded(): Boolean {
        val manager = ActiveCallSession.manager ?: return false
        if (ActiveCallSession.roomId.isBlank()) return false

        webRTCManager = manager
        manager.setListener(this)
        manager.reattachRenderers(binding.localView, binding.remoteView)
        isInCall = true
        isEndingCall = false
        currentRoomId = ActiveCallSession.roomId
        micEnabled = ActiveCallSession.micEnabled
        cameraEnabled = ActiveCallSession.cameraEnabled
        speakerEnabled = ActiveCallSession.speakerEnabled
        isLocalVideoFullScreen = manager.isLocalFullScreen()
        localVideoTrack = manager.getLocalVideoTrack()
        currentParticipantsSnapshot = manager.getParticipantsSnapshot()
        remoteVideoTracks.clear()
        manager.getConnectedRemoteUserIds().forEach { userId ->
            manager.getRemoteVideoTrack(userId)?.let { remoteVideoTracks[userId] = it }
        }

        viewModel.onCallStarted()
        CallForegroundService.start(this, currentRoomId, muted = !micEnabled)
        binding.btnCopyRoomId.isVisible = true
        binding.tvRoomStatus.text = "Call active"
        updateParticipants(count = 1)
        hideEntryScreens()
        binding.callControls.isVisible = true
        binding.reactionPanel.isVisible = true
        binding.localPreviewContainer.isVisible = true
        binding.btnMic.setImageResource(if (micEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off)
        binding.btnCamera.setImageResource(if (cameraEnabled) R.drawable.ic_videocam else R.drawable.ic_videocam_off)
        binding.btnSpeaker.setImageResource(if (speakerEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
        updateCameraDisabledUi()
        enterImmersiveMode()
        scheduleControlsAutoHide()
        startReactionSync()
        renderCallLayout()
        return true
    }

    private fun resetUiAfterCall() {
        cancelPreviewTransition()
        localPreviewLarge = false
        applyLocalPreviewSize()
        showingSoloVideo = false
        isInCall = false
        controlsVisible = true
        isGroupGridMode = false
        currentRoomId = ""
        entryRoomId = ""
        cancelPendingJoin()
        binding.btnCopyRoomId.isVisible = false
        binding.tvRoomStatus.text = "Ready to start"
        updateParticipants(count = 0)
        binding.callControls.fadeVisible(false)
        binding.reactionPanel.fadeVisible(false)
        binding.topBar.fadeVisible(true)
        binding.remoteScrim.fadeVisible(true)
        binding.localDisabledOverlay.isVisible = false
        binding.localDisabledIcon.isVisible = false
        binding.localAvatar.isVisible = false
        binding.fullDisabledOverlay.isVisible = false
        binding.fullAvatar.isVisible = false
        isLocalVideoFullScreen = false
        binding.groupVideoGrid.isVisible = false
        binding.remoteView.isVisible = true
        binding.localPreviewContainer.isVisible = true
        autoHideHandler.removeCallbacks(autoHideRunnable)
        viewModel.onCallEnded()
        exitImmersiveMode()
        showEntryScreen(MeetingEntryScreen.HOME)
    }

    private fun validateRoomBeforeJoining(onResult: (Boolean) -> Unit) {
        val roomId = currentRoomId.trim()
        if (roomId.isBlank()) {
            Toast.makeText(this, "Enter room ID first", Toast.LENGTH_SHORT).show()
            onResult(false)
            return
        }

        firestore.collection("calls")
            .document(roomId)
            .get()
            .addOnSuccessListener { document ->
                val ended = document.getBoolean("ended") == true || document.getString("status") == "ended"
                if (document.exists() && !ended) {
                    onResult(true)
                } else {
                    Toast.makeText(this, "That room has ended", Toast.LENGTH_SHORT).show()
                    onResult(false)
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Unable to join that room", Toast.LENGTH_SHORT).show()
                onResult(false)
            }
    }

    private fun resetJoinAttempt() {
        entryBusy = false
        isInCall = false
        currentRoomId = currentRoomId.trim()
        binding.tvRoomStatus.text = "Ready to start"
    }

    private fun checkForAppUpdate() {
        if (forceUpdateDialogShowing || updateCheckInFlight || isInCall || ActiveCallSession.isActive) return
        updateCheckInFlight = true
        firestore.collection(APP_CONFIG_COLLECTION)
            .document(APP_CONFIG_ANDROID_DOCUMENT)
            .get()
            .addOnSuccessListener { document ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                val latestVersionCode = document.getLong("latestVersionCode") ?: return@addOnSuccessListener
                val currentVersionCode = PackageInfoCompat.getLongVersionCode(
                    packageManager.getPackageInfo(packageName, 0)
                )
                if (currentVersionCode >= latestVersionCode) return@addOnSuccessListener

                updateRequired = true
                cancelPendingJoin()
                val latestVersionName = document.getString("latestVersionName").orEmpty()
                val forceUpdate = true
                val updateMessage = document.getString("updateMessage")
                    ?.takeIf { it.isNotBlank() }
                    ?: "A newer version of VexaMeet is required to continue."
                showUpdateDialog(latestVersionName, updateMessage, forceUpdate)
            }
            .addOnCompleteListener {
                updateCheckInFlight = false
                if (!isFinishing && !isDestroyed && !updateRequired &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    (pendingStartMeeting || pendingInviteRoomId != null)
                ) {
                    checkPermissions()
                }
            }
    }

    private fun showUpdateDialog(versionName: String, message: String, forceUpdate: Boolean) {
        if (forceUpdateDialogShowing || isFinishing) return
        forceUpdateDialogShowing = true

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(4))
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chat_video_call_clear)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                bottomMargin = dp(14)
            }
        })
        content.addView(TextView(this).apply {
            text = if (versionName.isNotBlank()) "Version $versionName is available" else "Update available"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.WHITE)
            alpha = 0.82f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(content)
            .setPositiveButton("Update", null)
            .apply {
                if (!forceUpdate) {
                    setNegativeButton("Later") { dialog, _ ->
                        forceUpdateDialogShowing = false
                        dialog.dismiss()
                    }
                }
            }
            .create()

        dialog.setCancelable(!forceUpdate)
        dialog.setCanceledOnTouchOutside(!forceUpdate)
        dialog.setOnCancelListener {
            if (forceUpdate) {
                showUpdateDialog(versionName, message, true)
            } else {
                forceUpdateDialogShowing = false
            }
        }
        dialog.setOnDismissListener {
            if (!forceUpdate) forceUpdateDialogShowing = false
        }
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            openPlayStore()
            if (!forceUpdate) {
                forceUpdateDialogShowing = false
                dialog.dismiss()
            }
        }
    }

    private fun openPlayStore() {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PLAY_STORE_PACKAGE_ID")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_WEB_URL))
        runCatching { startActivity(marketIntent) }
            .recoverCatching { startActivity(webIntent) }
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        binding.topBar.fadeVisible(controlsVisible)
        binding.callControls.fadeVisible(controlsVisible && isInCall)
        binding.reactionPanel.fadeVisible(controlsVisible && isInCall)
        binding.remoteScrim.fadeVisible(controlsVisible)
        binding.videoTapLayer.isVisible = isInCall
        if (visible) {
            cancelFullScreenTimeout()
            binding.root.updatePadding(top = lastSystemTopInset, bottom = lastSystemBottomInset)
            exitImmersiveMode()
            scheduleControlsAutoHide()
        } else {
            binding.root.updatePadding(top = 0, bottom = 0)
            enterImmersiveMode()
            autoHideHandler.removeCallbacks(autoHideRunnable)
        }
    }

    private fun toggleFullScreen(event: MotionEvent) {
        if (!canToggleFullscreenFromVideoTap(event)) return
        if (isFullScreen()) {
            exitFullScreen()
        } else {
            enterFullScreen()
        }
    }

    private fun enterFullScreen() {
        if (isFullScreen()) {
            scheduleFullScreenTimeout()
            return
        }
        setControlsVisible(false)
        scheduleFullScreenTimeout()
    }

    private fun exitFullScreen() {
        cancelFullScreenTimeout()
        if (!isFullScreen()) return
        setControlsVisible(true)
    }

    private fun isFullScreen(): Boolean {
        return isInCall && !controlsVisible
    }

    private fun attachVideoTapTarget(view: View) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnTouchListener { touchedView, event ->
            touchedView.parent?.requestDisallowInterceptTouchEvent(true)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun canToggleFullscreenFromVideoTap(event: MotionEvent): Boolean {
        if (!isInCall || isInPictureInPictureModeCompat()) return false
        if (binding.etReactionText.hasFocus() || isKeyboardVisible()) return false
        val rawX = event.rawX.toInt()
        val rawY = event.rawY.toInt()
        if (isPointInsideVisibleView(binding.reactionPanel, rawX, rawY)) return false
        if (isPointInsideVisibleView(binding.callControls, rawX, rawY)) return false
        if (isPointInsideVisibleView(binding.topBar, rawX, rawY)) return false
        if (isPointInsideVisibleView(binding.localPreviewContainer, rawX, rawY)) return false
        return isPointInsideVisibleView(binding.videoTapLayer, rawX, rawY) ||
            isPointInsideVisibleView(binding.remoteView, rawX, rawY) ||
            isPointInsideVisibleView(binding.remoteScrim, rawX, rawY) ||
            isPointInsideVisibleView(binding.groupVideoGrid, rawX, rawY)
    }

    private fun isKeyboardVisible(): Boolean {
        return ViewCompat.getRootWindowInsets(binding.root)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun isPointInsideVisibleView(view: View, rawX: Int, rawY: Int): Boolean {
        if (!view.isVisible || view.alpha <= 0f) return false
        val rect = Rect()
        view.getGlobalVisibleRect(rect)
        return rect.contains(rawX, rawY)
    }

    private fun scheduleFullScreenTimeout() {
        fullScreenTimeoutHandler.removeCallbacks(fullScreenTimeoutRunnable)
        fullScreenTimeoutHandler.postDelayed(fullScreenTimeoutRunnable, 8000L)
    }

    private fun cancelFullScreenTimeout() {
        fullScreenTimeoutHandler.removeCallbacks(fullScreenTimeoutRunnable)
    }

    private fun View.setControlTouchBoundary() {
        setOnTouchListener { view, event ->
            view.parent?.requestDisallowInterceptTouchEvent(true)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun toggleRemoteZoom() {
        if (isGroupGridMode) return
        remoteZoomed = !remoteZoomed
        binding.remoteView.setScalingType(
            if (remoteZoomed) RendererCommon.ScalingType.SCALE_ASPECT_FILL
            else RendererCommon.ScalingType.SCALE_ASPECT_FIT
        )
    }

    private fun setupLocalPreviewDrag() {
        var dX = 0f
        var dY = 0f
        val localGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isInCall) swapVideos()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleLocalPreviewLarge()
                return true
            }
        })

        binding.localPreviewContainer.setOnTouchListener { view, event ->
            if (previewTransitionRunning) return@setOnTouchListener true
            localGestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parent = binding.root
                    val targetX = (event.rawX + dX).coerceIn(0f, (parent.width - view.width).toFloat())
                    val targetY = (event.rawY + dY).coerceIn(0f, (parent.height - view.height).toFloat())
                    view.animate().x(targetX).y(targetY).setDuration(0).start()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    snapPreviewToBounds(view)
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleLocalPreviewLarge() {
        localPreviewLarge = !localPreviewLarge
        resizeLocalPreview()
    }

    private fun swapVideos() {
        if (!canSwapVideos()) return
        val manager = webRTCManager ?: return
        binding.localPreviewContainer.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .alpha(0.82f)
            .setDuration(90)
            .withEndAction {
                // A participant can leave while the tap animation is running.
                if (canSwapVideos() && webRTCManager === manager) {
                    isLocalVideoFullScreen = manager.swapVideoSurfaces()
                    updateHoldVideo()
                }
                updateCameraDisabledUi()
                binding.localPreviewContainer.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    private fun canSwapVideos(): Boolean = isInCall && !isGroupGridMode && !showingSoloVideo && !previewTransitionRunning &&
        getActiveParticipants().any { it.userId != senderId() && remoteVideoTracks.containsKey(it.userId) }

    private fun updateCameraDisabledUi() {
        val showDisabled = !cameraEnabled
        binding.localDisabledOverlay.isVisible = showDisabled && !isLocalVideoFullScreen
        binding.localDisabledIcon.isVisible = showDisabled && !isLocalVideoFullScreen
        binding.localAvatar.isVisible = showDisabled && !isLocalVideoFullScreen
        binding.fullDisabledOverlay.isVisible = showDisabled && isLocalVideoFullScreen && !isGroupGridMode
        binding.fullAvatar.isVisible = showDisabled && isLocalVideoFullScreen && !isGroupGridMode
    }

    private fun renderCallLayout() {
        if (!isInCall) return

        val activeParticipants = getActiveParticipants()
        val activeRemoteIds = activeParticipants.map { it.userId }.filter { it != senderId() }.sorted()
        val shouldUseGrid = activeParticipants.size >= 3

        if (shouldUseGrid) {
            renderGroupGrid(activeParticipants)
        } else {
            renderStandardLayout(activeRemoteIds)
        }

        updateParticipants(count = activeParticipants.size)
        updateHoldVideo()
    }

    override fun onHoldChanged() {
        runOnUiThread { updateHoldVideo() }
    }

    private fun updateHoldVideo() {
        val manager = webRTCManager ?: return
        val localHeld = manager.isParticipantOnHold(senderId())
        val remoteId = getActiveParticipants().map { it.userId }.filter { it != senderId() }.sorted().firstOrNull()
        val remoteHeld = remoteId?.let { manager.isParticipantOnHold(it) } == true
        binding.remoteView.setOnHold(if (isLocalVideoFullScreen) localHeld else remoteHeld)
        binding.localView.setOnHold(if (isLocalVideoFullScreen) remoteHeld else localHeld)
        groupLocalRenderer?.setOnHold(localHeld)
        remoteVideoViews.forEach { (id, view) -> view.setOnHold(manager.isParticipantOnHold(id)) }
    }

    private fun renderStandardLayout(activeRemoteIds: List<String>) {
        val solo = activeRemoteIds.isEmpty()
        val animateToPreview = showingSoloVideo && !solo && !isInPictureInPictureModeCompat()
        if (solo) {
            cancelPreviewTransition()
            isLocalVideoFullScreen = true
        } else if (showingSoloVideo || isGroupGridMode) {
            isLocalVideoFullScreen = false
        }
        showingSoloVideo = solo
        // Keep the manager's routing and camera mirroring consistent with this layout.
        webRTCManager?.let { manager ->
            if (manager.isLocalFullScreen() != isLocalVideoFullScreen) manager.swapVideoSurfaces()
        }
        isGroupGridMode = false
        binding.groupVideoGrid.isVisible = false
        binding.remoteView.isVisible = true
        binding.localPreviewContainer.isVisible = !solo && !isInPictureInPictureModeCompat()

        releaseGroupRenderers()

        localVideoTrack?.let { track ->
            track.removeSink(binding.localView)
            track.removeSink(binding.remoteView)
            if (isLocalVideoFullScreen) {
                track.addSink(binding.remoteView)
            } else {
                track.addSink(binding.localView)
            }
        }

        remoteVideoTracks.values.forEach { track ->
            track.removeSink(binding.localView)
            track.removeSink(binding.remoteView)
        }
        activeRemoteIds.firstOrNull()?.let { remoteId ->
            val track = remoteVideoTracks[remoteId]
            track?.removeSink(binding.localView)
            track?.removeSink(binding.remoteView)
            if (isLocalVideoFullScreen) {
                track?.addSink(binding.localView)
            } else {
                track?.addSink(binding.remoteView)
            }
        }

        binding.remoteScrim.isVisible = activeRemoteIds.isNotEmpty()
        updateCameraDisabledUi()
        if (animateToPreview) animateSelfVideoToPreview()
    }

    private fun cancelPreviewTransition() {
        previewTransitionGeneration++
        previewTransitionRunning = false
        binding.localPreviewContainer.animate().cancel()
        binding.localPreviewContainer.apply {
            translationX = 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            pivotX = width / 2f
            pivotY = height / 2f
        }
    }

    private fun animateSelfVideoToPreview() {
        cancelPreviewTransition()
        previewTransitionRunning = true
        val generation = previewTransitionGeneration
        val preview = binding.localPreviewContainer
        // Wait for the newly visible preview to have its bottom-right layout bounds.
        preview.doOnPreDraw {
            if (generation != previewTransitionGeneration) return@doOnPreDraw
            val full = binding.remoteView
            if (preview.width == 0 || preview.height == 0 || !isInCall || showingSoloVideo ||
                isGroupGridMode || isInPictureInPictureModeCompat()
            ) {
                cancelPreviewTransition()
                return@doOnPreDraw
            }
            preview.pivotX = 0f
            preview.pivotY = 0f
            preview.translationX = full.x - preview.left
            preview.translationY = full.y - preview.top
            preview.scaleX = full.width.toFloat() / preview.width
            preview.scaleY = full.height.toFloat() / preview.height
            preview.animate()
                .translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f)
                .setDuration(500)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    previewTransitionRunning = false
                    preview.pivotX = preview.width / 2f
                    preview.pivotY = preview.height / 2f
                }
                .start()
        }
    }

    private fun renderGroupGrid(activeParticipants: List<CallParticipant>) {
        cancelPreviewTransition()
        showingSoloVideo = false
        isGroupGridMode = true
        binding.groupVideoGrid.isVisible = true
        binding.remoteView.isVisible = false
        binding.localPreviewContainer.isVisible = false
        binding.remoteScrim.isVisible = true
        updateCameraDisabledUi()

        releaseGroupRenderers()
        val participantIds = buildList {
            add(senderId())
            addAll(activeParticipants.map { it.userId }.filter { it != senderId() }.sorted())
        }.distinct()

        val columns = when (participantIds.size) {
            1 -> 1
            2 -> 2
            3, 4 -> 2
            5, 6 -> 2
            else -> 3
        }
        val rows = max(1, ((participantIds.size + columns - 1) / columns))

        binding.groupVideoGrid.removeAllViews()
        binding.groupVideoGrid.columnCount = columns
        binding.groupVideoGrid.rowCount = rows

        val density = resources.displayMetrics.density
        participantIds.forEachIndexed { index, userId ->
            val row = index / columns
            val column = index % columns
            val tile = createVideoTile(userId)
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                rowSpec = GridLayout.spec(row, 1f)
                columnSpec = GridLayout.spec(column, 1f)
                setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            }
            binding.groupVideoGrid.addView(tile, params)
        }
    }

    private fun createVideoTile(userId: String): FrameLayout {
        val renderer = if (userId == senderId()) {
            groupLocalRenderer ?: com.vexa.meet.ui.RoundedTextureViewRenderer(this).apply {
                val manager = webRTCManager ?: error("Missing call manager")
                init(manager.eglContext(), 0f)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                attachVideoTapTarget(this)
                groupLocalRenderer = this
            }.also {
                localVideoTrack?.addSink(it)
            }
        } else {
            remoteVideoViews[userId]?.let { existing ->
                attachVideoTapTarget(existing)
                remoteVideoTracks[userId]?.addSink(existing)
                existing
            } ?: com.vexa.meet.ui.RoundedTextureViewRenderer(this).apply {
                val manager = webRTCManager ?: error("Missing call manager")
                init(manager.eglContext(), 0f)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                attachVideoTapTarget(this)
                remoteVideoViews[userId] = this
                remoteVideoTracks[userId]?.addSink(this)
            }
        }

        val tile = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = ContextCompat.getDrawable(this@MeetingActivity, R.drawable.bg_video_stage)
        }
        attachVideoTapTarget(tile)

        tile.addView(renderer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        return tile
    }

    private fun releaseGroupRenderers() {
        groupLocalRenderer?.let { view ->
            runCatching { view.release() }
        }
        groupLocalRenderer = null
        remoteVideoViews.values.forEach { view ->
            runCatching { view.release() }
        }
        remoteVideoViews.clear()
    }

    private fun getActiveParticipants(): List<CallParticipant> {
        return currentParticipantsSnapshot.filter { it.active }.sortedBy { it.userId }
    }

    private fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        ActiveCallSession.micEnabled = enabled
        webRTCManager?.setMicEnabled(micEnabled)
        binding.btnMic.setImageResource(if (micEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off)
        binding.btnMic.contentDescription = if (micEnabled) "Mute microphone" else "Unmute microphone"
        if (isInCall) {
            CallForegroundService.setMuted(this, muted = !micEnabled)
        }
    }

    private fun applyLocalPreviewSize() {
        binding.localPreviewContainer.updateLayoutParams {
            width = resources.getDimensionPixelSize(
                if (localPreviewLarge) R.dimen.local_preview_large_width else R.dimen.local_preview_width
            )
            height = resources.getDimensionPixelSize(
                if (localPreviewLarge) R.dimen.local_preview_large_height else R.dimen.local_preview_height
            )
        }
    }

    private fun resizeLocalPreview() {
        applyLocalPreviewSize()
        binding.localPreviewContainer.animate()
            .scaleX(1.02f)
            .scaleY(1.02f)
            .setDuration(90)
            .withEndAction {
                binding.localPreviewContainer.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                snapPreviewToBounds(binding.localPreviewContainer)
            }
            .start()
    }

    private fun snapPreviewToBounds(view: View) {
        val parent = binding.root
        val margin = 16f * resources.displayMetrics.density
        val leftSide = view.x < parent.width / 2f
        val targetX = if (leftSide) margin else parent.width - view.width - margin
        val minY = margin
        val maxY = parent.height - view.height - margin
        val targetY = min(max(view.y, minY), maxY)
        view.animate()
            .x(targetX)
            .y(targetY)
            .setDuration(180)
            .start()
    }

    private fun animateClick(view: View) {
        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }.start()
    }

    private fun View.fadeVisible(visible: Boolean) {
        if (visible) {
            if (isVisible && alpha == 1f) return
            alpha = 0f
            isVisible = true
            animate().alpha(1f).setDuration(180).start()
        } else {
            if (!isVisible) return
            animate().alpha(0f).setDuration(180).withEndAction { isVisible = false }.start()
        }
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun exitImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun scheduleControlsAutoHide() {
        autoHideHandler.removeCallbacks(autoHideRunnable)
        if (isInCall) {
            autoHideHandler.postDelayed(autoHideRunnable, 3500)
        }
    }

    private fun shareCurrentRoom() {
        if (currentRoomId.isBlank()) {
            Toast.makeText(this, "Create a room first", Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentRoomId)
        }
        startActivity(Intent.createChooser(shareIntent, "Invite people"))
    }

    private fun autoFillRoomIdFromClipboard() {
        if (isInCall || entryRoomId.isNotBlank()) return

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val roomId = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            ?.takeIf(::isValidRoomId)
            ?: return

        currentRoomId = roomId
        entryRoomId = roomId
        binding.btnCopyRoomId.isVisible = true
        binding.tvRoomStatus.text = "Room ready"
    }

    private fun isValidRoomId(value: String): Boolean {
        return value.matches(Regex("[A-Za-z0-9_-]{3,64}"))
    }

    private fun generateRoomId(): String {
        return "room_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }

    private fun senderId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "sender_${Build.MODEL.hashCode()}"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun handleInviteIntent(intent: Intent?) {
        val roomId = extractRoomIdFromIntent(intent) ?: return
        pendingInviteRoomId = roomId
        pendingStartMeeting = false
        entryBusy = true
        if (::binding.isInitialized) checkPermissions()
    }

    private fun consumePendingInvite() {
        val roomId = pendingInviteRoomId ?: return
        pendingInviteRoomId = null
        autoJoinRoom(roomId)
    }

    private fun autoJoinRoom(roomId: String) {
        if (roomId.isBlank()) return
        if (isInCall && currentRoomId == roomId) {
            entryBusy = false
            setControlsVisible(true)
            return
        }
        if (isInCall) {
            endCall()
        }

        currentRoomId = roomId
        entryRoomId = roomId
        showEntryScreen(MeetingEntryScreen.JOIN)
        binding.btnCopyRoomId.isVisible = true
        binding.tvRoomStatus.text = "Joining from invite"
        startCall(isCaller = false)
    }

    private fun extractRoomIdFromIntent(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return extractRoomIdFromUri(intent.data)
    }

    private fun extractRoomIdFromUri(uri: Uri?): String? {
        if (uri == null) return null

        return when {
            uri.scheme == "vexameet" && uri.host == "join" -> {
                uri.pathSegments.firstOrNull()
            }
            else -> null
        }?.trim()?.takeIf(::isValidRoomId)
    }

    private fun updateParticipants(count: Int) {
        binding.tvParticipants.text = when (count) {
            0 -> "0 joined"
            1 -> "1 joined"
            else -> "$count joined"
        }
    }

    override fun onConnectionLabelChanged(label: String, reconnecting: Boolean) {
        runOnUiThread {
            viewModel.setNetworkQuality(label, reconnecting)
            binding.tvRoomStatus.text = when (label) {
                "Connected" -> "Room live"
                "Reconnecting" -> "Reconnecting to room"
                "Disconnected" -> "Room disconnected"
                else -> "Waiting for connection"
            }
            if (label == "Connected") {
                webRTCManager?.forceSpeakerAfterConnection()
            }
        }
    }

    override fun onRoomStateChanged(status: String, participantCount: Int) {
        runOnUiThread {
            binding.tvRoomStatus.text = when (status) {
                "ringing" -> "Waiting for participants"
                "active" -> "Call active"
                "ended" -> "Room ended"
                else -> status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            updateParticipants(count = participantCount)
        }
    }

    override fun onParticipantsChanged(participants: List<CallParticipant>) {
        runOnUiThread {
            val sameParticipants = currentParticipantsSnapshot.map { it.userId }.toSet() ==
                participants.map { it.userId }.toSet()
            currentParticipantsSnapshot = participants
            // Keep existing frames when only a participant's hold state changes.
            if (sameParticipants) updateHoldVideo() else renderCallLayout()
        }
    }

    override fun onLocalVideoTrackReady(track: VideoTrack) {
        runOnUiThread {
            localVideoTrack = track
            renderCallLayout()
        }
    }

    override fun onRemoteVideoTrackReady(userId: String, track: VideoTrack) {
        runOnUiThread {
            remoteVideoTracks[userId] = track
            renderCallLayout()
        }
    }

    override fun onRemoteVideoTrackRemoved(userId: String) {
        runOnUiThread {
            remoteVideoTracks.remove(userId)
            remoteVideoViews.remove(userId)?.let { view ->
                runCatching { view.release() }
            }
            renderCallLayout()
        }
    }

    override fun onCallError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            if (isInCall) {
                endCall()
            } else {
                resetJoinAttempt()
            }
        }
    }

    override fun onCallDisconnected() {
        runOnUiThread {
            if (isInCall) {
                val manager = webRTCManager
                webRTCManager = null
                ActiveCallSession.clear()
                CallForegroundService.stop(this)
                manager?.release()
                stopReactionSync()
                releaseGroupRenderers()
                remoteVideoTracks.clear()
                localVideoTrack = null
                currentParticipantsSnapshot = emptyList()
                cancelFullScreenTimeout()
                resetUiAfterCall()
            }
        }
    }

    override fun onUserLeaveHint() {
        enterPipIfCallIsActive()
        super.onUserLeaveHint()
    }

    override fun onStop() {
        if (isInCall && !isEndingCall && !isChangingConfigurations && !isInPictureInPictureModeCompat()) {
            enterPipIfCallIsActive()
        }
        super.onStop()
    }

    private fun minimizeActiveCall() {
        if (!enterPipIfCallIsActive()) {
            moveTaskToBack(true)
        }
    }

    private fun enterPipIfCallIsActive(): Boolean {
        if (isInCall && !isEndingCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureModeCompat()) {
            val width = max(binding.remoteView.width, 16)
            val height = max(binding.remoteView.height, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(width, height))
                .setAutoEnterEnabledCompat()
                .build()
            return runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
        }
        return false
    }

    private fun PictureInPictureParams.Builder.setAutoEnterEnabledCompat(): PictureInPictureParams.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setAutoEnterEnabled(true)
        }
        return this
    }

    private fun isInPictureInPictureModeCompat(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        cancelPreviewTransition()
        binding.topBar.isVisible = !isInPictureInPictureMode && controlsVisible
        binding.callControls.isVisible = !isInPictureInPictureMode && controlsVisible && isInCall
        binding.reactionPanel.isVisible = !isInPictureInPictureMode && controlsVisible && isInCall
        binding.localPreviewContainer.isVisible = !isInPictureInPictureMode && !showingSoloVideo && !isGroupGridMode && isInCall
        binding.groupVideoGrid.isVisible = !isInPictureInPictureMode && isGroupGridMode
        if (!isInPictureInPictureMode && isInCall) {
            setControlsVisible(true)
        }
    }

    override fun onNotificationMuteToggled(muted: Boolean) {
        runOnUiThread {
            if (isInCall) {
                setMicEnabled(!muted)
            } else {
                ActiveCallSession.setMuted(muted)
            }
        }
    }

    override fun onNotificationEndCall() {
        runOnUiThread {
            if (isInCall) {
                endCall()
            } else {
                ActiveCallSession.endActiveCall()
                CallForegroundService.stop(this)
            }
        }
    }

    override fun onDestroy() {
        cancelPreviewTransition()
        autoHideHandler.removeCallbacks(autoHideRunnable)
        stopReactionSync()
        releaseGroupRenderers()
        if (!isInCall || isEndingCall) {
            webRTCManager?.release()
            webRTCManager = null
        } else {
            webRTCManager?.setListener(null)
        }
        if (ActiveCallActions.listener === this) {
            ActiveCallActions.listener = null
        }
        super.onDestroy()
    }

    companion object {
        private const val REACTION_TTL_MS = 45_000L
        private const val APP_CONFIG_COLLECTION = "appConfig"
        private const val APP_CONFIG_ANDROID_DOCUMENT = "android"
        private const val PLAY_STORE_PACKAGE_ID = "com.vexa.meet"
        private const val PLAY_STORE_WEB_URL = "https://play.google.com/store/apps/details?id=com.vexa.meet&hl=en_IN"
    }
}
