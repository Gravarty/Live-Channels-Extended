package com.android.tv.guide

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.tv.data.api.Channel
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** Eine Kanalzeile in der Programmübersicht; steuert das horizontale Scrollen per Fokus. */
class ProgramRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : TimelineGridView(context, attrs, defStyle) {

    fun interface ChildFocusListener {
        fun onChildFocus(oldFocus: View?, newFocus: View?)
    }

    private lateinit var programGuide: ProgramGuide
    private lateinit var programManager: ProgramManager
    private var keepFocusToCurrentProgram = false
    private var childFocusListener: ChildFocusListener? = null
    private var channel: Channel? = null

    private val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            viewTreeObserver.removeOnGlobalLayoutListener(this)
            updateChildVisibleArea()
        }
    }

    init {
        setAccessibilityDelegateCompat(ProgramRowAccessibilityDelegate(this))
    }

    fun setChildFocusListener(listener: ChildFocusListener?) { childFocusListener = listener }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        val itemView = child as ProgramItemView
        if (left <= itemView.right && itemView.left <= right) itemView.updateVisibleArea()
    }

    override fun onScrolled(dx: Int, dy: Int) {
        // Scrollen ersetzt das Warten auf das Layout
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        super.onScrolled(dx, dy)
        updateChildVisibleArea()
    }

    /** Fokus auf die laufende Sendung (sonst die erste). */
    fun focusCurrentProgram() {
        val currentProgram = getCurrentProgramView() ?: getChildAt(0)
        childFocusListener?.onChildFocus(null, currentProgram)
    }

    private fun isDirectionStart(direction: Int) =
        if (layoutDirection == LAYOUT_DIRECTION_LTR) direction == FOCUS_LEFT else direction == FOCUS_RIGHT

    private fun isDirectionEnd(direction: Int) =
        if (layoutDirection == LAYOUT_DIRECTION_LTR) direction == FOCUS_RIGHT else direction == FOCUS_LEFT

    /** Screenreader: bei Fokus am rechten Rand weiter scrollen. */
    internal fun focusSearchAccessibility(focused: View, direction: Int) {
        val entry = (focused as ProgramItemView).tableEntry ?: return
        val toMillis = programManager.toUtcMillis
        if ((isDirectionEnd(direction) || direction == FOCUS_FORWARD) && entry.entryEndUtcMillis >= toMillis) {
            scrollByTime(entry.entryEndUtcMillis - toMillis + HALF_HOUR_MILLIS)
        }
    }

    /** Links/Rechts: erst innerhalb langer Sendungen scrollen, dann zur nächsten Sendung wechseln. */
    override fun focusSearch(focused: View, direction: Int): View? {
        val focusedEntry = (focused as ProgramItemView).tableEntry ?: return super.focusSearch(focused, direction)
        val fromMillis = programManager.fromUtcMillis
        val toMillis = programManager.toUtcMillis
        if (!programGuide.isAccessibilityEnabled() && (isDirectionStart(direction) || direction == FOCUS_BACKWARD)) {
            if (focusedEntry.entryStartUtcMillis < fromMillis) {
                // Anfang der Sendung ist nicht sichtbar: zurückscrollen
                scrollByTime(max(-ONE_HOUR_MILLIS, focusedEntry.entryStartUtcMillis - fromMillis))
                return focused
            }
        } else if (isDirectionEnd(direction) || direction == FOCUS_FORWARD) {
            if (focusedEntry.entryEndUtcMillis >= toMillis + ONE_HOUR_MILLIS) {
                // Ende liegt mehr als 1 h rechts: weiterscrollen
                scrollByTime(ONE_HOUR_MILLIS)
                return focused
            }
        }
        val target = super.focusSearch(focused, direction)
        if (target !is ProgramItemView) {
            if ((isDirectionEnd(direction) || direction == FOCUS_FORWARD) && focusedEntry.entryEndUtcMillis != toMillis) {
                // Rest der Sendung sichtbar machen
                scrollByTime(focusedEntry.entryEndUtcMillis - toMillis)
                return focused
            }
            return target
        }
        val targetEntry = target.tableEntry ?: return target
        if (isDirectionStart(direction) || direction == FOCUS_BACKWARD) {
            if (programGuide.isAccessibilityEnabled()) {
                scrollByTime(targetEntry.entryStartUtcMillis - fromMillis)
            } else if (targetEntry.entryStartUtcMillis < fromMillis && targetEntry.entryEndUtcMillis < fromMillis + HALF_HOUR_MILLIS) {
                scrollByTime(max(-ONE_HOUR_MILLIS, targetEntry.entryStartUtcMillis - fromMillis))
            }
        } else if (isDirectionEnd(direction) || direction == FOCUS_FORWARD) {
            if (targetEntry.entryStartUtcMillis > fromMillis + ONE_HOUR_MILLIS + HALF_HOUR_MILLIS) {
                scrollByTime(min(ONE_HOUR_MILLIS, targetEntry.entryStartUtcMillis - fromMillis - ONE_HOUR_MILLIS))
            }
        }
        return target
    }

    private fun scrollByTime(timeToScroll: Long) = programManager.shiftTime(timeToScroll)

    override fun onChildDetachedFromWindow(child: View) {
        if (child.hasFocus()) {
            // Fokussierter Eintrag verschwindet: Fokus neu vergeben
            val entry = (child as ProgramItemView).tableEntry
            if (entry?.program == null) post { requestFocus() } else if (entry.isCurrentProgram) keepFocusToCurrentProgram = true
        }
        super.onChildDetachedFromWindow(child)
    }

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        if (keepFocusToCurrentProgram && (child as ProgramItemView).tableEntry?.isCurrentProgram == true) {
            keepFocusToCurrentProgram = false
            post { requestFocus() }
        }
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val programGrid = programGuide.programGrid
        val focusRange = programGrid.focusRange
        GuideUtils.findNextFocusedProgram(this, focusRange.lower, focusRange.upper, programGrid.isKeepCurrentProgramFocused)
            ?.let { return it.requestFocus() }
        val result = super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
        if (!result) {
            // Notfalls irgendeinen fokussierbaren Eintrag nehmen
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.isShown && child.hasFocusable()) return child.requestFocus()
            }
        }
        return result
    }

    private fun getCurrentProgramView(): View? =
        (0 until childCount).map { getChildAt(it) }.firstOrNull { (it as ProgramItemView).tableEntry?.isCurrentProgram == true }

    fun setChannel(channel: Channel?) { this.channel = channel }

    fun setProgramGuide(programGuide: ProgramGuide) {
        this.programGuide = programGuide
        programManager = programGuide.programManager
    }

    /** Scrollt so, dass die Sendung zur Zeitposition [scrollOffset] am Anfang steht. */
    fun resetScroll(scrollOffset: Int) {
        val startTime = GuideUtils.convertPixelToMillis(scrollOffset) + programManager.startTime
        val ch = channel
        val position = if (ch == null) -1 else programManager.getProgramIndexAtTime(ch.id, startTime)
        if (position < 0) {
            layoutManager!!.scrollToPosition(0)
        } else {
            val entry = programManager.getTableEntry(ch!!.id, position)
            val offset = GuideUtils.convertMillisToPixel(programManager.startTime, entry.entryStartUtcMillis) - scrollOffset
            (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, offset)
            // Nach dem Layout sichtbare Bereiche aktualisieren
            viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }
    }

    private fun updateChildVisibleArea() {
        for (i in 0 until childCount) {
            val child = getChildAt(i) as ProgramItemView
            if (left < child.right && child.left < right) child.updateVisibleArea()
        }
    }

    companion object {
        private val ONE_HOUR_MILLIS = TimeUnit.HOURS.toMillis(1)
        private val HALF_HOUR_MILLIS = ONE_HOUR_MILLIS / 2
    }
}
