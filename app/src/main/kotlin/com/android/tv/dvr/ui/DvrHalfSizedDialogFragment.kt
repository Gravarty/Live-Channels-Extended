package com.android.tv.dvr.ui

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.leanback.app.GuidedStepSupportFragment
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.dialog.HalfSizedDialogFragment
import com.android.tv.dvr.ui.DvrConflictFragment.DvrChannelWatchConflictFragment
import com.android.tv.dvr.ui.DvrConflictFragment.DvrProgramConflictFragment
import com.android.tv.ui.DetailsActivity

/** Halbhoher DVR-Dialog; hält die Programmübersicht offen, solange sie angezeigt wird. */
open class DvrHalfSizedDialogFragment : HalfSizedDialogFragment() {

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val activity = activity
        if (activity is MainActivity) {
            val programGuide = activity.overlayManager.programGuide
            if (programGuide.isActive) programGuide.cancelHide()
        }
    }

    override fun onDetach() {
        super.onDetach()
        val activity = activity
        if (activity is MainActivity) {
            val programGuide = activity.overlayManager.programGuide
            if (programGuide.isActive) programGuide.scheduleHide()
        }
    }

    /** Dialog, der einen [DvrGuidedStepFragment] einbettet. */
    abstract class DvrGuidedStepDialogFragment : DvrHalfSizedDialogFragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            val view = super.onCreateView(inflater, container, savedInstanceState)
            val fragment = onCreateGuidedStepFragment()
            fragment.arguments = arguments
            fragment.setOnActionClickListener(getOnActionClickListener())
            GuidedStepSupportFragment.add(childFragmentManager, fragment, R.id.halfsized_dialog_host)
            return view
        }

        // Abweichung: Das Original überschreibt setOnActionClickListener, um den Listener auch an ein
        // bereits erzeugtes inneres Fragment weiterzugeben. HalfSizedDialogFragment.setOnActionClickListener
        // ist in Kotlin final; der Listener wird daher nur in onCreateView übergeben (alle Aufrufer setzen
        // ihn vor dem Anzeigen).

        protected abstract fun onCreateGuidedStepFragment(): DvrGuidedStepFragment
    }

    /** Dialog für [DvrScheduleFragment]. */
    class DvrScheduleDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = DvrScheduleFragment()
    }

    /** Dialog für [DvrProgramConflictFragment]. */
    class DvrProgramConflictDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = DvrProgramConflictFragment()
    }

    /** Dialog für [DvrChannelWatchConflictFragment]. */
    class DvrChannelWatchConflictDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = DvrChannelWatchConflictFragment()
    }

    /** Dialog für [DvrChannelRecordDurationOptionFragment]. */
    class DvrChannelRecordDurationOptionDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = DvrChannelRecordDurationOptionFragment()
    }

    /** Dialog für [DvrInsufficientSpaceErrorFragment]. */
    class DvrInsufficientSpaceErrorDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = DvrInsufficientSpaceErrorFragment()
    }

    /** Dialog für [DvrMissingStorageErrorFragment]. */
    class DvrMissingStorageErrorDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = DvrMissingStorageErrorFragment()
    }

    /** Fehlerdialog: nicht genug freier Speicher für die Aufnahme. */
    class DvrNoFreeSpaceErrorDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = DvrGuidedStepFragment.DvrNoFreeSpaceErrorFragment()
    }

    /** Fehlerdialog: Speicher insgesamt zu klein für DVR. */
    class DvrSmallSizedStorageErrorDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = DvrGuidedStepFragment.DvrSmallSizedStorageErrorFragment()
    }

    /** Dialog für [DvrStopRecordingFragment]. */
    class DvrStopRecordingDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = DvrStopRecordingFragment()
    }

    /** Dialog für [DvrAlreadyScheduledFragment]. */
    class DvrAlreadyScheduledDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = DvrAlreadyScheduledFragment()
    }

    /** Dialog für [DvrAlreadyRecordedFragment]. */
    class DvrAlreadyRecordedDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrGuidedStepFragment = DvrAlreadyRecordedFragment()
    }

    /** Dialog für [DvrWriteStoragePermissionRationaleFragment]; fordert beim Schließen die Berechtigung an. */
    class DvrWriteStoragePermissionRationaleDialogFragment : DvrGuidedStepDialogFragment() {
        override fun onCreateGuidedStepFragment(): DvrWriteStoragePermissionRationaleFragment =
            DvrWriteStoragePermissionRationaleFragment()

        override fun onDismiss(dialog: DialogInterface) {
            when (val activity = activity) {
                is DetailsActivity -> activity.requestPermissions(arrayOf(WRITE_EXTERNAL_STORAGE), DetailsActivity.REQUEST_DELETE)
                is DvrSeriesDeletionActivity ->
                    activity.requestPermissions(arrayOf(WRITE_EXTERNAL_STORAGE), DvrSeriesDeletionActivity.REQUEST_DELETE)
            }
            super.onDismiss(dialog)
        }

        private companion object {
            const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
        }
    }

    /**
     * Dialog mit [DvrStopSeriesRecordingFragment].
     *
     * Abweichung: im Original eigene Top-Level-Klasse (DvrStopSeriesRecordingDialogFragment.java);
     * DvrUiHelper erwartet sie hier verschachtelt. Wie im Original kein HalfSizedDialogFragment.
     */
    class DvrStopSeriesRecordingDialogFragment : DialogFragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            val view = inflater.inflate(R.layout.halfsized_dialog, container, false)
            val fragment = DvrStopSeriesRecordingFragment()
            fragment.arguments = arguments
            GuidedStepSupportFragment.add(childFragmentManager, fragment, R.id.halfsized_dialog_host)
            return view
        }

        override fun getTheme(): Int = R.style.Theme_TV_dialog_HalfSizedDialog

        companion object {
            const val DIALOG_TAG = "dialog_tag"
        }
    }

    companion object {
        /** Abweichung: Kotlin vererbt Companion-Member nicht → Tag der Basisklasse hier gespiegelt. */
        @JvmField
        val DIALOG_TAG: String = HalfSizedDialogFragment.DIALOG_TAG
        /** Eingabe-ID (String). */
        const val KEY_INPUT_ID = "DvrHalfSizedDialogFragment.input_id"
        /** Sendung (ProgramImpl, Parcelable). */
        const val KEY_PROGRAM = "DvrHalfSizedDialogFragment.program"
        /** Kanal-ID (Long). */
        const val KEY_CHANNEL_ID = "DvrHalfSizedDialogFragment.channel_id"
        /** Aufnahmestart in ms (Long). */
        const val KEY_START_TIME_MS = "DvrHalfSizedDialogFragment.start_time_ms"
        /** Aufnahmeende in ms (Long). */
        const val KEY_END_TIME_MS = "DvrHalfSizedDialogFragment.end_time_ms"
    }
}
