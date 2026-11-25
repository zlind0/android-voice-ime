package com.litertime.android.ime.traditional

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.litertime.android.ui.PastelPalette

/**
 * Scrollable Chinese punctuation picker displayed inside the handwriting panel when tapping `[标点]`.
 */
@SuppressLint("ViewConstructor")
class ChinesePunctuationView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onPunctuationSelected(text: String)
        fun onBackClick()
        fun onBackspaceClick()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).toInt()
    private fun dpF(value: Float): Float = value * density

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hapticEnabled: Boolean = true
    private var backspaceHeld = false
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (backspaceHeld) {
                triggerHaptic()
                listener?.onBackspaceClick()
                mainHandler.postDelayed(this, 65L)
            }
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PastelPalette.BG_CANVAS)

        val recyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = GridLayoutManager(context, 6)
            adapter = PunctuationAdapter()
            setPadding(dp(8f), dp(8f), dp(8f), dp(4f))
            clipToPadding = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        addView(recyclerView)

        val bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(6f))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44f))
        }

        val backBtn = TextView(context).apply {
            text = "手写"
            setTextColor(PastelPalette.TEXT_ON_DARK_SKY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = keyStateBackground(PastelPalette.SURFACE_MOD_KEY, PastelPalette.SURFACE_PRESSED, dpF(8.5f))
            setPadding(dp(14f), 0, dp(14f), 0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            setOnClickListener {
                triggerHaptic()
                listener?.onBackClick()
            }
        }
        bottomBar.addView(backBtn)

        val titleView = TextView(context).apply {
            text = "中文标点"
            setTextColor(PastelPalette.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        bottomBar.addView(titleView)

        val backspaceBtn = TextView(context).apply {
            text = "⌫"
            setTextColor(PastelPalette.TEXT_ON_DARK_SKY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            background = keyBackground(PastelPalette.SURFACE_MOD_KEY, dpF(8.5f))
            layoutParams = LayoutParams(dp(58f), LayoutParams.MATCH_PARENT)
            setOnTouchListener { v, ev -> handleBackspaceTouch(v, ev) }
        }
        bottomBar.addView(backspaceBtn)
        addView(bottomBar)
    }

    fun setHapticEnabled(enabled: Boolean) {
        hapticEnabled = enabled
    }

    private fun handleBackspaceTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                backspaceHeld = true
                v.background = keyBackground(PastelPalette.SURFACE_BACKSPACE_PRESSED, dpF(8.5f))
                triggerHaptic()
                listener?.onBackspaceClick()
                mainHandler.removeCallbacks(backspaceRepeatRunnable)
                mainHandler.postDelayed(backspaceRepeatRunnable, 400L)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                backspaceHeld = false
                mainHandler.removeCallbacks(backspaceRepeatRunnable)
                v.background = keyBackground(PastelPalette.SURFACE_MOD_KEY, dpF(8.5f))
                return true
            }
        }
        return false
    }

    private fun triggerHaptic() {
        if (hapticEnabled) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun keyStateBackground(normalColor: Int, pressedColor: Int, radiusPx: Float): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), keyBackground(pressedColor, radiusPx))
            addState(intArrayOf(), keyBackground(normalColor, radiusPx))
        }

    private fun keyBackground(fillColor: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fillColor)
        }

    private inner class PunctuationAdapter : RecyclerView.Adapter<PunctuationAdapter.Holder>() {
        inner class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = TextView(parent.context).apply {
                setTextColor(PastelPalette.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                background = keyStateBackground(PastelPalette.SURFACE_CHAR_KEY, PastelPalette.SURFACE_KEY_PRESSED, dpF(8.5f))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(42f),
                ).apply {
                    val m = dp(3f)
                    setMargins(m, m, m, m)
                }
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = PUNCTUATIONS[position]
            holder.tv.text = item
            holder.tv.setOnClickListener {
                triggerHaptic()
                listener?.onPunctuationSelected(item)
            }
        }

        override fun getItemCount(): Int = PUNCTUATIONS.size
    }

    companion object {
        val PUNCTUATIONS = listOf(
            "，", "。", "、", "；", "：", "？",
            "！", "……", "——", "·", "“", "”",
            "‘", "’", "（", "）", "《", "》",
            "【", "】", "「", "」", "『", "』",
            "～", "￥", "%", "@", "#", "+",
            "-", "×", "÷", "=", "℃", "°",
            "№", "&", "*", "／", "＼", "｜",
        )
    }
}
