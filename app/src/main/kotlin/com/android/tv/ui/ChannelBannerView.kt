package com.android.tv.ui

import android.animation.Animator
import android.animation.AnimatorInflater
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.media.tv.TvContentRating
import android.media.tv.TvInputInfo
import android.text.Spannable
import android.text.SpannableString
import android.text.format.DateUtils
import android.text.style.TextAppearanceSpan
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import com.android.tv.R
import com.android.tv.common.SoftPreconditions
import com.android.tv.common.singletons.HasSingletons
import com.android.tv.data.ProgramImpl
import com.android.tv.data.StreamInfo
import com.android.tv.data.api.Channel
import com.android.tv.data.api.Program
import com.android.tv.dvr.DvrManager
import com.android.tv.dvr.data.ScheduledRecording
import com.android.tv.features.TvFeatures
import com.android.tv.ui.TvTransitionManager.TransitionLayout
import com.android.tv.ui.hideable.AutoHideScheduler
import com.android.tv.util.TvInputManagerHelper
import com.android.tv.util.Utils
import com.android.tv.util.images.ImageCache
import com.android.tv.util.images.ImageLoader
import com.android.tv.util.images.ImageLoader.ImageLoaderCallback
import com.android.tv.util.images.ImageLoader.LoadTvInputLogoTask
import com.android.tv.tweaks.Tweaks
import javax.inject.Provider

/**
 * Port von ChannelBannerView: Info-Banner beim Umschalten (Nummer, Name, Logo, Sendung, Zeit,
 * Fortschritt, Aufnahme-Symbol, Stream-Infos).
 * Namen von Altersfreigaben: ohne System-Rechte nur "Nicht eingestuft" (wie im Original).
 */
class ChannelBannerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle), TransitionLayout, AccessibilityStateChangeListener {

    interface MySingletons {
        fun getCurrentChannelProvider(): Provider<Channel?>
        fun getCurrentProgramProvider(): Provider<Program?>
        fun getOverlayManagerProvider(): Provider<TvOverlayManager>
        fun getTvInputManagerHelperSingleton(): TvInputManagerHelper
        fun getCurrentPlayingPositionProvider(): Provider<Long>
        /** null ohne DVR. */
        fun getDvrManagerSingleton(): DvrManager?
    }

