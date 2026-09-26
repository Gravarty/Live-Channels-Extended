package com.android.tv.ui.sidepanel

import com.android.tv.R
import com.android.tv.util.Utils

/**
 * Extended: Quelle. Listet alle Tuner/Services mit sichtbaren Kanälen. Nach der Auswahl zeigen Kanalwechsel,
 * Programmübersicht, "Kanäle"-Zeile und Kanalliste nur noch die Kanäle dieser Quelle.
 */
class SourceFragment : SideFragment<Item>() {

    override fun getTitle(): String = getString(R.string.side_panel_title_source)

    override fun getItemList(): List<Item> {
        val channelDataManager = mainActivity.channelDataManager
        val inputManager = mainActivity.tvInputManagerHelper
        return channelDataManager.getSourceInputIds().map { inputId ->
            val label = Utils.loadLabel(requireContext(), inputManager.getTvInputInfo(inputId)) ?: inputId
            val count = channelDataManager.getBrowsableChannelCountForInput(inputId)
            SourceItem(inputId, label, resources.getQuantityString(R.plurals.source_channel_count, count, count))
        }
    }

    private inner class SourceItem(private val inputId: String, title: String, description: String) :
        RadioButtonItem(title, description) {

        override fun onUpdate() {
            super.onUpdate()
            setChecked(inputId == mainActivity.channelDataManager.selectedSourceInputId)
        }

        override fun onSelected() {
            super.onSelected()
            switchSource(inputId)
            closeFragment()
        }
    }

    /** Wechselt die Quelle und tunt ihren zuletzt gesehenen Kanal (sonst den ersten). */
    private fun switchSource(inputId: String) {
        val channelDataManager = mainActivity.channelDataManager
        if (inputId == channelDataManager.selectedSourceInputId) return
        channelDataManager.selectSource(inputId)
        if (channelDataManager.selectedSourceInputId != inputId) return // Quelle hat keine sichtbaren Kanäle mehr
        val lastChannel = channelDataManager.getChannel(channelDataManager.getLastChannelIdForSource(inputId))
            ?.takeIf { channelDataManager.isVisible(it) }
        val target = lastChannel ?: channelDataManager.getBrowsableChannelList().firstOrNull() ?: return
        mainActivity.tuneToChannel(target)
    }
}
