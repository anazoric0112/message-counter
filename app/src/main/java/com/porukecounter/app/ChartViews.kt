package com.porukecounter.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.androidplot.Plot
import com.androidplot.ui.Anchor
import com.androidplot.ui.HorizontalPositioning
import com.androidplot.ui.Size
import com.androidplot.ui.SizeMode
import com.androidplot.ui.VerticalPositioning
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.LineAndPointFormatter
import com.androidplot.xy.SimpleXYSeries
import com.androidplot.xy.StepMode
import com.androidplot.xy.XYGraphWidget
import com.androidplot.xy.XYPlot
import com.porukecounter.core.ChartData
import com.porukecounter.core.GraphOptions
import com.porukecounter.core.ReportDates
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.FieldPosition
import java.text.Format
import java.text.ParsePosition
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

internal object ChartViews {
    private val colors = listOf("#7DF4B6", "#C5A2FF", "#77DDF0", "#F4BD72", "#EE86B9", "#B3D474", "#FF8D8D").map(Color::parseColor)
    private val exportColors = listOf("#D56B16", "#2685C4", "#A248B3", "#21835B", "#A38A00", "#D54343", "#CF5588").map(Color::parseColor)

    fun color(index: Int, export: Boolean = false): Int = if (export) exportColors[index % exportColors.size] else colors[index % colors.size]

    fun add(parent: LinearLayout, data: ChartData, options: GraphOptions, hideDateLabels: Boolean = false, expandable: Boolean = false) {
        parent.label(data.title, 18f, true)
        if (data.labels.isEmpty()) {
            parent.label("No dates in the selected interval")
            return
        }
        val scroll = HorizontalScrollView(parent.context).apply { isFillViewport = expandable }
        val screenWidth = parent.context.resources.displayMetrics.widthPixels - parent.context.dp(32)
        val chartWidth = max(screenWidth, parent.context.dp(options.xSize * 48))
        val chartHeight = parent.context.dp(max(250, options.ySize * 48))
        var expanded = !expandable
        val chart = plot(parent.context, data, options, hideDateLabels = hideDateLabels, isFitted = { expandable && !expanded }).apply {
            minimumWidth = if (expanded) chartWidth else 0
            minimumHeight = chartHeight
        }
        scroll.addView(chart, android.widget.FrameLayout.LayoutParams(if (expanded) chartWidth else -1, chartHeight))
        parent.addView(scroll, LinearLayout.LayoutParams(-1, chartHeight))
        if (options.legend) {
            data.series.forEachIndexed { index, series ->
                parent.label(series.name).setTextColor(color(series.colorIndex ?: index))
            }
        }
        if (expandable) {
            val toggle = parent.command("Expand graph", android.R.drawable.ic_menu_zoom) {}
            toggle.setOnClickListener {
                expanded = !expanded
                chart.minimumWidth = if (expanded) chartWidth else 0
                chart.layoutParams = chart.layoutParams.apply { width = if (expanded) chartWidth else -1 }
                scroll.scrollTo(0, 0)
                toggle.text = if (expanded) "Fit to screen" else "Expand graph"
            }
        }
    }

