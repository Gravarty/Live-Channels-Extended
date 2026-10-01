package com.android.tv.dvr

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import com.android.tv.TvSingletons
import com.android.tv.common.util.CommonUtils
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nach dem Einhängen eines Speichers: Aufnahmen des eingebauten Tuners ohne Dateien aus dem
 * TvProvider entfernen bzw. bei zu kleinem Speicher dessen Daten vergessen.
 * AsyncTask → Coroutine.
 */
class DvrStorageStatusManager(private val context: Context) : RecordingStorageStatusManager(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cleanUpJob: Job? = null

    override fun cleanUpDbIfNeeded() {
        cleanUpJob?.cancel()
        // Bugfix: erst nach der Zuweisung von job starten. Mit Main.immediate lief der Block sofort an und konnte
        // job lesen, bevor er gesetzt war (UninitializedPropertyAccessException, wenn die Abfrage sehr schnell fertig war).
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val forgetStorage = withContext(Dispatchers.IO) { cleanUp() }
            if (forgetStorage == true) {
                val singletons = TvSingletons.getSingletons(context)
                val dvrManager = singletons.getDvrManager()
                for (info in singletons.getTvInputManagerHelper().getTvInputInfos(true, false)) {
                    if (CommonUtils.isBundledInput(info.id) && dvrManager != null) dvrManager.forgetStorage(info.id)
                }
            }
            if (cleanUpJob === job) cleanUpJob = null
        }
        cleanUpJob = job
        job.start()
    }

    private suspend fun cleanUp(): Boolean? {
        when (getDvrStorageStatus()) {
            STORAGE_STATUS_MISSING -> return null
            STORAGE_STATUS_TOTAL_CAPACITY_TOO_SMALL -> return true
        }
        val ops = getDeleteOps()
        if (ops.isNullOrEmpty()) return null
        Log.i(TAG, "New device storage mounted. # of recordings to be forgotten : ${ops.size}")
        var i = 0
        while (i < ops.size && kotlin.coroutines.coroutineContext.isActive) {
            val batch = ArrayList(ops.subList(i, minOf(i + BATCH_OPERATION_COUNT, ops.size)))
            try {
                context.contentResolver.applyBatch(TvContractCompat.AUTHORITY, batch)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean up  RecordedPrograms.", e)
            }
            i += BATCH_OPERATION_COUNT
        }
        return null
    }

    /** Aufnahmen des eingebauten Tuners, deren Ordner fehlt. */
    private suspend fun getDeleteOps(): List<ContentProviderOperation>? {
        val ops = ArrayList<ContentProviderOperation>()
        return try {
            context.contentResolver.query(TvContractCompat.RecordedPrograms.CONTENT_URI, PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (!kotlin.coroutines.coroutineContext.isActive || getDvrStorageStatus() == STORAGE_STATUS_MISSING) {
                        ops.clear()
                        break
                    }
                    val dataUriString = c.getString(2) ?: continue
                    val dataUri = Uri.parse(dataUriString)
                    if (!CommonUtils.isInBundledPackageSet(c.getString(1)) || dataUri?.path == null ||
                        ContentResolver.SCHEME_FILE != dataUri.scheme
                    ) continue
                    if (!File(dataUri.path!!).exists()) {
                        ops.add(ContentProviderOperation.newDelete(TvContractCompat.buildRecordedProgramUri(c.getString(0).toLong())).build())
                    }
                }
                ops
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error when getting delete ops at CleanUpDbTask", e)
            null
        }
    }

    companion object {
        private const val TAG = "DvrStorageStatusManager"
        private val PROJECTION = arrayOf(
            TvContractCompat.RecordedPrograms._ID,
            TvContractCompat.RecordedPrograms.COLUMN_PACKAGE_NAME,
            TvContractCompat.RecordedPrograms.COLUMN_RECORDING_DATA_URI,
        )
        private const val BATCH_OPERATION_COUNT = 100
    }
}
