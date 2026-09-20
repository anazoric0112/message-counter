package com.porukecounter.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.graphics.ColorUtils

internal val ink = Color.rgb(236, 243, 240)
internal val muted = Color.rgb(169, 184, 180)
internal val surface = Color.rgb(23, 29, 32)
internal val canvasColor = Color.rgb(17, 21, 24)
internal val ruleColor = Color.rgb(51, 65, 65)
internal fun tintedSurface(color: Int, amount: Float = 0.1f): Int = ColorUtils.blendARGB(surface, color, amount)
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
internal fun Context.consoleFont(): Typeface = resources.getFont(R.font.console_font)

internal fun TextView.techText(size: Float = 14f, bold: Boolean = false, color: Int = ink) {
    typeface = Typeface.create(context.consoleFont(), if (bold) Typeface.BOLD else Typeface.NORMAL)
    textSize = size
    letterSpacing = 0f
    setTextColor(color)
}

internal fun Context.outline(fill: Int = surface, border: Int = ruleColor, radius: Int = 6): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radius).toFloat()
    setStroke(dp(1), border)
}

private fun Context.glowOutline(fill: Int, border: Int, strength: Float): Drawable = object : Drawable() {
    private val density = resources.displayMetrics.density
    private val frame = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeColor = if (strength > 0f) contrastColor(border, fill, 3.0) else border
    private var opacity = 255

    override fun onBoundsChange(bounds: Rect) {
        frame.set(bounds)
        frame.inset(6f * density, 6f * density)
    }

    override fun draw(canvas: Canvas) {
        if (frame.isEmpty) return
        val radius = 6f * density
        paint.style = Paint.Style.FILL
        paint.color = fill
        paint.alpha = opacity
        canvas.drawRoundRect(frame, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.color = border
        for (layer in 4 downTo 1) {
            paint.strokeWidth = layer * 2.5f * density
            paint.alpha = ((60 - layer * 10) * strength * opacity / 255f).toInt()
            canvas.drawRoundRect(frame, radius, radius, paint)
        }
        paint.color = edgeColor
        paint.strokeWidth = density
        paint.alpha = (opacity * (0.6f + 0.4f * strength)).toInt()
        canvas.drawRoundRect(frame, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun Context.controlBackground(primary: Boolean = false): Drawable {
    val primaryColor = primaryAccent
    val secondaryColor = secondaryAccent
    val highlight = if (primary) primaryColor else secondaryColor
    val fill = if (primary) tintedSurface(primaryColor) else surface
    val states = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), glowOutline(0xFF171C20.toInt(), 0xFF293332.toInt(), 0f))
        addState(intArrayOf(android.R.attr.state_pressed), glowOutline(fill, highlight, 1f))
        addState(intArrayOf(android.R.attr.state_focused), glowOutline(tintedSurface(secondaryColor), secondaryColor, 1f))
        addState(intArrayOf(android.R.attr.state_checked), glowOutline(tintedSurface(primaryColor), primaryColor, 0.9f))
        addState(intArrayOf(), glowOutline(fill, highlight, if (primary) 0.8f else 0.55f))
    }
    return RippleDrawable(ColorStateList.valueOf((primaryColor and 0x00FFFFFF) or 0x38000000), states, null)
}

internal fun Context.techBackground(): Drawable = object : Drawable() {
    private val linePaint = Paint().apply { color = ColorUtils.blendARGB(canvasColor, secondaryAccent, 0.04f); strokeWidth = dp(1).toFloat() }
    override fun draw(canvas: Canvas) {
        canvas.drawColor(canvasColor)
        val spacing = dp(32)
        for (horizontal in bounds.left..bounds.right step spacing) {
            canvas.drawLine(horizontal.toFloat(), bounds.top.toFloat(), horizontal.toFloat(), bounds.bottom.toFloat(), linePaint)
        }
        for (vertical in bounds.top..bounds.bottom step spacing) {
            canvas.drawLine(bounds.left.toFloat(), vertical.toFloat(), bounds.right.toFloat(), vertical.toFloat(), linePaint)
        }
    }
    override fun setAlpha(alpha: Int) { linePaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { linePaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

internal fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

internal fun LinearLayout.label(value: String, size: Float = 14f, bold: Boolean = false): TextView = TextView(context).also {
    it.text = value
    it.techText(size, bold, if (bold) context.secondaryTextAccent else ink)
    it.setPadding(0, context.dp(8), 0, context.dp(4))
    addView(it, LinearLayout.LayoutParams(-1, -2))
}

internal fun LinearLayout.field(label: String, value: String, numeric: Boolean = false, multiline: Boolean = false): EditText {
    val caption = label(label)
    caption.techText(12f, color = muted)
    return EditText(context).also {
        it.id = View.generateViewId()
        caption.labelFor = it.id
        it.contentDescription = label
        it.setText(value)
        it.inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        it.techText(15f)
        it.background = context.controlBackground()
        it.setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        it.setSingleLine(!multiline)
        if (multiline) it.minLines = 3
        it.minimumHeight = context.dp(48)
        addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(6) })
    }
}

internal fun LinearLayout.command(title: String, icon: Int? = null, primary: Boolean = false, action: () -> Unit): Button = Button(context).also {
    it.text = title
    it.techText(13f, primary, if (primary) context.primaryTextAccent else ink)
    it.isAllCaps = false
    it.minHeight = context.dp(48)
    it.setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
    it.background = context.controlBackground(primary)
    it.setTextColor(ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(0xFF667771.toInt(), if (primary) context.primaryTextAccent else ink),
    ))
    if (icon != null) {
        val drawable = context.getDrawable(icon)!!.mutate().apply {
            setTint(if (primary) context.primaryTextAccent else context.secondaryTextAccent)
            setBounds(0, 0, context.dp(20), context.dp(20))
        }
        it.setCompoundDrawables(drawable, null, null, null)
        it.compoundDrawablePadding = context.dp(10)
    }
    it.setOnClickListener { action() }
    addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
}

internal fun LinearLayout.toggle(title: String, selected: Boolean): CheckBox = CheckBox(context).also {
    it.text = title
    it.isChecked = selected
    it.minHeight = context.dp(48)
    it.techText(14f)
    it.buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(context.primaryTextAccent, muted))
    addView(it, LinearLayout.LayoutParams(-1, -2))
}

