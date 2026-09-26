package com.android.tv.features

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.android.tv.R

/** Feature-Schalter des Originals, soweit sie für eine Nicht-System-App gelten. */
object TvFeatures {
    private var pip: Boolean? = null

    /** Bild-in-Bild, falls das Gerät es unterstützt. */
    @JvmStatic
    fun isPictureInPictureEnabled(context: Context): Boolean =
        pip ?: context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE).also { pip = it }

    @JvmStatic
    fun useGtvLiveTvV2(context: Context): Boolean = context.resources.getBoolean(R.bool.use_gtv_livetv_v2)

    /** Signalstärke-Anzeige: im Original nur in ENG-Builds. */
    const val TUNER_SIGNAL_STRENGTH = false

    /** App-Symbol immer zeigen: im Original für Nicht-System-Apps (ohne ACCESS_ALL_EPG_DATA). */
    @JvmStatic
    fun isUnhideEnabled(context: Context): Boolean =
        !com.android.tv.common.util.PermissionUtils.hasAccessAllEpg(context)

    /** DVR: im Original nur für System-Apps (SystemAppFeature). */
    @JvmStatic
    fun isDvrEnabled(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
}
