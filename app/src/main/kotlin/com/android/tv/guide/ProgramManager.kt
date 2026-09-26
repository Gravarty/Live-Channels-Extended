package com.android.tv.guide

import android.util.ArraySet
import androidx.annotation.MainThread
import com.android.tv.data.ChannelDataManager
import com.android.tv.data.GenreItems
import com.android.tv.data.ProgramDataManager
import com.android.tv.data.api.Channel
import com.android.tv.data.api.Program
import com.android.tv.dvr.DvrDataManager
import com.android.tv.dvr.DvrScheduleManager
import com.android.tv.dvr.data.ScheduledRecording
import com.android.tv.util.TvInputManagerHelper
import com.android.tv.util.Utils
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Datenmodell der Programmübersicht: Kanäle (mit Genre-Filter), Zeitfenster und die Einträge je
 * Kanal (Sendungen, Lücken, gesperrte Kanäle, geplante Aufnahmen).
 */
@MainThread
class ProgramManager(
    private val tvInputManagerHelper: TvInputManagerHelper,
    private val channelDataManager: ChannelDataManager,
    private val programDataManager: ProgramDataManager,
    private val dvrDataManager: DvrDataManager?,
    private val dvrScheduleManager: DvrScheduleManager?,
) {
    interface Listener {
        fun onGenresUpdated()
        fun onChannelsUpdated()
        fun onTimeRangeUpdated()
    }

    fun interface TableEntriesUpdatedListener {
        fun onTableEntriesUpdated()
    }

    fun interface TableEntryChangedListener {
        fun onTableEntryChanged(entry: TableEntry)
    }

    open class ListenerAdapter : Listener {
        override fun onGenresUpdated() {}
        override fun onChannelsUpdated() {}
        override fun onTimeRangeUpdated() {}
    }

    var startTime = 0L
        private set
    private var endUtcMillis = 0L
    var fromUtcMillis = 0L
        private set
    var toUtcMillis = 0L
        private set
    private var channels: List<Channel> = ArrayList()
    private val channelIdEntriesMap = HashMap<Long, MutableList<TableEntry>>()
    private val genreChannelList = ArrayList<List<Channel>>()
    val filteredGenreIds = ArrayList<Int>()
    var selectedGenreId = GenreItems.ID_ALL_CHANNELS
        private set
    private var filteredChannels: List<Channel> = channels
    private var channelDataLoaded = false
    private val listeners = ArraySet<Listener>()
    private val tableEntriesUpdatedListeners = ArraySet<TableEntriesUpdatedListener>()
    private val tableEntryChangedListeners = ArraySet<TableEntryChangedListener>()

    private val dvrLoadedListener = object : DvrDataManager.OnDvrScheduleLoadFinishedListener {
        override fun onDvrScheduleLoadFinished() {
            val dvr = dvrDataManager ?: return
            if (channelDataLoaded) dvr.getAllScheduledRecordings().forEach { scheduledRecordingListener.onScheduledRecordingAdded(it) }
            dvr.removeDvrScheduleLoadFinishedListener(this)
        }
    }

    private val channelDataManagerListener = object : ChannelDataManager.Listener {
        override fun onLoadFinished() {
            channelDataLoaded = true
            updateChannels(false)
        }

        override fun onChannelListUpdated() = updateChannels(false)
        override fun onChannelBrowsableChanged() = updateChannels(false)
    }

    private val programDataManagerCallback = object : ProgramDataManager.Callback {
        override fun onProgramUpdated() = updateTableEntries(true)
        override fun onChannelUpdated() {
            updateTableEntriesWithoutNotification(false)
            notifyTableEntriesUpdated()
        }
    }

    private val scheduledRecordingListener = object : DvrDataManager.ScheduledRecordingListener {
        override fun onScheduledRecordingAdded(vararg scheduledRecordings: ScheduledRecording) =
            scheduledRecordings.forEach { replaceSchedule(it, it) }

        override fun onScheduledRecordingRemoved(vararg scheduledRecordings: ScheduledRecording) =
            scheduledRecordings.forEach { replaceSchedule(it, null) }

        override fun onScheduledRecordingStatusChanged(vararg scheduledRecordings: ScheduledRecording) =
            scheduledRecordings.forEach { replaceSchedule(it, it) }

        private fun replaceSchedule(schedule: ScheduledRecording, newSchedule: ScheduledRecording?) {
            val old = getTableEntry(schedule) ?: return
            updateEntry(old, TableEntry(old.channelId, old.program, newSchedule, old.entryStartUtcMillis, old.entryEndUtcMillis, old.isBlocked))
        }
    }

    private val onConflictStateChangeListener = DvrScheduleManager.OnConflictStateChangeListener { _, schedules ->
        schedules.forEach { schedule -> getTableEntry(schedule)?.let { notifyTableEntryUpdated(it) } }
    }

    /** Beim Öffnen/Schließen der Programmübersicht an-/abmelden (und Prefetch pausieren). */
    internal fun programGuideVisibilityChanged(visible: Boolean) {
        programDataManager.setPauseProgramUpdate(visible)
        if (visible) {
            channelDataManager.addListener(channelDataManagerListener)
            programDataManager.addCallback(programDataManagerCallback)
            dvrDataManager?.let {
                if (!it.isDvrScheduleLoadFinished) it.addDvrScheduleLoadFinishedListener(dvrLoadedListener)
                it.addScheduledRecordingListener(scheduledRecordingListener)
            }
            dvrScheduleManager?.addOnConflictStateChangeListener(onConflictStateChangeListener)
        } else {
            channelDataManager.removeListener(channelDataManagerListener)
            programDataManager.removeCallback(programDataManagerCallback)
            dvrDataManager?.let {
                it.removeDvrScheduleLoadFinishedListener(dvrLoadedListener)
                it.removeScheduledRecordingListener(scheduledRecordingListener)
            }
            dvrScheduleManager?.removeOnConflictStateChangeListener(onConflictStateChangeListener)
        }
    }

    internal fun addListener(l: Listener) { listeners.add(l) }
    internal fun addTableEntriesUpdatedListener(l: TableEntriesUpdatedListener) { tableEntriesUpdatedListeners.add(l) }
    internal fun addTableEntryChangedListener(l: TableEntryChangedListener) { tableEntryChangedListeners.add(l) }
    internal fun removeListener(l: Listener) { listeners.remove(l) }
    internal fun removeTableEntriesUpdatedListener(l: TableEntriesUpdatedListener) { tableEntriesUpdatedListeners.remove(l) }
    internal fun removeTableEntryChangedListener(l: TableEntryChangedListener) { tableEntryChangedListeners.remove(l) }

    /** Kanalliste nach Genre filtern. */
    internal fun resetChannelListWithGenre(genreId: Int) {
        if (genreId == selectedGenreId) return
        filteredChannels = genreChannelList.getOrNull(genreId) ?: throw IllegalStateException("Genre filter isn't ready.")
        selectedGenreId = genreId
        notifyChannelsUpdated()
    }

    /** Anfangs-Zeitfenster setzen und alles neu aufbauen. */
    internal fun updateInitialTimeRange(startUtcMillis: Long, endUtcMillis: Long) {
        startTime = startUtcMillis
        if (endUtcMillis > this.endUtcMillis) this.endUtcMillis = endUtcMillis
        programDataManager.setPrefetchTimeRange(startTime)
        updateChannels(true)
        setTimeRange(startUtcMillis, endUtcMillis)
    }

    /** Zeitfenster verschieben, begrenzt auf [startTime, Ende]. */
    internal fun shiftTime(timeMillisToScroll: Long) {
        var from = fromUtcMillis + timeMillisToScroll
        var to = toUtcMillis + timeMillisToScroll
        if (from < startTime) {
            to += startTime - from
            from = startTime
        }
        if (to > endUtcMillis) {
            from -= to - endUtcMillis
            to = endUtcMillis
        }
        setTimeRange(from, to)
    }

    internal val shiftedTime: Long get() = fromUtcMillis - startTime

    internal fun getProgramIdIndex(channelId: Long, entryId: Long): Int =
        channelIdEntriesMap[channelId]?.indexOfFirst { it.id == entryId } ?: -1

    internal fun getProgramIndexAtTime(channelId: Long, time: Long): Int =
        channelIdEntriesMap[channelId]?.indexOfFirst { it.entryStartUtcMillis <= time && time < it.entryEndUtcMillis } ?: -1

    internal val channelCount: Int get() = filteredChannels.size

    internal fun getChannel(channelIndex: Int): Channel? = filteredChannels.getOrNull(channelIndex)
    internal fun getChannelIndex(channel: Channel?): Int = filteredChannels.indexOf(channel)
    internal fun getChannelIndex(channelId: Long): Int = getChannelIndex(channelDataManager.getChannel(channelId))

    /** Bugfix: unbekannter Kanal lieferte im Original einen NPE. */
    internal fun getTableEntryCount(channelId: Long): Int = channelIdEntriesMap[channelId]?.size ?: 0

    internal fun getTableEntry(channelId: Long, index: Int): TableEntry {
        programDataManager.prefetchChannel(channelId, index)
        return channelIdEntriesMap[channelId]!![index]
    }

    private fun updateChannels(clearPreviousTableEntries: Boolean) {
        channels = channelDataManager.getBrowsableChannelList()
        selectedGenreId = GenreItems.ID_ALL_CHANNELS
        filteredChannels = channels
        updateTableEntriesWithoutNotification(clearPreviousTableEntries)
        // Reihenfolge wichtig: erst Kanäle, dann Einträge melden
        notifyChannelsUpdated()
        notifyTableEntriesUpdated()
        buildGenreFilters()
    }

    internal fun setChannels(channels: List<Channel>) {
        this.channels = ArrayList(channels)
        selectedGenreId = GenreItems.ID_ALL_CHANNELS
        filteredChannels = this.channels
        buildGenreFilters()
    }

    private fun updateTableEntries(clear: Boolean) {
        updateTableEntriesWithoutNotification(clear)
        notifyTableEntriesUpdated()
        buildGenreFilters()
    }

    /** Einträge je Kanal neu bauen und bis zum gemeinsamen Ende mit Lücken auffüllen. */
    private fun updateTableEntriesWithoutNotification(clear: Boolean) {
        if (clear) channelIdEntriesMap.clear()
        val parentalControlsEnabled = tvInputManagerHelper.isParentalControlsEnabled()
        for (channel in channels) {
            val entries = createProgramEntries(channel.id, parentalControlsEnabled)
            channelIdEntriesMap[channel.id] = entries
            val last = entries.lastOrNull() ?: continue
            if (endUtcMillis < last.entryEndUtcMillis && last.entryEndUtcMillis != Long.MAX_VALUE) {
                endUtcMillis = last.entryEndUtcMillis
            }
        }
        if (endUtcMillis <= startTime) return
        for (channel in channels) {
            val entries = channelIdEntriesMap[channel.id] ?: continue
            if (entries.isEmpty()) {
                entries.add(TableEntry(channel.id, null, null, startTime, endUtcMillis, false))
                continue
            }
            val last = entries.last()
            if (endUtcMillis > last.entryEndUtcMillis) {
                entries.add(TableEntry(channel.id, null, null, last.entryEndUtcMillis, endUtcMillis, false))
            } else if (last.entryEndUtcMillis == Long.MAX_VALUE) {
                // Gesperrter Kanal: bis zum Ende strecken
                entries.removeAt(entries.size - 1)
                entries.add(TableEntry(last.channelId, last.program, last.scheduledRecording, last.entryStartUtcMillis, endUtcMillis, last.isBlocked))
            }
        }
    }

    private fun buildGenreFilters() {
        val lists = ArrayList<MutableList<Channel>>()
        repeat(GenreItems.getGenreCount()) { lists.add(ArrayList()) }
        for (channel in channels) {
            programDataManager.getCurrentProgram(channel.id)?.canonicalGenres?.forEach { genre ->
                lists[GenreItems.getId(genre)].add(channel)
            }
        }
        genreChannelList.clear()
        genreChannelList.addAll(lists)
        genreChannelList[GenreItems.ID_ALL_CHANNELS] = channels
        filteredGenreIds.clear()
        filteredGenreIds.add(0)
        for (i in 1 until GenreItems.getGenreCount()) if (genreChannelList[i].isNotEmpty()) filteredGenreIds.add(i)
        selectedGenreId = GenreItems.ID_ALL_CHANNELS
        filteredChannels = channels
        notifyGenresUpdated()
    }

    private fun getTableEntry(scheduledRecording: ScheduledRecording): TableEntry? =
        getTableEntry(scheduledRecording.channelId, scheduledRecording.programId)

    private fun getTableEntry(channelId: Long, entryId: Long): TableEntry? =
        channelIdEntriesMap[channelId]?.firstOrNull { it.id == entryId }

    private fun updateEntry(old: TableEntry, newEntry: TableEntry) {
        val entries = channelIdEntriesMap[old.channelId] ?: return
        val index = entries.indexOf(old)
        if (index < 0) return // Bugfix: Eintrag inzwischen ersetzt (Original: Absturz)
        entries[index] = newEntry
        notifyTableEntryUpdated(newEntry)
    }

    private fun setTimeRange(fromUtcMillis: Long, toUtcMillis: Long) {
        if (this.fromUtcMillis != fromUtcMillis || this.toUtcMillis != toUtcMillis) {
            this.fromUtcMillis = fromUtcMillis
            this.toUtcMillis = toUtcMillis
            notifyTimeRangeUpdated()
        }
    }

    /** Sendungen ab Startzeit, Lücken als Einträge ohne Sendung; zu kurzer erster Eintrag wird verschmolzen. */
    private fun createProgramEntries(channelId: Long, parentalControlsEnabled: Boolean): MutableList<TableEntry> {
        val entries = ArrayList<TableEntry>()
        // Bugfix: Kanal kann inzwischen fehlen (Original: NPE)
        val channelLocked = parentalControlsEnabled && channelDataManager.getChannel(channelId)?.isLocked == true
        if (channelLocked) {
            entries.add(TableEntry(channelId, null, null, startTime, Long.MAX_VALUE, true))
        } else {
            var lastProgramEndTime = startTime
            for (program in programDataManager.getPrograms(channelId, startTime)) {
                if (program.channelId == INVALID_ID) continue // Platzhalter
                val programStartTime = max(program.startTimeUtcMillis, startTime)
                val programEndTime = program.endTimeUtcMillis
                if (programStartTime > lastProgramEndTime) {
                    entries.add(TableEntry(channelId, null, null, lastProgramEndTime, programStartTime, false))
                    lastProgramEndTime = programStartTime
                }
                if (programEndTime > lastProgramEndTime) {
                    val scheduledRecording = dvrDataManager?.getScheduledRecordingForProgramId(program.id)
                    entries.add(TableEntry(channelId, program, scheduledRecording, lastProgramEndTime, programEndTime, false))
                    lastProgramEndTime = programEndTime
                }
            }
        }
        if (entries.size > 1) {
            val second = entries[1]
            if (second.entryStartUtcMillis < startTime + FIRST_ENTRY_MIN_DURATION) {
                entries.removeAt(0)
                entries[0] = TableEntry(second.channelId, second.program, second.scheduledRecording, startTime,
                    second.entryEndUtcMillis, second.isBlocked)
            }
        }
        return entries
    }

    private fun notifyGenresUpdated() = listeners.toList().forEach { it.onGenresUpdated() }
    private fun notifyChannelsUpdated() = listeners.toList().forEach { it.onChannelsUpdated() }
    private fun notifyTimeRangeUpdated() = listeners.toList().forEach { it.onTimeRangeUpdated() }
    private fun notifyTableEntriesUpdated() = tableEntriesUpdatedListeners.toList().forEach { it.onTableEntriesUpdated() }
    private fun notifyTableEntryUpdated(entry: TableEntry) = tableEntryChangedListeners.toList().forEach { it.onTableEntryChanged(entry) }

    /** Eintrag im Raster: Sendung oder Lücke, ggf. gesperrt/mit Aufnahme. */
    class TableEntry internal constructor(
        @JvmField val channelId: Long,
        @JvmField val program: Program?,
        @JvmField val scheduledRecording: ScheduledRecording?,
        @JvmField val entryStartUtcMillis: Long,
        @JvmField val entryEndUtcMillis: Long,
        val isBlocked: Boolean,
    ) {
        /** Lücken bekommen eine negative ID aus der Endzeit. */
        val id: Long get() = program?.id ?: -entryEndUtcMillis
        val isGap: Boolean get() = !Program.isProgramValid(program)
        val isCurrentProgram: Boolean
            get() {
                val now = System.currentTimeMillis()
                return entryStartUtcMillis <= now && entryEndUtcMillis > now
            }

        fun hasGenre(genreId: Int): Boolean = !isGap && program!!.hasGenre(genreId)
        val width: Int get() = GuideUtils.convertMillisToPixel(entryStartUtcMillis, entryEndUtcMillis)

        override fun toString() = "TableEntry{hashCode=${hashCode()}, channelId=$channelId, program=$program, " +
            "startTime=${Utils.toTimeString(entryStartUtcMillis)}, endTimeTime=${Utils.toTimeString(entryEndUtcMillis)}}"
    }

    companion object {
        private const val TAG = "ProgramManager"
        internal val FIRST_ENTRY_MIN_DURATION = TimeUnit.MINUTES.toMillis(1)
        private const val INVALID_ID = -1L

        @JvmStatic
        fun createTableEntryForTest(channelId: Long, program: Program?, scheduledRecording: ScheduledRecording?,
            entryStartUtcMillis: Long, entryEndUtcMillis: Long, isBlocked: Boolean) =
            TableEntry(channelId, program, scheduledRecording, entryStartUtcMillis, entryEndUtcMillis, isBlocked)
    }
}
