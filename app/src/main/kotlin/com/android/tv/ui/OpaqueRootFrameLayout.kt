package com.android.tv.ui

import android.content.Context
import android.graphics.Region
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Bugfix: Wurzel von activity_tv. Wegen der SurfaceView (TvView) meldet Android SurfaceFlinger eine
 * "transparente Region"; SurfaceFlinger schneidet das App-Fenster auf den Rest zu. Die Region fiel oben
 * zu knapp aus, Kanal-Banner und Senderliste waren oben abgeschnitten (per dumpsys SurfaceFlinger belegt:
 * sourceCrop beginnt unterhalb der Oberkante). Das Fenster gilt jetzt als komplett nicht-transparent.
 */
class OpaqueRootFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun gatherTransparentRegion(region: Region?): Boolean {
        super.gatherTransparentRegion(region)
        region?.setEmpty()
        return true
    }
}
