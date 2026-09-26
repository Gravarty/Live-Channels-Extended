package com.android.tv.menu

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.data.api.Channel
import com.android.tv.data.api.Program
import com.android.tv.tweaks.Tweaks
import com.android.tv.util.images.ImageLoader

/**
 * Kanal-Karte: Nummer/Name, aktuelle Sendung, Fortschritt, Posterbild.
 * Gesperrte Altersfreigaben sind ohne Systemrechte nicht lesbar – das Poster wird daher
 * immer gezeigt (wie im Original bei Nicht-System-Apps).
 */
class ChannelCardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : BaseCardView<ChannelsRowItem>(context, attrs, defStyle) {

    private val cardImageWidth = resources.getDimensionPixelSize(R.dimen.card_image_layout_width)
    private val cardImageHeight = resources.getDimensionPixelSize(R.dimen.card_image_layout_height)
    private val mainActivity = context as MainActivity
    private lateinit var imageView: ImageView
    private lateinit var channelNumberNameView: TextView
    private lateinit var progressBar: ProgressBar
    private var channel: Channel? = null
    private var program: Program? = null
    private var posterArtUri: String? = null
    // Tweak: Senderlogo statt Posterbild
    private var showChannelLogo = false
    private var logoChannelId: Long? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        imageView = findViewById(R.id.image)
        imageView.setBackgroundResource(R.color.channel_card)
        channelNumberNameView = findViewById(R.id.channel_number_and_name)
        progressBar = findViewById(R.id.progress)
    }

    override fun onBind(item: ChannelsRowItem, selected: Boolean) {
        applyChannelLogoTweak()
        updateChannel(item)
        updateProgram()
        super.onBind(item, selected)
    }

    private fun updateChannel(item: ChannelsRowItem) {
        if (item.channel != channel) {
            channel = item.channel
            channelNumberNameView.text = channel?.displayText
            channelNumberNameView.visibility = VISIBLE
        }
    }

    private fun updateProgram() {
        val ch = channel ?: return
        if (mainActivity.isParentalControlsEnabled() && ch.isLocked) {
            setText(R.string.program_title_for_blocked_channel)
            program = null
        } else {
            val currentProgram = mainActivity.programDataManager.getCurrentProgram(ch.id)
            if (currentProgram != program) {
                program = currentProgram
                if (currentProgram == null || currentProgram.title.isNullOrEmpty()) {
                    setTextViewEnabled(false)
                    setText(R.string.program_title_for_no_information)
                } else {
                    setTextViewEnabled(true)
                    setText(currentProgram.title)
                }
            }
        }
        val p = program
        if (p == null) {
            progressBar.visibility = GONE
            if (showChannelLogo) setChannelLogo(ch) else setPosterArt(null)
        } else {
            progressBar.visibility = VISIBLE
            val start = p.startTimeUtcMillis
            val end = p.endTimeUtcMillis
            val now = System.currentTimeMillis()
            progressBar.progress = when {
                now <= start -> 0
                now >= end -> 100
                else -> (100 * (now - start) / (end - start)).toInt()
            }
            if (showChannelLogo) setChannelLogo(ch) else setPosterArt(p.posterArtUri)
        }
    }

    /** Tweak: Bildbereich für Logo (eingerückt, ganz sichtbar) oder Poster (randlos) einrichten. */
    private fun applyChannelLogoTweak() {
        val enabled = Tweaks.isChannelCardLogo(context)
        if (enabled == showChannelLogo) return
        showChannelLogo = enabled
        logoChannelId = null
        posterArtUri = POSTER_NOT_SET
        if (enabled) {
            val h = resources.getDimensionPixelSize(R.dimen.extended_card_logo_padding_horizontal)
            imageView.setPaddingRelative(h, resources.getDimensionPixelSize(R.dimen.extended_card_logo_padding_top),
                h, resources.getDimensionPixelSize(R.dimen.extended_card_logo_padding_bottom))
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        } else {
            imageView.setPaddingRelative(0, 0, 0, 0)
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    /** Tweak: Senderlogo laden; ohne Logo das Standardbild. */
    private fun setChannelLogo(ch: Channel) {
        if (logoChannelId == ch.id) return
        logoChannelId = ch.id
        imageView.setImageDrawable(null)
        imageView.foreground = null
        ch.loadBitmap(context, Channel.LOAD_IMAGE_TYPE_CHANNEL_LOGO, cardImageWidth, cardImageHeight,
            object : ImageLoader.ImageLoaderCallback<ChannelCardView>(this) {
                override fun onBitmapLoaded(referent: ChannelCardView, bitmap: Bitmap?) {
                    if (!referent.showChannelLogo || referent.channel?.id != ch.id) return
                    if (bitmap != null) {
                        referent.imageView.setImageBitmap(bitmap)
                    } else {
                        referent.imageView.setImageResource(R.drawable.ic_recent_thumbnail_default)
                    }
                }
            })
    }

    private fun setPosterArt(posterArtUri: String?) {
        if (this.posterArtUri == posterArtUri) return
        this.posterArtUri = posterArtUri
        val p = program
        if (posterArtUri == null || p == null ||
            !p.loadPosterArt(context, cardImageWidth, cardImageHeight, createProgramPosterArtCallback(this, p))
        ) {
            imageView.setImageResource(R.drawable.ic_recent_thumbnail_default)
            imageView.foreground = null
        }
    }

    private fun updatePosterArt(posterArt: Bitmap) {
        imageView.setImageBitmap(posterArt)
        imageView.foreground = context.getDrawable(R.drawable.card_image_gradient)
    }

    companion object {
        // Tweak: erzwingt Neuladen des Posters nach dem Umschalten
        private const val POSTER_NOT_SET = "extended:not_set"

        private fun createProgramPosterArtCallback(cardView: ChannelCardView, program: Program) =
            object : ImageLoader.ImageLoaderCallback<ChannelCardView>(cardView) {
                override fun onBitmapLoaded(referent: ChannelCardView, bitmap: Bitmap?) {
                    val current = referent.program
                    if (bitmap == null || current == null || program.channelId != current.channelId ||
                        program.channelId != referent.channel?.id
                    ) return
                    referent.updatePosterArt(bitmap)
                }
            }
    }
}
