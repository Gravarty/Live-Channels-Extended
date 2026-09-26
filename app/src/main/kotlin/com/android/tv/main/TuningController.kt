package com.android.tv.main

import android.content.ContentUris
import android.media.tv.AitInfo
import android.media.tv.TvContentRating
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvTrackInfo
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.android.tv.ChannelTuner
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.TvSingletons
import com.android.tv.common.CommonConstants
import com.android.tv.common.SoftPreconditions
import com.android.tv.common.util.ContentUriUtils
import com.android.tv.common.util.DurationTimer
import com.android.tv.data.ChannelImpl
import com.android.tv.data.StreamInfo
import com.android.tv.data.api.Channel
import com.android.tv.dialog.PinDialogFragment
import com.android.tv.features.TvFeatures
import com.android.tv.ui.TunableTvView
import com.android.tv.ui.TvOverlayManager
import com.android.tv.ui.sidepanel.CustomizeChannelListFragment
import com.android.tv.util.Utils

/**
 * Aus MainActivity ausgelagert: Starten/Stoppen der Wiedergabe, Tunen, Kanalsperre,
 * verkleinerte TvView (Einstellungen), zuletzt gesehene Kanäle und der OnTuneListener. Logik 1:1.
 * Entfällt: setMain()/restoreMainTvView (System-API) und requestVisibleBehind (seit Android 8 wirkungslos).
 */
