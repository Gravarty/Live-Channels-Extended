package com.android.tv.guide

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.tv.TvInputInfo
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.TextAppearanceSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.android.tv.R
import com.android.tv.TvSingletons
import com.android.tv.data.api.Channel
import com.android.tv.data.api.Program
import com.android.tv.dvr.DvrDataManager
import com.android.tv.dvr.DvrManager
import com.android.tv.dvr.data.ScheduledRecording
import com.android.tv.features.TvFeatures
import com.android.tv.ui.HardwareLayerAnimatorListenerAdapter
import com.android.tv.util.TvInputManagerHelper
import com.android.tv.util.Utils
import com.android.tv.util.images.ImageCache
import com.android.tv.util.images.ImageLoader
import com.android.tv.util.images.ImageLoader.ImageLoaderCallback
import com.android.tv.util.images.ImageLoader.LoadTvInputLogoTask
import com.android.tv.tweaks.Tweaks

/**
 * Kanalzeilen der Programmübersicht inkl. Kanal-Kopf (Nummer/Name/Logo/Input-Logo) und
 * Detailbereich der fokussierten Sendung (Poster, Zeit, Format, DVR-Status, Beschreibung).
 * Gesperrte Altersfreigaben sind ohne Systemrechte nicht lesbar (keine Inhalts-Sperre im Detail);
 * Kritiker-Bewertungen sind im AOSP-Build deaktiviert (UiFlags.enableCriticRatings = false).
 */
