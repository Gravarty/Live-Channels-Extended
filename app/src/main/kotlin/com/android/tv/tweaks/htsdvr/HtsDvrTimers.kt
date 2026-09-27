package com.android.tv.tweaks.htsdvr

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.media.tv.TvContract
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.MainThread
import com.android.tv.R
import com.android.tv.dvr.DvrDataManagerImpl
import com.android.tv.dvr.data.ScheduledRecording
import com.android.tv.tweaks.Tweaks
import com.gravarty.htsp.tvinput.HtspDvrContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tweak: Tvheadend-DVR. Einzeltimer des Servers (`timers`) werden als [ScheduledRecording] nur im
 * Speicher des [DvrDataManagerImpl] gespiegelt (keine eigene DB, kein lokaler Planer). Anlegen und
 * Löschen laufen über den DVR-Provider des HTS-Plugins; jede Server-Änderung meldet der Provider per
 * ContentObserver. Provider-Aufrufe blockieren (bis 30 s) und laufen daher nie im Main-Thread.
 */
@MainThread
object HtsDvrTimers {
    private const val TAG = "HtsDvrTimers"

    /** Gespiegelte Timer bekommen IDs ab hier, damit sie nie mit lokalen IDs kollidieren. */
    private const val MIRROR_ID_BASE = 1L shl 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dataManager: DvrDataManagerImpl? = null
    private var appContext: Context? = null
    private var loadJob: Job? = null
    private var reloadPending = false

    /** Lokale ID → Timer auf dem Server. */
    private val serverTimers = HashMap<Long, ServerTimer>()

    private class ServerTimer(val serverId: Long, val createdBySeriesOrTimeTimer: Boolean)

