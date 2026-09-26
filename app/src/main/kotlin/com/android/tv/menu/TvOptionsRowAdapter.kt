package com.android.tv.menu

import android.content.Context
import android.media.tv.TvTrackInfo
import com.android.tv.TvOptionsManager
import com.android.tv.common.customization.CustomAction
import com.android.tv.data.DisplayMode
import com.android.tv.features.TvFeatures
import com.android.tv.ui.sidepanel.ClosedCaptionFragment
import com.android.tv.ui.sidepanel.DisplayModeFragment
import com.android.tv.ui.sidepanel.MultiAudioFragment
import com.android.tv.ui.sidepanel.SourceFragment
import com.android.tv.util.OnboardingUtils
import javax.inject.Inject

/**
 * Optionen-Zeile: Untertitel, Bildformat, Bild-in-Bild, Tonspur, (Weitere Kanäle), Einstellungen.
 * Entfällt: Entwickleroptionen (nur ENG-Build/Entwicklerfunktionen).
 */
class TvOptionsRowAdapter(context: Context, customActions: List<CustomAction>?) :
    CustomizableOptionsRowAdapter(context, customActions) {

    /** Ersatz für die AutoFactory des Originals. */
    class Factory @Inject constructor() {
        fun create(context: Context, customActions: List<CustomAction>?) = TvOptionsRowAdapter(context, customActions)
    }

    override fun createBaseActions(): List<MenuAction> {
        val actionList = ArrayList<MenuAction>()
        actionList.add(MenuAction.SELECT_CLOSED_CAPTION_ACTION)
        actionList.add(MenuAction.SELECT_DISPLAY_MODE_ACTION)
        if (TvFeatures.isPictureInPictureEnabled(mainActivity)) actionList.add(MenuAction.SYSTEMWIDE_PIP_ACTION)
        actionList.add(MenuAction.SELECT_AUDIO_LANGUAGE_ACTION)
        // "Weitere Kanäle" nur mit Store-URL (im AOSP-Build leer)
        if (OnboardingUtils.createOnlineStoreIntent() != null) actionList.add(MenuAction.MORE_CHANNELS_ACTION)
        // Extended: Quelle direkt vor Einstellungen
        actionList.add(MenuAction.SOURCE_ACTION)
        actionList.add(MenuAction.SETTINGS_ACTION)
        updateClosedCaptionAction()
        updatePipAction()
        updateMultiAudioAction()
        updateDisplayModeAction()
        updateSourceAction()
        return actionList
    }

    override fun updateActions() {
        if (updateClosedCaptionAction()) notifyItemChanged(getItemPosition(MenuAction.SELECT_CLOSED_CAPTION_ACTION))
        if (updatePipAction()) notifyItemChanged(getItemPosition(MenuAction.SYSTEMWIDE_PIP_ACTION))
        if (updateMultiAudioAction()) notifyItemChanged(getItemPosition(MenuAction.SELECT_AUDIO_LANGUAGE_ACTION))
        if (updateDisplayModeAction()) notifyItemChanged(getItemPosition(MenuAction.SELECT_DISPLAY_MODE_ACTION))
        if (updateSourceAction()) notifyItemChanged(getItemPosition(MenuAction.SOURCE_ACTION))
    }

    private fun updateClosedCaptionAction() = updateActionDescription(MenuAction.SELECT_CLOSED_CAPTION_ACTION)

    private fun updatePipAction(): Boolean =
        containsItem(MenuAction.SYSTEMWIDE_PIP_ACTION) &&
            MenuAction.setEnabled(MenuAction.SYSTEMWIDE_PIP_ACTION, !mainActivity.isScreenBlockedByResourceConflictOrParentalControl())

    /** Tonspur-Auswahl nur bei mehr als einer Spur. */
    private fun updateMultiAudioAction(): Boolean {
        val audioTracks = mainActivity.getTracks(TvTrackInfo.TYPE_AUDIO)
        val enabled = audioTracks != null && audioTracks.size > 1
        return MenuAction.setEnabled(MenuAction.SELECT_AUDIO_LANGUAGE_ACTION, enabled) or
            updateActionDescription(MenuAction.SELECT_AUDIO_LANGUAGE_ACTION)
    }

    /** Bildformat nur, wenn Voll oder Zoom möglich ist. */
    private fun updateDisplayModeAction(): Boolean {
        val uiManager = mainActivity.tvViewUiManager
        val enabled = uiManager.isDisplayModeAvailable(DisplayMode.MODE_FULL) || uiManager.isDisplayModeAvailable(DisplayMode.MODE_ZOOM)
        return MenuAction.setEnabled(MenuAction.SELECT_DISPLAY_MODE_ACTION, enabled) or
            updateActionDescription(MenuAction.SELECT_DISPLAY_MODE_ACTION)
    }

    // Extended: Quelle – Beschreibung = gewählte Quelle
    private fun updateSourceAction(): Boolean = updateActionDescription(MenuAction.SOURCE_ACTION)

    private fun updateActionDescription(action: MenuAction): Boolean =
        MenuAction.setActionDescription(action, mainActivity.tvOptionsManager.getOptionString(action.type))

    override fun executeBaseAction(type: Int) {
        val sideFragmentManager = mainActivity.overlayManager.sideFragmentManager
        when (type) {
            TvOptionsManager.OPTION_CLOSED_CAPTIONS -> sideFragmentManager.show(ClosedCaptionFragment())
            TvOptionsManager.OPTION_DISPLAY_MODE -> sideFragmentManager.show(DisplayModeFragment())
            TvOptionsManager.OPTION_SYSTEMWIDE_PIP -> mainActivity.enterPictureInPictureMode()
            TvOptionsManager.OPTION_MULTI_AUDIO -> sideFragmentManager.show(MultiAudioFragment())
            TvOptionsManager.OPTION_MORE_CHANNELS -> mainActivity.showMerchantCollection()
            TvOptionsManager.OPTION_SETTINGS -> mainActivity.showSettingsFragment()
            // Extended: Quelle
            TvOptionsManager.OPTION_SOURCE -> sideFragmentManager.show(SourceFragment())
        }
    }
}