    fun bitmap(context: Context, charts: List<ChartData>, options: GraphOptions): Bitmap {
        val width = options.xSize * 100
        val height = options.ySize * 100
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val columns = if (charts.size == 4) 2 else 1
        val rows = if (charts.size == 4) 2 else 1
        val cellWidth = width / columns
        val cellHeight = height / rows
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f
            color = Color.rgb(31, 44, 40)
            typeface = context.consoleFont()
        }
        charts.forEachIndexed { index, chart ->
            val checkpoint = canvas.save()
            canvas.translate((index % columns * cellWidth).toFloat(), (index / columns * cellHeight).toFloat())
            canvas.clipRect(0, 0, cellWidth, cellHeight)
            val titleWidth = titlePaint.measureText(chart.title.take(75))
            if (titleWidth > cellWidth - 24) titlePaint.textSize *= (cellWidth - 24) / titleWidth
            canvas.drawText(chart.title.take(75), 12f, 22f, titlePaint)
            titlePaint.textSize = 15f
            val legendLines = if (options.legend) chart.series.size else 0
            val legendHeight = (legendLines * 18).coerceAtMost(cellHeight / 3)
            val plotHeight = max(100, cellHeight - 36 - legendHeight)
            val plot = plot(context, chart, options, export = true, hideDateLabels = charts.size == 4 && index % 2 == 0)
            plot.measure(View.MeasureSpec.makeMeasureSpec(cellWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(plotHeight, View.MeasureSpec.EXACTLY))
            plot.layout(0, 0, cellWidth, plotHeight)
            val panelBitmap = Bitmap.createBitmap(cellWidth, plotHeight, Bitmap.Config.ARGB_8888)
            plot.draw(Canvas(panelBitmap))
            canvas.drawBitmap(panelBitmap, 0f, 30f, null)
            panelBitmap.recycle()
            if (options.legend) {
                chart.series.take(legendHeight / 18).forEachIndexed { seriesIndex, series ->
                    titlePaint.color = color(series.colorIndex ?: seriesIndex, export = true)
                    canvas.drawText(series.name.take(70), 12f, (plotHeight + 46 + seriesIndex * 18).toFloat(), titlePaint)
                }
                titlePaint.color = Color.rgb(31, 44, 40)
            }
            canvas.restoreToCount(checkpoint)
        }
        return bitmap
    }

