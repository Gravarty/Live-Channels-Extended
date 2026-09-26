package com.android.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.PlaybackParams
import android.media.tv.TvContentRating
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvTrackInfo
import android.media.tv.TvView
import android.media.tv.TvView.OnUnhandledInputEventListener
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.tv.InputSessionManager
import com.android.tv.InputSessionManager.TvViewSession
import com.android.tv.R
import com.android.tv.TvSingletons
import com.android.tv.common.CommonConstants
import com.android.tv.common.compat.TvInputConstantCompat
import com.android.tv.common.compat.TvViewCompat.TvInputCallbackCompat
import com.android.tv.common.util.DurationTimer
import com.android.tv.data.ProgramDataManager
import com.android.tv.data.StreamInfo
import com.android.tv.data.WatchedHistoryManager
import com.android.tv.data.api.Channel
import com.android.tv.features.TvFeatures
import com.android.tv.ui.api.TunableTvViewPlayingApi
import com.android.tv.ui.api.TunableTvViewPlayingApi.TimeShiftListener
import com.android.tv.util.NetworkUtils
import com.android.tv.util.TvInputManagerHelper
import com.android.tv.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port von com.android.tv.ui.TunableTvView: TvView mit Sperr-/Ladebildschirm, Stumm-Logik,
 * Stream-Infos und Timeshift.
 *
 * Mit DVR (im Original nur als System-App) läuft das Tunen über InputSessionManager, damit
 * Aufnahmen Tuner freigeben können. Keine Kindersicherungs-Rechte (System-Recht).
 * Entfallen: Analytics-Tracker (AOSP-Stub), setMain() (System-API), Poster-Bild beim Tunen
 * (nur für den eingebauten Tuner).
 */
class TunableTvView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0, defStyleRes: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes), StreamInfo, TunableTvViewPlayingApi {

    interface OnTuneListener {
        fun onTuneFailed(channel: Channel?)
        fun onUnexpectedStop(channel: Channel?)
        fun onStreamInfoChanged(info: StreamInfo, allowAutoSelectionOfTrack: Boolean)
        fun onChannelRetuned(channel: Uri?)
        fun onContentBlocked()
        fun onContentAllowed()
        fun onChannelSignalStrength()
    }

    abstract class OnScreenBlockingChangedListener {
        abstract fun onScreenBlockingChanged(blocked: Boolean)
    }

    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inputSessionManager: InputSessionManager? =
        if (TvFeatures.isDvrEnabled(context)) TvSingletons.getSingletons(context).getInputSessionManager() else null
    private var tvViewSession: TvViewSession? = null

    lateinit var tvView: AppLayerTvView
        private set
    private lateinit var inputManagerHelper: TvInputManagerHelper
    private lateinit var programDataManager: ProgramDataManager
    private var watchedHistoryManager: WatchedHistoryManager? = null

    private var started = false
    private var targetInputId: String? = null
    private var inputInfo: TvInputInfo? = null
    private var onTuneListener: OnTuneListener? = null

    override var currentChannel: Channel? = null
    override var videoWidth = 0; private set
    override var videoHeight = 0; private set
    override var videoDefinitionLevel = StreamInfo.VIDEO_DEFINITION_LEVEL_UNKNOWN; private set
    override var videoFrameRate = 0f; private set
    override var videoDisplayAspectRatio = 0f; private set
    override var audioChannelCount = StreamInfo.AUDIO_CHANNEL_COUNT_UNKNOWN; private set
    private var hasClosedCaption = false
    override var videoUnavailableReason = VIDEO_UNAVAILABLE_REASON_NOT_TUNED; private set
    override var blockedContentRating: TvContentRating? = null; private set

