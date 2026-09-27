package com.android.tv.tweaks.htsdvr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.gravarty.htsp.tvinput.HtspDvrContract
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * Tweak: Tvheadend-DVR. Serien-Timer (`autorecs`) und Zeit-Timer (`timerecs`) des Servers für die
 * Liste "Zeitplan". Alle Aufrufe blockieren und laufen nur im Hintergrund.
 */
object HtsDvrRepeatingTimers {
    private const val TAG = "HtsDvrRepeatingTimers"

    class Timer(val uri: Uri, val isSeries: Boolean, val name: String, val detail: String?)

    fun load(context: Context): List<Timer>? {
        return try {
            val result = ArrayList<Timer>()
            context.contentResolver.query(HtspDvrContract.AUTORECS_URI, null, null, null, null)?.use { c ->
                val a = HtspDvrContract.Autorec
                while (c.moveToNext()) {
                    val id = c.getString(c.getColumnIndexOrThrow(a.ID)) ?: continue
                    val search = c.getColumnIndex(a.EPG_SEARCH).let { if (it < 0) null else c.getString(it) }
                    val name = c.getColumnIndex(a.NAME).let { if (it < 0) null else c.getString(it) }
                    result.add(Timer(Uri.withAppendedPath(HtspDvrContract.AUTORECS_URI, id), true,
                        name?.takeIf { it.isNotEmpty() } ?: search.orEmpty(), search))
                }
            } ?: return null
            context.contentResolver.query(HtspDvrContract.TIMERECS_URI, null, null, null, null)?.use { c ->
                val t = HtspDvrContract.Timerec
                fun int(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) null else c.getInt(it) }
                while (c.moveToNext()) {
                    val id = c.getString(c.getColumnIndexOrThrow(t.ID)) ?: continue
                    val name = c.getColumnIndex(t.NAME).let { if (it < 0) null else c.getString(it) }.orEmpty()
                    val start = int(t.START)
                    val stop = int(t.STOP)
                    val time = if (start != null && stop != null) "${formatMinutes(start)}–${formatMinutes(stop)}" else null
                    val days = int(t.DAYS_OF_WEEK)?.let { formatDays(it) }
                    result.add(Timer(Uri.withAppendedPath(HtspDvrContract.TIMERECS_URI, id), false, name,
                        listOfNotNull(time, days).joinToString(" · ").ifEmpty { null }))
                }
            } ?: return null
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load timers", e)
            null
        }
    }

    fun delete(context: Context, timer: Timer): Boolean = try {
        context.contentResolver.delete(timer.uri, null, null) > 0
    } catch (e: Exception) {
        Log.w(TAG, "Failed to delete ${timer.uri}", e)
        false
    }

    /** Minuten ab Mitternacht (so speichert Tvheadend Zeit-Timer). */
    private fun formatMinutes(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** Bitmaske Mo = 1 … So = 64. */
    private fun formatDays(mask: Int): String {
        val names = DateFormatSymbols.getInstance().shortWeekdays
        val order = intArrayOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        return order.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(" ") { names[it] }
    }
}
