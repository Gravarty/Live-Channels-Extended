package com.android.tv.menu

import android.content.Context
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.TextView
import android.widget.Toast
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.TimeShiftManager
import com.android.tv.TvSingletons
import com.android.tv.common.SoftPreconditions
import com.android.tv.dialog.HalfSizedDialogFragment
import com.android.tv.dvr.DvrDataManager
import com.android.tv.dvr.DvrManager
import com.android.tv.dvr.data.ScheduledRecording
import com.android.tv.dvr.ui.DvrStopRecordingFragment
import com.android.tv.dvr.ui.DvrUiHelper
import com.android.tv.features.TvFeatures
import com.android.tv.ui.TunableTvView

/**
 * Wiedergabe-Zeile: Zeitleiste der aktuellen Sendung, Position, Knöpfe (Sprung, Spulen,
 * Play/Pause) und Aufnahme-Knopf (mit DVR).
 */
class PlayControlsRowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0, defStyleRes: Int = 0,
) : MenuRowView(context, attrs, defStyleAttr, defStyleRes) {

    private val timeIndicatorLeftMargin = -resources.getDimensionPixelSize(R.dimen.play_controls_time_indicator_width) / 2
    private val timeTextLeftMargin = -resources.getDimensionPixelOffset(R.dimen.play_controls_time_width) / 2
    private val timelineWidth = resources.getDimensionPixelSize(R.dimen.play_controls_width)
    private val timeFormat = DateFormat.getTimeFormat(context)
    private val normalButtonMargin = resources.getDimensionPixelSize(R.dimen.play_controls_button_normal_margin)
    private val compactButtonMargin = resources.getDimensionPixelSize(R.dimen.play_controls_button_compact_margin)
    private val dvrDataManager: DvrDataManager?
    private val dvrManager: DvrManager?
    private val mainActivity = context as MainActivity
    private val unavailableMessage = resources.getString(R.string.play_controls_unavailable)

    private lateinit var backgroundView: TextView
    private lateinit var timeIndicator: View
    private lateinit var timeText: TextView
    private lateinit var progress: PlaybackProgressBar
    private lateinit var jumpPreviousButton: PlayControlsButton
    private lateinit var rewindButton: PlayControlsButton
    private lateinit var playPauseButton: PlayControlsButton
    private lateinit var fastForwardButton: PlayControlsButton
    private lateinit var jumpNextButton: PlayControlsButton
    private lateinit var recordButton: PlayControlsButton
    private lateinit var programStartTimeText: TextView
    private lateinit var programEndTimeText: TextView
    private lateinit var tvView: TunableTvView
    private lateinit var timeShiftManager: TimeShiftManager
    private var programStartTimeMs = 0L
    private var programEndTimeMs = 0L
    private var useCompactLayout = false

    private val scheduledRecordingListener = object : DvrDataManager.ScheduledRecordingListener {
        override fun onScheduledRecordingAdded(vararg scheduledRecordings: ScheduledRecording) {}
        override fun onScheduledRecordingRemoved(vararg scheduledRecordings: ScheduledRecording) {}
        override fun onScheduledRecordingStatusChanged(vararg scheduledRecordings: ScheduledRecording) {
            val currentChannel = mainActivity.currentChannel
            if (currentChannel != null && isShown && scheduledRecordings.any { it.channelId == currentChannel.id }) {
                updateRecordButton()
            }
        }
    }

    init {
        if (TvFeatures.isDvrEnabled(context)) {
            val singletons = TvSingletons.getSingletons(context)
            dvrDataManager = singletons.getDvrDataManager()
            dvrManager = singletons.getDvrManager()
        } else {
            dvrDataManager = null
            dvrManager = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val manager = dvrDataManager ?: return
        manager.addScheduledRecordingListener(scheduledRecordingListener)
        if (!manager.isDvrScheduleLoadFinished) {
            manager.addDvrScheduleLoadFinishedListener(object : DvrDataManager.OnDvrScheduleLoadFinishedListener {
                override fun onDvrScheduleLoadFinished() {
                    manager.removeDvrScheduleLoadFinishedListener(this)
                    if (isShown) updateRecordButton()
                }
            })
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dvrDataManager?.removeScheduledRecordingListener(scheduledRecordingListener)
    }

    override fun getContentsViewId() = R.id.play_controls

    override fun onFinishInflate() {
        super.onFinishInflate()
        findViewById<View>(R.id.body).clipToOutline = true
        backgroundView = findViewById(R.id.background)
        timeIndicator = findViewById(R.id.time_indicator)
        timeText = findViewById(R.id.time_text)
        progress = findViewById(R.id.progress)
        jumpPreviousButton = findViewById(R.id.jump_previous)
        rewindButton = findViewById(R.id.rewind)
        playPauseButton = findViewById(R.id.play_pause)
        fastForwardButton = findViewById(R.id.fast_forward)
        jumpNextButton = findViewById(R.id.jump_next)
        recordButton = findViewById(R.id.record)
        programStartTimeText = findViewById(R.id.program_start_time)
        programEndTimeText = findViewById(R.id.program_end_time)

        initializeButton(jumpPreviousButton, R.drawable.lb_ic_skip_previous, R.string.play_controls_description_skip_previous, null) {
            if (timeShiftManager.isAvailable) {
                timeShiftManager.jumpToPrevious()
                updateControls(true)
            }
        }
        initializeButton(rewindButton, R.drawable.lb_ic_fast_rewind, R.string.play_controls_description_fast_rewind, null) {
            if (timeShiftManager.isAvailable) {
                timeShiftManager.rewind()
                updateButtons()
            }
        }
        initializeButton(playPauseButton, R.drawable.lb_ic_play, R.string.play_controls_description_play_pause, null) {
            if (timeShiftManager.isAvailable) {
                timeShiftManager.togglePlayPause()
                updateButtons()
            }
        }
        initializeButton(fastForwardButton, R.drawable.lb_ic_fast_forward, R.string.play_controls_description_fast_forward, null) {
            if (timeShiftManager.isAvailable) {
                timeShiftManager.fastForward()
                updateButtons()
            }
        }
        initializeButton(jumpNextButton, R.drawable.lb_ic_skip_next, R.string.play_controls_description_skip_next, null) {
            if (timeShiftManager.isAvailable) {
                timeShiftManager.jumpToNext()
                updateControls(true)
            }
        }
        initializeButton(recordButton, R.drawable.ic_record_start, R.string.channels_item_record_start,
            resources.getColor(R.color.play_controls_recording_icon_color_on_focus, null), ::onRecordButtonClicked)
    }

    private fun isCurrentChannelRecording(): Boolean {
        val currentChannel = mainActivity.currentChannel
        return currentChannel != null && dvrManager?.getCurrentRecording(currentChannel.id) != null
    }

    /** Aufnahme der laufenden Sendung starten bzw. stoppen (mit Rückfrage). */
    private fun onRecordButtonClicked() {
        val currentChannel = mainActivity.currentChannel
        val manager = dvrManager
        if (!isCurrentChannelRecording()) {
            if (manager == null || currentChannel == null || !manager.isChannelRecordable(currentChannel)) {
                Toast.makeText(mainActivity, R.string.dvr_msg_cannot_record_channel, Toast.LENGTH_SHORT).show()
            } else {
                val program = mainActivity.programDataManager.getCurrentProgram(currentChannel.id)
                DvrUiHelper.checkStorageStatusAndShowErrorMessage(mainActivity, currentChannel.inputId) {
                    DvrUiHelper.requestRecordingCurrentProgram(mainActivity, currentChannel, program, true)
                }
            }
        } else if (currentChannel != null && manager != null) {
            DvrUiHelper.showStopRecordingDialog(mainActivity, currentChannel.id, DvrStopRecordingFragment.REASON_USER_STOP,
                HalfSizedDialogFragment.OnActionClickListener { actionId ->
                    if (actionId == DvrStopRecordingFragment.ACTION_STOP) {
                        manager.getCurrentRecording(currentChannel.id)?.let { manager.stopRecording(it) }
                    }
                })
        }
    }

    private fun initializeButton(
        button: PlayControlsButton, imageResId: Int, descriptionId: Int, focusedIconColor: Int?, clickAction: () -> Unit,
    ) {
        button.setImageResId(imageResId)
        button.setAction(Runnable(clickAction))
        focusedIconColor?.let { button.setFocusedIconColor(it) }
        button.findViewById<View>(R.id.button).contentDescription = resources.getString(descriptionId)
    }

    override fun onBind(row: MenuRow) {
        super.onBind(row)
        val playControlsRow = row as PlayControlsRow
        tvView = playControlsRow.tvView
        timeShiftManager = playControlsRow.timeShiftManager
        timeShiftManager.setListener(object : TimeShiftManager.Listener {
            override fun onAvailabilityChanged() {
                updateMenuVisibility()
                updateAll(false)
            }

            override fun onPlayStatusChanged(status: Int) {
                updateMenuVisibility()
                if (timeShiftManager.isAvailable) updateControls(false)
            }

            override fun onRecordTimeRangeChanged() {
                if (timeShiftManager.isAvailable) updateControls(false)
            }

            override fun onCurrentPositionChanged() {
                if (timeShiftManager.isAvailable) {
                    initializeTimeline()
                    updateControls(false)
                }
            }

            override fun onProgramInfoChanged() {
                if (timeShiftManager.isAvailable) {
                    initializeTimeline()
                    updateControls(false)
                }
            }

            /** Fokus auf Play/Pause, wenn der fokussierte Knopf deaktiviert wird. */
            override fun onActionEnabledChanged(actionId: Int, enabled: Boolean) {
                if (!enabled && (
                        (actionId == TimeShiftManager.TIME_SHIFT_ACTION_ID_JUMP_TO_PREVIOUS && jumpPreviousButton.hasFocus()) ||
                            (actionId == TimeShiftManager.TIME_SHIFT_ACTION_ID_REWIND && rewindButton.hasFocus()) ||
                            (actionId == TimeShiftManager.TIME_SHIFT_ACTION_ID_FAST_FORWARD && fastForwardButton.hasFocus()) ||
                            (actionId == TimeShiftManager.TIME_SHIFT_ACTION_ID_JUMP_TO_NEXT && jumpNextButton.hasFocus()))
                ) {
                    playPauseButton.requestFocus()
                }
            }
        })
        updateAll(true)
    }

    private fun initializeTimeline() {
        // Bugfix: ohne gültige Position gibt es keine Sendung (Original: NPE)
        val program = timeShiftManager.getProgramAt(timeShiftManager.currentPositionMs) ?: return
        programStartTimeMs = program.startTimeUtcMillis
        programEndTimeMs = program.endTimeUtcMillis
        progress.setMax(programEndTimeMs - programStartTimeMs)
        updateRecTimeText()
        SoftPreconditions.checkArgument(programStartTimeMs <= programEndTimeMs, TAG, "invalid program times")
    }

    /** Menü bleibt offen, solange gespult wird. */
    private fun updateMenuVisibility() {
        // Bugfix: Original hielt das Menü auch während der Pause dauerhaft offen; jetzt blendet es normal aus
        menu?.setKeepVisible(timeShiftManager.isAvailable && !timeShiftManager.isNormalPlaying && !timeShiftManager.isPaused)
    }

    fun onPreselected() = updateControls(true)

    override fun onSelected(showTitle: Boolean) {
        super.onSelected(showTitle)
        postHideRippleAnimation()
    }

    override fun initialize(reason: Int) {
        super.initialize(reason)
        val target = when (reason) {
            Menu.REASON_PLAY_CONTROLS_JUMP_TO_PREVIOUS ->
                if (timeShiftManager.isActionEnabled(TimeShiftManager.TIME_SHIFT_ACTION_ID_JUMP_TO_PREVIOUS)) jumpPreviousButton else playPauseButton
            Menu.REASON_PLAY_CONTROLS_REWIND ->
                if (timeShiftManager.isActionEnabled(TimeShiftManager.TIME_SHIFT_ACTION_ID_REWIND)) rewindButton else playPauseButton
            Menu.REASON_PLAY_CONTROLS_FAST_FORWARD ->
                if (timeShiftManager.isActionEnabled(TimeShiftManager.TIME_SHIFT_ACTION_ID_FAST_FORWARD)) fastForwardButton else playPauseButton
            Menu.REASON_PLAY_CONTROLS_JUMP_TO_NEXT ->
                if (timeShiftManager.isActionEnabled(TimeShiftManager.TIME_SHIFT_ACTION_ID_JUMP_TO_NEXT)) jumpNextButton else playPauseButton
            else -> playPauseButton
        }
        setInitialFocusView(target)
        postHideRippleAnimation()
    }

    private fun postHideRippleAnimation() {
        // Ripple beim Anzeigen nicht abspielen
        post {
            jumpPreviousButton.hideRippleAnimation()
            rewindButton.hideRippleAnimation()
            playPauseButton.hideRippleAnimation()
            fastForwardButton.hideRippleAnimation()
            jumpNextButton.hideRippleAnimation()
        }
    }

    /** Verlässt der Fokus Spulen-Knöpfe, wieder normal abspielen. */
    override fun onChildFocusChange(v: View, hasFocus: Boolean) {
        super.onChildFocusChange(v, hasFocus)
        if ((v.parent == rewindButton || v.parent == fastForwardButton) && !hasFocus &&
            timeShiftManager.playStatus == TimeShiftManager.PLAY_STATUS_PLAYING
        ) {
            timeShiftManager.play()
            updateButtons()
        }
    }

    override fun requestChildFocus() {
        playPauseButton.requestFocusWithAccessibility()
    }

    fun update() = updateAll(false)

    private fun updateAll(forceUpdate: Boolean) {
        if (timeShiftManager.isAvailable && !tvView.isScreenBlocked) {
            isEnabled = true
            initializeTimeline()
            backgroundView.isEnabled = true
            setTextIfNeeded(backgroundView, null)
        } else {
            isEnabled = false
            backgroundView.isEnabled = false
            setTextIfNeeded(backgroundView, unavailableMessage)
        }
        updateControls(forceUpdate)
    }

    private fun updateControls(forceUpdate: Boolean) {
        if (forceUpdate || contentsView.isShown) {
            updateTime()
            updateProgress()
            updateButtons()
            updateRecordButton()
            updateButtonMargin()
        }
    }

    private fun updateTime() {
        if (!isEnabled) {
            timeText.visibility = INVISIBLE
            timeIndicator.visibility = GONE
            return
        }
        timeText.visibility = VISIBLE
        timeIndicator.visibility = VISIBLE
        val currentPositionMs = timeShiftManager.currentPositionMs
        val pixel = convertDurationToPixel(currentPositionMs - programStartTimeMs)
        timeText.translationX = (pixel + timeTextLeftMargin).toFloat()
        setTextIfNeeded(timeText, getTimeString(currentPositionMs))
        timeIndicator.translationX = (pixel + timeIndicatorLeftMargin).toFloat()
    }

    private fun updateProgress() {
        if (!isEnabled) {
            progress.setProgressRange(0, 0)
            return
        }
        fun clamp(t: Long) = t.coerceIn(programStartTimeMs, maxOf(programStartTimeMs, programEndTimeMs))
        val progressStartTimeMs = clamp(timeShiftManager.recordStartTimeMs)
        val currentPlayingTimeMs = clamp(timeShiftManager.currentPositionMs)
        val progressEndTimeMs = clamp(timeShiftManager.recordEndTimeMs)
        progress.setProgressRange(progressStartTimeMs - programStartTimeMs, progressEndTimeMs - programStartTimeMs)
        progress.setProgress(currentPlayingTimeMs - programStartTimeMs)
    }

    private fun updateRecTimeText() {
        if (isEnabled) {
            programStartTimeText.visibility = VISIBLE
            setTextIfNeeded(programStartTimeText, getTimeString(programStartTimeMs))
            programEndTimeText.visibility = VISIBLE
            setTextIfNeeded(programEndTimeText, getTimeString(programEndTimeMs))
        } else {
            programStartTimeText.visibility = GONE
            programEndTimeText.visibility = GONE
        }
    }

    private fun updateButtons() {
        val buttons = listOf(playPauseButton, jumpPreviousButton, jumpNextButton, rewindButton, fastForwardButton)
        if (!isEnabled) {
            buttons.forEach { it.visibility = GONE }
            return
        }
        buttons.forEach { it.visibility = VISIBLE }
        if (timeShiftManager.playStatus == TimeShiftManager.PLAY_STATUS_PAUSED) {
            playPauseButton.setImageResId(R.drawable.lb_ic_play)
            playPauseButton.isEnabled = timeShiftManager.isActionEnabled(TimeShiftManager.TIME_SHIFT_ACTION_ID_PLAY)
        } else {
            playPauseButton.setImageResId(R.drawable.lb_ic_pause)
            playPauseButton.isEnabled = timeShiftManager.isActionEnabled(TimeShiftManager.TIME_SHIFT_ACTION_ID_PAUSE)
        }
        jumpPreviousButton.isEnabled = timeShiftManager.isActionEnabled(TimeShiftManager.TIME_SHIFT_ACTION_ID_JUMP_TO_PREVIOUS)
        rewindButton.isEnabled = timeShiftManager.isActionEnabled(TimeShiftManager.TIME_SHIFT_ACTION_ID_REWIND)
        fastForwardButton.isEnabled = timeShiftManager.isActionEnabled(TimeShiftManager.TIME_SHIFT_ACTION_ID_FAST_FORWARD)
        jumpNextButton.isEnabled = timeShiftManager.isActionEnabled(TimeShiftManager.TIME_SHIFT_ACTION_ID_JUMP_TO_NEXT)
        updateButtonMargin()
        // Geschwindigkeit am Spulen-Knopf der aktuellen Richtung anzeigen
        val button = if (timeShiftManager.playDirection == TimeShiftManager.PLAY_DIRECTION_FORWARD) {
            rewindButton.setLabel(null)
            fastForwardButton
        } else {
            fastForwardButton.setLabel(null)
            rewindButton
        }
        button.setLabel(
            if (timeShiftManager.displayedPlaySpeed == TimeShiftManager.PLAY_SPEED_1X) null
            else resources.getString(R.string.play_controls_speed, timeShiftManager.displayedPlaySpeed))
    }

    private fun updateRecordButton() {
        if (!isEnabled) {
            recordButton.visibility = GONE
            return
        }
        val manager = dvrManager
        val currentChannel = mainActivity.currentChannel
        if (manager == null || currentChannel == null || !manager.isChannelRecordable(currentChannel)) {
            recordButton.visibility = GONE
            updateButtonMargin()
            return
        }
        recordButton.visibility = VISIBLE
        updateButtonMargin()
        recordButton.setImageResId(if (isCurrentChannelRecording()) R.drawable.ic_record_stop else R.drawable.ic_record_start)
    }

    /** Ab 6 Knöpfen kompaktere Abstände. */
    private fun updateButtonMargin() {
        val visibleCount = listOf(jumpPreviousButton, rewindButton, playPauseButton, fastForwardButton, jumpNextButton, recordButton)
            .count { it.visibility == VISIBLE }
        val compact = visibleCount > NORMAL_WIDTH_MAX_BUTTON_COUNT
        if (useCompactLayout == compact) return
        useCompactLayout = compact
        val margin = if (compact) compactButtonMargin else normalButtonMargin
        for (button in listOf(jumpPreviousButton, rewindButton, playPauseButton, fastForwardButton, jumpNextButton, recordButton)) {
            val params = button.layoutParams as MarginLayoutParams
            params.setMargins(margin, 0, margin, 0)
            button.layoutParams = params
        }
    }

    private fun getTimeString(timeMs: Long): String = timeFormat.format(timeMs)

    private fun convertDurationToPixel(duration: Long): Int =
        if (programEndTimeMs <= programStartTimeMs) 0
        else (duration * timelineWidth / (programEndTimeMs - programStartTimeMs)).toInt()

    private fun setTextIfNeeded(textView: TextView, text: String?) {
        if (textView.text.toString() != (text ?: "")) textView.text = text
    }

    companion object {
        private const val TAG = "PlayControlsRowView"
        private const val NORMAL_WIDTH_MAX_BUTTON_COUNT = 5
    }
}
