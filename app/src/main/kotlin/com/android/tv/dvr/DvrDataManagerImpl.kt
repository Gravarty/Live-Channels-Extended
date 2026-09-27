package com.android.tv.dvr

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.sqlite.SQLiteException
import android.media.tv.TvContract
import android.media.tv.TvContract.RecordedPrograms
import android.media.tv.TvInputManager.TvInputCallback
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import androidx.annotation.MainThread
import com.android.tv.TvSingletons
import com.android.tv.common.SoftPreconditions
import com.android.tv.common.util.Clock
import com.android.tv.common.util.CommonUtils
import com.android.tv.dvr.data.IdGenerator
import com.android.tv.dvr.data.RecordedProgram
import com.android.tv.dvr.data.ScheduledRecording
import com.android.tv.dvr.data.SeriesRecording
import com.android.tv.dvr.provider.DvrDatabaseHelper
import com.android.tv.dvr.provider.DvrDbFuture
import com.android.tv.dvr.provider.DvrDbSync
import com.android.tv.dvr.recorder.SeriesRecordingScheduler
import com.android.tv.util.TvInputManagerHelper
import com.android.tv.util.TvProviderUtils
import com.android.tv.tweaks.htsdvr.HtsDvrTimers
import com.android.tv.tweaks.Tweaks
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DVR-Daten: Aufnahmepläne und Serien (eigene DB) sowie Aufnahmen (TvProvider). Inputs, die
 * verschwinden, werden samt Daten ausgeblendet und bei Rückkehr wieder eingeblendet.
 * Bugfixes: Aufnahmen entfernter Inputs wurden per Sendungs- statt per Aufnahme-ID gelöscht;
 * NPE bei Programm-ID-Wechsel; Serien-ID-Index nach Bereinigung veraltet; Serien-Planung
 * startete nicht, wenn die Aufnahmen zuletzt fertig geladen waren.
 */