    var isScreenBlocked = false; private set
    private var onScreenBlockedListener: OnScreenBlockingChangedListener? = null
    private var canReceiveInputEvent = false
    private var isMuted = false
    private var volume = 0f
    private var parentControlEnabled = false
    private var fixedSurfaceWidth = 0
    private var fixedSurfaceHeight = 0
    // Ohne MODIFY_PARENTAL_CONTROLS (System-Recht) immer false – wie im Original bei Nicht-System-Apps.
    private val canModifyParentalControls = false
    private var isUnderShrunken = false

    private var timeShiftState = TIME_SHIFT_STATE_NONE
    private var timeShiftListener: TimeShiftListener? = null
    override var isTimeShiftAvailable = false; private set
    private var timeShiftCurrentPositionMs = TvInputManager.TIME_SHIFT_INVALID_TIME
    private val channelViewTimer = DurationTimer()
    private var internetCheckJob: Job? = null

    private val blockScreenView: BlockScreenView
    private val bufferingSpinnerView: View
    private val dimScreenView: View
    private var fadeState = FADED_IN
    private var actionAfterFade: Runnable? = null
    private var blockScreenType = BLOCK_SCREEN_TYPE_NORMAL
    var channelSignalStrength = 0; private set

    private val callback = object : TvInputCallbackCompat() {
        override fun onConnectionFailed(inputId: String) {
            Log.w(TAG, "Failed to bind an input")
            val channel = currentChannel
            currentChannel = null
            inputInfo = null
            canReceiveInputEvent = false
            onTuneListener?.let { onTuneListener = null; it.onTuneFailed(channel) }
        }

        override fun onDisconnected(inputId: String) {
            Log.w(TAG, "Session is released by crash")
            val channel = currentChannel
            currentChannel = null
            inputInfo = null
            canReceiveInputEvent = false
            onTuneListener?.let { onTuneListener = null; it.onUnexpectedStop(channel) }
        }

        override fun onChannelRetuned(inputId: String, channelUri: Uri) {
            if (DEBUG) Log.d(TAG, "onChannelRetuned(inputId=$inputId, channelUri=$channelUri)")
            onTuneListener?.onChannelRetuned(channelUri)
        }

        override fun onTracksChanged(inputId: String, tracks: List<TvTrackInfo>) {
            hasClosedCaption = tracks.any { it.type == TvTrackInfo.TYPE_SUBTITLE }
            onTuneListener?.onStreamInfoChanged(this@TunableTvView, true)
        }

        override fun onTrackSelected(inputId: String, type: Int, trackId: String?) {
            if (trackId == null) {
                if (type == TvTrackInfo.TYPE_VIDEO) {
                    videoWidth = 0
                    videoHeight = 0
                    videoDefinitionLevel = StreamInfo.VIDEO_DEFINITION_LEVEL_UNKNOWN
                    videoFrameRate = 0f
                    videoDisplayAspectRatio = 0f
                } else if (type == TvTrackInfo.TYPE_AUDIO) {
                    audioChannelCount = StreamInfo.AUDIO_CHANNEL_COUNT_UNKNOWN
                }
            } else {
                val track = getTracks(type)?.firstOrNull { it.id == trackId }
                if (track == null) {
                    Log.w(TAG, "Invalid track ID: $trackId")
                } else if (type == TvTrackInfo.TYPE_VIDEO) {
                    videoWidth = track.videoWidth
                    videoHeight = track.videoHeight
                    videoDefinitionLevel = Utils.getVideoDefinitionLevelFromSize(videoWidth, videoHeight)
                    videoFrameRate = track.videoFrameRate
                    videoDisplayAspectRatio = if (videoWidth <= 0 || videoHeight <= 0) 0f else {
                        val par = track.videoPixelAspectRatio
                        videoWidth.toFloat() / videoHeight * (if (par > 0) par else 1f)
                    }
                } else if (type == TvTrackInfo.TYPE_AUDIO) {
                    audioChannelCount = track.audioChannelCount
                }
            }
            onTuneListener?.onStreamInfoChanged(this@TunableTvView, type == TvTrackInfo.TYPE_VIDEO)
        }

        override fun onVideoAvailable(inputId: String) {
            videoUnavailableReason = VIDEO_UNAVAILABLE_REASON_NONE
            updateBlockScreenAndMuting()
            onTuneListener?.onStreamInfoChanged(this@TunableTvView, true)
        }

        override fun onVideoUnavailable(inputId: String, reason: Int) {
            videoUnavailableReason = reason
            if (closePipIfNeeded()) return
            updateBlockScreenAndMuting()
            onTuneListener?.onStreamInfoChanged(this@TunableTvView, true)
        }

        override fun onContentAllowed(inputId: String) {
            blockedContentRating = null
            updateBlockScreenAndMuting()
            onTuneListener?.onContentAllowed()
        }

        override fun onContentBlocked(inputId: String, rating: TvContentRating) {
            if (rating == blockedContentRating) return
            blockedContentRating = rating
            if (closePipIfNeeded()) return
            updateBlockScreenAndMuting()
            onTuneListener?.onContentBlocked()
        }

        override fun onTimeShiftStatusChanged(inputId: String, status: Int) {
            if (DEBUG) Log.d(TAG, "onTimeShiftStatusChanged: {inputId=$inputId, status=$status}")
            setTimeShiftAvailable(status == TvInputManager.TIME_SHIFT_STATUS_AVAILABLE)
        }

        override fun onSignalStrength(inputId: String, value: Int) {
            channelSignalStrength = value
            onTuneListener?.onChannelSignalStrength()
        }
    }

