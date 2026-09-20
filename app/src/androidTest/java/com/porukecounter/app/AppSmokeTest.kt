package com.porukecounter.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.porukecounter.core.AnalysisConfig
import com.porukecounter.core.ChatAnalyzer
import com.porukecounter.core.GraphKind
import com.porukecounter.core.GraphOptions
import com.porukecounter.core.Graphs
import com.porukecounter.core.ReportOptions
import com.porukecounter.core.Reports
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sample = "1.2.24., 08:09 - Alex: hello there\n2.2.24., 09:59 - Blair: three whole words\n1.3.24., 10:00 - Alex: hello\n"
    private var savedPreferences: Map<String, *> = emptyMap<String, Any>()

    @Before
    fun preserveUserSettings() {
        savedPreferences = context.getSharedPreferences("poruke", 0).all.toMap()
    }

    @After
    fun restoreUserSettings() {
        val editor = context.getSharedPreferences("poruke", 0).edit().clear()
        savedPreferences.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }

    @Test
    fun importDefaultsAcceptWholeChatAndPreserveSavedRange() {
        val preferences = context.getSharedPreferences("poruke", 0)
        preferences.edit().clear().commit()
        val file = File(context.filesDir, "import-defaults.txt").apply { writeText(sample) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AppModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[AppModel::class.java]
                model.addFiles(listOf(Uri.fromFile(file)))
                val views = descendants(activity.window.decorView).toList()
                val start = views.filterIsInstance<EditText>().first { it.contentDescription == "Start month" }
                val end = views.filterIsInstance<EditText>().first { it.contentDescription == "End month" }
                assertEquals("0.0", start.text.toString())
                assertEquals("12.99", end.text.toString())
                views.filterIsInstance<EditText>().first { it.contentDescription == "Participants (one per line)" }.setText("Alex\nBlair")
                views.filterIsInstance<Button>().first { it.text == "Analyze" }.performClick()
            }
            runBlocking {
                withTimeout(15000) { model.state.first { !it.busy && it.result != null } }
            }
            instrumentation.waitForIdleSync()
            assertEquals(3L, model.state.value.result!!.rangeTotal)
            assertEquals(listOf("2.24", "3.24"), model.state.value.result!!.months)
            assertEquals("0.0", preferences.getString("start", null))
            assertEquals("12.99", preferences.getString("end", null))
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView).toList()
                val add = views.filterIsInstance<Button>().first { it.text == "Add WhatsApp exports" }
                val remove = views.filterIsInstance<ImageButton>().first { it.contentDescription == "Remove ${file.name}" }
                val addBounds = Rect()
                val removeBounds = Rect()
                assertTrue(add.getGlobalVisibleRect(addBounds))
                assertTrue(remove.getGlobalVisibleRect(removeBounds))
                assertTrue("Trash button must be separated from the export picker", removeBounds.top - addBounds.bottom >= activity.dp(8))
                assertTrue("Trash button must retain its touch target", remove.height >= activity.dp(48))
            }
            screenshot("import-default-months.png")
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView).toList()
                views.filterIsInstance<EditText>().first { it.contentDescription == "Start month" }.setText("1.24")
                views.filterIsInstance<EditText>().first { it.contentDescription == "End month" }.setText("3.24")
                views.filterIsInstance<Button>().first { it.text == "Analyze" }.performClick()
            }
            runBlocking {
                withTimeout(15000) { model.state.first { !it.busy && it.result != null } }
            }
            instrumentation.waitForIdleSync()
            assertEquals(listOf("1.24", "2.24", "3.24"), model.state.value.result!!.months)
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val inputs = descendants(activity.window.decorView).filterIsInstance<EditText>().toList()
                assertEquals("1.24", inputs.first { it.contentDescription == "Start month" }.text.toString())
                assertEquals("3.24", inputs.first { it.contentDescription == "End month" }.text.toString())
                assertEquals(3L, ViewModelProvider(activity)[AppModel::class.java].state.value.result!!.rangeTotal)
            }
        }
    }

    @Test
    fun importAnalyzeBrowseAllOutputsAndRecreate() {
        context.getSharedPreferences("poruke", 0).edit().clear().commit()
        val file = File(context.filesDir, "smoke-chat.txt").apply { writeText(sample) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AppModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[AppModel::class.java]
                model.addFiles(listOf(Uri.fromFile(file)))
                val views = descendants(activity.window.decorView).toList()
                fun input(label: String, value: String) {
                    (views.first { it is EditText && it.contentDescription == label } as EditText).setText(value)
                }
                input("Participants (one per line)", "Alex\nBlair")
                input("Start month", "2.24")
                input("End month", "3.24")
                (views.first { it is Button && it.text == "Analyze" } as Button).performClick()
            }
            runBlocking {
                withTimeout(15000) { model.state.first { !it.busy && it.result != null } }
            }
            instrumentation.waitForIdleSync()
            assertEquals(3L, model.state.value.result!!.total)
            scenario.onActivity { activity -> activity.findViewById<RadioButton>(R.id.tab_graphs).performClick() }
            instrumentation.waitForIdleSync()
            for (kind in GraphKind.entries) {
                scenario.onActivity { activity ->
                    val selector = descendants(activity.window.decorView).filterIsInstance<Spinner>().first { it.contentDescription == "Graph" }
                    selector.setSelection(kind.ordinal)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView).toList()
                    val charts = views.filterIsInstance<com.androidplot.xy.XYPlot>().filter { it.isShown }
                    val scroll = views.filterIsInstance<ScrollView>().first { it.isShown }
                    fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
                        scroll.offsetDescendantRectToMyCoords(view, it)
                    }
                    assertEquals(if (kind == GraphKind.OVERVIEW) 4 else 1, charts.size)
                    charts.forEach { assertTrue("${kind.name} chart measured zero width", it.width > 100) }
                    val update = views.filterIsInstance<Button>().first { it.text == "Update graph" }
                    assertTrue("Update graph must stay above ${kind.name}", bounds(update).bottom <= charts.minOf { bounds(it).top })
                    for (label in listOf("Chart settings", "Export PNG", "Export graph CSV")) {
                        val action = views.filterIsInstance<Button>().first { it.text == label }
                        assertTrue("$label must be below every ${kind.name} chart", bounds(action).top >= charts.maxOf { bounds(it).bottom })
                    }
                    val toggle = views.filterIsInstance<Button>().firstOrNull { it.text == "Expand graph" }
                    if (kind == GraphKind.OVERVIEW) {
                        assertTrue("Overview must retain its existing layout", toggle == null)
                    } else {
                        val chart = charts.single()
                        val scroller = chart.parent as android.widget.HorizontalScrollView
                        assertEquals("$kind must fit the screen by default", scroller.width, chart.width)
                        assertTrue("$kind must show the full horizontal range", !scroller.canScrollHorizontally(1) && !scroller.canScrollHorizontally(-1))
                        assertEquals(3L, chart.registry.seriesList.sumOf { series -> (0 until series.size()).sumOf { series.getY(it).toLong() } })
                        assertTrue("Size toggle must be below the graph", toggle != null && bounds(toggle).top >= bounds(chart).bottom)
                        toggle!!.performClick()
                    }
                }
                if (kind != GraphKind.OVERVIEW) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val views = descendants(activity.window.decorView).toList()
                        val chart = views.filterIsInstance<com.androidplot.xy.XYPlot>().first { it.isShown }
                        val scroller = chart.parent as android.widget.HorizontalScrollView
                        assertEquals("$kind must expand to its configured width", maxOf(scroller.width, activity.dp(15 * 48)), chart.width)
                        scroller.scrollTo(chart.width, 0)
                        assertEquals(chart.width - scroller.width, scroller.scrollX)
                        views.filterIsInstance<Button>().first { it.text == "Fit to screen" }.performClick()
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val chart = descendants(activity.window.decorView).filterIsInstance<com.androidplot.xy.XYPlot>().first { it.isShown }
                        val scroller = chart.parent as android.widget.HorizontalScrollView
                        assertEquals(scroller.width, chart.width)
                        assertEquals("Fitting must reset the horizontal scroll offset", 0, scroller.scrollX)
                        assertEquals(3L, chart.registry.seriesList.sumOf { series -> (0 until series.size()).sumOf { series.getY(it).toLong() } })
                    }
                }
            }
            scenario.onActivity { activity ->
                val scroll = descendants(activity.window.decorView).filterIsInstance<android.widget.ScrollView>().first { it.isShown }
                scroll.fullScroll(View.FOCUS_DOWN)
            }
            instrumentation.waitForIdleSync()
            val screen = instrumentation.uiAutomation.takeScreenshot()
            val rectangle = android.graphics.Rect()
            scenario.onActivity { activity ->
                val chart = descendants(activity.window.decorView).filterIsInstance<com.androidplot.xy.XYPlot>().first { it.isShown }
                assertTrue(chart.getGlobalVisibleRect(rectangle))
            }
            var seriesPixels = 0
            for (vertical in rectangle.top.coerceAtLeast(0) until rectangle.bottom.coerceAtMost(screen.height) step 2) {
                for (horizontal in rectangle.left.coerceAtLeast(0) until rectangle.right.coerceAtMost(screen.width) step 2) {
                    val pixel = screen.getPixel(horizontal, vertical)
                    if (Color.green(pixel) > Color.red(pixel) + 45 && Color.green(pixel) > Color.blue(pixel) + 25) seriesPixels++
                }
            }
            screen.recycle()
            assertTrue("On-screen chart has no colored series", seriesPixels > 10)
            screenshot("graphs.png")
            scenario.onActivity { activity -> activity.findViewById<RadioButton>(R.id.tab_reports).performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(descendants(activity.window.decorView).any { it is TextView && it.text == "Participant totals" })
                val reportViews = descendants(activity.window.decorView).toList()
                assertTrue(reportViews.none { it is TextView && it.text in listOf("EXPORT", "Export count", "Export threshold", "Export status") })
                assertTrue(reportViews.none { it is EditText && it.contentDescription in listOf("New export warning threshold", "Export count override (optional)") })
                assertTrue(reportViews.any { it is ImageButton && it.contentDescription == "Export reports CSV" })
            }
            screenshot("reports.png")
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(3L, ViewModelProvider(activity)[AppModel::class.java].state.value.result!!.total)
                assertTrue(activity.findViewById<RadioButton>(R.id.tab_reports).isChecked)
                activity.findViewById<RadioButton>(R.id.tab_import).performClick()
            }
            instrumentation.waitForIdleSync()
            screenshot("import.png")
        }
    }

    @Test
    fun participantColorsMatchSeriesAndLegendsAreOptional() {
        val preferences = context.getSharedPreferences("poruke", 0)
        preferences.edit().clear()
            .putString("names", "Alex\nBlair").putString("start", "2.24").putString("end", "3.24").commit()
        val file = File(context.filesDir, "participant-colors.txt").apply { writeText(sample) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AppModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[AppModel::class.java]
                model.addFiles(listOf(Uri.fromFile(file)))
                model.analyze(model.savedConfig())
            }
            runBlocking {
                withTimeout(15000) { model.state.first { !it.busy && it.result != null } }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> activity.findViewById<RadioButton>(R.id.tab_graphs).performClick() }
            instrumentation.waitForIdleSync()
            fun assertColors(names: List<String>, legend: Boolean = false) {
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView).toList()
                    val checks = views.filterIsInstance<CheckBox>()
                    assertEquals(legend, checks.first { it.text == "Legend" }.isChecked)
                    val chart = views.filterIsInstance<com.androidplot.xy.XYPlot>().first { it.isShown }
                    assertEquals(names, chart.registry.seriesList.map { it.title })
                    for (series in chart.registry.seriesList) {
                        val checkbox = checks.first { it.text == series.title }
                        val expected = ChartViews.color(listOf("Alex", "Blair").indexOf(series.title))
                        assertEquals(expected, checkbox.currentTextColor)
                        val formatter = chart.getFormatter(series, com.androidplot.xy.LineAndPointRenderer::class.java) as com.androidplot.xy.LineAndPointFormatter
                        assertEquals(expected, formatter.linePaint.color)
                        assertEquals(if (series.size() <= 31) expected else Color.TRANSPARENT, formatter.vertexPaint?.color ?: Color.TRANSPARENT)
                        val labels = views.filterIsInstance<TextView>().filter { it !is CheckBox && it.isShown && it.text == series.title }
                        assertEquals(if (legend) 1 else 0, labels.size)
                        if (legend) assertEquals(expected, labels.single().currentTextColor)
                    }
                }
            }
            for (kind in GraphKind.entries) {
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<Spinner>().first { it.contentDescription == "Graph" }.setSelection(kind.ordinal)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue("Legend should default to off for $kind", !descendants(activity.window.decorView).filterIsInstance<CheckBox>().first { it.text == "Legend" }.isChecked)
                }
                if (kind in listOf(GraphKind.OVERVIEW, GraphKind.MONTHS)) continue
                assertColors(listOf("Alex", "Blair"))
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView).toList()
                    views.filterIsInstance<CheckBox>().first { it.text == "Alex" }.isChecked = false
                    views.filterIsInstance<Button>().first { it.text == "Update graph" }.performClick()
                }
                instrumentation.waitForIdleSync()
                assertColors(listOf("Blair"))
            }
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<Spinner>().first { it.contentDescription == "Graph" }.setSelection(GraphKind.HOURS.ordinal)
            }
            instrumentation.waitForIdleSync()
            assertColors(listOf("Blair"))
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView).toList()
                val scroll = views.filterIsInstance<ScrollView>().first { it.isShown }
                val checkbox = views.filterIsInstance<CheckBox>().first { it.text == "Alex" }
                val bounds = Rect(0, 0, checkbox.width, checkbox.height)
                scroll.offsetDescendantRectToMyCoords(checkbox, bounds)
                scroll.scrollBy(0, bounds.top - activity.dp(28))
            }
            instrumentation.waitForIdleSync()
            screenshot("participant-colors.png")
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView).toList()
                views.filterIsInstance<Button>().first { it.text == "Chart settings" }.performClick()
                views.filterIsInstance<CheckBox>().first { it.text == "Legend" }.isChecked = true
                views.filterIsInstance<Button>().first { it.text == "Update graph" }.performClick()
            }
            instrumentation.waitForIdleSync()
            assertColors(listOf("Blair"), legend = true)
            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertColors(listOf("Blair"), legend = true)
            scenario.onActivity {
                val options = GraphOptions(listOf("Blair"), legend = true, xSize = 8, ySize = 5)
                val charts = Graphs.build(model.state.value.result!!, GraphKind.HOURS, options)
                val bitmap = ChartViews.bitmap(context, charts, options)
                val expected = ChartViews.color(1, export = true)
                var linePixels = 0
                var legendPixels = 0
                for (vertical in 0 until bitmap.height) for (horizontal in 0 until bitmap.width) {
                    if (bitmap.getPixel(horizontal, vertical) == expected) {
                        if (vertical < bitmap.height - 24) linePixels++ else legendPixels++
                    }
                }
                assertTrue("Exported line lost the participant's color", linePixels > 10)
                assertTrue("Exported legend lost the participant's color", legendPixels > 10)
                bitmap.recycle()
            }
        }
    }

    @Test
    fun everyGraphAppliesAndRemembersDateRange() {
        val preferences = context.getSharedPreferences("poruke", 0)
        preferences.edit().clear()
            .putString("names", "Alex\nBlair").putString("start", "2.24").putString("end", "3.24")
            .putInt("graphKind", GraphKind.MONTHS.ordinal)
            .putString("graph.MONTHS.from", "2.2.24").putString("graph.MONTHS.to", "29.2.24")
            .putString("graph.HOURS.from", "1.3.24").putString("graph.HOURS.to", "31.3.24")
            .putString("graph.MONTHS.title", "Monthly comparison")
            .putString("graph.HOURS.title", "Hourly comparison").commit()
        val file = File(context.filesDir, "graph-dates.txt").apply { writeText(sample) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AppModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[AppModel::class.java]
                model.addFiles(listOf(Uri.fromFile(file)))
                model.analyze(AnalysisConfig(listOf("Alex", "Blair"), "2.24", "3.24"))
            }
            runBlocking {
                withTimeout(15000) { model.state.first { !it.busy && it.result != null } }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> activity.findViewById<RadioButton>(R.id.tab_graphs).performClick() }
            instrumentation.waitForIdleSync()
            fun select(kind: GraphKind) {
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<Spinner>().first { it.contentDescription == "Graph" }.setSelection(kind.ordinal)
                }
                instrumentation.waitForIdleSync()
            }
            fun assertRange(startingDate: String, endingDate: String, expectedCount: Long) {
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView).toList()
                    val start = views.filterIsInstance<EditText>().first { it.contentDescription == "Starting date" }
                    val end = views.filterIsInstance<EditText>().first { it.contentDescription == "Ending date" }
                    assertTrue("Missing visible date controls", start.isShown && end.isShown)
                    assertEquals(startingDate, start.text.toString())
                    assertEquals(endingDate, end.text.toString())
                    val kind = GraphKind.entries[views.filterIsInstance<Spinner>().first { it.contentDescription == "Graph" }.selectedItemPosition]
                    val charts = views.filterIsInstance<com.androidplot.xy.XYPlot>().filter { it.isShown }
                    assertEquals(if (kind == GraphKind.OVERVIEW) 4 else 1, charts.size)
                    val count = charts.first().registry.seriesList.sumOf { series -> (0 until series.size()).sumOf { series.getY(it).toLong() } }
                    assertEquals("Date filter was not applied to $kind", expectedCount, count)
                    assertEquals(startingDate, preferences.getString("graph.from", null))
                    assertEquals(endingDate, preferences.getString("graph.to", null))
                    if (kind == GraphKind.OVERVIEW) {
                        assertTrue(views.filterIsInstance<TextView>().any { it.text == "Messages in February 2024" })
                    }
                }
            }
            assertRange("2.2.24", "29.2.24", 1L)
            for (kind in GraphKind.entries) {
                select(kind)
                assertRange("2.2.24", "29.2.24", 1L)
            }
            scenario.onActivity { activity ->
                val fields = descendants(activity.window.decorView).filterIsInstance<EditText>().toList()
                fields.first { it.contentDescription == "Starting date" }.setText("1.2.24")
                fields.first { it.contentDescription == "Ending date" }.setText("1.3.24")
            }
            select(GraphKind.HOURS)
            assertRange("1.2.24", "1.3.24", 3L)
            screenshot("graphs-hour-date-controls.png")
            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertRange("1.2.24", "1.3.24", 3L)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<RadioButton>(R.id.tab_graphs).isChecked)
                val title = descendants(activity.window.decorView).filterIsInstance<EditText>().first { it.contentDescription == "Title" }
                assertEquals("Hourly comparison", title.text.toString())
            }
            select(GraphKind.MONTHS)
            assertRange("1.2.24", "1.3.24", 3L)
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView).toList()
                assertEquals("Monthly comparison", views.filterIsInstance<EditText>().first { it.contentDescription == "Title" }.text.toString())
                views.filterIsInstance<EditText>().first { it.contentDescription == "Starting date" }.setText("2.2.24")
                views.filterIsInstance<Button>().first { it.text == "Update graph" }.performClick()
            }
            instrumentation.waitForIdleSync()
            assertRange("2.2.24", "1.3.24", 2L)
            scenario.onActivity { activity ->
                val chart = descendants(activity.window.decorView).filterIsInstance<com.androidplot.xy.XYPlot>().first { it.isShown }
                assertEquals(listOf(1L, 1L), chart.registry.seriesList.single().let { series -> (0 until series.size()).map { series.getY(it).toLong() } })
                assertEquals("February 2024", chart.graph.getLineLabelStyle(com.androidplot.xy.XYGraphWidget.Edge.BOTTOM).format.format(0))
                assertEquals(3L, model.state.value.result!!.total)
            }
            screenshot("graphs-month-date-controls.png")
            select(GraphKind.HOURS)
            assertRange("2.2.24", "1.3.24", 2L)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AppModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[AppModel::class.java]
                model.analyze(model.savedConfig())
            }
            runBlocking {
                withTimeout(15000) { model.state.first { !it.busy && it.result != null } }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activity.findViewById<RadioButton>(R.id.tab_graphs).performClick()
                val views = descendants(activity.window.decorView).toList()
                assertEquals("2.2.24", views.filterIsInstance<EditText>().first { it.contentDescription == "Starting date" }.text.toString())
                assertEquals("1.3.24", views.filterIsInstance<EditText>().first { it.contentDescription == "Ending date" }.text.toString())
                assertEquals("Hourly comparison", views.filterIsInstance<EditText>().first { it.contentDescription == "Title" }.text.toString())
                val chart = views.filterIsInstance<com.androidplot.xy.XYPlot>().first { it.isShown }
                assertEquals(2L, chart.registry.seriesList.sumOf { series -> (0 until series.size()).sumOf { series.getY(it).toLong() } })
            }
        }
    }

    @Test
    fun pngHasColoredSeriesInEveryOverviewPanel() {
        val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex", "Blair"), "2.24", "3.24"))
        analyzer.consume("smoke", sample.lineSequence())
        val options = GraphOptions(listOf("Alex", "Blair"), xSize = 15, ySize = 10)
        val charts = Graphs.build(analyzer.result(), GraphKind.OVERVIEW, options)
        instrumentation.runOnMainSync {
            val bitmap = ChartViews.bitmap(context, charts, options)
            assertEquals(1500, bitmap.width)
            assertEquals(1000, bitmap.height)
            assertEquals("Overview background must remain opaque white", Color.WHITE, bitmap.getPixel(2, 2))
            var titlePixels = 0
            for (vertical in 5..25) for (horizontal in 10..300) {
                val pixel = bitmap.getPixel(horizontal, vertical)
                if (Color.alpha(pixel) == 255 && Color.red(pixel) < 100) titlePixels++
            }
            assertTrue("Overview title was cleared by a chart", titlePixels > 20)
            for (panel in 0..3) {
                val left = panel % 2 * 750
                val top = panel / 2 * 500
                var coloredPixels = 0
                for (vertical in top + 45 until top + 400 step 2) {
                    for (horizontal in left + 60 until left + 700 step 2) {
                        val pixel = bitmap.getPixel(horizontal, vertical)
                        if (Color.red(pixel) > Color.green(pixel) + 35 && Color.green(pixel) > Color.blue(pixel) + 20) coloredPixels++
                    }
                }
                assertTrue("Panel $panel has no visible data", coloredPixels > 10)
            }
            File(context.getExternalFilesDir(null), "overview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test
    fun fittedGraphsStayReadableAtPhoneAndTabletWidths() {
        instrumentation.runOnMainSync {
            for (labelKind in listOf("months", "days")) {
                val labels = if (labelKind == "months") (0L..36L).map {
                    val month = YearMonth.of(2023, 9).plusMonths(it)
                    com.porukecounter.core.ReportDates.month("${month.monthValue}.${month.year}")
                } else (0L..1135L).map {
                    val date = java.time.LocalDate.of(2023, 9, 1).plusDays(it)
                    "${date.dayOfMonth}.${date.monthValue}.${date.year}"
                }
                val values = labels.indices.map { (100 + it * 13 % 400).toLong() }
                val data = com.porukecounter.core.ChartData("Messages by $labelKind", labels, listOf(com.porukecounter.core.ChartSeries("Alex", values)))
                val options = GraphOptions(listOf("Alex"), sparsity = if (labelKind == "days") 15 else 1)
                val host = context.column()
                ChartViews.add(host, data, options, expandable = true)
                val plot = descendants(host).filterIsInstance<com.androidplot.xy.XYPlot>().single()
                val scroll = plot.parent as android.widget.HorizontalScrollView
                val toggle = descendants(host).filterIsInstance<Button>().single()
                for (width in listOf(320, 800, 320)) {
                    for (expanded in listOf(false, true, false)) {
                        val action = if (expanded) "Fit to screen" else "Expand graph"
                        if (toggle.text != action) toggle.performClick()
                        host.measure(View.MeasureSpec.makeMeasureSpec(context.dp(width), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
                        val wideWidth = maxOf(context.resources.displayMetrics.widthPixels - context.dp(32), context.dp(options.xSize * 48))
                        assertEquals(if (expanded) maxOf(scroll.width, wideWidth) else scroll.width, plot.width)
                        assertEquals(0, scroll.scrollX)
                        val bitmap = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
                        host.draw(android.graphics.Canvas(bitmap))
                        assertTrue("Plot has no drawing area", plot.graph.gridRect.width() > 0 && plot.graph.gridRect.height() > 0)
                        assertEquals(0.0, plot.bounds.minX.toDouble(), 0.0)
                        assertEquals(labels.lastIndex.toDouble(), plot.bounds.maxX.toDouble(), 0.0)
                        val series = plot.registry.seriesList.single()
                        assertEquals(values, (0 until series.size()).map { series.getY(it).toLong() })
                        if (!expanded) {
                            assertTrue(!scroll.canScrollHorizontally(1) && !scroll.canScrollHorizontally(-1))
                            val style = plot.graph.getLineLabelStyle(com.androidplot.xy.XYGraphWidget.Edge.BOTTOM)
                            val rotation = android.graphics.Matrix().apply { setRotate(style.rotation) }
                            var previousRight = Float.NEGATIVE_INFINITY
                            var visibleLabels = 0
                            for (index in labels.indices) {
                                val label = style.format.format(index)
                                if (label.isEmpty()) continue
                                val bounds = Rect()
                                style.paint.getTextBounds(label, 0, label.length, bounds)
                                val rotated = android.graphics.RectF(bounds).apply { offset(-style.paint.measureText(label) / 2f, 0f) }
                                rotation.mapRect(rotated)
                                val horizontal = plot.graph.gridRect.left + plot.graph.gridRect.width() * index / labels.lastIndex
                                assertTrue("Fitted $labelKind labels overlap at ${width}dp: $label", horizontal + rotated.left >= previousRight)
                                assertTrue("Fitted label clipped on left", horizontal + rotated.left >= 0)
                                assertTrue("Fitted label clipped on right", horizontal + rotated.right <= plot.width)
                                assertTrue("Fitted label overlaps plot", plot.graph.labelRect.bottom + rotated.top >= plot.graph.gridRect.bottom)
                                assertTrue("Fitted label clipped below plot", plot.graph.labelRect.bottom + rotated.bottom <= plot.height)
                                previousRight = horizontal + rotated.right
                                visibleLabels++
                            }
                            assertTrue("Missing fitted axis labels", visibleLabels >= 2)
                        }
                        var seriesPixels = 0
                        for (vertical in 0 until bitmap.height step 2) for (horizontal in 0 until bitmap.width step 2) {
                            if (bitmap.getPixel(horizontal, vertical) == ChartViews.color(0)) seriesPixels++
                        }
                        assertTrue("Graph is blank at ${width}dp", seriesPixels > 10)
                        File(context.getExternalFilesDir(null), "graph-$labelKind-${width}dp-${if (expanded) "wide" else "fitted"}.png")
                            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun xAxisLabelsStayBelowPlotWithoutClipping() {
        val months = (0L..36L).map { YearMonth.of(2023, 9).plusMonths(it) }
        val names = listOf("Alex", "Blair")
        val options = GraphOptions(names, xSize = 10, ySize = 5, legend = false)
        instrumentation.runOnMainSync {
            for (labelKind in listOf("months", "dates", "named-months")) {
                val labels = months.map {
                    when (labelKind) {
                        "dates" -> "28.${it.monthValue}.${it.year}"
                        "named-months" -> com.porukecounter.core.ReportDates.month("${it.monthValue}.${it.year}")
                        else -> "${it.monthValue}.${it.year - 2000}"
                    }
                }
                val data = com.porukecounter.core.ChartData("Messages per person", labels, names.mapIndexed { person, name ->
                    com.porukecounter.core.ChartSeries(name, labels.indices.map { index -> (100 + (index * 73 + person * 137) % 600).toLong() })
                })
                val host = context.column()
                ChartViews.add(host, data, options)
                host.measure(View.MeasureSpec.makeMeasureSpec(context.dp(320), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                host.layout(0, 0, host.measuredWidth, host.measuredHeight)
                val plot = descendants(host).filterIsInstance<com.androidplot.xy.XYPlot>().single()
                val bitmap = Bitmap.createBitmap(plot.width, plot.height, Bitmap.Config.ARGB_8888)
                plot.draw(android.graphics.Canvas(bitmap))
                val style = plot.graph.getLineLabelStyle(com.androidplot.xy.XYGraphWidget.Edge.BOTTOM)
                val rotation = android.graphics.Matrix().apply { setRotate(style.rotation) }
                var previousRight = Float.NEGATIVE_INFINITY
                var visibleLabels = 0
                for ((index, label) in labels.withIndex()) {
                    val bounds = Rect()
                    style.paint.getTextBounds(label, 0, label.length, bounds)
                    val rotatedBounds = android.graphics.RectF(bounds).apply { offset(-style.paint.measureText(label) / 2f, 0f) }
                    rotation.mapRect(rotatedBounds)
                    val top = plot.graph.labelRect.bottom + rotatedBounds.top
                    val bottom = plot.graph.labelRect.bottom + rotatedBounds.bottom
                    assertTrue("X-axis label overlaps the plot: $label", top >= plot.graph.gridRect.bottom + context.dp(2))
                    assertTrue("X-axis label is clipped: $label", bottom <= plot.height)
                    val horizontal = plot.graph.gridRect.left + plot.graph.gridRect.width() * index / labels.lastIndex
                    assertTrue("First X-axis label is clipped: $label", horizontal + rotatedBounds.left >= 0)
                    assertTrue("Last X-axis label is clipped: $label", horizontal + rotatedBounds.right <= plot.width)
                    if (style.format.format(index).isNotEmpty()) {
                        assertTrue("X-axis labels overlap: $label", horizontal + rotatedBounds.left >= previousRight)
                        previousRight = horizontal + rotatedBounds.right
                        visibleLabels++
                    }
                }
                assertTrue("Missing X-axis labels", visibleLabels >= 2)
                File(context.getExternalFilesDir(null), "axis-$labelKind-screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val exported = ChartViews.bitmap(context, listOf(data), options)
                File(context.getExternalFilesDir(null), "axis-$labelKind-export.png").outputStream().use { exported.compress(Bitmap.CompressFormat.PNG, 100, it) }
                exported.recycle()
                if (labelKind == "named-months") {
                    val selected = context.column()
                    ChartViews.add(selected, data, options.copy(xTicks = listOf("9.23", "September 2026")))
                    val selectedPlot = descendants(selected).filterIsInstance<com.androidplot.xy.XYPlot>().single()
                    val format = selectedPlot.graph.getLineLabelStyle(com.androidplot.xy.XYGraphWidget.Edge.BOTTOM).format
                    assertEquals("September 2023", format.format(0))
                    assertEquals("", format.format(1))
                    assertEquals("September 2026", format.format(labels.lastIndex))
                }
            }
        }
    }

    @Test
    fun yAxisUsesAdaptiveWholeNumberSteps() {
        val cases = listOf(
            Triple(0L, 1.0, 1.0),
            Triple(1L, 1.0, 2.0),
            Triple(2L, 1.0, 3.0),
            Triple(7L, 2.0, 8.0),
            Triple(37L, 10.0, 40.0),
            Triple(100L, 20.0, 120.0),
            Triple(501L, 200.0, 600.0),
            Triple(9682L, 2000.0, 12000.0),
            Triple(19248L, 5000.0, 25000.0),
            Triple(1000000L, 200000.0, 1200000.0),
        )
        val options = GraphOptions(listOf("Alex", "Blair"), xSize = 10, ySize = 5, legend = false)
        instrumentation.runOnMainSync {
            for ((maximum, expectedStep, expectedMaximum) in cases) {
                val data = com.porukecounter.core.ChartData("Messages per person per month", listOf("7.26", "8.26", "9.26"), listOf(
                    com.porukecounter.core.ChartSeries("Alex", listOf(0L, maximum / 2, maximum / 4)),
                    com.porukecounter.core.ChartSeries("Blair", listOf(maximum / 3, maximum, maximum / 2)),
                ))
                val host = context.column()
                ChartViews.add(host, data, options)
                host.measure(View.MeasureSpec.makeMeasureSpec(context.dp(320), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                host.layout(0, 0, host.measuredWidth, host.measuredHeight)
                val plot = descendants(host).filterIsInstance<com.androidplot.xy.XYPlot>().single()
                val bitmap = Bitmap.createBitmap(plot.width, plot.height, Bitmap.Config.ARGB_8888)
                plot.draw(android.graphics.Canvas(bitmap))
                assertEquals(com.androidplot.xy.StepMode.INCREMENT_BY_VAL, plot.rangeStepMode)
                assertEquals("Step for maximum $maximum", expectedStep, plot.rangeStepValue, 0.0)
                assertEquals(0.0, plot.bounds.minY.toDouble(), 0.0)
                assertEquals("Upper bound for maximum $maximum", expectedMaximum, plot.bounds.maxY.toDouble(), 0.0)
                assertTrue("No headroom for maximum $maximum", plot.bounds.maxY.toDouble() > maximum)
                val style = plot.graph.getLineLabelStyle(com.androidplot.xy.XYGraphWidget.Edge.LEFT)
                for (tick in 0..(expectedMaximum / expectedStep).toInt()) {
                    val value = tick * expectedStep
                    val label = style.format.format(value)
                    assertEquals(java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value.toLong()), label)
                    assertTrue("Y-axis label clipped: $label", style.paint.measureText(label) <= plot.graph.labelRect.left - plot.plotMarginLeft)
                }
                plot.registry.seriesList.forEachIndexed { index, series ->
                    assertEquals(data.series[index].values, (0 until series.size()).map { series.getY(it).toLong() })
                }
                if (maximum in listOf(7L, 9682L, 1000000L)) {
                    File(context.getExternalFilesDir(null), "axis-scale-$maximum-screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val exported = ChartViews.bitmap(context, listOf(data), options)
                    File(context.getExternalFilesDir(null), "axis-scale-$maximum-export.png").outputStream().use { exported.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    exported.recycle()
                }
                bitmap.recycle()
            }
        }
    }

    @Test
    fun reportsHaveYearColumnsAndMonthCalendar() {
        val preferences = context.getSharedPreferences("poruke", 0)
        preferences.edit().clear().putString("report.calendarMonth", "2.24").commit()
        val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex", "Blair"), "12.23", "3.24"))
        analyzer.consume("reports.txt", sequenceOf(
            "31.12.23., 12:00 - Alex: hello",
            "1.1.24., 12:00 - Alex: hello",
            "1.2.24., 12:00 - Alex: hello",
            "2.2.24., 12:00 - Blair: hello",
            "2.2.24., 13:00 - Blair: hello again",
            "29.2.24., 12:00 - Alex: leap day",
            "1.3.24., 12:00 - Alex: hello",
        ))
        val sections = Reports.build(analyzer.result(), ReportOptions(startingDate = "2.2.24"))
        assertTrue(sections[1].rows.any { it.first() == "December 2023" })
        assertTrue(sections[2].rows.any { it.first() == "29.2.2024." })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var host: LinearLayout
            scenario.onActivity { activity ->
                host = activity.column().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(24)); background = activity.techBackground() }
                activity.setContentView(ScrollView(activity).apply { addView(host) })
                ReportViews(host, preferences, "messages").render(sections)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val views = descendants(host).toList()
                assertTrue(views.filterIsInstance<TextView>().any { it.text == "2023" })
                assertTrue(views.filterIsInstance<TextView>().any { it.text == "2024" })
                assertTrue(views.filterIsInstance<TextView>().any { it.text == "September" })
                val grid = views.first { it.tag == "report-calendar-grid" } as ViewGroup
                val cells = descendants(grid).filter { it.tag?.toString()?.startsWith("day-") == true }.toList()
                assertEquals(29, cells.size)
                assertEquals(6, grid.childCount)
                assertEquals("day-1.2.24", (grid.getChildAt(0) as ViewGroup).getChildAt(3).tag)
                assertTrue(!cells.first { it.tag == "day-1.2.24" }.isEnabled)
                val latestGrid = views.first { it.tag == "report-latest-calendar-grid" } as ViewGroup
                val latestCells = descendants(latestGrid).filter { it.tag?.toString()?.startsWith("day-") == true }.toList()
                assertEquals(31, latestCells.size)
                assertTrue(latestCells.all { it.isEnabled })
                latestCells.first { it.tag == "day-31.3.24" }.performClick()
                assertEquals("31.3.24", preferences.getString("report.latestCalendarDay", null))
                cells.first { it.tag == "day-29.2.24" }.performClick()
                assertEquals("29.2.24", preferences.getString("report.calendarDay", null))
                assertEquals("31.3.24", preferences.getString("report.latestCalendarDay", null))
                assertTrue(descendants(host).filterIsInstance<TextView>().any { it.text == "29.2.2024." })
                views.filterIsInstance<ImageButton>().first { it.contentDescription == "Next month" }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val views = descendants(host).toList()
                assertEquals("3.24", preferences.getString("report.calendarMonth", null))
                assertTrue(!views.filterIsInstance<ImageButton>().first { it.contentDescription == "Next month" }.isEnabled)
                val grid = views.first { it.tag == "report-calendar-grid" }
                assertEquals(31, descendants(grid).count { it.tag?.toString()?.startsWith("day-") == true })
                views.first { it.tag == "month-2.24" }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals("2.24", preferences.getString("report.calendarMonth", null))
                assertTrue(!descendants(host).filterIsInstance<ImageButton>().first { it.contentDescription == "Previous month" }.isEnabled)
                descendants(host).filterIsInstance<RadioButton>().first { it.text == "One year" }.performClick()
                assertTrue(preferences.getBoolean("report.singleYear", false))
                assertEquals(2024, preferences.getInt("report.year", 0))
                assertTrue(descendants(host).none { it.tag == "month-12.23" })
                val next = descendants(host).filterIsInstance<ImageButton>().first { it.contentDescription == "Next year" }
                assertTrue(!next.isEnabled)
                descendants(host).filterIsInstance<ImageButton>().first { it.contentDescription == "Previous year" }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(2023, preferences.getInt("report.year", 0))
                assertEquals(listOf("month-12.23"), descendants(host).mapNotNull { it.tag as? String }.filter { it.startsWith("month-") }.toList())
                assertTrue(!descendants(host).filterIsInstance<ImageButton>().first { it.contentDescription == "Previous year" }.isEnabled)
                descendants(host).filterIsInstance<ImageButton>().first { it.contentDescription == "Next year" }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(2024, preferences.getInt("report.year", 0))
                descendants(host).filterIsInstance<Spinner>().first { it.contentDescription == "Report year" }.setSelection(0)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(2023, preferences.getInt("report.year", 0))
                assertTrue(descendants(host).filterIsInstance<TextView>().any { it.text == "December 2023" })
                ReportViews(host, preferences, "messages").render(sections)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(2023, preferences.getInt("report.year", 0))
                assertTrue(descendants(host).filterIsInstance<RadioButton>().first { it.text == "One year" }.isChecked)
                assertEquals(listOf("month-12.23"), descendants(host).mapNotNull { it.tag as? String }.filter { it.startsWith("month-") }.toList())
                assertEquals("2.24", preferences.getString("report.calendarMonth", null))
                assertEquals("31.3.24", preferences.getString("report.latestCalendarDay", null))
                descendants(host).filterIsInstance<RadioButton>().first { it.text == "All years" }.performClick()
                assertTrue(!preferences.getBoolean("report.singleYear", true))
                assertEquals(4, descendants(host).count { it.tag?.toString()?.startsWith("month-") == true })
                assertEquals(4, sections[1].months.size)
            }
        }
    }

    @Test
    fun reportSectionsCollapseIndependentlyAndRememberState() {
        val preferences = context.getSharedPreferences("poruke", 0)
        preferences.edit().clear()
            .putString("names", "Alex\nBlair").putString("start", "2.24").putString("end", "3.24").commit()
        val titles = listOf("Participant totals", "Monthly counts", "Daily counts", "Latest month (Python)", "Summary")
        val file = File(context.filesDir, "collapsible-reports.txt").apply { writeText(sample) }
        fun assertExpanded(scenario: ActivityScenario<MainActivity>, expanded: Set<Int>) {
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView).toList()
                titles.forEachIndexed { index, title ->
                    val heading = views.first { it.tag == "report-heading-$index" }
                    val body = views.first { it.tag == "report-body-$index" }
                    assertTrue("$title heading must remain available", heading.isShown && heading.isClickable && heading.isFocusable)
                    assertEquals(title, heading.contentDescription)
                    assertEquals("$title visibility", if (index in expanded) View.VISIBLE else View.GONE, body.visibility)
                    assertEquals(if (index in expanded) "Expanded" else "Collapsed", androidx.core.view.ViewCompat.getStateDescription(heading))
                }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AppModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[AppModel::class.java]
                model.addFiles(listOf(Uri.fromFile(file)))
                model.analyze(model.savedConfig())
            }
            runBlocking {
                withTimeout(15000) { model.state.first { !it.busy && it.result != null } }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> activity.findViewById<RadioButton>(R.id.tab_reports).performClick() }
            instrumentation.waitForIdleSync()
            val expanded = titles.indices.toMutableSet()
            assertExpanded(scenario, expanded)
            for (index in titles.indices) {
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView).toList()
                    val heading = views.first { it.tag == "report-heading-$index" }
                    if (index == 0) assertTrue(heading.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_COLLAPSE, null))
                    else heading.performClick()
                }
                instrumentation.waitForIdleSync()
                expanded.remove(index)
                assertExpanded(scenario, expanded)
            }
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView).toList()
                val scroll = views.filterIsInstance<ScrollView>().first { it.isShown }
                scroll.fullScroll(View.FOCUS_UP)
                for (index in titles.indices) {
                    val section = views.first { it.tag == "report-section-$index" }
                    val heading = views.first { it.tag == "report-heading-$index" }
                    assertEquals("Collapsed content must not reserve space", heading.bottom, section.height)
                }
            }
            instrumentation.waitForIdleSync()
            screenshot("reports-collapsed.png")
            scenario.onActivity { activity ->
                val heading = descendants(activity.window.decorView).first { it.tag == "report-heading-1" }
                assertTrue(heading.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND, null))
            }
            instrumentation.waitForIdleSync()
            assertExpanded(scenario, setOf(1))
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).first { it.tag == "month-3.24" }.performClick()
            }
            instrumentation.waitForIdleSync()
            assertExpanded(scenario, setOf(1, 2))
            assertEquals("3.24", preferences.getString("report.calendarMonth", null))
            scenario.onActivity { activity ->
                val body = descendants(activity.window.decorView).first { it.tag == "report-body-2" }
                descendants(body).first { it.tag == "day-2.3.24" }.performClick()
                val heading = descendants(activity.window.decorView).first { it.tag == "report-heading-2" }
                heading.performClick()
                heading.performClick()
                val buttons = descendants(activity.window.decorView).filterIsInstance<Button>().toList()
                buttons.first { it.text == "Report settings" }.performClick()
                buttons.first { it.text == "Update reports" }.performClick()
            }
            instrumentation.waitForIdleSync()
            assertExpanded(scenario, setOf(1, 2))
            assertEquals("2.3.24", preferences.getString("report.calendarDay", null))
            assertEquals(3L, model.state.value.result!!.rangeTotal)
            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertExpanded(scenario, setOf(1, 2))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AppModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[AppModel::class.java]
                model.analyze(model.savedConfig())
            }
            runBlocking {
                withTimeout(15000) { model.state.first { !it.busy && it.result != null } }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> activity.findViewById<RadioButton>(R.id.tab_reports).performClick() }
            instrumentation.waitForIdleSync()
            assertExpanded(scenario, setOf(1, 2))
            assertEquals("2.3.24", preferences.getString("report.calendarDay", null))
        }
    }

    @Test
    fun reportLayoutsFitPhoneAndTabletWidths() {
        val preferences = context.getSharedPreferences("poruke", 0)
        preferences.edit().clear().putString("report.calendarMonth", "9.26").commit()
        val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alexandria", "Blair"), "9.23", "9.26"))
        analyzer.consume("visual-fixture.txt", sequence {
            var month = YearMonth.of(2023, 9)
            while (month <= YearMonth.of(2026, 9)) {
                for (day in 1..month.lengthOfMonth()) {
                    val count = (day * 7 + month.monthValue * 3 + month.year) % 43
                    repeat(count) { index ->
                        yield("$day.${month.monthValue}.${month.year - 2000}., 12:00 - ${if (index % 2 == 0) "Alexandria" else "Blair"}: hello")
                    }
                }
                month = month.plusMonths(1)
            }
        })
        val sections = Reports.build(analyzer.result(), ReportOptions())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((width, singleYear) in listOf(320 to false, 800 to false, 320 to true, 800 to true)) {
                scenario.onActivity { activity ->
                    preferences.edit().putBoolean("report.singleYear", singleYear).putInt("report.year", 2026).commit()
                    val host = activity.column().apply {
                        setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(24))
                        background = activity.techBackground()
                    }
                    ReportViews(host, preferences, "messages").render(sections)
                    host.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(width), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    host.layout(0, 0, host.measuredWidth, host.measuredHeight)
                    for (cell in descendants(host).filterIsInstance<LinearLayout>().filter { it.tag?.toString()?.startsWith("day-") == true }) {
                        val count = cell.getChildAt(1) as TextView
                        assertTrue("Calendar count has no height: ${cell.tag}", count.height > 0)
                        assertTrue("Calendar count lies outside its cell: ${cell.tag}", count.bottom <= cell.height - cell.paddingBottom)
                        assertTrue("Calendar count is not laid out: ${cell.tag}", count.layout != null && count.layout.lineCount == 1)
                        assertTrue("Calendar count is vertically clipped: ${cell.tag}", count.layout.height <= count.height - count.compoundPaddingTop - count.compoundPaddingBottom)
                    }
                    for (text in descendants(host).filterIsInstance<TextView>()) {
                        if (text.visibility != View.VISIBLE || text.width == 0 || text.height == 0) continue
                        val layout = text.layout ?: continue
                        val availableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
                        for (line in 0 until layout.lineCount) {
                            assertTrue("Text overflows at ${width}dp: ${text.text} (${layout.getLineMax(line)} > $availableWidth)", layout.getLineMax(line) <= availableWidth + 2)
                        }
                    }
                    for (section in if (singleYear) listOf(1) else listOf(1, 2, 3, 4)) {
                        val view = descendants(host).first { it.tag == "report-section-$section" }
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap).apply { drawColor(canvasColor) }
                        view.draw(canvas)
                        val path = File(context.getExternalFilesDir(null), "reports-$section-${width}dp${if (singleYear) "-one-year" else ""}.png")
                        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}