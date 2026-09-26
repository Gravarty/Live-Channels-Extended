package com.android.tv.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvContract.Programs
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.util.LruCache
import androidx.annotation.MainThread
import com.android.tv.common.SoftPreconditions
import com.android.tv.common.memory.MemoryManageable
import com.android.tv.common.util.Clock
import com.android.tv.data.api.Channel
import com.android.tv.data.api.Program
import com.android.tv.modules.DbDispatcher
import com.android.tv.util.TvProviderUtils
import com.android.tv.util.Utils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port von ProgramDataManager: hält die aktuelle Sendung je Kanal und (bei aktiviertem Prefetch)
 * einen Programm-Cache für die Programmübersicht. AsyncDbTasks ersetzt durch Coroutines auf dem DB-Thread.
 * Werte der BackendKnobs (AOSP-Standard) sind als Konstanten übernommen.
 */
@MainThread
@Singleton
class ProgramDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @DbDispatcher private val dbDispatcher: CoroutineDispatcher,
    private val contentResolver: ContentResolver,
    private val clock: Clock,
    private val channelDataManager: ChannelDataManager,
) : MemoryManageable {

    interface Callback {
        fun onProgramUpdated()
        fun onChannelUpdated()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper()) { handleMessage(it); true }

    private var started = false
    @Volatile var isCurrentProgramsLoadFinished = false
        private set
    private var programsUpdateJob: Job? = null
    private val programUpdateJobs = HashMap<Long, Job>()
    private val channelIdCurrentProgramMap: MutableMap<Long, Program> = ConcurrentHashMap()
    private val listenersByChannel = HashMap<Long, MutableSet<OnCurrentProgramUpdatedListener>>()
    private val callbacks = LinkedHashSet<Callback>()
    private var channelIdProgramCache: MutableMap<Long, ArrayList<Program>> = ConcurrentHashMap()
    private val completeInfoChannelIds = HashSet<Long>()
    private var prefetchEnabled = false
    private var programPrefetchUpdateWaitMs = PROGRAM_PREFETCH_UPDATE_WAIT_MS
    private var lastPrefetchTaskRunMs = 0L
    private var prefetchJob: Job? = null
    private var prefetchTimeRangeStartMs = 0L
    private var pauseProgramUpdate = false
    private val zeroLengthProgramCache = LruCache<Long, Program>(10)
    private var tunedChannelId = 0L
    private var maxFetchHoursMs = FETCH_HOURS_MS

    internal val contentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (!handler.hasMessages(MSG_UPDATE_CURRENT_PROGRAMS)) handler.sendEmptyMessage(MSG_UPDATE_CURRENT_PROGRAMS)
            if (isProgramUpdatePaused()) return
            if (prefetchEnabled) {
                handler.removeMessages(MSG_UPDATE_PREFETCH_PROGRAM)
                handler.sendEmptyMessage(MSG_UPDATE_PREFETCH_PROGRAM)
            }
        }
    }

    internal fun setProgramPrefetchUpdateWait(ms: Long) { programPrefetchUpdateWaitMs = ms }

    fun start() {
        if (started) return
        started = true
        handleUpdateCurrentPrograms()
        if (prefetchEnabled) handler.sendEmptyMessage(MSG_UPDATE_PREFETCH_PROGRAM)
        contentResolver.registerContentObserver(Programs.CONTENT_URI, true, contentObserver)
    }

    fun stop() {
        if (!started) return
        started = false
        contentResolver.unregisterContentObserver(contentObserver)
        handler.removeCallbacksAndMessages(null)
        clearTasks()
        cancelPrefetchTask()
        programsUpdateJob?.cancel()
        programsUpdateJob = null
    }

    fun getCurrentProgram(channelId: Long): Program? = channelIdCurrentProgramMap[channelId]

    fun getCurrentPrograms(): List<Program> = ArrayList(channelIdCurrentProgramMap.values)

    fun reload() {
        if (!handler.hasMessages(MSG_UPDATE_CURRENT_PROGRAMS)) handler.sendEmptyMessage(MSG_UPDATE_CURRENT_PROGRAMS)
        if (prefetchEnabled && !handler.hasMessages(MSG_UPDATE_PREFETCH_PROGRAM)) {
            handler.sendEmptyMessage(MSG_UPDATE_PREFETCH_PROGRAM)
        }
    }

    /** Lädt vollständige Programmdaten eines Kanals nach (Zeitfenster wächst beim Scrollen). */
    @JvmOverloads
    fun prefetchChannel(channelId: Long, selectedProgramIndex: Int = 0) {
        val startTimeMs = Utils.floorTime(clock.currentTimeMillis() - PROGRAM_GUIDE_SNAP_TIME_MS, PROGRAM_GUIDE_SNAP_TIME_MS)
        val programGuideMaxHoursMs = TimeUnit.HOURS.toMillis(PROGRAM_GUIDE_MAX_HOURS)
        var endTimeMs = 0L
        if (maxFetchHoursMs < programGuideMaxHoursMs && isHorizontalLoadNeeded(startTimeMs, channelId, selectedProgramIndex)) {
            maxFetchHoursMs = min(programGuideMaxHoursMs, maxFetchHoursMs + FETCH_HOURS_MS)
            completeInfoChannelIds.clear()
        }
        if (completeInfoChannelIds.isEmpty()) {
            endTimeMs = startTimeMs + programGuideMaxHoursMs
        } else if (channelId !in completeInfoChannelIds) {
            endTimeMs = startTimeMs + maxFetchHoursMs
        }
        if (endTimeMs > 0) {
            completeInfoChannelIds.add(channelId)
            runSingleChannelPrefetch(channelId, startTimeMs, endTimeMs)
        }
    }

    private fun isHorizontalLoadNeeded(startTimeMs: Long, channelId: Long, selectedProgramIndex: Int): Boolean {
        val programs = channelIdProgramCache[channelId] ?: return false
        val marginEndTime = startTimeMs + maxFetchHoursMs - BUFFER_HOURS_MS
        return programs.size > selectedProgramIndex && programs[selectedProgramIndex].endTimeUtcMillis > marginEndTime
    }

    fun onChannelTuned(channelId: Long) {
        tunedChannelId = channelId
        prefetchChannel(channelId)
    }

    fun addCallback(callback: Callback) { callbacks.add(callback) }
    fun removeCallback(callback: Callback) { callbacks.remove(callback) }

    fun setPrefetchEnabled(enable: Boolean) {
        if (prefetchEnabled == enable) return
        if (enable) {
            prefetchEnabled = true
            lastPrefetchTaskRunMs = 0
            if (started) handler.sendEmptyMessage(MSG_UPDATE_PREFETCH_PROGRAM)
        } else {
            prefetchEnabled = false
            cancelPrefetchTask()
            clearChannelInfoMap()
            handler.removeMessages(MSG_UPDATE_PREFETCH_PROGRAM)
        }
    }

    /** Programme eines Kanals ab [startTime] aus dem Cache (Prefetch muss aktiv sein). */
    fun getPrograms(channelId: Long, startTime: Long): List<Program> {
        SoftPreconditions.checkState(prefetchEnabled, TAG, "Prefetch is disabled.")
        val cached = channelIdProgramCache[channelId] ?: return emptyList()
        val startIndex = getProgramIndexAt(cached, startTime)
        return java.util.Collections.unmodifiableList(cached.subList(startIndex, cached.size))
    }

    private fun getProgramIndexAt(programs: List<Program>, time: Long): Int {
        val key = zeroLengthProgramCache[time] ?: createStubProgram(time, time).also { zeroLengthProgramCache.put(time, it) }
        var index = programs.binarySearch(key)
        if (index < 0) {
            index = -(index + 1) // Einfügeposition
            if (index > 0 && isProgramPlayedAt(programs[index - 1], time)) return index - 1
        }
        return index
    }

    private fun isProgramPlayedAt(program: Program, time: Long) =
        program.startTimeUtcMillis <= time && time <= program.endTimeUtcMillis

    /** Listener für einen Kanal; Channel.INVALID_ID = alle Kanäle. */
    fun addOnCurrentProgramUpdatedListener(channelId: Long, listener: OnCurrentProgramUpdatedListener) {
        listenersByChannel.getOrPut(channelId) { LinkedHashSet() }.add(listener)
    }

    fun removeOnCurrentProgramUpdatedListener(channelId: Long, listener: OnCurrentProgramUpdatedListener) {
        listenersByChannel[channelId]?.remove(listener)
    }

    private fun notifyCurrentProgramUpdate(channelId: Long, program: Program?) {
        listenersByChannel[channelId]?.toList()?.forEach { it.onCurrentProgramUpdated(channelId, program) }
        listenersByChannel[Channel.INVALID_ID]?.toList()?.forEach { it.onCurrentProgramUpdated(channelId, program) }
    }

    /** Setzt die aktuelle Sendung und plant die nächste Aktualisierung (Sendungsende bzw. 5–10 min). */
    private fun updateCurrentProgram(channelId: Long, program: Program?) {
        val previous = if (program == null) channelIdCurrentProgramMap.remove(channelId)
        else channelIdCurrentProgramMap.put(channelId, program)
        if (program != previous) {
            if (prefetchEnabled) removePreviousProgramsAndUpdateCurrentProgramInCache(channelId, program)
            notifyCurrentProgramUpdate(channelId, program)
        }
        val delayedTime = if (program == null) {
            PERIODIC_PROGRAM_UPDATE_MIN_MS +
                (Math.random() * (PERIODIC_PROGRAM_UPDATE_MAX_MS - PERIODIC_PROGRAM_UPDATE_MIN_MS)).toLong()
        } else {
            program.endTimeUtcMillis - clock.currentTimeMillis()
        }
        handler.sendMessageDelayed(handler.obtainMessage(MSG_UPDATE_ONE_CURRENT_PROGRAM, channelId), delayedTime)
    }

    private fun removePreviousProgramsAndUpdateCurrentProgramInCache(channelId: Long, currentProgram: Program?) {
        SoftPreconditions.checkState(prefetchEnabled, TAG, "Prefetch is disabled.")
        if (!Program.isProgramValid(currentProgram)) return
        currentProgram!!
        val cached = channelIdProgramCache.remove(channelId) ?: return
        val it = cached.listIterator()
        while (it.hasNext()) {
            val cachedProgram = it.next()
            if (cachedProgram.endTimeUtcMillis <= prefetchTimeRangeStartMs) {
                it.remove()
                continue
            }
            if (cachedProgram.endTimeUtcMillis <= currentProgram.startTimeUtcMillis) continue
            if (cachedProgram.startTimeUtcMillis < currentProgram.startTimeUtcMillis) {
                it.set(createStubProgram(cachedProgram.startTimeUtcMillis, currentProgram.startTimeUtcMillis))
                it.add(currentProgram)
            } else {
                it.set(currentProgram)
            }
            if (currentProgram.endTimeUtcMillis < cachedProgram.endTimeUtcMillis) {
                it.add(createStubProgram(currentProgram.endTimeUtcMillis, cachedProgram.endTimeUtcMillis))
            }
            break
        }
        if (cached.isEmpty()) cached.add(currentProgram)
        channelIdProgramCache[channelId] = cached
    }

    // ---- Aktuelle Sendungen aller Kanäle ----

    private fun handleUpdateCurrentPrograms() {
        if (programsUpdateJob != null) {
            handler.sendEmptyMessageDelayed(MSG_UPDATE_CURRENT_PROGRAMS, CURRENT_PROGRAM_UPDATE_WAIT_MS)
            return
        }
        clearTasks()
        handler.removeMessages(MSG_UPDATE_ONE_CURRENT_PROGRAM)
        val time = clock.currentTimeMillis()
        val uri = Programs.CONTENT_URI.buildUpon()
            .appendQueryParameter(PARAM_START_TIME, time.toString())
            .appendQueryParameter(PARAM_END_TIME, time.toString())
            .build()
        programsUpdateJob = scope.launch {
            val programs = withContext(dbDispatcher) {
                queryPrograms(uri, SORT_BY_CHANNEL_ID) { c ->
                    // Überlappende Einträge desselben Kanals verwerfen
                    val result = ArrayList<Program>()
                    var duplicateCount = 0
                    var last: Program? = null
                    while (c.moveToNext()) {
                        if (!coroutineContext.isActiveSafe()) return@queryPrograms result
                        val program = ProgramImpl.fromCursor(c)
                        if (Program.sameChannel(program, last) && Program.isOverlapping(program, last)) {
                            duplicateCount++
                            continue
                        }
                        last = program
                        result.add(program)
                    }
                    if (duplicateCount > 0) Log.w(TAG, "Found $duplicateCount overlapping programs")
                    result
                }
            }
            programsUpdateJob = null
            if (programs != null) {
                val removedChannelIds = HashSet(channelIdCurrentProgramMap.keys)
                for (program in programs) {
                    updateCurrentProgram(program.channelId, program)
                    removedChannelIds.remove(program.channelId)
                }
                for (channelId in removedChannelIds) {
                    if (prefetchEnabled) {
                        channelIdProgramCache.remove(channelId)
                        completeInfoChannelIds.remove(channelId)
                    }
                    channelIdCurrentProgramMap.remove(channelId)
                    notifyCurrentProgramUpdate(channelId, null)
                }
            }
            isCurrentProgramsLoadFinished = true
        }
    }

    private fun updateOneCurrentProgram(channelId: Long) {
        programUpdateJobs.remove(channelId)?.cancel()
        val time = clock.currentTimeMillis()
        val job = scope.launch {
            val program = withContext(dbDispatcher) {
                queryPrograms(TvContract.buildProgramsUriForChannel(channelId, time, time), SORT_BY_TIME) { c ->
                    if (c.moveToNext()) ProgramImpl.fromCursor(c) else null
                }
            }
            programUpdateJobs.remove(channelId)
            updateCurrentProgram(channelId, program)
        }
        programUpdateJobs[channelId] = job
    }

    // ---- Prefetch für die Programmübersicht ----

    private fun startPrefetch() {
        val time = clock.currentTimeMillis()
        val startTimeMs = Utils.floorTime(time - PROGRAM_GUIDE_SNAP_TIME_MS, PROGRAM_GUIDE_SNAP_TIME_MS)
        val endTimeMs = startTimeMs + TimeUnit.HOURS.toMillis(getFetchDuration())
        prefetchJob = scope.launch {
            val result = withContext(dbDispatcher) { prefetchAll(startTimeMs, endTimeMs) }
            prefetchJob = null
            if (isProgramUpdatePaused()) return@launch
            val nextDelay: Long
            if (result != null) {
                val now = clock.currentTimeMillis()
                lastPrefetchTaskRunMs = now
                nextDelay = Utils.floorTime(lastPrefetchTaskRunMs + PROGRAM_GUIDE_SNAP_TIME_MS, PROGRAM_GUIDE_SNAP_TIME_MS) - now
                channelIdProgramCache = result
                clearChannelInfoMap()
                prefetchChannel(tunedChannelId)
                notifyProgramUpdated()
            } else {
                nextDelay = PERIODIC_PROGRAM_UPDATE_MIN_MS
            }
            if (!handler.hasMessages(MSG_UPDATE_PREFETCH_PROGRAM)) {
                handler.sendEmptyMessageDelayed(MSG_UPDATE_PREFETCH_PROGRAM, nextDelay)
            }
        }
    }

    /** DB-Thread. Liefert null bei Misserfolg (bis zu 3 Versuche) oder Pause. */
    private suspend fun prefetchAll(startTimeMs: Long, endTimeMs: Long): MutableMap<Long, ArrayList<Program>>? {
        val uri = Programs.CONTENT_URI.buildUpon()
            .appendQueryParameter(PARAM_START_TIME, startTimeMs.toString())
            .appendQueryParameter(PARAM_END_TIME, endTimeMs.toString())
            .build()
        val programMap = HashMap<Long, ArrayList<Program>>()
        var lastReadProgram: Program? = null
        repeat(RETRY_COUNT) {
            if (isProgramUpdatePaused()) return null
            programMap.clear()
            var projection = ProgramImpl.PARTIAL_PROJECTION
            if (TvProviderUtils.checkSeriesIdColumn(context, Programs.CONTENT_URI) && Utils.isProgramsUri(uri)) {
                projection = TvProviderUtils.addExtraColumnsToProjection(projection, TvProviderUtils.EXTRA_PROGRAM_COLUMN_SERIES_ID)
            }
            try {
                val c = contentResolver.query(uri, projection, null, null, SORT_BY_TIME) ?: return@repeat
                c.use {
                    var duplicateCount = 0
                    while (c.moveToNext()) {
                        coroutineContext.ensureActive()
                        var program = ProgramImpl.fromCursorPartialProjection(c)
                        if (Program.isDuplicate(program, lastReadProgram)) {
                            duplicateCount++
                            continue
                        }
                        lastReadProgram = program
                        var programs = programMap[program.channelId]
                        if (programs == null) {
                            programs = ArrayList()
                            // Vollständige Daten der laufenden Sendung weiterverwenden
                            val current = channelIdCurrentProgramMap[program.channelId]
                            if (current != null && Program.isDuplicate(program, current)) program = current
                            programMap[program.channelId] = programs
                        }
                        programs.add(program)
                    }
                    if (duplicateCount > 0) Log.w(TAG, "Found $duplicateCount duplicate programs")
                }
                return programMap
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                if (DEBUG) Log.d(TAG, "Database is changed while querying. Will retry.")
            } catch (e: SecurityException) {
                Log.w(TAG, "Security exception during program data query", e)
            } catch (e: Exception) {
                Log.w(TAG, "Error during program data query", e)
            }
        }
        return null
    }

    private fun runSingleChannelPrefetch(channelId: Long, startTimeMs: Long, endTimeMs: Long) {
        scope.launch {
            val programs = withContext(dbDispatcher) {
                queryPrograms(TvContract.buildProgramsUriForChannel(channelId, startTimeMs, endTimeMs), SORT_BY_TIME) { c ->
                    ArrayList<Program>().apply { while (c.moveToNext()) add(ProgramImpl.fromCursor(c)) }
                }
            } ?: return@launch
            channelIdProgramCache[channelId] = programs
            notifyChannelUpdated()
        }
    }

    private fun clearChannelInfoMap() {
        completeInfoChannelIds.clear()
        maxFetchHoursMs = FETCH_HOURS_MS
    }

    /** Erstes Laden: 4 h; danach 48–336 h, abhängig von der Kanalanzahl (Ziel: 100 Kanäle). */
    private fun getFetchDuration(): Long {
        if (channelIdProgramCache.isEmpty()) return max(1L, PROGRAM_GUIDE_INITIAL_FETCH_HOURS)
        val channelCount = channelDataManager.channelCount
        return if (channelCount <= EPG_TARGET_CHANNEL_COUNT) {
            max(48L, PROGRAM_GUIDE_MAX_HOURS)
        } else {
            (PROGRAM_GUIDE_MAX_HOURS * EPG_TARGET_CHANNEL_COUNT / channelCount).coerceIn(48L, 336L)
        }
    }

    private fun notifyProgramUpdated() = callbacks.toList().forEach { it.onProgramUpdated() }
    private fun notifyChannelUpdated() = callbacks.toList().forEach { it.onChannelUpdated() }

    /** Gemeinsame Abfrage wie AsyncDbTask.AsyncQueryTask (inkl. series_id-Spalte). DB-Thread. */
    private suspend fun <T> queryPrograms(uri: Uri, sortOrder: String, onQuery: suspend (Cursor) -> T): T? {
        var projection = ProgramImpl.PROJECTION
        if (Utils.isProgramsUri(uri) && TvProviderUtils.checkSeriesIdColumn(context, Programs.CONTENT_URI)) {
            projection = TvProviderUtils.addExtraColumnsToProjection(projection, TvProviderUtils.EXTRA_PROGRAM_COLUMN_SERIES_ID)
        }
        return try {
            contentResolver.query(uri, projection, null, null, sortOrder)?.use { onQuery(it) }
                ?: run { Log.e(TAG, "Unknown query error for $uri"); null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Error querying $uri", e)
            null
        }
    }

    private fun kotlin.coroutines.CoroutineContext.isActiveSafe() = this[Job]?.isActive != false

    private fun handleMessage(msg: Message) {
        when (msg.what) {
            MSG_UPDATE_CURRENT_PROGRAMS -> handleUpdateCurrentPrograms()
            MSG_UPDATE_ONE_CURRENT_PROGRAM -> updateOneCurrentProgram(msg.obj as Long)
            MSG_UPDATE_PREFETCH_PROGRAM -> {
                if (isProgramUpdatePaused()) return
                if (prefetchJob != null) {
                    handler.sendEmptyMessageDelayed(msg.what, programPrefetchUpdateWaitMs)
                    return
                }
                val delayMillis = lastPrefetchTaskRunMs + programPrefetchUpdateWaitMs - clock.currentTimeMillis()
                if (delayMillis > 0) {
                    handler.sendEmptyMessageDelayed(MSG_UPDATE_PREFETCH_PROGRAM, delayMillis)
                } else {
                    startPrefetch()
                }
            }
        }
    }

    /** Pausiert Prefetch-Updates (z. B. während in der Programmübersicht gescrollt wird). */
    fun setPauseProgramUpdate(pause: Boolean) {
        SoftPreconditions.checkState(prefetchEnabled, TAG, "Prefetch is disabled.")
        if (pauseProgramUpdate && !pause && !handler.hasMessages(MSG_UPDATE_PREFETCH_PROGRAM)) {
            handler.sendEmptyMessage(MSG_UPDATE_PREFETCH_PROGRAM)
        }
        pauseProgramUpdate = pause
    }

    private fun isProgramUpdatePaused() = pauseProgramUpdate && channelIdProgramCache.isNotEmpty()

    fun setPrefetchTimeRange(startTimeMs: Long) {
        SoftPreconditions.checkState(prefetchEnabled, TAG, "Prefetch is disabled.")
        if (prefetchTimeRangeStartMs > startTimeMs && !handler.hasMessages(MSG_UPDATE_PREFETCH_PROGRAM)) {
            handler.sendEmptyMessage(MSG_UPDATE_PREFETCH_PROGRAM)
        }
        prefetchTimeRangeStartMs = startTimeMs
    }

    private fun clearTasks() {
        programUpdateJobs.values.forEach { it.cancel() }
        programUpdateJobs.clear()
    }

    private fun cancelPrefetchTask() {
        prefetchJob?.cancel()
        prefetchJob = null
    }

    private fun createStubProgram(startTimeMs: Long, endTimeMs: Long): Program =
        ProgramImpl.Builder()
            .setChannelId(Channel.INVALID_ID)
            .setStartTimeUtcMillis(startTimeMs)
            .setEndTimeUtcMillis(endTimeMs)
            .build()

    override fun performTrimMemory(level: Int) {
        listenersByChannel.entries.removeAll { it.value.isEmpty() }
    }

    companion object {
        private const val TAG = "ProgramDataManager"
        private const val DEBUG = false

        internal val PERIODIC_PROGRAM_UPDATE_MIN_MS = TimeUnit.MINUTES.toMillis(5)
        private val PERIODIC_PROGRAM_UPDATE_MAX_MS = TimeUnit.MINUTES.toMillis(10)
        private val PROGRAM_PREFETCH_UPDATE_WAIT_MS = TimeUnit.SECONDS.toMillis(5)
        private val CURRENT_PROGRAM_UPDATE_WAIT_MS = TimeUnit.SECONDS.toMillis(5)
        internal val PROGRAM_GUIDE_SNAP_TIME_MS = TimeUnit.MINUTES.toMillis(30)
        private val FETCH_HOURS_MS = TimeUnit.HOURS.toMillis(24)
        private val BUFFER_HOURS_MS = TimeUnit.HOURS.toMillis(6)
        private const val RETRY_COUNT = 3

        // DefaultBackendKnobsFlags (AOSP)
        private const val PROGRAM_GUIDE_INITIAL_FETCH_HOURS = 4L
        private const val PROGRAM_GUIDE_MAX_HOURS = 336L
        private const val EPG_TARGET_CHANNEL_COUNT = 100L

        private const val PARAM_START_TIME = "start_time"
        private const val PARAM_END_TIME = "end_time"
        private const val SORT_BY_TIME =
            "${Programs.COLUMN_START_TIME_UTC_MILLIS}, ${Programs.COLUMN_CHANNEL_ID}, ${Programs.COLUMN_END_TIME_UTC_MILLIS}"
        private const val SORT_BY_CHANNEL_ID =
            "${Programs.COLUMN_CHANNEL_ID}, ${Programs.COLUMN_START_TIME_UTC_MILLIS} DESC, " +
                "${Programs.COLUMN_END_TIME_UTC_MILLIS} ASC, ${Programs._ID} DESC"

        private const val MSG_UPDATE_CURRENT_PROGRAMS = 1000
        private const val MSG_UPDATE_ONE_CURRENT_PROGRAM = 1001
        private const val MSG_UPDATE_PREFETCH_PROGRAM = 1002
    }
}
