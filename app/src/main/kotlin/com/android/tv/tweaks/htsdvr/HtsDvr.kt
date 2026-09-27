package com.android.tv.tweaks.htsdvr

/**
 * Tweak: Tvheadend-DVR. Anbindung an den DVR des HTS-Plugins (com.gravarty.hts): Der Server plant
 * und nimmt selbst auf, Live Channels liest und legt Timer über den DVR-Provider an
 * ([com.gravarty.htsp.tvinput.HtspDvrContract]).
 */
object HtsDvr {
    private const val INPUT_ID_PREFIX = "com.gravarty.hts/"

    /** Eingang gehört zum HTS-Plugin. */
    @JvmStatic
    fun isHtsInput(inputId: String?): Boolean = inputId?.startsWith(INPUT_ID_PREFIX) == true
}