class TuningController(
    private val activity: MainActivity,
    private val tvView: TunableTvView,
    private val channelTuner: ChannelTuner,
) {
    private val intentHandler get() = activity.intentHandler
    private val overlayManager get() = activity.overlayManager
    private val tuneDurationTimer = DurationTimer()

    /** Input, dessen Einrichtungs-Activity gerade läuft (Tunen wird bis dahin aufgeschoben). */
    var inputIdUnderSetup: String? = null
    var isSetupActivityCalledByPopup = false
    var tunePending = false
        private set
    private var showNewSourcesFragment = true
    var showLockedChannelsTemporarily = false

    private var isCurrentChannelUnblockedByUser = false
    private var wasChannelUnblockedBeforeShrunkenByUser = false
    private var channelBeforeShrunkenTvView: Channel? = null
    private var isCompletingShrunkenTvView = false
    private var lastAllowedRatingForCurrentChannel: TvContentRating? = null
    private var allowedRatingBeforeShrunken: TvContentRating? = null

    val recentChannels = ArrayDeque<Long>(MAX_RECENT_CHANNELS)
    val onTuneListener = MyOnTuneListener()

    fun onResume() { showNewSourcesFragment = true }

    fun onPause() {
        showLockedChannelsTemporarily = false
        intentHandler.shouldTuneToTunerChannel = false
    }

    /** Startet die Wiedergabe neu, falls nötig (nach Resume, Bildschirm an, Kanäle geladen). */
    fun resumeTvIfNeeded() {
        val ih = intentHandler
        if (!tvView.isPlaying || ih.initChannelUri != null ||
            (ih.shouldTuneToTunerChannel && channelTuner.isCurrentChannelPassthrough)
        ) {
            val initUri = ih.initChannelUri
            if (initUri != null && TvContract.isChannelUriForPassthroughInput(initUri)) {
                // Passthrough-Input kann nach Bildschirm-aus seine ID ändern (z. B. HDMI-CEC)
                val helper = activity.tvInputManagerHelper
                val input = helper.getTvInputInfo(initUri.pathSegments[1])
                    ?: helper.getTvInputInfo(ih.parentInputIdWhenScreenOff)
                if (input == null) {
                    SoftPreconditions.checkState(false, TAG, "Input disappear.")
                    activity.finish()
                    // Bugfix: Original tunte danach trotzdem auf den verschwundenen Input
                    return
                } else if (input.id != initUri.pathSegments[1]) {
                    ih.initChannelUri = TvContract.buildChannelUriForPassthroughInput(input.id)
                }
            }
            ih.parentInputIdWhenScreenOff = null
            startTv(ih.initChannelUri)
            ih.initChannelUri = null
        }
        tvView.setBlockScreenType(activity.getDesiredBlockScreenType())
    }

    /** Startet die Wiedergabe auf [channelUri], sonst auf dem zuletzt gesehenen Kanal. */
    fun startTv(uri: Uri?) {
        var channelUri = uri
        if ((channelUri == null || !TvContract.isChannelUriForPassthroughInput(channelUri)) &&
            channelTuner.isCurrentChannelPassthrough
        ) {
            // Von Passthrough auf Tuner: Wiedergabe neu starten
            stopTv()
        }
        SoftPreconditions.checkState(
            (channelUri != null && TvContract.isChannelUriForPassthroughInput(channelUri)) || channelTuner.areAllChannelsLoaded(),
            TAG, "startTV assumes that ChannelDataManager is already loaded.")
        if (tvView.isPlaying) {
            // Läuft bereits: nichts tun, wenn kein anderer Kanal verlangt ist
            if (channelUri == null || channelUri == channelTuner.currentChannelUri) {
                activity.audioManagerHelper.setVolumeByAudioFocusStatus()
                return
            }
            stopTv()
        }
        if (channelTuner.currentChannel != null) {
            Log.w(TAG, "The current channel should be reset before")
            channelTuner.resetCurrentChannel()
        }
        if (channelUri == null) {
            val channelId = Utils.getLastWatchedChannelId(activity)
            if (channelId != Channel.INVALID_ID) channelUri = TvContract.buildChannelUri(channelId)
        }
        if (channelUri == null) {
            moveToNearestOrShowSettings()
        } else if (TvContract.isChannelUriForPassthroughInput(channelUri)) {
            channelTuner.moveToChannel(ChannelImpl.createPassthroughChannel(channelUri))
        } else {
            val channelId = ContentUris.parseId(channelUri)
            val channel = activity.channelDataManager.getChannel(channelId)
            if (channel == null || !channelTuner.moveToChannel(channel)) {
                Log.w(TAG, "The requested channel ($channelId) is not available, tuning to the nearest one")
                moveToNearestOrShowSettings()
            }
        }
        tvView.start()
        activity.audioManagerHelper.requestAudioFocus()
        tune(true)
    }

    private fun moveToNearestOrShowSettings() {
        if (!channelTuner.moveToChannel(channelTuner.findNearestBrowsableChannel(0))) {
            Log.w(TAG, "No browsable channel, show setup")
            activity.showSettingsFragment()
        }
    }

    @JvmOverloads
    fun stopTv(logForCaller: String? = null) {
        if (logForCaller != null) Log.i(TAG, "stopTv is called at $logForCaller.")
        if (tvView.isPlaying) {
            tvView.stop()
            activity.audioManagerHelper.abandonAudioFocus()
            activity.mediaSessionWrapper.setPlaybackState(false)
        }
        TvSingletons.getSingletons(activity).getMainActivityWrapper().notifyCurrentChannelChange(activity, null)
        channelTuner.resetCurrentChannel()
        tunePending = false
    }

    fun markCurrentChannelDuringScreenOff() {
        intentHandler.initChannelUri = channelTuner.currentChannelUri
        if (channelTuner.isCurrentChannelPassthrough) {
            intentHandler.parentInputIdWhenScreenOff = channelTuner.currentInputInfo?.parentId
        }
    }

    /** Tunt auf den aktuellen Kanal des ChannelTuners (inkl. Ersteinrichtungs-/Hinweislogik). */
    fun tune(updateChannelBanner: Boolean) {
        tuneDurationTimer.start()
        activity.lazyInitializeIfNeeded()
        if (inputIdUnderSetup != null) {
            tunePending = true
            return
        }
        tunePending = false
        if (TvFeatures.TUNER_SIGNAL_STRENGTH) {
            tvView.resetChannelSignalStrength()
            overlayManager.updateChannelBannerAndShowIfNeeded(TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_UPDATE_SIGNAL_STRENGTH)
        }
        val channel = channelTuner.currentChannel
        SoftPreconditions.checkState(channel != null, TAG, "channel is null")
        if (channel == null) return

        val setupUtils = activity.setupUtils
        if (!channelTuner.isCurrentChannelPassthrough) {
            if (activity.tvInputManagerHelper.getTunerTvInputSize() == 0) {
                Toast.makeText(activity, R.string.msg_no_input, Toast.LENGTH_SHORT).show()
                activity.finish()
                return
            }
            if (setupUtils.isFirstTune) {
                if (!channelTuner.areAllChannelsLoaded()) {
                    // Erst nach dem Laden der Kanäle neu tunen
                    stopTv("tune()")
                    return
                }
                if (activity.channelDataManager.channelCount > 0) {
                    overlayManager.showIntroDialog()
                } else {
                    activity.startOnboardingActivity()
                    return
                }
            }
            showNewSourcesFragment = false
            if (channelTuner.browsableChannelCount == 0 && activity.channelDataManager.channelCount > 0 &&
                !overlayManager.sideFragmentManager.isActive
            ) {
                if (!channelTuner.areAllChannelsLoaded()) return
                if (activity.tvInputManagerHelper.getTunerTvInputSize() == 1) {
                    overlayManager.sideFragmentManager.show(CustomizeChannelListFragment())
                } else {
                    overlayManager.showSetupFragment()
                }
                return
            }
            if (showNewSourcesFragment && setupUtils.hasUnrecognizedInput(activity.tvInputManagerHelper)) {
                // Neue Quellen zeigen, sobald Intro/andere Overlays geschlossen sind
                activity.runAfterAttachedToWindow {
                    overlayManager.runAfterOverlaysAreClosed { overlayManager.showNewSourcesFragment() }
                }
            }
            setupUtils.onTuned()
            intentHandler.tuneParams?.let { params ->
                val initChannelId = params.getLong(IntentHandler.KEY_INIT_CHANNEL_ID)
                if (initChannelId == channel.id) params.remove(IntentHandler.KEY_INIT_CHANNEL_ID)
                else intentHandler.tuneParams = null
            }
        }

        isCurrentChannelUnblockedByUser = false
        if (!isUnderShrunkenTvView()) lastAllowedRatingForCurrentChannel = null
        // Für Screenreader ansagen
        sendAccessibilityText(
            if (channelTuner.isCurrentChannelPassthrough) {
                Utils.loadLabel(activity, activity.tvInputManagerHelper.getTvInputInfo(channel.inputId)).orEmpty()
            } else {
                channel.displayText
            })

        val success = tvView.tuneTo(channel, intentHandler.tuneParams, onTuneListener)
        onTuneListener.onTune(channel, isUnderShrunkenTvView())
        intentHandler.tuneParams = null
        if (!success) {
            Toast.makeText(activity, R.string.msg_tune_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isUnderShrunkenTvView()) {
            if (!channel.isPassthrough) addToRecentChannels(channel.id)
            Utils.setLastWatchedChannel(activity, channel)
            TvSingletons.getSingletons(activity).getMainActivityWrapper().notifyCurrentChannelChange(activity, channel)
        }
        checkChannelLockNeeded(tvView, channel)
        if (updateChannelBanner) {
            overlayManager.updateChannelBannerAndShowIfNeeded(TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_TUNE)
        }
        activity.mediaSessionWrapper.update(tvView.isBlocked, activity.currentChannel, activity.currentProgram)
    }

    private fun sendAccessibilityText(text: String) {
        val am = activity.getSystemService(AccessibilityManager::class.java)
        if (!am.isEnabled) return
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain().apply {
            className = activity.javaClass.name
            packageName = activity.packageName
            eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
            getText().add(text)
        }
        am.sendAccessibilityEvent(event)
    }

    fun tuneToChannel(channel: Channel?) {
        if (channel == null) {
            if (tvView.isPlaying) tvView.reset()
            return
        }
        when {
            !tvView.isPlaying -> startTv(channel.uri)
            channel == tvView.currentChannel ->
                // Schon getunt: nur Banner zeigen
                overlayManager.updateChannelBannerAndShowIfNeeded(TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_TUNE)
            channel == channelTuner.currentChannel ->
                // Schnelles Umschalten: Banner zeigt den Kanal bereits
                tune(false)
            channelTuner.moveToChannel(channel) -> tune(true)
            else -> activity.showSettingsFragment()
        }
    }

    fun tuneToLastWatchedChannelForTunerInput() {
        if (!channelTuner.isCurrentChannelPassthrough) return
        stopTv()
        startTv(null)
    }

    fun moveToAdjacentChannel(channelUp: Boolean, fastTuning: Boolean) {
        if (channelTuner.moveToAdjacentBrowsableChannel(channelUp)) {
            overlayManager.updateChannelBannerAndShowIfNeeded(
                if (fastTuning) TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_TUNE_FAST
                else TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_TUNE)
        }
    }

    private fun addToRecentChannels(channelId: Long) {
        if (!recentChannels.remove(channelId) && recentChannels.size >= MAX_RECENT_CHANNELS) {
            recentChannels.removeLast()
        }
        recentChannels.addFirst(channelId)
        overlayManager.menu.onRecentChannelsChanged()
    }

    /** Sperrt den Bildschirm bei gesperrtem Kanal (Kindersicherung an), sonst entsperren. */
    fun checkChannelLockNeeded(view: TunableTvView, channel: Channel?) {
        val current = channel ?: view.currentChannel
        if (!view.isPlaying || current == null) return
        val block = activity.isParentalControlsEnabled() && current.isLocked && !showLockedChannelsTemporarily &&
            !(isUnderShrunkenTvView() && current == channelBeforeShrunkenTvView && wasChannelUnblockedBeforeShrunkenByUser)
        blockOrUnblockScreen(view, block)
    }

    private fun blockOrUnblockScreen(view: TunableTvView, block: Boolean) {
        view.blockOrUnblockScreen(block)
        if (view === tvView) {
            overlayManager.updateChannelBannerAndShowIfNeeded(TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_LOCK_OR_UNLOCK)
            activity.mediaSessionWrapper.update(block, activity.currentChannel, activity.currentProgram)
        }
    }

    fun onPinChecked(checked: Boolean, type: Int, rating: String?) {
        if (checked) {
            when (type) {
                PinDialogFragment.PIN_DIALOG_TYPE_UNLOCK_CHANNEL -> {
                    blockOrUnblockScreen(tvView, false)
                    isCurrentChannelUnblockedByUser = true
                }
                PinDialogFragment.PIN_DIALOG_TYPE_UNLOCK_PROGRAM -> {
                    val unblocked = TvContentRating.unflattenFromString(rating)
                    lastAllowedRatingForCurrentChannel = unblocked
                    tvView.unblockContent(unblocked)
                }
                // ENTER_PIN öffnete die Kindersicherungs-Einstellungen (entfällt, nur System-App)
                PinDialogFragment.PIN_DIALOG_TYPE_NEW_PIN -> overlayManager.sideFragmentManager.showSidePanel(true)
            }
        } else if (type == PinDialogFragment.PIN_DIALOG_TYPE_ENTER_PIN) {
            overlayManager.sideFragmentManager.hideAll(false)
        }
    }

    // ---- Verkleinerte TvView (Einstellungen/Kanalliste) ----

    fun startShrunkenTvView(showLockedChannelsTemporarily: Boolean, willMainViewBeTunerInput: Boolean) {
        channelBeforeShrunkenTvView = tvView.currentChannel
        wasChannelUnblockedBeforeShrunkenByUser = isCurrentChannelUnblockedByUser
        allowedRatingBeforeShrunken = lastAllowedRatingForCurrentChannel
        activity.tvViewUiManager.startShrunkenTvView()
        if (showLockedChannelsTemporarily) {
            this.showLockedChannelsTemporarily = true
            checkChannelLockNeeded(tvView, null)
        }
        tvView.setBlockScreenType(activity.getDesiredBlockScreenType())
    }

    fun endShrunkenTvView() {
        activity.tvViewUiManager.endShrunkenTvView()
        isCompletingShrunkenTvView = true
        var returnChannel = channelBeforeShrunkenTvView
        // Extended: Quelle – auch Kanäle außerhalb der gewählten Quelle gelten als nicht sichtbar
        if (returnChannel == null || (!returnChannel.isPassthrough && !activity.channelDataManager.isVisible(returnChannel))) {
            // Kanal wurde ausgeblendet: nächsten sichtbaren nehmen
            returnChannel = getBrowsableChannel()
        }
        showLockedChannelsTemporarily = false
        if (tvView.currentChannel != returnChannel) {
            val channel = returnChannel
            activity.tvViewUiManager.fadeOutTvView {
                tuneToChannel(channel)
                if (channelBeforeShrunkenTvView == null || channelBeforeShrunkenTvView != channel) {
                    Utils.setLastWatchedChannel(activity, channel)
                }
                finishShrunken()
            }
        } else {
            checkChannelLockNeeded(tvView, null)
            finishShrunken()
        }
    }

    private fun finishShrunken() {
        isCompletingShrunkenTvView = false
        isCurrentChannelUnblockedByUser = wasChannelUnblockedBeforeShrunkenByUser
        tvView.setBlockScreenType(activity.getDesiredBlockScreenType())
    }

    fun isUnderShrunkenTvView(): Boolean = activity.tvViewUiManager.isUnderShrunkenTvView || isCompletingShrunkenTvView

    private fun getBrowsableChannel(): Channel? {
        val current = channelTuner.currentChannel
        // Extended: Quelle
        return if (current != null && activity.channelDataManager.isVisible(current)) current else channelTuner.getAdjacentBrowsableChannel(true)
    }

    private fun updateAvailabilityToast() {
        if (tvView.isVideoAvailable || tvView.currentChannel != channelTuner.currentChannel) return
        when (tvView.videoUnavailableReason) {
            TunableTvView.VIDEO_UNAVAILABLE_REASON_NOT_TUNED,
            TunableTvView.VIDEO_UNAVAILABLE_REASON_NO_RESOURCE,
            TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING,
            TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING,
            TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY,
            TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> return
            CommonConstants.VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED ->
                Toast.makeText(activity, R.string.msg_channel_unavailable_not_connected, Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(activity, R.string.msg_channel_unavailable_unknown, Toast.LENGTH_SHORT).show()
        }
    }

    inner class MyOnTuneListener : TunableTvView.OnTuneListener {
        private var unlockAllowedRatingBeforeShrunken = true
        private var wasUnderShrunkenTvView = false
        private var channel: Channel? = null

        internal fun onTune(channel: Channel, wasUnderShrunkenTvView: Boolean) {
            this.channel = channel
            this.wasUnderShrunkenTvView = wasUnderShrunkenTvView
            activity.programDataManager.onChannelTuned(channel.id)
        }

        override fun onUnexpectedStop(channel: Channel?) {
            stopTv()
            startTv(null)
        }

        override fun onTuneFailed(channel: Channel?) {
            Log.w(TAG, "onTuneFailed($channel)")
            if (tvView.isFadedOut) tvView.removeFadeEffect()
            Toast.makeText(activity, R.string.msg_channel_unavailable_unknown, Toast.LENGTH_SHORT).show()
        }

        override fun onStreamInfoChanged(info: StreamInfo, allowAutoSelectionOfTrack: Boolean) {
            if (info.isVideoAvailable && tuneDurationTimer.isRunning) tuneDurationTimer.reset()
            if (info.isVideoOrAudioAvailable && channel == activity.currentChannel) {
                overlayManager.updateChannelBannerAndShowIfNeeded(TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_UPDATE_STREAM_INFO)
            }
            val tracks = activity.trackController
            tracks.applyDisplayRefreshRate(info.videoFrameRate)
            activity.tvViewUiManager.updateTvAspectRatio()
            tracks.applyMultiAudio(
                allowAutoSelectionOfTrack,
                if (allowAutoSelectionOfTrack) null else tracks.getSelectedTrack(TvTrackInfo.TYPE_AUDIO))
            tracks.applyClosedCaption()
            overlayManager.menu.onStreamInfoChanged()
            overlayManager.updateInputBannerIfNeeded(info)
            if (tvView.isVideoAvailable) activity.tvViewUiManager.fadeInTvView()
            if (!tvView.isContentBlocked && !tvView.isScreenBlocked) updateAvailabilityToast()
        }

        override fun onChannelRetuned(channel: Uri?) {
            if (channel == null) return
            val currentChannel = activity.channelDataManager.getChannel(ContentUriUtils.safeParseId(channel))
            if (currentChannel == null) {
                Log.e(TAG, "onChannelRetuned is called but can't find a channel with the URI $channel")
                return
            }
            if (activity.keyHandler.isChannelChangeKeyDownReceived()) return // Umschalten läuft
            channelTuner.currentChannel = currentChannel
            tvView.currentChannel = currentChannel
            overlayManager.updateChannelBannerAndShowIfNeeded(TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_TUNE)
        }

        override fun onContentBlocked() {
            tuneDurationTimer.reset()
            val rating = tvView.blockedContentRating
            // Vor dem Verkleinern freigegebene Freigabe wieder erlauben
            if (wasUnderShrunkenTvView && unlockAllowedRatingBeforeShrunken &&
                channelBeforeShrunkenTvView == channel && rating == allowedRatingBeforeShrunken
            ) {
                unlockAllowedRatingBeforeShrunken = isUnderShrunkenTvView()
                tvView.unblockContent(rating!!)
            }
            overlayManager.setBlockingContentRating(rating)
            activity.tvViewUiManager.fadeInTvView()
            activity.mediaSessionWrapper.update(true, activity.currentChannel, activity.currentProgram)
        }

        override fun onContentAllowed() {
            if (!isUnderShrunkenTvView()) unlockAllowedRatingBeforeShrunken = false
            overlayManager.setBlockingContentRating(null)
            activity.mediaSessionWrapper.update(false, activity.currentChannel, activity.currentProgram)
        }

        override fun onChannelSignalStrength() {
            if (TvFeatures.TUNER_SIGNAL_STRENGTH) {
                overlayManager.updateChannelBannerAndShowIfNeeded(TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_UPDATE_SIGNAL_STRENGTH)
            }
        }

        @RequiresApi(33)
        override fun onAitInfoUpdated(inputId: String, aitInfo: AitInfo) {
            activity.iAppManager?.onAitInfoUpdated(aitInfo)
        }
    }

    companion object {
        private const val TAG = "TuningController"
        private const val MAX_RECENT_CHANNELS = 5
    }
}
