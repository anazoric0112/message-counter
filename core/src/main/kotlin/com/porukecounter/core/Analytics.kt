package com.porukecounter.core

import java.time.LocalDate

data class MonthKey(val month: Int, val year: Int) : Comparable<MonthKey> {
    init {
        require(month in 1..12 && year in 0..9999) { "Use a month such as 6.26 or 6.2026." }
    }

    override fun compareTo(other: MonthKey): Int = compareValuesBy(this, other, { it.year }, { it.month })
    override fun toString(): String = "$month.$year"
    fun next(): MonthKey = if (month == 12) MonthKey(1, year + 1) else MonthKey(month + 1, year)

    companion object {
        fun parse(value: String): MonthKey {
            val parts = value.trim().trimEnd('.').split('.')
            require(parts.size == 2) { "Use a month such as 6.26." }
            return MonthKey(parts[0].toInt(), parts[1].toInt())
        }
    }
}

enum class CountMode { MESSAGES, WORDS }

data class AnalysisConfig(
    val names: List<String> = emptyList(),
    val startMonth: String = "0.0",
    val endMonth: String = "12.99",
    val countMode: CountMode = CountMode.MESSAGES,
) {
    internal val hasOpenStart: Boolean get() = startMonth.trim().trimEnd('.') == "0.0"
    internal fun firstMonth(): MonthKey = if (hasOpenStart) {
        MonthKey(1, if (MonthKey.parse(endMonth).year < 100) 0 else 2000)
    } else MonthKey.parse(startMonth)

    fun validate() {
        require(names.isNotEmpty() && names.all { it.isNotBlank() }) { "Enter at least one participant." }
        require(names.distinct().size == names.size) { "Participant names must be unique." }
        val start = firstMonth()
        val end = MonthKey.parse(endMonth)
        require(start <= end) { "Start month must not be after end month." }
        require((end.year - start.year) * 12 + end.month - start.month < 1200) { "Select a range of at most 100 years." }
    }
}

data class PersonCounts(
    var total: Long,
    val days: LongArray,
    val months: LongArray,
    val weekdays: LongArray = LongArray(7),
    val hours: LongArray = LongArray(24),
    val tenMinutes: LongArray = LongArray(144),
    val tenMinutesByDay: MutableMap<String, LongArray> = linkedMapOf(),
)

data class FileCounts(val name: String, val totals: Map<String, Long>)

data class ImportStats(
    val lines: Long,
    val headers: Long,
    val unmatchedSenders: Long,
    val skippedLines: Long,
    val invalidHeaders: Long,
)

data class AnalysisResult(
    val config: AnalysisConfig,
    val months: List<String>,
    val days: List<String>,
    val dayTotals: LongArray,
    val monthTotals: LongArray,
    val people: Map<String, PersonCounts>,
    val files: List<FileCounts>,
    val stats: ImportStats,
) {
    val total: Long get() = people.values.sumOf { it.total }
    val rangeTotal: Long get() = monthTotals.sum()
    val hourlyTotal: Long get() = people.values.sumOf { it.hours.sum() }
}

class ChatAnalyzer(val config: AnalysisConfig) {
    private val headerPattern = Regex("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})\\.?, (\\d{1,2}):(\\d{1,2}) - ([^:]+): ?(.*)$")
    private val datedLine = Regex("^\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}\\.?, \\d{1,2}:\\d{2} - ")
    private val whitespace = Regex("\\s+")
    private val months: List<String>
    private val days: List<String>
    private val dayTotals: LongArray
    private val monthTotals: LongArray
    private val people: Map<String, PersonCounts>
    private val files = mutableListOf<FileCounts>()
    private val monthIndices: Map<String, Int>
    private val dayIndices: Map<String, Int>
    private var previousName: String? = null
    private var lineCount = 0L
    private var headerCount = 0L
    private var unmatchedSenders = 0L
    private var skippedLines = 0L
    private var invalidHeaders = 0L
    private var firstIncludedMonth = Int.MAX_VALUE
    private var lastIncludedMonth = -1

    init {
        config.validate()
        val end = MonthKey.parse(config.endMonth)
        months = generateSequence(config.firstMonth()) { current ->
            if (current < end) current.next() else null
        }.map { it.toString() }.toList()
        days = months.flatMap { month ->
            val key = MonthKey.parse(month)
            val length = LocalDate.of(if (key.year < 100) 2000 + key.year else key.year, key.month, 1).lengthOfMonth()
            (1..length).map { day -> "$day.$month" }
        }
        monthIndices = months.withIndex().associate { it.value to it.index }
        dayIndices = days.withIndex().associate { it.value to it.index }
        dayTotals = LongArray(days.size)
        monthTotals = LongArray(months.size)
        people = config.names.associateWith { PersonCounts(0, LongArray(days.size), LongArray(months.size)) }
    }

