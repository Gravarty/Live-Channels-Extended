package com.android.tv.guide

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.TimeUnit

/** Umrechnung Zeit↔Pixel und Fokus-Suche in der Programmübersicht. */
internal object GuideUtils {
    private const val INVALID_INDEX = -1
    private var widthPerHour = 0

    fun setWidthPerHour(value: Int) { widthPerHour = value }

    fun convertMillisToPixel(millis: Long): Int = (millis * widthPerHour / TimeUnit.HOURS.toMillis(1)).toInt()

    fun convertMillisToPixel(startMillis: Long, endMillis: Long): Int =
        convertMillisToPixel(endMillis) - convertMillisToPixel(startMillis)

    fun convertPixelToMillis(pixel: Int): Long = pixel * TimeUnit.HOURS.toMillis(1) / widthPerHour

    /**
     * Nächster Fokus in einer Zeile: aktuelle Sendung (optional), sonst die, die den Bereich
     * ganz enthält, dann die breiteste ganz enthaltene, dann die mit größter Überlappung.
     */
    fun findNextFocusedProgram(programRow: View, focusRangeLeft: Int, focusRangeRight: Int, keepCurrentProgramFocused: Boolean): View? {
        val focusables = ArrayList<View>()
        findFocusables(programRow, focusables)
        if (keepCurrentProgramFocused) {
            focusables.firstOrNull { it is ProgramItemView && isCurrentProgram(it) }?.let { return it }
        }
        var maxFullyOverlappedWidth = Int.MIN_VALUE
        var maxPartiallyOverlappedWidth = Int.MIN_VALUE
        var nextFocusIndex = INVALID_INDEX
        for ((i, focusable) in focusables.withIndex()) {
            val rect = Rect()
            focusable.getGlobalVisibleRect(rect)
            if (rect.left <= focusRangeLeft && focusRangeRight <= rect.right) {
                return focusable
            } else if (focusRangeLeft <= rect.left && rect.right <= focusRangeRight) {
                if (rect.width() > maxFullyOverlappedWidth) {
                    nextFocusIndex = i
                    maxFullyOverlappedWidth = rect.width()
                }
            } else if (maxFullyOverlappedWidth == Int.MIN_VALUE) {
                val overlappedWidth = if (focusRangeLeft <= rect.left) focusRangeRight - rect.left else rect.right - focusRangeLeft
                if (overlappedWidth > maxPartiallyOverlappedWidth) {
                    nextFocusIndex = i
                    maxPartiallyOverlappedWidth = overlappedWidth
                }
            }
        }
        return if (nextFocusIndex != INVALID_INDEX) focusables[nextFocusIndex] else null
    }

    fun isCurrentProgram(view: ProgramItemView): Boolean = view.tableEntry?.isCurrentProgram == true

    fun isDescendant(container: ViewGroup, view: View?): Boolean {
        var p = view?.parent
        while (p != null) {
            if (p === container) return true
            p = p.parent
        }
        return false
    }

    private fun findFocusables(v: View, out: ArrayList<View>) {
        if (v.isFocusable) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) findFocusables(v.getChildAt(i), out)
    }
}
