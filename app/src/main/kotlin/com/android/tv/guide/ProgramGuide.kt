package com.android.tv.guide

import android.animation.Animator
import android.animation.AnimatorInflater
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener
import androidx.leanback.widget.VerticalGridView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.android.tv.ChannelTuner
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.data.ChannelDataManager
import com.android.tv.data.GenreItems
import com.android.tv.data.ProgramDataManager
import com.android.tv.dvr.DvrDataManager
import com.android.tv.dvr.DvrScheduleManager
import com.android.tv.tweaks.Tweaks
import com.android.tv.ui.HardwareLayerAnimatorListenerAdapter
import com.android.tv.ui.ViewUtils
import com.android.tv.ui.hideable.AutoHideScheduler
import com.android.tv.util.TvInputManagerHelper
import com.android.tv.util.Utils
import java.util.concurrent.TimeUnit

/**
 * Programmführer (EPG): Genre-Leiste, Zeitleiste, Kanalraster mit Detailzeile, Anzeige in
 * Teil- oder Vollansicht. Entfällt: Suche-Button (TvFeatures.EPG_SEARCH ist im Original OFF),
 * Tracker/PerformanceMonitor.
 */
class ProgramGuide(
    private val activity: MainActivity,
    private val channelTuner: ChannelTuner,
    tvInputManagerHelper: TvInputManagerHelper,
    channelDataManager: ChannelDataManager,
    programDataManager: ProgramDataManager,
    dvrDataManager: DvrDataManager?,
    dvrScheduleManager: DvrScheduleManager?,
    private val preShowRunnable: Runnable?,
    private val postHideRunnable: Runnable?,
) : ProgramGrid.ChildFocusListener, AccessibilityStateChangeListener {

    val programManager = ProgramManager(tvInputManagerHelper, channelDataManager, programDataManager, dvrDataManager, dvrScheduleManager)
    private val res = activity.resources
    private val widthPerHour = res.getDimensionPixelSize(R.dimen.program_guide_table_width_per_hour)
    private var viewPortMillis = 0L
    private val rowHeight = res.getDimensionPixelSize(R.dimen.program_guide_table_item_row_height)
    private val detailHeight = res.getDimensionPixelSize(R.dimen.program_guide_table_detail_height)
    private val selectionRow = res.getInteger(R.integer.program_guide_selection_row)
    private val tableFadeAnimDuration = res.getInteger(R.integer.program_guide_table_detail_fade_anim_duration).toLong()
    private val showDurationMillis = res.getInteger(R.integer.program_guide_show_duration).toLong()
    private val animationDuration = res.getInteger(R.integer.program_guide_table_detail_toggle_anim_duration).toLong()
    private val detailPadding = res.getDimensionPixelOffset(R.dimen.program_guide_table_detail_padding)
    private var currentTimeIndicatorWidth = 0
    private val container: View = activity.findViewById(R.id.program_guide)
    private val sidePanel: View = container.findViewById(R.id.program_guide_side_panel)
    val sidePanelGridView: VerticalGridView = container.findViewById(R.id.program_guide_side_panel_grid_view)
    private val table: View = container.findViewById(R.id.program_guide_table)
    private val timelineRow: TimelineRow = table.findViewById(R.id.time_row)
    val programGrid: ProgramGrid = table.findViewById(R.id.grid)
    private val timeListAdapter = TimeListAdapter(res)
    private val currentTimeIndicator: View = table.findViewById(R.id.current_time_indicator)
    private val showAnimatorFull: Animator
    private val showAnimatorPartial: Animator
    private val hideAnimatorFull: Animator
    private val hideAnimatorPartial: Animator
    private val partialToFullAnimator: Animator
    private val fullToPartialAnimator: Animator
    private val programTableFadeOutAnimator: Animator
    private val programTableFadeInAnimator: Animator
    private val sharedPreference = PreferenceManager.getDefaultSharedPreferences(activity)
    private val accessibilityManager = activity.getSystemService(AccessibilityManager::class.java)
    private var showGuidePartial: Boolean
    private var selectedRow: View? = null
    private var detailOutAnimator: Animator? = null
    private var detailInAnimator: Animator? = null
    private var startUtcTime = 0L
    private var timelineAnimation = false
    private var lastRequestedGenreId = GenreItems.ID_ALL_CHANNELS
    private var isDuringResetRowSelection = false
    // Tweak: Genre-Leiste ausgebaut (wird bei jedem show() gelesen)
    private var genrePanelHidden = false
    private val handler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == MSG_PROGRAM_TABLE_FADE_IN_ANIM) programTableFadeInAnimator.start()
        true
    }
    var isActive = false
        private set
    private val autoHideScheduler = AutoHideScheduler(activity) { hide() }
    private var onLayoutListenerForShow: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val programManagerListener = object : ProgramManager.ListenerAdapter() {
        override fun onTimeRangeUpdated() {
            val scrollOffset = (widthPerHour * programManager.shiftedTime / HOUR_IN_MILLIS).toInt()
            timelineRow.scrollTo(scrollOffset, timelineAnimation)
        }
    }
    private val updateTimeIndicator: Runnable = object : Runnable {
        override fun run() {
            positionCurrentTimeIndicator()
            handler.postAtTime(this, Utils.ceilTime(SystemClock.uptimeMillis(), TIME_INDICATOR_UPDATE_FREQUENCY))
        }
    }

    init {
        GuideUtils.setWidthPerHour(widthPerHour)
        updateViewPortMillis()

        container.viewTreeObserver.addOnGlobalFocusChangeListener(GlobalFocusChangeListener())
        sidePanelGridView.recycledViewPool.setMaxRecycledViews(R.layout.program_guide_side_panel_row,
            res.getInteger(R.integer.max_recycled_view_pool_epg_side_panel_row))
        sidePanelGridView.adapter = GenreListAdapter(activity, programManager, this)
        sidePanelGridView.windowAlignment = VerticalGridView.WINDOW_ALIGN_NO_EDGE
        sidePanelGridView.windowAlignmentOffset = res.getDimensionPixelOffset(R.dimen.program_guide_side_panel_alignment_y)
        sidePanelGridView.windowAlignmentOffsetPercent = VerticalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED

        timelineRow.recycledViewPool.setMaxRecycledViews(R.layout.program_guide_table_header_row_item,
            res.getInteger(R.integer.max_recycled_view_pool_epg_header_row_item))
        timelineRow.adapter = timeListAdapter

        val programTableAdapter = ProgramTableAdapter(activity, this)
        programTableAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                // Neue Kanalliste: Auswahl zurücksetzen und Höhe anpassen
                resetRowSelection()
                updateGuidePosition()
            }
        })
        programGrid.initialize(programManager)
        programGrid.recycledViewPool.setMaxRecycledViews(R.layout.program_guide_table_row,
            res.getInteger(R.integer.max_recycled_view_pool_epg_table_row))
        programGrid.adapter = programTableAdapter
        programGrid.setChildFocusListener(this)
        programGrid.setOnChildSelectedListener { _, view, _, _ ->
            if (isDuringResetRowSelection) {
                // Auswahl wurde nur zurückgesetzt
                isDuringResetRowSelection = false
                return@setOnChildSelectedListener
            }
            selectRow(view)
        }
        programGrid.focusScrollStrategy = VerticalGridView.FOCUS_SCROLL_ALIGNED
        programGrid.windowAlignmentOffset = selectionRow * rowHeight
        programGrid.windowAlignmentOffsetPercent = VerticalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        programGrid.itemAlignmentOffset = 0
        programGrid.itemAlignmentOffsetPercent = VerticalGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED
        timelineRow.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = onHorizontalScrolled(dx)
        })

        showAnimatorFull = createAnimator(R.animator.program_guide_side_panel_enter_full, 0, R.animator.program_guide_table_enter_full)
        showAnimatorPartial = createAnimator(R.animator.program_guide_side_panel_enter_partial, 0, R.animator.program_guide_table_enter_partial)
        showAnimatorPartial.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                sidePanelGridView.visibility = View.VISIBLE
                sidePanelGridView.alpha = 1.0f
            }
        })
        val hideEnd = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { container.visibility = View.GONE }
        }
        hideAnimatorFull = createAnimator(R.animator.program_guide_side_panel_exit, 0, R.animator.program_guide_table_exit).apply { addListener(hideEnd) }
        hideAnimatorPartial = createAnimator(R.animator.program_guide_side_panel_exit, 0, R.animator.program_guide_table_exit).apply { addListener(hideEnd) }
        partialToFullAnimator = createAnimator(R.animator.program_guide_side_panel_hide,
            R.animator.program_guide_side_panel_grid_fade_out, R.animator.program_guide_table_partial_to_full)
        fullToPartialAnimator = createAnimator(R.animator.program_guide_side_panel_reveal,
            R.animator.program_guide_side_panel_grid_fade_in, R.animator.program_guide_table_full_to_partial)

        programTableFadeOutAnimator = AnimatorInflater.loadAnimator(activity, R.animator.program_guide_table_fade_out).apply {
            setTarget(table)
            addListener(object : HardwareLayerAnimatorListenerAdapter(table) {
                override fun onAnimationEnd(animator: Animator) {
                    super.onAnimationEnd(animator)
                    if (!isActive) return
                    // Nach dem Ausblenden Genre wechseln und wieder einblenden
                    programManager.resetChannelListWithGenre(lastRequestedGenreId)
                    resetTimelineScroll()
                    if (!handler.hasMessages(MSG_PROGRAM_TABLE_FADE_IN_ANIM)) handler.sendEmptyMessage(MSG_PROGRAM_TABLE_FADE_IN_ANIM)
                }
            })
        }
        programTableFadeInAnimator = AnimatorInflater.loadAnimator(activity, R.animator.program_guide_table_fade_in).apply {
            setTarget(table)
            addListener(HardwareLayerAnimatorListenerAdapter(table))
        }
        // Mit Screenreader immer Teilansicht (mit Genre-Leiste)
        showGuidePartial = accessibilityManager.isEnabled || sharedPreference.getBoolean(KEY_SHOW_GUIDE_PARTIAL, true)
    }

    /** Fokuszeile beim Hoch/Runter an der richtigen Höhe halten (Detailzeile beachten). */
    override fun onRequestChildFocus(oldFocus: View?, newFocus: View?) {
        if (oldFocus == null || newFocus == null) return
        val selectionRowOffset = selectionRow * rowHeight
        if (oldFocus.top < newFocus.top) {
            programGrid.windowAlignmentOffset = selectionRowOffset + rowHeight + detailHeight
            programGrid.itemAlignmentOffsetPercent = 100f
        } else if (oldFocus.top > newFocus.top) {
            programGrid.windowAlignmentOffset = selectionRowOffset
            programGrid.itemAlignmentOffsetPercent = 0f
        }
    }

    /** Zeigt den Führer; [runnableAfterAnimatorReady] läuft, bevor die Einblend-Animation startet. */
    fun show(runnableAfterAnimatorReady: Runnable) {
        if (container.visibility == View.VISIBLE) return
        preShowRunnable?.run()
        programManager.programGuideVisibilityChanged(true)
        applyGenrePanelTweak()
        startUtcTime = Utils.floorTime(System.currentTimeMillis() - MIN_DURATION_FROM_START_TIME_TO_CURRENT_TIME, HALF_HOUR_IN_MILLIS)
        programManager.updateInitialTimeRange(startUtcTime, startUtcTime + viewPortMillis)
        programManager.addListener(programManagerListener)
        lastRequestedGenreId = GenreItems.ID_ALL_CHANNELS
        timeListAdapter.update(startUtcTime)
        timelineRow.resetScroll()
        container.visibility = View.VISIBLE
        isActive = true
        if (!showGuidePartial) table.requestFocus()
        positionCurrentTimeIndicator()
        sidePanelGridView.selectedPosition = 0
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                // Hardware-Layer für flüssige Einblendung
                table.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                sidePanelGridView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                table.buildLayer()
                sidePanelGridView.buildLayer()
                onLayoutListenerForShow = null
                timelineAnimation = true
                startCurrentTimeIndicator(TIME_INDICATOR_UPDATE_FREQUENCY)
                updateGuidePosition()
                runnableAfterAnimatorReady.run()
                if (showGuidePartial) showAnimatorPartial.start() else showAnimatorFull.start()
            }
        }
        onLayoutListenerForShow = listener
        container.viewTreeObserver.addOnGlobalLayoutListener(listener)
        scheduleHide()
    }

    fun hide() {
        if (!isActive) return
        onLayoutListenerForShow?.let {
            container.viewTreeObserver.removeOnGlobalLayoutListener(it)
            onLayoutListenerForShow = null
        }
        cancelHide()
        programManager.programGuideVisibilityChanged(false)
        programManager.removeListener(programManagerListener)
        isActive = false
        if (!showGuidePartial) hideAnimatorFull.start() else hideAnimatorPartial.start()
        // Genre-Wechsel abbrechen
        if (programTableFadeOutAnimator.isRunning) programTableFadeOutAnimator.cancel()
        if (programTableFadeInAnimator.isRunning) programTableFadeInAnimator.cancel()
        handler.removeMessages(MSG_PROGRAM_TABLE_FADE_IN_ANIM)
        table.alpha = 1.0f
        timelineAnimation = false
        stopCurrentTimeIndicator()
        postHideRunnable?.run()
    }

    fun scheduleHide() = autoHideScheduler.schedule(showDurationMillis)
    fun cancelHide() = autoHideScheduler.cancel()
    fun onBackPressed() = hide()

    val isRunningAnimation: Boolean
        get() = showAnimatorPartial.isStarted || showAnimatorFull.isStarted || hideAnimatorPartial.isStarted || hideAnimatorFull.isStarted

    internal val isFull: Boolean get() = !showGuidePartial

    /** Genre wechseln: Tabelle aus- und nach dem Wechsel wieder einblenden. */
    internal fun requestGenreChange(genreId: Int) {
        if (lastRequestedGenreId == genreId) return
        lastRequestedGenreId = genreId
        if (programTableFadeOutAnimator.isStarted) {
            // Wechsel passiert am Ende des Ausblendens
            handler.removeMessages(MSG_PROGRAM_TABLE_FADE_IN_ANIM)
            handler.sendEmptyMessageDelayed(MSG_PROGRAM_TABLE_FADE_IN_ANIM, tableFadeAnimDuration)
            return
        }
        if (handler.hasMessages(MSG_PROGRAM_TABLE_FADE_IN_ANIM)) {
            // Schon ausgeblendet: sofort wechseln
            programManager.resetChannelListWithGenre(lastRequestedGenreId)
            handler.removeMessages(MSG_PROGRAM_TABLE_FADE_IN_ANIM)
            handler.sendEmptyMessageDelayed(MSG_PROGRAM_TABLE_FADE_IN_ANIM, tableFadeAnimDuration)
            return
        }
        if (programTableFadeInAnimator.isStarted) programTableFadeInAnimator.cancel()
        programTableFadeOutAnimator.start()
    }

    internal fun getTimelineRowScrollOffset(): Int = timelineRow.scrollOffset

    internal fun isAccessibilityEnabled(): Boolean = accessibilityManager.isEnabled

    /** Tabellenhöhe an die Kanalanzahl anpassen (wenige Kanäle: unten ausrichten). */
    private fun updateGuidePosition() {
        val screenHeight = container.height
        if (screenHeight <= 0) return
        val startPadding = tableStartPadding()
        val topPadding = res.getDimensionPixelOffset(R.dimen.program_guide_table_margin_top)
        val bottomPadding = res.getDimensionPixelOffset(R.dimen.program_guide_table_margin_bottom)
        val tableHeight = res.getDimensionPixelOffset(R.dimen.program_guide_table_header_row_height) + detailHeight +
            rowHeight * (programGrid.adapter?.itemCount ?: 0) + topPadding + bottomPadding
        val layoutParams = table.layoutParams
        if (tableHeight > screenHeight) {
            table.setPaddingRelative(startPadding, topPadding, 0, 0)
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            table.setPaddingRelative(startPadding, topPadding, 0, bottomPadding)
            layoutParams.height = tableHeight
        }
        table.layoutParams = layoutParams
    }

    private fun createAnimator(sidePanelAnimResId: Int, sidePanelGridAnimResId: Int, tableAnimResId: Int): Animator {
        val list = ArrayList<Animator>()
        list.add(AnimatorInflater.loadAnimator(activity, sidePanelAnimResId).apply { setTarget(sidePanel) })
        if (sidePanelGridAnimResId != 0) {
            list.add(AnimatorInflater.loadAnimator(activity, sidePanelGridAnimResId).apply {
                setTarget(sidePanelGridView)
                addListener(HardwareLayerAnimatorListenerAdapter(sidePanelGridView))
            })
        }
        list.add(AnimatorInflater.loadAnimator(activity, tableAnimResId).apply {
            setTarget(table)
            addListener(HardwareLayerAnimatorListenerAdapter(table))
        })
        return AnimatorSet().apply { playTogether(list) }
    }

    private fun startFull() {
        if (!showGuidePartial) return
        showGuidePartial = false
        sharedPreference.edit().putBoolean(KEY_SHOW_GUIDE_PARTIAL, false).apply()
        partialToFullAnimator.start()
    }

    private fun startPartial() {
        if (showGuidePartial || genrePanelHidden) return // Tweak: ohne Genre-Leiste immer Vollansicht
        showGuidePartial = true
        sharedPreference.edit().putBoolean(KEY_SHOW_GUIDE_PARTIAL, true).apply()
        fullToPartialAnimator.start()
    }

    /** Sichtbare Zeitspanne aus der Rasterbreite. */
    private fun updateViewPortMillis() {
        // Fensterbreite über WindowMetrics statt Display.getSize()
        val displayWidth = activity.windowManager.currentWindowMetrics.bounds.width()
        val gridWidth = displayWidth - tableStartPadding() -
            res.getDimensionPixelSize(R.dimen.program_guide_table_header_column_width)
        viewPortMillis = gridWidth * HOUR_IN_MILLIS / widthPerHour
    }

    // Tweak: ohne Genre-Leiste wächst die Tabelle nach links
    private fun tableStartPadding(): Int = res.getDimensionPixelOffset(
        if (genrePanelHidden) R.dimen.extended_guide_table_margin_start else R.dimen.program_guide_table_margin_start)

    /** Tweak: Genre-Leiste komplett aus dem Layout nehmen, Vollansicht erzwingen, Genre-Filter zurücksetzen. */
    private fun applyGenrePanelTweak() {
        genrePanelHidden = Tweaks.isGuideGenresHidden(activity)
        sidePanel.visibility = if (genrePanelHidden) View.GONE else View.VISIBLE
        showGuidePartial = !genrePanelHidden &&
            (accessibilityManager.isEnabled || sharedPreference.getBoolean(KEY_SHOW_GUIDE_PARTIAL, true))
        if (genrePanelHidden && programManager.selectedGenreId != GenreItems.ID_ALL_CHANNELS) {
            programManager.resetChannelListWithGenre(GenreItems.ID_ALL_CHANNELS)
        }
        table.setPaddingRelative(tableStartPadding(), table.paddingTop, table.paddingEnd, table.paddingBottom)
        updateViewPortMillis()
    }

    private fun startCurrentTimeIndicator(initialDelay: Long) = handler.postDelayed(updateTimeIndicator, initialDelay)
    private fun stopCurrentTimeIndicator() = handler.removeCallbacks(updateTimeIndicator)

    /** Linie der aktuellen Uhrzeit (verborgen, wenn links aus dem Bild gescrollt). */
    private fun positionCurrentTimeIndicator() {
        val offset = GuideUtils.convertMillisToPixel(startUtcTime, System.currentTimeMillis()) - timelineRow.scrollOffset
        if (offset < 0) {
            currentTimeIndicator.visibility = View.GONE
            return
        }
        if (currentTimeIndicatorWidth == 0) {
            currentTimeIndicator.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            currentTimeIndicatorWidth = currentTimeIndicator.measuredWidth
        }
        currentTimeIndicator.setPaddingRelative(offset - currentTimeIndicatorWidth / 2, 0, 0, 0)
        currentTimeIndicator.visibility = View.VISIBLE
    }

    private fun resetTimelineScroll() {
        if (programManager.fromUtcMillis != startUtcTime) {
            val animation = timelineAnimation
            timelineAnimation = false
            // Zum Anfang, ohne Animation
            programManager.shiftTime(startUtcTime - programManager.fromUtcMillis)
            timelineAnimation = animation
        }
    }

    /** Alle Zeilen synchron mit der Zeitleiste scrollen. */
    private fun onHorizontalScrolled(dx: Int) {
        positionCurrentTimeIndicator()
        for (i in 0 until programGrid.childCount) programGrid.getChildAt(i).findViewById<View>(R.id.row).scrollBy(dx, 0)
    }

    private fun resetRowSelection() {
        detailOutAnimator?.end()
        detailInAnimator?.cancel()
        selectedRow = null
        isDuringResetRowSelection = true
        programGrid.selectedPosition = maxOf(programManager.getChannelIndex(channelTuner.currentChannel), 0)
        programGrid.resetFocusState()
        programGrid.onItemSelectionReset()
        isDuringResetRowSelection = false
    }

    private fun selectRow(row: View?) {
        if (row == null || row === selectedRow) return
        val old = selectedRow
        if (old == null || programGrid.getChildAdapterPosition(old) == RecyclerView.NO_POSITION) {
            old?.findViewById<View>(R.id.detail)?.visibility = View.GONE
            val detailView = row.findViewById<View>(R.id.detail)
            detailView.findViewById<View>(R.id.detail_content_full).apply {
                alpha = 1f
                translationY = 0f
            }
            ViewUtils.setLayoutHeight(detailView, detailHeight)
            detailView.visibility = View.VISIBLE
            val programRow = row.findViewById<ProgramRow>(R.id.row)
            programRow.post { programRow.focusCurrentProgram() }
        } else {
            animateRowChange(old, row)
        }
        selectedRow = row
    }

    /** Detailzeile von der alten zur neuen Zeile "wandern" lassen. */
    private fun animateRowChange(outRow: View?, inRow: View?) {
        detailOutAnimator?.end()
        detailInAnimator?.cancel()
        val animationPadding = when (programGrid.lastUpDownDirection) {
            View.FOCUS_UP -> detailPadding
            View.FOCUS_DOWN -> -detailPadding
            else -> 0
        }.toFloat()
        val outDetail = outRow?.findViewById<View>(R.id.detail)
        if (outDetail != null && outDetail.isShown) {
            val outContent = outDetail.findViewById<View>(R.id.detail_content_full)
            val fadeOut = ObjectAnimator.ofPropertyValuesHolder(outContent,
                PropertyValuesHolder.ofFloat(View.ALPHA, outDetail.alpha, 0f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, outContent.translationY, animationPadding)).apply {
                startDelay = 0
                duration = animationDuration
                addListener(HardwareLayerAnimatorListenerAdapter(outContent))
            }
            val collapse = ViewUtils.createHeightAnimator(outDetail, ViewUtils.getLayoutHeight(outDetail), 0).apply {
                startDelay = animationDuration
                duration = tableFadeAnimDuration
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animator: Animator) { outContent.visibility = View.GONE }
                    override fun onAnimationEnd(animator: Animator) { outContent.visibility = View.VISIBLE }
                })
            }
            detailOutAnimator = AnimatorSet().apply {
                playTogether(fadeOut, collapse)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animator: Animator) { detailOutAnimator = null }
                })
                start()
            }
        }
        val inDetail = inRow?.findViewById<View>(R.id.detail) ?: return
        val inContent = inDetail.findViewById<View>(R.id.detail_content_full)
        val expand = ViewUtils.createHeightAnimator(inDetail, 0, detailHeight).apply {
            startDelay = animationDuration
            duration = tableFadeAnimDuration
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animator: Animator) { inContent.visibility = View.GONE }
                override fun onAnimationEnd(animator: Animator) {
                    inContent.visibility = View.VISIBLE
                    inContent.alpha = 0f
                }
            })
        }
        val fadeIn = ObjectAnimator.ofPropertyValuesHolder(inContent,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -animationPadding, 0f)).apply {
            duration = animationDuration
            addListener(HardwareLayerAnimatorListenerAdapter(inContent))
        }
        detailInAnimator = AnimatorSet().apply {
            playSequentially(expand, fadeIn)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) { detailInAnimator = null }
            })
            start()
        }
    }

    override fun onAccessibilityStateChanged(enabled: Boolean) = autoHideScheduler.onAccessibilityStateChanged(enabled)

    /** Fokus zwischen Genre-Leiste/Kanalspalte und Raster schaltet Teil-/Vollansicht. */
    private inner class GlobalFocusChangeListener : ViewTreeObserver.OnGlobalFocusChangeListener {
        override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
            if (!isActive) return
            val from = getLocation(oldFocus)
            val to = getLocation(newFocus)
            when {
                (from == SIDE_PANEL || from == CHANNEL_COLUMN) && to == PROGRAM_TABLE -> startFull()
                from == PROGRAM_TABLE && (to == SIDE_PANEL || to == CHANNEL_COLUMN) -> startPartial()
            }
        }

        private fun getLocation(view: View?): Int {
            var obj: Any? = view ?: return UNKNOWN
            while (obj is View) {
                if (obj === sidePanel) return SIDE_PANEL
                if (obj === programGrid) return if (view is ProgramItemView) PROGRAM_TABLE else CHANNEL_COLUMN
                obj = obj.parent
            }
            return UNKNOWN
        }
    }

    companion object {
        private const val KEY_SHOW_GUIDE_PARTIAL = "show_guide_partial"
        private val TIME_INDICATOR_UPDATE_FREQUENCY = TimeUnit.SECONDS.toMillis(1)
        private val HOUR_IN_MILLIS = TimeUnit.HOURS.toMillis(1)
        private val HALF_HOUR_IN_MILLIS = HOUR_IN_MILLIS / 2
        private val MIN_DURATION_FROM_START_TIME_TO_CURRENT_TIME = ProgramManager.FIRST_ENTRY_MIN_DURATION
        private const val MSG_PROGRAM_TABLE_FADE_IN_ANIM = 1000
        private const val UNKNOWN = 0
        private const val SIDE_PANEL = 1
        private const val PROGRAM_TABLE = 2
        private const val CHANNEL_COLUMN = 3
    }
}
