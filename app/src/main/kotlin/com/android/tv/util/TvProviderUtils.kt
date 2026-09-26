package com.android.tv.util

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.WorkerThread
import com.android.tv.data.api.BaseProgram

/**
 * Prüft/ergänzt die Zusatzspalten series_id und state im TvProvider.
 * Die Methoden-/Extra-Namen sind im SDK @hide, daher als Konstanten mit den Werten aus TvContract.
 */
object TvProviderUtils {
    private const val TAG = "TvProviderUtils"

    const val EXTRA_PROGRAM_COLUMN_SERIES_ID = BaseProgram.COLUMN_SERIES_ID
    const val EXTRA_PROGRAM_COLUMN_STATE = BaseProgram.COLUMN_STATE

    // TvContract (@hide)
    private const val METHOD_GET_COLUMNS = "get_columns"
    private const val METHOD_ADD_COLUMN = "add_column"
    private const val EXTRA_EXISTING_COLUMN_NAMES = "android.media.tv.extra.EXISTING_COLUMN_NAMES"
    private const val EXTRA_COLUMN_NAME = "android.media.tv.extra.COLUMN_NAME"
    private const val EXTRA_DATA_TYPE = "android.media.tv.extra.DATA_TYPE"

    private var programHasSeriesIdColumn = false
    private var recordedProgramHasSeriesIdColumn = false
    private var recordedProgramHasStateColumn = false

    // Bugfix: Ohne Systemrechte liefert TvProvider für get_columns null. Das Original prüfte dann bei jeder
    // Programm-Abfrage erneut (2 IPCs unter Sperre); jetzt nur einmal pro Prozess.
    private var programSeriesIdColumnChecked = false
    private var recordedProgramSeriesIdColumnChecked = false
    private var recordedProgramStateColumnChecked = false

    // Ab Android O (minSdk 30) darf die App Spalten anlegen – Partner-Flag entfällt.

    @JvmStatic
    @WorkerThread
    @Synchronized
    fun checkSeriesIdColumn(context: Context, uri: Uri): Boolean =
        (Utils.isRecordedProgramsUri(uri) && checkRecordedProgramTableSeriesIdColumn(context, uri)) ||
            (Utils.isProgramsUri(uri) && checkProgramTableSeriesIdColumn(context, uri))

    @Synchronized
    private fun checkProgramTableSeriesIdColumn(context: Context, uri: Uri): Boolean {
        if (!programSeriesIdColumnChecked) {
            programSeriesIdColumnChecked = true
            programHasSeriesIdColumn = ensureColumn(context, uri, EXTRA_PROGRAM_COLUMN_SERIES_ID)
        }
        return programHasSeriesIdColumn
    }

    @Synchronized
    private fun checkRecordedProgramTableSeriesIdColumn(context: Context, uri: Uri): Boolean {
        if (!recordedProgramSeriesIdColumnChecked) {
            recordedProgramSeriesIdColumnChecked = true
            recordedProgramHasSeriesIdColumn = ensureColumn(context, uri, EXTRA_PROGRAM_COLUMN_SERIES_ID)
        }
        return recordedProgramHasSeriesIdColumn
    }

    @JvmStatic
    @WorkerThread
    @Synchronized
    fun checkStateColumn(context: Context, uri: Uri): Boolean {
        if (!Utils.isRecordedProgramsUri(uri)) return false
        if (!recordedProgramStateColumnChecked) {
            recordedProgramStateColumnChecked = true
            recordedProgramHasStateColumn = ensureColumn(context, uri, EXTRA_PROGRAM_COLUMN_STATE)
        }
        return recordedProgramHasStateColumn
    }

    /** Spalte vorhanden oder erfolgreich angelegt. */
    private fun ensureColumn(context: Context, uri: Uri, column: String): Boolean =
        getExistingColumns(context, uri).contains(column) || addColumnToTable(context, uri, column)

    @JvmStatic @Synchronized fun getProgramHasSeriesIdColumn() = programHasSeriesIdColumn
    @JvmStatic @Synchronized fun getRecordedProgramHasSeriesIdColumn() = recordedProgramHasSeriesIdColumn
    @JvmStatic @Synchronized fun getRecordedProgramHasStateColumn() = recordedProgramHasStateColumn

    @JvmStatic
    fun addExtraColumnsToProjection(projection: Array<String>, column: String): Array<String> =
        if (column in projection) projection else projection + column

    internal fun getExistingColumns(context: Context, uri: Uri): Set<String> {
        val result = try {
            context.contentResolver.call(uri, METHOD_GET_COLUMNS, uri.toString(), null)
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to get existing columns.", e)
            null
        }
        result?.getStringArray(EXTRA_EXISTING_COLUMN_NAMES)?.let { return it.toHashSet() }
        Log.e(TAG, "Query existing column names from $uri returned null")
        return emptySet()
    }

    private fun addColumnToTable(context: Context, contentUri: Uri, columnName: String): Boolean {
        val extra = Bundle().apply {
            putCharSequence(EXTRA_COLUMN_NAME, columnName)
            putCharSequence(EXTRA_DATA_TYPE, "TEXT")
        }
        // Schlägt das Anlegen fehl, kommt null zurück (kein Absturz).
        val allColumns = try {
            context.contentResolver.call(contentUri, METHOD_ADD_COLUMN, contentUri.toString(), extra)
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to add column.", e)
            null
        }
        if (allColumns == null) Log.w(TAG, "Adding new column failed. Uri=$contentUri")
        return allColumns != null
    }
}