    private class TimerRow(
        val serverId: Long,
        val tvChannelId: Long,
        val title: String?,
        val subtitle: String?,
        val description: String?,
        val startMs: Long,
        val stopMs: Long,
        val startExtraMin: Long,
        val stopExtraMin: Long,
        val state: String?,
        val enabled: Boolean,
        val eventId: Long,
        val createdBySeriesOrTimeTimer: Boolean,
        var inputId: String? = null,
        var programId: Long = ScheduledRecording.ID_NOT_SET,
    )

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload()
    }

    @JvmStatic
    fun isMirrored(id: Long): Boolean = id >= MIRROR_ID_BASE

    /** Aufnahme dieses Eingangs läuft über den Tvheadend-Server. */
    @JvmStatic
    fun handlesInput(context: Context, inputId: String?): Boolean =
        HtsDvr.isHtsInput(inputId) && Tweaks.isTvheadendDvr(context)

    @JvmStatic
    fun start(context: Context, dataManager: DvrDataManagerImpl) {
        if (this.dataManager != null) return
        this.dataManager = dataManager
        appContext = context.applicationContext
        context.contentResolver.registerContentObserver(HtspDvrContract.BASE_URI, true, observer)
        reload()
    }

    private fun reload() {
        val context = appContext ?: return
        if (loadJob != null) {
            reloadPending = true
            return
        }
        loadJob = scope.launch {
            val rows = withContext(Dispatchers.IO) { queryTimers(context) }
            loadJob = null
            if (rows != null) apply(rows)
            if (reloadPending) {
                reloadPending = false
                reload()
            }
        }
    }

    private fun queryTimers(context: Context): List<TimerRow>? {
        val rows = ArrayList<TimerRow>()
        try {
            context.contentResolver.query(HtspDvrContract.TIMERS_URI, null, null, null, null)?.use { c ->
                val d = HtspDvrContract.Dvr
                fun long(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
                fun str(col: String) = c.getColumnIndex(col).let { if (it < 0) null else c.getString(it) }
                while (c.moveToNext()) {
                    val tvChannelId = long(d.TV_CHANNEL_ID)
                    if (tvChannelId <= 0) continue // Sender nicht im TvProvider
                    rows.add(TimerRow(
                        serverId = long(d.ID),
                        tvChannelId = tvChannelId,
                        title = str(d.TITLE),
                        subtitle = str(d.SUBTITLE),
                        description = str(d.DESCRIPTION),
                        startMs = long(d.START) * 1000,
                        stopMs = long(d.STOP) * 1000,
                        startExtraMin = long(d.START_EXTRA),
                        stopExtraMin = long(d.STOP_EXTRA),
                        state = str(d.STATE),
                        enabled = c.getColumnIndex(d.ENABLED).let { it < 0 || c.isNull(it) || c.getInt(it) != 0 },
                        eventId = long(d.EVENT_ID),
                        createdBySeriesOrTimeTimer = !str(d.AUTOREC_ID).isNullOrEmpty() || !str(d.TIMEREC_ID).isNullOrEmpty(),
                    ))
                }
            } ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query timers", e)
            return null
        }
        val inputIds = HashMap<Long, String?>()
        for (row in rows) {
            row.inputId = inputIds.getOrPut(row.tvChannelId) { queryChannelInputId(context, row.tvChannelId) }
            if (row.eventId > 0) row.programId = queryProgramId(context, row)
        }
        return rows
    }

    private fun queryChannelInputId(context: Context, channelId: Long): String? = try {
        context.contentResolver.query(TvContract.buildChannelUri(channelId), arrayOf(TvContract.Channels.COLUMN_INPUT_ID),
            null, null, null)?.use { c -> if (c.moveToNext()) c.getString(0) else null }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to query channel $channelId", e)
        null
    }

    /** Sendung zum Timer: gleiche Event-ID in COLUMN_INTERNAL_PROVIDER_DATA (Text, Dezimalzahl). */
    private fun queryProgramId(context: Context, row: TimerRow): Long = try {
        context.contentResolver.query(TvContract.buildProgramsUriForChannel(row.tvChannelId, row.startMs, row.stopMs),
            arrayOf(TvContract.Programs._ID, TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA), null, null, null)?.use { c ->
            var id = ScheduledRecording.ID_NOT_SET
            while (c.moveToNext()) {
                if (c.getString(1)?.toLongOrNull() == row.eventId) {
                    id = c.getLong(0)
                    break
                }
            }
            id
        } ?: ScheduledRecording.ID_NOT_SET
    } catch (e: Exception) {
        Log.w(TAG, "Failed to query program for event ${row.eventId}", e)
        ScheduledRecording.ID_NOT_SET
    }

    private fun apply(rows: List<TimerRow>) {
        val dataManager = dataManager ?: return
        serverTimers.clear()
        val schedules = rows.map { row ->
            val id = MIRROR_ID_BASE + row.serverId
            serverTimers[id] = ServerTimer(row.serverId, row.createdBySeriesOrTimeTimer)
            ScheduledRecording.builder(row.inputId, row.tvChannelId, row.startMs, row.stopMs)
                .setId(id)
                .setProgramId(row.programId)
                .setProgramTitle(row.title)
                .setEpisodeTitle(row.subtitle)
                .setProgramDescription(row.description)
                .setStartOffsetMs(row.startExtraMin * 60_000)
                .setEndOffsetMs(row.stopExtraMin * 60_000)
                .setState(toState(row))
                .build()
        }
        dataManager.setHtsTimers(schedules)
    }

    /** pvr.hts-Zustände: scheduled | recording | completed | error; deaktivierte Timer als abgebrochen. */
    private fun toState(row: TimerRow): Int = when {
        !row.enabled -> ScheduledRecording.STATE_RECORDING_CANCELED
        row.state == "recording" -> ScheduledRecording.STATE_RECORDING_IN_PROGRESS
        row.state == "completed" -> ScheduledRecording.STATE_RECORDING_FINISHED
        row.state == "error" -> ScheduledRecording.STATE_RECORDING_FAILED
        else -> ScheduledRecording.STATE_RECORDING_NOT_STARTED
    }

    /** "Aufnehmen": Timer aus dem EPG (event_id), ohne Sendung ein manueller Timer. */
    @JvmStatic
    fun addTimer(context: Context, schedule: ScheduledRecording) {
        val appContext = context.applicationContext
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val values = ContentValues()
                val eventId = if (schedule.programId != ScheduledRecording.ID_NOT_SET) queryEventId(appContext, schedule.programId) else null
                if (eventId != null) {
                    values.put(HtspDvrContract.Dvr.EVENT_ID, eventId)
                } else {
                    values.put(HtspDvrContract.Dvr.TV_CHANNEL_ID, schedule.channelId)
                    schedule.programTitle?.let { values.put(HtspDvrContract.Dvr.TITLE, it) }
                    values.put(HtspDvrContract.Dvr.START,
                        if (schedule.startTimeMs <= System.currentTimeMillis()) 0L else schedule.startTimeMs / 1000)
                    values.put(HtspDvrContract.Dvr.STOP, schedule.endTimeMs / 1000)
                }
                if (schedule.startOffsetMs > 0) values.put(HtspDvrContract.Dvr.START_EXTRA, schedule.startOffsetMs / 60_000)
                if (schedule.endOffsetMs > 0) values.put(HtspDvrContract.Dvr.STOP_EXTRA, schedule.endOffsetMs / 60_000)
                try {
                    appContext.contentResolver.insert(HtspDvrContract.TIMERS_URI, values) != null
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add timer", e)
                    false
                }
            }
            if (!ok) Toast.makeText(appContext, R.string.tweak_tvheadend_dvr_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryEventId(context: Context, programId: Long): Long? = try {
        context.contentResolver.query(ContentUris.withAppendedId(TvContract.Programs.CONTENT_URI, programId),
            arrayOf(TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA), null, null, null)?.use { c ->
            if (c.moveToNext()) c.getString(0)?.toLongOrNull() else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to query event id of program $programId", e)
        null
    }

    /**
     * Timer löschen (eine laufende Aufnahme stoppt dabei). Timer eines Serien- oder Zeit-Timers
     * werden wie in pvr.hts nur deaktiviert.
     */
    @JvmStatic
    fun removeTimer(context: Context, schedule: ScheduledRecording) {
        val timer = serverTimers[schedule.id] ?: return
        val appContext = context.applicationContext
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val uri = ContentUris.withAppendedId(HtspDvrContract.TIMERS_URI, timer.serverId)
                try {
                    if (timer.createdBySeriesOrTimeTimer) {
                        appContext.contentResolver.update(uri, ContentValues().apply { put(HtspDvrContract.Dvr.ENABLED, 0) }, null, null) > 0
                    } else {
                        appContext.contentResolver.delete(uri, null, null) > 0
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove timer ${timer.serverId}", e)
                    false
                }
            }
            if (!ok) Toast.makeText(appContext, R.string.tweak_tvheadend_dvr_error, Toast.LENGTH_SHORT).show()
        }
    }
}
