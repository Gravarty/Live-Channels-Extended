package com.android.tv.ui.sidepanel

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.tweaks.htsdvr.HtsDvrRepeatingTimers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tweak: Tvheadend-DVR. Liste "Zeitplan" (Karte im TV-Menü): Serien- und Zeit-Timer des Servers. */
class HtsTimersFragment : SideFragment<Item>() {

    override fun getTitle(): String = getString(R.string.tweak_hts_timers)

    override fun getItemList(): List<Item> = listOf(infoItem(getString(R.string.tweak_hts_timers_loading)))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val timers = withContext(Dispatchers.IO) { HtsDvrRepeatingTimers.load(context) }
            setItems(when {
                timers == null -> listOf(infoItem(getString(R.string.tweak_tvheadend_dvr_error)))
                timers.isEmpty() -> listOf(infoItem(getString(R.string.tweak_hts_timers_empty)))
                else -> timers.map { timerItem(it) }
            })
        }
    }

    private fun infoItem(text: String) = object : ActionItem(text) {
        override fun onSelected() {}
    }

    private fun timerItem(timer: HtsDvrRepeatingTimers.Timer): Item {
        val type = getString(if (timer.isSeries) R.string.tweak_hts_timers_series else R.string.tweak_hts_timers_time)
        val sideFragmentManager = (requireActivity() as MainActivity).overlayManager.sideFragmentManager
        return object : SubMenuItem(timer.name, listOfNotNull(type, timer.detail).joinToString(" · "), sideFragmentManager) {
            override fun getFragment(): SideFragment<*> = HtsTimerFragment(timer)
        }
    }
}

/** Tweak: Tvheadend-DVR. Einzelner Serien- oder Zeit-Timer mit "Löschen". */
class HtsTimerFragment(private val timer: HtsDvrRepeatingTimers.Timer) : SideFragment<Item>() {

    override fun getTitle(): String = timer.name

    override fun getItemList(): List<Item> = listOf(object : ActionItem(getString(R.string.tweak_hts_timers_delete)) {
        override fun onSelected() {
            val context = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { HtsDvrRepeatingTimers.delete(context, timer) }
                Toast.makeText(context, if (ok) R.string.tweak_hts_timers_deleted else R.string.tweak_tvheadend_dvr_error,
                    Toast.LENGTH_SHORT).show()
                if (ok) closeFragment()
            }
        }
    })
}
