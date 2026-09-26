package com.android.tv

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract.Programs
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvInputManager.TvInputCallback
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.android.tv.common.util.Clock
import com.android.tv.common.util.SharedPreferencesUtils
import com.android.tv.common.ui.setup.animation.SetupAnimationHelper
import com.android.tv.data.ChannelDataManager
import com.android.tv.data.PreviewDataManager
import com.android.tv.data.ProgramDataManager
import com.android.tv.dvr.DvrDataManager
import com.android.tv.dvr.DvrManager
import com.android.tv.dvr.DvrScheduleManager
import com.android.tv.dvr.DvrStorageStatusManager
import com.android.tv.dvr.DvrWatchedPositionManager
import com.android.tv.dvr.RecordingStorageStatusManager
import com.android.tv.dvr.recorder.RecordingScheduler
import com.android.tv.dvr.ui.browse.DvrBrowseActivity
import com.android.tv.features.TvFeatures
import com.android.tv.modules.DbDispatcher
import com.android.tv.recommendation.ChannelPreviewUpdater
import com.android.tv.recommendation.RecordedProgramPreviewUpdater
import com.android.tv.util.SetupUtils
import com.android.tv.util.TvInputManagerHelper
import com.android.tv.util.Utils
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.MainScope

/**
 * Port von TvApplication. Startet TvInputManagerHelper/ChannelDataManager/ProgramDataManager
 * (im Original in den Dagger-Providern bzw. lazy), DVR-Scheduler und Vorschau-Kanäle, und
 * verarbeitet globale Tasten (Guide/TV/DVR/Input).
 * Entfällt: eingebauter Tuner, Cloud-EPG-Fetcher, PerformanceMonitor/Startzeit-Messung.
 */
@HiltAndroidApp
class TvApplication : Application(), TvSingletons, Starter {

    @Inject lateinit var tvInputManagerHelper: Lazy<TvInputManagerHelper>
    @Inject lateinit var channelDataManager: Lazy<ChannelDataManager>
    @Inject lateinit var programDataManager: Lazy<ProgramDataManager>
    @Inject lateinit var dvrManager: Lazy<DvrManager>
    @Inject lateinit var dvrDataManager: Lazy<DvrDataManager>
    @Inject lateinit var inputSessionManager: Lazy<InputSessionManager>
    @Inject lateinit var injectedMainActivityWrapper: MainActivityWrapper
    @Inject lateinit var injectedSetupUtils: SetupUtils
    @Inject lateinit var injectedClock: Clock
    @Inject @DbDispatcher lateinit var injectedDbDispatcher: CoroutineDispatcher

    var versionName = ""
        private set
    private var selectInputActivity: SelectInputActivity? = null
    private var previewDataManager: PreviewDataManager? = null
    private var dvrScheduleManager: DvrScheduleManager? = null
    private var dvrWatchedPositionManager: DvrWatchedPositionManager? = null
    private var recordingScheduler: RecordingScheduler? = null
    private var dvrStorageStatusManager: RecordingStorageStatusManager? = null
    private var started = false
    private var managersStarted = false

