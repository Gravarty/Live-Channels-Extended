package com.android.tv

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.android.tv.common.memory.MemoryManageable
import com.android.tv.common.TvContentRatingCache
import com.android.tv.common.ui.setup.OnActionClickListener
import com.android.tv.data.ChannelDataManager
import com.android.tv.data.ChannelImpl
import com.android.tv.data.OnCurrentProgramUpdatedListener
import com.android.tv.data.ProgramDataManager
import com.android.tv.data.WatchedHistoryManager
import com.android.tv.data.api.Channel
import com.android.tv.data.api.Program
import com.android.tv.dialog.PinDialogFragment
import com.android.tv.dialog.PinDialogFragment.OnPinCheckedListener
import com.android.tv.dvr.DvrManager
import com.android.tv.dvr.ui.DvrUiHelper
import com.android.tv.common.singletons.HasSingletons
import com.android.tv.common.util.PermissionUtils
import com.android.tv.features.TvFeatures
import com.android.tv.main.IntentHandler
import com.android.tv.main.KeyHandler
import com.android.tv.main.TrackController
import com.android.tv.main.TuningController
import com.android.tv.modules.DbDispatcher
import com.android.tv.onboarding.OnboardingActivity
import com.android.tv.recommendation.ChannelPreviewUpdater
import com.android.tv.search.ProgramGuideSearchFragment
import com.android.tv.ui.ChannelBannerView
import com.android.tv.ui.InputBannerViewBase
import com.android.tv.ui.KeypadChannelSwitchView
import com.android.tv.ui.SelectInputView
import com.android.tv.ui.TunableTvView
import com.android.tv.ui.TvOverlayManager
import com.android.tv.ui.TvViewUiManager
import com.android.tv.util.CaptionSettings
import com.android.tv.ui.sidepanel.SettingsFragment
import com.android.tv.ui.sidepanel.SideFragment
import com.android.tv.menu.TvOptionsRowAdapter
import com.android.tv.audio.AudioManagerHelper
import com.android.tv.util.GtvUtils
import com.android.tv.util.OnboardingUtils
import com.android.tv.util.SetupUtils
import com.android.tv.util.TvInputManagerHelper
import com.android.tv.util.Utils
import com.android.tv.util.ViewCache
import com.android.tv.util.images.ImageCache
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Hauptbildschirm (Live-TV). Port von com.android.tv.MainActivity, aufgeteilt in:
 * IntentHandler (Start-Intents), TuningController (Wiedergabe/Tunen/Sperre), KeyHandler (Tasten),
 * TrackController (Ton/Untertitel/Film-Modus). Hier bleiben Lebenszyklus und Verdrahtung.
 *
 * Entfällt: eingebauter Tuner (BuiltInTunerManager, Netzwerk-Tuner-Suche, AudioCapabilities),
 * Cloud-EPG-Fetcher des Tuners, Recommendation-Service (vor Android 8), Startzeit-Messung,
 * StrictMode/Debug-Tasten (ENG), setMain() und requestVisibleBehind() (System-API bzw. wirkungslos).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity(), OnActionClickListener, OnPinCheckedListener, ChannelChanger,
    HasSingletons<MainActivity.MySingletons> {
    // Extended: Interaktive TV-Apps (TIAF/HbbTV) entfernt, auf dem Gerät kein Dienst vorhanden

    interface MySingletons : ChannelBannerView.MySingletons

    @Inject lateinit var channelDataManager: ChannelDataManager
    @Inject lateinit var programDataManager: ProgramDataManager
    @Inject lateinit var tvInputManagerHelper: TvInputManagerHelper
    @Inject lateinit var setupUtils: SetupUtils
    @Inject lateinit var tvOptionsRowAdapterFactory: TvOptionsRowAdapter.Factory
    @Inject @DbDispatcher lateinit var dbDispatcher: CoroutineDispatcher

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var accessibilityManager: AccessibilityManager
    private lateinit var channelTuner: ChannelTuner
    val tvOptionsManager = TvOptionsManager(this)
    lateinit var tvViewUiManager: TvViewUiManager
        private set
    lateinit var timeShiftManager: TimeShiftManager
        private set
    var dvrManager: DvrManager? = null
        private set
    lateinit var tvView: TunableTvView
        private set
    lateinit var contentView: View
        private set
    lateinit var overlayManager: TvOverlayManager
        private set
    lateinit var searchFragment: ProgramGuideSearchFragment
        private set
    lateinit var mediaSessionWrapper: MediaSessionWrapper
        private set
    lateinit var audioManagerHelper: AudioManagerHelper
        private set
    var captionSettings: CaptionSettings? = null

    lateinit var intentHandler: IntentHandler
        private set
    lateinit var tuningController: TuningController
        private set
    lateinit var keyHandler: KeyHandler
        private set
    lateinit var trackController: TrackController
        private set

    var isActivityResumed = false
        private set
    var isActivityStarted = false
        private set
    var otherActivityLaunched = false
    private var screenOffIntentReceived = false
    private var isInPipMode = false
    private var lazyInitialized = false
    private var initialized = false
    private val memoryManageables = ArrayList<MemoryManageable>()
    private val onActionClickListeners = LinkedHashSet<OnActionClickListener>()
    private val mySingletons = MySingletonsImpl()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Bildschirm aus: Wiedergabe stoppen, beim Einschalten neu starten
                    screenOffIntentReceived = true
                    tuningController.markCurrentChannelDuringScreenOff()
                    stopAll()
                }
                Intent.ACTION_SCREEN_ON -> Unit // requestVisibleBehind ist seit Android 8 wirkungslos
                TvInputManager.ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED -> {
                    applyParentalControlSettings()
                    tuningController.checkChannelLockNeeded(tvView, null)
                }
                Intent.ACTION_TIME_CHANGED -> {
                    // Programme neu laden
                    if (channelTuner.currentChannel != null) tuningController.tune(true)
                }
            }
        }
    }

    private val onCurrentProgramUpdatedListener = OnCurrentProgramUpdatedListener { channelId, program ->
        // Bei Timeshift kümmert sich TimeShiftManager um die Anzeige
        if (timeShiftManager.isAvailable) return@OnCurrentProgramUpdatedListener
        val channel = tvView.currentChannel
        if (channel != null && channel.id == channelId) {
            overlayManager.updateChannelBannerAndShowIfNeeded(TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_UPDATE_INFO)
            mediaSessionWrapper.update(tvView.isBlocked, channel, program)
        }
    }

    private val channelTunerListener = object : ChannelTuner.Listener {
        override fun onLoadFinished() {
            setupUtils.markNewChannelsBrowsableIfEnabled()
            if (isActivityResumed) tuningController.resumeTvIfNeeded()
            overlayManager.onBrowsableChannelsUpdated()
        }

        override fun onBrowsableChannelListChanged() = overlayManager.onBrowsableChannelsUpdated()

        override fun onCurrentChannelUnavailable(channel: Channel?) {
            if (channelTuner.moveToAdjacentBrowsableChannel(true)) tuningController.tune(true)
            else tuningController.stopTv("onCurrentChannelUnavailable()")
        }

        override fun onChannelChanged(previousChannel: Channel?, currentChannel: Channel?) {
            currentChannel?.let { GtvUtils.broadcastInputId(this@MainActivity, it.inputId) }
        }
    }

    override fun singletons(): MySingletons = mySingletons

    override fun onCreate(savedInstanceState: Bundle?) {
        Starter.start(this)
        super.onCreate(savedInstanceState)
        accessibilityManager = getSystemService(AccessibilityManager::class.java)
        if (!tvInputManagerHelper.hasTvInputManager()) {
            Log.wtf(TAG, "Stopping because device does not have a TvInputManager")
            finishAndRemoveTask()
            return
        }
        val isPassthroughInput = intent.data?.let { TvContract.isChannelUriForPassthroughInput(it) } == true
        val tuneToPassthroughInput = Intent.ACTION_VIEW == intent.action && isPassthroughInput
        val channelLoadedAndNoChannelAvailable = channelDataManager.isDbLoadFinished && channelDataManager.channelCount <= 0
        if ((OnboardingUtils.isFirstRunWithCurrentVersion(this) || channelLoadedAndNoChannelAvailable) && !tuneToPassthroughInput) {
            startOnboardingActivity()
            return
        }
        setContentView(R.layout.activity_tv)
        contentView = findViewById(android.R.id.content)
        tvView = findViewById(R.id.main_tunable_tv_view)
        tvView.initialize(programDataManager, tvInputManagerHelper)

        intentHandler = IntentHandler(this, dbDispatcher)
        channelTuner = ChannelTuner(channelDataManager, tvInputManagerHelper)
        tuningController = TuningController(this, tvView, channelTuner)
        keyHandler = KeyHandler(this, tvView, channelTuner)
        trackController = TrackController(this, tvView, tvOptionsManager)

        tvView.setOnUnhandledInputEventListener { event -> keyHandler.onUnhandledInputEvent(event) }
        tvView.setBlockedInfoOnClickListener { keyHandler.showPinDialogFragment() }
        // Input des zuletzt gesehenen Kanals schon vorab verbinden
        val lastChannelId = Utils.getLastWatchedChannelId(this)
        val lastInputId = Utils.getLastWatchedTunerInputId(this)
        if (!isPassthroughInput && lastInputId != null && lastChannelId != Channel.INVALID_ID) {
            tvView.warmUpInput(lastInputId, TvContract.buildChannelUri(lastChannelId))
        }
        TvSingletons.getSingletons(this).getMainActivityWrapper().onMainActivityCreated(this)

        programDataManager.addOnCurrentProgramUpdatedListener(Channel.INVALID_ID, onCurrentProgramUpdatedListener)
        programDataManager.setPrefetchEnabled(true)
        channelTuner.addListener(channelTunerListener)
        channelTuner.start()
        memoryManageables.add(programDataManager)
        memoryManageables.add(ImageCache.getInstance())
        memoryManageables.add(TvContentRatingCache.getInstance())
        if (TvFeatures.isDvrEnabled(this)) dvrManager = TvSingletons.getSingletons(this).getDvrManager()

        timeShiftManager = TimeShiftManager(this, tvView, programDataManager) { _, program ->
            mediaSessionWrapper.update(tvView.isBlocked, currentChannel, program)
            val reason = when (timeShiftManager.lastActionId) {
                TimeShiftManager.TIME_SHIFT_ACTION_ID_REWIND,
                TimeShiftManager.TIME_SHIFT_ACTION_ID_FAST_FORWARD,
                TimeShiftManager.TIME_SHIFT_ACTION_ID_JUMP_TO_PREVIOUS,
                TimeShiftManager.TIME_SHIFT_ACTION_ID_JUMP_TO_NEXT -> TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_FORCE_SHOW
                else -> TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_UPDATE_INFO
            }
            overlayManager.updateChannelBannerAndShowIfNeeded(reason)
        }

        if (!PermissionUtils.hasAccessWatchedHistory(this)) {
            // Eigenen Sehverlauf führen, wenn der TvProvider ihn nicht bereitstellt
            tvView.setWatchedHistoryManager(WatchedHistoryManager(applicationContext).also { it.start() })
        }
        tvViewUiManager = TvViewUiManager(this, tvView, contentView as android.widget.FrameLayout, tvOptionsManager)

        val sceneContainer: ViewGroup = findViewById(R.id.scene_container)
        val inflater = layoutInflater
        val channelBannerView = inflater.inflate(R.layout.channel_banner, sceneContainer, false) as ChannelBannerView
        val keypadChannelSwitchView =
            inflater.inflate(R.layout.keypad_channel_switch, sceneContainer, false) as KeypadChannelSwitchView
        val inputBannerLayoutId = if (TvFeatures.useGtvLiveTvV2(this)) R.layout.input_banner_v2 else R.layout.input_banner
        val inputBannerView = inflater.inflate(inputBannerLayoutId, sceneContainer, false) as InputBannerViewBase
        val selectInputView = inflater.inflate(R.layout.select_input, sceneContainer, false) as SelectInputView
        selectInputView.setOnInputSelectedCallback(object : SelectInputView.OnInputSelectedCallback {
            override fun onTunerInputSelected() {
                val current = channelTuner.currentChannel
                if (current != null && !current.isPassthrough) hideOverlaysForInputSelection()
                else tuningController.tuneToLastWatchedChannelForTunerInput()
            }

            override fun onPassthroughInputSelected(input: TvInputInfo) {
                if (TextUtils.equals(input.id, channelTuner.currentChannel?.inputId)) hideOverlaysForInputSelection()
                else tuningController.tuneToChannel(ChannelImpl.createPassthroughChannel(input.id))
            }

            private fun hideOverlaysForInputSelection() = overlayManager.hideOverlays(
                TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_DIALOG or TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANELS or
                    TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_PROGRAM_GUIDE or TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_MENU or
                    TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_FRAGMENT)
        })
        searchFragment = ProgramGuideSearchFragment()
        overlayManager = TvOverlayManager(
            this, channelTuner, tvView, tvOptionsManager, keypadChannelSwitchView, channelBannerView,
            inputBannerView, selectInputView, sceneContainer, searchFragment, channelDataManager,
            tvInputManagerHelper, programDataManager, tvOptionsRowAdapterFactory)
        accessibilityManager.addAccessibilityStateChangeListener(overlayManager)

        audioManagerHelper = AudioManagerHelper(this, tvView)
        val nowPlayingIntent = PendingIntent.getActivity(
            this, REQUEST_CODE_NOW_PLAYING, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        mediaSessionWrapper = MediaSessionWrapper(this, nowPlayingIntent)

        tvViewUiManager.restoreDisplayMode(false)
        initialized = true
        if (!intentHandler.handleIntent(intent)) {
            finish()
            return
        }
        // SHOW_UPCOMING_CONFLICT_DIALOG ist im Original OFF → kein ConflictChecker
    }

    fun startOnboardingActivity() {
        startActivity(OnboardingActivity.buildIntent(this, intent))
        finish()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val density = resources.displayMetrics.density
        tvViewUiManager.onConfigurationChanged((newConfig.screenWidthDp * density).toInt(), (newConfig.screenHeightDp * density).toInt())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST_READ_TV_LISTINGS) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Mit Berechtigung alles neu laden und Activity neu starten
            channelDataManager.reload()
            programDataManager.reload()
            val restart = intent
            finish()
            startActivity(restart)
        } else {
            Toast.makeText(this, R.string.msg_read_tv_listing_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Welche Art Sperrbildschirm gerade passt (keine UI, verkleinert, normal). */
    fun getDesiredBlockScreenType(): Int {
        if (!isActivityResumed) return TunableTvView.BLOCK_SCREEN_TYPE_NO_UI
        if (tuningController.isUnderShrunkenTvView()) return TunableTvView.BLOCK_SCREEN_TYPE_SHRUNKEN_TV_VIEW
        if (overlayManager.needHideTextOnMainView()) return TunableTvView.BLOCK_SCREEN_TYPE_NO_UI
        val dialog = overlayManager.currentDialog
        if (dialog != null) {
            // PIN-Dialog zum Entsperren: Sperrbildschirm sichtbar lassen
            if (dialog is PinDialogFragment &&
                (dialog.type == PinDialogFragment.PIN_DIALOG_TYPE_UNLOCK_CHANNEL ||
                    dialog.type == PinDialogFragment.PIN_DIALOG_TYPE_UNLOCK_PROGRAM)
            ) return TunableTvView.BLOCK_SCREEN_TYPE_NORMAL
            return TunableTvView.BLOCK_SCREEN_TYPE_NO_UI
        }
        if (overlayManager.isSetupFragmentActive || overlayManager.isNewSourcesFragmentActive) {
            return TunableTvView.BLOCK_SCREEN_TYPE_NO_UI
        }
        return TunableTvView.BLOCK_SCREEN_TYPE_NORMAL
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!initialized) return // onCreate hat die Activity schon beendet
        overlayManager.sideFragmentManager.hideAll(false)
        if (!intentHandler.handleIntent(intent) && !isActivityStarted) finish()
    }

    override fun onStart() {
        super.onStart()
        screenOffIntentReceived = false
        isActivityStarted = true
        applyParentalControlSettings()
        val filter = IntentFilter().apply {
            addAction(TvInputManager.ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_TIME_CHANGED)
        }
        registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        isInPipMode = false
        if (!PermissionUtils.hasAccessAllEpg(this) &&
            checkSelfPermission(PermissionUtils.PERMISSION_READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(PermissionUtils.PERMISSION_READ_TV_LISTINGS), PERMISSIONS_REQUEST_READ_TV_LISTINGS)
        }
        keyHandler.needShowBackKeyGuide = true
        isActivityResumed = true
        tuningController.onResume()
        otherActivityLaunched = false
        audioManagerHelper.requestAudioFocus()

        val failedInfo = Utils.getFailedScheduledRecordingInfoSet(applicationContext)
        if (Utils.hasRecordingFailedReason(applicationContext, TvInputManager.RECORDING_ERROR_INSUFFICIENT_SPACE) &&
            failedInfo.isNotEmpty()
        ) {
            runAfterAttachedToWindow { DvrUiHelper.showDvrInsufficientSpaceErrorDialog(this, failedInfo) }
        }
        if (channelTuner.areAllChannelsLoaded()) {
            setupUtils.markNewChannelsBrowsableIfEnabled()
            tuningController.resumeTvIfNeeded()
        }
        overlayManager.showMenuWithTimeShiftPauseIfNeeded()

        val ih = intentHandler
        val inputToSetUp = ih.inputToSetUp
        when {
            inputToSetUp != null -> {
                startSetupActivity(inputToSetUp, false)
                ih.inputToSetUp = null
            }
            ih.showProgramGuide -> {
                ih.showProgramGuide = false
                // Nach dem Tunen anzeigen (vermeidet Verzögerung der Animation)
                handler.post { overlayManager.showProgramGuide() }
            }
            ih.showSelectInputView -> {
                ih.showSelectInputView = false
                handler.post { overlayManager.showSelectInputView() }
            }
        }
    }

    override fun onPause() {
        keyHandler.finishChannelChangeIfNeeded()
        isActivityResumed = false
        overlayManager.hideOverlays(TvOverlayManager.FLAG_HIDE_OVERLAYS_DEFAULT)
        tvView.setBlockScreenType(TunableTvView.BLOCK_SCREEN_TYPE_NO_UI)
        keyHandler.onPause()
        tuningController.onPause()
        if (!isInPipMode) {
            audioManagerHelper.abandonAudioFocus()
            mediaSessionWrapper.setPlaybackState(false)
        }
        super.onPause()
    }

    override fun onPinChecked(checked: Boolean, type: Int, rating: String?) =
        tuningController.onPinChecked(checked, type, rating)

    override fun onStop() {
        if (screenOffIntentReceived) {
            screenOffIntentReceived = false
        } else if (!getSystemService(PowerManager::class.java).isInteractive) {
            // Bildschirm-aus kann vor ACTION_SCREEN_OFF ankommen
            tuningController.markCurrentChannelDuringScreenOff()
        }
        if (channelTuner.isCurrentChannelPassthrough) intentHandler.initChannelUri = channelTuner.currentChannelUri
        isActivityStarted = false
        stopAll()
        unregisterReceiver(broadcastReceiver)
        super.onStop()
    }

    private fun stopAll() {
        overlayManager.hideOverlays(TvOverlayManager.FLAG_HIDE_OVERLAYS_WITHOUT_ANIMATION)
        tuningController.stopTv("stopAll()")
    }

    /** Öffnet die Einrichtung eines Inputs (über SetupPassthroughActivity). */
    fun startSetupActivity(input: TvInputInfo?, calledByPopup: Boolean) {
        val intent = input?.let { setupUtils.createSetupIntent(this, it) }
        if (input == null || intent == null) {
            Toast.makeText(this, R.string.msg_no_setup_activity, Toast.LENGTH_SHORT).show()
            return
        }
        // Nach der Einrichtung Kanäle freischalten
        intent.component = ComponentName(this, SetupPassthroughActivity::class.java)
        try {
            // Input braucht Schreibrechte auf die EPG-Daten
            SetupUtils.grantEpgPermission(this, input.serviceInfo.packageName)
            tuningController.inputIdUnderSetup = input.id
            tuningController.isSetupActivityCalledByPopup = calledByPopup
            // Wiedergabe stoppen, sonst blockiert die Session evtl. die Einrichtung
            tuningController.stopTv("startSetupActivity()")
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_START_SETUP_ACTIVITY)
        } catch (e: ActivityNotFoundException) {
            tuningController.inputIdUnderSetup = null
            Toast.makeText(this, getString(R.string.msg_unable_to_start_setup_activity, input.loadLabel(this)),
                Toast.LENGTH_SHORT).show()
            return
        }
        overlayManager.hideOverlays(
            TvOverlayManager.FLAG_HIDE_OVERLAYS_WITHOUT_ANIMATION or
                if (calledByPopup) TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_FRAGMENT
                else TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANEL_HISTORY)
    }

    fun hasCaptioningSettingsActivity(): Boolean = Utils.isIntentAvailable(this, Intent(Settings.ACTION_CAPTIONING_SETTINGS))

    fun startSystemCaptioningSettingsActivity() {
        try {
            startActivitySafe(Intent(Settings.ACTION_CAPTIONING_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.msg_unable_to_start_system_captioning_settings), Toast.LENGTH_SHORT).show()
        }
    }

    val currentChannel: Channel? get() = channelTuner.currentChannel
    val currentChannelId: Long get() = channelTuner.currentChannelId

    /** Aktuelle Sendung – bei Timeshift die an der Wiedergabeposition. */
    val currentProgram: Program?
        get() = if (!keyHandler.isChannelChangeKeyDownReceived() && timeShiftManager.isAvailable) {
            timeShiftManager.currentProgram
        } else {
            programDataManager.getCurrentProgram(currentChannelId)
        }

    val currentPlayingPosition: Long
        get() = if (timeShiftManager.isAvailable) timeShiftManager.currentPositionMs else System.currentTimeMillis()

    fun startActivitySafe(intent: Intent) = LauncherActivity.startActivitySafe(this, intent)

    fun showSettingsFragment() {
        if (!channelTuner.areAllChannelsLoaded()) return // gesperrte Kanäle sind noch unbekannt
        overlayManager.sideFragmentManager.show(SettingsFragment())
    }

    /** "Weitere Kanäle": im AOSP-Build ohne URL, daher nur ein Log-Eintrag. */
    fun showMerchantCollection() {
        val onlineStoreIntent = OnboardingUtils.createOnlineStoreIntent()
        if (onlineStoreIntent != null) startActivitySafe(onlineStoreIntent)
        else Log.w(TAG, "Unable to show merchant collection, more channels url is not valid.")
    }

    fun startShrunkenTvView(showLockedChannelsTemporarily: Boolean, willMainViewBeTunerInput: Boolean) =
        tuningController.startShrunkenTvView(showLockedChannelsTemporarily, willMainViewBeTunerInput)

    fun endShrunkenTvView() = tuningController.endShrunkenTvView()

    fun isScreenBlockedByResourceConflictOrParentalControl(): Boolean =
        tvView.videoUnavailableReason == TunableTvView.VIDEO_UNAVAILABLE_REASON_NO_RESOURCE || tvView.isBlocked

    @Deprecated("Wie im Original über onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_START_SETUP_ACTIVITY) {
            val tc = tuningController
            if (resultCode == RESULT_OK) {
                val count = channelDataManager.getChannelCountForInput(tc.inputIdUnderSetup.orEmpty())
                val text = if (count > 0) resources.getQuantityString(R.plurals.msg_channel_added, count, count)
                else getString(R.string.msg_no_channel_added)
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
                tc.inputIdUnderSetup = null
                if (channelTuner.currentChannel == null) channelTuner.moveToAdjacentBrowsableChannel(true)
                if (tc.tunePending) tc.tune(true)
            } else {
                tc.inputIdUnderSetup = null
            }
            if (!tc.isSetupActivityCalledByPopup) overlayManager.sideFragmentManager.showSidePanel(false)
        }
        data?.getStringExtra(LauncherActivity.ERROR_MESSAGE)?.takeIf { it.isNotEmpty() }?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        keyHandler.dispatchKeyEvent(event) { super.dispatchKeyEvent(it) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keyHandler.onKeyDown(keyCode, event) ?: super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        keyHandler.onKeyUp(keyCode, event) ?: super.onKeyUp(keyCode, event)

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean = keyHandler.onKeyLongPress(keyCode, event)

    fun updateKeyInputFocus() {
        handler.post { tvView.setBlockScreenType(getDesiredBlockScreenType()) }
    }

    /** Führt [runnable] aus, sobald das Fenster angehängt ist – nur wenn die Activity resumed ist. */
    fun runAfterAttachedToWindow(runnable: Runnable) {
        val runIfResumed = Runnable { if (isActivityResumed) runnable.run() }
        if (contentView.isAttachedToWindow) {
            handler.post(runIfResumed)
            return
        }
        contentView.viewTreeObserver.addOnWindowAttachListener(object : ViewTreeObserver.OnWindowAttachListener {
            override fun onWindowAttached() {
                contentView.viewTreeObserver.removeOnWindowAttachListener(this)
                handler.post(runIfResumed)
            }

            override fun onWindowDetached() {}
        })
    }

    fun isNowPlayingProgram(channel: Channel?, program: Program?): Boolean =
        if (program == null) channel != null && currentProgram == null && channel == currentChannel
        else program == currentProgram

    fun getRecentChannels(): ArrayDeque<Long> = tuningController.recentChannels

    fun hideOverlaysForTune() = overlayManager.hideOverlays(TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_SCENE)

    fun needToKeepSetupScreenWhenHidingOverlay(): Boolean =
        tuningController.inputIdUnderSetup != null && tuningController.isSetupActivityCalledByPopup

    fun showProgramGuideSearchFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, searchFragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Keine Zustandssicherung – die Activity baut sich neu auf (wie im Original)
    }

    override fun onDestroy() {
        SideFragment.releaseRecycledViewPool()
        ViewCache.getInstance().clear()
        if (initialized) {
            tvView.release()
            channelTuner.removeListener(channelTunerListener)
            channelTuner.stop()
            programDataManager.removeOnCurrentProgramUpdatedListener(Channel.INVALID_ID, onCurrentProgramUpdatedListener)
            val wrapper = TvSingletons.getSingletons(this).getMainActivityWrapper()
            if (wrapper.isCurrent(this)) programDataManager.setPrefetchEnabled(false)
            accessibilityManager.removeAccessibilityStateChangeListener(overlayManager)
            overlayManager.release()
            mediaSessionWrapper.release()
            keyHandler.release()
            wrapper.onMainActivityDestroyed(this)
        }
        memoryManageables.clear()
        handler.removeCallbacksAndMessages(null)
        tvInputManagerHelper.clearTvInputLabels()
        super.onDestroy()
    }

    override fun channelDown() = keyHandler.channelDown()
    override fun channelUp() = keyHandler.channelUp()

    val isChannelChangeKeyDownReceived: Boolean get() = keyHandler.isChannelChangeKeyDownReceived()

    fun isKeyEventBlocked(): Boolean = keyHandler.isKeyEventBlocked()

    fun tuneToChannel(channel: Channel?) = tuningController.tuneToChannel(channel)

    fun stopTv() = tuningController.stopTv()

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (initialized) overlayManager.onUserInteraction()
    }

    /** Bild-in-Bild: Overlays vorher ohne Animation schließen. */
    override fun enterPictureInPictureMode() {
        isInPipMode = true
        if (overlayManager.isOverlayOpened) {
            overlayManager.hideOverlays(TvOverlayManager.FLAG_HIDE_OVERLAYS_WITHOUT_ANIMATION)
            handler.post { super.enterPictureInPictureMode() }
        } else {
            super.enterPictureInPictureMode()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) keyHandler.finishChannelChangeIfNeeded()
    }

    @Deprecated("Wie im Original")
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        otherActivityLaunched = true
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode)
    }

    fun getTracks(type: Int) = trackController.getTracks(type)
    fun getSelectedTrack(type: Int) = trackController.getSelectedTrack(type)
    fun selectAudioTrack(trackId: String?) = trackController.selectAudioTrack(trackId)
    fun selectSubtitleTrack(option: Int, trackId: String?) = trackController.selectSubtitleTrack(option, trackId)
    fun selectSubtitleLanguage(option: Int, language: String?, trackId: String?) =
        trackController.selectSubtitleLanguage(option, language, trackId)

    fun willShowOverlayUiWhenResume(): Boolean =
        intentHandler.inputToSetUp != null || intentHandler.showProgramGuide || intentHandler.showSelectInputView

    /** Ersetzt getParentalControlSettings().isParentalControlsEnabled() (öffentliche API). */
    fun isParentalControlsEnabled(): Boolean =
        getSystemService(TvInputManager::class.java)?.isParentalControlsEnabled == true

    private fun applyParentalControlSettings() {
        tvView.onParentalControlChanged(isParentalControlsEnabled())
        ChannelPreviewUpdater.getInstance(this).updatePreviewDataForChannelsImmediately()
    }

    fun addOnActionClickListener(listener: OnActionClickListener) { onActionClickListeners.add(listener) }
    fun removeOnActionClickListener(listener: OnActionClickListener) { onActionClickListeners.remove(listener) }

    override fun onActionClick(category: String, id: Int, params: Bundle?): Boolean =
        onActionClickListeners.any { it.onActionClick(category, id, params) }

    /** Animationen, Seitenleisten und Menüeinträge 1 s nach dem ersten Tunen vorbereiten. */
    fun lazyInitializeIfNeeded() {
        if (lazyInitialized) return
        lazyInitialized = true
        handler.postDelayed({
            if (isActivityStarted) {
                tvViewUiManager.initAnimatorIfNeeded()
                overlayManager.initAnimatorIfNeeded()
                SideFragment.preloadItemViews(this)
                overlayManager.menu.preloadItemViews()
            }
        }, LAZY_INITIALIZATION_DELAY_MS)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        memoryManageables.forEach { it.performTrimMemory(level) }
    }

    private inner class MySingletonsImpl : MySingletons {
        override fun getCurrentChannelProvider() = Provider { currentChannel }
        override fun getCurrentProgramProvider() = Provider { currentProgram }
        override fun getOverlayManagerProvider() = Provider { overlayManager }
        override fun getTvInputManagerHelperSingleton() = tvInputManagerHelper
        override fun getCurrentPlayingPositionProvider() = Provider { currentPlayingPosition }
        override fun getDvrManagerSingleton() = TvSingletons.getSingletons(applicationContext).getDvrManager()
    }

    companion object {
        private const val TAG = "MainActivity"

        const val KEY_EVENT_HANDLER_RESULT_PASSTHROUGH = 0
        const val KEY_EVENT_HANDLER_RESULT_NOT_HANDLED = 1
        const val KEY_EVENT_HANDLER_RESULT_HANDLED = 2
        const val KEY_EVENT_HANDLER_RESULT_DISPATCH_TO_OVERLAY = 3

        private const val PERMISSIONS_REQUEST_READ_TV_LISTINGS = 1
        private const val REQUEST_CODE_START_SETUP_ACTIVITY = 1
        private const val REQUEST_CODE_NOW_PLAYING = 2
        private const val LAZY_INITIALIZATION_DELAY_MS = 1000L
    }
}
