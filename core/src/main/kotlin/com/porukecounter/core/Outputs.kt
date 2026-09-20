package com.porukecounter.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

enum class GraphKind(val label: String) {
    OVERVIEW("Overview"),
    MONTHS("Monthly totals"),
    HOURS("Per person / hour"),
    TEN_MINUTES("Per person / 10 minutes"),
    PERSON_MONTHS("Per person / month"),
    WEEKDAYS("Per person / weekday"),
    PERSON_DAYS("Per person / day"),
}

data class GraphOptions(
    val names: List<String>,
    val startingDate: String = "0.0.0",
    val endingDate: String = "31.12.99",
    val title: String = "",
    val xGrid: Boolean = true,
    val yGrid: Boolean = true,
    val legend: Boolean = false,
    val xSize: Int = 15,
    val ySize: Int = 5,
    val sparsity: Int = 1,
    val xTicks: List<String> = emptyList(),
) {
    fun validate() {
        require(xSize in 4..30 && ySize in 3..20) { "Width must be 4-30; height must be 3-20." }
        require(sparsity in 1..10000) { "Tick spacing must be 1-10000." }
        require(graphDateValue(startingDate) <= graphDateValue(endingDate)) { "Graph start must not be after graph end." }
    }
}

data class ChartSeries(val name: String, val values: List<Long>, val colorIndex: Int? = null)
data class ChartData(val title: String, val labels: List<String>, val series: List<ChartSeries>) {
    fun rows(): List<List<String>> = listOf(listOf("Label") + series.map { it.name }) +
        labels.indices.map { index -> listOf(labels[index]) + series.map { it.values[index].toString() } }
}

private fun dateParts(value: String, size: Int): List<Int> {
    val parts = value.trim().trimEnd('.').split('.').map {
        it.toIntOrNull() ?: throw IllegalArgumentException("Use ${if (size == 3) "day.month.year" else "month.year"}.")
    }
    require(parts.size == size) { "Use ${if (size == 3) "day.month.year" else "month.year"}." }
    require(parts.last() in 0..9999 && parts[size - 2] in 0..12) { "Invalid date or month." }
    if (size == 3) require(parts[0] in 0..31) { "Day must be 0-31." }
    return parts.reversed()
}

fun compareDates(first: String, second: String): Int = compareParts(dateParts(first, 3), dateParts(second, 3))
fun compareMonths(first: String, second: String): Int = compareParts(dateParts(first, 2), dateParts(second, 2))

private fun graphDateValue(value: String): Int {
    val parts = dateParts(value, 3)
    val year = if (parts[0] in 0..99 && parts[1] > 0) parts[0] + 2000 else parts[0]
    return year * 10000 + parts[1] * 100 + parts[2]
}

private fun compareParts(first: List<Int>, second: List<Int>): Int {
    for (index in first.indices) {
        val comparison = first[index].compareTo(second[index])
        if (comparison != 0) return comparison
    }
    return 0
}