    private fun plot(context: Context, data: ChartData, options: GraphOptions, export: Boolean = false, hideDateLabels: Boolean = false, isFitted: () -> Boolean = { false }): XYPlot {
        val plot = XYPlot(context, "", Plot.RenderMode.USE_MAIN_THREAD)
        val scale = if (export) 1f else context.resources.displayMetrics.density
        val explicitTicks = options.xTicks.toSet() + options.xTicks.mapNotNull { runCatching { ReportDates.month(it) }.getOrNull() }
        fun selectedTick(index: Int, label: String): Boolean = !hideDateLabels &&
            (if (options.sparsity > 1) index % options.sparsity == 0 else explicitTicks.isEmpty() || label in explicitTicks)
        val maximum = data.series.maxOfOrNull { it.values.maxOrNull() ?: 0L }?.toDouble() ?: 0.0
        val desiredStep = max(1.0, maximum / 5.0)
        val magnitude = 10.0.pow(floor(log10(desiredStep)))
        val rangeStep = listOf(1.0, 2.0, 5.0, 10.0).first { it * magnitude >= desiredStep } * magnitude
        val rangeMaximum = max(rangeStep, ceil(maximum * 1.08 / rangeStep) * rangeStep)
        val plotInk = if (export) Color.rgb(31, 44, 40) else ink
        val plotBackground = if (export) Color.WHITE else surface
        plot.setBackgroundColor(plotBackground)
        plot.backgroundPaint = Paint().apply { color = plotBackground }
        plot.borderPaint = null
        plot.title.isVisible = false
        plot.legend.isVisible = false
        plot.domainTitle.isVisible = false
        plot.rangeTitle.isVisible = false
        plot.graph.apply {
            size = Size(0f, SizeMode.FILL, 0f, SizeMode.FILL)
            position(0f, HorizontalPositioning.ABSOLUTE_FROM_LEFT, 0f, VerticalPositioning.ABSOLUTE_FROM_TOP, Anchor.LEFT_TOP)
            setMargins(0f, 0f, 0f, 0f)
            backgroundPaint = Paint().apply { color = plotBackground }
            gridBackgroundPaint = Paint().apply { color = plotBackground }
            setLineLabelEdges(XYGraphWidget.Edge.BOTTOM, XYGraphWidget.Edge.LEFT)
            isClippingEnabled = false
            val domainLabelStyle = getLineLabelStyle(XYGraphWidget.Edge.BOTTOM).apply {
                paint.color = plotInk
                paint.typeface = context.consoleFont()
                paint.textSize = 10f * scale
                rotation = -35f
            }
            val domainLabelBounds = RectF()
            val labelRotation = Matrix().apply { setRotate(domainLabelStyle.rotation) }
            data.labels.forEachIndexed { index, label ->
                if (selectedTick(index, label)) {
                    val bounds = Rect()
                    domainLabelStyle.paint.getTextBounds(label, 0, label.length, bounds)
                    val rotated = RectF(bounds).apply { offset(-domainLabelStyle.paint.measureText(label) / 2f, 0f) }
                    labelRotation.mapRect(rotated)
                    domainLabelBounds.union(rotated)
                }
            }
            val labelOffset = max(28f * scale, 8f * scale - domainLabelBounds.top)
            lineLabelInsets.bottom = -labelOffset
            domainLabelStyle.format = object : Format() {
                override fun format(value: Any, target: StringBuffer, position: FieldPosition): StringBuffer {
                    val numeric = (value as Number).toDouble()
                    val index = numeric.roundToInt()
                    val label = data.labels.getOrNull(index).orEmpty()
                    val availableWidth = gridRect?.width() ?: 0f
                    val automaticSpacing = if (((options.sparsity == 1 && explicitTicks.isEmpty()) || (isFitted() && options.sparsity > 1)) && availableWidth > 0f)
                        ceil((domainLabelBounds.width() + 6f * scale) * data.labels.lastIndex / availableWidth / options.sparsity).toInt().coerceAtLeast(1) * options.sparsity else 1
                    val visible = index in data.labels.indices && abs(numeric - index) < 0.01 &&
                        selectedTick(index, label) && index % automaticSpacing == 0
                    return target.append(if (visible) label else "")
                }
                override fun parseObject(source: String?, position: ParsePosition?): Any? = null
            }
            val rangeLabelStyle = getLineLabelStyle(XYGraphWidget.Edge.LEFT).apply {
                paint.color = plotInk
                paint.typeface = context.consoleFont()
                paint.textSize = 11f * scale
                paint.textAlign = Paint.Align.RIGHT
                format = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
            }
            val labelWidth = rangeLabelStyle.paint.measureText(rangeLabelStyle.format.format(rangeMaximum))
            setPadding(
                maxOf(52f * scale, labelWidth + 8f * scale, 8f * scale - domainLabelBounds.left),
                14f * scale,
                max(32f * scale, domainLabelBounds.right + 8f * scale),
                max(56f * scale, labelOffset + domainLabelBounds.bottom + 8f * scale),
            )
            val gridColor = if (export) Color.rgb(224, 231, 227) else ruleColor
            domainGridLinePaint.color = if (options.xGrid) gridColor else Color.TRANSPARENT
            domainSubGridLinePaint.color = if (options.xGrid) gridColor else Color.TRANSPARENT
            domainOriginLinePaint.color = if (options.xGrid) gridColor else Color.TRANSPARENT
            rangeGridLinePaint.color = if (options.yGrid) gridColor else Color.TRANSPARENT
            rangeSubGridLinePaint.color = if (options.yGrid) gridColor else Color.TRANSPARENT
            rangeOriginLinePaint.color = if (options.yGrid) gridColor else Color.TRANSPARENT
        }
        data.series.forEachIndexed { index, series ->
            val seriesColor = color(series.colorIndex ?: index, export)
            val formatter = LineAndPointFormatter(seriesColor, if (data.labels.size <= 31) seriesColor else null, null, null)
            formatter.linePaint.strokeWidth = 1.6f * scale
            formatter.vertexPaint?.strokeWidth = 4f * scale
            plot.addSeries(SimpleXYSeries(series.values, SimpleXYSeries.ArrayFormat.Y_VALS_ONLY, series.name), formatter)
        }
        plot.setDomainBoundaries(0, max(1, data.labels.lastIndex), BoundaryMode.FIXED)
        plot.setRangeBoundaries(0, rangeMaximum, BoundaryMode.FIXED)
        plot.setDomainStep(StepMode.INCREMENT_BY_VAL, options.sparsity.toDouble())
        plot.setRangeStep(StepMode.INCREMENT_BY_VAL, rangeStep)
        plot.contentDescription = "${data.title}, ${data.labels.size} data points"
        return plot
    }
}