@MainThread
@Singleton
class DvrDataManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    clock: Clock,
    private val inputManager: TvInputManagerHelper,
    private val dbHelper: DvrDatabaseHelper,
) : BaseDvrDataManager(clock) {

    private val scheduledRecordings = HashMap<Long, ScheduledRecording>()
    private val recordedPrograms = HashMap<Long, RecordedProgram>()
    private val seriesRecordings = HashMap<Long, SeriesRecording>()
    private val programId2ScheduledRecordings = HashMap<Long, ScheduledRecording>()
    private val seriesId2SeriesRecordings = HashMap<String?, SeriesRecording>()
    private val scheduledRecordingsForRemovedInput = HashMap<Long, ScheduledRecording>()
    private val recordedProgramsForRemovedInput = HashMap<Long, RecordedProgram>()
    private val seriesRecordingsForRemovedInput = HashMap<Long, SeriesRecording>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingJobs = HashSet<Job>()
    private var dvrLoadFinished = false
    private var recordedProgramLoadFinished = false
    private var dbSync: DvrDbSync? = null
    private val storageStatusManager: RecordingStorageStatusManager = TvSingletons.getSingletons(context).getRecordingStorageStatusManager()

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onChange(selfChange, null)
        override fun onChange(selfChange: Boolean, uri: Uri?) = queryRecordedPrograms(uri)
    }

    private val inputCallback = object : TvInputCallback() {
        override fun onInputAdded(inputId: String) {
            if (isInputAvailable(inputId)) unhideInput(inputId)
        }

        override fun onInputRemoved(inputId: String) = hideInput(inputId)
    }

    /** Nur für den eingebauten Tuner relevant (Speicher ein-/ausgehängt). */
    private val storageMountChangedListener = RecordingStorageStatusManager.OnStorageMountChangedListener { mounted ->
        for (input in inputManager.getTvInputInfos(true, true)) {
            if (CommonUtils.isBundledInput(input.id)) if (mounted) unhideInput(input.id) else hideInput(input.id)
        }
    }

    private val dbCallback = object : DvrDbFuture.Callback<Unit> {
        override fun onSuccess(result: Unit?) {}
        override fun onFailure(t: Throwable) = Log.w(TAG, "Failed to execute.", t).let {}
    }

    override val isInitialized: Boolean get() = dvrLoadFinished && recordedProgramLoadFinished
    override val isDvrScheduleLoadFinished: Boolean get() = dvrLoadFinished
    override val isRecordedProgramLoadFinished: Boolean get() = recordedProgramLoadFinished

    init {
        start()
    }

    private fun start() {
        inputManager.addCallback(inputCallback)
        storageStatusManager.addListener(storageMountChangedListener)
        DvrDbFuture.DvrQuerySeriesRecordingFuture(dbHelper).executeOnDbThread(object : DvrDbFuture.Callback<List<SeriesRecording>> {
            override fun onSuccess(result: List<SeriesRecording>?) = onSeriesRecordingsLoaded(result.orEmpty())
            override fun onFailure(t: Throwable) = Log.w(TAG, "Failed to load series recording.", t).let {}
        })
        DvrDbFuture.DvrQueryScheduleFuture(dbHelper).executeOnDbThread(object : DvrDbFuture.Callback<List<ScheduledRecording>> {
            override fun onSuccess(result: List<ScheduledRecording>?) = onSchedulesLoaded(result.orEmpty())
            override fun onFailure(t: Throwable) = Log.w(TAG, "Failed to load scheduled recording.", t).let {}
        })
        queryRecordedPrograms(null)
        context.contentResolver.registerContentObserver(RecordedPrograms.CONTENT_URI, true, contentObserver)
    }

    fun stop() {
        inputManager.removeCallback(inputCallback)
        storageStatusManager.removeListener(storageMountChangedListener)
        SeriesRecordingScheduler.getInstance(context).stop()
        dbSync?.stop()
        context.contentResolver.unregisterContentObserver(contentObserver)
        pendingJobs.toList().forEach { it.cancel() }
        pendingJobs.clear()
    }

    private fun onSeriesRecordingsLoaded(list: List<SeriesRecording>) {
        var maxId = 0L
        val seriesIds = HashSet<String?>()
        for (r in list) {
            if (SoftPreconditions.checkState(r.seriesId !in seriesIds, TAG, "Skip loading series recording with duplicate series ID: $r")) {
                seriesIds.add(r.seriesId)
                if (isInputAvailable(r.inputId)) {
                    seriesRecordings[r.id] = r
                    seriesId2SeriesRecordings[r.seriesId] = r
                } else {
                    seriesRecordingsForRemovedInput[r.id] = r
                }
            }
            if (maxId < r.id) maxId = r.id
        }
        IdGenerator.SERIES_RECORDING.setMaxId(maxId)
    }

    /** Beim Laden: abgelaufene laufende/geplante Aufnahmen als fehlgeschlagen markieren, abgebrochene löschen. */
    private fun onSchedulesLoaded(result: List<ScheduledRecording>) {
        var maxId = 0L
        val toUpdate = ArrayList<ScheduledRecording>()
        val toDelete = ArrayList<ScheduledRecording>()
        for (r in result) {
            if (!isInputAvailable(r.inputId)) {
                scheduledRecordingsForRemovedInput[r.id] = r
            } else if (r.state == ScheduledRecording.STATE_RECORDING_DELETED) {
                deletedScheduleMap[r.programId] = r
            } else {
                scheduledRecordings[r.id] = r
                if (r.programId != ScheduledRecording.ID_NOT_SET) programId2ScheduledRecordings[r.programId] = r
                val ended = r.endTimeMs <= clock.currentTimeMillis()
                when (r.state) {
                    ScheduledRecording.STATE_RECORDING_IN_PROGRESS -> toUpdate.add(
                        if (ended) ScheduledRecording.buildFrom(r).setState(ScheduledRecording.STATE_RECORDING_FAILED)
                            .setFailedReason(ScheduledRecording.FAILED_REASON_NOT_FINISHED).build()
                        else ScheduledRecording.buildFrom(r).setState(ScheduledRecording.STATE_RECORDING_NOT_STARTED).build())
                    ScheduledRecording.STATE_RECORDING_NOT_STARTED -> if (ended) toUpdate.add(
                        ScheduledRecording.buildFrom(r).setState(ScheduledRecording.STATE_RECORDING_FAILED)
                            .setFailedReason(ScheduledRecording.FAILED_REASON_PROGRAM_ENDED_BEFORE_RECORDING_STARTED).build())
                    ScheduledRecording.STATE_RECORDING_CANCELED -> toDelete.add(r)
                }
            }
            if (maxId < r.id) maxId = r.id
        }
        if (toUpdate.isNotEmpty()) updateScheduledRecording(*toUpdate.toTypedArray())
        if (toDelete.isNotEmpty()) removeScheduledRecording(*toDelete.toTypedArray())
        IdGenerator.SCHEDULED_RECORDING.setMaxId(maxId)
        if (recordedProgramLoadFinished) validateSeriesRecordings()
        dvrLoadFinished = true
        notifyDvrScheduleLoadFinished()
        // Tweak: Tvheadend-DVR – Server-Timer spiegeln
        if (Tweaks.isTvheadendDvr(context)) HtsDvrTimers.start(context, this)
        startSyncIfInitialized()
    }

    /** Sobald Pläne und Aufnahmen geladen sind: DB-Sync und Serien-Planung starten (einmal). */
    private fun startSyncIfInitialized() {
        if (!isInitialized || dbSync != null) return
        // Ohne DVR (kein DvrManager) keine Synchronisation
        if (TvSingletons.getSingletons(context).getDvrManager() == null) return
        dbSync = DvrDbSync(context, this).also { it.start() }
        SeriesRecordingScheduler.getInstance(context).start()
    }

    private fun queryRecordedPrograms(uri: Uri?) {
        val dbDispatcher = TvSingletons.getSingletons(context).getDbDispatcher()
        lateinit var job: Job
        job = scope.launch {
            val result = withContext(dbDispatcher) { loadRecordedPrograms(uri ?: RecordedPrograms.CONTENT_URI) }
            pendingJobs.remove(job)
            onRecordedProgramsLoadedFinished(uri, result)
        }
        pendingJobs.add(job)
    }

    /** Wie AsyncRecordedProgramQueryTask (inkl. series_id/state, falls vorhanden). */
    private fun loadRecordedPrograms(uri: Uri): List<RecordedProgram>? {
        var projection = RecordedProgram.PROJECTION
        if (TvProviderUtils.checkSeriesIdColumn(context, RecordedPrograms.CONTENT_URI)) {
            projection = TvProviderUtils.addExtraColumnsToProjection(projection, TvProviderUtils.EXTRA_PROGRAM_COLUMN_SERIES_ID)
        }
        if (TvProviderUtils.checkStateColumn(context, RecordedPrograms.CONTENT_URI)) {
            projection = TvProviderUtils.addExtraColumnsToProjection(projection, TvProviderUtils.EXTRA_PROGRAM_COLUMN_STATE)
        }
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val list = ArrayList<RecordedProgram>()
                while (c.moveToNext()) list.add(RecordedProgram.fromCursor(c))
                list
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying $uri", e)
            null
        }
    }

    private fun onRecordedProgramsLoadedFinished(uriIn: Uri?, loaded: List<RecordedProgram>?) {
        val uri = uriIn ?: RecordedPrograms.CONTENT_URI
        val programs = loaded.orEmpty()
        val segments = uri.pathSegments
        val isAll = uri.authority == TvContract.AUTHORITY && segments.size == 1 && segments[0] == "recorded_program"
        val isOne = uri.authority == TvContract.AUTHORITY && segments.size == 2 && segments[0] == "recorded_program"
        if (isAll) {
            if (!recordedProgramLoadFinished) {
                for (p in programs) if (isInputAvailable(p.inputId)) recordedPrograms[p.id] = p else recordedProgramsForRemovedInput[p.id] = p
                recordedProgramLoadFinished = true
                notifyRecordedProgramLoadFinished()
                if (isInitialized) validateSeriesRecordings()
                startSyncIfInitialized()
            } else if (programs.isEmpty()) {
                val old = ArrayList(recordedPrograms.values)
                recordedPrograms.clear()
                recordedProgramsForRemovedInput.clear()
                notifyRecordedProgramsRemoved(*old.toTypedArray())
            } else {
                // Unterschiede zur bisherigen Liste melden
                val old = HashMap(recordedPrograms)
                recordedPrograms.clear()
                recordedProgramsForRemovedInput.clear()
                val added = ArrayList<RecordedProgram>()
                val changed = ArrayList<RecordedProgram>()
                for (p in programs) {
                    if (isInputAvailable(p.inputId)) {
                        recordedPrograms[p.id] = p
                        if (old.remove(p.id) == null) added.add(p) else changed.add(p)
                    } else {
                        recordedProgramsForRemovedInput[p.id] = p
                    }
                }
                if (added.isNotEmpty()) notifyRecordedProgramsAdded(*added.toTypedArray())
                if (changed.isNotEmpty()) notifyRecordedProgramsChanged(*changed.toTypedArray())
                if (old.isNotEmpty()) notifyRecordedProgramsRemoved(*old.values.toTypedArray())
            }
            if (isInitialized) {
                validateSeriesRecordings()
                SeriesRecordingScheduler.getInstance(context).start()
            }
        } else if (isOne) {
            if (!recordedProgramLoadFinished) return
            val id = ContentUris.parseId(uri)
            if (programs.isEmpty()) {
                recordedProgramsForRemovedInput.remove(id)
                val old = recordedPrograms.remove(id) ?: return
                notifyRecordedProgramsRemoved(old)
                val series = seriesId2SeriesRecordings[old.seriesId]
                if (series != null && isEmptySeriesRecording(series)) removeSeriesRecording(series)
            } else {
                val p = programs[0]
                if (isInputAvailable(p.inputId)) {
                    if (recordedPrograms.put(id, p) == null) notifyRecordedProgramsAdded(p) else notifyRecordedProgramsChanged(p)
                } else {
                    recordedProgramsForRemovedInput[id] = p
                }
            }
        }
    }

    private fun getScheduledRecordingsPrograms(): List<ScheduledRecording> =
        if (!dvrLoadFinished) emptyList() else scheduledRecordings.values.sortedWith(ScheduledRecording.START_TIME_COMPARATOR)

    override fun getRecordedPrograms(): List<RecordedProgram> =
        if (!recordedProgramLoadFinished) emptyList() else ArrayList(recordedPrograms.values)

    override fun getRecordedPrograms(seriesRecordingId: Long): List<RecordedProgram> =
        if (!recordedProgramLoadFinished || getSeriesRecording(seriesRecordingId) == null) emptyList()
        else super.getRecordedPrograms(seriesRecordingId)

    override fun getAllScheduledRecordings(): List<ScheduledRecording> = ArrayList(scheduledRecordings.values)

    override fun getRecordingsWithState(vararg states: Int): List<ScheduledRecording> = scheduledRecordings.values.filter { it.state in states }

    override fun getSeriesRecordings(): List<SeriesRecording> = if (!dvrLoadFinished) emptyList() else ArrayList(seriesRecordings.values)
    override fun getSeriesRecordings(inputId: String): List<SeriesRecording> = seriesRecordings.values.filter { it.inputId == inputId }

    override fun getNextScheduledStartTimeAfter(time: Long): Long = getNextStartTimeAfter(getScheduledRecordingsPrograms(), time)

    override fun getScheduledRecordings(period: Range<Long>, state: Int): List<ScheduledRecording> =
        scheduledRecordings.values.filter { it.isOverLapping(period) && it.state == state }

    override fun getScheduledRecordings(seriesRecordingId: Long): List<ScheduledRecording> =
        scheduledRecordings.values.filter { it.seriesRecordingId == seriesRecordingId }

    override fun getScheduledRecordings(inputId: String): List<ScheduledRecording> = scheduledRecordings.values.filter { it.inputId == inputId }

    override fun getScheduledRecording(recordingId: Long) = scheduledRecordings[recordingId]
    override fun getScheduledRecordingForProgramId(programId: Long) = programId2ScheduledRecordings[programId]
    override fun getRecordedProgram(recordingId: Long) = recordedPrograms[recordingId]
    override fun getSeriesRecording(seriesRecordingId: Long) = seriesRecordings[seriesRecordingId]
    override fun getSeriesRecording(seriesId: String) = seriesId2SeriesRecordings[seriesId]

    override fun addScheduledRecording(vararg scheduledRecordings: ScheduledRecording) {
        // Tweak: Tvheadend-DVR – Aufnahmen des HTS-Plugins als Server-Timer anlegen statt lokal planen
        // (nur neue, noch nicht gestartete; fertige Einträge aus Aufnahmen bleiben lokale Verwaltung)
        val htsSchedules = scheduledRecordings.filter {
            it.state == ScheduledRecording.STATE_RECORDING_NOT_STARTED && HtsDvrTimers.handlesInput(context, it.inputId)
        }
        if (htsSchedules.isNotEmpty()) {
            htsSchedules.forEach { HtsDvrTimers.addTimer(context, it) }
            val rest = scheduledRecordings.filter { it !in htsSchedules }
            if (rest.isNotEmpty()) addScheduledRecording(*rest.toTypedArray())
            return
        }
        for (r in scheduledRecordings) {
            if (r.id == ScheduledRecording.ID_NOT_SET) r.id = IdGenerator.SCHEDULED_RECORDING.newId()
            this.scheduledRecordings[r.id] = r
            if (r.programId != ScheduledRecording.ID_NOT_SET) programId2ScheduledRecordings[r.programId] = r
        }
        if (dvrLoadFinished) notifyScheduledRecordingAdded(*scheduledRecordings)
        DvrDbFuture.AddScheduleFuture(dbHelper).executeOnDbThread(dbCallback, *scheduledRecordings)
        removeDeletedSchedules(*scheduledRecordings)
    }

    override fun addSeriesRecording(vararg seriesRecordings: SeriesRecording) {
        for (r in seriesRecordings) {
            r.id = IdGenerator.SERIES_RECORDING.newId()
            this.seriesRecordings[r.id] = r
            val previous = seriesId2SeriesRecordings.put(r.seriesId, r)
            SoftPreconditions.checkArgument(previous == null, TAG, "Attempt to add series recording with the duplicate series ID: %s", r.seriesId)
        }
        if (dvrLoadFinished) notifySeriesRecordingAdded(*seriesRecordings)
        DvrDbFuture.AddSeriesRecordingFuture(dbHelper).executeOnDbThread(dbCallback, *seriesRecordings)
    }

    override fun removeScheduledRecording(vararg scheduledRecordings: ScheduledRecording) =
        removeScheduledRecording(false, *scheduledRecordings)

    /**
     * Entfernt Aufnahmen. Nicht gestartete/abgebrochene Serien-Folgen werden als "gelöscht"
     * gemerkt (werden nicht erneut geplant), außer bei [forceRemove].
     */
    override fun removeScheduledRecording(forceRemove: Boolean, vararg scheduledRecordings: ScheduledRecording) {
        // Tweak: Tvheadend-DVR – gespiegelte Server-Timer nicht lokal entfernen (Löschen nur über DvrManager)
        if (scheduledRecordings.any { HtsDvrTimers.isMirrored(it.id) }) {
            val rest = scheduledRecordings.filterNot { HtsDvrTimers.isMirrored(it.id) }
            if (rest.isNotEmpty()) removeScheduledRecording(forceRemove, *rest.toTypedArray())
            return
        }
        val toDelete = ArrayList<ScheduledRecording>()
        val notToDelete = ArrayList<ScheduledRecording>()
        val seriesIdsToCheck = HashSet<Long>()
        for (r in scheduledRecordings) {
            this.scheduledRecordings.remove(r.id)
            deletedScheduleMap.remove(r.programId)
            programId2ScheduledRecordings.remove(r.programId)
            if (r.seriesRecordingId != SeriesRecording.ID_NOT_SET &&
                (r.state == ScheduledRecording.STATE_RECORDING_NOT_STARTED || r.state == ScheduledRecording.STATE_RECORDING_IN_PROGRESS)
            ) seriesIdsToCheck.add(r.seriesRecordingId)
            // Bugfix: Schlüssel ist die Aufnahme-ID (Original: Sendungs-ID)
            val isForRemovedInput = scheduledRecordingsForRemovedInput.remove(r.id) != null
            if (!isForRemovedInput && !forceRemove && r.seriesRecordingId != SeriesRecording.ID_NOT_SET &&
                (r.state == ScheduledRecording.STATE_RECORDING_NOT_STARTED || r.state == ScheduledRecording.STATE_RECORDING_CANCELED)
            ) {
                SoftPreconditions.checkState(r.programId != ScheduledRecording.ID_NOT_SET)
                val deleted = ScheduledRecording.buildFrom(r).setState(ScheduledRecording.STATE_RECORDING_DELETED).build()
                deletedScheduleMap[deleted.programId] = deleted
                notToDelete.add(deleted)
            } else {
                toDelete.add(r)
            }
        }
        if (dvrLoadFinished) {
            if (recordedProgramLoadFinished) checkAndRemoveEmptySeriesRecording(*seriesIdsToCheck.toLongArray())
            notifyScheduledRecordingRemoved(*scheduledRecordings)
        }
        // Serie weg: "gelöscht"-Einträge doch löschen
        val iter = notToDelete.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            if (!seriesRecordings.containsKey(r.seriesRecordingId)) {
                iter.remove()
                toDelete.add(r)
            }
        }
        if (toDelete.isNotEmpty()) DvrDbFuture.DeleteScheduleFuture(dbHelper).executeOnDbThread(dbCallback, *toDelete.toTypedArray())
        if (notToDelete.isNotEmpty()) DvrDbFuture.UpdateScheduleFuture(dbHelper).executeOnDbThread(dbCallback, *notToDelete.toTypedArray())
    }

    /** Serien entfernen: ausstehende Folgen löschen, übrige von der Serie lösen. */
    override fun removeSeriesRecording(vararg seasonSchedules: SeriesRecording) {
        val ids = HashSet<Long>()
        for (r in seasonSchedules) {
            seriesRecordings.remove(r.id)
            seriesId2SeriesRecordings.remove(r.seriesId)
            ids.add(r.id)
        }
        val toUpdate = ArrayList<ScheduledRecording>()
        val toDelete = ArrayList<ScheduledRecording>()
        for (r in scheduledRecordings.values) {
            if (r.seriesRecordingId !in ids) continue
            if (r.state == ScheduledRecording.STATE_RECORDING_NOT_STARTED) toDelete.add(r)
            else toUpdate.add(ScheduledRecording.buildFrom(r).setSeriesRecordingId(SeriesRecording.ID_NOT_SET).build())
        }
        // DB setzt die Serien-ID per Fremdschlüssel selbst auf NULL
        if (toUpdate.isNotEmpty()) updateScheduledRecording(false, *toUpdate.toTypedArray())
        if (toDelete.isNotEmpty()) removeScheduledRecording(true, *toDelete.toTypedArray())
        if (dvrLoadFinished) notifySeriesRecordingRemoved(*seasonSchedules)
        DvrDbFuture.DeleteSeriesRecordingFuture(dbHelper).executeOnDbThread(dbCallback, *seasonSchedules)
        removeDeletedSchedules(*seasonSchedules)
    }

    override fun updateScheduledRecording(vararg scheduledRecordings: ScheduledRecording) =
        updateScheduledRecording(true, *scheduledRecordings)

    private fun updateScheduledRecording(updateDb: Boolean, vararg schedules: ScheduledRecording) {
        val toUpdate = ArrayList<ScheduledRecording>()
        val seriesIdsToCheck = HashSet<Long>()
        for (r in schedules) {
            if (!SoftPreconditions.checkState(scheduledRecordings.containsKey(r.id), TAG, "Recording not found for: $r")) continue
            toUpdate.add(r)
            val old = scheduledRecordings.put(r.id, r)!!
            SoftPreconditions.checkState(r.channelId == old.channelId)
            val programId = r.programId
            if (old.programId != programId && old.programId != ScheduledRecording.ID_NOT_SET) {
                // Bugfix: Eintrag kann fehlen (Original: NPE)
                if (programId2ScheduledRecordings[old.programId]?.id == r.id) programId2ScheduledRecordings.remove(old.programId)
            }
            if (programId != ScheduledRecording.ID_NOT_SET) programId2ScheduledRecordings[programId] = r
            if (r.state == ScheduledRecording.STATE_RECORDING_FAILED && r.seriesRecordingId != SeriesRecording.ID_NOT_SET) {
                // Fehlgeschlagene Folge: evtl. ist die Serie jetzt leer
                seriesIdsToCheck.add(r.seriesRecordingId)
            }
        }
        if (toUpdate.isEmpty()) return
        val array = toUpdate.toTypedArray()
        if (dvrLoadFinished) notifyScheduledRecordingStatusChanged(*array)
        // Tweak: Tvheadend-DVR – gespiegelte Server-Timer nicht in die eigene DB schreiben
        val dbArray = array.filterNot { HtsDvrTimers.isMirrored(it.id) }.toTypedArray()
        if (updateDb && dbArray.isNotEmpty()) DvrDbFuture.UpdateScheduleFuture(dbHelper).executeOnDbThread(dbCallback, *dbArray)
        checkAndRemoveEmptySeriesRecording(*seriesIdsToCheck.toLongArray())
        removeDeletedSchedules(*schedules)
    }

    override fun updateSeriesRecording(vararg seriesRecordings: SeriesRecording) {
        for (r in seriesRecordings) {
            if (!SoftPreconditions.checkArgument(this.seriesRecordings.containsKey(r.id), TAG, "Non Existing Series ID: %s", r)) continue
            val old1 = this.seriesRecordings.put(r.id, r)
            val old2 = seriesId2SeriesRecordings.put(r.seriesId, r)
            SoftPreconditions.checkArgument(old1 == old2, TAG, "Series ID cannot be updated: %s", r)
        }
        if (dvrLoadFinished) notifySeriesRecordingChanged(*seriesRecordings)
        DvrDbFuture.UpdateSeriesRecordingFuture(dbHelper).executeOnDbThread(dbCallback, *seriesRecordings)
    }

    /**
     * Tweak: Tvheadend-DVR – ersetzt alle gespiegelten Server-Timer (nur im Speicher, ohne DB) und
     * meldet Hinzugefügte, Geänderte und Entfernte an die Listener.
     */
    fun setHtsTimers(timers: List<ScheduledRecording>) {
        val newIds = timers.map { it.id }.toSet()
        val removed = scheduledRecordings.values.filter { HtsDvrTimers.isMirrored(it.id) && it.id !in newIds }
        val added = ArrayList<ScheduledRecording>()
        val changed = ArrayList<ScheduledRecording>()
        for (r in removed) {
            scheduledRecordings.remove(r.id)
            if (programId2ScheduledRecordings[r.programId]?.id == r.id) programId2ScheduledRecordings.remove(r.programId)
        }
        for (r in timers) {
            val old = scheduledRecordings.put(r.id, r)
            if (old != null && old.programId != r.programId && programId2ScheduledRecordings[old.programId]?.id == r.id) {
                programId2ScheduledRecordings.remove(old.programId)
            }
            if (r.programId != ScheduledRecording.ID_NOT_SET) programId2ScheduledRecordings[r.programId] = r
            if (old == null) added.add(r) else changed.add(r)
        }
        if (!dvrLoadFinished) return
        if (removed.isNotEmpty()) notifyScheduledRecordingRemoved(*removed.toTypedArray())
        if (added.isNotEmpty()) notifyScheduledRecordingAdded(*added.toTypedArray())
        if (changed.isNotEmpty()) notifyScheduledRecordingStatusChanged(*changed.toTypedArray())
    }

    /** Input vorhanden (eingebauter Tuner zusätzlich nur mit eingehängtem Speicher). */
    private fun isInputAvailable(inputId: String?): Boolean =
        inputManager.hasTvInputInfo(inputId) && (inputId == null || !CommonUtils.isBundledInput(inputId) || storageStatusManager.isStorageMounted)

    /** Neu geplante Sendungen aus der "gelöscht"-Liste entfernen. */
    private fun removeDeletedSchedules(vararg added: ScheduledRecording) {
        val toDelete = added.mapNotNull { deletedScheduleMap.remove(it.programId) }
        if (toDelete.isNotEmpty()) DvrDbFuture.DeleteScheduleFuture(dbHelper).executeOnDbThread(dbCallback, *toDelete.toTypedArray())
    }

    private fun removeDeletedSchedules(vararg removedSeries: SeriesRecording) {
        val ids = removedSeries.map { it.id }.toSet()
        val toDelete = ArrayList<ScheduledRecording>()
        val iter = deletedScheduleMap.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.value.seriesRecordingId in ids) {
                toDelete.add(e.value)
                iter.remove()
            }
        }
        if (toDelete.isNotEmpty()) DvrDbFuture.DeleteScheduleFuture(dbHelper).executeOnDbThread(dbCallback, *toDelete.toTypedArray())
    }

    private fun unhideInput(inputId: String) {
        val movedSchedules = moveElements(scheduledRecordingsForRemovedInput, scheduledRecordings) { it.inputId == inputId }
        val movedRecorded = moveElements(recordedProgramsForRemovedInput, recordedPrograms) { it.inputId == inputId }
        val removedSeries = ArrayList<SeriesRecording>()
        val movedSeries = moveElements(seriesRecordingsForRemovedInput, seriesRecordings) { r ->
            if (r.inputId == inputId) {
                if (!isEmptySeriesRecording(r)) return@moveElements true
                removedSeries.add(r)
            }
            false
        }
        movedSchedules.forEach { programId2ScheduledRecordings[it.programId] = it }
        movedSeries.forEach { seriesId2SeriesRecordings[it.seriesId] = it }
        removedSeries.forEach { seriesRecordingsForRemovedInput.remove(it.id) }
        DvrDbFuture.DeleteSeriesRecordingFuture(dbHelper).executeOnDbThread(dbCallback, *removedSeries.toTypedArray())
        if (movedSchedules.isNotEmpty()) notifyScheduledRecordingAdded(*movedSchedules.toTypedArray())
        if (movedSeries.isNotEmpty()) notifySeriesRecordingAdded(*movedSeries.toTypedArray())
        if (movedRecorded.isNotEmpty()) notifyRecordedProgramsAdded(*movedRecorded.toTypedArray())
    }

    private fun hideInput(inputId: String) {
        val movedSchedules = moveElements(scheduledRecordings, scheduledRecordingsForRemovedInput) { it.inputId == inputId }
        val movedSeries = moveElements(seriesRecordings, seriesRecordingsForRemovedInput) { it.inputId == inputId }
        val movedRecorded = moveElements(recordedPrograms, recordedProgramsForRemovedInput) { it.inputId == inputId }
        movedSchedules.forEach { programId2ScheduledRecordings.remove(it.programId) }
        movedSeries.forEach { seriesId2SeriesRecordings.remove(it.seriesId) }
        if (movedSchedules.isNotEmpty()) notifyScheduledRecordingRemoved(*movedSchedules.toTypedArray())
        if (movedSeries.isNotEmpty()) notifySeriesRecordingRemoved(*movedSeries.toTypedArray())
        if (movedRecorded.isNotEmpty()) notifyRecordedProgramsRemoved(*movedRecorded.toTypedArray())
    }

    /** Alle Daten eines (nicht mehr vorhandenen) Speichers/Inputs endgültig löschen. */
    override fun forgetStorage(inputId: String) {
        val schedulesToDelete = ArrayList<ScheduledRecording>()
        scheduledRecordingsForRemovedInput.values.removeAll { r -> (inputId == r.inputId).also { if (it) schedulesToDelete.add(r) } }
        val seriesToDelete = ArrayList<SeriesRecording>()
        seriesRecordingsForRemovedInput.values.removeAll { r -> (inputId == r.inputId).also { if (it) seriesToDelete.add(r) } }
        recordedProgramsForRemovedInput.values.removeAll { inputId == it.inputId }
        DvrDbFuture.DeleteScheduleFuture(dbHelper).executeOnDbThread(dbCallback, *schedulesToDelete.toTypedArray())
        DvrDbFuture.DeleteSeriesRecordingFuture(dbHelper).executeOnDbThread(dbCallback, *seriesToDelete.toTypedArray())
        scope.launch(TvSingletons.getSingletons(context).getDbDispatcher()) {
            try {
                context.contentResolver.delete(RecordedPrograms.CONTENT_URI, "${RecordedPrograms.COLUMN_INPUT_ID} = ?", arrayOf(inputId))
            } catch (e: SQLiteException) {
                Log.e(TAG, "Failed to delete recorded programs for inputId: $inputId", e)
            }
        }
    }

    /** Leere, gestoppte Serien entfernen. Bugfix: auch aus dem Serien-ID-Index. */
    private fun validateSeriesRecordings() {
        val removed = seriesRecordings.values.filter { isEmptySeriesRecording(it) }
        if (removed.isEmpty()) return
        removed.forEach {
            seriesRecordings.remove(it.id)
            seriesId2SeriesRecordings.remove(it.seriesId)
        }
        val array = removed.toTypedArray()
        DvrDbFuture.DeleteSeriesRecordingFuture(dbHelper).executeOnDbThread(dbCallback, *array)
        if (dvrLoadFinished) notifySeriesRecordingRemoved(*array)
    }

    companion object {
        private const val TAG = "DvrDataManagerImpl"

        private fun <T> moveElements(from: HashMap<Long, T>, to: HashMap<Long, T>, filter: (T) -> Boolean): List<T> {
            val moved = ArrayList<T>()
            val iter = from.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (filter(e.value)) {
                    to[e.key] = e.value
                    iter.remove()
                    moved.add(e.value)
                }
            }
            return moved
        }

        /** Binärsuche: erste Startzeit nach [startTime]. */
        @JvmStatic
        internal fun getNextStartTimeAfter(list: List<ScheduledRecording>, startTime: Long): Long {
            var start = 0
            var end = list.size - 1
            while (start <= end) {
                val mid = (start + end) / 2
                if (list[mid].startTimeMs <= startTime) start = mid + 1 else end = mid - 1
            }
            return if (start < list.size) list[start].startTimeMs else DvrDataManager.NEXT_START_TIME_NOT_FOUND
        }
    }
}
