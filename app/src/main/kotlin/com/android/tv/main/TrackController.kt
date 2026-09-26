package com.android.tv.main

import android.hardware.display.DisplayManager
import android.media.tv.TvTrackInfo
import android.util.Log
import android.view.Display
import com.android.tv.MainActivity
import com.android.tv.TvOptionsManager
import com.android.tv.ui.TunableTvView
import com.android.tv.util.CaptionSettings
import com.android.tv.util.TvSettings
import com.android.tv.util.TvTrackInfoUtils
import com.android.tv.util.Utils
import kotlin.math.abs

/**
 * Aus MainActivity ausgelagert: Tonspur- und Untertitelwahl sowie Film-Modus
 * (Bildwiederholrate 23,976 Hz bei 24p-Material). Logik 1:1.
 */
class TrackController(
    private val activity: MainActivity,
    private val tvView: TunableTvView,
    private val optionsManager: TvOptionsManager,
) {
    private val defaultRefreshRate: Float =
        activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).refreshRate
    private var isFilmModeSet = false

    private val captionSettings: CaptionSettings get() = activity.captionSettings!!

    fun getTracks(type: Int): List<TvTrackInfo>? = tvView.getTracks(type)
    fun getSelectedTrack(type: Int): String? = tvView.getSelectedTrack(type)

    /** Schaltet bei 24p auf eine passende Bildwiederholrate um und zurück. */
    fun applyDisplayRefreshRate(videoFrameRate: Float) {
        val is24Fps = abs(videoFrameRate - FRAME_RATE_FOR_FILM) < FRAME_RATE_EPSILON
        if (isFilmModeSet && !is24Fps) {
            setPreferredRefreshRate(defaultRefreshRate)
            isFilmModeSet = false
        } else if (!isFilmModeSet && is24Fps) {
            val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            for (refreshRate in display.supportedRefreshRates) {
                if (abs(videoFrameRate - refreshRate) < REFRESH_RATE_EPSILON) {
                    setPreferredRefreshRate(refreshRate)
                    isFilmModeSet = true
                    return
                }
            }
        }
    }

    private fun setPreferredRefreshRate(refreshRate: Float) {
        val window = activity.window
        window.attributes = window.attributes.apply { preferredRefreshRate = refreshRate }
    }

    /** Wählt die Tonspur: explizite ID, sonst gespeicherte Vorliebe (ID/Sprache/Kanäle). */
    @JvmOverloads
    fun applyMultiAudio(allowAutoSelection: Boolean = false, trackId: String?) {
        if (!allowAutoSelection && trackId == null) {
            // Bugfix: Nur abwählen, wenn wirklich eine Tonspur aktiv ist. Nach einer Stream-Info-Meldung ohne
            // gewählte Tonspur (z. B. Untertitel-Meldung direkt nach dem Tunen) schickte das Original ein
            // überflüssiges "Tonspur abwählen". Der MediaTek-Tuner (JVC/Vestel) stürzt dabei ab
            // (SIGSEGV in a_mtktvapi_select_audio via unselectAudio, per Log belegt), v. a. nachdem der
            // TV-eigene Player den Tuner benutzt hat.
            if (getSelectedTrack(TvTrackInfo.TYPE_AUDIO) != null) {
                selectTrack(TvTrackInfo.TYPE_AUDIO, null, UNDEFINED_TRACK_INDEX)
            }
            optionsManager.onMultiAudioChanged(null)
            return
        }
        val tracks = getTracks(TvTrackInfo.TYPE_AUDIO)
        if (tracks == null) {
            optionsManager.onMultiAudioChanged(null)
            return
        }
        // Bugfix: Beim MediaTek-Tuner (JVC/Vestel) hängt die gemeldete Kanalzahl/Sprache davon ab, welche
        // Spur gerade aktiv ist, und die Spur-IDs ändern sich bei jedem Tune (per Log belegt: 513/514 →
        // 553/554). Die automatische Wahl bei jeder Spurmeldung sprang dadurch endlos zwischen zwei Spuren.
        // Automatisch gewählt wird daher nur einmal pro Spurliste; bleibt die Liste gleich, bleibt die Spur.
        val audioTrackIds = tracks.map { it.id }
        if (allowAutoSelection && trackId == null && audioTrackIds == lastAudioTrackIds &&
            getSelectedTrack(TvTrackInfo.TYPE_AUDIO) in audioTrackIds
        ) {
            val selected = tracks.first { it.id == getSelectedTrack(TvTrackInfo.TYPE_AUDIO) }
            optionsManager.onMultiAudioChanged(TvTrackInfoUtils.getMultiAudioString(activity, selected, false))
            return
        }
        lastAudioTrackIds = audioTrackIds
        var bestTrack = if (trackId != null) tracks.firstOrNull { it.id == trackId } else null
        if (bestTrack == null) {
            bestTrack = TvTrackInfoUtils.getBestTrackInfo(
                tracks,
                TvSettings.getMultiAudioId(activity),
                TvSettings.getMultiAudioLanguage(activity),
                TvSettings.getMultiAudioChannelCount(activity),
            )
        }
        if (bestTrack != null) {
            if (bestTrack.id != getSelectedTrack(TvTrackInfo.TYPE_AUDIO)) {
                selectTrack(TvTrackInfo.TYPE_AUDIO, bestTrack, UNDEFINED_TRACK_INDEX)
            } else {
                optionsManager.onMultiAudioChanged(TvTrackInfoUtils.getMultiAudioString(activity, bestTrack, false))
            }
            return
        }
        optionsManager.onMultiAudioChanged(null)
    }

    /** Untertitel gemäß CaptionSettings an/aus und beste Spur wählen. */
    fun applyClosedCaption() {
        val tracks = getTracks(TvTrackInfo.TYPE_SUBTITLE)
        if (tracks == null) {
            optionsManager.onClosedCaptionsChanged(null, UNDEFINED_TRACK_INDEX)
            return
        }
        val enabled = captionSettings.isEnabled
        tvView.setClosedCaptionEnabled(enabled)
        // Bugfix: Der MediaTek-Tuner (JVC/Vestel) meldet "keine Untertitel" als Spur-ID "255", die in der
        // Spurliste nicht vorkommt. Das Original hielt das für eine gewählte Spur und schickte immer wieder
        // selectTrack(SUBTITLE, null); der Tuner antwortete jedes Mal mit neuen Spurmeldungen → Endlosschleife
        // (per Log belegt, mehrere Aufrufe pro Sekunde, Ton stottert). Nur IDs aus der Spurliste zählen.
        val selectedTrackId = getSelectedTrack(TvTrackInfo.TYPE_SUBTITLE)?.takeIf { id -> tracks.any { it.id == id } }
        if (enabled) {
            val bestTrackIndex = findBestCaptionTrackIndex(
                tracks, captionSettings.language, captionSettings.systemPreferenceLanguageList, captionSettings.trackId)
            if (bestTrackIndex != UNDEFINED_TRACK_INDEX) {
                selectCaptionTrack(selectedTrackId, tracks[bestTrackIndex], bestTrackIndex)
                return
            }
        }
        deselectCaptionTrack(selectedTrackId)
    }

    /** Spur-IDs, für die zuletzt eine Tonspur gewählt wurde (siehe Bugfix in applyMultiAudio). */
    private var lastAudioTrackIds: List<String>? = null

    private fun selectTrack(type: Int, track: TvTrackInfo?, trackIndex: Int) {
        tvView.selectTrack(type, track?.id)
        if (type == TvTrackInfo.TYPE_AUDIO) {
            optionsManager.onMultiAudioChanged(track?.let { TvTrackInfoUtils.getMultiAudioString(activity, it, false) })
        } else if (type == TvTrackInfo.TYPE_SUBTITLE) {
            optionsManager.onClosedCaptionsChanged(track, trackIndex)
        }
    }

    private fun selectCaptionTrack(selectedTrackId: String?, track: TvTrackInfo, trackIndex: Int) {
        if (track.id != selectedTrackId) {
            selectTrack(TvTrackInfo.TYPE_SUBTITLE, track, trackIndex)
        } else {
            optionsManager.onClosedCaptionsChanged(track, trackIndex) // schon ausgewählt
        }
        if (DEBUG) Log.d(TAG, "Subtitle Track Selected {id=${track.id}, language=${track.language}}")
    }

    private fun deselectCaptionTrack(selectedTrackId: String?) {
        if (selectedTrackId != null) selectTrack(TvTrackInfo.TYPE_SUBTITLE, null, UNDEFINED_TRACK_INDEX)
        else optionsManager.onClosedCaptionsChanged(null, UNDEFINED_TRACK_INDEX)
    }

    fun selectAudioTrack(trackId: String?) {
        saveMultiAudioSetting(trackId)
        applyMultiAudio(false, trackId)
    }

    private fun saveMultiAudioSetting(trackId: String?) {
        val track = getTracks(TvTrackInfo.TYPE_AUDIO)?.firstOrNull { it.id == trackId }
        TvSettings.setMultiAudioId(activity, track?.id)
        TvSettings.setMultiAudioLanguage(activity, track?.language)
        TvSettings.setMultiAudioChannelCount(activity, track?.audioChannelCount ?: 0)
    }

    fun selectSubtitleTrack(option: Int, trackId: String?) {
        saveClosedCaptionSetting(option, trackId)
        applyClosedCaption()
    }

    fun selectSubtitleLanguage(option: Int, language: String?, trackId: String?) {
        captionSettings.enableOption = option
        captionSettings.language = language
        captionSettings.trackId = trackId
        applyClosedCaption()
    }

    private fun saveClosedCaptionSetting(option: Int, trackId: String?) {
        captionSettings.enableOption = option
        if (option != CaptionSettings.OPTION_ON) return
        getTracks(TvTrackInfo.TYPE_SUBTITLE)?.firstOrNull { it.id == trackId }?.let {
            captionSettings.language = it.language
            captionSettings.trackId = trackId
        }
    }

    companion object {
        private const val TAG = "TrackController"
        private const val DEBUG = false
        private const val FRAME_RATE_FOR_FILM = 23.976f
        private const val FRAME_RATE_EPSILON = 0.1f
        private const val REFRESH_RATE_EPSILON = 0.01f
        const val UNDEFINED_TRACK_INDEX = -1
        private const val HIGHEST_PRIORITY = -1

        /**
         * Beste Untertitelspur: gewählte Spur-ID > gewählte Sprache > Systemsprachen (Reihenfolge)
         * > erste Spur.
         */
        @JvmStatic
        fun findBestCaptionTrackIndex(
            tracks: List<TvTrackInfo>, selectedLanguage: String?, preferredLanguages: List<String>, selectedTrackId: String?,
        ): Int {
            var alternativeTrackIndex = UNDEFINED_TRACK_INDEX
            var alternativeTrackPriority = preferredLanguages.size
            tracks.forEachIndexed { i, track ->
                if (Utils.isEqualLanguage(track.language, selectedLanguage)) {
                    if (track.id == selectedTrackId) return i
                    if (alternativeTrackPriority != HIGHEST_PRIORITY) {
                        alternativeTrackIndex = i
                        alternativeTrackPriority = HIGHEST_PRIORITY
                    }
                } else {
                    val index = preferredLanguages.indexOfFirst { Utils.isEqualLanguage(track.language, it) }
                    if (index != UNDEFINED_TRACK_INDEX && index < alternativeTrackPriority) {
                        alternativeTrackIndex = i
                        alternativeTrackPriority = index
                    } else if (alternativeTrackIndex == UNDEFINED_TRACK_INDEX) {
                        alternativeTrackIndex = i
                    }
                }
            }
            return alternativeTrackIndex
        }
    }
}
