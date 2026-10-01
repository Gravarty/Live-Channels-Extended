package com.android.tv.dvr.provider

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.tv.TvContract
import android.media.tv.TvContract.Programs
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.android.tv.TvSingletons
import com.android.tv.data.ChannelDataManager
import com.android.tv.data.ProgramQueries
import com.android.tv.data.api.Program
import com.android.tv.dvr.DvrDataManager.ScheduledRecordingListener
import com.android.tv.dvr.DvrManager
import com.android.tv.dvr.WritableDvrDataManager
import com.android.tv.dvr.data.ScheduledRecording
import com.android.tv.dvr.data.SeriesRecording
import com.android.tv.dvr.recorder.SeriesRecordingScheduler
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hält geplante Aufnahmen mit dem EPG synchron: Zeiten/Folgendaten aktualisieren, Aufnahmen
 * gelöschter Kanäle/Sendungen entfernen, Serien zuordnen. AsyncQueryProgramTask → Coroutine.
 */
@MainThread
class DvrDbSync internal constructor(
    private val context: Context,
    private val dataManager: WritableDvrDataManager,
    private val channelDataManager: ChannelDataManager,
    private val dvrManager: DvrManager,
    private val seriesRecordingScheduler: SeriesRecordingScheduler,
) {
    constructor(context: Context, dataManager: WritableDvrDataManager) : this(
        context, dataManager,
        TvSingletons.getSingletons(context).getChannelDataManager(),
        TvSingletons.getSingletons(context).getDvrManager()!!,
        SeriesRecordingScheduler.getInstance(context),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dbDispatcher = TvSingletons.getSingletons(context).getDbDispatcher()
    private val programIdQueue = LinkedList<Long>()
    private var queryProgramJob: Job? = null

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (uri == null) return
            // Ersatz für TvUriMatcher.MATCH_PROGRAM / MATCH_PROGRAM_ID
            val segments = uri.pathSegments
            if (uri.authority != TvContract.AUTHORITY || segments.isEmpty() || segments[0] != "program") return
            when (segments.size) {
                1 -> onProgramsUpdated()
                2 -> segments[1].toLongOrNull()?.let { onProgramUpdated(ContentUris.parseId(uri)) }
            }
        }
    }

    private val channelDataManagerListener = object : ChannelDataManager.Listener {
        override fun onLoadFinished() = start()
        override fun onChannelListUpdated() = onChannelsUpdated()
        override fun onChannelBrowsableChanged() {}
    }

    private val scheduleListener = object : ScheduledRecordingListener {
        override fun onScheduledRecordingAdded(vararg scheduledRecordings: ScheduledRecording) {
            scheduledRecordings.forEach { addProgramIdToCheckIfNeeded(it) }
            startNextUpdateIfNeeded()
        }

        override fun onScheduledRecordingRemoved(vararg scheduledRecordings: ScheduledRecording) {
            scheduledRecordings.forEach { programIdQueue.remove(it.programId) }
        }

        override fun onScheduledRecordingStatusChanged(vararg scheduledRecordings: ScheduledRecording) {
            scheduledRecordings.forEach {
                programIdQueue.remove(it.programId)
                addProgramIdToCheckIfNeeded(it)
            }
            startNextUpdateIfNeeded()
        }
    }

    fun start() {
        if (!channelDataManager.isDbLoadFinished) {
            channelDataManager.addListener(channelDataManagerListener)
            return
        }
        context.contentResolver.registerContentObserver(Programs.CONTENT_URI, true, contentObserver)
        dataManager.addScheduledRecordingListener(scheduleListener)
        onChannelsUpdated()
        onProgramsUpdated()
    }

    fun stop() {
        programIdQueue.clear()
        queryProgramJob?.cancel()
        queryProgramJob = null
        channelDataManager.removeListener(channelDataManagerListener)
        dataManager.removeScheduledRecordingListener(scheduleListener)
        context.contentResolver.unregisterContentObserver(contentObserver)
    }

    /** Serien mit gelöschtem Kanal stoppen, Aufnahmen gelöschter Kanäle entfernen. */
    private fun onChannelsUpdated() {
        val seriesToUpdate = dataManager.getSeriesRecordings()
            .filter { it.channelOption == SeriesRecording.OPTION_CHANNEL_ONE && !channelDataManager.doesChannelExistInDb(it.channelId) }
            .map {
                SeriesRecording.buildFrom(it).setChannelOption(SeriesRecording.OPTION_CHANNEL_ALL)
                    .setState(SeriesRecording.STATE_SERIES_STOPPED).build()
            }
        if (seriesToUpdate.isNotEmpty()) dataManager.updateSeriesRecording(*seriesToUpdate.toTypedArray())
        val schedulesToRemove = dataManager.getAvailableScheduledRecordings().filter { !channelDataManager.doesChannelExistInDb(it.channelId) }
        schedulesToRemove.forEach { programIdQueue.remove(it.programId) }
        if (schedulesToRemove.isNotEmpty()) dataManager.removeScheduledRecording(*schedulesToRemove.toTypedArray())
    }

    private fun onProgramsUpdated() {
        dataManager.getAvailableScheduledRecordings().forEach { addProgramIdToCheckIfNeeded(it) }
        startNextUpdateIfNeeded()
    }

    private fun onProgramUpdated(programId: Long) {
        addProgramIdToCheckIfNeeded(dataManager.getScheduledRecordingForProgramId(programId))
        startNextUpdateIfNeeded()
    }

    private fun addProgramIdToCheckIfNeeded(schedule: ScheduledRecording?) {
        if (schedule == null) return
        val programId = schedule.programId
        if (programId != ScheduledRecording.ID_NOT_SET && programId !in programIdQueue &&
            (schedule.state == ScheduledRecording.STATE_RECORDING_NOT_STARTED ||
                schedule.state == ScheduledRecording.STATE_RECORDING_IN_PROGRESS)
        ) {
            programIdQueue.offer(programId)
            // Serien-Planung pausieren, bis alles geprüft ist
            seriesRecordingScheduler.pauseUpdate()
        }
    }

    private fun startNextUpdateIfNeeded() {
        if (queryProgramJob?.isActive == true) return
        val programId = programIdQueue.poll()
        if (programId == null) {
            seriesRecordingScheduler.resumeUpdate()
            return
        }
        // Bugfix: erst nach der Zuweisung von job starten. Mit Main.immediate lief der Block sofort an und konnte
        // job lesen, bevor er gesetzt war (UninitializedPropertyAccessException, wenn die Abfrage sehr schnell fertig war).
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val program = withContext(dbDispatcher) { ProgramQueries.queryProgram(context, programId) }
            if (queryProgramJob === job) queryProgramJob = null
            handleUpdateProgram(program, programId)
            startNextUpdateIfNeeded()
        }
        queryProgramJob = job
        job.start()
    }

    /** Sendung weg → Aufnahme löschen; sonst Zeiten/Metadaten/Serie übernehmen. */
    internal fun handleUpdateProgram(program: Program?, programId: Long) {
        val seriesRecordingsToUpdate = HashSet<SeriesRecording>()
        val schedule = dataManager.getScheduledRecordingForProgramId(programId) ?: return
        if (schedule.state != ScheduledRecording.STATE_RECORDING_NOT_STARTED &&
            schedule.state != ScheduledRecording.STATE_RECORDING_IN_PROGRESS
        ) return
        if (program == null) {
            dataManager.removeScheduledRecording(schedule)
            if (schedule.seriesRecordingId != SeriesRecording.ID_NOT_SET) {
                dataManager.getSeriesRecording(schedule.seriesRecordingId)?.let { seriesRecordingsToUpdate.add(it) }
            }
            // Wie im Original: Serien werden hier nicht neu geplant
            return
        }
        var builder = ScheduledRecording.buildFrom(schedule)
            .setSeasonNumber(program.seasonNumber).setEpisodeNumber(program.episodeNumber)
            .setEpisodeTitle(program.episodeTitle).setProgramDescription(program.description)
            .setProgramLongDescription(program.longDescription).setProgramPosterArtUri(program.posterArtUri)
            .setProgramThumbnailUri(program.thumbnailUri)
        var needUpdate = false
        val seriesForOldSchedule = dataManager.getSeriesRecording(schedule.seriesRecordingId)
        if (program.isEpisodic) {
            val series = program.seriesId?.let { dataManager.getSeriesRecording(it) }
            if (series == null) {
                val newSeries = dvrManager.addSeriesRecording(program, listOf(program), SeriesRecording.STATE_SERIES_STOPPED)
                // Bugfix: addSeriesRecording kann null liefern (DVR nicht bereit), Original lief dann in eine NPE.
                if (newSeries != null) {
                    builder.setSeriesRecordingId(newSeries.id)
                    needUpdate = true
                }
            } else if (series.id != schedule.seriesRecordingId) {
                builder.setSeriesRecordingId(series.id)
                needUpdate = true
                seriesRecordingsToUpdate.add(series)
                seriesForOldSchedule?.let { seriesRecordingsToUpdate.add(it) }
            } else if (schedule.seasonNumber != program.seasonNumber || schedule.episodeNumber != program.episodeNumber) {
                // Folge geändert: Serie neu planen (evtl. doppelt)
                seriesForOldSchedule?.let { seriesRecordingsToUpdate.add(it) }
            }
        } else if (seriesForOldSchedule != null) {
            seriesRecordingsToUpdate.add(seriesForOldSchedule)
        }
        if (DvrDatabaseHelper.START_EARLY_END_LATE_ENABLED) {
            handleUpdateProgramTime(program, schedule, builder)?.let {
                builder = it
                needUpdate = true
            }
        } else {
            // Kurz vor Start (±10 s) nicht mehr verschieben
            val marginalToCurrentTime = RECORD_MARGIN_MS > abs(System.currentTimeMillis() - schedule.startTimeMs)
            if (schedule.state != ScheduledRecording.STATE_RECORDING_IN_PROGRESS &&
                program.startTimeUtcMillis != schedule.startTimeMs && !marginalToCurrentTime
            ) {
                builder.setStartTimeMs(program.startTimeUtcMillis)
                needUpdate = true
            }
            if (schedule.endTimeMs != program.endTimeUtcMillis) {
                builder.setEndTimeMs(program.endTimeUtcMillis)
                needUpdate = true
            }
        }
        if (needUpdate || schedule.seasonNumber != program.seasonNumber || schedule.episodeNumber != program.episodeNumber ||
            schedule.episodeTitle != program.episodeTitle || schedule.programDescription != program.description ||
            schedule.programLongDescription != program.longDescription ||
            schedule.programPosterArtUri != program.posterArtUri || schedule.programThumbnailUri != program.thumbnailUri
        ) {
            dataManager.updateScheduledRecording(builder.build())
        }
        if (seriesRecordingsToUpdate.isNotEmpty()) seriesRecordingScheduler.updateSchedules(seriesRecordingsToUpdate)
    }

    companion object {
        private val RECORD_MARGIN_MS = TimeUnit.SECONDS.toMillis(10)

        /** Zeiten mit Vor-/Nachlauf (nur bei startEarlyEndLateEnabled). */
        private fun handleUpdateProgramTime(program: Program, schedule: ScheduledRecording, builder: ScheduledRecording.Builder): ScheduledRecording.Builder? {
            var needUpdate = false
            val now = System.currentTimeMillis()
            val marginalToCurrentTime = RECORD_MARGIN_MS > abs(now - schedule.startTimeMs)
            var updatedStartTime = program.startTimeUtcMillis - schedule.startOffsetMs
            if (schedule.state != ScheduledRecording.STATE_RECORDING_IN_PROGRESS && schedule.startTimeMs != updatedStartTime &&
                !marginalToCurrentTime
            ) {
                if (updatedStartTime < now) {
                    updatedStartTime = now + RECORD_MARGIN_MS
                    builder.setStartOffsetMs(maxOf(0, program.startTimeUtcMillis - updatedStartTime))
                }
                builder.setStartTimeMs(updatedStartTime)
                needUpdate = true
            }
            val updatedEndTime = program.endTimeUtcMillis + schedule.endOffsetMs
            if (schedule.endTimeMs != updatedEndTime) {
                builder.setEndTimeMs(updatedEndTime)
                needUpdate = true
            }
            return if (needUpdate) builder else null
        }
    }
}
