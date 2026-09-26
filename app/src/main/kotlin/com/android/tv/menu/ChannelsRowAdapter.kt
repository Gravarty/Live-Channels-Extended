package com.android.tv.menu

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener
import com.android.tv.ChannelChanger
import com.android.tv.R
import com.android.tv.TvSingletons
import com.android.tv.data.ChannelImpl
import com.android.tv.data.ChannelDataManager
import com.android.tv.data.api.Channel
import com.android.tv.dvr.DvrDataManager
import com.android.tv.features.TvFeatures
import com.android.tv.recommendation.Recommender

/** Karten der Kanal-Zeile; mit Bedienungshilfen zusätzlich Kanal hoch/runter. */
class ChannelsRowAdapter(
    private val context: Context,
    private val recommender: Recommender,
    private val minCount: Int,
    private val maxCount: Int,
) : ItemListRowView.ItemListAdapter<ChannelsRowItem>(context), AccessibilityStateChangeListener {

    private val dvrDataManager: DvrDataManager? =
        if (TvFeatures.isDvrEnabled(context)) TvSingletons.getSingletons(context).getDvrDataManager() else null
    private val channelChanger = context as ChannelChanger
    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    private var showChannelUpDown = accessibilityManager.isEnabled

    init {
        setHasStableIds(true)
        accessibilityManager.addAccessibilityStateChangeListener(this)
    }

    override fun getItemViewType(position: Int) = itemList[position].layoutId
    override fun getLayoutResId(viewType: Int) = viewType
    override fun getItemId(position: Int) = itemList[position].itemId

    override fun onBindViewHolder(viewHolder: MyViewHolder, position: Int) {
        val itemView = viewHolder.itemView
        when (getItemViewType(position)) {
            R.layout.menu_card_guide -> itemView.setOnClickListener { mainActivity.overlayManager.showProgramGuide() }
            R.layout.menu_card_up -> itemView.setOnClickListener { channelChanger.channelUp() }
            R.layout.menu_card_down -> itemView.setOnClickListener { channelChanger.channelDown() }
            R.layout.menu_card_setup -> itemView.setOnClickListener { mainActivity.overlayManager.showSetupFragment() }
            R.layout.menu_card_app_link -> itemView.setOnClickListener(::onAppLinkClicked)
            R.layout.menu_card_dvr -> {
                itemView.setOnClickListener { mainActivity.overlayManager.showDvrManager() }
                (itemView as SimpleCardView).setText(R.string.channels_item_dvr)
            }
            else -> {
                itemView.tag = itemList[position].channel
                itemView.setOnClickListener(::onChannelClicked)
            }
        }
        super.onBindViewHolder(viewHolder, position)
    }

    override fun update() {
        if (itemCount == 0) createItems() else updateItems()
    }

    private fun onAppLinkClicked(view: View) {
        (view as AppLinkCardView).intent?.let { mainActivity.startActivitySafe(it) }
    }

    private fun onChannelClicked(view: View) {
        mainActivity.tuneToChannel(view.tag as Channel?)
        mainActivity.hideOverlaysForTune()
    }

    private fun createItems() {
        val items = ArrayList<ChannelsRowItem>()
        items.add(ChannelsRowItem.GUIDE_ITEM)
        if (showChannelUpDown) {
            items.add(ChannelsRowItem.UP_ITEM)
            items.add(ChannelsRowItem.DOWN_ITEM)
        }
        if (needToShowSetupItem()) items.add(ChannelsRowItem.SETUP_ITEM)
        if (needToShowDvrItem()) items.add(ChannelsRowItem.DVR_ITEM)
        if (needToShowAppLinkItem()) {
            ChannelsRowItem.APP_LINK_ITEM.channel = ChannelImpl.Builder(mainActivity.currentChannel!!).build()
            items.add(ChannelsRowItem.APP_LINK_ITEM)
        }
        getRecentChannels().forEach { items.add(ChannelsRowItem(it, R.layout.menu_card_channel)) }
        setItemList(items)
    }

    /** Feste Karten ein-/ausfügen und Kanäle neu setzen (mit gezielten notify-Aufrufen). */
    private fun updateItems() {
        val items = itemList
        var currentIndex = 1
        if (updateItem(showChannelUpDown, ChannelsRowItem.UP_ITEM, currentIndex)) ++currentIndex
        if (updateItem(showChannelUpDown, ChannelsRowItem.DOWN_ITEM, currentIndex)) ++currentIndex
        if (updateItem(needToShowSetupItem(), ChannelsRowItem.SETUP_ITEM, currentIndex)) ++currentIndex
        if (updateItem(needToShowDvrItem(), ChannelsRowItem.DVR_ITEM, currentIndex)) ++currentIndex
        if (updateItem(needToShowAppLinkItem(), ChannelsRowItem.APP_LINK_ITEM, currentIndex)) {
            val current = mainActivity.currentChannel!!
            if (!current.hasSameReadOnlyInfo(ChannelsRowItem.APP_LINK_ITEM.channel)) {
                ChannelsRowItem.APP_LINK_ITEM.channel = ChannelImpl.Builder(current).build()
                notifyItemChanged(currentIndex)
            }
            ++currentIndex
        }
        val numOldChannels = items.size - currentIndex
        if (numOldChannels > 0) {
            while (items.size > currentIndex) items.removeAt(items.size - 1)
            notifyItemRangeRemoved(currentIndex, numOldChannels)
        }
        getRecentChannels().forEach { items.add(ChannelsRowItem(it, R.layout.menu_card_channel)) }
        val numNewChannels = items.size - currentIndex
        if (numNewChannels > 0) notifyItemRangeInserted(currentIndex, numNewChannels)
    }

    /** Liefert [needToShow]; fügt die Karte an [index] ein bzw. entfernt sie. */
    private fun updateItem(needToShow: Boolean, item: ChannelsRowItem, index: Int): Boolean {
        val items = itemList
        val isItemInList = index < items.size && item == items[index]
        if (needToShow && !isItemInList) {
            items.add(index, item)
            notifyItemInserted(index)
        } else if (!needToShow && isItemInList) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
        return needToShow
    }

    private fun needToShowSetupItem(): Boolean {
        val singletons = TvSingletons.getSingletons(context)
        return singletons.getSetupUtils().hasNewInput(singletons.getTvInputManagerHelper())
    }

    /** DVR-Karte nur mit DVR und aufnahmefähigem Input. */
    private fun needToShowDvrItem(): Boolean =
        dvrDataManager != null &&
            TvSingletons.getSingletons(context).getTvInputManagerHelper().getTvInputInfos(true, true).any { it.canRecord() }

    private fun needToShowAppLinkItem(): Boolean {
        val current = mainActivity.currentChannel ?: return false
        return current.getAppLinkType(context) != Channel.APP_LINK_TYPE_NONE &&
            TvSingletons.getSingletons(context).getTvInputManagerHelper().getTvInputAppInfo(current.inputId) != null
    }

    /** Zuletzt gesehener Kanal, dann Empfehlungen, aufgefüllt mit weiteren zuletzt gesehenen. */
    private fun getRecentChannels(): List<Channel> {
        val channelList = ArrayList<Channel>()
        val currentChannelId = mainActivity.currentChannelId
        val recentChannels = mainActivity.getRecentChannels()
        val channelDataManager = mainActivity.channelDataManager
        for (channelId in recentChannels) {
            if (addChannelToList(channelList, recommender.getChannel(channelId), currentChannelId, channelDataManager)) break
        }
        for (channel in recommender.recommendChannels(maxCount)) {
            if (channelList.size >= maxCount) break
            addChannelToList(channelList, channel, currentChannelId, channelDataManager)
        }
        for (channelId in recentChannels) {
            if (channelList.size >= minCount) break
            addChannelToList(channelList, recommender.getChannel(channelId), currentChannelId, channelDataManager)
        }
        return channelList
    }

    override fun onAccessibilityStateChanged(enabled: Boolean) {
        showChannelUpDown = enabled
        update()
    }

    override fun release() {
        accessibilityManager.removeAccessibilityStateChangeListener(this)
        super.release()
    }

    companion object {
        // Extended: Quelle – nur Kanäle der gewählten Quelle (isVisible statt isBrowsable)
        private fun addChannelToList(channelList: MutableList<Channel>, channel: Channel?, currentChannelId: Long,
            channelDataManager: ChannelDataManager): Boolean {
            if (channel == null || channel.id == currentChannelId || channel in channelList ||
                !channelDataManager.isVisible(channel)) return false
            channelList.add(channel)
            return true
        }
    }
}
