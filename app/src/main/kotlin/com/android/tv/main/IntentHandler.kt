package com.android.tv.main

import android.app.SearchManager
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.common.util.ContentUriUtils
import com.android.tv.data.ProgramImpl
import com.android.tv.data.api.Channel
import com.android.tv.data.api.Program
import com.android.tv.ui.DetailsActivity
import com.android.tv.util.CaptionSettings
import com.android.tv.util.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aus MainActivity.handleIntent() ausgelagert: wertet Start-Intents aus (Kanal-/Input-URIs,
 * Programmübersicht, Eingangsauswahl, Einrichtung) und merkt sich, worauf beim Start getunt wird.
 */
class IntentHandler(
    private val activity: MainActivity,
    private val dbDispatcher: CoroutineDispatcher,
) {
    /** Kanal, auf den beim nächsten Start/Resume getunt wird (null = zuletzt gesehener). */
    var initChannelUri: Uri? = null
    /** Parent-Input des Passthrough-Kanals beim Ausschalten des Bildschirms. */
    var parentInputIdWhenScreenOff: String? = null
    var tuneParams: Bundle? = null
    var shouldTuneToTunerChannel = false
    var showProgramGuide = false
    var showSelectInputView = false
    var inputToSetUp: TvInputInfo? = null
    var lastInputIdFromIntent: String? = null
        private set

    /** false = Intent ungültig, Activity soll beendet werden. */
    fun handleIntent(intent: Intent): Boolean {
        lastInputIdFromIntent = getInputId(intent)
        if (!activity.tvView.isPlaying) activity.captionSettings = CaptionSettings(activity)
        shouldTuneToTunerChannel = intent.getBooleanExtra(Utils.EXTRA_KEY_FROM_LAUNCHER, false)
        initChannelUri = null

        val extraAction = intent.getStringExtra(Utils.EXTRA_KEY_ACTION)
        if (Utils.EXTRA_ACTION_SHOW_TV_INPUT == extraAction) {
            Utils.getLastWatchedChannelUri(activity)?.let { initChannelUri = Uri.parse(it) }
            showSelectInputView = true
        }

        if (TvInputManager.ACTION_SETUP_INPUTS == intent.action) {
            activity.runAfterAttachedToWindow { activity.overlayManager.showSetupFragment() }
            return true
        }
        if (Intent.ACTION_VIEW != intent.action) return true

        val uri = intent.data
        if (Utils.isProgramsUri(uri)) {
            showProgramGuide = true
            return true
        }
        initChannelUri = uri
        if (Channels.CONTENT_URI == uri) {
            initChannelUri = null
            shouldTuneToTunerChannel = true
            return true
        }
        if (!Utils.isChannelUriForOneChannel(uri) && !Utils.isChannelUriForInput(uri)) {
            Log.w(TAG, "Malformed channel uri $uri tuning to default instead")
            initChannelUri = null
            return true
        }

        val params = intent.extras ?: Bundle()
        tuneParams = params
        val programUri = intent.getStringExtra(SearchManager.EXTRA_DATA_KEY)?.let(Uri::parse)
        val channelIdFromIntent = ContentUriUtils.safeParseId(uri)
        if (programUri != null && channelIdFromIntent != Channel.INVALID_ID) {
            showDetailsIfFutureProgram(programUri, channelIdFromIntent)
        }

        val channelUri = uri!!
        when {
            Utils.isChannelUriForTunerInput(channelUri) -> params.putLong(KEY_INIT_CHANNEL_ID, channelIdFromIntent)
            TvContract.isChannelUriForPassthroughInput(channelUri) -> {
                val input = activity.tvInputManagerHelper.getTvInputInfo(channelUri.pathSegments[1])
                if (input == null) {
                    initChannelUri = null
                    Toast.makeText(activity, R.string.msg_no_specific_input, Toast.LENGTH_SHORT).show()
                    return false
                }
                if (!input.isPassthroughInput) {
                    initChannelUri = null
                    Toast.makeText(activity, R.string.msg_not_passthrough_input, Toast.LENGTH_SHORT).show()
                    return false
                }
            }
            else -> {
                // URI für einen Input: zuletzt gesehenen Kanal dieses Inputs, sonst den ersten
                val inputId = channelUri.getQueryParameter("input")
                var channelId = if (inputId != null) Utils.getLastWatchedChannelIdForInput(activity, inputId) else Channel.INVALID_ID
                if (channelId == Channel.INVALID_ID) {
                    activity.contentResolver.query(channelUri, arrayOf(BaseColumns._ID), null, null, null)?.use {
                        if (it.moveToNext()) channelId = it.getLong(0)
                    }
                }
                if (channelId == Channel.INVALID_ID) {
                    initChannelUri = null
                    inputToSetUp = activity.tvInputManagerHelper.getTvInputInfo(inputId)
                } else {
                    initChannelUri = TvContract.buildChannelUri(channelId)
                    params.putLong(KEY_INIT_CHANNEL_ID, channelId)
                }
            }
        }
        return true
    }

    /** Öffnet die Detailansicht, wenn die Sendung aus dem Such-Intent noch nicht begonnen hat. */
    private fun showDetailsIfFutureProgram(programUri: Uri, channelId: Long) {
        activity.lifecycleScope.launch {
            val program: Program? = withContext(dbDispatcher) {
                try {
                    activity.contentResolver.query(programUri, ProgramImpl.PROJECTION, null, null, null)?.use {
                        if (it.moveToNext()) ProgramImpl.fromCursor(it) else null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error querying $programUri", e)
                    null
                }
            }
            if (program == null || program.startTimeUtcMillis <= System.currentTimeMillis()) return@launch
            val channel = activity.channelDataManager.getChannel(channelId) ?: return@launch
            activity.startActivity(Intent(activity, DetailsActivity::class.java).apply {
                putExtra(DetailsActivity.CHANNEL_ID, channelId)
                putExtra(DetailsActivity.DETAILS_VIEW_TYPE, DetailsActivity.PROGRAM_VIEW)
                putExtra(DetailsActivity.PROGRAM, program.toParcelable())
                putExtra(DetailsActivity.INPUT_ID, channel.inputId)
            })
        }
    }

    /** Input-ID bei Passthrough-URIs (HDMI usw.), sonst null. */
    private fun getInputId(intent: Intent): String? {
        val uri = intent.data
        return if (uri != null && TvContract.isChannelUriForPassthroughInput(uri)) uri.pathSegments[1] else null
    }

    fun isAudioOnlyInput(): Boolean {
        val inputId = lastInputIdFromIntent ?: return false
        return activity.tvInputManagerHelper.getTvInputInfoCompat(inputId)?.isAudioOnly == true
    }

    companion object {
        private const val TAG = "IntentHandler"
        const val KEY_INIT_CHANNEL_ID = "com.android.tv.init_channel_id"
    }
}
