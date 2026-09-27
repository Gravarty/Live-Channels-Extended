package com.android.tv.tweaks.htsdvr

import android.content.ContentUris
import android.content.Context
import android.media.tv.TvContract
import android.util.Log
import android.widget.Toast
import com.android.tv.R
import com.android.tv.dvr.data.RecordedProgram
import com.gravarty.htsp.tvinput.HtspDvrContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tweak: Tvheadend-DVR. Aufnahmen des HTS-Plugins werden über `recordings/<id>` auf dem Server
 * gelöscht, nie direkt in RecordedPrograms (das Plugin würde die Zeile sonst wieder anlegen).
 * Die DVR-ID steht als Text in RecordedPrograms.COLUMN_INTERNAL_PROVIDER_DATA.
 */
object HtsDvrRecordings {
    private const val TAG = "HtsDvrRecordings"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @JvmStatic
    fun deleteRecordings(context: Context, recordedPrograms: List<RecordedProgram>) {
        if (recordedPrograms.isEmpty()) return
        val appContext = context.applicationContext
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                recordedPrograms.map { deleteRecording(appContext, it) }.all { it }
            }
            if (!ok) Toast.makeText(appContext, R.string.tweak_tvheadend_dvr_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRecording(context: Context, recordedProgram: RecordedProgram): Boolean = try {
        val dvrId = context.contentResolver.query(recordedProgram.uri,
            arrayOf(TvContract.RecordedPrograms.COLUMN_INTERNAL_PROVIDER_DATA), null, null, null)?.use { c ->
            if (c.moveToNext()) c.getString(0)?.toLongOrNull() else null
        }
        if (dvrId == null) {
            Log.w(TAG, "No DVR id for ${recordedProgram.uri}")
            false
        } else {
            context.contentResolver.delete(ContentUris.withAppendedId(HtspDvrContract.RECORDINGS_URI, dvrId), null, null) > 0
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to delete recording ${recordedProgram.uri}", e)
        false
    }
}
