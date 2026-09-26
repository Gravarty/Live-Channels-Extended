package com.android.tv.audio

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.android.tv.features.TvFeatures
import com.android.tv.ui.api.TunableTvViewPlayingApi

/** Audio-Fokus: Lautstärke bzw. Timeshift-Pause abhängig vom Fokus (Ducking = 30 %). */
class AudioManagerHelper(
    private val activity: Activity,
    private val tvView: TunableTvViewPlayingApi,
) : AudioManager.OnAudioFocusChangeListener {

    private val audioManager = activity.getSystemService(AudioManager::class.java)
    private var audioFocusStatus = AudioManager.AUDIOFOCUS_NONE
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build())
        .setOnAudioFocusChangeListener(this)
        // Pausieren statt leiser stellen, wenn Timeshift verfügbar ist (siehe unten)
        .setWillPauseWhenDucked(true)
        .build()

    /** Stellt Lautstärke/Pause passend zum aktuellen Fokus ein. */
    fun setVolumeByAudioFocusStatus() {
        if (!tvView.isPlaying) return
        when (audioFocusStatus) {
            AudioManager.AUDIOFOCUS_GAIN ->
                if (tvView.isTimeShiftAvailable) tvView.timeShiftPlay() else tvView.setStreamVolume(AUDIO_MAX_VOLUME)
            AudioManager.AUDIOFOCUS_NONE, AudioManager.AUDIOFOCUS_LOSS -> {
                // Bild-in-Bild bei Fokusverlust beenden
                if (TvFeatures.isPictureInPictureEnabled(activity) && activity.isInPictureInPictureMode) {
                    activity.finish()
                } else {
                    pauseOrSetVolume(AUDIO_MIN_VOLUME)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseOrSetVolume(AUDIO_MIN_VOLUME)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pauseOrSetVolume(AUDIO_DUCKING_VOLUME)
        }
    }

    private fun pauseOrSetVolume(volume: Float) {
        if (tvView.isTimeShiftAvailable) tvView.timeShiftPause() else tvView.setStreamVolume(volume)
    }

    fun requestAudioFocus() {
        val result = audioManager.requestAudioFocus(focusRequest)
        audioFocusStatus = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) AudioManager.AUDIOFOCUS_GAIN
        else AudioManager.AUDIOFOCUS_LOSS
        setVolumeByAudioFocusStatus()
    }

    fun abandonAudioFocus() {
        audioFocusStatus = AudioManager.AUDIOFOCUS_LOSS
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    override fun onAudioFocusChange(focusChange: Int) {
        // Bugfix: Der TV-eigene Player (com.mediatek.wwtv.tvcenter, JVC/Vestel) läuft im Hintergrund weiter und
        // fordert bei jedem Tune den Audio-Fokus an. Das Original schaltet bei dauerhaftem Fokusverlust stumm,
        // obwohl die App im Vordergrund läuft → Sender ohne Ton (per Log belegt). Solange die App sichtbar im
        // Vordergrund ist (kein Bild-in-Bild), wird ein dauerhafter Fokusverlust daher ignoriert.
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS && isResumedInForeground()) {
            Log.w(TAG, "Audio focus lost while in foreground, keeping volume")
            return
        }
        audioFocusStatus = focusChange
        setVolumeByAudioFocusStatus()
    }

    private fun isResumedInForeground(): Boolean =
        !activity.isInPictureInPictureMode &&
            (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

    companion object {
        private const val TAG = "AudioManagerHelper"
        private const val AUDIO_MAX_VOLUME = 1.0f
        private const val AUDIO_MIN_VOLUME = 0.0f
        private const val AUDIO_DUCKING_VOLUME = 0.3f
    }
}
