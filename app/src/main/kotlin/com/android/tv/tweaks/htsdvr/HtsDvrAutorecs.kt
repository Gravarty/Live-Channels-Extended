package com.android.tv.tweaks.htsdvr

import android.content.ContentValues
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.android.tv.R
import com.android.tv.data.api.Program
import com.gravarty.htsp.tvinput.HtspDvrContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tweak: Tvheadend-DVR. "Serie aufnehmen" legt auf dem Server einen Serien-Timer (`autorecs`,
 * EPG-Suche nach dem Titel) auf dem Sender der Sendung an, statt einer lokalen Serie.
 */
object HtsDvrAutorecs {
    private const val TAG = "HtsDvrAutorecs"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @JvmStatic
    fun addAutorec(context: Context, program: Program) {
        val appContext = context.applicationContext
        val title = program.title.orEmpty()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(HtspDvrContract.Autorec.NAME, title)
                    put(HtspDvrContract.Autorec.EPG_SEARCH, title)
                    put(HtspDvrContract.Autorec.TV_CHANNEL_ID, program.channelId)
                }
                try {
                    appContext.contentResolver.insert(HtspDvrContract.AUTORECS_URI, values) != null
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add autorec", e)
                    false
                }
            }
            Toast.makeText(appContext,
                if (ok) appContext.getString(R.string.tweak_tvheadend_dvr_series_added, title)
                else appContext.getString(R.string.tweak_tvheadend_dvr_error),
                Toast.LENGTH_SHORT).show()
        }
    }
}
