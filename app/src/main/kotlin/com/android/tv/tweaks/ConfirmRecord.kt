package com.android.tv.tweaks

import android.os.Bundle
import android.widget.Toast
import androidx.core.os.BundleCompat
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.TvSingletons
import com.android.tv.data.ProgramImpl
import com.android.tv.data.api.Program
import com.android.tv.dvr.data.ScheduledRecording
import com.android.tv.dvr.ui.DvrGuidedStepFragment
import com.android.tv.dvr.ui.DvrHalfSizedDialogFragment
import com.android.tv.dvr.ui.DvrUiHelper

/**
 * Tweak: Bei Aufnahme fragen. Bestätigung, bevor ein Klick in der Programmübersicht eine Aufnahme
 * plant oder einen Aufnahmeplan abbricht. Folgen gehen weiter über den Stock-Dialog "Folge / Serie".
 */
object ConfirmRecord {
    private const val KEY_SCHEDULE_ID = "confirm_record_schedule_id"

    /** Zeigt die Bestätigung; [schedule] = vorhandener Aufnahmeplan (Abbrechen) oder null (Aufnehmen). */
    fun show(activity: MainActivity, program: Program, schedule: ScheduledRecording?) {
        val dialog = ConfirmRecordDialogFragment().apply {
            arguments = Bundle().apply {
                putParcelable(DvrHalfSizedDialogFragment.KEY_PROGRAM, program.toParcelable())
                putLong(KEY_SCHEDULE_ID, schedule?.id ?: -1L)
            }
        }
        activity.overlayManager.showDialogFragment(DvrHalfSizedDialogFragment.DIALOG_TAG, dialog, false, true)
    }

    class ConfirmRecordDialogFragment : DvrHalfSizedDialogFragment.DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = ConfirmRecordFragment()
    }

    class ConfirmRecordFragment : DvrGuidedStepFragment() {
        private var program: ProgramImpl? = null
        private var schedule: ScheduledRecording? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            val args = requireArguments()
            program = BundleCompat.getParcelable(args, DvrHalfSizedDialogFragment.KEY_PROGRAM, ProgramImpl::class.java)
            val scheduleId = args.getLong(KEY_SCHEDULE_ID, -1L)
            if (scheduleId != -1L) {
                schedule = TvSingletons.getSingletons(requireContext()).getDvrDataManager().getScheduledRecording(scheduleId)
            }
            super.onCreate(savedInstanceState)
        }

        override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
            val program = program
            return Guidance(program?.title, program?.getDurationString(requireContext()), null,
                resources.getDrawable(R.drawable.ic_dvr, null))
        }

        override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            val context = requireContext()
            if (schedule == null) {
                actions.add(GuidedAction.Builder(context).id(ACTION_RECORD).title(R.string.tweak_confirm_record_record).build())
                actions.add(GuidedAction.Builder(context).clickAction(GuidedAction.ACTION_ID_CANCEL).build())
            } else {
                actions.add(GuidedAction.Builder(context).id(ACTION_CANCEL_RECORDING).title(R.string.dvr_detail_cancel_recording).build())
                actions.add(GuidedAction.Builder(context).id(ACTION_BACK).title(R.string.tweak_confirm_record_back).build())
            }
        }

        override fun onTrackedGuidedActionClicked(action: GuidedAction) {
            val activity = activity as? MainActivity
            val program = program
            dismissDialog()
            if (activity == null || program == null) return
            when (action.id) {
                // Wie ProgramItemView (Stock): Speicher prüfen, dann planen
                ACTION_RECORD -> {
                    val inputId = activity.channelDataManager.getChannel(program.channelId)?.inputId ?: return
                    DvrUiHelper.checkStorageStatusAndShowErrorMessage(activity, inputId) {
                        DvrUiHelper.requestRecordingFutureProgram(activity, program, false)
                    }
                }
                // Wie ProgramItemView (Stock): Aufnahmeplan entfernen
                ACTION_CANCEL_RECORDING -> {
                    val schedule = schedule ?: return
                    TvSingletons.getSingletons(activity).getDvrManager()?.removeScheduledRecording(schedule) ?: return
                    Toast.makeText(activity, activity.getString(R.string.dvr_schedules_deletion_info, program.title),
                        Toast.LENGTH_SHORT).show()
                }
            }
        }

        private companion object {
            const val ACTION_RECORD = 1L
            const val ACTION_CANCEL_RECORDING = 2L
            const val ACTION_BACK = 3L
        }
    }
}
