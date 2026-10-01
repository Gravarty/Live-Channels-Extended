package com.android.tv.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import androidx.annotation.MainThread
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import com.android.tv.R
import com.android.tv.common.util.PermissionUtils
import com.android.tv.util.images.ImageLoader
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vorschau-Kanäle/-Programme der App im Launcher (Android 8+).
 * AsyncTasks → Coroutines (Abbruch wie zuvor über cancel()).
 */
@MainThread
class PreviewDataManager(context: Context) {

    interface PreviewDataListener {
        fun onPreviewDataLoadFinished()
        fun onPreviewDataUpdateFinished()
    }

    fun interface OnPreviewChannelCreationResultListener {
        fun onPreviewChannelCreationResult(createdPreviewChannelId: Long)
    }

    private val context = context.applicationContext
    private val contentResolver = context.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var isLoadFinished = false
        private set
    private var previewData = PreviewData()
    private val previewDataListeners = CopyOnWriteArraySet<PreviewDataListener>()
    private var queryPreviewJob: Job? = null
    private val createPreviewChannelTasks = HashMap<Long, CreatePreviewChannelTask>()
    private val updatePreviewProgramTasks = HashMap<Long, UpdatePreviewProgramTask>()
    private val previewChannelLogoWidth = context.resources.getDimensionPixelSize(R.dimen.preview_channel_logo_width)
    private val previewChannelLogoHeight = context.resources.getDimensionPixelSize(R.dimen.preview_channel_logo_height)

