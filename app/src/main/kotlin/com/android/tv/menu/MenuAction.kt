package com.android.tv.menu

import android.content.Context
import android.graphics.drawable.Drawable
import com.android.tv.R
import com.android.tv.TvOptionsManager

/** Eine Aktion in der Optionen-Zeile (Titel, Beschreibung, Symbol, aktiv). */
class MenuAction private constructor(
    private val actionName: String?,
    val actionNameResId: Int,
    val type: Int,
    private var drawable: Drawable?,
    private val drawableResId: Int,
) {
    constructor(actionNameResId: Int, type: Int, drawableResId: Int) : this(null, actionNameResId, type, null, drawableResId)
    constructor(actionName: String, type: Int, drawable: Drawable?) : this(actionName, 0, type, drawable, 0)

    var actionDescription: String? = null
        private set
    var isEnabled = true
        private set

    fun getActionName(context: Context): String =
        if (!actionName.isNullOrEmpty()) actionName else context.getString(actionNameResId)

    fun getDrawable(context: Context): Drawable? =
        drawable ?: context.getDrawable(drawableResId).also { drawable = it }

    companion object {
        @JvmField val SELECT_CLOSED_CAPTION_ACTION =
            MenuAction(R.string.options_item_closed_caption, TvOptionsManager.OPTION_CLOSED_CAPTIONS, R.drawable.ic_tvoption_cc)
        @JvmField val SELECT_DISPLAY_MODE_ACTION =
            MenuAction(R.string.options_item_display_mode, TvOptionsManager.OPTION_DISPLAY_MODE, R.drawable.ic_tvoption_aspect)
        @JvmField val SYSTEMWIDE_PIP_ACTION =
            MenuAction(R.string.options_item_pip, TvOptionsManager.OPTION_SYSTEMWIDE_PIP, R.drawable.ic_tvoption_pip)
        @JvmField val SELECT_AUDIO_LANGUAGE_ACTION =
            MenuAction(R.string.options_item_multi_audio, TvOptionsManager.OPTION_MULTI_AUDIO, R.drawable.ic_tvoption_multi_track)
        @JvmField val MORE_CHANNELS_ACTION =
            MenuAction(R.string.options_item_more_channels, TvOptionsManager.OPTION_MORE_CHANNELS, R.drawable.ic_app_store)
        // Extended: Quelle
        @JvmField val SOURCE_ACTION =
            MenuAction(R.string.options_item_source, TvOptionsManager.OPTION_SOURCE, R.drawable.ic_tvoption_source)
        @JvmField val SETTINGS_ACTION =
            MenuAction(R.string.options_item_settings, TvOptionsManager.OPTION_SETTINGS, R.drawable.ic_settings)

        /** true, wenn sich die Beschreibung geändert hat. */
        @JvmStatic
        fun setActionDescription(action: MenuAction, actionDescription: String?): Boolean {
            val old = action.actionDescription
            action.actionDescription = actionDescription
            return actionDescription != old
        }

        @JvmStatic
        fun setEnabled(action: MenuAction, enabled: Boolean): Boolean {
            val changed = action.isEnabled != enabled
            action.isEnabled = enabled
            return changed
        }
    }
}
