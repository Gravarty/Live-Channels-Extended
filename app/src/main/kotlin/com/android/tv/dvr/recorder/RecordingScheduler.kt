package com.android.tv.dvr.recorder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager.TvInputCallback
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.util.ArrayMap
import android.util.Log
import android.util.Range
import androidx.annotation.MainThread
import com.android.tv.InputSessionManager
import com.android.tv.Starter
import com.android.tv.TvSingletons
import com.android.tv.common.SoftPreconditions
import com.android.tv.common.util.Clock
import com.android.tv.data.ChannelDataManager
import com.android.tv.dvr.DvrDataManager
import com.android.tv.dvr.DvrManager
import com.android.tv.dvr.WritableDvrDataManager
import com.android.tv.dvr.data.ScheduledRecording
import com.android.tv.util.TvInputManagerHelper
import com.android.tv.util.Utils
import com.android.tv.tweaks.htsdvr.HtsDvr
import java.util.concurrent.TimeUnit

/**
 * Startet Aufnahmen: Pläne der nächsten Minute an die Input-Scheduler geben, sonst einen
 * Wecker vor dem nächsten Start stellen; bei anstehenden Aufnahmen den Vordergrunddienst starten.
 * Android 12+: exakte Wecker nur mit Berechtigung – sonst nicht-exakter Wecker statt Absturz.
 */