    /** Lädt vorhandene Vorschau-Kanäle/-Programme der App. */
    fun start() {
        if (queryPreviewJob != null) return
        // Bugfix: erst nach der Zuweisung von job starten. Mit Main.immediate lief der Block sofort an und konnte
        // job lesen, bevor er gesetzt war (UninitializedPropertyAccessException, wenn die Abfrage sehr schnell fertig war).
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val result = withContext(Dispatchers.IO) { queryPreviewData() }
            if (queryPreviewJob === job) {
                queryPreviewJob = null
                previewData = PreviewData(result)
                isLoadFinished = true
                previewDataListeners.forEach { it.onPreviewDataLoadFinished() }
            }
        }
        queryPreviewJob = job
        job.start()
    }

    fun stop() {
        queryPreviewJob?.cancel()
        createPreviewChannelTasks.values.forEach { it.cancel() }
        updatePreviewProgramTasks.values.forEach { it.cancel() }
        queryPreviewJob = null
        createPreviewChannelTasks.clear()
        updatePreviewProgramTasks.clear()
    }

    fun getPreviewChannelId(previewChannelType: Long): Long = previewData.getPreviewChannelId(previewChannelType)

    fun createDefaultPreviewChannel(listener: OnPreviewChannelCreationResultListener) =
        createPreviewChannel(TYPE_DEFAULT_PREVIEW_CHANNEL.toLong(), listener)

    /** Legt einen Vorschau-Kanal an (läuft schon eine Anlage dieses Typs, wird nur angehängt). */
    fun createPreviewChannel(previewChannelType: Long, listener: OnPreviewChannelCreationResultListener?) {
        val running = createPreviewChannelTasks[previewChannelType]
        if (running == null) {
            val task = CreatePreviewChannelTask(previewChannelType)
            task.addListener(listener)
            createPreviewChannelTasks[previewChannelType] = task
            task.start()
        } else {
            running.addListener(listener)
        }
    }

    fun addListener(listener: PreviewDataListener) { previewDataListeners.add(listener) }
    fun removeListener(listener: PreviewDataListener) { previewDataListeners.remove(listener) }

    /** Setzt die Programme eines Vorschau-Kanals (laufende Aktualisierung mit anderem Inhalt wird ersetzt). */
    fun updatePreviewProgramsForChannel(previewChannelId: Long, programs: Set<PreviewProgramContent>, listener: PreviewDataListener?) {
        val running = updatePreviewProgramTasks[previewChannelId]
        if (running != null && running.programs == programs) {
            running.addListener(listener)
            return
        }
        val task = UpdatePreviewProgramTask(previewChannelId, programs)
        task.addListener(listener)
        if (running != null) {
            running.cancel()
            running.saveStatus()
            task.addListeners(running.listeners)
        }
        updatePreviewProgramTasks[previewChannelId] = task
        task.start()
    }

    private fun queryPreviewData(): PreviewData {
        val data = PreviewData()
        try {
            val previewChannelsUri = PreviewDataUtils.addQueryParamToUri(TvContract.Channels.CONTENT_URI, "preview" to "true")
            val packageName = context.packageName
            val hasAllEpg = PermissionUtils.hasAccessAllEpg(context)
            contentResolver.query(
                previewChannelsUri,
                androidx.tvprovider.media.tv.Channel.PROJECTION,
                if (hasAllEpg) TvContract.Channels.COLUMN_PACKAGE_NAME + "=?" else null,
                if (hasAllEpg) arrayOf(packageName) else null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val previewChannel = androidx.tvprovider.media.tv.Channel.fromCursor(cursor)
                    val type = previewChannel.internalProviderFlag1
                    // Ohne ALL_EPG liefert der Provider nur eigene Kanäle – Paket trotzdem prüfen
                    if (type != null && (hasAllEpg || packageName == previewChannel.packageName)) {
                        data.addPreviewChannelId(type, previewChannel.id)
                    }
                }
            }
            for (previewChannelId in data.previewChannelType2Id.values) {
                contentResolver.query(TvContract.buildPreviewProgramsUriForChannel(previewChannelId),
                    PreviewProgram.PROJECTION, null, null, null)?.use { c ->
                    while (c.moveToNext()) data.addPreviewProgram(PreviewProgram.fromCursor(c))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to get preview data", e)
        }
        return data
    }

    private inner class CreatePreviewChannelTask(private val previewChannelType: Long) {
        private val listeners = CopyOnWriteArraySet<OnPreviewChannelCreationResultListener>()
        private var job: Job? = null

        fun addListener(listener: OnPreviewChannelCreationResultListener?) {
            if (listener != null) listeners.add(listener)
        }

        fun cancel() { job?.cancel() }

        fun start() {
            job = scope.launch {
                val result = withContext(Dispatchers.IO) { insertPreviewChannel() }
                if (result != INVALID_PREVIEW_CHANNEL_ID) previewData.addPreviewChannelId(previewChannelType, result)
                listeners.forEach { it.onPreviewChannelCreationResult(result) }
                createPreviewChannelTasks.remove(previewChannelType)
            }
        }

        private fun insertPreviewChannel(): Long {
            val previewChannelId = try {
                val channelUri = contentResolver.insert(TvContract.Channels.CONTENT_URI,
                    PreviewDataUtils.createPreviewChannel(context, previewChannelType).toContentValues())
                if (channelUri == null) {
                    Log.e(TAG, "Fail to insert preview channel")
                    return INVALID_PREVIEW_CHANNEL_ID
                }
                ContentUris.parseId(channelUri)
            } catch (e: UnsupportedOperationException) {
                Log.e(TAG, "Fail to get channel ID")
                return INVALID_PREVIEW_CHANNEL_ID
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Fail to get channel ID")
                return INVALID_PREVIEW_CHANNEL_ID
            }
            // App-Symbol als Kanallogo
            val appIcon = context.applicationInfo.loadIcon(context.packageManager)
            if (appIcon is BitmapDrawable) {
                ChannelLogoUtils.storeChannelLogo(context, previewChannelId,
                    Bitmap.createScaledBitmap(appIcon.bitmap, previewChannelLogoWidth, previewChannelLogoHeight, false))
            }
            return previewChannelId
        }
    }

    private inner class UpdatePreviewProgramTask(private val previewChannelId: Long, val programs: Set<PreviewProgramContent>) {
        private val currentProgramId2PreviewProgramId: MutableMap<Long, Long> =
            HashMap(previewData.getPreviewProgramIds(previewChannelId) ?: emptyMap())
        val listeners = CopyOnWriteArraySet<PreviewDataListener>()
        private var job: Job? = null

        fun addListener(listener: PreviewDataListener?) {
            if (listener != null) listeners.add(listener)
        }

        fun addListeners(other: Set<PreviewDataListener>) = listeners.addAll(other)

        fun cancel() { job?.cancel() }

        fun start() {
            job = scope.launch {
                withContext(Dispatchers.IO) { doUpdate() }
                previewData.setPreviewProgramIds(previewChannelId, currentProgramId2PreviewProgramId)
                updatePreviewProgramTasks.remove(previewChannelId)
                listeners.forEach { it.onPreviewDataUpdateFinished() }
            }
        }

        /** Fehlende Programme einfügen, nicht mehr gewünschte löschen. */
        private suspend fun doUpdate() {
            val unchecked = HashMap(currentProgramId2PreviewProgramId)
            for (program in programs) {
                kotlin.coroutines.coroutineContext.ensureActive()
                if (unchecked.remove(program.id) != null) continue // schon vorhanden
                try {
                    val aspectRatio = ImageLoader.getAspectRatioFromPosterArtUri(context, program.posterArtUri.toString())
                    val programUri = contentResolver.insert(TvContract.PreviewPrograms.CONTENT_URI,
                        PreviewDataUtils.createPreviewProgramFromContent(program, aspectRatio).toContentValues())
                    if (programUri != null) currentProgramId2PreviewProgramId[program.id] = ContentUris.parseId(programUri)
                    else Log.e(TAG, "Fail to insert preview program")
                } catch (e: Exception) {
                    Log.e(TAG, "Fail to get preview program ID")
                }
            }
            for ((key, previewProgramId) in unchecked) {
                kotlin.coroutines.coroutineContext.ensureActive()
                try {
                    contentResolver.delete(TvContract.buildPreviewProgramUri(previewProgramId), null, null)
                    currentProgramId2PreviewProgramId.remove(key)
                } catch (e: Exception) {
                    Log.e(TAG, "Fail to remove preview program $previewProgramId")
                }
            }
        }

        fun saveStatus() = previewData.setPreviewProgramIds(previewChannelId, currentProgramId2PreviewProgramId)
    }

    private class PreviewData() {
        val previewChannelType2Id = HashMap<Long, Long>()
        private val programId2PreviewProgramId = HashMap<Long, MutableMap<Long, Long>>()

        constructor(other: PreviewData) : this() {
            previewChannelType2Id.putAll(other.previewChannelType2Id)
            programId2PreviewProgramId.putAll(other.programId2PreviewProgramId)
        }

        fun addPreviewProgram(previewProgram: PreviewProgram) {
            val map = programId2PreviewProgramId.getOrPut(previewProgram.channelId) { HashMap() }
            previewProgram.internalProviderId?.toLongOrNull()?.let { map[it] = previewProgram.id }
        }

        fun getPreviewChannelId(type: Long): Long = previewChannelType2Id[type] ?: INVALID_PREVIEW_CHANNEL_ID
        fun addPreviewChannelId(type: Long, id: Long) { previewChannelType2Id[type] = id }
        fun getPreviewProgramIds(previewChannelId: Long): Map<Long, Long>? = programId2PreviewProgramId[previewChannelId]
        fun setPreviewProgramIds(previewChannelId: Long, ids: MutableMap<Long, Long>) { programId2PreviewProgramId[previewChannelId] = ids }
    }

    object PreviewDataUtils {
        @JvmStatic
        fun createPreviewChannel(context: Context, previewChannelType: Long): androidx.tvprovider.media.tv.Channel {
            val builder = androidx.tvprovider.media.tv.Channel.Builder()
                .setType(TvContract.Channels.TYPE_PREVIEW)
                .setAppLinkIntentUri(TvContract.Channels.CONTENT_URI)
                .setInternalProviderFlag1(previewChannelType)
            if (previewChannelType == TYPE_RECORDED_PROGRAM_PREVIEW_CHANNEL.toLong()) {
                builder.setDisplayName(context.resources.getString(R.string.recorded_programs_preview_channel))
            } else {
                val info = context.applicationInfo
                builder.setDisplayName(info.loadLabel(context.packageManager)?.toString())
                    .setDescription(info.loadDescription(context.packageManager)?.toString())
            }
            return builder.build()
        }

        @JvmStatic
        fun createPreviewProgramFromContent(program: PreviewProgramContent, aspectRatio: Int): PreviewProgram =
            PreviewProgram.Builder()
                .setChannelId(program.previewChannelId)
                .setType(program.type)
                .setLive(program.live)
                .setTitle(program.title)
                .setDescription(program.description)
                .setPosterArtAspectRatio(aspectRatio)
                .setPosterArtUri(program.posterArtUri)
                .setIntentUri(program.intentUri)
                .setPreviewVideoUri(program.previewVideoUri)
                .setInternalProviderId(program.id.toString())
                .setContentId(program.intentUri.toString())
                .build()

        @JvmStatic
        fun addQueryParamToUri(uri: Uri, param: Pair<String, String?>): Uri =
            uri.buildUpon().appendQueryParameter(param.first, param.second).build()
    }

    companion object {
        private const val TAG = "PreviewDataManager"
        const val INVALID_PREVIEW_CHANNEL_ID = -1L
        const val TYPE_DEFAULT_PREVIEW_CHANNEL = 1
        const val TYPE_RECORDED_PROGRAM_PREVIEW_CHANNEL = 2
    }
}
