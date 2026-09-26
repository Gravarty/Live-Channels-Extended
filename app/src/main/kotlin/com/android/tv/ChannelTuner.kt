package com.android.tv

import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.android.tv.common.SoftPreconditions
import com.android.tv.data.ChannelDataManager
import com.android.tv.data.api.Channel
import com.android.tv.util.TvInputManagerHelper

/** Port von com.android.tv.ChannelTuner: aktueller Kanal und Kanalwechsel (hoch/runter). */
@MainThread
class ChannelTuner(
    private val channelDataManager: ChannelDataManager,
    private val inputManager: TvInputManagerHelper,
) {
    interface Listener {
        /** Kanalliste fertig geladen. */
        fun onLoadFinished()
        /** Liste der sichtbaren Kanäle geändert. */
        fun onBrowsableChannelListChanged()
        /** Aktueller Kanal existiert nicht mehr. */
        fun onCurrentChannelUnavailable(channel: Channel?)
        /** Kanal gewechselt. */
        fun onChannelChanged(previousChannel: Channel?, currentChannel: Channel?)
    }

    private var started = false
    private var channelDataManagerLoaded = false
    private val channels = ArrayList<Channel>()
    private val browsableChannels = ArrayList<Channel>()
    private val channelMap = HashMap<Long, Channel>()
    private val channelIndexMap = HashMap<Long, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()

    var currentChannel: Channel? = null
    var currentInputInfo: TvInputInfo? = null
        private set

    private val channelDataManagerListener = object : ChannelDataManager.Listener {
        override fun onLoadFinished() {
            channelDataManagerLoaded = true
            updateChannelData(channelDataManager.getChannelList())
            listeners.toList().forEach { it.onLoadFinished() }
        }

        override fun onChannelListUpdated() = updateChannelData(channelDataManager.getChannelList())

        override fun onChannelBrowsableChanged() {
            updateBrowsableChannels()
            listeners.toList().forEach { it.onBrowsableChannelListChanged() }
        }
    }

    fun start() {
        check(!started) { "start is called twice" }
        started = true
        channelDataManager.addListener(channelDataManagerListener)
        if (channelDataManager.isDbLoadFinished) {
            handler.post { channelDataManagerListener.onLoadFinished() }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        channelDataManager.removeListener(channelDataManagerListener)
        currentChannel = null
        channels.clear()
        browsableChannels.clear()
        channelMap.clear()
        channelIndexMap.clear()
        channelDataManagerLoaded = false
    }

    fun areAllChannelsLoaded(): Boolean = channelDataManagerLoaded

    fun getBrowsableChannelList(): List<Channel> = java.util.Collections.unmodifiableList(browsableChannels)

    val browsableChannelCount: Int get() = browsableChannels.size

    val currentChannelId: Long get() = currentChannel?.id ?: Channel.INVALID_ID

    val currentChannelUri: Uri?
        get() {
            val channel = currentChannel ?: return null
            return if (channel.isPassthrough) TvContract.buildChannelUriForPassthroughInput(channel.inputId)
            else TvContract.buildChannelUri(channel.id)
        }

    val isCurrentChannelPassthrough: Boolean get() = currentChannel?.isPassthrough == true

    /** Zum nächsten/vorherigen sichtbaren Kanal wechseln. */
    fun moveToAdjacentBrowsableChannel(up: Boolean): Boolean {
        val channel = getAdjacentBrowsableChannel(up) ?: return false
        setCurrentChannelAndNotify(channelMap[channel.id])
        return true
    }

    /** Nächster/vorheriger sichtbarer Kanal (mit Umlauf am Listenende). */
    fun getAdjacentBrowsableChannel(up: Boolean): Channel? {
        if (isCurrentChannelPassthrough || browsableChannelCount == 0) return null
        val current = currentChannel
        val channelIndex: Int
        if (current == null) {
            channelIndex = 0
            val channel = channels[channelIndex]
            if (isVisible(channel)) return channel
        } else {
            // Bugfix: gelöschter aktueller Kanal führte zu NPE → vom Listenanfang suchen
            channelIndex = channelIndexMap[current.id] ?: 0
        }
        val size = channels.size
        for (i in 0 until size) {
            var next = if (up) channelIndex + 1 + i else channelIndex - 1 - i + size
            if (next >= size) next -= size
            val channel = channels[next]
            if (isVisible(channel)) return channel
        }
        Log.e(TAG, "This code should not be reached")
        return null
    }

    /** Der Kanal selbst, wenn sichtbar, sonst der nächstgelegene sichtbare. */
    fun findNearestBrowsableChannel(channelId: Long): Channel? {
        if (browsableChannelCount == 0) return null
        val channel = channelMap[channelId] ?: return browsableChannels[0]
        if (isVisible(channel)) return channel
        val index = channelIndexMap[channelId] ?: return browsableChannels[0]
        val size = channels.size
        for (i in 1..size / 2) {
            channels[(index + i) % size].takeIf { isVisible(it) }?.let { return it }
            channels[(index - i + size) % size].takeIf { isVisible(it) }?.let { return it }
        }
        throw IllegalStateException("This code should be unreachable in findNearestBrowsableChannel")
    }

    fun moveToChannel(channel: Channel?): Boolean {
        if (channel == null) return false
        if (channel.isPassthrough) {
            setCurrentChannelAndNotify(channel)
            return true
        }
        SoftPreconditions.checkState(channelDataManagerLoaded, TAG, "Channel data is not loaded")
        val newChannel = channelMap[channel.id] ?: return false
        setCurrentChannelAndNotify(newChannel)
        return true
    }

    fun resetCurrentChannel() = setCurrentChannelAndNotify(null)

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    private fun setCurrentChannelAndNotify(channel: Channel?) {
        if (currentChannel === channel || (channel != null && channel.hasSameReadOnlyInfo(currentChannel))) return
        val previous = currentChannel
        currentChannel = channel
        if (channel != null) currentInputInfo = inputManager.getTvInputInfo(channel.inputId)
        // Extended: Quelle – Kanal einer anderen Quelle (Launcher, Suche, DVR) → Quelle wechselt mit
        if (channel != null && !channel.isPassthrough) {
            if (!channelDataManager.isInSelectedSource(channel)) channelDataManager.selectSource(channel.inputId)
            channelDataManager.setLastChannelForSource(channel)
        }
        listeners.toList().forEach { it.onChannelChanged(previous, channel) }
    }

    private fun updateChannelData(newChannels: List<Channel>) {
        channels.clear()
        channels.addAll(newChannels)
        channelMap.clear()
        channelIndexMap.clear()
        newChannels.forEachIndexed { i, channel ->
            channelMap[channel.id] = channel
            channelIndexMap[channel.id] = i
        }
        updateBrowsableChannels()
        val current = currentChannel
        if (current != null && !current.isPassthrough) {
            setCurrentChannelAndNotify(channelMap[current.id])
            if (currentChannel == null) listeners.toList().forEach { it.onCurrentChannelUnavailable(current) }
        }
        listeners.toList().forEach { it.onBrowsableChannelListChanged() }
    }

    // Extended: Quelle – "sichtbar" heißt zusätzlich "in der gewählten Quelle"
    private fun isVisible(channel: Channel) = channelDataManager.isVisible(channel)

    private fun updateBrowsableChannels() {
        browsableChannels.clear()
        channels.filterTo(browsableChannels) { isVisible(it) }
    }

    companion object {
        private const val TAG = "ChannelTuner"
    }
}
