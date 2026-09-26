package com.android.tv.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.media.tv.TvContract
import android.media.tv.TvInputManager.TvInputCallback
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.android.tv.common.util.PermissionUtils
import com.android.tv.common.util.SharedPreferencesUtils
import com.android.tv.data.api.Channel
import com.android.tv.modules.DbDispatcher
import com.android.tv.util.TvInputManagerHelper
import com.android.tv.util.Utils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port von com.android.tv.data.ChannelDataManager: lädt alle Kanäle aus dem TvProvider und
 * hält sie im Speicher. Browsable/Locked werden lokal geändert und mit applyUpdatedValuesToDb()
 * geschrieben. AsyncTasks ersetzt durch Coroutines auf dem DB-Thread.
 */
@MainThread
@Singleton
class ChannelDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inputManager: TvInputManagerHelper,
    @DbDispatcher private val dbDispatcher: CoroutineDispatcher,
    private val contentResolver: ContentResolver,
) {
    interface Listener {
        /** Erstes Laden aus der DB abgeschlossen. */
        fun onLoadFinished()
        /** Kanalliste hat sich geändert. */
        fun onChannelListUpdated()
        /** Browsable-Status eines Kanals hat sich geändert. */
        fun onChannelBrowsableChanged()
    }

    interface ChannelListener {
        fun onChannelRemoved(channel: Channel)
        fun onChannelUpdated(channel: Channel)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Ersatz für AsyncTask.SERIAL_EXECUTOR beim Logo-Check. */
    private val logoCheckDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var started = false
    var isDbLoadFinished = false
        private set
    private var channelsUpdateJob: Job? = null
    private val postRunnablesAfterChannelUpdate = ArrayList<Runnable>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile private var data = ChannelData.EMPTY

    private val channelComparator = ChannelImpl.DefaultComparator(context, inputManager).apply {
        detectDuplicatesEnabled = true
    }

    private val handler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == MSG_UPDATE_CHANNELS) handleUpdateChannels()
        true
    }

    private val browsableUpdateChannelIds = HashSet<Long>()
    private val lockedUpdateChannelIds = HashSet<Long>()

    internal val contentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (!handler.hasMessages(MSG_UPDATE_CHANNELS)) handler.sendEmptyMessage(MSG_UPDATE_CHANNELS)
        }
    }

    // Ohne ACCESS_ALL_EPG_DATA (Nicht-System-App) wird "browsable" lokal gespeichert.
    private val storeBrowsableInSharedPreferences = !PermissionUtils.hasAccessAllEpg(context)
    private val browsableSharedPreferences: SharedPreferences =
        context.getSharedPreferences(SharedPreferencesUtils.SHARED_PREF_BROWSABLE, Context.MODE_PRIVATE)

    private val tvInputCallback = object : TvInputCallback() {
        override fun onInputAdded(inputId: String) {
            var channelAdded = false
            val newData = ChannelData(data)
            for (wrapper in data.channelWrapperMap.values) {
                if (wrapper.channel.inputId == inputId) {
                    wrapper.inputRemoved = false
                    addChannel(newData, wrapper.channel)
                    channelAdded = true
                }
            }
            if (channelAdded) {
                newData.channels.sortWith(channelComparator)
                data = newData
                notifyChannelListUpdated()
            }
        }

        override fun onInputRemoved(inputId: String) {
            val removed = data.channelWrapperMap.values.filter { it.channel.inputId == inputId }
            if (removed.isEmpty()) return
            removed.forEach { it.inputRemoved = true }
            val newData = ChannelData()
            newData.channelWrapperMap.putAll(data.channelWrapperMap)
            for (wrapper in newData.channelWrapperMap.values) {
                if (!wrapper.inputRemoved) addChannel(newData, wrapper.channel)
            }
            newData.channels.sortWith(channelComparator)
            data = newData
            notifyChannelListUpdated()
            removed.forEach { it.notifyChannelRemoved() }
        }
    }

    /** Startet das Laden und beobachtet danach Änderungen im TvProvider. */
    fun start() {
        if (started) return
        started = true
        handleUpdateChannels()
        contentResolver.registerContentObserver(TvContract.Channels.CONTENT_URI, true, contentObserver)
        inputManager.addCallback(tvInputCallback)
    }

    /** Stoppt, verwirft den Speicherstand und schreibt offene Änderungen in die DB. */
    fun stop() {
        if (!started) return
        started = false
        isDbLoadFinished = false
        inputManager.removeCallback(tvInputCallback)
        contentResolver.unregisterContentObserver(contentObserver)
        handler.removeCallbacksAndMessages(null)
        clearChannels()
        postRunnablesAfterChannelUpdate.clear()
        channelsUpdateJob?.cancel()
        channelsUpdateJob = null
        applyUpdatedValuesToDb()
    }

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun addChannelListener(channelId: Long, listener: ChannelListener) {
        data.channelWrapperMap[channelId]?.listeners?.add(listener)
    }

    fun removeChannelListener(channelId: Long, listener: ChannelListener) {
        data.channelWrapperMap[channelId]?.listeners?.remove(listener)
    }

    val channelCount: Int get() = data.channels.size

    /** Kopie der Kanalliste (sortiert, inkl. ausgeblendeter Kanäle). */
    fun getChannelList(): List<Channel> = ArrayList(data.channels)

    // Extended: Quelle – nur Kanäle der gewählten Quelle (Programmübersicht, Suche, Empfehlungen)
    fun getBrowsableChannelList(): List<Channel> = data.channels.filter { isVisible(it) }

    // ---- Extended: Quelle ----
    // Die Kanalliste zeigt nur die Kanäle eines Tuners/Services. Passthrough-Eingänge (HDMI) sind nie gefiltert.

    private val sourcePreferences: SharedPreferences =
        context.getSharedPreferences(PREF_SOURCE, Context.MODE_PRIVATE)

    /** Input-ID der gewählten Quelle; null = noch keine Quelle mit Kanälen. */
    var selectedSourceInputId: String? = sourcePreferences.getString(KEY_SELECTED_SOURCE, null)
        private set

    /** Kanal gehört zur gewählten Quelle (oder ist ein Passthrough-Eingang). */
    fun isInSelectedSource(channel: Channel): Boolean =
        channel.isPassthrough || selectedSourceInputId == null || channel.inputId == selectedSourceInputId

    /** Sichtbar = nicht ausgeblendet und in der gewählten Quelle. */
    fun isVisible(channel: Channel): Boolean = channel.isBrowsable && isInSelectedSource(channel)

    /** Alle Quellen mit mindestens einem sichtbaren Kanal, in Reihenfolge der Kanalliste. */
    fun getSourceInputIds(): List<String> =
        data.channels.asSequence().filter { it.isBrowsable && !it.isPassthrough }.map { it.inputId }.distinct().toList()

    /** Wählt eine Quelle. Ohne sichtbare Kanäle wird sie ignoriert. */
    fun selectSource(inputId: String) {
        if (inputId == selectedSourceInputId || inputId !in getSourceInputIds()) return
        setSelectedSource(inputId)
        notifyChannelBrowsableChanged()
    }

    /** Letzter gesehener Kanal pro Quelle (für den Quellenwechsel). */
    fun getLastChannelIdForSource(inputId: String): Long =
        sourcePreferences.getLong(KEY_LAST_CHANNEL_PREFIX + inputId, Channel.INVALID_ID)

    fun setLastChannelForSource(channel: Channel) {
        if (channel.isPassthrough) return
        sourcePreferences.edit().putLong(KEY_LAST_CHANNEL_PREFIX + channel.inputId, channel.id).apply()
    }

    /**
     * Gewählte Quelle entfernt oder ohne sichtbare Kanäle → erste andere Quelle mit Kanälen.
     * Gibt es keine mehr, wird die Auswahl gelöscht (App geht wie bisher ins Setup).
     */
    private fun validateSelectedSource() {
        val sources = getSourceInputIds()
        val selected = selectedSourceInputId
        if (selected != null && selected in sources) return
        val newSource = sources.firstOrNull()
        if (newSource != selected) {
            Log.i(TAG, "Quelle $selected nicht mehr verfügbar, wechsle zu $newSource")
            setSelectedSource(newSource)
        }
    }

    private fun setSelectedSource(inputId: String?) {
        selectedSourceInputId = inputId
        sourcePreferences.edit().putString(KEY_SELECTED_SOURCE, inputId).apply()
    }
    // ---- Ende Extended: Quelle ----

    fun getChannelCountForInput(inputId: String): Int = data.channelCountMap[inputId] ?: 0

    fun getBrowsableChannelCountForInput(inputId: String): Int =
        data.channels.count { it.inputId == inputId && it.isBrowsable }

    fun doesChannelExistInDb(channelId: Long): Boolean = data.channelWrapperMap[channelId] != null

    fun areAllChannelsHidden(): Boolean = data.channels.none { it.isBrowsable }

    /** Kanal oder null, wenn unbekannt oder sein Input entfernt wurde. */
    fun getChannel(channelId: Long?): Channel? {
        val wrapper = data.channelWrapperMap[channelId] ?: return null
        return if (wrapper.inputRemoved) null else wrapper.channel
    }

    /** Ändert "browsable" nur im Speicher; applyUpdatedValuesToDb() schreibt es. */
    @JvmOverloads
    fun updateBrowsable(channelId: Long, browsable: Boolean, skipNotifyChannelBrowsableChanged: Boolean = false) {
        val wrapper = data.channelWrapperMap[channelId] ?: return
        if (wrapper.channel.isBrowsable == browsable) return
        wrapper.channel.isBrowsable = browsable
        if (browsable == wrapper.browsableInDb) {
            browsableUpdateChannelIds.remove(wrapper.channel.id)
        } else {
            browsableUpdateChannelIds.add(wrapper.channel.id)
        }
        wrapper.notifyChannelUpdated()
        if (!skipNotifyChannelBrowsableChanged) notifyChannelBrowsableChanged()
    }

    // Extended: Quelle vor jeder Benachrichtigung prüfen, damit alle Listener denselben Stand sehen
    fun notifyChannelBrowsableChanged() {
        validateSelectedSource()
        listeners.forEach { it.onChannelBrowsableChanged() }
    }
    private fun notifyChannelListUpdated() {
        validateSelectedSource()
        listeners.forEach { it.onChannelListUpdated() }
    }
    private fun notifyLoadFinished() {
        validateSelectedSource()
        listeners.forEach { it.onLoadFinished() }
    }

    /** Lädt neu und führt [postRunnable] danach aus. */
    fun updateChannels(postRunnable: Runnable) {
        channelsUpdateJob?.cancel()
        channelsUpdateJob = null
        postRunnablesAfterChannelUpdate.add(postRunnable)
        if (!handler.hasMessages(MSG_UPDATE_CHANNELS)) handler.sendEmptyMessage(MSG_UPDATE_CHANNELS)
    }

    /** Ändert "locked" nur im Speicher; applyUpdatedValuesToDb() schreibt es. */
    fun updateLocked(channelId: Long, locked: Boolean) {
        val wrapper = data.channelWrapperMap[channelId] ?: return
        if (wrapper.channel.isLocked == locked) return
        wrapper.channel.isLocked = locked
        if (locked == wrapper.lockedInDb) {
            lockedUpdateChannelIds.remove(wrapper.channel.id)
        } else {
            lockedUpdateChannelIds.add(wrapper.channel.id)
        }
        wrapper.notifyChannelUpdated()
    }

    /** Schreibt geänderte browsable/locked-Werte (browsable ohne Systemrechte in SharedPreferences). */
    fun applyUpdatedValuesToDb() {
        val current = data
        val browsableIds = ArrayList<Long>()
        val unbrowsableIds = ArrayList<Long>()
        for (id in browsableUpdateChannelIds) {
            val wrapper = current.channelWrapperMap[id] ?: continue
            if (wrapper.channel.isBrowsable) browsableIds.add(id) else unbrowsableIds.add(id)
            wrapper.browsableInDb = wrapper.channel.isBrowsable
        }
        if (storeBrowsableInSharedPreferences) {
            val editor = browsableSharedPreferences.edit()
            // Bugfix: getChannel() liefert null, wenn der Input gerade entfernt ist → Wrapper nutzen
            browsableIds.forEach { id -> current.channelWrapperMap[id]?.let { editor.putBoolean(getBrowsableKey(it.channel), true) } }
            unbrowsableIds.forEach { id -> current.channelWrapperMap[id]?.let { editor.putBoolean(getBrowsableKey(it.channel), false) } }
            editor.apply()
        } else {
            if (browsableIds.isNotEmpty()) updateOneColumnValue(TvContract.Channels.COLUMN_BROWSABLE, 1, browsableIds)
            if (unbrowsableIds.isNotEmpty()) updateOneColumnValue(TvContract.Channels.COLUMN_BROWSABLE, 0, unbrowsableIds)
        }
        browsableUpdateChannelIds.clear()

        val lockedIds = ArrayList<Long>()
        val unlockedIds = ArrayList<Long>()
        for (id in lockedUpdateChannelIds) {
            val wrapper = current.channelWrapperMap[id] ?: continue
            if (wrapper.channel.isLocked) lockedIds.add(id) else unlockedIds.add(id)
            wrapper.lockedInDb = wrapper.channel.isLocked
        }
        if (lockedIds.isNotEmpty()) updateOneColumnValue(TvContract.Channels.COLUMN_LOCKED, 1, lockedIds)
        if (unlockedIds.isNotEmpty()) updateOneColumnValue(TvContract.Channels.COLUMN_LOCKED, 0, unlockedIds)
        lockedUpdateChannelIds.clear()
        if (DEBUG) {
            Log.d(TAG, "applyUpdatedValuesToDb browsable=${browsableIds.size} unbrowsable=${unbrowsableIds.size} " +
                "locked=${lockedIds.size} unlocked=${unlockedIds.size}")
        }
    }

    private fun addChannel(target: ChannelData, channel: Channel) {
        target.channels.add(channel)
        target.channelCountMap.merge(channel.inputId, 1, Int::plus)
    }

    private fun clearChannels() {
        data = ChannelData.EMPTY
    }

    private fun handleUpdateChannels() {
        channelsUpdateJob?.cancel()
        channelsUpdateJob = scope.launch {
            val channels = withContext(dbDispatcher) { queryAllChannels() }
            channelsUpdateJob = null
            if (channels != null) onChannelsLoaded(channels)
        }
    }

    /** Lädt neu, sofern das erste Laden fertig ist und kein Update ansteht. */
    fun reload() {
        if (isDbLoadFinished && !handler.hasMessages(MSG_UPDATE_CHANNELS)) {
            handler.sendEmptyMessage(MSG_UPDATE_CHANNELS)
        }
    }

    /** DB-Thread. Bricht bei Abbruch des Jobs ab. */
    private suspend fun queryAllChannels(): List<Channel>? {
        return try {
            contentResolver.query(TvContract.Channels.CONTENT_URI, ChannelImpl.PROJECTION, null, null, null)
                ?.use { c ->
                    val result = ArrayList<Channel>()
                    while (c.moveToNext()) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        result.add(ChannelImpl.fromCursor(c))
                    }
                    result
                } ?: run {
                Log.e(TAG, "Unknown query error for channels")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Error querying channels", e)
            null
        }
    }

    private fun onChannelsLoaded(channels: List<Channel>) {
        val newData = ChannelData()
        newData.channelWrapperMap.putAll(data.channelWrapperMap)
        val removedChannelIds = HashSet(newData.channelWrapperMap.keys)
        val removedWrappers = ArrayList<ChannelWrapper>()
        val updatedWrappers = ArrayList<ChannelWrapper>()
        var channelAdded = false
        var channelUpdated = false
        var channelRemoved = false

        val deletedBrowsableMap: MutableMap<String, *>? =
            if (storeBrowsableInSharedPreferences) HashMap(browsableSharedPreferences.all) else null

        for (channel in channels) {
            if (storeBrowsableInSharedPreferences) {
                val key = getBrowsableKey(channel)
                // Bugfix: Original nimmt ohne gespeicherten Wert "nicht sichtbar" an. Als Nicht-System-App
                // wird aber nie etwas gespeichert (MARK_NEW_CHANNELS_BROWSABLE = false), daher waren alle
                // Kanäle versteckt und die App sprang beim Start immer ins Setup. Neue Kanäle gelten jetzt
                // als sichtbar; vom Nutzer ausgeblendete Kanäle bleiben per gespeichertem false versteckt.
                channel.isBrowsable = browsableSharedPreferences.getBoolean(key, true)
                deletedBrowsableMap!!.remove(key)
            }
            val channelId = channel.id
            val newlyAdded = !removedChannelIds.remove(channelId)
            if (newlyAdded) {
                checkChannelLogoExist(channel)
                val wrapper = ChannelWrapper(channel)
                newData.channelWrapperMap[channelId] = wrapper
                if (!wrapper.inputRemoved) channelAdded = true
            } else {
                val wrapper = newData.channelWrapperMap[channelId]!!
                if (!wrapper.channel.hasSameReadOnlyInfo(channel)) {
                    val old = wrapper.channel
                    // browsable/locked sind nur hier änderbar – nicht überschreiben
                    channel.isBrowsable = old.isBrowsable
                    channel.isLocked = old.isLocked
                    wrapper.channel.copyFrom(channel)
                    if (!wrapper.inputRemoved) {
                        channelUpdated = true
                        updatedWrappers.add(wrapper)
                    }
                }
            }
        }

        if (storeBrowsableInSharedPreferences && deletedBrowsableMap!!.isNotEmpty() &&
            PermissionUtils.hasReadTvListings(context)
        ) {
            // Einträge gelöschter Kanäle entfernen (nur wenn wirklich alle Kanäle lesbar sind)
            val editor = browsableSharedPreferences.edit()
            deletedBrowsableMap.keys.forEach { editor.remove(it) }
            editor.apply()
        }

        for (id in removedChannelIds) {
            val wrapper = newData.channelWrapperMap.remove(id)!!
            if (!wrapper.inputRemoved) {
                channelRemoved = true
                removedWrappers.add(wrapper)
            }
        }
        for (wrapper in newData.channelWrapperMap.values) {
            if (!wrapper.inputRemoved) addChannel(newData, wrapper.channel)
        }
        newData.channels.sortWith(channelComparator)
        data = newData

        if (!isDbLoadFinished) {
            isDbLoadFinished = true
            notifyLoadFinished()
        } else if (channelAdded || channelUpdated || channelRemoved) {
            notifyChannelListUpdated()
        }
        removedWrappers.forEach { it.notifyChannelRemoved() }
        updatedWrappers.forEach { it.notifyChannelUpdated() }
        postRunnablesAfterChannelUpdate.forEach { it.run() }
        postRunnablesAfterChannelUpdate.clear()
    }

    /** Prüft im Hintergrund, ob ein Kanallogo existiert (ersetzt CheckChannelLogoExistTask). */
    private fun checkChannelLogoExist(channel: Channel) {
        scope.launch {
            val exists = withContext(logoCheckDispatcher) {
                try {
                    contentResolver.openAssetFileDescriptor(TvContract.buildChannelLogoUri(channel.id), "r")
                        ?.use { true } ?: false
                } catch (e: FileNotFoundException) {
                    false // kein Logo
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to find logo for $channel", e)
                    false
                }
            }
            data.channelWrapperMap[channel.id]?.channel?.setChannelLogoExist(exists)
        }
    }

    private fun updateOneColumnValue(columnName: String, columnValue: Int, ids: List<Long>) {
        if (!PermissionUtils.hasAccessAllEpg(context)) return
        scope.launch(dbDispatcher) {
            val values = ContentValues().apply { put(columnName, columnValue) }
            contentResolver.update(
                TvContract.Channels.CONTENT_URI, values, Utils.buildSelectionForIds(TvContract.Channels._ID, ids), null)
        }
    }

    private fun getBrowsableKey(channel: Channel) = "${channel.inputId}|${channel.id}"

    private inner class ChannelWrapper(val channel: Channel) {
        val listeners = HashSet<ChannelListener>()
        var browsableInDb = channel.isBrowsable
        var lockedInDb = channel.isLocked
        var inputRemoved = !inputManager.hasTvInputInfo(channel.inputId)

        fun notifyChannelUpdated() = listeners.forEach { it.onChannelUpdated(channel) }
        fun notifyChannelRemoved() = listeners.forEach { it.onChannelRemoved(channel) }
    }

    /** Snapshot der Kanaldaten. Wird nach dem Befüllen nur noch gelesen. */
    private class ChannelData(
        val channelWrapperMap: MutableMap<Long, ChannelWrapper> = HashMap(),
        val channelCountMap: MutableMap<String, Int> = HashMap(),
        val channels: MutableList<Channel> = ArrayList(),
    ) {
        constructor(other: ChannelData) :
            this(HashMap(other.channelWrapperMap), HashMap(other.channelCountMap), ArrayList(other.channels))

        companion object {
            val EMPTY = ChannelData()
        }
    }

    companion object {
        private const val TAG = "ChannelDataManager"
        private const val DEBUG = false
        private const val MSG_UPDATE_CHANNELS = 1000
        // Extended: Quelle
        private const val PREF_SOURCE = "com.android.tv.extended.source"
        private const val KEY_SELECTED_SOURCE = "selected_source"
        private const val KEY_LAST_CHANNEL_PREFIX = "last_channel_"
    }
}
