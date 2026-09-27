package com.android.tv.menu

import com.android.tv.R
import com.android.tv.data.api.Channel

/** Eintrag der Kanal-Zeile: Kanal oder feste Karte (Guide, Setup, DVR, App-Link, Pfeile). */
class ChannelsRowItem private constructor(val itemId: Long, val layoutId: Int) {
    var channel: Channel? = null

    constructor(channel: Channel, layoutId: Int) : this(channel.id, layoutId) {
        this.channel = channel
    }

    override fun toString() = "ChannelsRowItem{itemId=$itemId, layoutId=$layoutId, channel=$channel}"

    companion object {
        const val GUIDE_ITEM_ID = -1L
        const val SETUP_ITEM_ID = -2L
        const val DVR_ITEM_ID = -3L
        const val APP_LINK_ITEM_ID = -4L
        const val UP_ID = -5L
        const val DOWN_ID = -6L
        // Tweak: HTS-DVR – Karte "Zeitplan"
        const val HTS_TIMERS_ITEM_ID = -7L

        @JvmField val GUIDE_ITEM = ChannelsRowItem(GUIDE_ITEM_ID, R.layout.menu_card_guide)
        @JvmField val SETUP_ITEM = ChannelsRowItem(SETUP_ITEM_ID, R.layout.menu_card_setup)
        @JvmField val DVR_ITEM = ChannelsRowItem(DVR_ITEM_ID, R.layout.menu_card_dvr)
        @JvmField val APP_LINK_ITEM = ChannelsRowItem(APP_LINK_ITEM_ID, R.layout.menu_card_app_link)
        @JvmField val UP_ITEM = ChannelsRowItem(UP_ID, R.layout.menu_card_up)
        @JvmField val DOWN_ITEM = ChannelsRowItem(DOWN_ID, R.layout.menu_card_down)
        @JvmField val HTS_TIMERS_ITEM = ChannelsRowItem(HTS_TIMERS_ITEM_ID, R.layout.menu_card_hts_timers)
    }
}
