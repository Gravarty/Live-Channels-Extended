package com.android.tv.guide

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** Horizontale Liste ohne Fokus-Scroll (Zeitleiste/Programmzeile). */
open class TimelineGridView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {
    init {
        layoutManager = object : LinearLayoutManager(context, HORIZONTAL, false) {
            // Scrollen übernimmt die Programmübersicht selbst
            override fun onRequestChildFocus(parent: RecyclerView, state: State, child: View, focused: View?) = true
        }
        isFocusable = false
        setItemViewCacheSize(0)
    }
}

/** Zeitleiste über der Programmübersicht (merkt sich die Scrollposition). */
class TimelineRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : TimelineGridView(context, attrs, defStyle) {
    private var scrollPosition = 0

    fun resetScroll() = layoutManager!!.scrollToPosition(0)

    val scrollOffset: Int get() = abs(scrollPosition)

    fun scrollTo(scrollOffset: Int, smoothScroll: Boolean) {
        val dx = (scrollOffset - this.scrollOffset) * if (layoutDirection == LAYOUT_DIRECTION_LTR) 1 else -1
        if (smoothScroll) smoothScrollBy(dx, 0) else scrollBy(dx, 0)
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        // Nach RTL-Wechsel Position neu anwenden
        if (isAttachedToWindow) scrollTo(scrollOffset, false)
    }

    override fun onScrolled(dx: Int, dy: Int) {
        // dx=dy=0 bei Layout-Reset
        if (dx == 0 && dy == 0) scrollPosition = 0 else scrollPosition += dx
    }

    override fun getLeftFadingEdgeStrength() = if (layoutDirection == LAYOUT_DIRECTION_LTR) FADING_EDGE_STRENGTH_START else 0f
    override fun getRightFadingEdgeStrength() = if (layoutDirection == LAYOUT_DIRECTION_RTL) FADING_EDGE_STRENGTH_START else 0f

    companion object {
        private const val FADING_EDGE_STRENGTH_START = 1.0f
    }
}
