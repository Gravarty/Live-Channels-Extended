package com.android.tv

import android.content.Context
import android.media.tv.TvContract
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import com.android.tv.common.SoftPreconditions
import com.android.tv.data.OnCurrentProgramUpdatedListener
import com.android.tv.data.ProgramDataManager
import com.android.tv.data.ProgramImpl
import com.android.tv.data.api.Channel
import com.android.tv.data.api.Program
import com.android.tv.ui.TunableTvView
import com.android.tv.ui.api.TunableTvViewPlayingApi.TimeShiftListener
import com.android.tv.util.TimeShiftUtils
import com.android.tv.util.TvProviderUtils
import com.android.tv.util.Utils
import com.android.tv.tweaks.Tweaks
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port von TimeShiftManager: Timeshift-Steuerung (Play/Pause/Spulen/Springen), Positionsabfrage
 * im Sekundentakt und Programmliste für den aufgezeichneten Zeitraum (mit Platzhaltern für
 * Lücken). Programmladen über Coroutine auf dem DB-Thread statt AsyncDbTask.
 */
class TimeShiftManager(
    private val context: Context,
    tvView: TunableTvView,
    programDataManager: ProgramDataManager,
    private val onCurrentProgramUpdatedListener: OnCurrentProgramUpdatedListener?,
) {
    interface Listener {
        fun onAvailabilityChanged()
        fun onPlayStatusChanged(status: Int)
        fun onRecordTimeRangeChanged()
        fun onCurrentPositionChanged()
        fun onProgramInfoChanged()
        fun onActionEnabledChanged(actionId: Int, enabled: Boolean)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MSG_GET_CURRENT_POSITION -> playController.handleGetCurrentPosition()
            MSG_PREFETCH_PROGRAM -> programManager.prefetchPrograms()
        }
        true
    }
    private val playController = PlayController(tvView)
    private val programManager = ProgramManager(programDataManager)
    internal val currentPositionMediator = CurrentPositionMediator()
    private var listener: Listener? = null
    private var enabledActionIds = TIME_SHIFT_ACTION_ID_PLAY or TIME_SHIFT_ACTION_ID_PAUSE or
        TIME_SHIFT_ACTION_ID_REWIND or TIME_SHIFT_ACTION_ID_FAST_FORWARD or
        TIME_SHIFT_ACTION_ID_JUMP_TO_PREVIOUS or TIME_SHIFT_ACTION_ID_JUMP_TO_NEXT
    var lastActionId = 0
        private set
    private var currentProgramInternal: Program? = null
    private var notificationEnabled = false

    fun setListener(listener: Listener?) { this.listener = listener }

    /** Ob Timeshift für den aktuellen Kanal verfügbar ist. */
    val isAvailable: Boolean get() = playController.available

    /** Aktuelle Wiedergabeposition (Uhrzeit in ms). */
    val currentPositionMs: Long get() = currentPositionMediator.currentPositionMs

    internal fun setCurrentPositionMs(currentTimeMs: Long) = currentPositionMediator.onCurrentPositionChanged(currentTimeMs)

    /** Beginn des aufgezeichneten Bereichs, INVALID_TIME ohne Programmdaten. */
    val recordStartTimeMs: Long
        get() = if (programManager.getOldestProgramStartTime() == INVALID_TIME) INVALID_TIME else playController.recordStartTimeMs

    val recordEndTimeMs: Long
        get() = if (playController.recordEndTimeMs == CURRENT_TIME) System.currentTimeMillis() else playController.recordEndTimeMs

    fun play() {
        if (!isActionEnabled(TIME_SHIFT_ACTION_ID_PLAY)) return
        lastActionId = TIME_SHIFT_ACTION_ID_PLAY
        playController.play()
        updateActions()
    }

    fun pause() {
        if (!isActionEnabled(TIME_SHIFT_ACTION_ID_PAUSE)) return
        lastActionId = TIME_SHIFT_ACTION_ID_PAUSE
        playController.pause()
        updateActions()
    }

    fun togglePlayPause() {
        // Tweak: Play setzt Spulen fort – beim Spulen normal abspielen statt pausieren
        if (Tweaks.isPlayResumesTrickPlay(context) && playController.isTrickPlaying()) {
            play()
            return
        }
        playController.togglePlayPause()
    }

    fun rewind() {
        if (!isActionEnabled(TIME_SHIFT_ACTION_ID_REWIND)) return
        lastActionId = TIME_SHIFT_ACTION_ID_REWIND
        playController.rewind()
        updateActions()
    }

    fun fastForward() {
        if (!isActionEnabled(TIME_SHIFT_ACTION_ID_FAST_FORWARD)) return
        lastActionId = TIME_SHIFT_ACTION_ID_FAST_FORWARD
        playController.fastForward()
        updateActions()
    }

    /** Zum Anfang der aktuellen (bzw. bei < 3 s der vorherigen) Sendung springen. */
    fun jumpToPrevious() {
        if (!isActionEnabled(TIME_SHIFT_ACTION_ID_JUMP_TO_PREVIOUS)) return
        val program = programManager.getProgramAt(currentPositionMediator.currentPositionMs - PROGRAM_START_TIME_THRESHOLD)
            ?: return
        val seekPosition = max(program.startTimeUtcMillis, playController.recordStartTimeMs)
        lastActionId = TIME_SHIFT_ACTION_ID_JUMP_TO_PREVIOUS
        playController.seekTo(seekPosition)
        currentPositionMediator.onSeekRequested(seekPosition)
        updateActions()
    }

    /** Zur nächsten Sendung springen, ohne bereits ausgestrahlte: zur Live-Position. */
    fun jumpToNext() {
        if (!isActionEnabled(TIME_SHIFT_ACTION_ID_JUMP_TO_NEXT)) return
        val currentProgram = programManager.getProgramAt(currentPositionMediator.currentPositionMs) ?: return
        val nextProgram = programManager.getProgramAt(currentProgram.endTimeUtcMillis)
        val currentTimeMs = System.currentTimeMillis()
        lastActionId = TIME_SHIFT_ACTION_ID_JUMP_TO_NEXT
        if (nextProgram == null || nextProgram.startTimeUtcMillis > currentTimeMs) {
            playController.seekTo(currentTimeMs)
            if (playController.isForwarding()) {
                playController.isPlayOffsetChanged = false
                currentPositionMediator.initialize(currentTimeMs)
            } else {
                currentPositionMediator.onSeekRequested(currentTimeMs)
            }
        } else {
            playController.seekTo(nextProgram.startTimeUtcMillis)
            currentPositionMediator.onSeekRequested(nextProgram.startTimeUtcMillis)
        }
        updateActions()
    }

    val playStatus: Int get() = playController.playStatus
    val displayedPlaySpeed: Int get() = playController.displayedPlaySpeed
    val playDirection: Int get() = playController.playDirection

    internal fun enableAction(actionId: Int, enable: Boolean) {
        val old = enabledActionIds
        enabledActionIds = if (enable) enabledActionIds or actionId else enabledActionIds and actionId.inv()
        if (notificationEnabled && old != enabledActionIds) listener?.onActionEnabledChanged(actionId, enable)
    }

    fun isActionEnabled(actionId: Int): Boolean = enabledActionIds and actionId == actionId

    /** Aktionen je nach Abstand zu Aufnahmebeginn/-ende an/aus (mit Hysterese). */
    private fun updateActions() {
        if (isAvailable) {
            enableAction(TIME_SHIFT_ACTION_ID_PLAY, true)
            enableAction(TIME_SHIFT_ACTION_ID_PAUSE, true)
            var threshold = if (isActionEnabled(TIME_SHIFT_ACTION_ID_REWIND)) DISABLE_ACTION_THRESHOLD else ENABLE_ACTION_THRESHOLD
            var enabled = currentPositionMediator.currentPositionMs - playController.recordStartTimeMs > threshold
            enableAction(TIME_SHIFT_ACTION_ID_REWIND, enabled)
            enableAction(TIME_SHIFT_ACTION_ID_JUMP_TO_PREVIOUS, enabled)
            threshold = if (isActionEnabled(TIME_SHIFT_ACTION_ID_FAST_FORWARD)) DISABLE_ACTION_THRESHOLD else ENABLE_ACTION_THRESHOLD
            enabled = recordEndTimeMs - currentPositionMediator.currentPositionMs > threshold
            enableAction(TIME_SHIFT_ACTION_ID_FAST_FORWARD, enabled)
            enableAction(TIME_SHIFT_ACTION_ID_JUMP_TO_NEXT, enabled)
        } else {
            enableAction(TIME_SHIFT_ACTION_ID_PLAY, false)
            enableAction(TIME_SHIFT_ACTION_ID_PAUSE, false)
            enableAction(TIME_SHIFT_ACTION_ID_REWIND, false)
            enableAction(TIME_SHIFT_ACTION_ID_JUMP_TO_PREVIOUS, false)
            enableAction(TIME_SHIFT_ACTION_ID_FAST_FORWARD, false)
            // Bugfix: Original deaktivierte hier PLAY doppelt und vergaß JUMP_TO_NEXT
            enableAction(TIME_SHIFT_ACTION_ID_JUMP_TO_NEXT, false)
        }
    }

    private fun updateCurrentProgram() {
        SoftPreconditions.checkState(isAvailable, TAG, "Time shift is not available")
        SoftPreconditions.checkState(currentPositionMediator.currentPositionMs != INVALID_TIME, TAG, "invalid position")
        var program = getProgramAt(currentPositionMediator.currentPositionMs)
        if (!Program.isProgramValid(program)) program = null
        if (currentProgramInternal != program) {
            currentProgramInternal = program
            if (notificationEnabled && onCurrentProgramUpdatedListener != null) {
                val channel = playController.currentChannel
                if (channel != null) {
                    onCurrentProgramUpdatedListener.onCurrentProgramUpdated(channel.id, currentProgramInternal)
                    playController.onCurrentProgramChanged()
                }
            }
        }
    }

    val isNormalPlaying: Boolean
        get() = playController.available && playController.playStatus == PLAY_STATUS_PLAYING &&
            playController.playDirection == PLAY_DIRECTION_FORWARD && playController.displayedPlaySpeed == PLAY_SPEED_1X

    val isPaused: Boolean get() = playController.available && playController.playStatus == PLAY_STATUS_PAUSED

    /** Sendung zu [timeMs]; fehlt sie, werden Platzhalter angelegt. */
    fun getProgramAt(timeMs: Long): Program? =
        programManager.getProgramAt(timeMs) ?: run {
            programManager.addPlaceholderProgramsAt(timeMs)
            programManager.getProgramAt(timeMs)
        }

    internal fun onAvailabilityChanged() {
        currentPositionMediator.initialize(playController.recordStartTimeMs)
        programManager.onAvailabilityChanged(playController.available, playController.currentChannel, playController.recordStartTimeMs)
        updateActions()
        // Immer melden, auch wenn Benachrichtigungen aus sind
        listener?.onAvailabilityChanged()
    }

    internal fun onRecordTimeRangeChanged() {
        if (playController.available) {
            programManager.onRecordTimeRangeChanged(playController.recordStartTimeMs, playController.recordEndTimeMs)
        }
        updateActions()
        if (notificationEnabled) listener?.onRecordTimeRangeChanged()
    }

    internal fun onCurrentPositionChanged() {
        updateActions()
        updateCurrentProgram()
        if (notificationEnabled) listener?.onCurrentPositionChanged()
    }

    internal fun onPlayStatusChanged(status: Int) {
        if (notificationEnabled) listener?.onPlayStatusChanged(status)
    }

    internal fun onProgramInfoChanged() {
        updateCurrentProgram()
        if (notificationEnabled) listener?.onProgramInfoChanged()
    }

    /** Sendung an der Wiedergabeposition (null ohne Timeshift). */
    val currentProgram: Program? get() = if (isAvailable) currentProgramInternal else null

    private fun getPlaybackSpeed(): Int {
        if (playController.displayedPlaySpeed == PLAY_SPEED_1X) return 1
        val durationMs = currentProgram?.durationMillis ?: 0
        return if (playController.displayedPlaySpeed > PLAY_SPEED_5X) {
            Log.w(TAG, "Unknown displayed play speed is chosen : ${playController.displayedPlaySpeed}")
            TimeShiftUtils.getMaxPlaybackSpeed(durationMs)
        } else {
            TimeShiftUtils.getPlaybackSpeed(playController.displayedPlaySpeed - PLAY_SPEED_2X, durationMs)
        }
    }

    private inner class PlayController(private val tvView: TunableTvView) {
        private var availabilityChangedTimeMs = 0L
        var recordStartTimeMs = 0L
        var recordEndTimeMs = 0L
        var playStatus = PLAY_STATUS_PAUSED
            private set
        var displayedPlaySpeed = PLAY_SPEED_1X
            private set
        var playDirection = PLAY_DIRECTION_FORWARD
            private set
        private var playbackSpeed = 0
        var available = false
            private set
        /** Position weicht von der Live-Zeit ab (nach Pause/Spulen/Springen). */
        var isPlayOffsetChanged = false

        init {
            tvView.setTimeShiftListener(object : TimeShiftListener() {
                override fun onAvailabilityChanged() = this@PlayController.onAvailabilityChanged()

                override fun onRecordStartTimeChanged(recordStartTimeMs: Long) {
                    if (!SoftPreconditions.checkState(available, TAG, "Trick play is not available.")) return
                    var startMs = recordStartTimeMs
                    if (startMs < availabilityChangedTimeMs - ALLOWED_START_TIME_OFFSET) {
                        Log.e(TAG, "The start time is too earlier than the time of availability: {startTime: $startMs, availability: $availabilityChangedTimeMs")
                        return
                    }
                    if (startMs > System.currentTimeMillis()) {
                        // Kommt bei manchen Inputs vor
                        Log.e(TAG, "The start time should not be earlier than the current time, reset the start time to the current time: {startTime: $startMs, current: ${System.currentTimeMillis()}")
                        startMs = System.currentTimeMillis()
                    }
                    if (this@PlayController.recordStartTimeMs == startMs) return
                    this@PlayController.recordStartTimeMs = startMs
                    this@TimeShiftManager.onRecordTimeRangeChanged()
                    // Pausiert am Aufnahmebeginn: weiterspielen, sonst läuft der Puffer davon
                    if (playStatus == PLAY_STATUS_PAUSED && currentPositionMs - startMs < RECORDING_BOUNDARY_THRESHOLD) {
                        this@TimeShiftManager.play()
                    }
                }
            })
        }

        fun onAvailabilityChanged() {
            val newAvailable = tvView.isTimeShiftAvailable
            if (available == newAvailable) return
            available = newAvailable
            // Zustandswechsel ohne Einzelbenachrichtigungen
            notificationEnabled = false
            displayedPlaySpeed = PLAY_SPEED_1X
            playbackSpeed = 1
            playDirection = PLAY_DIRECTION_FORWARD
            handler.removeMessages(MSG_GET_CURRENT_POSITION)
            if (available) {
                availabilityChangedTimeMs = System.currentTimeMillis()
                isPlayOffsetChanged = false
                recordStartTimeMs = availabilityChangedTimeMs
                recordEndTimeMs = CURRENT_TIME
                setPlayStatus(PLAY_STATUS_PLAYING)
                handler.sendEmptyMessageDelayed(MSG_GET_CURRENT_POSITION, REQUEST_CURRENT_POSITION_INTERVAL)
            } else {
                availabilityChangedTimeMs = INVALID_TIME
                isPlayOffsetChanged = false
                recordStartTimeMs = INVALID_TIME
                recordEndTimeMs = INVALID_TIME
                setPlayStatus(PLAY_STATUS_PAUSED)
            }
            this@TimeShiftManager.onAvailabilityChanged()
            notificationEnabled = true
        }

        /** Sekündlich: Position vom Input holen und an Aufnahmegrenzen normal abspielen. */
        fun handleGetCurrentPosition() {
            if (isPlayOffsetChanged) {
                val currentTimeMs = if (recordEndTimeMs == CURRENT_TIME) System.currentTimeMillis() else recordEndTimeMs
                val currentPositionMs = max(min(tvView.timeShiftGetCurrentPositionMs(), currentTimeMs), recordStartTimeMs)
                val isCurrentTime = currentTimeMs - currentPositionMs < RECORDING_BOUNDARY_THRESHOLD
                val newCurrentPositionMs: Long
                if (isCurrentTime && isForwarding()) {
                    // Live erreicht
                    newCurrentPositionMs = currentTimeMs
                    isPlayOffsetChanged = false
                    if (displayedPlaySpeed > PLAY_SPEED_1X) this@TimeShiftManager.play()
                } else {
                    newCurrentPositionMs = currentPositionMs
                    val isRecordStartTime = currentPositionMs - recordStartTimeMs < RECORDING_BOUNDARY_THRESHOLD
                    if (isRecordStartTime && isRewinding()) this@TimeShiftManager.play()
                }
                setCurrentPositionMs(newCurrentPositionMs)
            } else {
                setCurrentPositionMs(System.currentTimeMillis())
                this@TimeShiftManager.onCurrentPositionChanged()
            }
            // Nächste Abfrage einplanen
            handler.sendEmptyMessageDelayed(MSG_GET_CURRENT_POSITION, REQUEST_CURRENT_POSITION_INTERVAL)
        }

        fun play() {
            displayedPlaySpeed = PLAY_SPEED_1X
            playbackSpeed = 1
            playDirection = PLAY_DIRECTION_FORWARD
            tvView.timeShiftPlay()
            setPlayStatus(PLAY_STATUS_PLAYING)
        }

        fun pause() {
            displayedPlaySpeed = PLAY_SPEED_1X
            playbackSpeed = 1
            tvView.timeShiftPause()
            setPlayStatus(PLAY_STATUS_PAUSED)
            isPlayOffsetChanged = true
        }

        fun togglePlayPause() = if (playStatus == PLAY_STATUS_PAUSED) play() else pause()

        // Tweak: Play setzt Spulen fort – true beim Rück- oder Vorspulen
        fun isTrickPlaying(): Boolean = playStatus == PLAY_STATUS_PLAYING &&
            (playDirection == PLAY_DIRECTION_BACKWARD || displayedPlaySpeed != PLAY_SPEED_1X)

        fun rewind() {
            if (playDirection == PLAY_DIRECTION_BACKWARD) increaseDisplayedPlaySpeed() else displayedPlaySpeed = PLAY_SPEED_2X
            playDirection = PLAY_DIRECTION_BACKWARD
            playbackSpeed = getPlaybackSpeed()
            tvView.timeShiftRewind(playbackSpeed)
            setPlayStatus(PLAY_STATUS_PLAYING)
            isPlayOffsetChanged = true
        }

        fun fastForward() {
            if (playDirection == PLAY_DIRECTION_FORWARD) increaseDisplayedPlaySpeed() else displayedPlaySpeed = PLAY_SPEED_2X
            playDirection = PLAY_DIRECTION_FORWARD
            playbackSpeed = getPlaybackSpeed()
            tvView.timeShiftFastForward(playbackSpeed)
            setPlayStatus(PLAY_STATUS_PLAYING)
            isPlayOffsetChanged = true
        }

        /** Springt, begrenzt auf den aufgezeichneten Bereich. */
        fun seekTo(timeMs: Long) {
            val end = if (recordEndTimeMs == CURRENT_TIME) System.currentTimeMillis() else recordEndTimeMs
            tvView.timeShiftSeekTo(min(end, max(recordStartTimeMs, timeMs)))
            isPlayOffsetChanged = true
        }

        /** Geschwindigkeit hängt von der Sendungslänge ab – bei Sendungswechsel neu setzen. */
        fun onCurrentProgramChanged() {
            if (displayedPlaySpeed == PLAY_SPEED_1X) return
            val speed = getPlaybackSpeed()
            if (speed != playbackSpeed) {
                playbackSpeed = speed
                if (playDirection == PLAY_DIRECTION_FORWARD) tvView.timeShiftFastForward(speed)
                else tvView.timeShiftRewind(speed)
            }
        }

        private fun increaseDisplayedPlaySpeed() {
            displayedPlaySpeed = when (displayedPlaySpeed) {
                PLAY_SPEED_1X -> PLAY_SPEED_2X
                PLAY_SPEED_2X -> PLAY_SPEED_3X
                PLAY_SPEED_3X -> PLAY_SPEED_4X
                PLAY_SPEED_4X -> PLAY_SPEED_5X
                else -> displayedPlaySpeed
            }
        }

        private fun setPlayStatus(status: Int) {
            playStatus = status
            this@TimeShiftManager.onPlayStatusChanged(status)
        }

        fun isForwarding() = playStatus == PLAY_STATUS_PLAYING && playDirection == PLAY_DIRECTION_FORWARD
        private fun isRewinding() = playStatus == PLAY_STATUS_PLAYING && playDirection == PLAY_DIRECTION_BACKWARD

        val currentChannel: Channel? get() = tvView.currentChannel
    }

    private inner class ProgramManager(private val programDataManager: ProgramDataManager) {
        private var channel: Channel? = null
        private val programs = ArrayList<Program>()
        private val programLoadQueue = LinkedList<Range<Long>>()
        private var programLoadTask: LoadProgramsTask? = null
        private var emptyFetchCount = 0

        fun onAvailabilityChanged(available: Boolean, channel: Channel?, currentPositionMs: Long) {
            programLoadQueue.clear()
            programLoadTask?.cancel()
            handler.removeMessages(MSG_PREFETCH_PROGRAM)
            programs.clear()
            emptyFetchCount = 0
            this.channel = channel
            if (channel == null || channel.isPassthrough || currentPositionMs == INVALID_TIME) return
            if (!available) return
            val program = programDataManager.getCurrentProgram(channel.id)
            val prefetchStartTimeMs = if (program != null) {
                programs.add(program)
                program.endTimeUtcMillis
            } else {
                Utils.floorTime(currentPositionMs, MAX_PLACEHOLDER_PROGRAM_DURATION)
            }
            programs.addAll(createPlaceholderPrograms(prefetchStartTimeMs, currentPositionMs + PREFETCH_DURATION_FOR_NEXT))
            schedulePrefetchPrograms()
            this@TimeShiftManager.onProgramInfoChanged()
        }

        fun onRecordTimeRangeChanged(startTimeMs: Long, endTime: Long) {
            val ch = channel
            if (ch == null || ch.isPassthrough) return
            val endTimeMs = if (endTime == CURRENT_TIME) System.currentTimeMillis() else endTime
            val fetchStartTimeMs = Utils.floorTime(startTimeMs, MAX_PLACEHOLDER_PROGRAM_DURATION)
            val fetchEndTimeMs = Utils.ceilTime(endTimeMs + PREFETCH_DURATION_FOR_NEXT, MAX_PLACEHOLDER_PROGRAM_DURATION)
            removeOutdatedPrograms(fetchStartTimeMs)
            if (addPlaceholderPrograms(fetchStartTimeMs, fetchEndTimeMs)) {
                programLoadQueue.add(Range.create(fetchStartTimeMs, fetchEndTimeMs))
                startTaskIfNeeded()
            }
        }

        fun startTaskIfNeeded() {
            if (programLoadQueue.isEmpty()) return
            val task = programLoadTask
            if (task == null || task.isCancelled) {
                startNext()
            } else {
                // Vom laufenden Task abgedeckte Bereiche verwerfen
                programLoadQueue.removeAll { task.period.contains(it) }
            }
        }

        private fun startNext() {
            programLoadTask = null
            if (programLoadQueue.isEmpty()) return
            var next = programLoadQueue.poll()!!
            val it = programLoadQueue.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (next.contains(r.lower) || next.contains(r.upper)) {
                    it.remove()
                    next = next.extend(r)
                }
            }
            channel?.let { programLoadTask = LoadProgramsTask(it.id, next).also { t -> t.start() } }
        }

        fun addPlaceholderProgramsAt(timeMs: Long) {
            addPlaceholderPrograms(timeMs, timeMs + PREFETCH_DURATION_FOR_NEXT)
        }

        private fun addPlaceholderPrograms(period: Range<Long>): Boolean = addPlaceholderPrograms(period.lower, period.upper)

        /** Füllt Lücken vor, nach und zwischen den Sendungen mit Platzhaltern (max. 30 min). */
        private fun addPlaceholderPrograms(startTimeMs: Long, endTimeMs: Long): Boolean {
            if (programs.isEmpty()) {
                programs.addAll(createPlaceholderPrograms(startTimeMs, endTimeMs))
                return true
            }
            var added = false
            val first = programs[0]
            if (startTimeMs < first.startTimeUtcMillis) {
                if (!first.isValid) {
                    programs.removeAt(0)
                    programs.addAll(0, createPlaceholderPrograms(startTimeMs, first.endTimeUtcMillis))
                } else {
                    programs.addAll(0, createPlaceholderPrograms(startTimeMs, first.startTimeUtcMillis))
                }
                added = true
            }
            val last = programs[programs.size - 1]
            if (endTimeMs > last.endTimeUtcMillis) {
                if (!last.isValid) {
                    programs.removeAt(programs.size - 1)
                    programs.addAll(createPlaceholderPrograms(last.startTimeUtcMillis, endTimeMs))
                } else {
                    programs.addAll(createPlaceholderPrograms(last.endTimeUtcMillis, endTimeMs))
                }
                added = true
            }
            var i = 1
            while (i < programs.size) {
                val endOfPrevious = programs[i - 1].endTimeUtcMillis
                val startOfCurrent = programs[i].startTimeUtcMillis
                if (startOfCurrent > endOfPrevious) {
                    val placeholders = createPlaceholderPrograms(endOfPrevious, startOfCurrent)
                    programs.addAll(i, placeholders)
                    i += placeholders.size
                    added = true
                }
                ++i
            }
            return added
        }

        private fun removeOutdatedPrograms(startTimeMs: Long) {
            while (programs.isNotEmpty() && programs[0].endTimeUtcMillis <= startTimeMs) programs.removeAt(0)
        }

        private fun removePlaceholderPrograms() {
            programs.removeAll { !it.isValid }
        }

        private fun removeOverlappedPrograms(loadedPrograms: List<Program>) {
            if (programs.isEmpty()) return
            var program = programs[0]
            var i = 0
            var j = 0
            while (i < programs.size && j < loadedPrograms.size) {
                val loaded = loadedPrograms[j]
                // Nicht überlappende überspringen
                while (program.endTimeUtcMillis <= loaded.startTimeUtcMillis) {
                    if (++i == programs.size) return
                    program = programs[i]
                }
                // Überlappende entfernen
                while (program.startTimeUtcMillis < loaded.endTimeUtcMillis &&
                    program.endTimeUtcMillis > loaded.startTimeUtcMillis
                ) {
                    programs.removeAt(i)
                    if (i >= programs.size) break
                    program = programs[i]
                }
                ++j
            }
        }

        private fun createPlaceholderPrograms(startTimeMs: Long, endTimeMs: Long): List<Program> {
            SoftPreconditions.checkArgument(endTimeMs - startTimeMs <= TWO_WEEKS_MS, TAG,
                "createPlaceholderProgram: long duration of placeholder programs are requested (%s, %s)",
                Utils.toTimeString(startTimeMs), Utils.toTimeString(endTimeMs))
            if (startTimeMs >= endTimeMs) return emptyList()
            val result = ArrayList<Program>()
            var start = startTimeMs
            var end = Utils.ceilTime(startTimeMs, MAX_PLACEHOLDER_PROGRAM_DURATION)
            while (end < endTimeMs) {
                result.add(ProgramImpl.Builder().setStartTimeUtcMillis(start).setEndTimeUtcMillis(end).build())
                start = end
                end += MAX_PLACEHOLDER_PROGRAM_DURATION
            }
            result.add(ProgramImpl.Builder().setStartTimeUtcMillis(start).setEndTimeUtcMillis(endTimeMs).build())
            return result
        }

        /** Binärsuche in der sortierten Liste. */
        fun getProgramAt(timeMs: Long): Program? {
            var start = 0
            var end = programs.size - 1
            while (start <= end) {
                val mid = (start + end) / 2
                val program = programs[mid]
                when {
                    program.startTimeUtcMillis > timeMs -> end = mid - 1
                    program.endTimeUtcMillis <= timeMs -> start = mid + 1
                    else -> return program
                }
            }
            return null
        }

        fun getOldestProgramStartTime(): Long = if (programs.isEmpty()) INVALID_TIME else programs[0].startTimeUtcMillis

        private fun getLastValidProgram(): Program? = programs.lastOrNull { it.isValid }

        /** Nächstes Nachladen kurz vor Ende der letzten bekannten Sendung (sonst 0 s/5 s/30 s/5 min). */
        private fun schedulePrefetchPrograms() {
            if (handler.hasMessages(MSG_PREFETCH_PROGRAM)) return
            val last = getLastValidProgram()
            val delay = if (last != null) {
                last.endTimeUtcMillis - PREFETCH_TIME_OFFSET_FROM_PROGRAM_END - System.currentTimeMillis()
            } else {
                when (emptyFetchCount) {
                    0 -> 0L
                    1 -> TimeUnit.SECONDS.toMillis(5)
                    2 -> TimeUnit.SECONDS.toMillis(30)
                    else -> TimeUnit.MINUTES.toMillis(5)
                }
            }
            handler.sendEmptyMessageDelayed(MSG_PREFETCH_PROGRAM, delay)
        }

        fun prefetchPrograms() {
            val startTimeMs = getLastValidProgram()?.endTimeUtcMillis ?: System.currentTimeMillis()
            val endTimeMs = System.currentTimeMillis() + PREFETCH_DURATION_FOR_NEXT
            if (startTimeMs <= endTimeMs) programLoadQueue.add(Range.create(startTimeMs, endTimeMs))
            startTaskIfNeeded()
        }

        /** Ersatz für LoadProgramsForCurrentChannelTask. */
        private inner class LoadProgramsTask(private val channelId: Long, val period: Range<Long>) {
            private var job: Job? = null
            var isCancelled = false
                private set

            fun start() {
                job = scope.launch {
                    val loaded = withContext(dbDispatcher) { queryPrograms(channelId, period) }
                    onPostExecute(loaded)
                }
            }

            fun cancel() {
                isCancelled = true
                job?.cancel()
                startNextLoadingIfNeeded() // entspricht onCancelled()
            }

            private fun onPostExecute(loaded: MutableList<Program>?) {
                programLoadQueue.removeAll { period.contains(it) }
                if (loaded.isNullOrEmpty()) {
                    emptyFetchCount++
                    if (addPlaceholderPrograms(period)) this@TimeShiftManager.onProgramInfoChanged()
                    schedulePrefetchPrograms()
                    startNextLoadingIfNeeded()
                    return
                }
                emptyFetchCount = 0
                if (programs.isNotEmpty()) {
                    removePlaceholderPrograms()
                    removeOverlappedPrograms(loaded)
                    var loadedProgram = loaded[0]
                    var i = 0
                    outer@ while (i < programs.size && loaded.isNotEmpty()) {
                        val program = programs[i]
                        while (program.startTimeUtcMillis > loadedProgram.startTimeUtcMillis) {
                            programs.add(i++, loadedProgram)
                            loaded.removeAt(0)
                            if (loaded.isEmpty()) break
                            loadedProgram = loaded[0]
                        }
                        ++i
                    }
                }
                programs.addAll(loaded)
                addPlaceholderPrograms(period)
                this@TimeShiftManager.onProgramInfoChanged()
                schedulePrefetchPrograms()
                startNextLoadingIfNeeded()
            }

            private fun startNextLoadingIfNeeded() {
                if (programLoadTask === this) programLoadTask = null
                // Tasks sollen nacheinander laufen
                handler.post { startTaskIfNeeded() }
            }
        }
    }

    private val dbDispatcher by lazy { TvSingletons.getSingletons(context).getDbDispatcher() }

    /** DB-Thread: Programme eines Kanals im Zeitraum (wie AsyncProgramQueryTask). */
    private fun queryPrograms(channelId: Long, period: Range<Long>): MutableList<Program>? {
        val uri = TvContract.buildProgramsUriForChannel(channelId, period.lower, period.upper)
        var projection = ProgramImpl.PROJECTION
        if (TvProviderUtils.checkSeriesIdColumn(context, TvContract.Programs.CONTENT_URI)) {
            projection = TvProviderUtils.addExtraColumnsToProjection(projection, TvProviderUtils.EXTRA_PROGRAM_COLUMN_SERIES_ID)
        }
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val list = ArrayList<Program>()
                while (c.moveToNext()) list.add(ProgramImpl.fromCursor(c))
                list
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Error querying $uri", e)
            null
        }
    }

    /** Glättet die Position nach Seek-Anfragen, bis der Input die neue Position meldet. */
    internal inner class CurrentPositionMediator {
        var currentPositionMs = 0L
        private var seekRequestTimeMs = 0L

        fun initialize(timeMs: Long) {
            seekRequestTimeMs = INVALID_TIME
            currentPositionMs = timeMs
            if (timeMs != INVALID_TIME) this@TimeShiftManager.onCurrentPositionChanged()
        }

        fun onSeekRequested(seekTimeMs: Long) {
            seekRequestTimeMs = System.currentTimeMillis()
            currentPositionMs = seekTimeMs
            this@TimeShiftManager.onCurrentPositionChanged()
        }

        fun onCurrentPositionChanged(positionMs: Long) {
            if (seekRequestTimeMs == INVALID_TIME) {
                currentPositionMs = positionMs
                this@TimeShiftManager.onCurrentPositionChanged()
                return
            }
            val currentTimeMs = System.currentTimeMillis()
            val isValid = abs(positionMs - currentPositionMs) < REQUEST_TIMEOUT_MS
            val isTimeout = currentTimeMs > seekRequestTimeMs + REQUEST_TIMEOUT_MS
            if (isValid || isTimeout) {
                initialize(positionMs)
            } else {
                if (playStatus == PLAY_STATUS_PLAYING) {
                    val delta = (currentTimeMs - seekRequestTimeMs) * getPlaybackSpeed()
                    currentPositionMs += if (playDirection == PLAY_DIRECTION_FORWARD) delta else -delta
                }
                this@TimeShiftManager.onCurrentPositionChanged()
            }
        }
    }

    companion object {
        private const val TAG = "TimeShiftManager"

        const val PLAY_STATUS_PAUSED = 0
        const val PLAY_STATUS_PLAYING = 1
        const val PLAY_SPEED_1X = 1
        const val PLAY_SPEED_2X = 2
        const val PLAY_SPEED_3X = 3
        const val PLAY_SPEED_4X = 4
        const val PLAY_SPEED_5X = 5
        const val PLAY_DIRECTION_FORWARD = 0
        const val PLAY_DIRECTION_BACKWARD = 1

        const val TIME_SHIFT_ACTION_ID_PLAY = 1
        const val TIME_SHIFT_ACTION_ID_PAUSE = 1 shl 1
        const val TIME_SHIFT_ACTION_ID_REWIND = 1 shl 2
        const val TIME_SHIFT_ACTION_ID_FAST_FORWARD = 1 shl 3
        const val TIME_SHIFT_ACTION_ID_JUMP_TO_PREVIOUS = 1 shl 4
        const val TIME_SHIFT_ACTION_ID_JUMP_TO_NEXT = 1 shl 5

        private const val MSG_GET_CURRENT_POSITION = 1000
        private const val MSG_PREFETCH_PROGRAM = 1001
        private val REQUEST_CURRENT_POSITION_INTERVAL = TimeUnit.SECONDS.toMillis(1)
        private val MAX_PLACEHOLDER_PROGRAM_DURATION = TimeUnit.MINUTES.toMillis(30)
        const val INVALID_TIME = -1L
        const val CURRENT_TIME = -2L
        private val PREFETCH_TIME_OFFSET_FROM_PROGRAM_END = TimeUnit.MINUTES.toMillis(1)
        private val PREFETCH_DURATION_FOR_NEXT = TimeUnit.HOURS.toMillis(2)
        private val ALLOWED_START_TIME_OFFSET = TimeUnit.DAYS.toMillis(14)
        private val TWO_WEEKS_MS = TimeUnit.DAYS.toMillis(14)
        internal val REQUEST_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(3)
        private val PROGRAM_START_TIME_THRESHOLD = TimeUnit.SECONDS.toMillis(3)
        private val DISABLE_ACTION_THRESHOLD = 3 * REQUEST_CURRENT_POSITION_INTERVAL
        private val ENABLE_ACTION_THRESHOLD = DISABLE_ACTION_THRESHOLD + 3 * REQUEST_CURRENT_POSITION_INTERVAL
        private val RECORDING_BOUNDARY_THRESHOLD = REQUEST_CURRENT_POSITION_INTERVAL
    }
}
