package com.android.tv.main

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.util.Log
import android.widget.Toast
import com.android.tv.ChannelTuner
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.data.api.Channel
import com.android.tv.dialog.HalfSizedDialogFragment
import com.android.tv.dialog.PinDialogFragment
import com.android.tv.dvr.ui.DvrStopRecordingFragment
import com.android.tv.dvr.ui.DvrUiHelper
import com.android.tv.menu.Menu
import com.android.tv.tweaks.Tweaks
import com.android.tv.ui.KeypadChannelSwitchView
import com.android.tv.ui.TunableTvView
import com.android.tv.ui.TvOverlayManager
import com.android.tv.ui.sidepanel.ClosedCaptionFragment
import com.android.tv.ui.sidepanel.MultiAudioFragment

/**
 * Aus MainActivity ausgelagert: Tastenverarbeitung (Fernbedienung), Dauer-Umschalten mit
 * Beschleunigung und Weitergabe an die TV-Input-Session. Logik 1:1.
 * Entfällt: Debug-Tasten (nur ENG-Build/Entwickleroptionen) und Tasten-Logging.
 */
class KeyHandler(
    private val activity: MainActivity,
    private val tvView: TunableTvView,
    private val channelTuner: ChannelTuner,
) {
    private val overlayManager get() = activity.overlayManager
    private val tuning get() = activity.tuningController
    private var backKeyPressed = false
    var needShowBackKeyGuide = false

    private val handler = Handler(Looper.getMainLooper()) { msg -> handleMessage(msg); true }

    /** Tasten, die nie an die TV-Input-Session gehen. */
    private val blocklistKeycodeToTis = setOf(
        KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_WINDOW,
    )

    // Tweak: true, sobald OK-Langdruck das Menü geöffnet hat; restliche OK-Events bis ACTION_UP werden verworfen
    private var okLongPressHandled = false

    // Tweak: Bestätigen zum Beenden
    private var lastExitBackPressMs = 0L
    private var exitToast: Toast? = null

    fun onPause() { backKeyPressed = false }

    /** Tasten, die die TvView nicht verarbeitet hat. */
    fun onUnhandledInputEvent(event: InputEvent): Boolean {
        if (isKeyEventBlocked()) return true
        if (event !is KeyEvent) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.isLongPress && onKeyLongPress(event.keyCode, event)) return true
        return when (event.action) {
            KeyEvent.ACTION_UP -> activity.onKeyUp(event.keyCode, event)
            KeyEvent.ACTION_DOWN -> activity.onKeyDown(event.keyCode, event)
            else -> false
        }
    }

    /** Aus Activity.dispatchKeyEvent; [superDispatch] = Standardverarbeitung der Activity. */
    fun dispatchKeyEvent(event: KeyEvent, superDispatch: (KeyEvent) -> Boolean): Boolean {
        // Bugfix: Guide-Taste der Fernbedienung öffnet/schließt die Programmübersicht. AOSP bekommt sie nur
        // als globale Taste (GLOBAL_BUTTON), die Android ausschließlich an die System-TV-App sendet.
        if (event.keyCode in GUIDE_KEYCODES) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) overlayManager.toggleProgramGuide()
            return true
        }
        // Tweak: Wiederholungen und ACTION_UP des OK-Langdrucks nicht ans geöffnete Menü weitergeben
        if (okLongPressHandled && isOkKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) okLongPressHandled = false
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // BACK_UP ohne BACK_DOWN ignorieren (von einer anderen Activity übrig)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backKeyPressed = true
            if (!backKeyPressed) return true
            if (event.action == KeyEvent.ACTION_UP) backKeyPressed = false
        }
        val side = overlayManager.sideFragmentManager
        if ((activity.contentView.hasFocusable() && !side.isHiding) || side.isActive) return superDispatch(event)
        if (event.keyCode in blocklistKeycodeToTis || KeyEvent.isGamepadButton(event.keyCode)) return superDispatch(event)
        return dispatchKeyEventToSession(event) || superDispatch(event)
    }

    private fun dispatchKeyEventToSession(event: KeyEvent): Boolean {
        val handled = tvView.dispatchKeyEvent(event)
        if (isKeyEventBlocked()) {
            if ((event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B) && needShowBackKeyGuide) {
                // Hinweis: BACK wird an den Passthrough-Input weitergegeben
                Toast.makeText(activity, R.string.msg_back_key_guide, Toast.LENGTH_SHORT).show()
                needShowBackKeyGuide = false
            }
            return true
        }
        return handled
    }

    /** Bei Passthrough-Inputs (HDMI) gehen alle Tasten an den Input. */
    fun isKeyEventBlocked(): Boolean = channelTuner.isCurrentChannelPassthrough

    /** null = an super.onKeyDown weitergeben. */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean? {
        when (overlayManager.onKeyDown(keyCode, event)) {
            MainActivity.KEY_EVENT_HANDLER_RESULT_DISPATCH_TO_OVERLAY -> return null
            MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED -> return true
            MainActivity.KEY_EVENT_HANDLER_RESULT_NOT_HANDLED -> return false
        }
        if (activity.searchFragment.isVisible) return null
        if (!channelTuner.areAllChannelsLoaded()) return false
        if (!channelTuner.isCurrentChannelPassthrough) {
            // Tweak: OK öffnet die Programmübersicht (in onKeyUp), OK lang das Menü
            if (isOkKey(keyCode) && Tweaks.isOkOpensGuide(activity) && channelTuner.browsableChannelCount > 0) {
                if (event.isLongPress && !okLongPressHandled) {
                    okLongPressHandled = true
                    overlayManager.updateChannelBannerAndShowIfNeeded(TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_FORCE_SHOW)
                    overlayManager.showMenu(Menu.REASON_NONE)
                }
                return true
            }
            // Tweak: Hoch/Runter öffnet die Senderliste statt umzuschalten
            if ((keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
                Tweaks.isDpadChannelList(activity) && channelTuner.browsableChannelCount > 0
            ) {
                if (event.repeatCount == 0) overlayManager.showKeypadChannelBrowse(keyCode == KeyEvent.KEYCODE_DPAD_UP)
                return true
            }
            when (keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.repeatCount == 0 && channelTuner.browsableChannelCount > 0) channelUpPressed()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.repeatCount == 0 && channelTuner.browsableChannelCount > 0) channelDownPressed()
                    return true
                }
            }
        }
        return null
    }

    fun channelDown() {
        channelDownPressed()
        finishChannelChangeIfNeeded()
    }

    fun channelUp() {
        channelUpPressed()
        finishChannelChangeIfNeeded()
    }

    private fun channelDownPressed() {
        // Solange gedrückt: schnelles Weiterschalten ohne Tunen
        handler.sendMessageDelayed(handler.obtainMessage(MSG_CHANNEL_DOWN_PRESSED, System.currentTimeMillis()),
            CHANNEL_CHANGE_INITIAL_DELAY_MILLIS)
        tuning.moveToAdjacentChannel(false, false)
    }

    private fun channelUpPressed() {
        handler.sendMessageDelayed(handler.obtainMessage(MSG_CHANNEL_UP_PRESSED, System.currentTimeMillis()),
            CHANNEL_CHANGE_INITIAL_DELAY_MILLIS)
        tuning.moveToAdjacentChannel(true, false)
    }

    /** null = an super.onKeyUp weitergeben. */
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean? {
        // Laufendes Umschalten abschließen, bevor Overlays erscheinen
        finishChannelChangeIfNeeded()
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            activity.otherActivityLaunched = true
            return false
        }
        when (overlayManager.onKeyUp(keyCode, event)) {
            MainActivity.KEY_EVENT_HANDLER_RESULT_DISPATCH_TO_OVERLAY -> return null
            MainActivity.KEY_EVENT_HANDLER_RESULT_HANDLED -> return true
            MainActivity.KEY_EVENT_HANDLER_RESULT_NOT_HANDLED -> return false
        }
        if (activity.searchFragment.isVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                activity.supportFragmentManager.popBackStack()
                return true
            }
            return null
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Tweak: Bestätigen zum Beenden – erst der zweite Druck innerhalb von 2 s beendet die App
            if (Tweaks.isConfirmExit(activity)) {
                val now = SystemClock.uptimeMillis()
                if (now - lastExitBackPressMs > CONFIRM_EXIT_WINDOW_MS) {
                    lastExitBackPressMs = now
                    exitToast?.cancel()
                    exitToast = Toast.makeText(activity, R.string.tweak_confirm_exit_toast, Toast.LENGTH_SHORT).also { it.show() }
                    return true
                }
                exitToast?.cancel()
                lastExitBackPressMs = 0L
            }
            // Enthält Aufräumarbeiten für das Verlassen der App
            activity.onBackPressedDispatcher.onBackPressed()
            return true
        }
        if (!channelTuner.areAllChannelsLoaded()) {
            // Tasten ignorieren
        } else if (channelTuner.browsableChannelCount == 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_MENU -> {
                    activity.showSettingsFragment()
                    return true
                }
            }
        } else {
            if (KeypadChannelSwitchView.isChannelNumberKey(keyCode)) {
                overlayManager.showKeypadChannelSwitch(keyCode)
                return true
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!tvView.isVideoOrAudioAvailable &&
                        tvView.videoUnavailableReason == TunableTvView.VIDEO_UNAVAILABLE_REASON_NO_RESOURCE
                    ) {
                        DvrUiHelper.startSchedulesActivityForTuneConflict(activity, channelTuner.currentChannel)
                        return true
                    }
                    showPinDialogFragment()
                    return true
                }
                KeyEvent.KEYCODE_WINDOW -> {
                    activity.enterPictureInPictureMode()
                    return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_E,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MENU -> {
                    if (event.isCanceled) return true // Menü-Langdruck
                    // Tweak: OK öffnet die Programmübersicht
                    if (isOkKey(keyCode) && Tweaks.isOkOpensGuide(activity)) {
                        overlayManager.showProgramGuide()
                        return true
                    }
                    if (keyCode != KeyEvent.KEYCODE_MENU) {
                        overlayManager.updateChannelBannerAndShowIfNeeded(TvOverlayManager.UPDATE_CHANNEL_BANNER_REASON_FORCE_SHOW)
                    }
                    if (keyCode != KeyEvent.KEYCODE_E) overlayManager.showMenu(Menu.REASON_NONE)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> return true // in onKeyDown behandelt
                KeyEvent.KEYCODE_CAPTIONS -> {
                    overlayManager.sideFragmentManager.show(ClosedCaptionFragment())
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                    overlayManager.sideFragmentManager.show(MultiAudioFragment())
                    return true
                }
                KeyEvent.KEYCODE_INFO -> {
                    overlayManager.showBanner()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_V -> {
                    handleRecordKey()
                    return true
                }
            }
        }
        if (keyCode == KeyEvent.KEYCODE_WINDOW) return true // PiP-Taste bei geladenen Kanälen schlucken
        return null
    }

    /** Aufnahme-Taste: aktuelle Sendung aufnehmen bzw. laufende Aufnahme stoppen (nur mit DVR). */
    private fun handleRecordKey() {
        val currentChannel: Channel = activity.currentChannel ?: return
        val dvrManager = activity.dvrManager ?: return
        if (dvrManager.getCurrentRecording(currentChannel.id) == null) {
            if (!dvrManager.isChannelRecordable(currentChannel)) {
                Toast.makeText(activity, R.string.dvr_msg_cannot_record_program, Toast.LENGTH_SHORT).show()
            } else {
                val program = activity.programDataManager.getCurrentProgram(currentChannel.id)
                DvrUiHelper.checkStorageStatusAndShowErrorMessage(activity, currentChannel.inputId) {
                    DvrUiHelper.requestRecordingCurrentProgram(activity, currentChannel, program, false)
                }
            }
        } else {
            DvrUiHelper.showStopRecordingDialog(
                activity, currentChannel.id, DvrStopRecordingFragment.REASON_USER_STOP,
                object : HalfSizedDialogFragment.OnActionClickListener {
                    override fun onActionClick(actionId: Long) {
                        if (actionId == DvrStopRecordingFragment.ACTION_STOP) {
                            dvrManager.getCurrentRecording(currentChannel.id)?.let { dvrManager.stopRecording(it) }
                        }
                    }
                })
        }
    }

    /** PIN-Dialog zum Entsperren – nur mit MODIFY_PARENTAL_CONTROLS (System-Recht), wie im Original. */
    fun showPinDialogFragment() {
        if (activity.checkSelfPermission(PERMISSION_MODIFY_PARENTAL_CONTROLS) != PackageManager.PERMISSION_GRANTED) return
        val dialog = when {
            tvView.isScreenBlocked -> PinDialogFragment.create(PinDialogFragment.PIN_DIALOG_TYPE_UNLOCK_CHANNEL)
            tvView.isContentBlocked -> PinDialogFragment.create(
                PinDialogFragment.PIN_DIALOG_TYPE_UNLOCK_PROGRAM, tvView.blockedContentRating!!.flattenToString())
            else -> null
        } ?: return
        overlayManager.showDialogFragment(PinDialogFragment.DIALOG_TAG, dialog, false)
    }

    // Tweak: OK-Tasten für "OK öffnet Programmübersicht"
    private fun isOkKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** Langdruck auf Zurück ist im Original deaktiviert (USE_BACK_KEY_LONG_PRESS = false). */
    fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean = false

    fun isChannelChangeKeyDownReceived(): Boolean =
        handler.hasMessages(MSG_CHANNEL_UP_PRESSED) || handler.hasMessages(MSG_CHANNEL_DOWN_PRESSED)

    /** Beendet schnelles Umschalten und tunt auf den gewählten Kanal. */
    fun finishChannelChangeIfNeeded() {
        if (!isChannelChangeKeyDownReceived()) return
        handler.removeMessages(MSG_CHANNEL_UP_PRESSED)
        handler.removeMessages(MSG_CHANNEL_DOWN_PRESSED)
        if (channelTuner.browsableChannelCount > 0) {
            if (!tvView.isPlaying) Log.w(TAG, "TV view isn't played in finishChannelChangeIfNeeded")
            tuning.tuneToChannel(channelTuner.currentChannel)
        } else {
            activity.showSettingsFragment()
        }
    }

    fun release() = handler.removeCallbacksAndMessages(null)

    private fun handleMessage(msg: Message) {
        when (msg.what) {
            MSG_CHANNEL_DOWN_PRESSED, MSG_CHANNEL_UP_PRESSED -> {
                val startTime = msg.obj as Long
                handler.sendMessageDelayed(Message.obtain(msg), getDelay(startTime))
                tuning.moveToAdjacentChannel(msg.what == MSG_CHANNEL_UP_PRESSED, true)
            }
        }
    }

    /** Nach 3 s Dauerdruck schneller umschalten. */
    private fun getDelay(startTime: Long): Long =
        if (System.currentTimeMillis() - startTime > CHANNEL_CHANGE_NORMAL_SPEED_DURATION_MS) CHANNEL_CHANGE_DELAY_MS_IN_MAX_SPEED
        else CHANNEL_CHANGE_DELAY_MS_IN_NORMAL_SPEED

    companion object {
        /**
         * Bugfix: Tastencodes der Guide-Taste. Neben dem Standard auch Herstellerbelegungen
         * (JVC/Vestel: KEYCODE_11). Weitere Geräte hier ergänzen.
         */
        private val GUIDE_KEYCODES = setOf(KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_11)
        private const val TAG = "KeyHandler"
        // Tweak: Bestätigen zum Beenden
        private const val CONFIRM_EXIT_WINDOW_MS = 2000L
        private const val PERMISSION_MODIFY_PARENTAL_CONTROLS = "android.permission.MODIFY_PARENTAL_CONTROLS"
        private const val CHANNEL_CHANGE_NORMAL_SPEED_DURATION_MS = 3000L
        private const val CHANNEL_CHANGE_DELAY_MS_IN_MAX_SPEED = 50L
        private const val CHANNEL_CHANGE_DELAY_MS_IN_NORMAL_SPEED = 200L
        private const val CHANNEL_CHANGE_INITIAL_DELAY_MILLIS = 500L
        private const val MSG_CHANNEL_DOWN_PRESSED = 1000
        private const val MSG_CHANNEL_UP_PRESSED = 1001
    }
}