    override fun onCreate() {
        if (getSystemService(TvInputManager::class.java) == null) {
            val msg = "Not an Android TV device."
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            Log.wtf(TAG, msg)
            throw IllegalStateException(msg)
        }
        super.onCreate()
        SharedPreferencesUtils.initialize(this, MainScope(), Runnable {})
        versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Unable to find package '$packageName'.", e)
            ""
        }
        Log.i(TAG, "Starting TV app $versionName")
        SetupAnimationHelper.initialize(this)
        startManagersIfNeeded()
        Log.i(TAG, "Started TV app $versionName")
    }

    /** Im Original starteten die Dagger-Provider diese Manager beim ersten Zugriff. */
    private fun startManagersIfNeeded() {
        if (managersStarted) return
        managersStarted = true
        tvInputManagerHelper.get().start()
        channelDataManager.get().start()
        // Original: lazy beim ersten Zugriff. MainActivity bekommt ihn per Injection, daher hier starten.
        programDataManager.get().start()
    }

    /** Initialisierung beim Start einer Activity/eines Service (Starter.start). */
    override fun start() {
        if (started) return
        started = true
        tvInputManagerHelper.get().addCallback(object : TvInputCallback() {
            override fun onInputAdded(inputId: String) = handleInputCountChanged()
            override fun onInputRemoved(inputId: String) = handleInputCountChanged()
        })
        if (TvFeatures.isDvrEnabled(this)) {
            dvrScheduleManager = DvrScheduleManager(this)
            recordingScheduler = RecordingScheduler.createScheduler(this)
        }
        ChannelPreviewUpdater.getInstance(this).startRoutineService()
        if (TvFeatures.isDvrEnabled(this)) {
            RecordedProgramPreviewUpdater.getInstance(this).updatePreviewDataForRecordedPrograms()
        }
    }

    override fun getTvInputManagerHelper(): TvInputManagerHelper = tvInputManagerHelper.get()
    override fun getChannelDataManager(): ChannelDataManager = channelDataManager.get()

    override fun getProgramDataManager(): ProgramDataManager = programDataManager.get()

    override fun getClock(): Clock = injectedClock
    override fun getDbDispatcher(): CoroutineDispatcher = injectedDbDispatcher
    override fun getSetupUtils(): SetupUtils = injectedSetupUtils
    override fun getInputSessionManager(): InputSessionManager = inputSessionManager.get()
    override fun getMainActivityWrapper(): MainActivityWrapper = injectedMainActivityWrapper

    override fun getPreviewDataManager(): PreviewDataManager =
        previewDataManager ?: PreviewDataManager(this).also { it.start(); previewDataManager = it }

    override fun getDvrManager(): DvrManager? =
        if (TvFeatures.isDvrEnabled(this) && dvrScheduleManager != null) dvrManager.get() else null

    override fun getDvrDataManager(): DvrDataManager = dvrDataManager.get()
    override fun getDvrScheduleManager(): DvrScheduleManager? = dvrScheduleManager
    override fun getRecordingScheduler(): RecordingScheduler? = recordingScheduler

    override fun getDvrWatchedPositionManager(): DvrWatchedPositionManager =
        dvrWatchedPositionManager ?: DvrWatchedPositionManager(this).also { dvrWatchedPositionManager = it }

    override fun getRecordingStorageStatusManager(): RecordingStorageStatusManager =
        dvrStorageStatusManager ?: DvrStorageStatusManager(this).also { dvrStorageStatusManager = it }

    fun setSelectInputActivity(activity: SelectInputActivity?) { selectInputActivity = activity }

    /** GUIDE-Taste: Programmübersicht öffnen bzw. umschalten. */
    fun handleGuideKey() {
        if (!injectedMainActivityWrapper.isResumed) {
            startActivity(Intent(Intent.ACTION_VIEW, Programs.CONTENT_URI).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            injectedMainActivityWrapper.mainActivity?.overlayManager?.toggleProgramGuide()
        }
    }

    /** TV-Taste: Live-TV öffnen, falls nicht schon im Vordergrund. */
    fun handleTvKey() {
        if (!injectedMainActivityWrapper.isResumed) startMainActivity(null)
    }

    fun handleDvrKey() {
        startActivity(Intent(this, DvrBrowseActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Input-Taste: Eingangsauswahl (alle Tuner-Inputs zählen als einer). */
    fun handleTvInputKey() {
        val tvInputs = getSystemService(TvInputManager::class.java).tvInputList
        var inputCount = 0
        var hasTunerInput = false
        for (input in tvInputs) {
            if (input.isPassthroughInput) {
                if (!input.isHidden(this)) ++inputCount
            } else if (!hasTunerInput) {
                hasTunerInput = true
                ++inputCount
            }
        }
        if (inputCount < 1) {
            Log.w(TAG, "No available input to be selected.")
            return
        }
        if (injectedMainActivityWrapper.isResumed && inputCount < 2) return // nur ein Input: nichts zu wählen
        val activityToHandle: Activity? =
            if (injectedMainActivityWrapper.isResumed) injectedMainActivityWrapper.mainActivity else selectInputActivity
        when {
            activityToHandle != null -> {
                activityToHandle.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TV_INPUT))
                activityToHandle.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TV_INPUT))
            }
            injectedMainActivityWrapper.isStarted -> startMainActivity(Bundle().apply {
                putString(Utils.EXTRA_KEY_ACTION, Utils.EXTRA_ACTION_SHOW_TV_INPUT)
            })
            else -> startActivity(Intent(this, SelectInputActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun startMainActivity(extras: Bundle?) {
        // MainActivity ist singleTask – ein vorhandenes Exemplar wird wiederverwendet
        val intent = Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras?.let { intent.putExtras(it) }
        startActivity(intent)
    }

    /**
     * Blendet das App-Symbol (TvActivity) ein/aus, je nachdem ob Tuner-Inputs existieren.
     * Als Nicht-System-App ist es immer sichtbar (UNHIDE).
     */
    fun handleInputCountChanged(
        calledByTunerServiceChanged: Boolean = false, tunerServiceEnabled: Boolean = false, dontKillApp: Boolean = false,
    ) {
        val inputManager = getSystemService(TvInputManager::class.java)
        var enable = (calledByTunerServiceChanged && tunerServiceEnabled) || TvFeatures.isUnhideEnabled(this)
        if (!enable) {
            val inputs = inputManager.tvInputList
            val tunerInputs = inputs.filter { it.type == TvInputInfo.TYPE_TUNER }
            // Nur Play Movies als Tuner: nicht als Tuner werten
            val skipTunerInputCheck = tunerInputs.size == 1 && tunerInputs[0].id.startsWith("com.google.android.videos/")
            if (!skipTunerInputCheck && tunerInputs.isNotEmpty()) enable = true
        }
        val name = ComponentName(this, TvActivity::class.java)
        val newState = if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (packageManager.getComponentEnabledSetting(name) != newState) {
            packageManager.setComponentEnabledSetting(name, newState, if (dontKillApp) PackageManager.DONT_KILL_APP else 0)
            Log.i(TAG, "${if (enable) "Un-hide" else "Hide"} TV app.")
        }
        injectedSetupUtils.onInputListUpdated(inputManager)
    }

    companion object {
        private const val TAG = "TvApplication"
    }
}
