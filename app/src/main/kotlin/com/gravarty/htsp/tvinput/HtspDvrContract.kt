package com.gravarty.htsp.tvinput

import android.net.Uri

/**
 * Public contract of the DVR provider (for the Live Channels integration).
 * Fields and semantics follow pvr.hts: recordings = Recording::IsRecording,
 * timers = Recording::IsTimer (one-shot), autorecs = series / EPG-search timers,
 * timerecs = repeating time-based timers. Times in unix seconds unless noted.
 */
object HtspDvrContract {
    const val AUTHORITY = "com.gravarty.hts.dvr"
    val BASE_URI: Uri = Uri.parse("content://$AUTHORITY")

    val RECORDINGS_URI: Uri = BASE_URI.buildUpon().appendPath("recordings").build()
    val TIMERS_URI: Uri = BASE_URI.buildUpon().appendPath("timers").build()
    val AUTORECS_URI: Uri = BASE_URI.buildUpon().appendPath("autorecs").build()
    val TIMERECS_URI: Uri = BASE_URI.buildUpon().appendPath("timerecs").build()

    /** Common to recordings and timers (tvheadend dvr entry) */
    object Dvr {
        const val ID = "_id"                         // dvr entry id (long)
        const val CHANNEL = "channel"                // HTSP channel id
        const val TV_CHANNEL_ID = "tv_channel_id"    // TvContract.Channels._ID or null
        const val CHANNEL_NAME = "channel_name"
        const val TITLE = "title"
        const val SUBTITLE = "subtitle"
        const val DESCRIPTION = "description"
        const val START = "start"                    // scheduled start
        const val STOP = "stop"                      // scheduled stop
        const val START_EXTRA = "start_extra"        // minutes
        const val STOP_EXTRA = "stop_extra"          // minutes
        const val REMOVAL = "removal"                // lifetime, tvheadend "removal" (0 = DVR config)
        const val PRIORITY = "priority"              // tvheadend DVR_PRIO_* (2 = normal)
        const val STATE = "state"                    // scheduled | recording | completed | error
        const val ENABLED = "enabled"                // 0/1
        const val EVENT_ID = "event_id"              // EPG event id (= TvContract program INTERNAL_PROVIDER_DATA)
        const val AUTOREC_ID = "autorec_id"          // set if created by a series timer
        const val TIMEREC_ID = "timerec_id"          // set if created by a time timer
        const val CONTENT_TYPE = "content_type"      // DVB genre
        const val ERROR = "error"
        // recordings only
        const val RECORDING_START = "recording_start" // real start (pvr.hts GetRecordings)
        const val RECORDING_STOP = "recording_stop"
        const val FILE_SIZE = "file_size"             // bytes
        const val PLAYCOUNT = "playcount"
        const val PLAYPOSITION = "playposition"       // seconds
        const val DATA_URI = "data_uri"               // = RecordedPrograms.COLUMN_RECORDING_DATA_URI
    }

    object Autorec {
        const val ID = "_id"                 // string id
        const val NAME = "name"
        const val EPG_SEARCH = "epg_search"  // tvheadend "title": EPG search string
        const val USE_REGEX = "use_regex"    // write only: 1 = EPG_SEARCH is a regex (else escaped)
        const val FULLTEXT = "fulltext"
        const val CHANNEL = "channel"        // HTSP channel id, 0/-1 = any
        const val TV_CHANNEL_ID = "tv_channel_id"
        const val DAYS_OF_WEEK = "days_of_week" // bit mask Mon=1 .. Sun=64
        const val START = "start"            // minutes from midnight, -1 = any (read only)
        const val START_WINDOW = "start_window" // (read only)
        const val START_EXTRA = "start_extra"
        const val STOP_EXTRA = "stop_extra"
        const val REMOVAL = "removal"
        const val PRIORITY = "priority"
        const val DUP_DETECT = "dup_detect"
        const val ENABLED = "enabled"
        const val DIRECTORY = "directory"
        const val SERIESLINK_URI = "serieslink_uri"
    }

    object Timerec {
        const val ID = "_id"                 // string id
        const val NAME = "name"
        const val CHANNEL = "channel"
        const val TV_CHANNEL_ID = "tv_channel_id"
        const val DAYS_OF_WEEK = "days_of_week"
        const val START = "start"            // read: minutes from midnight; write: unix time
        const val STOP = "stop"              // read: minutes from midnight; write: unix time
        const val REMOVAL = "removal"
        const val PRIORITY = "priority"
        const val ENABLED = "enabled"
        const val DIRECTORY = "directory"
    }
}