object Graphs {
    fun build(result: AnalysisResult, kind: GraphKind, options: GraphOptions): List<ChartData> {
        options.validate()
        require(options.names.all { it in result.people }) { "Unknown participant selected." }
        val unit = if (result.config.countMode == CountMode.MESSAGES) "Messages" else "Words"
        val interval = graphDateValue(options.startingDate)..graphDateValue(options.endingDate)
        val dayIndices = result.days.indices.filter { graphDateValue(result.days[it]) in interval }
        val monthDays = dayIndices.groupBy { result.days[it].substringAfter('.') }
        val months = result.months.filter { it in monthDays }
        val monthLabels = months.map(ReportDates::month)
        fun monthlyValues(values: LongArray): List<Long> = months.map { month -> monthDays.getValue(month).sumOf { values[it] } }
        fun timeValues(person: PersonCounts): LongArray {
            val values = LongArray(144)
            for ((day, buckets) in person.tenMinutesByDay) {
                val date = runCatching { graphDateValue(day) }.getOrNull() ?: continue
                if (date !in interval) continue
                for (index in buckets.indices) values[index] += buckets[index]
            }
            return values
        }
        fun chart(title: String, labels: List<String>, values: List<Long>) = ChartData(
            title, labels, listOf(ChartSeries(unit, values)),
        )
        fun personChart(title: String, labels: List<String>, values: (PersonCounts) -> List<Long>): List<ChartData> {
            require(options.names.isNotEmpty()) { "Select at least one participant." }
            return listOf(ChartData(options.title.ifBlank { title }, labels, options.names.map { name ->
                ChartSeries(name, values(result.people.getValue(name)), result.config.names.indexOf(name))
            }))
        }
        return when (kind) {
            GraphKind.OVERVIEW -> {
                val values = dayIndices.map { result.dayTotals[it] }
                var cumulative = 0L
                val latest = months.lastOrNull()
                val latestIndices = monthDays[latest].orEmpty()
                listOf(
                    chart(options.title.ifBlank { "$unit by day" }, dayIndices.map { result.days[it] }, values),
                    chart("$unit by month", monthLabels, monthlyValues(result.dayTotals)),
                    chart("Cumulative $unit", dayIndices.map { result.days[it] }, values.map { count ->
                        cumulative += count
                        cumulative
                    }),
                    chart("$unit in ${latest?.let(ReportDates::month) ?: "selected interval"}", latestIndices.map { result.days[it].substringBefore('.') }, latestIndices.map { result.dayTotals[it] }),
                )
            }
            GraphKind.MONTHS -> listOf(chart(options.title.ifBlank { "$unit by month" }, monthLabels, monthlyValues(result.dayTotals)))
            GraphKind.HOURS -> personChart("$unit per person per hour", (0..23).map { it.toString() }) { person ->
                val values = timeValues(person)
                (0..23).map { hour -> (hour * 6 until hour * 6 + 6).sumOf { values[it] } }
            }
            GraphKind.TEN_MINUTES -> personChart("$unit per person per 10 minutes", (0..143).map {
                String.format(Locale.ROOT, "%02d:%02d", it / 6, it % 6 * if (result.config.rules == CountingRules.CALENDAR_CORRECT) 10 else 1)
            }) { timeValues(it).toList() }
            GraphKind.PERSON_MONTHS -> personChart("$unit per person per month", monthLabels) { monthlyValues(it.days) }
            GraphKind.WEEKDAYS -> personChart("$unit per person per weekday", (0..6).map { it.toString() }) { person ->
                val values = LongArray(7)
                for (index in dayIndices) {
                    if (person.days[index] == 0L) continue
                    val parts = dateParts(result.days[index], 3)
                    val year = if (result.config.rules == CountingRules.CALENDAR_CORRECT && parts[0] < 100) parts[0] + 2000 else parts[0]
                    val weekday = LocalDate.of(year, parts[1], parts[2]).dayOfWeek.value - 1
                    values[weekday] += person.days[index]
                }
                values.toList()
            }
            GraphKind.PERSON_DAYS -> personChart("$unit per person per day", dayIndices.map { result.days[it] }) { person -> dayIndices.map { person.days[it] } }
        }
    }
}

data class ReportOptions(
    val startingDate: String = "0.0.0",
    val startingMonth: String = "0.0",
    val totalsFile: Int? = null,
    val numerator: String? = null,
    val denominator: String? = null,
)

object ReportDates {
    fun fullYear(year: Int): Int = if (year in 0..99) year + 2000 else year

    fun calendarMonth(value: String): YearMonth {
        val month = MonthKey.parse(value)
        return YearMonth.of(fullYear(month.year), month.month)
    }

    fun month(value: String): String {
        val date = calendarMonth(value)
        return "${date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${date.year}"
    }

    fun day(value: String): String {
        val parts = dateParts(value, 3)
        return "${parts[2]}.${parts[1]}.${fullYear(parts[0])}."
    }
}

data class ReportMonth(val key: String, val count: Long) {
    val date: YearMonth get() = ReportDates.calendarMonth(key)
    val label: String get() = ReportDates.month(key)
}

data class ReportDay(val key: String, val day: Int, val count: Long?) {
    val label: String get() = ReportDates.day(key)
}

data class ReportCalendarMonth(val key: String, val days: List<ReportDay>) {
    val date: YearMonth get() = ReportDates.calendarMonth(key)
    val label: String get() = ReportDates.month(key)
    val firstWeekday: Int get() = date.atDay(1).dayOfWeek.value - 1
    val total: Long get() = days.sumOf { it.count ?: 0L }
    val busiest: ReportDay? get() = days.filter { (it.count ?: 0L) > 0 }.maxByOrNull { it.count!! }
}

enum class ReportLayout { TABLE, YEAR_TABLE, CALENDAR, LATEST_CALENDAR }

data class ReportSection(
    val title: String,
    val rows: List<List<String>>,
    val layout: ReportLayout = ReportLayout.TABLE,
    val months: List<ReportMonth> = emptyList(),
    val calendarMonths: List<ReportCalendarMonth> = emptyList(),
)

