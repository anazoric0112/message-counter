package com.porukecounter.app

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnLayout
import com.porukecounter.core.ReportCalendarMonth
import com.porukecounter.core.ReportDay
import com.porukecounter.core.ReportLayout
import com.porukecounter.core.ReportMonth
import com.porukecounter.core.ReportSection
import java.text.NumberFormat
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

internal class ReportViews(
    private val parent: LinearLayout,
    private val preferences: SharedPreferences,
    private val unit: String,
) {
    private val context = parent.context
    private val primaryAccent = context.primaryAccent
    private val secondaryAccent = context.secondaryAccent
    private val primaryTextAccent = context.primaryTextAccent
    private val secondaryTextAccent = context.secondaryTextAccent
    private val numbers = NumberFormat.getIntegerInstance(Locale.US)
    private val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
    private var openMonth: ((String) -> Unit)? = null

    fun render(sections: List<ReportSection>) {
        parent.removeAllViews()
        openMonth = null
        sections.forEachIndexed { index, section ->
            val host = context.column().apply { tag = "report-section-$index" }
            parent.addView(host)
            val body = context.column().apply { tag = "report-body-$index" }
            val reveal = sectionHeading(host, section, index + 1, body)
            host.addView(body)
            when (section.layout) {
                ReportLayout.YEAR_TABLE -> yearTable(body, section.months)
                ReportLayout.CALENDAR -> calendar(body, section.calendarMonths, reveal = reveal)
                ReportLayout.LATEST_CALENDAR -> calendar(body, section.calendarMonths, latest = true)
                ReportLayout.TABLE -> table(body, section.rows, section.title == "Summary")
            }
        }
    }

    private fun sectionHeading(host: LinearLayout, section: ReportSection, number: Int, body: LinearLayout): () -> Unit {
        val title = section.title
        host.divider(secondaryAccent)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(48)
            isFocusable = true
            contentDescription = title
            tag = "report-heading-${number - 1}"
            setPadding(0, context.dp(6), 0, context.dp(14))
            val selectable = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
            setBackgroundResource(selectable.resourceId)
        }
        row.addView(TextView(context).apply {
            text = number.toString().padStart(2, '0')
            techText(12f, true, primaryTextAccent)
            setPadding(0, 0, context.dp(12), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        row.addView(TextView(context).apply {
            text = title
            techText(18f, true, secondaryTextAccent)
            setShadowLayer(context.dp(3).toFloat(), 0f, 0f, (secondaryAccent and 0x00FFFFFF) or 0x44000000)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val indicator = ImageView(context).apply {
            imageTintList = ColorStateList.valueOf(secondaryTextAccent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(indicator, LinearLayout.LayoutParams(context.dp(32), context.dp(24)))
        ViewCompat.setAccessibilityHeading(row, true)
        val stateKey = "report.section.${if (section.layout == ReportLayout.TABLE) title else section.layout.name}.expanded"
        fun setExpanded(expanded: Boolean) {
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            indicator.setImageResource(if (expanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
            val action = if (expanded) "Collapse" else "Expand"
            row.tooltipText = "$action $title"
            ViewCompat.setStateDescription(row, if (expanded) "Expanded" else "Collapsed")
            ViewCompat.removeAccessibilityAction(row, if (expanded) AccessibilityNodeInfoCompat.ACTION_EXPAND else AccessibilityNodeInfoCompat.ACTION_COLLAPSE)
            ViewCompat.replaceAccessibilityAction(
                row,
                if (expanded) AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE else AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND,
                action,
            ) { _, _ -> row.performClick() }
            preferences.edit().putBoolean(stateKey, expanded).apply()
        }
        row.setOnClickListener { setExpanded(body.visibility != View.VISIBLE) }
        setExpanded(preferences.getBoolean(stateKey, true))
        host.addView(row)
        return { setExpanded(true) }
    }

    private fun yearTable(host: LinearLayout, months: List<ReportMonth>) {
        if (months.isEmpty()) {
            host.label("No months in this interval").setTextColor(muted)
            return
        }
        val years = months.map { it.date.year }.distinct().sorted()
        var singleYear = preferences.getBoolean("report.singleYear", false)
        val savedYear = preferences.getInt("report.year", years.last())
        var yearIndex = years.indexOf(savedYear).takeIf { it >= 0 } ?: years.lastIndex
        val modes = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val modeIds = listOf(View.generateViewId(), View.generateViewId())
        listOf("All years", "One year").forEachIndexed { index, title ->
            modes.addView(RadioButton(context).apply {
                id = modeIds[index]
                text = title
                techText(13f, true)
                buttonDrawable = null
                setTextColor(ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(primaryTextAccent, muted),
                ))
                background = context.controlBackground()
                gravity = Gravity.CENTER
                minHeight = context.dp(48)
                setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                isSaveEnabled = false
            }, RadioGroup.LayoutParams(0, -2, 1f).apply { marginEnd = if (index == 0) context.dp(6) else 0 })
        }
        modes.check(modeIds[if (singleYear) 1 else 0])
        host.addView(modes, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(10) })
        val navigation = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val previous = navigation.iconButton("Previous year", android.R.drawable.ic_media_previous) {}
        val pickerHost = context.column()
        navigation.addView(pickerHost, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(context.dp(6), 0, context.dp(6), 0) })
        val picker = pickerHost.choices("Report year", years.map { it.toString() }, yearIndex, showCaption = false).apply { isSaveEnabled = false }
        val next = navigation.iconButton("Next year", android.R.drawable.ic_media_next) {}
        host.addView(navigation, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })
        val body = context.column()
        host.addView(body)

        fun renderTable() {
            preferences.edit().putBoolean("report.singleYear", singleYear).putInt("report.year", years[yearIndex]).apply()
            navigation.visibility = if (singleYear) View.VISIBLE else View.GONE
            previous.isEnabled = yearIndex > 0
            next.isEnabled = yearIndex < years.lastIndex
            body.removeAllViews()
            yearGrid(body, if (singleYear) months.filter { it.date.year == years[yearIndex] } else months)
        }
        modes.setOnCheckedChangeListener { _, selected ->
            singleYear = selected == modeIds[1]
            renderTable()
        }
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                yearIndex = position
                renderTable()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        previous.setOnClickListener { if (yearIndex > 0) picker.setSelection(yearIndex - 1) }
        next.setOnClickListener { if (yearIndex < years.lastIndex) picker.setSelection(yearIndex + 1) }
        renderTable()
    }

    private fun yearGrid(host: LinearLayout, months: List<ReportMonth>) {
        val years = months.groupBy { it.date.year }.toSortedMap()
        val best = months.filter { it.count > 0 }.maxByOrNull { it.count }
        val rowHeight = context.dp((44 * fontScale).roundToInt())
        val table = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = context.outline(radius = 0)
            tag = "year-table"
        }
        val labels = context.column()
        labels.addView(tableCell("Month", rowHeight, header = true, left = true))
        for (month in Month.values()) {
            labels.addView(tableCell(month.getDisplayName(TextStyle.FULL, Locale.ENGLISH), rowHeight, left = true, stripe = month.value % 2 == 0))
        }
        labels.addView(tableCell("Year total", rowHeight, header = true, left = true))
        table.addView(labels, LinearLayout.LayoutParams(context.dp((112 * fontScale.coerceAtMost(1.5f)).roundToInt()), -2))
        val scroller = HorizontalScrollView(context).apply { isFillViewport = true }
        val minimumColumnWidth = context.dp((108 * fontScale).roundToInt())
        val columns = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = View.MeasureSpec.getSize(widthMeasureSpec)
                val expandedWidth = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.EXACTLY)
                    maxOf(minimumColumnWidth, available / years.size) else minimumColumnWidth
                for (index in 0 until childCount) getChildAt(index).layoutParams.width = expandedWidth
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply { orientation = LinearLayout.HORIZONTAL }
        for ((year, entries) in years) {
            val column = context.column()
            column.addView(tableCell(year.toString(), rowHeight, header = true))
            val counts = entries.associateBy { it.date.monthValue }
            for (month in 1..12) {
                val entry = counts[month]
                val cell = tableCell(entry?.let { numbers.format(it.count) } ?: "-", rowHeight, stripe = month % 2 == 0)
                if (entry == null) cell.setTextColor(0xFF63716C.toInt())
                else {
                    cell.setTextColor(if (entry.count > 0) primaryTextAccent else muted)
                    cell.contentDescription = "${entry.label}, ${numbers.format(entry.count)} $unit"
                    cell.tag = "month-${entry.key}"
                    cell.isFocusable = true
                    if (entry == best) {
                        cell.background = context.outline(tintedSurface(secondaryAccent), secondaryAccent, radius = 0)
                        cell.setTextColor(secondaryTextAccent)
                    }
                    cell.setOnClickListener { openMonth?.invoke(entry.key) }
                }
                column.addView(cell)
            }
            column.addView(tableCell(numbers.format(entries.sumOf { it.count }), rowHeight, header = true).apply { setTextColor(primaryTextAccent) })
            columns.addView(column, LinearLayout.LayoutParams(minimumColumnWidth, -2))
        }
        scroller.addView(columns)
        table.addView(scroller, LinearLayout.LayoutParams(0, -2, 1f))
        host.addView(table)
        highlight(host, "Busiest month", best?.label ?: "No activity", best?.count)
    }

    private fun tableCell(value: String, height: Int, header: Boolean = false, left: Boolean = false, stripe: Boolean = false): TextView = TextView(context).apply {
        text = value
        techText(if (header) 13f else 12f, header, if (header) secondaryTextAccent else ink)
        gravity = Gravity.CENTER_VERTICAL or if (left) Gravity.START else Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
        background = context.outline(if (header) tintedSurface(secondaryAccent, 0.06f) else if (stripe) tintedSurface(primaryAccent, 0.025f) else surface, ruleColor, 0)
        setAutoSizeTextTypeUniformWithConfiguration(9, if (header) 13 else 12, 1, TypedValue.COMPLEX_UNIT_SP)
        maxLines = 2
        layoutParams = LinearLayout.LayoutParams(-1, height)
    }

    private fun calendar(host: LinearLayout, months: List<ReportCalendarMonth>, latest: Boolean = false, reveal: () -> Unit = {}) {
        if (months.isEmpty()) {
            host.label("No days in this interval").setTextColor(muted)
            return
        }
        val statePrefix = if (latest) "report.latestCalendar" else "report.calendar"
        val saved = preferences.getString("${statePrefix}Month", null)
        var monthIndex = months.indexOfFirst { it.key == saved }.coerceAtLeast(0)
        var selectedDay = preferences.getString("${statePrefix}Day", null)
        val navigation = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val previous = navigation.iconButton("Previous month", android.R.drawable.ic_media_previous) {}.apply {
            if (latest) visibility = View.GONE
        }
        val pickerHost = context.column()
        navigation.addView(pickerHost, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(context.dp(6), 0, context.dp(6), 0) })
        val picker = if (latest) {
            pickerHost.label(months[monthIndex].label, 16f, true)
            null
        } else pickerHost.choices("Calendar month", months.map { it.label }, monthIndex, showCaption = false)
        val next = navigation.iconButton("Next month", android.R.drawable.ic_media_next) {}.apply {
            if (latest) visibility = View.GONE
        }
        host.addView(navigation)
        val metadata = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val total = TextView(context).apply { techText(13f, true, primaryTextAccent); setPadding(0, context.dp(12), 0, context.dp(12)) }
        metadata.addView(total, LinearLayout.LayoutParams(0, -2, 1f))
        val pageNumber = TextView(context).apply {
            techText(11f, color = muted)
            gravity = Gravity.CENTER_VERTICAL
            if (latest) visibility = View.GONE
        }
        metadata.addView(pageNumber, LinearLayout.LayoutParams(-2, -1))
        host.addView(metadata)
        val weekdayRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { index, day ->
            weekdayRow.addView(TextView(context).apply {
                text = day
                techText(10f, true, if (index > 4) secondaryTextAccent else muted)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, context.dp(30), 1f))
        }
        host.addView(weekdayRow)
        val grid = context.column().apply { tag = if (latest) "report-latest-calendar-grid" else "report-calendar-grid" }
        host.addView(grid)
        val details = context.column()
        host.addView(details)

        fun renderMonth() {
            val month = months[monthIndex]
            val selected = month.days.firstOrNull { it.key == selectedDay && it.count != null }
                ?: month.busiest ?: month.days.first { it.count != null }
            selectedDay = selected.key
            preferences.edit().putString("${statePrefix}Month", month.key).putString("${statePrefix}Day", selected.key).apply()
            previous.isEnabled = monthIndex > 0
            next.isEnabled = monthIndex < months.lastIndex
            total.text = "${numbers.format(month.total)} $unit"
            pageNumber.text = String.format(Locale.US, "%02d / %02d", monthIndex + 1, months.size)
            grid.removeAllViews()
            for (week in 0..5) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for (weekday in 0..6) {
                    val dayIndex = week * 7 + weekday - month.firstWeekday
                    val day = month.days.getOrNull(dayIndex)
                    val cell = if (day == null) View(context) else calendarCell(day, day == selected, month.busiest?.count ?: 0L) {
                        selectedDay = day.key
                        renderMonth()
                    }
                    row.addView(cell, LinearLayout.LayoutParams(0, context.dp((64 * fontScale.coerceAtMost(1.6f)).roundToInt()), 1f).apply {
                        setMargins(context.dp(1), context.dp(1), context.dp(1), context.dp(1))
                    })
                }
                grid.addView(row)
            }
            details.removeAllViews()
            highlight(details, "Selected day", selected.label, selected.count, primaryAccent)
            highlight(details, "Busiest day", month.busiest?.label ?: "No activity", month.busiest?.count)
        }
        if (picker != null) {
            picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    monthIndex = position
                    renderMonth()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            previous.setOnClickListener { if (monthIndex > 0) picker.setSelection(monthIndex - 1) }
            next.setOnClickListener { if (monthIndex < months.lastIndex) picker.setSelection(monthIndex + 1) }
            openMonth = { key ->
                val index = months.indexOfFirst { it.key == key }
                if (index >= 0) {
                    reveal()
                    picker.setSelection(index)
                    host.doOnLayout { it.requestRectangleOnScreen(Rect(0, 0, it.width, context.dp(96)), false) }
                }
            }
        }
        renderMonth()
    }

    private fun calendarCell(day: ReportDay, selected: Boolean, maximum: Long, action: () -> Unit): LinearLayout = context.column().apply {
        val count = day.count
        val textScale = fontScale.coerceAtMost(1.6f)
        val intensity = if (maximum == 0L || count == null) 0f else (count.toDouble() / maximum).toFloat()
        val fill = if (selected) tintedSurface(secondaryAccent) else tintedSurface(primaryAccent, 0.02f + 0.12f * intensity)
        background = context.outline(fill, if (selected) secondaryAccent else ruleColor, radius = 3)
        setPadding(context.dp(3), context.dp(5), context.dp(3), context.dp(4))
        isEnabled = count != null
        isFocusable = count != null
        contentDescription = if (count == null) "${day.label}, outside selected interval" else "${day.label}, ${numbers.format(count)} $unit"
        tag = "day-${day.key}"
        addView(TextView(context).apply {
            text = day.day.toString()
            techText(11f, selected, if (selected) secondaryTextAccent else if (count != null) ink else 0xFF63716C.toInt())
            gravity = Gravity.START
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, context.dp((20 * textScale).roundToInt())))
        addView(TextView(context).apply {
            text = count?.toString() ?: "-"
            techText(12f, true, if ((count ?: 0L) > 0) primaryTextAccent else muted)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, context.dp((28 * textScale).roundToInt())))
        if (count != null) setOnClickListener { action() }
    }

    private fun highlight(host: LinearLayout, title: String, detail: String, count: Long?, tint: Int = secondaryAccent) {
        val band = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(tintedSurface(tint), surface))
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        }
        val textColumn = context.column()
        textColumn.label(title, 11f).apply { setPadding(0, 0, 0, context.dp(6)); setTextColor(muted) }
        textColumn.label(detail, 14f, true).apply { setPadding(0, 0, context.dp(8), 0); setTextColor(contrastColor(tint, tintedSurface(tint))) }
        band.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1.5f))
        if (count != null) {
            val valueColumn = context.column()
            valueColumn.label(numbers.format(count), 20f, true).apply {
                gravity = Gravity.END
                setTextColor(primaryTextAccent)
                setPadding(0, 0, 0, 0)
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(11, 20, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            valueColumn.label(unit, 10f).apply { gravity = Gravity.END; setTextColor(muted); setPadding(0, context.dp(4), 0, 0) }
            band.addView(valueColumn, LinearLayout.LayoutParams(0, -2, 1f))
        }
        host.addView(band, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = context.dp(14)
            bottomMargin = context.dp(4)
        })
        host.addView(View(context).apply { setBackgroundColor(tint) }, LinearLayout.LayoutParams(-1, context.dp(1)))
    }

    private fun table(host: LinearLayout, rows: List<List<String>>, summary: Boolean) {
        val hasHeader = rows.firstOrNull()?.firstOrNull() == "Participant"
        val header = if (hasHeader) rows.first() else null
        val data = if (hasHeader) rows.drop(1) else rows
        var page = 0
        val body = context.column()
        host.addView(body)
        val navigation = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        host.addView(navigation)
        fun renderPage() {
            body.removeAllViews()
            header?.let { tableRow(body, it, header = true) }
            data.drop(page * 100).take(100).forEachIndexed { index, cells ->
                if (summary) {
                    when (cells.firstOrNull()) {
                        "Configured-range count" -> body.label("TOTALS", 11f, true)
                        "Parsed message headers" -> { body.divider(); body.label("IMPORT", 11f, true) }
                    }
                }
                if (cells.firstOrNull() == "Total") body.divider(primaryAccent)
                tableRow(body, cells, emphasis = cells.firstOrNull() in listOf("Total", "Count"), stripe = index % 2 == 0)
            }
            navigation.removeAllViews()
            if (data.size > 100) {
                navigation.iconButton("Previous page", android.R.drawable.ic_media_previous) { page--; renderPage() }.isEnabled = page > 0
                navigation.addView(TextView(context).apply {
                    text = String.format(Locale.US, "%d / %d", page + 1, (data.size + 99) / 100)
                    techText(12f, color = muted)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(0, context.dp(48), 1f))
                navigation.iconButton("Next page", android.R.drawable.ic_media_next) { page++; renderPage() }.isEnabled = (page + 1) * 100 < data.size
            }
        }
        renderPage()
    }

    private fun tableRow(host: LinearLayout, cells: List<String>, header: Boolean = false, emphasis: Boolean = false, stripe: Boolean = false) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (header) tintedSurface(secondaryAccent, 0.06f) else if (emphasis) tintedSurface(primaryAccent) else if (stripe) surface else canvasColor)
            minimumHeight = context.dp(44)
        }
        cells.forEachIndexed { index, value ->
            row.addView(TextView(context).apply {
                text = value.toLongOrNull()?.let { numbers.format(it) } ?: value
                techText(if (header) 11f else 13f, header || emphasis, if (header) secondaryTextAccent else if (index > 0 || emphasis) primaryTextAccent else ink)
                gravity = Gravity.CENTER_VERTICAL or if (index == 0) Gravity.START else Gravity.END
                setPadding(context.dp(10), context.dp(12), context.dp(10), context.dp(12))
                if (!header) setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(0, -2, if (index == 0) 1.4f else 1f))
        }
        host.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(1) })
    }
}