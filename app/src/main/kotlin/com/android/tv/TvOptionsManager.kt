package com.android.tv

import android.content.Context
import android.media.tv.TvTrackInfo
import android.util.SparseArray
import com.android.tv.data.DisplayMode
import java.util.Locale

/** Hält die aktuellen Werte der Menü-Optionen (Untertitel, Bildformat, Tonspur). */
class TvOptionsManager(private val context: Context) {

    fun interface OptionChangedListener {
        fun onOptionChanged(optionType: Int, newString: String?)
    }

    private val optionChangedListeners = SparseArray<OptionChangedListener>()
    private var closedCaptionsLanguage: String? = null
    private var displayMode = 0
    private var multiAudio: String? = null

    fun getOptionString(option: Int): String? = when (option) {
        OPTION_CLOSED_CAPTIONS -> closedCaptionsLanguage?.let { Locale(it).displayName }
            ?: context.getString(R.string.closed_caption_option_item_off)
        OPTION_DISPLAY_MODE -> {
            val available = (context as MainActivity).tvViewUiManager.isDisplayModeAvailable(displayMode)
            DisplayMode.getLabel(if (available) displayMode else DisplayMode.MODE_NORMAL, context)
        }
        OPTION_MULTI_AUDIO -> multiAudio
        // Extended: Quelle – Name der gewählten Quelle
        OPTION_SOURCE -> (context as MainActivity).let { activity ->
            activity.channelDataManager.selectedSourceInputId?.let { inputId ->
                com.android.tv.util.Utils.loadLabel(activity, activity.tvInputManagerHelper.getTvInputInfo(inputId))
            }
        }
        else -> ""
    }

    /** Sprache der gewählten Untertitelspur, sonst "Unbekannt (n)". */
    fun onClosedCaptionsChanged(track: TvTrackInfo?, trackIndex: Int) {
        closedCaptionsLanguage = when {
            track == null -> null
            track.language != null -> track.language
            else -> context.getString(R.string.closed_caption_unknown_language, trackIndex + 1)
        }
        notifyOptionChanged(OPTION_CLOSED_CAPTIONS)
    }

    fun onDisplayModeChanged(displayMode: Int) {
        this.displayMode = displayMode
        notifyOptionChanged(OPTION_DISPLAY_MODE)
    }

    fun onMultiAudioChanged(multiAudio: String?) {
        this.multiAudio = multiAudio
        notifyOptionChanged(OPTION_MULTI_AUDIO)
    }

    private fun notifyOptionChanged(option: Int) {
        optionChangedListeners.get(option)?.onOptionChanged(option, getOptionString(option))
    }

    fun setOptionChangedListener(option: Int, listener: OptionChangedListener?) {
        optionChangedListeners.put(option, listener)
    }

    companion object {
        const val OPTION_CLOSED_CAPTIONS = 0
        const val OPTION_DISPLAY_MODE = 1
        const val OPTION_SYSTEMWIDE_PIP = 2
        const val OPTION_MULTI_AUDIO = 3
        const val OPTION_MORE_CHANNELS = 4
        const val OPTION_DEVELOPER = 5
        const val OPTION_SETTINGS = 6
        // Extended: Quelle
        const val OPTION_SOURCE = 7
    }
}
