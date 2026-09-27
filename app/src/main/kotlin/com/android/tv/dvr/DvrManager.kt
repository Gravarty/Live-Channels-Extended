package com.android.tv.dvr

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.OperationApplicationException
import android.media.tv.TvContract
import android.net.Uri
import android.os.Handler
import android.os.RemoteException
import android.util.Log
import android.util.Range
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.android.tv.TvSingletons
import com.android.tv.common.SoftPreconditions
import com.android.tv.common.util.CommonUtils
import com.android.tv.data.api.Channel
import com.android.tv.data.api.Program
import com.android.tv.dvr.data.RecordedProgram
import com.android.tv.dvr.data.ScheduledRecording
import com.android.tv.dvr.data.SeriesRecording
import com.android.tv.util.Utils
import com.android.tv.tweaks.htsdvr.HtsDvrTimers
import com.android.tv.tweaks.htsdvr.HtsDvrRecordings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Öffentliche DVR-Aktionen: Aufnahmen/Serien planen, ändern, stoppen, löschen; Aufnahme-
 * fähigkeit prüfen. Legt für aufgenommene Folgen fehlende Serien an.
 * Bugfix: Beim Entfernen einer Serie wurde nur die erste laufende Aufnahme gestoppt.
 */
@MainThread
@Singleton
class DvrManager @Inject constructor(@ApplicationContext context: Context) {

    fun interface Listener {
        fun onStopRecordingRequested(scheduledRecording: ScheduledRecording)
    }

    private val appContext = context.applicationContext
    private val singletons = TvSingletons.getSingletons(context)
    private val dataManager = singletons.getDvrDataManager() as WritableDvrDataManager
    private val scheduleManager: DvrScheduleManager = singletons.getDvrScheduleManager()!!
    private val listeners = HashMap<Listener, Handler>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dbDispatcher = singletons.getDbDispatcher()

    init {
        if (dataManager.isInitialized && scheduleManager.isInitialized) {
            createSeriesRecordingsForRecordedProgramsIfNeeded(dataManager.getRecordedPrograms())
        } else {
            if (!dataManager.isRecordedProgramLoadFinished) {
                dataManager.addRecordedProgramLoadFinishedListener(object : DvrDataManager.OnRecordedProgramLoadFinishedListener {
                    override fun onRecordedProgramLoadFinished() {
                        dataManager.removeRecordedProgramLoadFinishedListener(this)
                        if (dataManager.isInitialized && scheduleManager.isInitialized) {
                            createSeriesRecordingsForRecordedProgramsIfNeeded(dataManager.getRecordedPrograms())
                        }
                    }
                })
            }
            if (!scheduleManager.isInitialized) {
                scheduleManager.addOnInitializeListener(object : DvrScheduleManager.OnInitializeListener {
                    override fun onInitialize() {
                        scheduleManager.removeOnInitializeListener(this)
                        if (dataManager.isInitialized && scheduleManager.isInitialized) {
                            createSeriesRecordingsForRecordedProgramsIfNeeded(dataManager.getRecordedPrograms())
                        }
                    }
                })
            }
        }
        dataManager.addRecordedProgramListener(object : DvrDataManager.RecordedProgramListener {
            override fun onRecordedProgramsAdded(vararg recordedPrograms: RecordedProgram) {
                if (!dataManager.isInitialized || !scheduleManager.isInitialized) return
                recordedPrograms.forEach { createSeriesRecordingForRecordedProgramIfNeeded(it) }
            }

            override fun onRecordedProgramsChanged(vararg recordedPrograms: RecordedProgram) {}
            override fun onRecordedProgramsRemoved(vararg recordedPrograms: RecordedProgram) {}
        })
    }

    private fun createSeriesRecordingsForRecordedProgramsIfNeeded(list: List<RecordedProgram>) =
        list.forEach { createSeriesRecordingForRecordedProgramIfNeeded(it) }

    private fun createSeriesRecordingForRecordedProgramIfNeeded(p: RecordedProgram) {
        if (p.isEpisodic && p.seriesId?.let { dataManager.getSeriesRecording(it) } == null) addSeriesRecording(p)
    }

