package com.android.tv.util

import android.content.Context
import android.media.tv.TvTrackInfo
import androidx.preference.PreferenceManager

/**
 * App-Einstellungen in den Standard-SharedPreferences.
 * Entfällt: Altersfreigabe-Systeme/-Stufen (Kindersicherung, nur System-App).
 */
object TvSettings {
    const val PREF_DISPLAY_MODE = "display_mode" // int
    const val PREF_PIN = "pin" // 4-stellig, sonst nicht gesetzt

    private const val PREF_MULTI_AUDIO_ID = "pref.multi_audio_id"
    private const val PREF_MULTI_AUDIO_LANGUAGE = "pref.multi_audio_language"
    private const val PREF_MULTI_AUDIO_CHANNEL_COUNT = "pref.multi_audio_channel_count"
    private const val PREF_DVR_MULTI_AUDIO_ID = "pref.dvr_multi_audio_id"
    private const val PREF_DVR_MULTI_AUDIO_LANGUAGE = "pref.dvr_multi_audio_language"
    private const val PREF_DVR_MULTI_AUDIO_CHANNEL_COUNT = "pref.dvr_multi_audio_channel_count"
    private const val PREF_DVR_SUBTITLE_ID = "pref.dvr_subtitle_id"
    private const val PREF_DVR_SUBTITLE_LANGUAGE = "pref.dvr_subtitle_language"
    private const val PREF_DISABLE_PIN_UNTIL = "pref.disable_pin_until"

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    @JvmStatic fun getMultiAudioId(context: Context): String? = prefs(context).getString(PREF_MULTI_AUDIO_ID, null)
    @JvmStatic fun setMultiAudioId(context: Context, id: String?) = prefs(context).edit().putString(PREF_MULTI_AUDIO_ID, id).apply()
    @JvmStatic fun getMultiAudioLanguage(context: Context): String? = prefs(context).getString(PREF_MULTI_AUDIO_LANGUAGE, null)
    @JvmStatic fun setMultiAudioLanguage(context: Context, language: String?) =
        prefs(context).edit().putString(PREF_MULTI_AUDIO_LANGUAGE, language).apply()
    @JvmStatic fun getMultiAudioChannelCount(context: Context): Int = prefs(context).getInt(PREF_MULTI_AUDIO_CHANNEL_COUNT, 0)
    @JvmStatic fun setMultiAudioChannelCount(context: Context, channelCount: Int) =
        prefs(context).edit().putInt(PREF_MULTI_AUDIO_CHANNEL_COUNT, channelCount).apply()

    /** Ton-/Untertitelwahl für DVR-Wiedergabe speichern (null = zurücksetzen). */
    @JvmStatic
    fun setDvrPlaybackTrackSettings(context: Context, trackType: Int, info: TvTrackInfo?) {
        val editor = prefs(context).edit()
        when (trackType) {
            TvTrackInfo.TYPE_AUDIO -> if (info == null) editor.remove(PREF_DVR_MULTI_AUDIO_ID) else editor
                .putString(PREF_DVR_MULTI_AUDIO_LANGUAGE, info.language)
                .putInt(PREF_DVR_MULTI_AUDIO_CHANNEL_COUNT, info.audioChannelCount)
                .putString(PREF_DVR_MULTI_AUDIO_ID, info.id)
            TvTrackInfo.TYPE_SUBTITLE -> if (info == null) editor.remove(PREF_DVR_SUBTITLE_ID) else editor
                .putString(PREF_DVR_SUBTITLE_LANGUAGE, info.language)
                .putString(PREF_DVR_SUBTITLE_ID, info.id)
            else -> return
        }
        editor.apply()
    }

    @JvmStatic
    fun getDvrPlaybackTrackSettings(context: Context, trackType: Int): TvTrackInfo? {
        val pref = prefs(context)
        return when (trackType) {
            TvTrackInfo.TYPE_AUDIO -> {
                val trackId = pref.getString(PREF_DVR_MULTI_AUDIO_ID, null) ?: return null
                TvTrackInfo.Builder(trackType, trackId)
                    .setAudioChannelCount(pref.getInt(PREF_DVR_MULTI_AUDIO_CHANNEL_COUNT, 0))
                    // Abweichung: setLanguage ist @NonNull, daher nur setzen, wenn gespeichert.
                    .apply { pref.getString(PREF_DVR_MULTI_AUDIO_LANGUAGE, null)?.let { setLanguage(it) } }
                    .build()
            }
            TvTrackInfo.TYPE_SUBTITLE -> {
                val trackId = pref.getString(PREF_DVR_SUBTITLE_ID, null) ?: return null
                TvTrackInfo.Builder(trackType, trackId)
                    .apply { pref.getString(PREF_DVR_SUBTITLE_LANGUAGE, null)?.let { setLanguage(it) } }.build()
            }
            else -> null
        }
    }

    @JvmStatic fun getDisablePinUntil(context: Context): Long = prefs(context).getLong(PREF_DISABLE_PIN_UNTIL, 0)
    @JvmStatic fun setDisablePinUntil(context: Context, timeMillis: Long) =
        prefs(context).edit().putLong(PREF_DISABLE_PIN_UNTIL, timeMillis).apply()

}