    private val singletons: MySingletons = HasSingletons.get(context)
    private val currentChannelProvider = singletons.getCurrentChannelProvider()
    private val currentProgramProvider = singletons.getCurrentProgramProvider()
    private val currentPlayingPositionProvider = singletons.getCurrentPlayingPositionProvider()
    private val tvInputManagerHelper = singletons.getTvInputManagerHelperSingleton()
    private val tvOverlayManager = singletons.getOverlayManagerProvider()
    private val dvrManager: DvrManager? = if (TvFeatures.isDvrEnabled(context)) singletons.getDvrManagerSingleton() else null
    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)

    private val noProgram: Program = ProgramImpl.Builder()
        .setTitle(context.getString(R.string.channel_banner_no_title)).setDescription(EMPTY_STRING).build()
    private val lockedChannelProgram: Program = ProgramImpl.Builder()
        .setTitle(context.getString(R.string.channel_banner_locked_channel_title)).setDescription(EMPTY_STRING).build()
    private val closedCaptionMark = context.getString(R.string.closed_caption)

    private lateinit var channelView: View
    private lateinit var channelNumberTextView: TextView
    private lateinit var channelLogoImageView: ImageView
    private lateinit var programTextView: TextView
    private lateinit var tvInputLogoImageView: ImageView
    private lateinit var channelSignalStrengthView: ImageView
    private lateinit var channelNameTextView: TextView
    private lateinit var programTimeTextView: TextView
    private lateinit var remainingTimeView: ProgressBar
    private lateinit var recordingIndicatorView: TextView
    private lateinit var closedCaptionTextView: TextView
    private lateinit var aspectRatioTextView: TextView
    private lateinit var resolutionTextView: TextView
    private lateinit var audioChannelTextView: TextView
    private val contentRatingsTextViews = arrayOfNulls<TextView>(DISPLAYED_CONTENT_RATINGS_COUNT)
    private lateinit var programDescriptionTextView: TextView
    private var programDescriptionText: String = ""
    private lateinit var anchorView: View

    private var currentChannel: Channel? = null
    private var currentChannelLogoExists = false
    private var lastUpdatedProgram: Program? = null
    private val autoHideScheduler = AutoHideScheduler(context, ::hide)
    private var blockingContentRating: TvContentRating? = null
    private var lockType = LOCK_NONE
    private var updateOnTune = false
    private var resizeAnimator: Animator? = null
    private var currentHeight = 0
    private var programInfoUpdatePendingByResizing = false

    private val showDurationMillis = resources.getInteger(R.integer.channel_banner_show_duration).toLong()
    private val channelLogoImageViewWidth = resources.getDimensionPixelSize(R.dimen.channel_banner_channel_logo_width)
    private val channelLogoImageViewHeight = resources.getDimensionPixelSize(R.dimen.channel_banner_channel_logo_height)
    private val channelLogoImageViewMarginStart = resources.getDimensionPixelSize(R.dimen.channel_banner_channel_logo_margin_start)
    private val programDescriptionTextViewWidth = resources.getDimensionPixelSize(R.dimen.channel_banner_program_description_width)
    private val channelBannerTextColor = resources.getColor(R.color.channel_banner_text_color, null)
    private val channelBannerDimTextColor = resources.getColor(R.color.channel_banner_dim_text_color, null)
    private val resizeAnimDuration = resources.getInteger(R.integer.channel_banner_fast_anim_duration).toLong()
    private val recordingIconPadding = resources.getDimensionPixelOffset(R.dimen.channel_banner_recording_icon_padding)
    private val resizeInterpolator = AnimationUtils.loadInterpolator(context, android.R.interpolator.linear_out_slow_in)
    private val programDescriptionFadeInAnimator =
        AnimatorInflater.loadAnimator(context, R.animator.channel_banner_program_description_fade_in)
    private val programDescriptionFadeOutAnimator =
        AnimatorInflater.loadAnimator(context, R.animator.channel_banner_program_description_fade_out)

    private val resizeAnimatorListener = object : AnimatorListenerAdapter() {
        override fun onAnimationStart(animator: Animator) { programInfoUpdatePendingByResizing = false }

        override fun onAnimationEnd(animator: Animator) {
            programDescriptionTextView.alpha = 1f
            resizeAnimator = null
            if (programInfoUpdatePendingByResizing) {
                programInfoUpdatePendingByResizing = false
                updateProgramInfo(lastUpdatedProgram)
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        channelView = findViewById(R.id.channel_banner_view)
        channelNumberTextView = findViewById(R.id.channel_number)
        channelLogoImageView = findViewById(R.id.channel_logo)
        programTextView = findViewById(R.id.program_text)
        tvInputLogoImageView = findViewById(R.id.tvinput_logo)
        channelSignalStrengthView = findViewById(R.id.channel_signal_strength)
        channelNameTextView = findViewById(R.id.channel_name)
        programTimeTextView = findViewById(R.id.program_time_text)
        remainingTimeView = findViewById(R.id.remaining_time)
        recordingIndicatorView = findViewById(R.id.recording_indicator)
        closedCaptionTextView = findViewById(R.id.closed_caption)
        aspectRatioTextView = findViewById(R.id.aspect_ratio)
        resolutionTextView = findViewById(R.id.resolution)
        audioChannelTextView = findViewById(R.id.audio_channel)
        contentRatingsTextViews[0] = findViewById(R.id.content_ratings_0)
        contentRatingsTextViews[1] = findViewById(R.id.content_ratings_1)
        contentRatingsTextViews[2] = findViewById(R.id.content_ratings_2)
        programDescriptionTextView = findViewById(R.id.program_description)
        anchorView = findViewById(R.id.anchor)
        programDescriptionFadeInAnimator.setTarget(programDescriptionTextView)
        programDescriptionFadeOutAnimator.setTarget(programDescriptionTextView)
        programDescriptionFadeOutAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) { programDescriptionTextView.text = programDescriptionText }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accessibilityManager.addAccessibilityStateChangeListener(autoHideScheduler)
    }

    override fun onDetachedFromWindow() {
        accessibilityManager.removeAccessibilityStateChangeListener(autoHideScheduler)
        super.onDetachedFromWindow()
    }

    override fun onEnterAction(fromEmptyScene: Boolean) {
        resetAnimationEffects()
        if (fromEmptyScene) ViewUtils.setTransitionAlpha(channelView, 1f)
        autoHideScheduler.schedule(showDurationMillis)
    }

    override fun onExitAction() {
        currentHeight = 0
        autoHideScheduler.cancel()
    }

    private fun resetAnimationEffects() {
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        translationY = 0f
    }

    /** Setzt die Sperrart und liefert die vorherige. */
    fun setLockType(lockType: Int): Int {
        require(lockType == LOCK_NONE || lockType == LOCK_CHANNEL_INFO || lockType == LOCK_PROGRAM_DETAIL) {
            "No such lock type $lockType"
        }
        val previous = this.lockType
        this.lockType = lockType
        return previous
    }

    fun setBlockingContentRating(rating: TvContentRating?) {
        blockingContentRating = rating
        updateProgramRatings(currentProgramProvider.get())
    }

    /** Aktualisiert das Banner; [updateOnTune] = nach Kanalwechsel (Kanal-Infos neu laden). */
    fun updateViews(updateOnTune: Boolean) {
        resetAnimationEffects()
        channelView.visibility = VISIBLE
        this.updateOnTune = updateOnTune
        if (updateOnTune) {
            if (isShown) autoHideScheduler.schedule(showDurationMillis)
            blockingContentRating = null
            currentChannel = currentChannelProvider.get()
            currentChannelLogoExists = currentChannel?.channelLogoExists() == true
            updateStreamInfo(null)
            updateChannelInfo()
        } else if (providerLogoHidden != Tweaks.isProviderLogoHidden(context)) {
            // Tweak: geänderten Schalter sofort übernehmen, nicht erst beim nächsten Senderwechsel
            updateChannelInfo()
        }
        updateProgramInfo(currentProgramProvider.get())
        this.updateOnTune = false
    }

    private fun hide() {
        currentHeight = 0
        tvOverlayManager.get().hideOverlays(
            TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_DIALOG or
                TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANELS or
                TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_PROGRAM_GUIDE or
                TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_MENU or
                TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_FRAGMENT)
    }

    fun updateStreamInfo(info: StreamInfo?) {
        if (lockType != LOCK_CHANNEL_INFO && info != null) {
            updateText(closedCaptionTextView, if (info.hasClosedCaption()) closedCaptionMark else EMPTY_STRING)
            updateText(aspectRatioTextView, Utils.getAspectRatioString(info.videoDisplayAspectRatio))
            updateText(resolutionTextView, Utils.getVideoDefinitionLevelString(context, info.videoDefinitionLevel))
            updateText(audioChannelTextView, Utils.getAudioChannelString(context, info.audioChannelCount))
        } else {
            closedCaptionTextView.visibility = GONE
            aspectRatioTextView.visibility = GONE
            resolutionTextView.visibility = GONE
            audioChannelTextView.visibility = GONE
        }
    }

    private fun updateChannelInfo() {
        val channel = currentChannel
        val displayNumber = channel?.displayNumber ?: EMPTY_STRING
        val displayName = channel?.displayName ?: EMPTY_STRING
        channelNumberTextView.visibility = if (displayNumber.isEmpty()) GONE else VISIBLE
        // Schriftgröße nach Länge der Nummer
        when {
            displayNumber.length <= 3 -> updateTextView(channelNumberTextView,
                R.dimen.channel_banner_channel_number_large_text_size, R.dimen.channel_banner_channel_number_large_margin_top)
            displayNumber.length <= 4 -> updateTextView(channelNumberTextView,
                R.dimen.channel_banner_channel_number_medium_text_size, R.dimen.channel_banner_channel_number_medium_margin_top)
            else -> updateTextView(channelNumberTextView,
                R.dimen.channel_banner_channel_number_small_text_size, R.dimen.channel_banner_channel_number_small_margin_top)
        }
        channelNumberTextView.text = displayNumber
        channelNameTextView.text = displayName

        val info = tvInputManagerHelper.getTvInputInfo(getCurrentInputId())
        // Tweak: Provider-Logo verstecken
        providerLogoHidden = Tweaks.isProviderLogoHidden(context)
        if (providerLogoHidden || info == null ||
            !ImageLoader.loadBitmap(createTvInputLogoLoaderCallback(info, this),
                LoadTvInputLogoTask(context, ImageCache.getInstance(), info))
        ) {
            tvInputLogoImageView.visibility = GONE
            tvInputLogoImageView.setImageDrawable(null)
        }
        channelLogoImageView.setImageBitmap(null)
        channelLogoImageView.visibility = GONE
        if (channel != null && currentChannelLogoExists) {
            channel.loadBitmap(context, Channel.LOAD_IMAGE_TYPE_CHANNEL_LOGO,
                channelLogoImageViewWidth, channelLogoImageViewHeight, createChannelLogoCallback(this, channel))
        }
    }

    // Tweak: Stand des Schalters beim letzten updateChannelInfo()
    private var providerLogoHidden = false

    private fun getCurrentInputId(): String? = currentChannelProvider.get()?.inputId

    private fun updateTvInputLogo(bitmap: Bitmap) {
        tvInputLogoImageView.visibility = VISIBLE
        tvInputLogoImageView.setImageBitmap(bitmap)
    }

    private fun updateText(view: TextView, text: String?) {
        if (text.isNullOrEmpty()) {
            view.visibility = GONE
        } else {
            view.visibility = VISIBLE
            view.text = text
        }
    }

    private fun updateTextView(textView: TextView, sizeRes: Int, marginTopRes: Int) {
        val textSize = resources.getDimension(sizeRes)
        if (textView.textSize != textSize) textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        updateTopMargin(textView, marginTopRes)
    }

    private fun updateTopMargin(view: View, marginTopRes: Int) {
        val lp = view.layoutParams as RelativeLayout.LayoutParams
        val topMargin = resources.getDimension(marginTopRes).toInt()
        if (lp.topMargin != topMargin) {
            lp.topMargin = topMargin
            view.layoutParams = lp
        }
    }

    /** Signalstärke 0–100 → Balkensymbol; andere Werte blenden das Symbol aus. */
    fun updateChannelSignalStrengthView(value: Int) {
        val resId = signalStrengthToResId(value)
        if (resId != 0) {
            channelSignalStrengthView.visibility = VISIBLE
            channelSignalStrengthView.setImageResource(resId)
        } else {
            channelSignalStrengthView.visibility = GONE
        }
    }

    private fun signalStrengthToResId(value: Int): Int = when {
        value !in 0..100 -> 0
        value <= SIGNAL_STRENGTH_0_OF_4_UPPER_BOUND -> R.drawable.quantum_ic_signal_cellular_0_bar_white_24
        value <= SIGNAL_STRENGTH_1_OF_4_UPPER_BOUND -> R.drawable.quantum_ic_signal_cellular_1_bar_white_24
        value <= SIGNAL_STRENGTH_2_OF_4_UPPER_BOUND -> R.drawable.quantum_ic_signal_cellular_2_bar_white_24
        value <= SIGNAL_STRENGTH_3_OF_4_UPPER_BOUND -> R.drawable.quantum_ic_signal_cellular_3_bar_white_24
        else -> R.drawable.quantum_ic_signal_cellular_4_bar_white_24
    }

    private fun updateLogo(logo: Bitmap?) {
        if (logo == null) {
            // Kein Logo: Programmtext darf die Logobreite nutzen
            updateProgramTextView(lastUpdatedProgram)
            return
        }
        channelLogoImageView.setImageBitmap(logo)
        channelLogoImageView.visibility = VISIBLE
        updateProgramTextView(lastUpdatedProgram)
        if (resizeAnimator == null) {
            val description = programDescriptionTextView.text.toString()
            updateBannerHeight(description != programDescriptionText && !updateOnTune)
        } else {
            programInfoUpdatePendingByResizing = true
        }
    }

    private fun updateProgramInfo(input: Program?) {
        val program = when {
            lockType == LOCK_CHANNEL_INFO -> lockedChannelProgram
            input == null || !input.isValid || input.title.isNullOrEmpty() -> noProgram
            else -> input
        }
        val last = lastUpdatedProgram
        if (last == null || program.title != last.title ||
            program.getEpisodeDisplayTitle(context) != last.getEpisodeDisplayTitle(context)
        ) {
            updateProgramTextView(program)
        }
        updateProgramTimeInfo(program)
        updateRecordingStatus(program)
        updateProgramRatings(program)

        val isProgramChanged = program != last
        val animator = resizeAnimator
        if (animator != null && isProgramChanged) {
            // Nach der laufenden Animation erneut aktualisieren
            lastUpdatedProgram = program
            programInfoUpdatePendingByResizing = true
            animator.cancel()
        } else if (animator == null) {
            val description = program.description
            if (lockType != LOCK_NONE || description.isNullOrEmpty()) {
                programDescriptionTextView.visibility = GONE
                programDescriptionText = ""
            } else {
                programDescriptionTextView.visibility = VISIBLE
                programDescriptionText = description
            }
            val shown = programDescriptionTextView.text.toString()
            updateBannerHeight((isProgramChanged || shown != programDescriptionText) && !updateOnTune)
        } else {
            programInfoUpdatePendingByResizing = true
        }
        lastUpdatedProgram = program
    }

    private fun updateProgramTextView(program: Program?) {
        if (program == null) return
        updateProgramTextView(program == lockedChannelProgram, program.title, program.getEpisodeDisplayTitle(context))
    }

    /** Titel (+ Folgentitel kleiner), bei Überlänge zweizeilig mit kleinerer Schrift. */
    private fun updateProgramTextView(dimText: Boolean, title: String?, episodeDisplayTitle: String?) {
        programTextView.visibility = VISIBLE
        programTextView.setTextColor(if (dimText) channelBannerDimTextColor else channelBannerTextColor)
        updateTextView(programTextView, R.dimen.channel_banner_program_large_text_size,
            R.dimen.channel_banner_program_large_margin_top)
        if (episodeDisplayTitle.isNullOrEmpty()) {
            programTextView.text = title
        } else {
            val fullTitle = "$title  $episodeDisplayTitle"
            programTextView.text = SpannableString(fullTitle).apply {
                setSpan(TextAppearanceSpan(context, R.style.text_appearance_channel_banner_episode_title),
                    fullTitle.length - episodeDisplayTitle.length, fullTitle.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val width = programDescriptionTextViewWidth +
            if (currentChannelLogoExists) 0 else channelLogoImageViewWidth + channelLogoImageViewMarginStart
        programTextView.layoutParams = programTextView.layoutParams.apply { this.width = width }
        programTextView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        var oneline = programTextView.lineCount == 1
        if (!oneline) {
            updateTextView(programTextView, R.dimen.channel_banner_program_medium_text_size,
                R.dimen.channel_banner_program_medium_margin_top)
            programTextView.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            oneline = programTextView.lineCount == 1
        }
        updateTopMargin(anchorView,
            if (oneline) R.dimen.channel_banner_anchor_one_line_y else R.dimen.channel_banner_anchor_two_line_y)
    }

    /**
     * Altersfreigaben (max. 3). Anzeigename nur für "Nicht eingestuft" – die Rating-Systeme
     * (ContentRatingsManager) sind nur für System-Apps lesbar, genau wie im Original.
     */
    private fun updateProgramRatings(program: Program?) {
        when {
            lockType == LOCK_CHANNEL_INFO -> contentRatingsTextViews.forEach { it?.visibility = GONE }
            blockingContentRating != null -> {
                val name = getDisplayNameForRating(blockingContentRating)
                contentRatingsTextViews[0]?.apply {
                    if (name.isNullOrEmpty()) visibility = GONE else { text = name; visibility = VISIBLE }
                }
                for (i in 1 until DISPLAYED_CONTENT_RATINGS_COUNT) contentRatingsTextViews[i]?.visibility = GONE
            }
            else -> {
                var index = 0
                program?.contentRatings?.forEach { rating ->
                    val name = getDisplayNameForRating(rating)
                    if (index < DISPLAYED_CONTENT_RATINGS_COUNT && !name.isNullOrEmpty()) {
                        contentRatingsTextViews[index]?.apply { text = name; visibility = VISIBLE }
                        index++
                    }
                }
                while (index < DISPLAYED_CONTENT_RATINGS_COUNT) contentRatingsTextViews[index++]?.visibility = GONE
            }
        }
    }

    private fun getDisplayNameForRating(rating: TvContentRating?): String? =
        if (rating == TvContentRating.UNRATED) resources.getString(R.string.unrated_rating_name) else null

    private fun updateProgramTimeInfo(program: Program) {
        if (lockType != LOCK_CHANNEL_INFO && program.durationMillis > 0 && program.startTimeUtcMillis > 0) {
            programTimeTextView.visibility = VISIBLE
            remainingTimeView.visibility = VISIBLE
            programTimeTextView.text = program.getDurationString(context)
        } else {
            programTimeTextView.visibility = GONE
            remainingTimeView.visibility = GONE
        }
    }

    private fun getProgressPercent(currTime: Long, startTime: Long, endTime: Long): Int = when {
        currTime <= startTime -> 0
        currTime >= endTime -> 100
        else -> (100 * (currTime - startTime) / (endTime - startTime)).toInt()
    }

    private fun updateRecordingStatus(program: Program) {
        val manager = dvrManager
        if (manager == null) {
            updateProgressBarAndRecIcon(program, null)
            return
        }
        val currentRecording = currentChannel?.let { manager.getCurrentRecording(it.id) }
        if (DEBUG) Log.d(TAG, if (currentRecording == null) "No Recording" else "Recording:$currentRecording")
        updateProgressBarAndRecIcon(program,
            if (currentRecording != null && isCurrentProgram(currentRecording, program)) currentRecording else null)
    }

    /** Fortschritt der Sendung; bei Aufnahme primär = Aufnahmebeginn, sekundär = aktuelle Position. */
    private fun updateProgressBarAndRecIcon(program: Program, recording: ScheduledRecording?) {
        val start = program.startTimeUtcMillis
        val end = program.endTimeUtcMillis
        val currentPosition = currentPlayingPositionProvider.get()
        updateRecordingIndicator(recording)
        if (recording != null) {
            remainingTimeView.progress = getProgressPercent(recording.startTimeMs, start, end)
            remainingTimeView.secondaryProgress = getProgressPercent(currentPosition, start, end)
        } else {
            remainingTimeView.progress = getProgressPercent(currentPosition, start, end)
            remainingTimeView.secondaryProgress = 0
        }
    }

    private fun updateRecordingIndicator(recording: ScheduledRecording?) {
        if (recording == null) {
            recordingIndicatorView.visibility = GONE
            return
        }
        if (remainingTimeView.visibility == GONE) {
            recordingIndicatorView.text = resources.getString(R.string.dvr_recording_till_format,
                DateUtils.formatDateTime(context, recording.endTimeMs, DateUtils.FORMAT_SHOW_TIME))
            recordingIndicatorView.compoundDrawablePadding = recordingIconPadding
        } else {
            recordingIndicatorView.text = ""
            recordingIndicatorView.compoundDrawablePadding = 0
        }
        recordingIndicatorView.visibility = VISIBLE
    }

    private fun isCurrentProgram(recording: ScheduledRecording, program: Program): Boolean {
        val currentPosition = currentPlayingPositionProvider.get()
        return (recording.type == ScheduledRecording.TYPE_PROGRAM && recording.programId == program.id) ||
            (recording.type == ScheduledRecording.TYPE_TIMED &&
                currentPosition >= recording.startTimeMs && currentPosition <= recording.endTimeMs)
    }

    /** Passt die Bannerhöhe an; animiert, wenn das Banner bereits sichtbar ist. */
    private fun updateBannerHeight(needProgramDescriptionFadeAnimation: Boolean) {
        SoftPreconditions.checkState(resizeAnimator == null, TAG, "resize animator running")
        val oldDescription = programDescriptionTextView.text
        programDescriptionTextView.text = programDescriptionText
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val targetHeight = measuredHeight
        if (currentHeight == 0 || !isShown) {
            currentHeight = targetHeight
            val lp = layoutParams as LayoutParams
            if (targetHeight != lp.height) {
                lp.height = targetHeight
                layoutParams = lp
            }
        } else if (currentHeight != targetHeight || needProgramDescriptionFadeAnimation) {
            if (needProgramDescriptionFadeAnimation) programDescriptionTextView.text = oldDescription
            resizeAnimator = createResizeAnimator(targetHeight, needProgramDescriptionFadeAnimation).also { it.start() }
        }
    }

    private fun createResizeAnimator(targetHeight: Int, addFadeAnimation: Boolean): Animator {
        val heightAnimator = ValueAnimator.ofInt(currentHeight, targetHeight).apply {
            addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                val lp = this@ChannelBannerView.layoutParams as LayoutParams
                if (value != lp.height) {
                    lp.height = value
                    this@ChannelBannerView.layoutParams = lp
                }
                currentHeight = value
            }
            duration = resizeAnimDuration
            interpolator = resizeInterpolator
        }
        if (!addFadeAnimation) {
            heightAnimator.addListener(resizeAnimatorListener)
            return heightAnimator
        }
        val fadeOutAndHeight = AnimatorSet().apply { playTogether(programDescriptionFadeOutAnimator, heightAnimator) }
        return AnimatorSet().apply {
            playSequentially(fadeOutAndHeight, programDescriptionFadeInAnimator)
            addListener(resizeAnimatorListener)
        }
    }

    override fun onAccessibilityStateChanged(enabled: Boolean) = autoHideScheduler.onAccessibilityStateChanged(enabled)

    companion object {
        private const val TAG = "ChannelBannerView"
        private const val DEBUG = false

        const val LOCK_NONE = 0
        const val LOCK_PROGRAM_DETAIL = 1
        const val LOCK_CHANNEL_INFO = 2

        private const val DISPLAYED_CONTENT_RATINGS_COUNT = 3
        private const val EMPTY_STRING = ""

        private const val SIGNAL_STRENGTH_0_OF_4_UPPER_BOUND = 20
        private const val SIGNAL_STRENGTH_1_OF_4_UPPER_BOUND = 40
        private const val SIGNAL_STRENGTH_2_OF_4_UPPER_BOUND = 60
        private const val SIGNAL_STRENGTH_3_OF_4_UPPER_BOUND = 80

        private fun createTvInputLogoLoaderCallback(info: TvInputInfo, view: ChannelBannerView) =
            object : ImageLoaderCallback<ChannelBannerView>(view) {
                override fun onBitmapLoaded(referent: ChannelBannerView, bitmap: Bitmap?) {
                    if (bitmap != null && info.id == referent.currentChannel?.inputId) referent.updateTvInputLogo(bitmap)
                }
            }

        private fun createChannelLogoCallback(view: ChannelBannerView, channel: Channel) =
            object : ImageLoaderCallback<ChannelBannerView>(view) {
                override fun onBitmapLoaded(referent: ChannelBannerView, bitmap: Bitmap?) {
                    if (channel == referent.currentChannel) referent.updateLogo(bitmap)
                }
            }
    }
}