    fun consume(filename: String, lines: Sequence<String>) {
        val fileTotals = config.names.associateWith { 0L }.toMutableMap()
        var pending: MatchResult? = null
        val body = StringBuilder()
        fun flush() {
            val match = pending ?: return
            pending = null
            val sender = match.groupValues[6]
            val matchedName = config.names.lastOrNull { sender.startsWith(it) }
            if (matchedName == null) unmatchedSenders++
            val name = matchedName ?: previousName ?: return
            previousName = name
            if (config.countMode == CountMode.WORDS && body.contains("<Media omitted>")) return
            val rawYear = match.groupValues[3].toInt()
            val year = if (rawYear < 100) 2000 + rawYear else rawYear
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[1].toInt()
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull()
            val hour = match.groupValues[4].toInt()
            val minute = match.groupValues[5].toInt()
            if (date == null || hour !in 0..23 || minute !in 0..59) {
                invalidHeaders++
                return
            }
            val configuredYear = if (config.firstMonth().year < 100) year - 2000 else year
            val monthIndex = monthIndices["$month.$configuredYear"] ?: return
            val dayIndex = dayIndices["$day.$month.$configuredYear"] ?: return
            val increment = if (config.countMode == CountMode.MESSAGES) 1L else {
                val content = if (match.value.contains(name)) body.substring(match.groups[7]!!.range.first) else body.toString()
                countWords(content)
            }
            val totalIncrement = if (config.countMode == CountMode.MESSAGES && !match.value.contains(name)) 0L else increment
            firstIncludedMonth = minOf(firstIncludedMonth, monthIndex)
            lastIncludedMonth = maxOf(lastIncludedMonth, monthIndex)
            val person = people.getValue(name)
            person.total += totalIncrement
            person.days[dayIndex] += increment
            person.months[monthIndex] += increment
            person.weekdays[LocalDate.of(rawYear, month, day).dayOfWeek.value - 1] += increment
            person.hours[hour] += increment
            person.tenMinutes[hour * 6 + minute / 10] += increment
            if (increment != 0L) {
                person.tenMinutesByDay.getOrPut(days[dayIndex]) { LongArray(144) }[hour * 6 + minute / 10] += increment
            }
            dayTotals[dayIndex] += increment
            monthTotals[monthIndex] += increment
            fileTotals[name] = fileTotals.getValue(name) + totalIncrement
        }
        for (rawLine in lines) {
            lineCount++
            val line = rawLine.trimStart('\uFEFF', '\u200E', '\u200F')
            val match = headerPattern.find(line)
            if (match != null) {
                flush()
                headerCount++
                pending = match
                body.clear()
                body.append(line)
            } else if (datedLine.containsMatchIn(line)) {
                flush()
                skippedLines++
            } else if (pending != null) {
                body.append('\n').append(line)
            } else skippedLines++
        }
        flush()
        files += FileCounts(filename, fileTotals.toMap())
    }

    fun result(): AnalysisResult {
        val firstMonthIndex = if (config.hasOpenStart && lastIncludedMonth >= 0) firstIncludedMonth else 0
        val lastMonthIndex = if (config.hasOpenStart && MonthKey.parse(config.endMonth) == MonthKey(12, 99))
            maxOf(firstMonthIndex, lastIncludedMonth) else months.lastIndex
        val firstDayIndex = dayIndices.getValue("1.${months[firstMonthIndex]}")
        val endDayIndex = if (lastMonthIndex == months.lastIndex) days.size else
            dayIndices.getValue("1.${months[lastMonthIndex + 1]}")
        val visiblePeople = if (firstMonthIndex == 0 && lastMonthIndex == months.lastIndex) people else people.mapValues { (_, person) ->
            person.copy(
                days = person.days.copyOfRange(firstDayIndex, endDayIndex),
                months = person.months.copyOfRange(firstMonthIndex, lastMonthIndex + 1),
            )
        }
        return AnalysisResult(
            config, months.subList(firstMonthIndex, lastMonthIndex + 1), days.subList(firstDayIndex, endDayIndex),
            dayTotals.copyOfRange(firstDayIndex, endDayIndex), monthTotals.copyOfRange(firstMonthIndex, lastMonthIndex + 1),
            visiblePeople, files.toList(), ImportStats(lineCount, headerCount, unmatchedSenders, skippedLines, invalidHeaders),
        )
    }

    private fun countWords(text: String): Long = if (text.isBlank()) 0L else text.trim().split(whitespace).size.toLong()
}