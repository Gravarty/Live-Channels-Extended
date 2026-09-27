package com.android.tv.guide

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.TvSingletons
import com.android.tv.common.util.Clock
import com.android.tv.dvr.data.ScheduledRecording
import com.android.tv.dvr.ui.DvrUiHelper
import com.android.tv.features.TvFeatures
import com.android.tv.guide.ProgramManager.TableEntry
import com.android.tv.tweaks.ConfirmRecord
import com.android.tv.tweaks.Tweaks
import com.android.tv.util.Utils
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Eintrag in der Programmübersicht: Titel/Folge, Fortschritt der laufenden Sendung (sekündlich),
 * Aufnahme-Symbol; Klick tunt (laufend) bzw. plant/entfernt eine Aufnahme (Zukunft, mit DVR).
 */
class ProgramItemView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : TextView(context, attrs, defStyle) {

    private val singletons = TvSingletons.getSingletons(context)
    private val clock: Clock = singletons.getClock()
    private val channelDataManager = singletons.getChannelDataManager()
    private val dvrManager = singletons.getDvrManager()
    private var programGuide: ProgramGuide? = null
    var tableEntry: TableEntry? = null
        private set
    private var maxWidthForRipple = 0
    private var textWidth = 0
    private var preventParentRelayout = false

    /** Aktualisiert jede Sekunde den Fortschrittsbalken der laufenden Sendung. */
    private val updateFocus: Runnable = object : Runnable {
        override fun run() {
            refreshDrawableState()
            val entry = tableEntry ?: return
            if (entry.isCurrentProgram) {
                val background = background
                val guide = programGuide
                if (guide == null || !guide.isActive || guide.isRunningAnimation) background.jumpToCurrentState()
                setProgress(background, R.id.reverse_progress,
                    MAX_PROGRESS - getProgress(clock, entry.entryStartUtcMillis, entry.entryEndUtcMillis))
            }
            handler?.postAtTime(this, Utils.ceilTime(clock.uptimeMillis(), FOCUS_UPDATE_FREQUENCY))
        }
    }

    init {
        setOnClickListener(::onClicked)
        setOnFocusChangeListener { _, hasFocus -> if (hasFocus) updateFocus.run() else handler?.removeCallbacks(updateFocus) }
    }

