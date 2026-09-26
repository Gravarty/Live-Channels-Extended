package com.android.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.android.tv.MainActivity
import com.android.tv.R
import com.android.tv.data.ChannelNumber
import com.android.tv.data.api.Channel
import kotlin.math.min

/**
 * Kanalwahl per Zifferntasten mit Trefferliste (max. 8 Einträge, bis 5 Haupt-/3 Nebenziffern).
 * Nach Ablauf wird der ausgewählte Kanal getunt.
 */
class KeypadChannelSwitchView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), TvTransitionManager.TransitionLayout {

    private val mainActivity = context as MainActivity
    private var channels: List<Channel>? = null
    private lateinit var channelNumberView: TextView
    private lateinit var channelItemListView: ListView
    private val typedChannelNumber = ChannelNumber()
    private val channelCandidates = ArrayList<Channel>()
    private val adapter = ChannelItemAdapter()
    private val layoutInflater = LayoutInflater.from(context)
    private var selectedChannel: Channel? = null

    // Tweak: Browse-Modus (Hoch/Runter): ganze Liste, kein Umschalten beim Ablauf
    private var browseMode = false
    private val browseShowDurationMillis = 5000L

    private val hideRunnable = Runnable {
        currentHeight = 0
        val channel = if (browseMode) null else selectedChannel
        if (channel != null) {
            mainActivity.tuneToChannel(channel)
        } else {
            mainActivity.overlayManager.hideOverlays(
                TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_DIALOG or TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_SIDE_PANELS or
                    TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_PROGRAM_GUIDE or TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_MENU or
                    TvOverlayManager.FLAG_HIDE_OVERLAYS_KEEP_FRAGMENT)
        }
    }
    private val showDurationMillis = resources.getInteger(R.integer.keypad_channel_switch_show_duration).toLong()
    private val rippleAnimDurationMillis = resources.getInteger(R.integer.keypad_channel_switch_ripple_anim_duration).toLong()
    private val baseViewHeight = resources.getDimensionPixelSize(R.dimen.keypad_channel_switch_base_height)
    private val itemHeight = resources.getDimensionPixelSize(R.dimen.keypad_channel_switch_item_height)
    private val resizeAnimDuration = resources.getInteger(R.integer.keypad_channel_switch_anim_duration).toLong()
    private var resizeAnimator: Animator? = null
    private val resizeInterpolator = AnimationUtils.loadInterpolator(context, android.R.interpolator.linear_out_slow_in)
    private var currentHeight = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        channelNumberView = findViewById(R.id.channel_number)
        channelItemListView = findViewById(R.id.channel_list)
        channelItemListView.adapter = adapter
        channelItemListView.setOnItemClickListener { _, _, position, _ ->
            if (position >= adapter.count) return@setOnItemClickListener // Leerzeile
            channelItemListView.isFocusable = false
            val channel = adapter.getItem(position)
            // Ripple zu Ende laufen lassen
            postDelayed({
                channelItemListView.isFocusable = true
                mainActivity.tuneToChannel(channel)
            }, rippleAnimDurationMillis)
        }
        channelItemListView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedChannel = if (position >= adapter.count) null else adapter.getItem(position)
                if (browseMode) channelNumberView.text = selectedChannel?.displayNumber.orEmpty()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) { selectedChannel = null }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        scheduleHide()
        return super.dispatchKeyEvent(event)
    }

    // Tweak: P+/P- blättern im Browse-Modus durch die Liste
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (browseMode && adapter.count > 0 &&
            (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN)
        ) {
            val step = if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1
            val position = (channelItemListView.selectedItemPosition + step).coerceIn(0, adapter.count - 1)
            channelItemListView.setSelection(position)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isChannelNumberKey(keyCode)) {
            onNumberKeyUp(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        if (ChannelNumber.isChannelNumberDelimiterKey(keyCode)) {
            onDelimiterKeyUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onEnterAction(fromEmptyScene: Boolean) {
        reset()
        if (fromEmptyScene) ViewUtils.setTransitionAlpha(channelItemListView, 1f)
        updateView()
        scheduleHide()
    }

    override fun onExitAction() {
        currentHeight = 0
        cancelHide()
    }

    private fun scheduleHide() {
        cancelHide()
        postDelayed(hideRunnable, if (browseMode) browseShowDurationMillis else showDurationMillis)
    }

    private fun cancelHide() = removeCallbacks(hideRunnable)

    private fun reset() {
        browseMode = false
        typedChannelNumber.reset()
        selectedChannel = null
        channelCandidates.clear()
        adapter.notifyDataSetChanged()
    }

    fun setChannels(channels: List<Channel>?) { this.channels = channels }

    /** Tweak: alle Kanäle zeigen, [current] vorauswählen. Umschalten nur mit OK. */
    fun startBrowse(current: Channel?) {
        browseMode = true
        channelCandidates.clear()
        channelCandidates.addAll(channels.orEmpty())
        adapter.notifyDataSetChanged()
        if (adapter.count > 0) {
            val position = channelCandidates.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
            channelItemListView.requestFocus()
            channelItemListView.setSelection(position)
            selectedChannel = channelCandidates[position]
            channelNumberView.text = selectedChannel?.displayNumber.orEmpty()
        }
        updateViewHeight()
        scheduleHide()
    }

    fun onNumberKeyUp(num: Int) {
        browseMode = false // Tweak: Zifferneingabe beendet den Browse-Modus
        // Maximale Stellenzahl erreicht: neu beginnen
        if (!typedChannelNumber.hasDelimiter && typedChannelNumber.majorNumber.length >= MAX_CHANNEL_NUMBER_DIGIT) {
            Log.i(TAG, "Channel number reset because majorNumber.length = ${typedChannelNumber.majorNumber.length}")
            typedChannelNumber.reset()
        } else if (typedChannelNumber.hasDelimiter && typedChannelNumber.minorNumber.length >= MAX_MINOR_CHANNEL_NUMBER_DIGIT) {
            Log.i(TAG, "Channel number reset because minorNumber.length = ${typedChannelNumber.minorNumber.length}")
            typedChannelNumber.reset()
        }
        if (!typedChannelNumber.hasDelimiter) typedChannelNumber.majorNumber += num.toString()
        else typedChannelNumber.minorNumber += num.toString()
        updateView()
    }

    private fun onDelimiterKeyUp() {
        if (typedChannelNumber.hasDelimiter || typedChannelNumber.majorNumber.isEmpty()) return
        typedChannelNumber.hasDelimiter = true
        updateView()
    }

    /** Treffer: exakte Nummer zuerst, danach Nummern, die mit der Eingabe beginnen. */
    private fun updateView() {
        channelNumberView.text = "${typedChannelNumber}_"
        channelCandidates.clear()
        val secondary = ArrayList<Channel>()
        // Bugfix: Original stürzte ab, wenn noch keine Kanalliste gesetzt war
        for (channel in channels.orEmpty()) {
            val chNumber = ChannelNumber.parseChannelNumber(channel.displayNumber)
            if (chNumber == null) {
                Log.i(TAG, "Malformed channel number (name=${channel.displayName}, number=${channel.displayNumber})")
                continue
            }
            if (matchChannelNumber(typedChannelNumber, chNumber)) {
                channelCandidates.add(channel)
            } else if (!typedChannelNumber.hasDelimiter &&
                channel.displayNumber!!.replace(Regex(CHANNEL_DELIMITERS_REGEX), "").startsWith(typedChannelNumber.majorNumber)
            ) {
                secondary.add(channel)
            }
        }
        channelCandidates.addAll(secondary)
        adapter.notifyDataSetChanged()
        if (adapter.count > 0) {
            channelItemListView.requestFocus()
            channelItemListView.setSelection(0)
            selectedChannel = channelCandidates[0]
        }
        updateViewHeight()
    }

    private fun updateViewHeight() {
        val targetHeight = baseViewHeight + itemHeight * min(MAX_CHANNEL_ITEM, adapter.count)
        resizeAnimator?.cancel()
        resizeAnimator = null
        if (currentHeight == 0) {
            currentHeight = targetHeight
            setViewHeight(this, targetHeight)
        } else if (currentHeight != targetHeight) {
            resizeAnimator = createResizeAnimator(targetHeight).also { it.start() }
        }
    }

    private fun createResizeAnimator(targetHeight: Int): Animator =
        ValueAnimator.ofInt(currentHeight, targetHeight).apply {
            addUpdateListener {
                val value = it.animatedValue as Int
                setViewHeight(this@KeypadChannelSwitchView, value)
                currentHeight = value
            }
            duration = resizeAnimDuration
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) { resizeAnimator = null }
            })
            interpolator = resizeInterpolator
        }

    private fun setViewHeight(view: View, height: Int) {
        val lp = view.layoutParams
        if (height != lp.height) {
            lp.height = height
            view.layoutParams = lp
        }
    }

    private inner class ChannelItemAdapter : BaseAdapter() {
        override fun getCount() = channelCandidates.size
        override fun getItem(position: Int): Channel = channelCandidates[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val channel = channelCandidates[position]
            val v = convertView ?: layoutInflater.inflate(R.layout.keypad_channel_switch_item, parent, false)
            v.findViewById<TextView>(R.id.number).text = channel.displayNumber
            v.findViewById<TextView>(R.id.name).text = channel.displayName
            return v
        }
    }

    companion object {
        private const val TAG = "KeypadChannelSwitchView"
        private const val MAX_CHANNEL_NUMBER_DIGIT = 5
        private const val MAX_MINOR_CHANNEL_NUMBER_DIGIT = 3
        private const val MAX_CHANNEL_ITEM = 8
        private const val CHANNEL_DELIMITERS_REGEX = "[-\\.\\s]"

        @JvmStatic
        fun isChannelNumberKey(keyCode: Int) = keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9

        private fun matchChannelNumber(typed: ChannelNumber, chNumber: ChannelNumber): Boolean {
            if (chNumber.majorNumber != typed.majorNumber) return false
            if (typed.hasDelimiter) {
                if (!chNumber.hasDelimiter) return false
                if (!chNumber.minorNumber.startsWith(typed.minorNumber)) return false
            }
            return true
        }
    }
}