object Reports {
    fun build(result: AnalysisResult, options: ReportOptions): List<ReportSection> {
        compareDates(options.startingDate, options.startingDate)
        compareMonths(options.startingMonth, options.startingMonth)
        val totals = options.totalsFile?.let { result.files[it].totals } ?: result.people.mapValues { it.value.total }
        val total = totals.values.sum()
        val peopleRows = listOf(listOf("Participant", "Count", "Percent")) + totals.map { (name, count) ->
            listOf(name, "$count", String.format(Locale.ROOT, "%.2f%%", if (total == 0L) 0.0 else count * 100.0 / total))
        } + listOf(listOf("Total", "$total", if (total == 0L) "0.00%" else "100.00%"))

        val monthIndices = result.months.indices.filter { compareMonths(result.months[it], options.startingMonth) >= 0 }
        val bestMonth = monthIndices.filter { result.monthTotals[it] > 0 }.maxByOrNull { result.monthTotals[it] }
        val monthCounts = monthIndices.map { ReportMonth(result.months[it], result.monthTotals[it]) }
        val monthRows = listOf(listOf("Month", "Count")) + monthIndices.map {
            listOf(ReportDates.month(result.months[it]), "${result.monthTotals[it]}")
        } + listOf(listOf("Busiest month", bestMonth?.let { "${ReportDates.month(result.months[it])}: ${result.monthTotals[it]}" } ?: "None"))

        val dayRows = mutableListOf(listOf("Day", "Count"))
        val calendarMonths = mutableListOf<ReportCalendarMonth>()
        val dayCounts = result.days.indices.associate { result.days[it] to result.dayTotals[it] }
        for (month in result.months) {
            val trackedDays = (1..31).map { day ->
                val key = "$day.$month"
                ReportDay(key, day, if (compareDates(key, options.startingDate) >= 0) dayCounts[key] ?: 0L else null)
            }
            val nonzeroDays = trackedDays.filter { (it.count ?: 0L) > 0 }
            dayRows += nonzeroDays.map { listOf(it.label, "${it.count}") }
            nonzeroDays.maxByOrNull { it.count!! }?.let {
                dayRows += listOf("Busiest day", "${it.label}: ${it.count}")
            }
            val calendarDays = trackedDays.take(ReportDates.calendarMonth(month).lengthOfMonth())
            if (calendarDays.any { it.count != null }) {
                calendarMonths += ReportCalendarMonth(month, calendarDays)
            }
        }
        val firstCandidate = if (result.config.rules == CountingRules.PYTHON_COMPATIBLE) 1 else 0
        val latestIndex = (result.months.lastIndex downTo firstCandidate).firstOrNull { result.monthTotals[it] != 0L } ?: result.months.lastIndex
        val latest = result.months[latestIndex]
        val latestRows = listOf(listOf("Month", ReportDates.month(latest)), listOf("Count", "${result.monthTotals[latestIndex]}")) +
            result.days.indices.filter { result.days[it].substringAfter('.') == latest && result.dayTotals[it] > 0 }.map {
            listOf(ReportDates.day(result.days[it]), "${result.dayTotals[it]}")
            }
        val latestCalendar = ReportCalendarMonth(latest, (1..ReportDates.calendarMonth(latest).lengthOfMonth()).map { day ->
            val key = "$day.$latest"
            ReportDay(key, day, dayCounts[key] ?: 0L)
        })
        val numerator = options.numerator ?: result.config.names.first()
        val denominator = options.denominator ?: result.config.names.last()
        require(numerator in totals && denominator in totals) { "Select participants for the ratio." }
        val denominatorCount = totals.getValue(denominator)
        val ratio = if (denominatorCount == 0L) "Undefined (zero denominator)" else
            String.format(Locale.ROOT, "%.2f", totals.getValue(numerator).toDouble() / denominatorCount)
        val summary = listOf(
            listOf("Configured-range count", "${result.rangeTotal}"),
            listOf("Sum of months", "${result.monthTotals.sum()}"),
            listOf("All-file participant total", "${result.total}"),
            listOf("Hourly tracker total", "${result.hourlyTotal}"),
            listOf("$numerator / $denominator", ratio),
            listOf("Pair total minus range total", "${totals.getValue(numerator) + totals.getValue(denominator) - result.rangeTotal}"),
            listOf("Parsed message headers", "${result.stats.headers}"),
            listOf("Unmatched sender headers", "${result.stats.unmatchedSenders}"),
            listOf("Skipped non-message lines", "${result.stats.skippedLines}"),
            listOf("Invalid headers", "${result.stats.invalidHeaders}"),
        )
        return listOf(
            ReportSection("Participant totals", peopleRows),
            ReportSection("Monthly counts", monthRows, ReportLayout.YEAR_TABLE, months = monthCounts),
            ReportSection("Daily counts", dayRows, ReportLayout.CALENDAR, calendarMonths = calendarMonths),
            ReportSection(
                if (result.config.rules == CountingRules.PYTHON_COMPATIBLE) "Latest month (Python)" else "Latest active month",
                latestRows, ReportLayout.LATEST_CALENDAR, calendarMonths = listOf(latestCalendar),
            ),
            ReportSection("Summary", summary),
        )
    }
}