internal fun LinearLayout.choices(title: String, choices: List<String>, selected: Int = 0, showCaption: Boolean = true): Spinner {
    val caption = if (showCaption) label(title).apply { techText(12f, color = muted) } else null
    return Spinner(context).also {
        it.id = View.generateViewId()
        caption?.labelFor = it.id
        it.contentDescription = title
        it.adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, choices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(12), context.dp(8), context.dp(10), context.dp(8))
                addView(TextView(context).apply {
                    text = getItem(position)
                    techText(14f)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(context).apply {
                    setImageResource(android.R.drawable.arrow_down_float)
                    imageTintList = ColorStateList.valueOf(context.secondaryTextAccent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(context.dp(20), context.dp(20)))
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = TextView(context).apply {
                text = getItem(position)
                techText(14f)
                minHeight = context.dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            }
        }
        it.background = context.controlBackground()
        it.setPopupBackgroundDrawable(context.outline())
        it.setSelection(selected.coerceIn(0, (choices.size - 1).coerceAtLeast(0)))
        it.minimumHeight = context.dp(48)
        addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })
    }
}

internal fun LinearLayout.divider(color: Int = ruleColor) {
    addView(View(context).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(-1, context.dp(1)).apply {
        topMargin = context.dp(16)
        bottomMargin = context.dp(8)
    })
}

internal fun LinearLayout.iconButton(label: String, icon: Int, action: () -> Unit): ImageButton = ImageButton(context).also {
    it.setImageResource(icon)
    it.contentDescription = label
    it.tooltipText = label
    it.background = context.controlBackground()
    it.imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(ruleColor, context.primaryTextAccent))
    it.setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
    it.setOnClickListener { action() }
    addView(it, LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply { gravity = Gravity.CENTER_VERTICAL })
}