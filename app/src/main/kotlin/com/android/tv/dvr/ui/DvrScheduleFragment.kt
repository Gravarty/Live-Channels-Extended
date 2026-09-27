package com.android.tv.dvr.ui

import android.os.Bundle
import android.text.format.DateUtils
import androidx.core.os.BundleCompat
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import com.android.tv.R
import com.android.tv.TvSingletons
import com.android.tv.common.SoftPreconditions
import com.android.tv.data.ProgramImpl
import com.android.tv.dvr.data.SeriesRecording
import com.android.tv.dvr.ui.DvrConflictFragment.DvrProgramConflictFragment
import com.android.tv.tweaks.htsdvr.HtsDvrAutorecs
import com.android.tv.tweaks.htsdvr.HtsDvrTimers
import com.android.tv.util.Utils

/**
 * Fragt, ob eine Folge oder die ganze Serie aufgenommen werden soll.
 *
 * Die Sendung muss eine Folge sein und es darf noch keine (aktive) Serienaufnahme geben.
 */
class DvrScheduleFragment : DvrGuidedStepFragment() {
    private var program: ProgramImpl? = null
    private var addCurrentProgramToSeries = false

    override fun onCreate(savedInstanceState: Bundle?) {
        arguments?.let { args ->
            program = BundleCompat.getParcelable(args, DvrHalfSizedDialogFragment.KEY_PROGRAM, ProgramImpl::class.java)
            addCurrentProgramToSeries = args.getBoolean(KEY_ADD_CURRENT_PROGRAM_TO_SERIES, false)
        }
        val program = program
        SoftPreconditions.checkArgument(program != null && program.isEpisodic, TAG, "The program should be episodic: %s ", program)
        // Bugfix: ohne Sendung/DvrManager keine Prüfung (Original: NPE)
        val seriesRecording = program?.let { TvSingletons.getSingletons(requireContext()).getDvrManager()?.getSeriesRecording(it) }
        SoftPreconditions.checkArgument(seriesRecording == null || seriesRecording.isStopped, TAG,
            "The series recording should be stopped or null: %s", seriesRecording)
        super.onCreate(savedInstanceState)
    }

    // Abweichung: keine Hilt-/Dagger-Injektion von DvrFlags; startEarlyEndLateEnabled() ist im AOSP-Build false

    override fun onProvideTheme(): Int = R.style.Theme_TV_Dvr_GuidedStep_Twoline_Action

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        val title = getString(R.string.dvr_schedule_dialog_title)
        val icon = resources.getDrawable(R.drawable.ic_dvr, null)
        return Guidance(title, null, null, icon)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        val program = program ?: return // Bugfix: fehlende Sendung (Original: NPE)
        val description = if (program.startTimeUtcMillis <= System.currentTimeMillis()) {
            getString(R.string.dvr_action_record_episode_from_now_description,
                DateUtils.formatDateTime(context, program.endTimeUtcMillis, DateUtils.FORMAT_SHOW_TIME))
        } else {
            program.getDurationString(context)
        }
        actions.add(GuidedAction.Builder(context).id(ACTION_RECORD_EPISODE.toLong())
            .title(R.string.dvr_action_record_episode).description(description).build())
        actions.add(GuidedAction.Builder(context).id(ACTION_RECORD_SERIES.toLong())
            .title(R.string.dvr_action_record_series).description(program.title).build())
    }

    override fun onTrackedGuidedActionClicked(action: GuidedAction) {
        val program = program ?: return
        val dvrManager = dvrManager ?: return
        if (action.id == ACTION_RECORD_EPISODE.toLong()) {
            // DvrFlags.startEarlyEndLateEnabled() ist false → direkt planen
            dvrManager.addSchedule(program)
            val conflicts = dvrManager.getConflictingSchedules(program)
            if (conflicts.isEmpty()) {
                DvrUiHelper.showAddScheduleToast(requireContext(), program.title, program.startTimeUtcMillis, program.endTimeUtcMillis)
                dismissDialog()
            } else {
                val fragment: GuidedStepSupportFragment = DvrProgramConflictFragment()
                fragment.arguments = Bundle().apply { putParcelable(DvrHalfSizedDialogFragment.KEY_PROGRAM, program) }
                GuidedStepSupportFragment.add(parentFragmentManager, fragment, R.id.halfsized_dialog_host)
            }
        } else if (action.id == ACTION_RECORD_SERIES.toLong()) {
            // Tweak: Tvheadend-DVR – Serien-Timer auf dem Server statt lokaler Serie
            val inputId = Utils.getTvInputInfoForProgram(requireContext(), program)?.id
            if (HtsDvrTimers.handlesInput(requireContext(), inputId)) {
                HtsDvrAutorecs.addAutorec(requireContext(), program)
                dismissDialog()
                return
            }
            val singletons = TvSingletons.getSingletons(requireContext())
            var seriesRecording = program.seriesId?.let { singletons.getDvrDataManager().getSeriesRecording(it) }
            if (seriesRecording == null) {
                seriesRecording = dvrManager.addSeriesRecording(program, emptyList(), SeriesRecording.STATE_SERIES_STOPPED)
            } else {
                // Priorität wieder auf die höchste setzen (Abweichung: ohne DvrScheduleManager bleibt sie)
                val priority = singletons.getDvrScheduleManager()?.suggestNewSeriesPriority() ?: seriesRecording.priority
                seriesRecording = SeriesRecording.buildFrom(seriesRecording).setPriority(priority).build()
                dvrManager.updateSeriesRecording(seriesRecording)
            }
            // Bugfix: Serienaufnahme konnte nicht angelegt werden (Original: NPE)
            if (seriesRecording != null) {
                DvrUiHelper.startSeriesSettingsActivity(requireContext(), seriesRecording.id, null, true, true, true,
                    if (addCurrentProgramToSeries) program else null)
            }
            dismissDialog()
        }
    }

    companion object {
        /** Ob die aktuelle Sendung zur Serie hinzugefügt werden soll (Boolean). */
        const val KEY_ADD_CURRENT_PROGRAM_TO_SERIES = "add_current_program_to_series"
        private const val TAG = "DvrScheduleFragment"
        private const val ACTION_RECORD_EPISODE = 1
        private const val ACTION_RECORD_SERIES = 2
    }
}