    init {
        inflate(context, R.layout.tunable_tv_view, this)
        blockScreenView = findViewById(R.id.block_screen)
        blockScreenView.addInfoFadeInAnimationListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) = adjustBlockScreenSpacingAndText()
        })
        bufferingSpinnerView = findViewById(R.id.buffering_spinner)
        dimScreenView = findViewById(R.id.dim_screen)
        dimScreenView.animate().setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { actionAfterFade?.run() }
            override fun onAnimationCancel(animation: Animator) { actionAfterFade?.run() }
        })
    }

    /** Muss vor der Nutzung aufgerufen werden. */
    fun initialize(
        programDataManager: ProgramDataManager,
        tvInputManagerHelper: TvInputManagerHelper,
    ) {
        tvView = findViewById(R.id.tv_view)
        tvView.setUseSecureSurface(true) // Release-Build ohne Entwickler-Features
        this.programDataManager = programDataManager
        inputManagerHelper = tvInputManagerHelper
        if (inputSessionManager != null) {
            tvViewSession = inputSessionManager.createTvViewSession(tvView, this, callback)
        } else {
            tvView.setCallback(callback)
        }
    }

    fun start() { started = true }

    /** Input vorab verbinden, bevor die App gestartet ist. */
    fun warmUpInput(inputId: String?, channelUri: Uri?) {
        if (!started && inputId != null && channelUri != null) {
            lastCaptionEnabled = null
            tvViewSession?.tune(inputId, channelUri) ?: tvView.tune(inputId, channelUri)
            videoUnavailableReason = TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
            updateBlockScreenAndMuting()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        logChannelViewStop()
        reset()
    }

    fun release() {
        inputSessionManager?.let { manager ->
            tvViewSession?.let { manager.releaseTvViewSession(it) }
            tvViewSession = null
        }
        scope.cancel()
    }

    fun reset() {
        resetInternal()
        videoUnavailableReason = VIDEO_UNAVAILABLE_REASON_NOT_TUNED
        updateBlockScreenAndMuting()
    }

    fun resetByRecording() = resetInternal()

    private fun resetInternal() {
        lastCaptionEnabled = null
        tvViewSession?.reset() ?: tvView.reset()
        currentChannel = null
        inputInfo = null
        canReceiveInputEvent = false
        onTuneListener = null
        setTimeShiftAvailable(false)
    }

    fun setWatchedHistoryManager(manager: WatchedHistoryManager?) { watchedHistoryManager = manager }
    fun setIsUnderShrunken(value: Boolean) { isUnderShrunken = value }

    fun resetChannelSignalStrength() { channelSignalStrength = TvInputConstantCompat.SIGNAL_STRENGTH_NOT_USED }

    override val isPlaying: Boolean get() = started

    fun onParentalControlChanged(enabled: Boolean) {
        parentControlEnabled = enabled
        if (!enabled) updateBlockScreenAndMuting()
    }

    private fun logChannelViewStop() {
        val channel = currentChannel ?: return
        val duration = channelViewTimer.reset()
        if (!channel.isPassthrough) {
            watchedHistoryManager?.logChannelViewStop(channel, System.currentTimeMillis(), duration)
        }
    }

    /** Tunt auf [channel]. false, wenn der Input nicht (mehr) existiert. */
    fun tuneTo(channel: Channel?, params: Bundle?, listener: OnTuneListener?): Boolean {
        check(started) { "TvView isn't started" }
        if (channel == null) return false
        val info = inputManagerHelper.getTvInputInfo(channel.inputId) ?: return false
        logChannelViewStop()
        onTuneListener = listener
        currentChannel = channel
        var needSurfaceSizeUpdate = false
        if (info != inputInfo) {
            targetInputId = info.id
            inputInfo = info
            canReceiveInputEvent = context.packageManager.checkPermission(
                PERMISSION_RECEIVE_INPUT_EVENT, info.serviceInfo.packageName) == PackageManager.PERMISSION_GRANTED
            if (DEBUG) Log.d(TAG, "Input '${info.id}' can receive input event: $canReceiveInputEvent")
            needSurfaceSizeUpdate = true
        }
        channelViewTimer.start()
        videoWidth = 0
        videoHeight = 0
        videoDefinitionLevel = StreamInfo.VIDEO_DEFINITION_LEVEL_UNKNOWN
        videoFrameRate = 0f
        videoDisplayAspectRatio = 0f
        audioChannelCount = StreamInfo.AUDIO_CHANNEL_COUNT_UNKNOWN
        hasClosedCaption = false
        blockedContentRating = null
        timeShiftCurrentPositionMs = TvInputManager.TIME_SHIFT_INVALID_TIME
        tvView.setTimeShiftPositionCallback(null)
        setTimeShiftAvailable(false)
        videoUnavailableReason = TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
        lastCaptionEnabled = null
        tvViewSession?.tune(channel, params, listener) ?: tvView.tune(info.id, channel.uri, params)
        if (needSurfaceSizeUpdate && fixedSurfaceWidth > 0 && fixedSurfaceHeight > 0) {
            surfaceView?.holder?.setFixedSize(fixedSurfaceWidth, fixedSurfaceHeight)
                ?: Log.w(TAG, "Failed to set fixed size for surface view: Null surface view")
        }
        updateBlockScreenAndMuting()
        onTuneListener?.onStreamInfoChanged(this, true)
        return true
    }

    override fun setStreamVolume(volume: Float) {
        check(started) { "TvView isn't started" }
        this.volume = volume
        if (!isMuted) tvView.setStreamVolume(volume)
    }

    override val streamVolume: Float get() = if (isMuted) 0f else volume

    fun setFixedSurfaceSize(width: Int, height: Int) {
        fixedSurfaceWidth = width
        fixedSurfaceHeight = height
        val holder = (tvView.getChildAt(0) as SurfaceView).holder
        if (width > 0 && height > 0) holder.setFixedSize(width, height) else holder.setSizeFromLayout()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = canReceiveInputEvent && tvView.dispatchKeyEvent(event)
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = canReceiveInputEvent && tvView.dispatchTouchEvent(event)
    override fun dispatchTrackballEvent(event: MotionEvent): Boolean = canReceiveInputEvent && tvView.dispatchTrackballEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        canReceiveInputEvent && tvView.dispatchGenericMotionEvent(event)

    fun unblockContent(rating: TvContentRating) = tvView.unblockContentCompat(rating)

    override fun hasClosedCaption(): Boolean = hasClosedCaption

    override val isVideoAvailable: Boolean get() = videoUnavailableReason == VIDEO_UNAVAILABLE_REASON_NONE

    override val isVideoOrAudioAvailable: Boolean
        get() = videoUnavailableReason == VIDEO_UNAVAILABLE_REASON_NONE ||
            videoUnavailableReason == TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY

    private val surfaceView: SurfaceView? get() = tvView.getChildAt(0) as? SurfaceView

    fun setOnUnhandledInputEventListener(listener: OnUnhandledInputEventListener?) =
        tvView.setOnUnhandledInputEventListener(listener)

    // Bugfix: Das Original ruft setCaptionEnabled bei jeder Stream-Info-Änderung auf. Der MediaTek-Tuner
    // (JVC/Vestel) startet darauf jedes Mal den Untertitel-Stream neu und meldet wieder Änderungen –
    // eine Schleife mit mehreren Aufrufen pro Sekunde, bei der der Ton ständig aussetzt.
    // Daher nur senden, wenn sich der Wert seit dem letzten Tune geändert hat.
    private var lastCaptionEnabled: Boolean? = null

    fun setClosedCaptionEnabled(enabled: Boolean) {
        if (lastCaptionEnabled == enabled) return
        lastCaptionEnabled = enabled
        tvView.setCaptionEnabled(enabled)
    }
    fun setOnTuneListener(listener: OnTuneListener?) { onTuneListener = listener }
    fun getTracks(type: Int): List<TvTrackInfo>? = tvView.getTracks(type)
    fun getSelectedTrack(type: Int): String? = tvView.getSelectedTrack(type)
    fun selectTrack(type: Int, trackId: String?) = tvView.selectTrack(type, trackId)

    var tvViewLayoutParams: MarginLayoutParams
        get() = tvView.layoutParams as MarginLayoutParams
        set(value) { tvView.layoutParams = value }

    val isBlocked: Boolean get() = isScreenBlocked || isContentBlocked
    val isContentBlocked: Boolean get() = blockedContentRating != null

    fun setOnScreenBlockedListener(listener: OnScreenBlockingChangedListener?) { onScreenBlockedListener = listener }

    /** Sperrt/entsperrt den Bildschirm (gesperrter Kanal). */
    fun blockOrUnblockScreen(block: Boolean) {
        if (isScreenBlocked == block) return
        isScreenBlocked = block
        if (closePipIfNeeded()) return
        updateBlockScreenAndMuting()
        onScreenBlockedListener?.onScreenBlockingChanged(block)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // tvView ist erst nach initialize() gesetzt
        if (::tvView.isInitialized) tvView.visibility = visibility
    }

    fun setBlockScreenType(type: Int) {
        if (blockScreenType != type) {
            blockScreenType = type
            updateBlockScreen(true)
        }
    }

    private fun updateBlockScreen(animation: Boolean) {
        blockScreenView.endAnimations()
        val blockReason = if ((isScreenBlocked || blockedContentRating != null) && parentControlEnabled) {
            VIDEO_UNAVAILABLE_REASON_SCREEN_BLOCKED
        } else {
            videoUnavailableReason
        }
        if (blockReason == VIDEO_UNAVAILABLE_REASON_NONE) {
            bufferingSpinnerView.visibility = GONE
            if (blockScreenView.visibility == VISIBLE) blockScreenView.fadeOut()
            return
        }
        bufferingSpinnerView.visibility =
            if (blockReason == TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING ||
                blockReason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
            ) VISIBLE else GONE
        if (!animation) adjustBlockScreenSpacingAndText()
        if (blockReason == TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING) return
        blockScreenView.visibility = VISIBLE
        if (shouldShowEmptyInputStatusBlock()) {
            blockScreenView.setEmptyInputStatusInputInfo(inputInfo)
            blockScreenView.setEmptyInputStatusBlockVisibility(true)
        } else {
            blockScreenView.setEmptyInputStatusBlockVisibility(false)
        }
        blockScreenView.setBackgroundImage(null)
        if (blockReason == VIDEO_UNAVAILABLE_REASON_SCREEN_BLOCKED) {
            blockScreenView.setIconVisibility(true)
            if (!canModifyParentalControls) {
                blockScreenView.setIconImage(R.drawable.ic_message_lock_no_permission)
                blockScreenView.setIconScaleType(ImageView.ScaleType.CENTER)
            } else {
                blockScreenView.setIconImage(R.drawable.ic_message_lock)
                blockScreenView.setIconScaleType(ImageView.ScaleType.FIT_CENTER)
            }
        } else {
            internetCheckJob?.cancel()
            internetCheckJob = null
            blockScreenView.setIconVisibility(false)
            val channel = currentChannel
            if (blockReason == TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN &&
                channel != null && !channel.isPhysicalTunerChannel
            ) {
                startInternetCheck()
            }
        }
        blockScreenView.onBlockStatusChanged(blockScreenType, animation)
    }

    private fun adjustBlockScreenSpacingAndText() {
        blockScreenView.setSpacing(blockScreenType)
        getBlockScreenText()?.let { blockScreenView.setInfoText(it) }
        blockScreenView.setInfoTextClickable(isScreenBlocked && parentControlEnabled)
    }

    /**
     * Text des Sperr-/Hinweisbildschirms. Namen von Altersfreigaben sind ohne System-Rechte nicht
     * verfügbar, daher greifen die Texte ohne Namen (wie im Original bei leerer Rating-Liste).
     */
    private fun getBlockScreenText(): String? {
        val res = resources
        val isA11y = accessibilityManager.isEnabled
        when {
            isScreenBlocked && parentControlEnabled -> return when (blockScreenType) {
                BLOCK_SCREEN_TYPE_NORMAL ->
                    if (canModifyParentalControls) {
                        res.getString(if (isA11y) R.string.tvview_channel_locked_talkback else R.string.tvview_channel_locked)
                    } else {
                        res.getString(R.string.tvview_channel_locked_no_permission)
                    }
                else -> ""
            }
            blockedContentRating != null && parentControlEnabled -> return when (blockScreenType) {
                BLOCK_SCREEN_TYPE_NO_UI -> ""
                BLOCK_SCREEN_TYPE_SHRUNKEN_TV_VIEW -> res.getString(R.string.shrunken_tvview_content_locked)
                else ->
                    if (canModifyParentalControls) {
                        res.getString(if (isA11y) R.string.tvview_content_locked_talkback else R.string.tvview_content_locked)
                    } else {
                        res.getString(R.string.tvview_content_locked_no_permission)
                    }
            }
            videoUnavailableReason != VIDEO_UNAVAILABLE_REASON_NONE -> return when (videoUnavailableReason) {
                TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> res.getString(R.string.tvview_msg_audio_only)
                TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> res.getString(R.string.tvview_msg_weak_signal)
                CommonConstants.VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED ->
                    res.getString(R.string.msg_channel_unavailable_not_connected)
                VIDEO_UNAVAILABLE_REASON_NO_RESOURCE -> getTuneConflictMessage()
                else -> ""
            }
        }
        return null
    }

    /** "Tuner belegt bis HH:MM" während einer Aufnahme (nur mit DVR). */
    private fun getTuneConflictMessage(): String? {
        val inputId = targetInputId ?: return null
        val manager = inputSessionManager ?: return null
        val timeMs = manager.getEarliestRecordingSessionEndTimeMs(inputId) ?: return null
        val input = inputManagerHelper.getTvInputInfo(inputId) ?: return null
        return resources.getQuantityString(
            R.plurals.tvview_msg_input_no_resource,
            input.tunerCount,
            DateUtils.formatDateTime(context, timeMs, DateUtils.FORMAT_SHOW_TIME),
        )
    }

    /** Beendet Bild-in-Bild, wenn dort nichts Sinnvolles angezeigt werden kann. */
    private fun closePipIfNeeded(): Boolean {
        val activity = context as? Activity ?: return false
        if (TvFeatures.isPictureInPictureEnabled(context) && activity.isInPictureInPictureMode &&
            (isScreenBlocked || blockedContentRating != null ||
                videoUnavailableReason == TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN ||
                videoUnavailableReason == CommonConstants.VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED)
        ) {
            activity.finish()
            return true
        }
        return false
    }

    private fun updateBlockScreenAndMuting() {
        updateBlockScreen(false)
        updateMuteStatus()
    }

    /** Stumm, solange kein Bild/Ton verfügbar ist oder gesperrt wird (Nicht-Bundled-Inputs). */
    private fun updateMuteStatus() {
        if (isVideoOrAudioAvailable && !isScreenBlocked && blockedContentRating == null) {
            if (isMuted) {
                isMuted = false
                tvView.setStreamVolume(volume)
            }
        } else if (!isMuted) {
            if (inputInfo == null && !isScreenBlocked && blockedContentRating == null) return
            isMuted = true
            tvView.setStreamVolume(0f)
        }
    }

    private fun shouldShowEmptyInputStatusBlock(): Boolean =
        TvFeatures.useGtvLiveTvV2(context) &&
            (videoUnavailableReason == TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL ||
                videoUnavailableReason == CommonConstants.VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED)

    val isFadedOut: Boolean get() = fadeState == FADED_OUT

    fun fadeOut(durationMillis: Int, interpolator: TimeInterpolator, actionAfterFade: Runnable?) {
        dimScreenView.alpha = 0f
        dimScreenView.visibility = VISIBLE
        dimScreenView.animate().alpha(1f).setDuration(durationMillis.toLong()).setInterpolator(interpolator)
            .withStartAction { fadeState = FADING_OUT; this.actionAfterFade = actionAfterFade }
            .withEndAction { fadeState = FADED_OUT }
    }

    fun fadeIn(durationMillis: Int, interpolator: TimeInterpolator, actionAfterFade: Runnable?) {
        dimScreenView.alpha = 1f
        dimScreenView.visibility = VISIBLE
        dimScreenView.animate().alpha(0f).setDuration(durationMillis.toLong()).setInterpolator(interpolator)
            .withStartAction { fadeState = FADING_IN; this.actionAfterFade = actionAfterFade }
            .withEndAction { fadeState = FADED_IN; dimScreenView.visibility = GONE }
    }

    fun removeFadeEffect() {
        dimScreenView.animate().cancel()
        dimScreenView.visibility = GONE
        fadeState = FADED_IN
    }

    override fun setTimeShiftListener(listener: TimeShiftListener?) { timeShiftListener = listener }

    fun setBlockedInfoOnClickListener(onClickListener: OnClickListener?) =
        blockScreenView.setInfoTextOnClickListener(onClickListener)

    private fun setTimeShiftAvailable(available: Boolean) {
        if (isTimeShiftAvailable == available) return
        // Status gehört zur jeweiligen Session – sonst ignoriert der nächste Kanal Play/Pause
        timeShiftState = TIME_SHIFT_STATE_NONE
        isTimeShiftAvailable = available
        if (available) {
            tvView.setTimeShiftPositionCallback(object : TvView.TimeShiftPositionCallback() {
                override fun onTimeShiftStartPositionChanged(inputId: String, timeMs: Long) {
                    if (currentChannel?.inputId == inputId) timeShiftListener?.onRecordStartTimeChanged(timeMs)
                }

                override fun onTimeShiftCurrentPositionChanged(inputId: String, timeMs: Long) {
                    timeShiftCurrentPositionMs = timeMs
                }
            })
        } else {
            tvView.setTimeShiftPositionCallback(null)
        }
        timeShiftListener?.onAvailabilityChanged()
    }

    private fun checkTimeShift() = check(isTimeShiftAvailable) { "Time-shift is not supported for the current channel" }

    override fun timeShiftPlay() {
        checkTimeShift()
        if (timeShiftState == TIME_SHIFT_STATE_PLAY) return
        // Bugfix: Original setzte den Status bei Play/Pause nie
        timeShiftState = TIME_SHIFT_STATE_PLAY
        tvView.timeShiftResume()
    }

    override fun timeShiftPause() {
        checkTimeShift()
        if (timeShiftState == TIME_SHIFT_STATE_PAUSE) return
        timeShiftState = TIME_SHIFT_STATE_PAUSE
        tvView.timeShiftPause()
    }

    override fun timeShiftRewind(speed: Int) {
        checkTimeShift()
        require(speed > 0) { "The speed should be a positive integer." }
        timeShiftState = TIME_SHIFT_STATE_REWIND
        tvView.timeShiftSetPlaybackParams(PlaybackParams().setSpeed(-speed.toFloat()))
    }

    override fun timeShiftFastForward(speed: Int) {
        checkTimeShift()
        require(speed > 0) { "The speed should be a positive integer." }
        timeShiftState = TIME_SHIFT_STATE_FAST_FORWARD
        tvView.timeShiftSetPlaybackParams(PlaybackParams().setSpeed(speed.toFloat()))
    }

    override fun timeShiftSeekTo(timeMs: Long) {
        checkTimeShift()
        tvView.timeShiftSeekTo(timeMs)
    }

    override fun timeShiftGetCurrentPositionMs(): Long {
        checkTimeShift()
        if (DEBUG) Log.d(TAG, "timeShiftGetCurrentPositionMs: current position =${Utils.toTimeString(timeShiftCurrentPositionMs)}")
        return timeShiftCurrentPositionMs
    }

    /** Prüft die Internetverbindung und zeigt ggf. "Keine Internetverbindung" (ersetzt InternetCheckTask). */
    private fun startInternetCheck() {
        internetCheckJob = scope.launch {
            val networkAvailable = withContext(Dispatchers.IO) { NetworkUtils.isNetworkAvailable(connectivityManager) }
            internetCheckJob = null
            if (!networkAvailable && isAttachedToWindow && !isScreenBlocked && blockedContentRating == null &&
                videoUnavailableReason == TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN
            ) {
                blockScreenView.setIconVisibility(true)
                blockScreenView.setIconImage(R.drawable.ic_sad_cloud)
                blockScreenView.setInfoText(R.string.tvview_msg_no_internet_connection)
            }
        }
    }

    companion object {
        private const val TAG = "TunableTvView"
        private const val DEBUG = false

        const val VIDEO_UNAVAILABLE_REASON_NOT_TUNED = -1
        const val VIDEO_UNAVAILABLE_REASON_NO_RESOURCE = -2
        const val VIDEO_UNAVAILABLE_REASON_SCREEN_BLOCKED = -3
        const val VIDEO_UNAVAILABLE_REASON_NONE = -100

        const val BLOCK_SCREEN_TYPE_NO_UI = 0
        const val BLOCK_SCREEN_TYPE_SHRUNKEN_TV_VIEW = 1
        const val BLOCK_SCREEN_TYPE_NORMAL = 2

        private const val PERMISSION_RECEIVE_INPUT_EVENT = CommonConstants.BASE_PACKAGE + ".permission.RECEIVE_INPUT_EVENT"

        private const val TIME_SHIFT_STATE_NONE = 0
        private const val TIME_SHIFT_STATE_PLAY = 1
        private const val TIME_SHIFT_STATE_PAUSE = 2
        private const val TIME_SHIFT_STATE_REWIND = 3
        private const val TIME_SHIFT_STATE_FAST_FORWARD = 4

        private const val FADED_IN = 0
        private const val FADED_OUT = 1
        private const val FADING_IN = 2
        private const val FADING_OUT = 3
    }
}