internal class ProgramTableAdapter(private val context: Context, private val programGuide: ProgramGuide) :
    RecyclerView.Adapter<ProgramTableAdapter.ProgramRowViewHolder>(), ProgramManager.TableEntryChangedListener {

    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    private val tvInputManagerHelper: TvInputManagerHelper = TvSingletons.getSingletons(context).getTvInputManagerHelper()
    private val dvrManager: DvrManager?
    private val dvrDataManager: DvrDataManager?
    private val programManager = programGuide.programManager
    private val handler = Handler(Looper.getMainLooper())
    private val programListAdapters = ArrayList<ProgramListAdapter>()
    private val recycledViewPool = RecyclerView.RecycledViewPool()
    private val res = context.resources
    private val channelLogoWidth = res.getDimensionPixelSize(R.dimen.program_guide_table_header_column_channel_logo_width)
    private val channelLogoHeight = res.getDimensionPixelSize(R.dimen.program_guide_table_header_column_channel_logo_height)
    private val imageWidth = res.getDimensionPixelSize(R.dimen.program_guide_table_detail_image_width)
    private val imageHeight = res.getDimensionPixelSize(R.dimen.program_guide_table_detail_image_height)
    private val programTitleForNoInformation = res.getString(R.string.program_title_for_no_information)
    private val programTitleForBlockedChannel = res.getString(R.string.program_title_for_blocked_channel)
    private val channelTextColor = res.getColor(R.color.program_guide_table_header_column_channel_number_text_color, null)
    private val channelBlockedTextColor = res.getColor(R.color.program_guide_table_header_column_channel_number_blocked_text_color, null)
    private val detailTextColor = res.getColor(R.color.program_guide_table_detail_title_text_color, null)
    private val detailGrayedTextColor = res.getColor(R.color.program_guide_table_detail_title_grayed_text_color, null)
    private val animationDuration = res.getInteger(R.integer.program_guide_table_detail_fade_anim_duration).toLong()
    private val detailPadding = res.getDimensionPixelOffset(R.dimen.program_guide_table_detail_padding).toFloat()
    private val programRecordableText = res.getString(R.string.dvr_epg_program_recordable)
    private val recordingScheduledText = res.getString(R.string.dvr_epg_program_recording_scheduled)
    private val recordingConflictText = res.getString(R.string.dvr_epg_program_recording_conflict)
    private val recordingFailedText = res.getString(R.string.dvr_epg_program_recording_failed)
    private val recordingInProgressText = res.getString(R.string.dvr_epg_program_recording_in_progress)
    private val dvrPaddingStartWithTrack = res.getDimensionPixelOffset(R.dimen.program_guide_table_detail_dvr_margin_start)
    private val dvrPaddingStartWithOutTrack = res.getDimensionPixelOffset(R.dimen.program_guide_table_detail_dvr_margin_start_without_track)
    private val episodeTitleStyle = TextAppearanceSpan(null, 0,
        res.getDimensionPixelSize(R.dimen.program_guide_table_detail_episode_title_text_size),
        ColorStateList.valueOf(res.getColor(R.color.program_guide_table_detail_episode_title_text_color, null)), null)
    private var recyclerView: RecyclerView? = null

    init {
        if (TvFeatures.isDvrEnabled(context)) {
            val singletons = TvSingletons.getSingletons(context)
            dvrManager = singletons.getDvrManager()
            dvrDataManager = singletons.getDvrDataManager()
        } else {
            dvrManager = null
            dvrDataManager = null
        }
        recycledViewPool.setMaxRecycledViews(R.layout.program_guide_table_item,
            res.getInteger(R.integer.max_recycled_view_pool_epg_table_item))
        programManager.addListener(object : ProgramManager.ListenerAdapter() {
            override fun onChannelsUpdated() = update()
        })
        update()
        programManager.addTableEntryChangedListener(this)
    }

    /** Je Kanal einen eigenen Zeilen-Adapter anlegen. */
    private fun update() {
        programListAdapters.forEach { programManager.removeTableEntriesUpdatedListener(it) }
        programListAdapters.clear()
        for (i in 0 until programManager.channelCount) {
            val listAdapter = ProgramListAdapter(res, programGuide, i)
            programManager.addTableEntriesUpdatedListener(listAdapter)
            programListAdapters.add(listAdapter)
        }
        if (recyclerView?.isComputingLayout == true) handler.post { notifyDataSetChanged() } else notifyDataSetChanged()
    }

    override fun getItemCount() = programListAdapters.size
    override fun getItemViewType(position: Int) = R.layout.program_guide_table_row

    override fun onBindViewHolder(holder: ProgramRowViewHolder, position: Int) = holder.onBind(position)

    override fun onBindViewHolder(holder: ProgramRowViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty()) holder.updateDetailView() else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProgramRowViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        itemView.findViewById<ProgramRow>(R.id.row).setRecycledViewPool(recycledViewPool)
        return ProgramRowViewHolder(itemView)
    }

    override fun onTableEntryChanged(entry: ProgramManager.TableEntry) {
        val channelIndex = programManager.getChannelIndex(entry.channelId)
        val pos = programManager.getProgramIdIndex(entry.channelId, entry.id)
        if (channelIndex in programListAdapters.indices) {
            programListAdapters[channelIndex].notifyItemChanged(pos, entry)
            notifyItemChanged(channelIndex, true)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
        super.onAttachedToRecyclerView(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    inner class ProgramRowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), ProgramRow.ChildFocusListener {
        private val container = itemView as ViewGroup
        private val programRow: ProgramRow = container.findViewById(R.id.row)
        private var selectedEntry: ProgramManager.TableEntry? = null
        private var detailOutAnimator: Animator? = null
        private var detailInAnimator: Animator? = null
        private val detailInStarter = Runnable {
            programRow.removeOnScrollListener(onScrollListener)
            detailInAnimator?.start()
        }
        private val updateDetailViewRunnable = Runnable { updateDetailView() }
        private val onScrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = onHorizontalScrolled()
        }
        private val globalFocusChangeListener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
            onChildFocus(if (GuideUtils.isDescendant(container, oldFocus)) oldFocus else null,
                if (GuideUtils.isDescendant(container, newFocus)) newFocus else null)
        }
        private val detailView: ViewGroup = container.findViewById(R.id.detail)
        private val imageView: ImageView = detailView.findViewById(R.id.image)
        private val blockView: ImageView = detailView.findViewById(R.id.block)
        private val titleView: TextView = detailView.findViewById(R.id.title)
        private val timeView: TextView = detailView.findViewById(R.id.time)
        private val criticScoresLayout: LinearLayout = detailView.findViewById(R.id.critic_scores)
        private val descriptionView: TextView = detailView.findViewById(R.id.desc)
        private val aspectRatioView: TextView = detailView.findViewById(R.id.aspect_ratio)
        private val resolutionView: TextView = detailView.findViewById(R.id.resolution)
        private val dvrIconView: ImageView = detailView.findViewById(R.id.dvr_icon)
        private val dvrTextIconView: TextView = detailView.findViewById(R.id.dvr_text_icon)
        private val dvrStatusView: TextView = detailView.findViewById(R.id.dvr_status)
        private val dvrIndicator: ViewGroup = container.findViewById(R.id.dvr_indicator)
        internal var channel: Channel? = null
            private set
        private val channelHeaderView: View = container.findViewById(R.id.header_column)
        private val channelNumberView: TextView = container.findViewById(R.id.channel_number)
        private val channelNameView: TextView = container.findViewById(R.id.channel_name)
        private val channelLogoView: ImageView = container.findViewById(R.id.channel_logo)
        private val channelBlockView: ImageView = container.findViewById(R.id.channel_block)
        private val inputLogoView: ImageView = container.findViewById(R.id.input_logo)
        private var isInputLogoVisible = false
        private val accessibilityStateChangeListener =
            AccessibilityManager.AccessibilityStateChangeListener { enabled -> channelHeaderView.isFocusable = enabled }

        init {
            container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    container.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusChangeListener)
                    accessibilityManager.addAccessibilityStateChangeListener(accessibilityStateChangeListener)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    container.viewTreeObserver.removeOnGlobalFocusChangeListener(globalFocusChangeListener)
                    accessibilityManager.removeAccessibilityStateChangeListener(accessibilityStateChangeListener)
                }
            })
            // Kanal-Kopf nur für Screenreader fokussierbar
            channelHeaderView.isFocusable = accessibilityManager.isEnabled
        }

        fun onBind(position: Int) {
            onBindChannel(programManager.getChannel(position))
            programRow.swapAdapter(programListAdapters[position], true)
            programRow.setProgramGuide(programGuide)
            programRow.setChannel(programManager.getChannel(position))
            programRow.setChildFocusListener(this)
            programRow.resetScroll(programGuide.getTimelineRowScrollOffset())
            detailView.visibility = View.GONE
            // Letzte Zeile mit abgerundetem Hintergrund
            channelHeaderView.setBackgroundResource(
                if (position < programListAdapters.size - 1) R.drawable.program_guide_table_header_column_item_background
                else R.drawable.program_guide_table_header_column_last_item_background)
        }

        private fun onBindChannel(channel: Channel?) {
            this.channel = channel
            inputLogoView.visibility = View.GONE
            isInputLogoVisible = false
            if (channel == null) {
                channelNumberView.visibility = View.GONE
                channelNameView.visibility = View.GONE
                channelLogoView.visibility = View.GONE
                channelBlockView.visibility = View.GONE
                return
            }
            val displayNumber = channel.displayNumber
            if (displayNumber == null) {
                channelNumberView.visibility = View.GONE
            } else {
                val size = if (displayNumber.length <= 4) R.dimen.program_guide_table_header_column_channel_number_large_font_size
                else R.dimen.program_guide_table_header_column_channel_number_small_font_size
                channelNumberView.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(size))
                channelNumberView.text = displayNumber
                channelNumberView.visibility = View.VISIBLE
            }
            val locked = tvInputManagerHelper.isParentalControlsEnabled() && channel.isLocked
            channelNumberView.setTextColor(if (locked) channelBlockedTextColor else channelTextColor)
            channelLogoView.setImageBitmap(null)
            channelLogoView.visibility = View.GONE
            if (locked) {
                channelNameView.visibility = View.GONE
                channelBlockView.visibility = View.VISIBLE
            } else {
                channelNameView.text = channel.displayName
                channelNameView.visibility = View.VISIBLE
                channelBlockView.visibility = View.GONE
                channel.loadBitmap(itemView.context, Channel.LOAD_IMAGE_TYPE_CHANNEL_LOGO, channelLogoWidth, channelLogoHeight,
                    createChannelLogoLoadedCallback(this, channel.id))
            }
        }

        /** Detailbereich beim Fokuswechsel mit Aus-/Einblenden aktualisieren. */
        override fun onChildFocus(oldFocus: View?, newFocus: View?) {
            if (newFocus == null) return
            selectedEntry = when {
                // Mit Screenreader kann der Kanal-Kopf fokussiert sein
                newFocus === channelHeaderView -> (programRow.getChildAt(0) as? ProgramItemView)?.tableEntry ?: return
                newFocus === detailView -> return
                else -> (newFocus as ProgramItemView).tableEntry
            }
            if (oldFocus == null) {
                // Fokus neu in dieser Zeile: sofort anzeigen
                if (programGuide.programGrid.isInLayout) handler.post(updateDetailViewRunnable) else updateDetailView()
                return
            }
            selectedEntry?.program?.takeIf { Program.isProgramValid(it) }?.prefetchPosterArt(itemView.context, imageWidth, imageHeight)
            val direction = if (oldFocus.left < newFocus.left) -1 else 1
            val detailContentView = detailView.findViewById<View>(R.id.detail_content)
            if (detailInAnimator == null) {
                detailOutAnimator = ObjectAnimator.ofPropertyValuesHolder(detailContentView,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, direction * detailPadding)).apply {
                    duration = animationDuration
                    addListener(object : HardwareLayerAnimatorListenerAdapter(detailContentView) {
                        override fun onAnimationEnd(animator: Animator) {
                            super.onAnimationEnd(animator)
                            detailOutAnimator = null
                            handler.removeCallbacks(detailInStarter)
                            handler.postDelayed(detailInStarter, animationDuration)
                        }
                    })
                }
                programRow.addOnScrollListener(onScrollListener)
                detailOutAnimator!!.start()
            } else {
                if (detailInAnimator!!.isStarted) {
                    detailInAnimator!!.cancel()
                    detailContentView.alpha = 0f
                }
                handler.removeCallbacks(detailInStarter)
                handler.postDelayed(detailInStarter, animationDuration)
            }
            detailInAnimator = ObjectAnimator.ofPropertyValuesHolder(detailContentView,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, direction * -detailPadding, 0f)).apply {
                duration = animationDuration
                addListener(object : HardwareLayerAnimatorListenerAdapter(detailContentView) {
                    override fun onAnimationStart(animator: Animator) {
                        super.onAnimationStart(animator)
                        updateDetailView()
                    }

                    override fun onAnimationEnd(animator: Animator) {
                        super.onAnimationEnd(animator)
                        detailInAnimator = null
                    }
                })
            }
        }

        internal fun updateDetailView() {
            val entry = selectedEntry ?: return
            criticScoresLayout.removeAllViews()
            val program = entry.program
            if (program != null && Program.isProgramValid(program)) {
                titleView.setTextColor(detailTextColor)
                val ctx = itemView.context
                updatePosterArt(null)
                program.loadPosterArt(ctx, imageWidth, imageHeight, createProgramPosterArtCallback(this, program))
                val episodeTitle = program.getEpisodeDisplayTitle(context)
                if (episodeTitle.isNullOrEmpty()) {
                    titleView.text = program.title
                } else {
                    val fullTitle = "${program.title}  $episodeTitle"
                    titleView.text = SpannableString(fullTitle).apply {
                        setSpan(episodeTitleStyle, fullTitle.length - episodeTitle.length, fullTitle.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                updateTextView(timeView, program.getDurationString(ctx))
                var trackMetaDataVisible = updateTextView(aspectRatioView, Utils.getAspectRatioString(program.videoWidth, program.videoHeight))
                val level = Utils.getVideoDefinitionLevelFromSize(program.videoWidth, program.videoHeight)
                trackMetaDataVisible = updateTextView(resolutionView, Utils.getVideoDefinitionLevelString(ctx, level)) or trackMetaDataVisible
                updateDvrIndicator(program, trackMetaDataVisible)
                // Kritiker-Bewertungen: UiFlags.enableCriticRatings() ist im AOSP-Build false
                blockView.visibility = View.GONE
                updateTextView(descriptionView, program.description)
            } else {
                titleView.setTextColor(detailGrayedTextColor)
                updateTextView(titleView, if (entry.isBlocked) programTitleForBlockedChannel else programTitleForNoInformation)
                imageView.visibility = View.GONE
                blockView.visibility = View.GONE
                timeView.visibility = View.GONE
                dvrIndicator.visibility = View.GONE
                descriptionView.visibility = View.GONE
                aspectRatioView.visibility = View.GONE
                resolutionView.visibility = View.GONE
            }
        }

        private fun updateDvrIndicator(program: Program, trackMetaDataVisible: Boolean) {
            val manager = dvrManager
            if (manager == null || !manager.isProgramRecordable(program)) {
                dvrIndicator.visibility = View.GONE
                return
            }
            val schedule = dvrDataManager?.getScheduledRecordingForProgramId(program.id)
            var statusText = programRecordableText
            var iconResId = 0
            if (schedule != null) {
                if (manager.isConflicting(schedule)) {
                    iconResId = R.drawable.ic_warning_white_12dp
                    statusText = recordingConflictText
                } else {
                    when (schedule.state) {
                        ScheduledRecording.STATE_RECORDING_IN_PROGRESS -> {
                            iconResId = R.drawable.ic_recording_program
                            statusText = recordingInProgressText
                        }
                        ScheduledRecording.STATE_RECORDING_NOT_STARTED -> {
                            iconResId = R.drawable.ic_scheduled_white
                            statusText = recordingScheduledText
                        }
                        ScheduledRecording.STATE_RECORDING_FAILED -> {
                            iconResId = R.drawable.ic_warning_white_12dp
                            statusText = recordingFailedText
                        }
                    }
                }
            }
            if (iconResId == 0) {
                dvrIconView.visibility = View.GONE
                dvrTextIconView.visibility = View.VISIBLE
            } else {
                dvrTextIconView.visibility = View.GONE
                dvrIconView.setImageResource(iconResId)
                dvrIconView.visibility = View.VISIBLE
            }
            dvrIndicator.setPaddingRelative(if (trackMetaDataVisible) dvrPaddingStartWithTrack else dvrPaddingStartWithOutTrack, 0, 0, 0)
            dvrIndicator.visibility = View.VISIBLE
            dvrStatusView.text = statusText
        }

        /** Input-Logo nur in der ersten Zeile eines Inputs. */
        internal fun updateInputLogo(lastPosition: Int, forceShow: Boolean) {
            val ch = channel
            // Tweak: Provider-Logo verstecken
            if (ch == null || Tweaks.isProviderLogoHidden(itemView.context)) {
                inputLogoView.visibility = View.GONE
                isInputLogoVisible = false
                return
            }
            val showLogo = forceShow || programManager.getChannel(lastPosition)?.inputId != ch.inputId
            if (showLogo) {
                if (!isInputLogoVisible) {
                    isInputLogoVisible = true
                    tvInputManagerHelper.getTvInputInfo(ch.inputId)?.let { info ->
                        ImageLoader.loadBitmap(createTvInputLogoLoadedCallback(info, this),
                            LoadTvInputLogoTask(itemView.context, ImageCache.getInstance(), info))
                    }
                }
            } else {
                inputLogoView.visibility = View.GONE
                inputLogoView.setImageDrawable(null)
                isInputLogoVisible = false
            }
        }

        private fun updateTextView(textView: TextView, text: String?): Boolean {
            if (text.isNullOrEmpty()) {
                textView.visibility = View.GONE
                return false
            }
            textView.visibility = View.VISIBLE
            textView.text = text
            return true
        }

        internal fun updatePosterArt(posterArt: Bitmap?) {
            imageView.setImageBitmap(posterArt)
            imageView.visibility = if (posterArt == null) View.GONE else View.VISIBLE
        }

        internal fun updateChannelLogo(logo: Bitmap?) {
            channelLogoView.setImageBitmap(logo)
            channelNameView.visibility = View.GONE
            channelLogoView.visibility = View.VISIBLE
        }

        internal fun updateInputLogoInternal(tvInputLogo: Bitmap) {
            if (!isInputLogoVisible) return
            inputLogoView.setImageBitmap(tvInputLogo)
            inputLogoView.visibility = View.VISIBLE
        }

        internal val selectedProgramPosterArtUri: String? get() = selectedEntry?.program?.posterArtUri

        private fun onHorizontalScrolled() {
            // Einblenden verschieben, solange gescrollt wird
            if (detailInAnimator != null) {
                handler.removeCallbacks(detailInStarter)
                handler.postDelayed(detailInStarter, animationDuration)
            }
        }
    }

    companion object {
        private fun createProgramPosterArtCallback(holder: ProgramRowViewHolder, program: Program) =
            object : ImageLoaderCallback<ProgramRowViewHolder>(holder) {
                override fun onBitmapLoaded(referent: ProgramRowViewHolder, bitmap: Bitmap?) {
                    val uri = referent.selectedProgramPosterArtUri
                    if (bitmap == null || uri == null || uri != program.posterArtUri) return
                    referent.updatePosterArt(bitmap)
                }
            }

        private fun createChannelLogoLoadedCallback(holder: ProgramRowViewHolder, channelId: Long) =
            object : ImageLoaderCallback<ProgramRowViewHolder>(holder) {
                override fun onBitmapLoaded(referent: ProgramRowViewHolder, bitmap: Bitmap?) {
                    if (bitmap == null || referent.channel?.id != channelId) return
                    referent.updateChannelLogo(bitmap)
                }
            }

        private fun createTvInputLogoLoadedCallback(info: TvInputInfo, holder: ProgramRowViewHolder) =
            object : ImageLoaderCallback<ProgramRowViewHolder>(holder) {
                override fun onBitmapLoaded(referent: ProgramRowViewHolder, bitmap: Bitmap?) {
                    if (bitmap != null && info.id == referent.channel?.inputId) referent.updateInputLogoInternal(bitmap)
                }
            }
    }
}
