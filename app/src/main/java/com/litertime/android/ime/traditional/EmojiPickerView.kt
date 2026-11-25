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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.litertime.android.ui.PastelPalette

/**
 * Shared Emoji Picker backed by FlorisBoard's Unicode CLDR `emoji/root.txt` plus persisted recent
 * emojis. Used by both English QWERTY keyboard and Chinese handwriting panels.
 */
@SuppressLint("ViewConstructor")
class EmojiPickerView(
    context: Context,
    initialReturnLabel: String = "ABC",
) : LinearLayout(context) {

    interface Listener {
        fun onEmojiSelected(emoji: String)
        fun onBackClick()
        fun onBackspaceClick()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).toInt()
    private fun dpF(value: Float): Float = value * density

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hapticEnabled: Boolean = true
    private var recentEmojis: List<String> = emptyList()
    private val catalogCategories: List<EmojiCatalog.Category> by lazy { EmojiCatalog.load(context) }

    // Tab 0 = Recent, Tabs 1..N = catalogCategories[0..N-1]
    private var selectedTabIndex: Int = 1

    private val tabsRow: LinearLayout
    private val categoryTitleView: TextView
    private val backButton: TextView
    private val backspaceButton: TextView
    private val recyclerView: RecyclerView
    private val adapter = EmojiAdapter()
    private val tabViews = mutableListOf<TextView>()

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

        // 1. Top category tab strip
        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(38f))
        }
        tabsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), 0, dp(8f), 0)
        }
        tabScroll.addView(tabsRow)
        addView(tabScroll)

        // 2. Emoji grid
        recyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = GridLayoutManager(context, 8)
            adapter = this@EmojiPickerView.adapter
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            clipToPadding = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        addView(recyclerView)

        // 3. Bottom action bar: [Back] [Category Title] [Backspace]
        val bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(6f))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44f))
        }

        backButton = TextView(context).apply {
            text = initialReturnLabel
            setTextColor(PastelPalette.TEXT_ON_DARK_SKY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = keyStateBackground(PastelPalette.SURFACE_MOD_KEY, PastelPalette.SURFACE_PRESSED, dpF(8.5f))
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            setOnClickListener {
                triggerHaptic()
                listener?.onBackClick()
            }
        }
        bottomBar.addView(backButton)

        categoryTitleView = TextView(context).apply {
            setTextColor(PastelPalette.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        bottomBar.addView(categoryTitleView)

        backspaceButton = TextView(context).apply {
            text = "⌫"
            setTextColor(PastelPalette.TEXT_ON_DARK_SKY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            background = keyBackground(PastelPalette.SURFACE_MOD_KEY, dpF(8.5f))
            setPadding(dp(16f), 0, dp(16f), 0)
            layoutParams = LayoutParams(dp(58f), LayoutParams.MATCH_PARENT)
            setOnTouchListener { v, ev -> handleBackspaceTouch(v, ev) }
        }
        bottomBar.addView(backspaceButton)
        addView(bottomBar)

        buildTabs()
        selectTab(if (recentEmojis.isNotEmpty()) 0 else 1)
    }

    fun setReturnLabel(label: String) {
        backButton.text = label
    }

    fun setHapticEnabled(enabled: Boolean) {
        hapticEnabled = enabled
    }

    fun setRecentEmojis(recent: List<String>) {
        recentEmojis = recent
        if (selectedTabIndex == 0) {
            adapter.submitList(recentEmojis)
        }
    }

    fun resetToDefaultTab() {
        val target = if (recentEmojis.isNotEmpty()) 0 else 1
        selectTab(target)
        recyclerView.scrollToPosition(0)
    }

    private fun buildTabs() {
        tabsRow.removeAllViews()
        tabViews.clear()

        val tabIcons = buildList {
            add("🕒")
            addAll(catalogCategories.map { it.icon })
        }
        tabIcons.forEachIndexed { index, icon ->
            val tab = TextView(context).apply {
                text = icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(dp(38f), dp(34f)).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener {
                    triggerHaptic()
                    selectTab(index)
                    recyclerView.scrollToPosition(0)
                }
            }
            tabsRow.addView(tab)
            tabViews += tab
        }
    }

    private fun selectTab(index: Int) {
        val maxIdx = catalogCategories.size
        val clamped = index.coerceIn(0, maxIdx.coerceAtLeast(0))
        selectedTabIndex = clamped

        tabViews.forEachIndexed { i, tv ->
            val active = i == clamped
            tv.background = if (active) {
                keyBackground(PastelPalette.SKY_BLUE, dpF(8.5f))
            } else {
                null
            }
            tv.alpha = if (active) 1f else 0.68f
        }

        if (clamped == 0) {
            categoryTitleView.text = if (recentEmojis.isEmpty()) "最近使用（暂无）" else "最近使用"
            adapter.submitList(recentEmojis)
        } else {
            val cat = catalogCategories.getOrNull(clamped - 1)
            categoryTitleView.text = cat?.title.orEmpty()
            adapter.submitList(cat?.emojis.orEmpty())
        }
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

    private fun keyBackground(
        fillColor: Int,
        radiusPx: Float,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(fillColor)
    }

    private inner class EmojiAdapter : RecyclerView.Adapter<EmojiAdapter.Holder>() {
        private var items: List<String> = emptyList()

        fun submitList(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class Holder(val label: TextView) : RecyclerView.ViewHolder(label)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
                setTextColor(PastelPalette.TEXT_PRIMARY)
                gravity = Gravity.CENTER
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(40f),
                )
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val emoji = items[position]
            holder.label.text = emoji
            holder.label.setOnClickListener {
                triggerHaptic()
                listener?.onEmojiSelected(emoji)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