    @JvmOverloads
    fun addSchedule(program: Program, startOffsetMs: Long = 0, endOffsetMs: Long = 0): ScheduledRecording? {
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) return null
        val series = getSeriesRecording(program)
        return addSchedule(program, series?.priority ?: scheduleManager.suggestNewPriority(), startOffsetMs, endOffsetMs)
    }

    fun addScheduleWithHighestPriority(program: Program): ScheduledRecording? {
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) return null
        val series = getSeriesRecording(program)
        val priority = if (series == null) scheduleManager.suggestNewPriority()
        else scheduleManager.suggestHighestPriority(series.inputId.orEmpty(),
            Range.create(program.startTimeUtcMillis, program.endTimeUtcMillis), series.priority)
        return addSchedule(program, priority, 0, 0)
    }

    private fun addSchedule(program: Program, priority: Long, startOffsetMs: Long, endOffsetMs: Long): ScheduledRecording? {
        val input = Utils.getTvInputInfoForProgram(appContext, program)
        if (input == null) {
            Log.e(TAG, "Can't find input for program: $program")
            return null
        }
        val series = getSeriesRecording(program)
        val schedule = createScheduledRecordingBuilder(input.id, program)
            .setPriority(priority)
            .setSeriesRecordingId(series?.id ?: SeriesRecording.ID_NOT_SET)
            .setStartOffsetMs(startOffsetMs).setEndOffsetMs(endOffsetMs)
            .build()
        dataManager.addScheduledRecording(schedule)
        return schedule
    }

    /** Zeitgesteuerte Aufnahme eines Kanals. */
    fun addSchedule(channel: Channel, startTime: Long, endTime: Long) {
        Log.i(TAG, "Adding scheduled recording of channel $channel starting at ${Utils.toTimeString(startTime)} and ending at ${Utils.toTimeString(endTime)}")
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) return
        val input = Utils.getTvInputInfoForChannelId(appContext, channel.id)
        if (input == null) {
            Log.e(TAG, "Can't find input for channel: $channel")
            return
        }
        dataManager.addScheduledRecording(ScheduledRecording.builder(input.id, channel.id, startTime, endTime)
            .setPriority(scheduleManager.suggestNewPriority()).build())
    }

    fun addSchedule(schedule: ScheduledRecording) {
        if (dataManager.isDvrScheduleLoadFinished) dataManager.addScheduledRecording(schedule)
    }

    /** Serie anlegen, vorhandene Aufnahmen zuordnen und [programsToSchedule] planen. */
    fun addSeriesRecording(selectedProgram: Program, programsToSchedule: List<Program>, initialState: Int): SeriesRecording? {
        Log.i(TAG, "Adding series recording for program $selectedProgram, and schedules: $programsToSchedule")
        if (!SoftPreconditions.checkState(dataManager.isInitialized)) return null
        val input = Utils.getTvInputInfoForProgram(appContext, selectedProgram)
        if (input == null) {
            Log.e(TAG, "Can't find input for program: $selectedProgram")
            return null
        }
        val series = SeriesRecording.builder(input.id, selectedProgram)
            .setPriority(scheduleManager.suggestNewSeriesPriority()).setState(initialState).build()
        dataManager.addSeriesRecording(series)
        addRecordedProgramToSeriesRecording(series)
        addScheduleToSeriesRecording(series, programsToSchedule)
        return series
    }

    private fun addSeriesRecording(recordedProgram: RecordedProgram) {
        val series = SeriesRecording.builder(recordedProgram.inputId, recordedProgram)
            .setPriority(scheduleManager.suggestNewSeriesPriority()).setState(SeriesRecording.STATE_SERIES_STOPPED).build()
        dataManager.addSeriesRecording(series)
        addRecordedProgramToSeriesRecording(series)
    }

    /** Vorhandene (nicht abgeschnittene) Aufnahmen der Serie als abgeschlossene Pläne eintragen. */
    private fun addRecordedProgramToSeriesRecording(series: SeriesRecording) {
        val toAdd = dataManager.getRecordedPrograms()
            .filter { series.seriesId == it.seriesId && !it.isClipped }
            .map { ScheduledRecording.builder(it).setPriority(series.priority).setSeriesRecordingId(series.id).build() }
        if (toAdd.isNotEmpty()) dataManager.addScheduledRecording(*toAdd.toTypedArray())
    }

    /** Folgen einer Serie planen; bereits geplante (nicht gestartete) werden der Serie zugeordnet. */
    fun addScheduleToSeriesRecording(series: SeriesRecording, programsToSchedule: List<Program>) {
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) return
        val input = series.inputId?.let { Utils.getTvInputInfoForInputId(appContext, it) }
        if (input == null) {
            Log.e(TAG, "Can't find input with ID: ${series.inputId}")
            return
        }
        val toAdd = ArrayList<ScheduledRecording>()
        val toUpdate = ArrayList<ScheduledRecording>()
        for (program in programsToSchedule) {
            val existing = dataManager.getScheduledRecordingForProgramId(program.id)
            if (existing != null) {
                if (existing.isNotStarted) {
                    val r = ScheduledRecording.buildFrom(existing).setSeriesRecordingId(series.id).build()
                    if (r != existing) toUpdate.add(r)
                }
            } else {
                toAdd.add(createScheduledRecordingBuilder(input.id, program).setPriority(series.priority).setSeriesRecordingId(series.id).build())
            }
        }
        if (toAdd.isNotEmpty()) dataManager.addScheduledRecording(*toAdd.toTypedArray())
        if (toUpdate.isNotEmpty()) dataManager.updateScheduledRecording(*toUpdate.toTypedArray())
    }

    /** Serie ändern; bei Kanalwechsel ausstehende Folgen verwerfen, bei Prioritätswechsel übernehmen. */
    fun updateSeriesRecording(series: SeriesRecording) {
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) return
        val previous = dataManager.getSeriesRecording(series.id)
        if (previous != null && (previous.channelOption != series.channelOption ||
                (previous.channelOption == SeriesRecording.OPTION_CHANNEL_ONE && previous.channelId != series.channelId))
        ) {
            val toRemove = ArrayList<ScheduledRecording>()
            for (schedule in dataManager.getScheduledRecordings(series.id)) {
                if (schedule.isNotStarted) {
                    toRemove.add(schedule)
                } else if (schedule.isInProgress && series.channelOption == SeriesRecording.OPTION_CHANNEL_ONE &&
                    schedule.channelId != series.channelId
                ) {
                    stopRecording(schedule)
                }
            }
            // Abgelehnte zukünftige Folgen ebenfalls freigeben
            dataManager.getDeletedSchedules().filter { it.seriesRecordingId == series.id && it.endTimeMs > System.currentTimeMillis() }
                .let { toRemove.addAll(it) }
            dataManager.removeScheduledRecording(true, *toRemove.toTypedArray())
        }
        dataManager.updateSeriesRecording(series)
        if (previous == null || previous.priority != series.priority) {
            val toUpdate = dataManager.getScheduledRecordings(series.id).filter { it.isNotStarted || it.isInProgress }
                .map { ScheduledRecording.buildFrom(it).setPriority(series.priority).build() }
            if (toUpdate.isNotEmpty()) dataManager.updateScheduledRecording(*toUpdate.toTypedArray())
        }
    }

    fun removeSeriesRecording(seriesRecordingId: Long) {
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) return
        val series = dataManager.getSeriesRecording(seriesRecordingId) ?: return
        dataManager.getAllScheduledRecordings()
            .filter { it.seriesRecordingId == seriesRecordingId && it.state == ScheduledRecording.STATE_RECORDING_IN_PROGRESS }
            .forEach { stopRecording(it) }
        dataManager.removeSeriesRecording(series)
    }

    /** Stopp an die Recorder weitergeben (jeweils auf ihrem Handler). */
    fun stopRecording(recording: ScheduledRecording) {
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) return
        // Tweak: Tvheadend-DVR – laufende Server-Aufnahme über den Timer stoppen
        if (HtsDvrTimers.isMirrored(recording.id)) {
            HtsDvrTimers.removeTimer(appContext, recording)
            return
        }
        synchronized(listeners) {
            for ((l, h) in listeners) h.post { l.onStopRecordingRequested(recording) }
        }
    }

    fun removeScheduledRecording(vararg schedules: ScheduledRecording) {
        Log.i(TAG, "Removing ${schedules.toList()}")
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) return
        for (r in schedules) {
            // Tweak: Tvheadend-DVR – Server-Timer über den Provider löschen
            if (HtsDvrTimers.isMirrored(r.id)) HtsDvrTimers.removeTimer(appContext, r)
            else if (r.state == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) stopRecording(r) else dataManager.removeScheduledRecording(r)
        }
    }

    fun forceRemoveScheduledRecording(vararg schedules: ScheduledRecording) {
        Log.i(TAG, "Force removing ${schedules.toList()}")
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) return
        for (r in schedules) {
            // Tweak: Tvheadend-DVR – Server-Timer über den Provider löschen
            if (HtsDvrTimers.isMirrored(r.id)) HtsDvrTimers.removeTimer(appContext, r)
            else if (r.state == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) stopRecording(r) else dataManager.removeScheduledRecording(true, r)
        }
    }

    fun removeRecordedProgram(recordedProgramUri: Uri, deleteFile: Boolean) {
        if (!SoftPreconditions.checkState(dataManager.isInitialized)) return
        removeRecordedProgram(ContentUris.parseId(recordedProgramUri), deleteFile)
    }

    fun removeRecordedProgram(recordedProgramId: Long, deleteFile: Boolean) {
        if (!SoftPreconditions.checkState(dataManager.isInitialized)) return
        dataManager.getRecordedProgram(recordedProgramId)?.let { removeRecordedProgram(it, deleteFile) }
    }

    fun removeRecordedProgram(recordedProgram: RecordedProgram, deleteFile: Boolean) {
        if (!SoftPreconditions.checkState(dataManager.isInitialized)) return
        // Tweak: Tvheadend-DVR – Aufnahme auf dem Server löschen
        if (HtsDvrTimers.handlesInput(appContext, recordedProgram.inputId)) {
            HtsDvrRecordings.deleteRecordings(appContext, listOf(recordedProgram))
            return
        }
        scope.launch {
            val deleted = withContext(dbDispatcher) { appContext.contentResolver.delete(recordedProgram.uri, null, null) }
            if (deleted > 0 && deleteFile) withContext(Dispatchers.IO) { removeRecordedData(recordedProgram.dataUri) }
        }
    }

    fun removeRecordedPrograms(recordedProgramIds: List<Long>, deleteFiles: Boolean) {
        val ops = ArrayList<ContentProviderOperation>()
        val dataUris = ArrayList<Uri?>()
        // Tweak: Tvheadend-DVR – Aufnahmen des HTS-Plugins auf dem Server löschen
        val htsRecordings = recordedProgramIds.mapNotNull { dataManager.getRecordedProgram(it) }
            .filter { HtsDvrTimers.handlesInput(appContext, it.inputId) }
        HtsDvrRecordings.deleteRecordings(appContext, htsRecordings)
        for (id in recordedProgramIds) {
            val r = dataManager.getRecordedProgram(id) ?: continue
            if (r in htsRecordings) continue // Tweak: Tvheadend-DVR
            dataUris.add(r.dataUri)
            ops.add(ContentProviderOperation.newDelete(r.uri).build())
        }
        scope.launch {
            val success = withContext(dbDispatcher) {
                try {
                    appContext.contentResolver.applyBatch(TvContract.AUTHORITY, ops)
                    true
                } catch (e: RemoteException) {
                    Log.w(TAG, "Remove recorded programs from DB failed.", e)
                    false
                } catch (e: OperationApplicationException) {
                    Log.w(TAG, "Remove recorded programs from DB failed.", e)
                    false
                }
            }
            if (success && deleteFiles) withContext(Dispatchers.IO) { dataUris.forEach { removeRecordedData(it) } }
        }
    }

    fun updateScheduledRecording(recording: ScheduledRecording) {
        if (SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) dataManager.updateScheduledRecording(recording)
    }

    fun getConflictingSchedules(program: Program): List<ScheduledRecording> =
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) emptyList() else scheduleManager.getConflictingSchedules(program)

    fun getConflictingSchedules(channelId: Long, startTimeMs: Long, endTimeMs: Long): List<ScheduledRecording> =
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) emptyList()
        else scheduleManager.getConflictingSchedules(channelId, startTimeMs, endTimeMs)

    fun isConflicting(schedule: ScheduledRecording?): Boolean =
        schedule != null && SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished) && scheduleManager.isConflicting(schedule)

    fun getConflictingSchedulesForTune(channelId: Long): List<ScheduledRecording> =
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) emptyList()
        else scheduleManager.getConflictingSchedulesForTune(channelId)

    fun setHighestPriority(schedule: ScheduledRecording) {
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) return
        val newPriority = scheduleManager.suggestHighestPriority(schedule)
        if (newPriority != schedule.priority) dataManager.updateScheduledRecording(ScheduledRecording.buildFrom(schedule).setPriority(newPriority).build())
    }

    fun suggestHighestPriority(schedule: ScheduledRecording): Long =
        if (SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) scheduleManager.suggestHighestPriority(schedule)
        else DvrScheduleManager.DEFAULT_PRIORITY

    /** Aufnahmefähig: kein Verbot für Kanal/aktuelle Sendung und Input kann aufnehmen. */
    fun isChannelRecordable(channel: Channel?): Boolean {
        if (!dataManager.isDvrScheduleLoadFinished || channel == null || channel.isRecordingProhibited) return false
        val info = Utils.getTvInputInfoForChannelId(appContext, channel.id)
        if (info == null) {
            Log.w(TAG, "Could not find TvInputInfo for $channel")
            return false
        }
        if (!info.canRecord()) return false
        val program = singletons.getProgramDataManager().getCurrentProgram(channel.id)
        return program == null || !program.isRecordingProhibited
    }

    fun isProgramRecordable(program: Program): Boolean {
        if (!dataManager.isInitialized) return false
        val channel = singletons.getChannelDataManager().getChannel(program.channelId)
        if (channel == null || channel.isRecordingProhibited) return false
        val info = Utils.getTvInputInfoForChannelId(appContext, channel.id)
        if (info == null) {
            Log.w(TAG, "Could not find TvInputInfo for $program")
            return false
        }
        return info.canRecord() && !program.isRecordingProhibited
    }

    fun getCurrentRecording(channelId: Long): ScheduledRecording? =
        if (!dataManager.isDvrScheduleLoadFinished) null else dataManager.getStartedRecordings().firstOrNull { it.channelId == channelId }

    fun getAvailableScheduledRecording(seriesRecordingId: Long): List<ScheduledRecording> =
        if (!dataManager.isDvrScheduleLoadFinished) emptyList()
        else dataManager.getScheduledRecordings(seriesRecordingId).filter { it.isInProgress || it.isNotStarted }

    fun getSeriesRecording(program: Program): SeriesRecording? {
        if (!SoftPreconditions.checkState(dataManager.isDvrScheduleLoadFinished)) return null
        return program.seriesId?.let { dataManager.getSeriesRecording(it) }
    }

    fun hasValidItems(): Boolean = !(dataManager.getRecordedPrograms().isEmpty() && dataManager.getStartedRecordings().isEmpty() &&
        dataManager.getNonStartedScheduledRecordings().isEmpty() && dataManager.getSeriesRecordings().isEmpty())

    @WorkerThread
    fun addListener(listener: Listener, handler: Handler) = synchronized(listeners) { listeners[listener] = handler }

    @WorkerThread
    fun removeListener(listener: Listener) = synchronized(listeners) { listeners.remove(listener) }

    /** Laufende Sendung: ab jetzt aufnehmen. */
    private fun createScheduledRecordingBuilder(inputId: String, program: Program): ScheduledRecording.Builder {
        val builder = ScheduledRecording.builder(inputId, program)
        val time = System.currentTimeMillis()
        if (program.startTimeUtcMillis < time && time < program.endTimeUtcMillis) builder.setStartTimeMs(time)
        return builder
    }

    fun getScheduledRecording(title: String?, seasonNumber: String?, episodeNumber: String?): ScheduledRecording? {
        if (!SoftPreconditions.checkState(dataManager.isInitialized) || title == null || seasonNumber == null || episodeNumber == null) return null
        return dataManager.getAllScheduledRecordings().firstOrNull {
            title == it.programTitle && seasonNumber == it.seasonNumber && episodeNumber == it.episodeNumber
        }
    }

    fun getRecordedProgram(title: String?, seasonNumber: String?, episodeNumber: String?): RecordedProgram? {
        if (!SoftPreconditions.checkState(dataManager.isInitialized) || title == null || seasonNumber == null || episodeNumber == null) return null
        return dataManager.getRecordedPrograms().firstOrNull {
            title == it.title && seasonNumber == it.seasonNumber && episodeNumber == it.episodeNumber && !it.isClipped
        }
    }

    @WorkerThread
    private fun removeRecordedData(dataUri: Uri?) {
        try {
            if (!isFile(dataUri)) return
            val path = File(dataUri!!.path!!)
            if (path.exists() && !CommonUtils.deleteDirOrFile(path)) Log.w(TAG, "Unable to delete recording data at $dataUri")
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to delete recording data at $dataUri", e)
        }
    }

    fun forgetStorage(inputId: String) {
        if (dataManager.isInitialized) dataManager.forgetStorage(inputId)
    }

    companion object {
        private const val TAG = "DvrManager"

        @AnyThread
        @JvmStatic
        fun isFromBundledInput(recordedProgram: RecordedProgram) = CommonUtils.isInBundledPackageSet(recordedProgram.packageName)

        @AnyThread
        @JvmStatic
        fun isFile(dataUri: Uri?) = dataUri != null && ContentResolver.SCHEME_FILE == dataUri.scheme && dataUri.path != null
    }
}
