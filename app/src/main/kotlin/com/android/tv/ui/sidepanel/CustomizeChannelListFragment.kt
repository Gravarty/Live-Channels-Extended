package com.android.tv.ui.sidepanel

import android.content.Context
import android.media.tv.TvContract.Channels
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import com.android.tv.R
import com.android.tv.common.util.SharedPreferencesUtils
import com.android.tv.data.ChannelImpl
import com.android.tv.data.ChannelNumber
import com.android.tv.data.api.Channel
import com.android.tv.ui.OnRepeatedKeyInterceptListener
import com.android.tv.util.TvInputManagerHelper
import com.android.tv.util.Utils

/**
 * Kanalliste anpassen: Kanäle ein-/ausblenden, gruppiert nach Quelle oder HD/SD.
 * Der fokussierte Kanal läuft in der verkleinerten TvView.
 */
class CustomizeChannelListFragment : SideFragment<Item>() {

    private val channels = ArrayList<Channel>()
    private var initialChannelId = Channel.INVALID_ID
    private var lastFocusedChannelId = Channel.INVALID_ID
    private lateinit var inputManager: TvInputManagerHelper
    private lateinit var channelComparator: ChannelImpl.DefaultComparator
    private var groupByFragmentRunning = false
    private val items = ArrayList<Item>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = mainActivity.tvInputManagerHelper
        initialChannelId = mainActivity.currentChannelId
        channelComparator = ChannelImpl.DefaultComparator(requireActivity(), inputManager)
        if (groupingType == null) {
            groupingType = requireContext()
                .getSharedPreferences(SharedPreferencesUtils.SHARED_PREF_UI_SETTINGS, Context.MODE_PRIVATE)
                .getInt(PREF_KEY_GROUP_SETTINGS, GROUP_BY_SOURCE)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        val listView = view.findViewById<VerticalGridView>(R.id.side_panel_list)
        listView.setOnKeyInterceptListener(object : OnRepeatedKeyInterceptListener(listView) {
            override fun onInterceptKeyEvent(event: KeyEvent): Boolean {
                // Beim Loslassen von Hoch/Runter auf den fokussierten Kanal tunen
                if (event.action == KeyEvent.ACTION_UP &&
                    (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
                    lastFocusedChannelId != Channel.INVALID_ID
                ) {
                    mainActivity.tuneToChannel(channelDataManager.getChannel(lastFocusedChannelId))
                }
                return super.onInterceptKeyEvent(event)
            }
        })
        if (!groupByFragmentRunning) {
            mainActivity.startShrunkenTvView(false, true)
            val initialPosition = items.indexOfFirst { it is ChannelItem && it.channel.id == initialChannelId }
            setSelectedPosition(if (initialPosition >= 0) initialPosition else 0)
            lastFocusedChannelId = initialChannelId
            if (lastFocusedChannelId != Channel.INVALID_ID && lastFocusedChannelId != mainActivity.currentChannelId) {
                mainActivity.tuneToChannel(channelDataManager.getChannel(lastFocusedChannelId))
            }
        }
        groupByFragmentRunning = false
        return view
    }

    override fun onDestroyView() {
        channelDataManager.applyUpdatedValuesToDb()
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainActivity.endShrunkenTvView()
    }

    override fun getTitle(): String = getString(R.string.side_panel_title_edit_channels_for_an_input)

    override fun getItemList(): List<Item> {
        items.clear()
        channels.clear()
        // Extended: Quelle – nur Kanäle der gewählten Quelle
        channels.addAll(channelDataManager.getChannelList().filter { channelDataManager.isInSelectedSource(it) })
        if (groupingType == GROUP_BY_SOURCE) addItemForGroupBySource(items) else addItemForGroupByHdSd(items)
        return items
    }

    /** "Alle auswählen" bei Gruppen mit nur einem Kanal entfernen. */
    private fun cleanUpOneChannelGroupItem(items: MutableList<Item>) {
        val iter = items.iterator()
        while (iter.hasNext()) {
            val item = iter.next()
            if (item is SelectGroupItem && item.channelItemsInGroup.size == 1) {
                item.channelItemsInGroup[0].selectGroupItem = null
                iter.remove()
            }
        }
    }

    private fun addItemForGroupBySource(items: MutableList<Item>) {
        items.add(GroupBySubMenu(getString(R.string.edit_channels_group_by_sources)))
        var selectGroupItem: SelectGroupItem? = null
        var inputId: String? = null
        for (channel in channels.sortedWith(channelComparator)) {
            if (channel.inputId != inputId) {
                inputId = channel.inputId
                items.add(DividerItem(Utils.loadLabel(requireActivity(), inputManager.getTvInputInfo(inputId))))
                selectGroupItem = SelectGroupItem().also { items.add(it) }
            }
            val channelItem = ChannelItem(channel, selectGroupItem)
            items.add(channelItem)
            selectGroupItem!!.addChannelItem(channelItem)
        }
        cleanUpOneChannelGroupItem(items)
    }

    private fun addItemForGroupByHdSd(items: MutableList<Item>) {
        items.add(GroupBySubMenu(getString(R.string.edit_channels_group_by_hd_sd)))
        var selectGroupItem: SelectGroupItem? = null
        // HD zuerst, dann nach Nummer
        val sorted = channels.sortedWith { lhs, rhs ->
            val lhsHd = isHdChannel(lhs)
            val rhsHd = isHdChannel(rhs)
            if (lhsHd == rhsHd) ChannelNumber.compare(lhs.displayNumber, rhs.displayNumber) else if (lhsHd) -1 else 1
        }
        var isHdGroup: Boolean? = null
        for (channel in sorted) {
            val isHd = isHdChannel(channel)
            if (isHdGroup == null || isHd != isHdGroup) {
                isHdGroup = isHd
                items.add(DividerItem(getString(
                    if (isHd) R.string.edit_channels_group_divider_for_hd else R.string.edit_channels_group_divider_for_sd)))
                selectGroupItem = SelectGroupItem().also { items.add(it) }
            }
            val channelItem = ChannelItem(channel, selectGroupItem)
            items.add(channelItem)
            selectGroupItem!!.addChannelItem(channelItem)
        }
        cleanUpOneChannelGroupItem(items)
    }

    /** "Alle aus-/abwählen" einer Gruppe. */
    private inner class SelectGroupItem : ActionItem(null) {
        val channelItemsInGroup = ArrayList<ChannelItem>()
        private var textView: TextView? = null
        private var allChecked = false

        fun addChannelItem(channelItem: ChannelItem) { channelItemsInGroup.add(channelItem) }

        override fun onBind(view: View) {
            super.onBind(view)
            textView = view.findViewById(R.id.title)
        }

        override fun onUpdate() {
            super.onUpdate()
            allChecked = channelItemsInGroup.all { it.channel.isBrowsable }
            updateText()
        }

        override fun onSelected() {
            for (channelItem in channelItemsInGroup) {
                val channel = channelItem.channel
                if (channel.isBrowsable == allChecked) {
                    channelDataManager.updateBrowsable(channel.id, !allChecked, true)
                    channelItem.notifyUpdated()
                }
            }
            channelDataManager.notifyChannelBrowsableChanged()
            allChecked = !allChecked
            updateText()
        }

        private fun updateText() {
            textView?.text = getString(
                if (allChecked) R.string.edit_channels_item_deselect_group else R.string.edit_channels_item_select_group)
        }
    }

    private inner class ChannelItem(channel: Channel, var selectGroupItem: SelectGroupItem?) :
        ChannelCheckItem(channel, channelDataManager, programDataManager) {

        override fun onUpdate() {
            super.onUpdate()
            setChecked(channel.isBrowsable)
        }

        override fun onSelected() {
            super.onSelected()
            channelDataManager.updateBrowsable(channel.id, isChecked)
            selectGroupItem?.notifyUpdated()
        }

        override fun onFocused() {
            super.onFocused()
            lastFocusedChannelId = channel.id
        }
    }

    /** Auswahl der Gruppierung (Quelle oder HD/SD). */
    class GroupByFragment : SideFragment<Item>() {
        override fun getTitle(): String = getString(R.string.side_panel_title_group_by)

        override fun getItemList(): List<Item> {
            val items = listOf<Item>(
                object : RadioButtonItem(getString(R.string.edit_channels_group_by_sources)) {
                    override fun onSelected() {
                        super.onSelected()
                        setGroupingType(GROUP_BY_SOURCE)
                        closeFragment()
                    }
                },
                object : RadioButtonItem(getString(R.string.edit_channels_group_by_hd_sd)) {
                    override fun onSelected() {
                        super.onSelected()
                        setGroupingType(GROUP_BY_HD_SD)
                        closeFragment()
                    }
                },
            )
            (items[groupingType ?: GROUP_BY_SOURCE] as RadioButtonItem).setChecked(true)
            return items
        }

        private fun setGroupingType(type: Int) {
            groupingType = type
            requireContext().getSharedPreferences(SharedPreferencesUtils.SHARED_PREF_UI_SETTINGS, Context.MODE_PRIVATE)
                .edit().putInt(PREF_KEY_GROUP_SETTINGS, type).apply()
        }
    }

    private inner class GroupBySubMenu(description: String) : SubMenuItem(
        getString(R.string.edit_channels_item_group_by), description, mainActivity.overlayManager.sideFragmentManager,
    ) {
        override fun getFragment(): SideFragment<*> = GroupByFragment()

        override fun onSelected() {
            groupByFragmentRunning = true
            super.onSelected()
        }
    }

    companion object {
        private const val GROUP_BY_SOURCE = 0
        private const val GROUP_BY_HD_SD = 1
        // Schreibfehler "settigns" aus dem Original – Schlüssel muss gleich bleiben
        private const val PREF_KEY_GROUP_SETTINGS = "pref_key_group_settigns"
        private var groupingType: Int? = null

        private fun isHdChannel(channel: Channel): Boolean = channel.videoFormat in setOf(
            Channels.VIDEO_FORMAT_720P, Channels.VIDEO_FORMAT_1080I, Channels.VIDEO_FORMAT_1080P,
            Channels.VIDEO_FORMAT_2160P, Channels.VIDEO_FORMAT_4320P,
        )
    }
}