@MainThread
class RecordingScheduler internal constructor(
    private val looper: Looper,
    private val dvrManager: DvrManager,
    private val sessionManager: InputSessionManager,
    private val dataManager: WritableDvrDataManager,
    private val channelDataManager: ChannelDataManager,
    private val inputManager: TvInputManagerHelper,
    private val context: Context,
    private val clock: Clock,
    private val alarmManager: AlarmManager,
) : TvInputCallback(), DvrDataManager.ScheduledRecordingListener {

    private val inputSchedulerMap = ArrayMap<String, InputTaskScheduler>()
    private var lastStartTimePendingMs = 0L

    private val dvrScheduleLoadListener = object : DvrDataManager.OnDvrScheduleLoadFinishedListener {
        override fun onDvrScheduleLoadFinished() {
            dataManager.removeDvrScheduleLoadFinishedListener(this)
            if (isDbLoaded()) updateInternal()
        }
    }

    private val channelDataLoadListener = object : ChannelDataManager.Listener {
        override fun onLoadFinished() {
            channelDataManager.removeListener(this)
            if (isDbLoaded()) updateInternal()
        }
        override fun onChannelListUpdated() {}
        override fun onChannelBrowsableChanged() {}
    }

    init {
        dataManager.addScheduledRecordingListener(this)
        inputManager.addCallback(this)
        if (isDbLoaded()) {
            updateInternal()
        } else {
            if (!dataManager.isDvrScheduleLoadFinished) dataManager.addDvrScheduleLoadFinishedListener(dvrScheduleLoadListener)
            if (!channelDataManager.isDbLoadFinished) channelDataManager.addListener(channelDataLoadListener)
        }
    }

    /** Vom Wecker/Boot: aktualisieren bzw. Dienst starten, bis die Daten geladen sind. */
    fun updateAndStartServiceIfNeeded() {
        if (isDbLoaded()) updateInternal() else DvrRecordingService.startForegroundService(context, false)
    }

    private fun updateInternal() {
        val recordingSoon = updatePendingRecordings()
        updateNextAlarm()
        if (recordingSoon) DvrRecordingService.startForegroundService(context, true)
        else DvrRecordingService.stopForegroundIfNotRecording()
    }

    /** Pläne, die innerhalb der nächsten Minute starten, übergeben. */
    private fun updatePendingRecordings(): Boolean {
        val soon = dataManager.getScheduledRecordings(
            Range(lastStartTimePendingMs, clock.currentTimeMillis() + SOON_DURATION_IN_MS),
            ScheduledRecording.STATE_RECORDING_NOT_STARTED)
        soon.forEach { scheduleRecordingSoon(it) }
        return soon.isNotEmpty() ||
            (lastStartTimePendingMs > clock.currentTimeMillis() && lastStartTimePendingMs < clock.currentTimeMillis() + SOON_DURATION_IN_MS)
    }

    private fun isDbLoaded() = dataManager.isDvrScheduleLoadFinished && channelDataManager.isDbLoadFinished

    override fun onScheduledRecordingAdded(vararg scheduledRecordings: ScheduledRecording) {
        if (isDbLoaded()) handleScheduleChange(*scheduledRecordings)
    }

    override fun onScheduledRecordingRemoved(vararg scheduledRecordings: ScheduledRecording) {
        if (!isDbLoaded()) return
        var needToUpdateAlarm = false
        for (s in scheduledRecordings) {
            inputSchedulerMap[s.inputId]?.let {
                it.removeSchedule(s)
                needToUpdateAlarm = true
            }
        }
        if (needToUpdateAlarm) updateNextAlarm()
    }

    override fun onScheduledRecordingStatusChanged(vararg scheduledRecordings: ScheduledRecording) {
        if (!isDbLoaded()) return
        scheduledRecordings.forEach { inputSchedulerMap[it.inputId]?.updateSchedule(it) }
        handleScheduleChange(*scheduledRecordings)
    }

    private fun handleScheduleChange(vararg schedules: ScheduledRecording) {
        var needToUpdateAlarm = false
        for (s in schedules) {
            if (s.state != ScheduledRecording.STATE_RECORDING_NOT_STARTED) continue
            // Tweak: Tvheadend-DVR – Aufnahmen des HTS-Plugins plant und startet der Server selbst
            if (HtsDvr.isHtsInput(s.inputId)) continue
            if (startsWithin(s, SOON_DURATION_IN_MS)) scheduleRecordingSoon(s) else needToUpdateAlarm = true
        }
        if (needToUpdateAlarm) updateNextAlarm()
    }

    private fun scheduleRecordingSoon(schedule: ScheduledRecording) {
        val input = schedule.inputId?.let { Utils.getTvInputInfoForInputId(context, it) }
        if (input == null) {
            Log.e(TAG, "Can't find input for $schedule")
            dataManager.changeState(schedule, ScheduledRecording.STATE_RECORDING_FAILED, ScheduledRecording.FAILED_REASON_INPUT_UNAVAILABLE)
            return
        }
        if (!input.canRecord() || input.tunerCount <= 0) {
            Log.e(TAG, "TV input doesn't support recording: $input")
            dataManager.changeState(schedule, ScheduledRecording.STATE_RECORDING_FAILED, ScheduledRecording.FAILED_REASON_INPUT_DVR_UNSUPPORTED)
            return
        }
        val scheduler = inputSchedulerMap.getOrPut(input.id) {
            InputTaskScheduler(context, input, looper, channelDataManager, dvrManager, dataManager, sessionManager, clock)
        }
        scheduler.addSchedule(schedule)
        if (lastStartTimePendingMs < schedule.startTimeMs) lastStartTimePendingMs = schedule.startTimeMs
    }

    /** Wecker kurz vor dem nächsten Aufnahmestart. */
    private fun updateNextAlarm() {
        val nextStartTime = dataManager.getNextScheduledStartTimeAfter(maxOf(lastStartTimePendingMs, clock.currentTimeMillis()))
        if (nextStartTime == DvrDataManager.NEXT_START_TIME_NOT_FOUND) return
        val wakeAt = nextStartTime - MS_TO_WAKE_BEFORE_START
        val alarmIntent = PendingIntent.getBroadcast(context, 0, Intent(context, DvrStartRecordingReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, alarmIntent)
        } else {
            Log.w(TAG, "Exact alarms not permitted, using inexact alarm")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, alarmIntent)
        }
    }

    internal fun startsWithin(schedule: ScheduledRecording, durationInMs: Long) =
        clock.currentTimeMillis() >= schedule.startTimeMs - durationInMs

    override fun onInputUpdated(inputId: String) {
        inputSchedulerMap[inputId]?.updateTvInputInfo(Utils.getTvInputInfoForInputId(context, inputId))
    }

    override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) {
        inputSchedulerMap[inputInfo.id]?.updateTvInputInfo(inputInfo)
    }

    companion object {
        private const val TAG = "RecordingScheduler"
        private val SOON_DURATION_IN_MS = TimeUnit.MINUTES.toMillis(1)
        internal val MS_TO_WAKE_BEFORE_START = TimeUnit.SECONDS.toMillis(30)

        @JvmStatic
        fun createScheduler(context: Context): RecordingScheduler {
            val singletons = TvSingletons.getSingletons(context)
            SoftPreconditions.checkState(singletons.getRecordingScheduler() == null)
            val handlerThread = HandlerThread("RecordingScheduler").also { it.start() }
            return RecordingScheduler(handlerThread.looper, singletons.getDvrManager()!!, singletons.getInputSessionManager(),
                singletons.getDvrDataManager() as WritableDvrDataManager, singletons.getChannelDataManager(),
                singletons.getTvInputManagerHelper(), context, Clock.SYSTEM, context.getSystemService(AlarmManager::class.java))
        }
    }
}

/** Wecker-Empfänger: Aufnahmen prüfen/starten. */
class DvrStartRecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Starter.start(context)
        TvSingletons.getSingletons(context).getRecordingScheduler()?.updateAndStartServiceIfNeeded()
    }
}
