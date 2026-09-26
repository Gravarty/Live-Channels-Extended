package com.android.tv

import android.content.Context
import android.media.tv.TvContentRating
import android.media.tv.TvInputInfo
import android.media.tv.TvRecordingClient
import android.media.tv.TvTrackInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.android.tv.common.compat.TvViewCompat
import com.android.tv.common.compat.TvViewCompat.TvInputCallbackCompat
import com.android.tv.data.api.Channel
import com.android.tv.dvr.DvrTvView
import com.android.tv.ui.TunableTvView
import com.android.tv.ui.api.TunableTvViewPlayingApi
import com.android.tv.util.TvInputManagerHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port von InputSessionManager: verwaltet TvView- und Aufnahme-Sessions und verteilt die
 * begrenzten Tuner eines Inputs (Aufnahme hat Vorrang vor Live-Ansicht). Nur aktiv mit DVR.
 */
@MainThread
@Singleton
class InputSessionManager @Inject constructor(
    @ApplicationContext context: Context,
    private val inputManager: TvInputManagerHelper,
) {
    fun interface OnTvViewChannelChangeListener {
        /** Kanal-URI der TvView geändert (null = keine Wiedergabe). */
        fun onTvViewChannelChange(channelUri: Uri?)
    }

    fun interface OnRecordingSessionChangeListener {
        fun onRecordingSessionChange(create: Boolean, count: Int)
    }

    private val context = context.applicationContext
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val tvViewSessions = LinkedHashSet<TvViewSession>()
    private val recordingSessions: MutableSet<RecordingSession> = Collections.synchronizedSet(LinkedHashSet())
    private val onTvViewChannelChangeListeners = LinkedHashSet<OnTvViewChannelChangeListener>()
    private val onRecordingSessionChangeListeners = LinkedHashSet<OnRecordingSessionChangeListener>()

    fun createTvViewSession(
        tvView: TvViewCompat, tunableTvView: TunableTvViewPlayingApi, callback: TvInputCallbackCompat,
    ): TvViewSession = TvViewSession(tvView, tunableTvView, callback).also { tvViewSessions.add(it) }

    fun releaseTvViewSession(session: TvViewSession) {
        tvViewSessions.remove(session)
        session.reset()
    }

    fun createRecordingSession(
        inputId: String, tag: String, callback: TvRecordingClient.RecordingCallback, handler: Handler, endTimeMs: Long,
    ): RecordingSession {
        val session = RecordingSession(inputId, tag, callback, handler, endTimeMs)
        recordingSessions.add(session)
        onRecordingSessionChangeListeners.forEach { it.onRecordingSessionChange(true, recordingSessions.size) }
        return session
    }

    fun releaseRecordingSession(session: RecordingSession) {
        recordingSessions.remove(session)
        session.release()
        onRecordingSessionChangeListeners.forEach { it.onRecordingSessionChange(false, recordingSessions.size) }
    }

    fun addOnTvViewChannelChangeListener(l: OnTvViewChannelChangeListener) { onTvViewChannelChangeListeners.add(l) }
    fun removeOnTvViewChannelChangeListener(l: OnTvViewChannelChangeListener) { onTvViewChannelChangeListeners.remove(l) }

    internal fun notifyTvViewChannelChange(channelUri: Uri?) =
        onTvViewChannelChangeListeners.toList().forEach { it.onTvViewChannelChange(channelUri) }

    fun addOnRecordingSessionChangeListener(l: OnRecordingSessionChangeListener) { onRecordingSessionChangeListeners.add(l) }
    fun removeRecordingSessionChangeListener(l: OnRecordingSessionChangeListener) { onRecordingSessionChangeListeners.remove(l) }

    fun getCurrentTvViewChannelUri(): Uri? = tvViewSessions.firstOrNull { it.tuned }?.channelUri

    /** Frühestes Ende laufender Aufnahmen dieses Inputs, sonst null. */
    fun getEarliestRecordingSessionEndTimeMs(inputId: String): Long? = synchronized(recordingSessions) {
        recordingSessions.filter { it.tuned && it.inputId == inputId }.minOfOrNull { it.endTimeMs }
    }

    internal fun getTunedTvViewSessionCount(inputId: String?): Int =
        tvViewSessions.count { it.tuned && it.inputId == inputId }

    internal fun isTunedForTvView(channelUri: Uri?): Boolean =
        tvViewSessions.any { it.tuned && it.channelUri == channelUri }

    internal fun getTunedRecordingSessionCount(inputId: String?): Int = synchronized(recordingSessions) {
        recordingSessions.count { it.tuned && it.inputId == inputId }
    }

    internal fun isTunedForRecording(channelUri: Uri?): Boolean = synchronized(recordingSessions) {
        recordingSessions.any { it.tuned && it.channelUri == channelUri }
    }

    inner class TvViewSession internal constructor(
        private val tvView: TvViewCompat,
        private val tunableTvView: TunableTvViewPlayingApi,
        private val callback: TvInputCallbackCompat,
    ) {
        private val isDvrSession = tunableTvView is DvrTvView
        private var channel: Channel? = null
        internal var inputId: String? = null; private set
        internal var channelUri: Uri? = null; private set
        private var params: Bundle? = null
        private var onTuneListener: TunableTvView.OnTuneListener? = null
        internal var tuned = false; private set
        private var needToBeRetuned = false

        init {
            tvView.setCallback(object : DelegateTvInputCallback(callback) {
                override fun onConnectionFailed(inputId: String) {
                    tuned = false
                    needToBeRetuned = false
                    super.onConnectionFailed(inputId)
                    notifyTvViewChannelChange(null)
                }

                override fun onDisconnected(inputId: String) {
                    tuned = false
                    needToBeRetuned = false
                    super.onDisconnected(inputId)
                    notifyTvViewChannelChange(null)
                }
            })
        }

        /** Vorwärm-Tune ohne Kanalobjekt. */
        fun tune(inputId: String, channelUri: Uri) {
            if (DEBUG) Log.d(TAG, "warm-up tune: {input=$inputId, channelUri=$channelUri}")
            this.inputId = inputId
            this.channelUri = channelUri
            tuned = true
            needToBeRetuned = false
            tvView.tune(inputId, channelUri)
            notifyTvViewChannelChange(channelUri)
        }

        /** Tunt, sofern ein Tuner frei ist; sonst Verbindungsfehler + Wiederholung, sobald frei. */
        fun tune(channel: Channel, params: Bundle?, listener: TunableTvView.OnTuneListener?) {
            if (DEBUG) Log.d(TAG, "tune: {session=$this, channel=$channel, params=$params, tuned=$tuned}")
            this.channel = channel
            inputId = channel.inputId
            channelUri = channel.uri
            this.params = params
            onTuneListener = listener
            val input: TvInputInfo? = inputManager.getTvInputInfo(channel.inputId)
            if (input == null ||
                (input.canRecord() && !isTunedForRecording(channelUri) &&
                    getTunedRecordingSessionCount(channel.inputId) >= input.tunerCount)
            ) {
                if (DEBUG) {
                    Log.d(TAG, if (input == null) "Can't find input for input ID: ${channel.inputId}" else "No more tuners to tune for input: $input")
                }
                callback.onConnectionFailed(channel.inputId)
                resetByRecording()
                return
            }
            tuned = true
            needToBeRetuned = false
            tvView.tune(channel.inputId, channelUri, params)
            notifyTvViewChannelChange(channelUri)
        }

        internal fun retune() {
            if (isDvrSession) {
                Log.w(TAG, "DVR session should not call retune()!")
                return
            }
            if (needToBeRetuned) {
                (tunableTvView as TunableTvView).tuneTo(channel, params, onTuneListener)
                needToBeRetuned = false
            }
        }

        fun timeShiftPlay(inputId: String, recordedProgramUri: Uri) {
            tuned = false
            needToBeRetuned = false
            tvView.timeShiftPlay(inputId, recordedProgramUri)
            notifyTvViewChannelChange(null)
        }

        fun reset() {
            tuned = false
            tvView.reset()
            needToBeRetuned = false
            notifyTvViewChannelChange(null)
        }

        /** Tuner wird für eine Aufnahme gebraucht: Wiedergabe stoppen, später neu tunen. */
        internal fun resetByRecording() {
            callback.onVideoUnavailable(inputId, TunableTvView.VIDEO_UNAVAILABLE_REASON_NO_RESOURCE)
            if (isDvrSession) {
                Log.w(TAG, "DVR session should not call resetByRecording()!")
                return
            }
            if (tuned) {
                (tunableTvView as TunableTvView).resetByRecording()
                reset()
            }
            needToBeRetuned = true
        }
    }

    inner class RecordingSession internal constructor(
        internal val inputId: String,
        tag: String,
        private val callback: TvRecordingClient.RecordingCallback,
        private val handler: Handler,
        @Volatile internal var endTimeMs: Long,
    ) {
        internal var channelUri: Uri? = null; private set
        private var client: TvRecordingClient? = TvRecordingClient(context, tag, callback, handler)
        internal var tuned = false; private set

        internal fun release() = runOnHandler(mainThreadHandler) {
            tuned = false
            client?.release()
            client = null
            // Frei gewordenen Tuner an eine wartende TvView-Session zurückgeben
            tvViewSessions.firstOrNull { !it.tuned && it.inputId == inputId }?.retune()
        }

        fun tune(inputId: String, channelUri: Uri) = runOnHandler(mainThreadHandler) {
            val tunedRecordingSessionCount = getTunedRecordingSessionCount(inputId)
            val input = inputManager.getTvInputInfo(inputId)
            if (input == null || !input.canRecord() || input.tunerCount <= tunedRecordingSessionCount) {
                runOnHandler(handler) { callback.onConnectionFailed(inputId) }
                return@runOnHandler
            }
            tuned = true
            val tunedTuneSessionCount = getTunedTvViewSessionCount(inputId)
            if (!isTunedForTvView(channelUri) && tunedTuneSessionCount > 0 &&
                tunedRecordingSessionCount + tunedTuneSessionCount >= input.tunerCount
            ) {
                tvViewSessions.firstOrNull {
                    it.tuned && it.inputId == inputId && !isTunedForRecording(it.channelUri)
                }?.resetByRecording()
            }
            this.channelUri = channelUri
            client?.tune(inputId, channelUri)
        }

        fun startRecording(programHintUri: Uri?) { client?.startRecording(programHintUri) }
        fun stopRecording() { client?.stopRecording() }
        fun setEndTimeMs(endTimeMs: Long) { this.endTimeMs = endTimeMs }

        private fun runOnHandler(handler: Handler, runnable: () -> Unit) {
            if (Looper.myLooper() == handler.looper) runnable() else handler.post(runnable)
        }
    }

    private open class DelegateTvInputCallback(private val delegate: TvInputCallbackCompat) : TvInputCallbackCompat() {
        override fun onConnectionFailed(inputId: String) = delegate.onConnectionFailed(inputId)
        override fun onDisconnected(inputId: String) = delegate.onDisconnected(inputId)
        override fun onChannelRetuned(inputId: String, channelUri: Uri) = delegate.onChannelRetuned(inputId, channelUri)
        override fun onTracksChanged(inputId: String, tracks: List<TvTrackInfo>) = delegate.onTracksChanged(inputId, tracks)
        override fun onTrackSelected(inputId: String, type: Int, trackId: String?) = delegate.onTrackSelected(inputId, type, trackId)
        override fun onVideoSizeChanged(inputId: String, width: Int, height: Int) = delegate.onVideoSizeChanged(inputId, width, height)
        override fun onVideoAvailable(inputId: String) = delegate.onVideoAvailable(inputId)
        override fun onVideoUnavailable(inputId: String, reason: Int) = delegate.onVideoUnavailable(inputId, reason)
        override fun onContentAllowed(inputId: String) = delegate.onContentAllowed(inputId)
        override fun onContentBlocked(inputId: String, rating: TvContentRating) = delegate.onContentBlocked(inputId, rating)
        override fun onTimeShiftStatusChanged(inputId: String, status: Int) = delegate.onTimeShiftStatusChanged(inputId, status)
        override fun onSignalStrength(inputId: String, value: Int) = delegate.onSignalStrength(inputId, value)
    }

    companion object {
        private const val TAG = "InputSessionManager"
        private const val DEBUG = false
    }
}