    private fun onClicked(view: View) {
        val entry = tableEntry ?: return
        val tvActivity = context as MainActivity
        val channel = tvActivity.channelDataManager.getChannel(entry.channelId)
        if (entry.isCurrentProgram) {
            // Ripple nur bei schmalen Einträgen abwarten
            val delay = if (entry.width > maxWidthForRipple) 0L
            else resources.getInteger(R.integer.program_guide_ripple_anim_duration).toLong()
            view.postDelayed({
                tvActivity.tuneToChannel(channel)
                tvActivity.hideOverlaysForTune()
            }, delay)
        } else if (entry.program != null && TvFeatures.isDvrEnabled(context)) {
            val manager = singletons.getDvrManager() ?: return
            if (entry.entryStartUtcMillis > clock.currentTimeMillis() && manager.isProgramRecordable(entry.program)) {
                // Tweak: Bei Aufnahme fragen – Folgen ohne Aufnahmeplan weiter über den Stock-Dialog
                if (Tweaks.isConfirmRecord(context) && (entry.scheduledRecording != null || !entry.program.isEpisodic)) {
                    ConfirmRecord.show(tvActivity, entry.program, entry.scheduledRecording)
                } else if (entry.scheduledRecording == null) {
                    // DvrFlags.startEarlyEndLateEnabled() ist im AOSP-Build false
                    val inputId = channel?.inputId ?: return // Bugfix: Kanal fehlt (Original: NPE)
                    DvrUiHelper.checkStorageStatusAndShowErrorMessage(tvActivity, inputId) {
                        DvrUiHelper.requestRecordingFutureProgram(tvActivity, entry.program, false)
                    }
                } else {
                    manager.removeScheduledRecording(entry.scheduledRecording)
                    Toast.makeText(context, resources.getString(R.string.dvr_schedules_deletion_info, entry.program.title),
                        Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, R.string.dvr_msg_cannot_record_program, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initIfNeeded() {
        if (visibleThreshold != 0) return
        val res = context.resources
        visibleThreshold = res.getDimensionPixelOffset(R.dimen.program_guide_table_item_visible_threshold)
        itemPadding = res.getDimensionPixelOffset(R.dimen.program_guide_table_item_padding)
        iconPadding = res.getDimensionPixelOffset(R.dimen.program_guide_table_item_compound_drawable_padding)
        val programTitleColor = ColorStateList.valueOf(res.getColor(R.color.program_guide_table_item_program_title_text_color, null))
        val grayedOutProgramTitleColor = res.getColorStateList(R.color.program_guide_table_item_grayed_out_program_text_color, null)
        val episodeTitleColor = ColorStateList.valueOf(res.getColor(R.color.program_guide_table_item_program_episode_title_text_color, null))
        val grayedOutEpisodeTitleColor =
            ColorStateList.valueOf(res.getColor(R.color.program_guide_table_item_grayed_out_program_episode_title_text_color, null))
        val programTitleSize = res.getDimensionPixelSize(R.dimen.program_guide_table_item_program_title_font_size)
        val episodeTitleSize = res.getDimensionPixelSize(R.dimen.program_guide_table_item_program_episode_title_font_size)
        programTitleStyle = TextAppearanceSpan(null, 0, programTitleSize, programTitleColor, null)
        grayedOutProgramTitleStyle = TextAppearanceSpan(null, 0, programTitleSize, grayedOutProgramTitleColor, null)
        episodeTitleStyle = TextAppearanceSpan(null, 0, episodeTitleSize, episodeTitleColor, null)
        grayedOutEpisodeTitleStyle = TextAppearanceSpan(null, 0, episodeTitleSize, grayedOutEpisodeTitleColor, null)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        initIfNeeded()
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val entry = tableEntry ?: return super.onCreateDrawableState(extraSpace)
        val states = super.onCreateDrawableState(extraSpace + STATE_CURRENT_PROGRAM.size + STATE_TOO_WIDE.size)
        if (entry.isCurrentProgram) mergeDrawableStates(states, STATE_CURRENT_PROGRAM)
        if (entry.width > maxWidthForRipple) mergeDrawableStates(states, STATE_TOO_WIDE)
        return states
    }

    fun setValues(programGuide: ProgramGuide, entry: TableEntry, selectedGenreId: Int, fromUtcMillis: Long, toUtcMillis: Long, gapTitle: String) {
        this.programGuide = programGuide
        tableEntry = entry
        layoutParams?.let {
            it.width = entry.width
            layoutParams = it
        }
        var title = if (entry.isGap) gapTitle else entry.program?.title
        if (title.isNullOrEmpty()) title = resources.getString(R.string.program_title_for_no_information)
        updateText(selectedGenreId, title)
        updateIcons()
        updateContentDescription(title)
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        textWidth = measuredWidth - paddingStart - paddingEnd
        // Breiter als der sichtbare Bereich: kein Ripple
        maxWidthForRipple = GuideUtils.convertMillisToPixel(fromUtcMillis, toUtcMillis)
    }

    private fun isEntryWideEnough() = (tableEntry?.width ?: 0) >= visibleThreshold

    /** Titel (+ Folge); Sendungen außerhalb des Genre-Filters ausgegraut. */
    private fun updateText(selectedGenreId: Int, title: String) {
        val entry = tableEntry
        if (entry == null || !isEntryWideEnough()) {
            text = null
            return
        }
        var episode = entry.program?.getEpisodeDisplayTitle(context)
        var titleStyle = grayedOutProgramTitleStyle
        var episodeStyle = grayedOutEpisodeTitleStyle
        if (entry.isGap) {
            episode = null
        } else if (entry.hasGenre(selectedGenreId)) {
            titleStyle = programTitleStyle
            episodeStyle = episodeTitleStyle
        }
        val description = SpannableStringBuilder().append(title)
        if (!episode.isNullOrEmpty()) {
            // Zero-Width-Joiner verhindert, dass leere Zeile verschluckt wird
            description.append('\n').append('\u200D')
            val middle = description.length
            description.append(episode)
            description.setSpan(titleStyle, 0, middle, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            description.setSpan(episodeStyle, middle, description.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            description.setSpan(titleStyle, 0, description.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text = description
    }

    private fun updateIcons() {
        var iconResId = 0
        val schedule = tableEntry?.scheduledRecording
        if (isEntryWideEnough() && schedule != null) {
            iconResId = if (dvrManager?.isConflicting(schedule) == true) R.drawable.quantum_ic_warning_white_18
            else when (schedule.state) {
                ScheduledRecording.STATE_RECORDING_NOT_STARTED -> R.drawable.ic_scheduled_recording
                ScheduledRecording.STATE_RECORDING_IN_PROGRESS -> R.drawable.ic_recording_program
                else -> 0
            }
        }
        setCompoundDrawablePadding(if (iconResId != 0) iconPadding else 0)
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, iconResId, 0)
    }

    /** Vorlesetext: Nummer, Titel, Zeit, Folge, Aufnahme-Status, Sperre/Beschreibung. */
    private fun updateContentDescription(title: String) {
        val entry = tableEntry ?: return
        val sb = StringBuilder()
        channelDataManager.getChannel(entry.channelId)?.let { sb.append(it.displayNumber).append(' ') }
        sb.append(title)
        val program = entry.program
        if (program != null) {
            sb.append(' ').append(program.getDurationString(context))
            program.getEpisodeContentDescription(context)?.takeIf { it.isNotEmpty() }?.let { sb.append(' ').append(it) }
        } else {
            sb.append(' ').append(Utils.getDurationString(context, clock, entry.entryStartUtcMillis, entry.entryEndUtcMillis, true))
        }
        entry.scheduledRecording?.let { schedule ->
            val extra = if (dvrManager?.isConflicting(schedule) == true) resources.getString(R.string.dvr_epg_program_recording_conflict)
            else when (schedule.state) {
                ScheduledRecording.STATE_RECORDING_NOT_STARTED -> resources.getString(R.string.dvr_epg_program_recording_scheduled)
                ScheduledRecording.STATE_RECORDING_IN_PROGRESS -> resources.getString(R.string.dvr_epg_program_recording_in_progress)
                else -> null
            }
            extra?.let { sb.append(' ').append(it) }
        }
        if (entry.isBlocked) {
            sb.append(' ').append(resources.getString(R.string.program_guide_content_locked))
        } else {
            program?.description?.takeIf { it.isNotEmpty() }?.let { sb.append(' ').append(it) }
        }
        contentDescription = sb.toString()
    }

    /** Text im sichtbaren Teil halten, wenn der Eintrag links/rechts abgeschnitten ist. */
    fun updateVisibleArea() {
        val parentView = parent as? View ?: return
        if (layoutDirection == LAYOUT_DIRECTION_LTR) layoutVisibleArea(parentView.left - left, right - parentView.right)
        else layoutVisibleArea(right - parentView.right, parentView.left - left)
    }

    private fun layoutVisibleArea(startOffset: Int, endOffset: Int) {
        val width = tableEntry?.width ?: return
        var startPadding = max(0, startOffset)
        var endPadding = max(0, endOffset)
        val minWidth = min(width, textWidth + 2 * itemPadding)
        if (startPadding > 0 && width - startPadding < minWidth) startPadding = max(0, width - minWidth)
        if (endPadding > 0 && width - endPadding < minWidth) endPadding = max(0, width - minWidth)
        if (startPadding + itemPadding != paddingStart || endPadding + itemPadding != paddingEnd) {
            preventParentRelayout = true // Größe bleibt gleich, Eltern nicht neu layouten
            setPaddingRelative(startPadding + itemPadding, 0, endPadding + itemPadding, 0)
            preventParentRelayout = false
        }
    }

    fun clearValues() {
        handler?.removeCallbacks(updateFocus)
        tag = null
        programGuide = null
        tableEntry = null
    }

    override fun requestLayout() {
        if (preventParentRelayout) forceLayout() else super.requestLayout()
    }

    companion object {
        private val FOCUS_UPDATE_FREQUENCY = TimeUnit.SECONDS.toMillis(1)
        private const val MAX_PROGRESS = 10000
        private val STATE_CURRENT_PROGRAM = intArrayOf(R.attr.state_current_program)
        private val STATE_TOO_WIDE = intArrayOf(R.attr.state_program_too_wide)
        private var visibleThreshold = 0
        private var itemPadding = 0
        private var iconPadding = 0
        private lateinit var programTitleStyle: TextAppearanceSpan
        private lateinit var grayedOutProgramTitleStyle: TextAppearanceSpan
        private lateinit var episodeTitleStyle: TextAppearanceSpan
        private lateinit var grayedOutEpisodeTitleStyle: TextAppearanceSpan

        private fun getProgress(clock: Clock, start: Long, end: Long): Int {
            val now = clock.currentTimeMillis()
            return when {
                now <= start -> 0
                now >= end -> MAX_PROGRESS
                else -> ((now - start) * MAX_PROGRESS / (end - start)).toInt()
            }
        }

        /** Level der Ebene [id] in allen Zuständen/Ebenen setzen (ab Android 10 öffentliche API). */
        private fun setProgress(drawable: Drawable?, id: Int, progress: Int) {
            when (drawable) {
                is StateListDrawable -> for (i in 0 until drawable.stateCount) setProgress(drawable.getStateDrawable(i), id, progress)
                is LayerDrawable -> for (i in 0 until drawable.numberOfLayers) {
                    setProgress(drawable.getDrawable(i), id, progress)
                    if (drawable.getId(i) == id) drawable.getDrawable(i).level = progress
                }
            }
        }
    }
}
