package com.android.tv.ui

import android.content.Intent
import android.media.tv.TvContentRating
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.annotation.UiThread
import com.android.tv.ChannelTuner
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.TimeShiftManager
import com.android.tv.TvOptionsManager
import com.android.tv.TvSingletons
import com.android.tv.common.ui.setup.OnActionClickListener
import com.android.tv.common.ui.setup.SetupFragment
import com.android.tv.common.ui.setup.SetupMultiPaneFragment
import com.android.tv.data.ChannelDataManager
import com.android.tv.data.ProgramDataManager
import com.android.tv.data.StreamInfo
import com.android.tv.dialog.DvrHistoryDialogFragment
import com.android.tv.dialog.FullscreenDialogFragment
import com.android.tv.dialog.HalfSizedDialogFragment
import com.android.tv.dialog.PinDialogFragment
import com.android.tv.dialog.RecentlyWatchedDialogFragment
import com.android.tv.dialog.SafeDismissDialogFragment
import com.android.tv.dvr.ui.browse.DvrBrowseActivity
import com.android.tv.features.TvFeatures
import com.android.tv.guide.ProgramGuide
import com.android.tv.license.LicenseDialogFragment
import com.android.tv.menu.Menu
import com.android.tv.menu.MenuRowFactory
import com.android.tv.menu.MenuView
import com.android.tv.menu.TvOptionsRowAdapter
import com.android.tv.onboarding.NewSourcesFragment
import com.android.tv.onboarding.SetupSourcesFragment
import com.android.tv.search.ProgramGuideSearchFragment
import com.android.tv.ui.sidepanel.SideFragmentManager
import com.android.tv.util.TvInputManagerHelper
import java.util.LinkedList

/**
 * Port von TvOverlayManager: steuert alle Overlays über der TvView (Menü, Seitenleisten,
 * Programmübersicht, Dialoge, Banner-Szenen, Einrichtungs-Fragmente) und verteilt Tasten.
 * Framework-Fragments → AndroidX-Fragments. Entfällt: Ratings-Attributionsdialog (Kindersicherung),
 * Analytics-Tracker.
 */
