package com.android.tv.guide

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.android.tv.R
import com.android.tv.guide.ProgramManager.TableEntry

/** Einträge einer Kanalzeile in der Programmübersicht. */
internal class ProgramListAdapter(res: Resources, private val programGuide: ProgramGuide, private val channelIndex: Int) :
    RecyclerView.Adapter<ProgramListAdapter.ProgramItemViewHolder>(), ProgramManager.TableEntriesUpdatedListener {

    private val programManager = programGuide.programManager
    private val noInfoProgramTitle = res.getString(R.string.program_title_for_no_information)
    private val blockedProgramTitle = res.getString(R.string.program_title_for_blocked_channel)
    private var channelId = 0L

    init {
        setHasStableIds(true)
        onTableEntriesUpdated()
    }

    override fun onTableEntriesUpdated() {
        val channel = programManager.getChannel(channelIndex) ?: return // Kanal verschwunden
        channelId = channel.id
        notifyDataSetChanged()
    }

    override fun getItemCount() = programManager.getTableEntryCount(channelId)
    override fun getItemViewType(position: Int) = R.layout.program_guide_table_item
    override fun getItemId(position: Int) = programManager.getTableEntry(channelId, position).id

    override fun onBindViewHolder(holder: ProgramItemViewHolder, position: Int) {
        val entry = programManager.getTableEntry(channelId, position)
        holder.onBind(entry, programGuide, if (entry.isBlocked) blockedProgramTitle else noInfoProgramTitle)
    }

    override fun onViewRecycled(holder: ProgramItemViewHolder) = holder.onUnbind()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ProgramItemViewHolder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))

    class ProgramItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onBind(entry: TableEntry, programGuide: ProgramGuide, gapTitle: String) {
            val pm = programGuide.programManager
            (itemView as ProgramItemView).setValues(programGuide, entry, pm.selectedGenreId, pm.fromUtcMillis, pm.toUtcMillis, gapTitle)
        }

        fun onUnbind() = (itemView as ProgramItemView).clearValues()
    }
}
