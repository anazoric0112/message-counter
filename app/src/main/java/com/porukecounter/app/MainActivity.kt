package com.porukecounter.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.porukecounter.core.AnalysisConfig
import com.porukecounter.core.AnalysisResult
import com.porukecounter.core.ChartData
import com.porukecounter.core.CountMode
import com.porukecounter.core.CountingRules
import com.porukecounter.core.GraphKind
import com.porukecounter.core.GraphOptions
import com.porukecounter.core.Graphs
import com.porukecounter.core.ReportOptions
import com.porukecounter.core.ReportSection
import com.porukecounter.core.Reports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pages: List<ScrollView>
    private lateinit var inputPage: LinearLayout
    private lateinit var graphPage: LinearLayout
    private lateinit var reportPage: LinearLayout
    private lateinit var fileList: LinearLayout
    private lateinit var namesInput: EditText
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var modeInput: Spinner
    private lateinit var rulesInput: Spinner
    private var lastFiles: List<ChatFile>? = null
    private var lastResult: AnalysisResult? = null
    private var activeTab = 0
    private var persistGraph: (() -> Unit)? = null
    private var persistReports: (() -> Unit)? = null
    private var currentCharts: List<ChartData> = emptyList()
    private var currentGraphOptions: GraphOptions? = null
    private var currentReports: List<ReportSection> = emptyList()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> model.addFiles(uris) }
    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) writeCsv(uri, model.exportRows)
    }
    private val savePng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = model.pendingBitmap
        model.pendingBitmap = null
        if (uri != null && bitmap != null) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
                        output.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG export failed" } }
                    }
                    toast("PNG saved")
                } catch (error: Exception) {
                    showError(error)
                } finally {
                    bitmap.recycle()
                }
            }
        } else bitmap?.recycle()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeTab = savedInstanceState?.getInt("tab") ?: 0
        root = column().apply {
            setPadding(dp(16), dp(8), dp(16), 0)
            background = techBackground()
        }
        root.label("Poruke Counter", 22f, true).apply {
            setTextColor(accent)
            setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x667DF4B6)
        }
        status = root.label("", 11f).apply { setTextColor(muted) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        val tabs = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val tabIds = listOf(R.id.tab_import, R.id.tab_graphs, R.id.tab_reports)
        listOf("Import", "Graphs", "Reports").forEachIndexed { index, title ->
            tabs.addView(RadioButton(this).apply {
                id = tabIds[index]
                text = title
                techText(13f, true)
                buttonDrawable = null
                setTextColor(android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accent, muted),
                ))
                background = controlBackground()
                gravity = android.view.Gravity.CENTER
                minHeight = dp(48)
                isChecked = index == activeTab
            }, RadioGroup.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = if (index < 2) dp(6) else 0
                topMargin = dp(8)
                bottomMargin = dp(12)
            })
        }
        root.addView(tabs)
        inputPage = column()
        graphPage = column()
        reportPage = column()
        pages = listOf(inputPage, graphPage, reportPage).map { content ->
            ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                setPadding(0, 0, 0, dp(24))
                addView(content)
                root.addView(this, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        tabs.setOnCheckedChangeListener { _, selected -> selectTab(tabIds.indexOf(selected)) }
        setContentView(root)
        buildImport()
        buildGraphs(null)
        buildReports(null)
        selectTab(activeTab)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    status.text = state.status
                    progress.visibility = if (state.busy) View.VISIBLE else View.INVISIBLE
                    enableInputs(inputPage, !state.busy)
                    inputPage.findViewWithTag<View>("cancel")?.isEnabled = state.busy
                    if (state.files != lastFiles) {
                        lastFiles = state.files
                        renderFiles(state.files)
                    }
                    if (state.result !== lastResult) {
                        persistGraph?.invoke()
                        persistReports?.invoke()
                        lastResult = state.result
                        buildGraphs(state.result)
                        buildReports(state.result)
                    }
                }
            }
        }
    }

    private fun selectTab(index: Int) {
        activeTab = index.coerceIn(0, 2)
        pages.forEachIndexed { pageIndex, page -> page.visibility = if (pageIndex == activeTab) View.VISIBLE else View.GONE }
    }

    private fun buildImport() {
        inputPage.command("Add WhatsApp exports", android.R.drawable.ic_menu_add) { pickFiles.launch(arrayOf("text/*", "application/octet-stream")) }
        fileList = column()
        inputPage.addView(fileList)
        inputPage.divider()
        val config = model.savedConfig()
        namesInput = inputPage.field("Participants (one per line)", config.names.joinToString("\n"), multiline = true)
        startInput = inputPage.field("Start month", config.startMonth)
        endInput = inputPage.field("End month", config.endMonth)
        modeInput = inputPage.choices("Count", listOf("Messages", "Words"), config.countMode.ordinal)
        rulesInput = inputPage.choices("Counting rules", listOf("Python-compatible", "Calendar-correct"), config.rules.ordinal)
        inputPage.command("Analyze", android.R.drawable.ic_media_play, primary = true) {
            attempt { model.analyze(readConfig()) }
        }
        inputPage.command("Cancel", android.R.drawable.ic_menu_close_clear_cancel) { model.cancel() }.tag = "cancel"
        inputPage.divider()
    }

    private fun renderFiles(files: List<ChatFile>) {
        fileList.removeAllViews()
        files.forEach { file ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = file.name
                techText(14f, color = accent)
                setPadding(0, dp(10), dp(8), dp(10))
            }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            row.iconButton("Remove ${file.name}", android.R.drawable.ic_menu_delete) { model.removeFile(file) }
            fileList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun readConfig() = AnalysisConfig(
        namesInput.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() },
        startInput.text.toString().trim(), endInput.text.toString().trim(),
        CountMode.entries[modeInput.selectedItemPosition],
        CountingRules.entries[rulesInput.selectedItemPosition],
    )

    private fun buildGraphs(result: AnalysisResult?) {
        graphPage.removeAllViews()
        persistGraph = null
        currentCharts = emptyList()
        if (result == null) {
            graphPage.label("No analysis yet", 20f, true)
            return
        }
        val selector = graphPage.choices("Graph", GraphKind.entries.map { it.label }, model.preferences.getInt("graphKind", 0))
        val controls = column()
        graphPage.addView(controls)
        var previousKind: GraphKind? = null
        fun loadKind() {
            val kind = GraphKind.entries[selector.selectedItemPosition]
            if (previousKind == kind) return
            persistGraph?.invoke()
            previousKind = kind
            model.preferences.edit().putInt("graphKind", kind.ordinal).apply()
            buildGraphControls(controls, result, kind)
        }
        selector.onItemSelectedListener = selectionListener { loadKind() }
        loadKind()
    }

    private fun buildGraphControls(parent: LinearLayout, result: AnalysisResult, kind: GraphKind) {
        parent.removeAllViews()
        currentCharts = emptyList()
        currentGraphOptions = null
        val prefix = "graph.${kind.name}."
        fun stored(key: String, fallback: String) = model.preferences.getString(prefix + key, fallback)!!
        val dateInput = parent.field("Starting date", model.preferences.getString("graph.from", stored("from", "0.0.0"))!!)
        val endingInput = parent.field("Ending date", model.preferences.getString("graph.to", stored("to", "31.12.99"))!!)
        val nameChecks = mutableMapOf<String, CheckBox>()
        if (kind !in listOf(GraphKind.OVERVIEW, GraphKind.MONTHS)) {
            parent.label("Participants", 16f, true)
            val savedNames = runCatching {
                val array = JSONArray(stored("names", JSONArray(result.config.names).toString()))
                (0 until array.length()).map { array.getString(it) }.toSet()
            }.getOrDefault(result.config.names.toSet())
            result.config.names.forEachIndexed { index, name ->
                nameChecks[name] = parent.toggle(name, name in savedNames).apply { setTextColor(ChartViews.color(index)) }
            }
        }
        val settings = column().apply { visibility = View.GONE }
        val title = settings.field("Title", stored("title", ""))
        val xGrid = settings.toggle("X grid", model.preferences.getBoolean(prefix + "xGrid", true))
        val yGrid = settings.toggle("Y grid", model.preferences.getBoolean(prefix + "yGrid", true))
        val legend = settings.toggle("Legend", model.preferences.getBoolean(prefix + "legend", false))
        val width = settings.field("Figure width (inches, 100 DPI export)", stored("width", if (kind == GraphKind.OVERVIEW) "8" else "15"), numeric = true)
        val height = settings.field("Figure height (inches)", stored("height", "5"), numeric = true)
        val sparsity = settings.field("X tick spacing", stored("sparsity", if (kind == GraphKind.PERSON_DAYS) "15" else "1"), numeric = true)
        val ticks = settings.field("X tick labels (comma separated)", stored("ticks", ""))
        persistGraph = {
            model.preferences.edit().putString("graph.from", dateInput.text.toString())
                .putString("graph.to", endingInput.text.toString())
                .putString(prefix + "title", title.text.toString())
                .putString(prefix + "width", width.text.toString()).putString(prefix + "height", height.text.toString())
                .putString(prefix + "sparsity", sparsity.text.toString()).putString(prefix + "ticks", ticks.text.toString())
                .putBoolean(prefix + "xGrid", xGrid.isChecked).putBoolean(prefix + "yGrid", yGrid.isChecked)
                .putBoolean(prefix + "legend", legend.isChecked)
                .putString(prefix + "names", JSONArray(nameChecks.filterValues { it.isChecked }.keys.toList()).toString()).apply()
        }
        val output = column()
        fun update() {
            attempt {
                val options = GraphOptions(
                    if (nameChecks.isEmpty()) result.config.names else nameChecks.filterValues { it.isChecked }.keys.toList(),
                    dateInput.text.toString(), endingInput.text.toString(), title.text.toString(),
                    xGrid.isChecked, yGrid.isChecked, legend.isChecked,
                    width.text.toString().toIntOrNull() ?: error("Enter a numeric figure width"),
                    height.text.toString().toIntOrNull() ?: error("Enter a numeric figure height"),
                    sparsity.text.toString().toIntOrNull() ?: error("Enter numeric tick spacing"),
                    ticks.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                )
                val charts = Graphs.build(result, kind, options)
                output.removeAllViews()
                charts.forEachIndexed { index, chart ->
                    ChartViews.add(output, chart, options, kind == GraphKind.OVERVIEW && index % 2 == 0, expandable = kind != GraphKind.OVERVIEW)
                }
                currentCharts = charts
                currentGraphOptions = options
                persistGraph?.invoke()
            }
        }
        parent.command("Update graph", android.R.drawable.ic_menu_rotate, primary = true) { update() }
        parent.addView(output)
        parent.command("Chart settings", android.R.drawable.ic_menu_manage) {
            settings.visibility = if (settings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        parent.addView(settings)
        parent.command("Export PNG", android.R.drawable.ic_menu_save) {
            attempt {
                require(currentCharts.isNotEmpty()) { "Update the graph first" }
                model.pendingBitmap?.recycle()
                model.pendingBitmap = ChartViews.bitmap(this, currentCharts, currentGraphOptions!!)
                savePng.launch("poruke-${kind.name.lowercase()}.png")
            }
        }
        parent.command("Export graph CSV", android.R.drawable.ic_menu_save) {
            attempt {
                require(currentCharts.isNotEmpty()) { "Update the graph first" }
                model.exportRows = currentCharts.flatMap { listOf(listOf(it.title)) + it.rows() + listOf(emptyList()) }
                saveCsv.launch("poruke-${kind.name.lowercase()}.csv")
            }
        }
        update()
    }

    private fun buildReports(result: AnalysisResult?) {
        reportPage.removeAllViews()
        persistReports = null
        currentReports = emptyList()
        if (result == null) {
            reportPage.label("No analysis yet", 20f, true)
            return
        }
        fun stored(key: String, fallback: String) = model.preferences.getString("report.$key", fallback)!!
        val settings = column().apply { visibility = View.GONE }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        toolbar.command("Report settings", android.R.drawable.ic_menu_manage) {
            settings.visibility = if (settings.visibility == View.GONE) View.VISIBLE else View.GONE
        }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
        toolbar.iconButton("Export reports CSV", android.R.drawable.ic_menu_save) {
            model.exportRows = currentReports.flatMap { listOf(listOf(it.title)) + it.rows + listOf(emptyList()) }
            saveCsv.launch("poruke-reports.csv")
        }
        reportPage.addView(toolbar)
        reportPage.addView(settings)
        val sinceDay = settings.field("Daily report starting date", stored("day", "0.0.0"))
        val sinceMonth = settings.field("Monthly report starting month", stored("month", "0.0"))
        val file = settings.choices("Participant totals source", listOf("All files") + result.files.mapIndexed { index, counts -> "${index + 1}. ${counts.name}" }, model.preferences.getInt("report.file", 0))
        val extra = column().apply { visibility = View.GONE }
        settings.command("Summary settings", android.R.drawable.ic_menu_manage) {
            extra.visibility = if (extra.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        settings.addView(extra)
        val numerator = extra.choices("Ratio numerator", result.config.names, model.preferences.getInt("report.numerator", 0))
        val denominator = extra.choices("Ratio denominator", result.config.names, model.preferences.getInt("report.denominator", result.config.names.lastIndex))
        persistReports = {
            model.preferences.edit().putString("report.day", sinceDay.text.toString()).putString("report.month", sinceMonth.text.toString())
                .putInt("report.file", file.selectedItemPosition).putInt("report.numerator", numerator.selectedItemPosition)
                .putInt("report.denominator", denominator.selectedItemPosition).apply()
        }
        val output = column()
        fun update() {
            attempt {
                val options = ReportOptions(
                    sinceDay.text.toString(), sinceMonth.text.toString(), file.selectedItemPosition.takeIf { it > 0 }?.minus(1),
                    result.config.names[numerator.selectedItemPosition], result.config.names[denominator.selectedItemPosition],
                )
                val sections = Reports.build(result, options)
                currentReports = sections
                val unit = if (result.config.countMode == CountMode.MESSAGES) "messages" else "words"
                ReportViews(output, model.preferences, unit).render(sections)
                persistReports?.invoke()
            }
        }
        settings.command("Update reports", android.R.drawable.ic_menu_rotate, primary = true) { update() }
        reportPage.addView(output)
        update()
    }

    private fun writeCsv(uri: Uri, rows: List<List<String>>) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
                    stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        CSVPrinter(writer, CSVFormat.DEFAULT).use { csv ->
                            rows.forEach { row ->
                                csv.printRecord(row.map { value ->
                                    if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
                                })
                            }
                        }
                    }
                }
                toast("CSV saved")
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun enableInputs(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (index in 0 until view.childCount) enableInputs(view.getChildAt(index), enabled)
    }

    private fun selectionListener(action: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun attempt(action: () -> Unit) {
        try { action() } catch (error: Exception) { showError(error) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun showError(error: Exception) {
        val message = error.message ?: "Operation failed"
        status.text = message
        toast(message)
    }

    override fun onPause() {
        model.saveConfig(readConfig())
        persistGraph?.invoke()
        persistReports?.invoke()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", activeTab)
        super.onSaveInstanceState(outState)
    }
}