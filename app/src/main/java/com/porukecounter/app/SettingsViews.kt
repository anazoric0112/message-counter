package com.porukecounter.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView

internal object SettingsViews {
    fun page(context: Context, selected: ColorTheme, back: () -> Unit, choose: (ColorTheme) -> Unit): LinearLayout = context.column().apply {
        tag = "app-settings"
        isFocusableInTouchMode = true
        setPadding(context.dp(16), context.dp(8), context.dp(16), 0)
        background = context.techBackground()
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backIcon = TypedValue().also { context.theme.resolveAttribute(android.R.attr.homeAsUpIndicator, it, true) }
        toolbar.iconButton("Back", backIcon.resourceId, back)
        toolbar.label("App settings", 20f, true).apply {
            setTextColor(context.primaryTextAccent)
            setPadding(context.dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        addView(toolbar)
        val content = context.column().apply {
            setPadding(0, context.dp(8), 0, context.dp(24))
            label("Color theme", 14f, true)
        }
        val options = RadioGroup(context).apply { isSaveEnabled = false }
        val ids = ColorTheme.entries.map { View.generateViewId() }
        ColorTheme.entries.forEach { option ->
            val palette = ContextThemeWrapper(context, option.style)
            val row = RadioButton(context).apply {
                id = ids[option.ordinal]
                tag = "theme-${option.ordinal}"
                text = option.label
                techText(14f)
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, TypedValue.COMPLEX_UNIT_SP)
                minHeight = context.dp(64)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(12), context.dp(8), context.dp(8), context.dp(8))
                isSaveEnabled = false
                buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(context.primaryTextAccent, muted))
                val states = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), ColorDrawable(tintedSurface(context.primaryAccent, 0.06f)))
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                background = RippleDrawable(ColorStateList.valueOf((context.primaryAccent and 0x00FFFFFF) or 0x28000000), states, null)
                val swatches = Swatches(context, palette.primaryAccent, palette.secondaryAccent).apply {
                    setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                }
                setCompoundDrawables(null, null, swatches, null)
                compoundDrawablePadding = context.dp(12)
            }
            options.addView(row, RadioGroup.LayoutParams(-1, -2))
        }
        options.check(ids[selected.ordinal])
        options.setOnCheckedChangeListener { _, checked ->
            ids.indexOf(checked).takeIf { it >= 0 }?.let { choose(ColorTheme.entries[it]) }
        }
        content.addView(options)
        addView(ScrollView(context).apply {
            tag = "theme-scroll"
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private class Swatches(context: Context, private val primary: Int, private val secondary: Int) : Drawable() {
        private val width = context.dp(88)
        private val height = context.dp(44)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var opacity = 255

        override fun draw(canvas: Canvas) {
            val scale = bounds.height() / 44f
            val radius = 10f * scale
            for ((index, color) in listOf(primary, secondary).withIndex()) {
                val center = bounds.left + bounds.width() * (0.25f + index * 0.5f)
                paint.color = color
                paint.style = Paint.Style.STROKE
                for (layer in 4 downTo 1) {
                    paint.strokeWidth = layer * 2.5f * scale
                    paint.alpha = ((60 - layer * 10) * 0.8f * opacity / 255f).toInt()
                    canvas.drawCircle(center, bounds.exactCenterY(), radius, paint)
                }
                paint.style = Paint.Style.FILL
                paint.alpha = opacity
                canvas.drawCircle(center, bounds.exactCenterY(), radius, paint)
            }
        }

        override fun getIntrinsicWidth(): Int = width
        override fun getIntrinsicHeight(): Int = height
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}