@UiThread
class TvOverlayManager(
    private val mainActivity: MainActivity,
    private val channelTuner: ChannelTuner,
    private val tvView: TunableTvView,
    optionsManager: TvOptionsManager,
    private val keypadChannelSwitchView: KeypadChannelSwitchView?,
    private val channelBannerView: ChannelBannerView,
    private val inputBannerView: InputBannerViewBase,
    private val selectInputView: SelectInputView,
    sceneContainer: ViewGroup,
    private val searchFragment: ProgramGuideSearchFragment,
    private val channelDataManager: ChannelDataManager,
    private val inputManager: TvInputManagerHelper,
    programDataManager: ProgramDataManager,
    tvOptionsRowAdapterFactory: TvOptionsRowAdapter.Factory,
) : AccessibilityStateChangeListener {

    private val transitionManager = TvTransitionManager(
        mainActivity, sceneContainer, channelBannerView, inputBannerView, keypadChannelSwitchView, selectInputView)
    val menu: Menu
    val sideFragmentManager: SideFragmentManager
    val programGuide: ProgramGuide

    var currentDialog: SafeDismissDialogFragment? = null
        private set
    var isSetupFragmentActive = false
        private set
    var isNewSourcesFragmentActive = false
        private set
    private var channelBannerHiddenBySideFragment = false
    private val handler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == MSG_OVERLAY_CLOSED) handleOverlayClosed()
        true
    }
    private var openedOverlays = OVERLAY_TYPE_NONE
    private val pendingActions = ArrayList<Runnable>()
    private val pendingDialogActionQueue = LinkedList<PendingDialogAction>()
    private var onBackStackChangedListener: FragmentManager.OnBackStackChangedListener? = null

    private val fragmentManager: FragmentManager get() = mainActivity.supportFragmentManager

    init {
        transitionManager.setListener(object : TvTransitionManager.Listener {
            override fun onSceneChanged(fromScene: Int, toScene: Int) {
                if (toScene != TvTransitionManager.SCENE_TYPE_EMPTY) onOverlayOpened(convertSceneToOverlayType(toScene))
                if (fromScene != TvTransitionManager.SCENE_TYPE_EMPTY) onOverlayClosed(convertSceneToOverlayType(fromScene))
            }
        })
        val menuView: MenuView = mainActivity.findViewById(R.id.menu)
        menu = Menu(
            mainActivity, tvView, optionsManager, menuView,
            MenuRowFactory(mainActivity, tvView, tvOptionsRowAdapterFactory),
        ) { visible -> if (visible) onOverlayOpened(OVERLAY_TYPE_MENU) else onOverlayClosed(OVERLAY_TYPE_MENU) }
        menu.setChannelTuner(channelTuner)
        sideFragmentManager = SideFragmentManager(
            mainActivity,
            {
                onOverlayOpened(OVERLAY_TYPE_SIDE_FRAGMENT)
                hideOverlays(FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANELS)
            },
            {
                showChannelBannerIfHiddenBySideFragment()
                onOverlayClosed(OVERLAY_TYPE_SIDE_FRAGMENT)
            },
        )
        val singletons = TvSingletons.getSingletons(mainActivity)
        val dvrDataManager = if (TvFeatures.isDvrEnabled(mainActivity)) singletons.getDvrDataManager() else null
        programGuide = ProgramGuide(
            mainActivity, channelTuner, inputManager, channelDataManager, programDataManager,
            dvrDataManager, singletons.getDvrScheduleManager(),
            { onOverlayOpened(OVERLAY_TYPE_GUIDE) },
            { onOverlayClosed(OVERLAY_TYPE_GUIDE) },
        )
        mainActivity.addOnActionClickListener(object : OnActionClickListener {
            override fun onActionClick(category: String, id: Int, params: Bundle?): Boolean {
                when (category) {
                    SetupSourcesFragment.ACTION_CATEGORY -> when (id) {
                        SetupMultiPaneFragment.ACTION_DONE -> {
                            closeSetupFragment(true)
                            return true
                        }
                        SetupSourcesFragment.ACTION_ONLINE_STORE -> {
                            mainActivity.showMerchantCollection()
                            return true
                        }
                        SetupSourcesFragment.ACTION_SETUP_INPUT -> {
                            val inputId = params?.getString(SetupSourcesFragment.ACTION_PARAM_KEY_INPUT_ID)
                            mainActivity.startSetupActivity(inputManager.getTvInputInfo(inputId), true)
                            return true
                        }
                    }
                    NewSourcesFragment.ACTION_CATEOGRY -> when (id) {
                        NewSourcesFragment.ACTION_SETUP -> {
                            closeNewSourcesFragment(false)
                            showSetupFragment()
                            return true
                        }
                        NewSourcesFragment.ACTION_SKIP -> {
                            closeNewSourcesFragment(true)
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    fun release() {
        menu.release()
        handler.removeCallbacksAndMessages(null)
        keypadChannelSwitchView?.setChannels(null)
    }

    private fun getSetupSourcesFragment(): Fragment? = fragmentManager.findFragmentByTag(FRAGMENT_TAG_SETUP_SOURCES)
    private fun getNewSourcesFragment(): Fragment? = fragmentManager.findFragmentByTag(FRAGMENT_TAG_NEW_SOURCES)

    fun showMenu(reason: Int) {
        if (channelTuner.areAllChannelsLoaded()) menu.show(reason)
    }

    /** Zeigt das Menü, wenn Timeshift pausiert ist. */
    fun showMenuWithTimeShiftPauseIfNeeded(): Boolean {
        if (mainActivity.timeShiftManager.isPaused) {
            showMenu(Menu.REASON_PLAY_CONTROLS_PAUSE)
            return true
        }
        return false
    }

    /** Zeigt einen Dialog; läuft schon einer, wird er in die Warteschlange gestellt. */
    @JvmOverloads
    fun showDialogFragment(
        tag: String, dialog: SafeDismissDialogFragment, keepSidePanelHistory: Boolean, keepProgramGuide: Boolean = false,
    ) {
        var flags = FLAG_HIDE_OVERLAYS_KEEP_DIALOG
        if (keepSidePanelHistory) flags = flags or FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANEL_HISTORY
        if (keepProgramGuide) flags = flags or FLAG_HIDE_OVERLAYS_KEEP_PROGRAM_GUIDE
        hideOverlays(flags)
        if (tag !in AVAILABLE_DIALOG_TAGS) return
        if (currentDialog != null) {
            pendingDialogActionQueue.offer(PendingDialogAction(tag, dialog, keepSidePanelHistory, keepProgramGuide))
            return
        }
        currentDialog = dialog
        dialog.show(fragmentManager, tag)
        onOverlayOpened(OVERLAY_TYPE_DIALOG)
    }

    fun onBrowsableChannelsUpdated() {
        keypadChannelSwitchView?.setChannels(channelTuner.getBrowsableChannelList())
    }

    private fun runAfterSideFragmentsAreClosed(runnable: Runnable) {
        if (!sideFragmentManager.isSidePanelVisible) {
            runnable.run()
            return
        }
        val listener = object : FragmentManager.OnBackStackChangedListener {
            override fun onBackStackChanged() {
                if (fragmentManager.backStackEntryCount == 0) {
                    fragmentManager.removeOnBackStackChangedListener(this)
                    onBackStackChangedListener = null
                    runnable.run()
                }
            }
        }
        onBackStackChangedListener = listener
        fragmentManager.addOnBackStackChangedListener(listener)
    }

    private fun showFragment(fragment: Fragment, tag: String) {
        hideOverlays(FLAG_HIDE_OVERLAYS_KEEP_FRAGMENT)
        onOverlayOpened(OVERLAY_TYPE_FRAGMENT)
        runAfterSideFragmentsAreClosed {
            fragmentManager.beginTransaction().replace(R.id.fragment_container, fragment, tag).commit()
        }
    }

    private fun closeFragment(fragmentTagToRemove: String?) {
        onOverlayClosed(OVERLAY_TYPE_FRAGMENT)
        if (fragmentTagToRemove == null) return
        val fragment = fragmentManager.findFragmentByTag(fragmentTagToRemove)
        if (fragment == null) {
            // Noch nicht hinzugefügt: wartenden Listener entfernen
            onBackStackChangedListener?.let { fragmentManager.removeOnBackStackChangedListener(it) }
            onBackStackChangedListener = null
        } else {
            fragmentManager.beginTransaction().remove(fragment).commit()
        }
    }

    fun showSetupFragment() {
        isSetupFragmentActive = true
        val setupFragment = SetupSourcesFragment().apply {
            enableFragmentTransition(
                SetupFragment.FRAGMENT_ENTER_TRANSITION or SetupFragment.FRAGMENT_EXIT_TRANSITION or
                    SetupFragment.FRAGMENT_RETURN_TRANSITION or SetupFragment.FRAGMENT_REENTER_TRANSITION)
            setFragmentTransition(SetupFragment.FRAGMENT_EXIT_TRANSITION, Gravity.END)
        }
        showFragment(setupFragment, FRAGMENT_TAG_SETUP_SOURCES)
    }

    /** Ohne Kanäle nach der Einrichtung wird die App beendet. */
    private fun closeSetupFragment(removeFragment: Boolean) {
        if (!isSetupFragmentActive) return
        isSetupFragmentActive = false
        closeFragment(if (removeFragment) FRAGMENT_TAG_SETUP_SOURCES else null)
        if (channelDataManager.channelCount == 0) mainActivity.finish()
    }

    fun showNewSourcesFragment() {
        isNewSourcesFragmentActive = true
        showFragment(NewSourcesFragment(), FRAGMENT_TAG_NEW_SOURCES)
    }

    private fun closeNewSourcesFragment(removeFragment: Boolean) {
        if (!isNewSourcesFragmentActive) return
        isNewSourcesFragmentActive = false
        closeFragment(if (removeFragment) FRAGMENT_TAG_NEW_SOURCES else null)
    }

    fun showDvrManager() = mainActivity.startActivity(Intent(mainActivity, DvrBrowseActivity::class.java))

    fun showIntroDialog() = showDialogFragment(
        FullscreenDialogFragment.DIALOG_TAG,
        FullscreenDialogFragment.newInstance(R.layout.intro_dialog, INTRO_TRACKER_LABEL), false)

    fun showRecentlyWatchedDialog() =
        showDialogFragment(RecentlyWatchedDialogFragment.DIALOG_TAG, RecentlyWatchedDialogFragment(), false)

    fun showDvrHistoryDialog() =
        showDialogFragment(DvrHistoryDialogFragment.DIALOG_TAG, DvrHistoryDialogFragment(), false)

    fun showBanner() = transitionManager.goToChannelBannerScene()

    fun showKeypadChannelSwitch(keyCode: Int) {
        if (!channelTuner.areAllChannelsLoaded()) return
        hideOverlays(FLAG_HIDE_OVERLAYS_KEEP_SCENE or FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANELS or
            FLAG_HIDE_OVERLAYS_KEEP_DIALOG or FLAG_HIDE_OVERLAYS_KEEP_FRAGMENT)
        transitionManager.goToKeypadChannelSwitchScene()
        keypadChannelSwitchView?.onNumberKeyUp(keyCode - KeyEvent.KEYCODE_0)
    }

    /** Tweak: Senderliste per Hoch/Runter, Nachbarsender vorausgewählt, Umschalten erst mit OK. */
    fun showKeypadChannelBrowse(up: Boolean) {
        if (!channelTuner.areAllChannelsLoaded()) return
        hideOverlays(FLAG_HIDE_OVERLAYS_KEEP_SCENE or FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANELS or
            FLAG_HIDE_OVERLAYS_KEEP_DIALOG or FLAG_HIDE_OVERLAYS_KEEP_FRAGMENT)
        transitionManager.goToKeypadChannelSwitchScene()
        keypadChannelSwitchView?.startBrowse(channelTuner.currentChannel, if (up) -1 else 1)
    }

    fun showSelectInputView() {
        hideOverlays(FLAG_HIDE_OVERLAYS_KEEP_SCENE)
        transitionManager.goToSelectInputScene()
    }

    fun initAnimatorIfNeeded() = transitionManager.initIfNeeded()

    /** Vom Dialog beim Zerstören aufgerufen: nächsten Dialog zeigen oder Overlay schließen. */
    fun onDialogDestroyed() {
        currentDialog = null
        pendingDialogActionQueue.poll()?.run() ?: onOverlayClosed(OVERLAY_TYPE_DIALOG)
    }

    fun showProgramGuide() = programGuide.show { hideOverlays(FLAG_HIDE_OVERLAYS_KEEP_PROGRAM_GUIDE) }

    /** true, wenn die Programmübersicht danach sichtbar ist. */
    fun toggleProgramGuide(): Boolean {
        if (programGuide.isActive) {
            programGuide.onBackPressed()
            return false
        }
        showProgramGuide()
        return true
    }

    fun setBlockingContentRating(rating: TvContentRating?) {
        if (!mainActivity.isChannelChangeKeyDownReceived) {
            channelBannerView.setBlockingContentRating(rating)
            updateChannelBannerAndShowIfNeeded(UPDATE_CHANNEL_BANNER_REASON_LOCK_OR_UNLOCK)
        }
    }

    val isOverlayOpened: Boolean get() = openedOverlays != OVERLAY_TYPE_NONE

    /** Schließt Overlays außer den per [flags] ausgenommenen. */
    fun hideOverlays(flags: Int) {
        var f = flags
        if (mainActivity.needToKeepSetupScreenWhenHidingOverlay()) f = f or FLAG_HIDE_OVERLAYS_KEEP_FRAGMENT
        if (f and FLAG_HIDE_OVERLAYS_KEEP_DIALOG == 0) {
            currentDialog?.let { if (it is PinDialogFragment) it.dismissSilently() else it.dismiss() }
            pendingDialogActionQueue.clear()
            currentDialog = null
        }
        val withAnimation = f and FLAG_HIDE_OVERLAYS_WITHOUT_ANIMATION == 0
        if (f and FLAG_HIDE_OVERLAYS_KEEP_FRAGMENT == 0) {
            if (isSetupFragmentActive) {
                if (!withAnimation) getSetupSourcesFragment()?.apply { returnTransition = null; exitTransition = null }
                closeSetupFragment(true)
            }
            if (isNewSourcesFragmentActive) {
                if (!withAnimation) getNewSourcesFragment()?.apply { returnTransition = null; exitTransition = null }
                closeNewSourcesFragment(true)
            }
        }
        if (f and FLAG_HIDE_OVERLAYS_KEEP_MENU == 0) menu.hide(withAnimation)
        if (f and FLAG_HIDE_OVERLAYS_KEEP_SCENE == 0) transitionManager.goToEmptyScene(withAnimation)
        if (f and FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANELS == 0 && sideFragmentManager.isActive) {
            if (f and FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANEL_HISTORY != 0) sideFragmentManager.hideSidePanel(withAnimation)
            else sideFragmentManager.hideAll(withAnimation)
        }
        if (f and FLAG_HIDE_OVERLAYS_KEEP_PROGRAM_GUIDE == 0) programGuide.hide()
    }

    override fun onAccessibilityStateChanged(enabled: Boolean) {
        channelBannerView.onAccessibilityStateChanged(enabled)
        programGuide.onAccessibilityStateChanged(enabled)
        sideFragmentManager.onAccessibilityStateChanged(enabled)
    }

    fun needHideTextOnMainView(): Boolean =
        sideFragmentManager.isActive || menu.isActive || transitionManager.isKeypadChannelSwitchActive ||
            transitionManager.isSelectInputActive || isSetupFragmentActive || isNewSourcesFragmentActive

    /** Aktualisiert das Kanal-Banner je nach Anlass (inkl. Sperrart) und zeigt es ggf. an. */
    fun updateChannelBannerAndShowIfNeeded(reason: Int) {
        if (mainActivity.isChannelChangeKeyDownReceived &&
            reason != UPDATE_CHANNEL_BANNER_REASON_TUNE && reason != UPDATE_CHANNEL_BANNER_REASON_TUNE_FAST
        ) return
        if (!channelTuner.isCurrentChannelPassthrough) {
            val parentalEnabled = mainActivity.isParentalControlsEnabled()
            val lockType = when {
                reason == UPDATE_CHANNEL_BANNER_REASON_TUNE_FAST ->
                    if (parentalEnabled && mainActivity.currentChannel?.isLocked == true) ChannelBannerView.LOCK_CHANNEL_INFO
                    else ChannelBannerView.LOCK_PROGRAM_DETAIL
                reason == UPDATE_CHANNEL_BANNER_REASON_TUNE ->
                    if (!parentalEnabled) ChannelBannerView.LOCK_NONE
                    else if (mainActivity.currentChannel?.isLocked == true) ChannelBannerView.LOCK_CHANNEL_INFO
                    else ChannelBannerView.LOCK_PROGRAM_DETAIL
                tvView.isScreenBlocked -> ChannelBannerView.LOCK_CHANNEL_INFO
                tvView.isContentBlocked || (parentalEnabled && !tvView.isVideoOrAudioAvailable) ->
                    ChannelBannerView.LOCK_PROGRAM_DETAIL
                else -> ChannelBannerView.LOCK_NONE
            }
            val previousLockType = channelBannerView.setLockType(lockType)
            if (previousLockType == lockType && reason == UPDATE_CHANNEL_BANNER_REASON_LOCK_OR_UNLOCK) {
                return
            } else if (reason == UPDATE_CHANNEL_BANNER_REASON_UPDATE_STREAM_INFO) {
                channelBannerView.updateStreamInfo(tvView)
                if (previousLockType == ChannelBannerView.LOCK_PROGRAM_DETAIL &&
                    lockType != ChannelBannerView.LOCK_PROGRAM_DETAIL
                ) channelBannerView.updateViews(false)
            } else if (TvFeatures.TUNER_SIGNAL_STRENGTH && reason == UPDATE_CHANNEL_BANNER_REASON_UPDATE_SIGNAL_STRENGTH) {
                channelBannerView.updateChannelSignalStrengthView(tvView.channelSignalStrength)
            } else {
                channelBannerView.updateViews(
                    reason == UPDATE_CHANNEL_BANNER_REASON_TUNE || reason == UPDATE_CHANNEL_BANNER_REASON_TUNE_FAST)
            }
        }
        val needToShowBanner = reason == UPDATE_CHANNEL_BANNER_REASON_FORCE_SHOW ||
            reason == UPDATE_CHANNEL_BANNER_REASON_TUNE || reason == UPDATE_CHANNEL_BANNER_REASON_TUNE_FAST
        if (needToShowBanner && !mainActivity.willShowOverlayUiWhenResume() && currentDialog == null &&
            !isSetupFragmentActive && !isNewSourcesFragmentActive
        ) {
            when {
                channelTuner.currentChannel == null -> channelBannerHiddenBySideFragment = false
                sideFragmentManager.isActive -> channelBannerHiddenBySideFragment = true
                else -> {
                    channelBannerHiddenBySideFragment = false
                    showBanner()
                }
            }
        }
    }

    fun updateInputBannerIfNeeded(info: StreamInfo) {
        if (transitionManager.isInputBannerActive) inputBannerView.onStreamInfoUpdated(info)
    }

    private fun convertSceneToOverlayType(sceneType: Int): Int = when (sceneType) {
        TvTransitionManager.SCENE_TYPE_CHANNEL_BANNER -> OVERLAY_TYPE_SCENE_CHANNEL_BANNER
        TvTransitionManager.SCENE_TYPE_INPUT_BANNER -> OVERLAY_TYPE_SCENE_INPUT_BANNER
        TvTransitionManager.SCENE_TYPE_KEYPAD_CHANNEL_SWITCH -> OVERLAY_TYPE_SCENE_KEYPAD_CHANNEL_SWITCH
        TvTransitionManager.SCENE_TYPE_SELECT_INPUT -> OVERLAY_TYPE_SCENE_SELECT_INPUT
        else -> OVERLAY_TYPE_NONE
    }

    private fun onOverlayOpened(overlayType: Int) {
        openedOverlays = openedOverlays or overlayType
        handler.removeMessages(MSG_OVERLAY_CLOSED)
        mainActivity.updateKeyInputFocus()
    }

    private fun onOverlayClosed(overlayType: Int) {
        openedOverlays = openedOverlays and overlayType.inv()
        handler.removeMessages(MSG_OVERLAY_CLOSED)
        mainActivity.updateKeyInputFocus()
        if (canExecuteCloseAction()) handler.sendEmptyMessage(MSG_OVERLAY_CLOSED)
    }

    private fun showChannelBannerIfHiddenBySideFragment() {
        if (channelBannerHiddenBySideFragment) updateChannelBannerAndShowIfNeeded(UPDATE_CHANNEL_BANNER_REASON_FORCE_SHOW)
    }

    private fun canExecuteCloseAction() = mainActivity.isActivityResumed && isOnlyBannerOrNoneOpened()

    private fun isOnlyBannerOrNoneOpened() =
        openedOverlays and OVERLAY_TYPE_SCENE_CHANNEL_BANNER.inv() and OVERLAY_TYPE_SCENE_INPUT_BANNER.inv() == 0

    /** Führt [action] aus, sobald nur noch Banner (oder nichts) offen sind. */
    fun runAfterOverlaysAreClosed(action: Runnable) {
        if (canExecuteCloseAction()) action.run() else pendingActions.add(action)
    }

    fun onUserInteraction() {
        when {
            sideFragmentManager.isActive -> sideFragmentManager.scheduleHideAll()
            menu.isActive -> menu.scheduleHide()
            programGuide.isActive -> programGuide.scheduleHide()
        }
    }

    private fun handleOverlayClosed() {
        if (!canExecuteCloseAction()) return
        // Bugfix: bei Pause das Menü nach dem Schließen anderer Overlays nicht mehr erzwingen
        if (pendingActions.isNotEmpty()) pendingActions.removeAt(0).run()
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Int {
        if (currentDialog != null) return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
        if (isMediaStartKey(keyCode)) return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
        if (menu.isActive || sideFragmentManager.isActive || programGuide.isActive ||
            isSetupFragmentActive || isNewSourcesFragmentActive
        ) return MainActivity.KEY_EVENT_HANDLER_RESULT_DISPATCH_TO_OVERLAY
        if (transitionManager.isKeypadChannelSwitchActive) {
            return if (keypadChannelSwitchView?.onKeyDown(keyCode, event) == true) MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            else MainActivity.KEY_EVENT_HANDLER_RESULT_NOT_HANDLED
        }
        if (transitionManager.isSelectInputActive) {
            return if (selectInputView.onKeyDown(keyCode, event)) MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            else MainActivity.KEY_EVENT_HANDLER_RESULT_NOT_HANDLED
        }
        return MainActivity.KEY_EVENT_HANDLER_RESULT_PASSTHROUGH
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Int {
        if (isMediaStartKey(keyCode)) {
            if (currentDialog != null || programGuide.isActive || sideFragmentManager.isActive ||
                searchFragment.isVisible || transitionManager.isKeypadChannelSwitchActive ||
                transitionManager.isSelectInputActive || isSetupFragmentActive || isNewSourcesFragmentActive
            ) return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            if (tvView.isScreenBlocked) return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            val tsm: TimeShiftManager = mainActivity.timeShiftManager
            if (!tsm.isAvailable) return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> { tsm.play(); showMenu(Menu.REASON_PLAY_CONTROLS_PLAY) }
                KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    tsm.pause(); showMenu(Menu.REASON_PLAY_CONTROLS_PAUSE)
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { tsm.togglePlayPause(); showMenu(Menu.REASON_PLAY_CONTROLS_PLAY_PAUSE) }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { tsm.rewind(); showMenu(Menu.REASON_PLAY_CONTROLS_REWIND) }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { tsm.fastForward(); showMenu(Menu.REASON_PLAY_CONTROLS_FAST_FORWARD) }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    tsm.jumpToPrevious(); showMenu(Menu.REASON_PLAY_CONTROLS_JUMP_TO_PREVIOUS)
                }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    tsm.jumpToNext(); showMenu(Menu.REASON_PLAY_CONTROLS_JUMP_TO_NEXT)
                }
            }
            return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
        }
        if (keyCode == KeyEvent.KEYCODE_I || keyCode == KeyEvent.KEYCODE_TV_INPUT) {
            if (transitionManager.isSelectInputActive) selectInputView.onKeyUp(keyCode, event) else showSelectInputView()
            return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
        }
        if (currentDialog != null) return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
        if (programGuide.isActive) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                programGuide.onBackPressed()
                return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            }
            return MainActivity.KEY_EVENT_HANDLER_RESULT_DISPATCH_TO_OVERLAY
        }
        if (sideFragmentManager.isActive) {
            if (keyCode == KeyEvent.KEYCODE_BACK || sideFragmentManager.isHideKeyForCurrentPanel(keyCode)) {
                sideFragmentManager.popSideFragment()
                return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            }
            return MainActivity.KEY_EVENT_HANDLER_RESULT_DISPATCH_TO_OVERLAY
        }
        if (menu.isActive || transitionManager.isSceneActive) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // Bugfix: Zurück setzt eine Pause nicht mehr fort (Original rief hier play() auf)
                hideOverlays(FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANELS or FLAG_HIDE_OVERLAYS_KEEP_DIALOG or
                    FLAG_HIDE_OVERLAYS_KEEP_FRAGMENT)
                return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            }
            if (menu.isActive) {
                if (KeypadChannelSwitchView.isChannelNumberKey(keyCode)) {
                    showKeypadChannelSwitch(keyCode)
                    return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
                }
                return MainActivity.KEY_EVENT_HANDLER_RESULT_DISPATCH_TO_OVERLAY
            }
        }
        if (transitionManager.isKeypadChannelSwitchActive) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                transitionManager.goToEmptyScene(true)
                return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            }
            return if (keypadChannelSwitchView?.onKeyUp(keyCode, event) == true) MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            else MainActivity.KEY_EVENT_HANDLER_RESULT_NOT_HANDLED
        }
        if (transitionManager.isSelectInputActive) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                transitionManager.goToEmptyScene(true)
                return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            }
            return if (selectInputView.onKeyUp(keyCode, event)) MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            else MainActivity.KEY_EVENT_HANDLER_RESULT_NOT_HANDLED
        }
        if (isSetupFragmentActive) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeSetupFragment(true)
                return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            }
            return MainActivity.KEY_EVENT_HANDLER_RESULT_DISPATCH_TO_OVERLAY
        }
        if (isNewSourcesFragmentActive) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeNewSourcesFragment(true)
                return MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED
            }
            return MainActivity.KEY_EVENT_HANDLER_RESULT_DISPATCH_TO_OVERLAY
        }
        return MainActivity.KEY_EVENT_HANDLER_RESULT_PASSTHROUGH
    }

    private inner class PendingDialogAction(
        val tag: String, val dialog: SafeDismissDialogFragment, val keepSidePanelHistory: Boolean, val keepProgramGuide: Boolean,
    ) {
        fun run() = showDialogFragment(tag, dialog, keepSidePanelHistory, keepProgramGuide)
    }

    companion object {
        private const val TAG = "TvOverlayManager"
        private const val INTRO_TRACKER_LABEL = "Intro dialog"

        const val FLAG_HIDE_OVERLAYS_DEFAULT = 0b000000000
        const val FLAG_HIDE_OVERLAYS_WITHOUT_ANIMATION = 0b000000010
        const val FLAG_HIDE_OVERLAYS_KEEP_SCENE = 0b000000100
        const val FLAG_HIDE_OVERLAYS_KEEP_DIALOG = 0b000001000
        const val FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANELS = 0b000010000
        const val FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANEL_HISTORY = 0b000100000
        const val FLAG_HIDE_OVERLAYS_KEEP_PROGRAM_GUIDE = 0b001000000
        const val FLAG_HIDE_OVERLAYS_KEEP_MENU = 0b010000000
        const val FLAG_HIDE_OVERLAYS_KEEP_FRAGMENT = 0b100000000

        private const val MSG_OVERLAY_CLOSED = 1000

        private const val OVERLAY_TYPE_NONE = 0b000000000
        private const val OVERLAY_TYPE_MENU = 0b000000001
        private const val OVERLAY_TYPE_SIDE_FRAGMENT = 0b000000010
        private const val OVERLAY_TYPE_DIALOG = 0b000000100
        private const val OVERLAY_TYPE_GUIDE = 0b000001000
        private const val OVERLAY_TYPE_SCENE_CHANNEL_BANNER = 0b000010000
        private const val OVERLAY_TYPE_SCENE_INPUT_BANNER = 0b000100000
        private const val OVERLAY_TYPE_SCENE_KEYPAD_CHANNEL_SWITCH = 0b001000000
        private const val OVERLAY_TYPE_SCENE_SELECT_INPUT = 0b010000000
        private const val OVERLAY_TYPE_FRAGMENT = 0b100000000

        const val UPDATE_CHANNEL_BANNER_REASON_FORCE_SHOW = 1
        const val UPDATE_CHANNEL_BANNER_REASON_TUNE = 2
        const val UPDATE_CHANNEL_BANNER_REASON_TUNE_FAST = 3
        const val UPDATE_CHANNEL_BANNER_REASON_UPDATE_INFO = 4
        const val UPDATE_CHANNEL_BANNER_REASON_LOCK_OR_UNLOCK = 5
        const val UPDATE_CHANNEL_BANNER_REASON_UPDATE_STREAM_INFO = 6
        const val UPDATE_CHANNEL_BANNER_REASON_UPDATE_SIGNAL_STRENGTH = 7

        private const val FRAGMENT_TAG_SETUP_SOURCES = "tag_setup_sources"
        private const val FRAGMENT_TAG_NEW_SOURCES = "tag_new_sources"

        private val AVAILABLE_DIALOG_TAGS = setOf(
            RecentlyWatchedDialogFragment.DIALOG_TAG,
            DvrHistoryDialogFragment.DIALOG_TAG,
            PinDialogFragment.DIALOG_TAG,
            FullscreenDialogFragment.DIALOG_TAG,
            LicenseDialogFragment.DIALOG_TAG,
            HalfSizedDialogFragment.DIALOG_TAG,
        )

        private fun isMediaStartKey(keyCode: Int): Boolean = keyCode in setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STOP,
        )
    }
}
