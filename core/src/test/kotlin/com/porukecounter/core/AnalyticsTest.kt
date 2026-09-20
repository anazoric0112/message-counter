package com.porukecounter.core

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalyticsTest {
    private val fixture = javaClass.getResourceAsStream("/python-compatibility.json")!!.bufferedReader().use {
        JsonParser.parseReader(it).asJsonObject
    }

    @Test
    fun messagesMatchPythonFixture() = verifyFixture(CountMode.MESSAGES, "messages")

    @Test
    fun wordsMatchPythonFixture() = verifyFixture(CountMode.WORDS, "words")

    private fun verifyFixture(mode: CountMode, expectedKey: String) {
        val names = fixture.getAsJsonArray("names").map { it.asString }
        val analyzer = ChatAnalyzer(AnalysisConfig(
            names, fixture["startMonth"].asString, fixture["endMonth"].asString, mode,
        ))
        analyzer.consume("fixture.txt", fixture["input"].asString.lineSequence())
        val result = analyzer.result()
        val expected = fixture.getAsJsonObject(expectedKey)
        assertEquals(fixture["daySlots"].asInt, result.days.size)
        assertContentEquals(expected.getAsJsonArray("months").map { it.asLong }.toLongArray(), result.monthTotals)
        assertEquals(expected["hourTotal"].asLong, result.hourlyTotal)
        for (name in names) {
            assertEquals(expected.getAsJsonObject("totals")[name].asLong, result.people.getValue(name).total)
            assertContentEquals(
                expected.getAsJsonObject("weekdays").getAsJsonArray(name).map { it.asLong }.toLongArray(),
                result.people.getValue(name).weekdays,
            )
        }
    }

    @Test
    fun importsAccumulateButKeepSeparateTotals() {
        val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex"), "2.24", "2.24"))
        analyzer.consume("first.txt", sequenceOf("1.2.24., 08:00 - Alex: first"))
        analyzer.consume("second.txt", sequenceOf("1.2.24., 09:00 - Alex: second"))
        val result = analyzer.result()
        assertEquals(2L, result.rangeTotal)
        assertEquals(2, result.files.size)
        assertEquals(1L, result.files.first().totals.getValue("Alex"))
    }

    @Test
    fun datedTimeBucketsPreserveCountsAcrossFilesAndCountingModes() {
        for (rules in CountingRules.entries) for (mode in CountMode.entries) {
            val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex"), "2.24", "2.24", mode, rules))
            analyzer.consume("first.txt", sequenceOf(
                "31.1.24., 07:00 - Alex: outside range",
                "1.2.24., 08:09 - Alex: two words",
            ))
            analyzer.consume("second.txt", sequenceOf(
                "1.2.24., 08:19 - Alex: three whole words",
                "2.2.24., 09:59 - Alex: another pair",
            ))
            val result = analyzer.result()
            val person = result.people.getValue("Alex")
            assertEquals(if (mode == CountMode.MESSAGES) 3L else 7L, result.rangeTotal)
            assertEquals(rules == CountingRules.PYTHON_COMPATIBLE, person.tenMinutesByDay.containsKey("31.1.24"))
            assertEquals(if (mode == CountMode.MESSAGES) 1L else 2L, person.tenMinutesByDay.getValue("1.2.24")[48])
            assertEquals(if (mode == CountMode.MESSAGES) 1L else 3L, person.tenMinutesByDay.getValue("1.2.24")[49])
            assertEquals(if (mode == CountMode.MESSAGES) 1L else 2L, person.tenMinutesByDay.getValue("2.2.24")[59])
            assertContentEquals(person.tenMinutes, LongArray(144) { bucket -> person.tenMinutesByDay.values.sumOf { it[bucket] } })
            assertContentEquals(person.hours, LongArray(24) { hour ->
                person.tenMinutesByDay.values.sumOf { buckets -> (hour * 6 until hour * 6 + 6).sumOf { buckets[it] } }
            })
            val hourly = Graphs.build(result, GraphKind.HOURS, GraphOptions(result.config.names)).single()
            assertEquals(person.hours.toList(), hourly.series.single().values)
            val outside = Graphs.build(result, GraphKind.HOURS, GraphOptions(result.config.names, "31.1.24", "31.1.24")).single()
            val outsideCount = if (rules == CountingRules.CALENDAR_CORRECT) 0L else if (mode == CountMode.MESSAGES) 1L else 2L
            assertEquals(outsideCount, outside.series.single().values.sum())
        }
    }

    @Test
    fun defaultImportBoundsAcceptAllChatMonthsWithoutEmptyCentury() {
        for (rules in CountingRules.entries) for (mode in CountMode.entries) {
            val config = AnalysisConfig(names = listOf("Alex"), countMode = mode, rules = rules)
            assertEquals("0.0", config.startMonth)
            assertEquals("12.99", config.endMonth)
            config.validate()
            val analyzer = ChatAnalyzer(config)
            analyzer.consume("newer.txt", sequenceOf("1.3.24., 09:00 - Alex: two words"))
            analyzer.consume("older.txt", sequenceOf("31.12.23., 08:00 - Alex: three whole words"))
            val result = analyzer.result()
            val expected = if (mode == CountMode.MESSAGES) 2L else 5L
            assertEquals(listOf("12.23", "1.24", "2.24", "3.24"), result.months)
            assertEquals("1.12.23", result.days.first())
            assertEquals("31.3.24", result.days.last())
            assertEquals(expected, result.rangeTotal)
            assertEquals(expected, result.total)
            val person = result.people.getValue("Alex")
            assertContentEquals(result.dayTotals, person.days)
            assertContentEquals(result.monthTotals, person.months)
            assertEquals(result.days.size, person.days.size)
            assertEquals(result.months.size, person.months.size)
            assertEquals(2, result.files.size)
            val sections = Reports.build(result, ReportOptions())
            assertEquals("March 2024", sections[3].calendarMonths.single().label)
            for (kind in GraphKind.entries) {
                val charts = Graphs.build(result, kind, GraphOptions(config.names))
                assertEquals(expected, charts.first().series.sumOf { it.values.sum() }, "$rules / $mode / $kind")
            }
        }
    }

    @Test
    fun openImportStartHandlesEmptyInputsAndExplicitEndMonths() {
        for (rules in CountingRules.entries) {
            val empty = ChatAnalyzer(AnalysisConfig(names = listOf("Alex"), rules = rules)).result()
            assertEquals(listOf("1.0"), empty.months)
            assertEquals(0L, empty.rangeTotal)
            assertEquals("January 2000", Reports.build(empty, ReportOptions())[3].calendarMonths.single().label)
            val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex"), "0.0", "3.24", rules = rules))
            analyzer.consume("bounded.txt", sequenceOf(
                "1.2.24., 08:00 - Alex: included",
                "1.4.24., 08:00 - Alex: excluded",
            ))
            val result = analyzer.result()
            assertEquals(listOf("2.24", "3.24"), result.months)
            assertEquals(1L, result.rangeTotal)
            assertEquals(if (rules == CountingRules.PYTHON_COMPATIBLE) 2L else 1L, result.total)
        }
        for (month in listOf("0.24", "13.24", "-1.24")) {
            assertFailsWith<IllegalArgumentException> { AnalysisConfig(startMonth = month).validate() }
        }
        assertFailsWith<IllegalArgumentException> { AnalysisConfig(endMonth = "0.0").validate() }
    }

    @Test
    fun openImportStartSupportsFullYearsAndTheYear2000() {
        val analyzer = ChatAnalyzer(AnalysisConfig(names = listOf("Alex"), rules = CountingRules.CALENDAR_CORRECT))
        analyzer.consume("full-years.txt", sequenceOf(
            "1.1.2000, 08:00 - Alex: included",
            "31.12.2099, 09:00 - Alex: included",
            "1.1.2100, 10:00 - Alex: excluded",
        ))
        val result = analyzer.result()
        assertEquals(1200, result.months.size)
        assertEquals(2L, result.rangeTotal)
        for (kind in GraphKind.entries) {
            val chart = Graphs.build(result, kind, GraphOptions(result.config.names, "1.1.2000", "1.1.2000")).first()
            assertEquals(1L, chart.series.sumOf { it.values.sum() })
        }
        val fullYearConfig = ChatAnalyzer(AnalysisConfig(listOf("Alex"), "0.0", "3.2024", rules = CountingRules.CALENDAR_CORRECT))
        fullYearConfig.consume("bounded.txt", sequenceOf("1.2.2024, 08:00 - Alex: included"))
        assertEquals(listOf("2.2024", "3.2024"), fullYearConfig.result().months)
    }

    @Test
    fun rejectsReversedRange() {
        assertFailsWith<IllegalArgumentException> {
            ChatAnalyzer(AnalysisConfig(listOf("Alex"), "3.24", "2.24"))
        }
    }

    private fun sample(): AnalysisResult {
        val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex", "Blair"), "2.24", "3.24"))
        analyzer.consume("sample.txt", sequenceOf(
            "1.2.24., 08:09 - Alex: hello",
            "2.2.24., 09:59 - Blair: hello",
            "1.3.24., 10:00 - Alex: hello",
        ))
        return analyzer.result()
    }

    @Test
    fun participantSeriesKeepColorsWhenSelectionsChange() {
        val result = sample()
        assertEquals(false, GraphOptions(result.config.names).legend)
        val selections = listOf(
            listOf("Alex", "Blair") to listOf(0, 1),
            listOf("Blair") to listOf(1),
            listOf("Blair", "Alex") to listOf(1, 0),
        )
        for (kind in GraphKind.entries.filter { it !in listOf(GraphKind.OVERVIEW, GraphKind.MONTHS) }) {
            for ((names, colors) in selections) {
                val chart = Graphs.build(result, kind, GraphOptions(names)).single()
                assertEquals(names, chart.series.map { it.name }, "$kind participants")
                assertEquals(colors, chart.series.map { it.colorIndex }, "$kind participant colors")
            }
        }
    }

    @Test
    fun allSevenGraphModesHaveAlignedSeries() {
        val result = sample()
        for (kind in GraphKind.entries) {
            val charts = Graphs.build(result, kind, GraphOptions(result.config.names))
            assertEquals(if (kind == GraphKind.OVERVIEW) 4 else 1, charts.size)
            charts.forEach { chart -> chart.series.forEach { assertEquals(chart.labels.size, it.values.size) } }
        }
    }

    @Test
    fun dailyGraphHasWorkingDefaultsAndInclusiveBoundaries() {
        val result = sample()
        val defaults = Graphs.build(result, GraphKind.PERSON_DAYS, GraphOptions(result.config.names)).single()
        assertEquals(62, defaults.labels.size)
        val filtered = Graphs.build(result, GraphKind.PERSON_DAYS, GraphOptions(listOf("Blair"), "2.2.24", "2.2.24")).single()
        assertEquals(listOf("2.2.24"), filtered.labels)
        assertEquals(listOf(1L), filtered.series.single().values)
    }

    @Test
    fun overviewPanelsShareInclusiveDateRange() {
        val result = sample()
        val charts = Graphs.build(result, GraphKind.OVERVIEW, GraphOptions(result.config.names, "2.2.24", "28.2.24"))
        assertEquals("2.2.24", charts[0].labels.first())
        assertEquals("28.2.24", charts[0].labels.last())
        assertEquals(1L, charts[2].series.single().values.last())
        assertEquals(listOf("February 2024"), charts[1].labels)
        assertEquals(listOf(1L), charts[1].series.single().values)
        assertEquals("Messages in February 2024", charts[3].title)
        assertEquals((2..28).map { it.toString() }, charts[3].labels)
        assertEquals(1L, charts[3].series.single().values.sum())
    }

    @Test
    fun everyGraphFiltersInclusiveDaysInBothCountingModes() {
        for (rules in CountingRules.entries) for (mode in CountMode.entries) {
            val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex", "Blair"), "12.23", "3.24", mode, rules))
            analyzer.consume("dates.txt", sequenceOf(
                "31.12.23., 07:00 - Alex: before start",
                "1.1.24., 08:09 - Alex: first day",
                "15.1.24., 09:19 - Blair: middle three words",
                "1.2.24., 10:59 - Alex: final day",
                "2.2.24., 11:00 - Blair: after end",
                "1.3.24., 12:00 - Alex: after month",
            ))
            val result = analyzer.result()
            val reports = Reports.build(result, ReportOptions())
            val options = GraphOptions(result.config.names, "1.1.24", "1.2.24")
            val expected = if (mode == CountMode.MESSAGES) 3L else 7L
            for (kind in GraphKind.entries) {
                val charts = Graphs.build(result, kind, options)
                assertEquals(expected, charts.first().series.sumOf { it.values.sum() }, "$rules / $mode / $kind")
                charts.forEach { chart -> chart.series.forEach { assertEquals(chart.labels.size, it.values.size) } }
                if (kind == GraphKind.OVERVIEW) {
                    assertEquals(expected, charts[1].series.single().values.sum())
                    assertEquals(expected, charts[2].series.single().values.last())
                    assertEquals(listOf("1"), charts[3].labels)
                    assertEquals(if (mode == CountMode.MESSAGES) 1L else 2L, charts[3].series.single().values.single())
                }
            }
            val monthly = Graphs.build(result, GraphKind.MONTHS, options).single()
            assertEquals(listOf("January 2024", "February 2024"), monthly.labels)
            assertEquals(if (mode == CountMode.MESSAGES) listOf(2L, 1L) else listOf(5L, 2L), monthly.series.single().values)
            val weekdays = Graphs.build(result, GraphKind.WEEKDAYS, options).single()
            val year = if (rules == CountingRules.CALENDAR_CORRECT) 2024 else 24
            val expectedWeekdays = LongArray(7)
            val alexIncrement = if (mode == CountMode.MESSAGES) 1L else 2L
            expectedWeekdays[java.time.LocalDate.of(year, 1, 1).dayOfWeek.value - 1] += alexIncrement
            expectedWeekdays[java.time.LocalDate.of(year, 2, 1).dayOfWeek.value - 1] += alexIncrement
            assertEquals(expectedWeekdays.toList(), weekdays.series.first().values)
            val selectedPerson = Graphs.build(result, GraphKind.HOURS, options.copy(names = listOf("Blair"))).single()
            assertEquals(listOf("Blair"), selectedPerson.series.map { it.name })
            assertEquals(if (mode == CountMode.MESSAGES) 1L else 3L, selectedPerson.series.single().values[9])
            assertEquals(if (mode == CountMode.MESSAGES) 6L else 13L, result.rangeTotal)
            assertEquals(reports, Reports.build(result, ReportOptions()))
        }
    }

    @Test
    fun graphDatesAcceptShortAndFullYearsAndRejectReversedIntervals() {
        val result = sample()
        for (kind in GraphKind.entries) {
            val short = Graphs.build(result, kind, GraphOptions(result.config.names, "2.2.24", "2.2.24"))
            val full = Graphs.build(result, kind, GraphOptions(result.config.names, "02.02.2024.", "02.02.2024."))
            assertEquals(short, full)
            assertFailsWith<IllegalArgumentException> {
                Graphs.build(result, kind, GraphOptions(result.config.names, "2.2.2024", "1.2.24"))
            }
        }
        val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex"), "2.2024", "3.2024", rules = CountingRules.CALENDAR_CORRECT))
        analyzer.consume("full-years.txt", sequenceOf("29.2.2024, 08:09 - Alex: leap day"))
        for (kind in GraphKind.entries) {
            val charts = Graphs.build(analyzer.result(), kind, GraphOptions(listOf("Alex"), "29.2.24", "29.2.2024"))
            assertEquals(1L, charts.first().series.sumOf { it.values.sum() })
            val defaults = Graphs.build(analyzer.result(), kind, GraphOptions(listOf("Alex")))
            assertEquals(1L, defaults.first().series.sumOf { it.values.sum() })
        }
    }

    @Test
    fun graphMonthsUseFullNamesAndYearsWithoutChangingCounts() {
        for ((year, rules) in listOf(26 to CountingRules.PYTHON_COMPATIBLE, 26 to CountingRules.CALENDAR_CORRECT, 2026 to CountingRules.CALENDAR_CORRECT)) {
            for (mode in CountMode.entries) {
                val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex"), "8.$year", "9.$year", mode, rules))
                analyzer.consume("months.txt", sequenceOf(
                    "31.8.$year., 08:00 - Alex: two words",
                    "1.9.$year., 09:00 - Alex: three whole words",
                ))
                val result = analyzer.result()
                val options = GraphOptions(result.config.names)
                val expectedValues = if (mode == CountMode.MESSAGES) listOf(1L, 1L) else listOf(2L, 3L)
                for (kind in listOf(GraphKind.MONTHS, GraphKind.PERSON_MONTHS, GraphKind.OVERVIEW)) {
                    val charts = Graphs.build(result, kind, options)
                    val monthly = if (kind == GraphKind.OVERVIEW) charts[1] else charts.single()
                    assertEquals(listOf("August 2026", "September 2026"), monthly.labels)
                    assertEquals(expectedValues, monthly.series.single().values)
                    assertEquals(listOf("September 2026", expectedValues.last().toString()), monthly.rows().last())
                    if (kind == GraphKind.OVERVIEW) {
                        assertEquals("${if (mode == CountMode.MESSAGES) "Messages" else "Words"} in September 2026", charts[3].title)
                        assertEquals(expectedValues.last(), charts[3].series.single().values.sum())
                    }
                }
                assertEquals("My comparison", Graphs.build(result, GraphKind.MONTHS, options.copy(title = "My comparison")).single().title)
                assertEquals(listOf("8.$year", "9.$year"), result.months)
            }
        }
    }

    @Test
    fun emptyGraphRangesProduceEmptyDatesOrZeroBuckets() {
        val result = sample()
        for (kind in GraphKind.entries) {
            val charts = Graphs.build(result, kind, GraphOptions(result.config.names, "1.5.24", "31.5.24"))
            charts.forEach { chart ->
                chart.series.forEach { series ->
                    assertEquals(chart.labels.size, series.values.size)
                    assertEquals(0L, series.values.sum())
                }
                if (kind !in listOf(GraphKind.HOURS, GraphKind.TEN_MINUTES, GraphKind.WEEKDAYS)) {
                    assertEquals(emptyList(), chart.labels)
                }
            }
        }
    }

    @Test
    fun reportsFilterDatesAndHandleZeroDenominator() {
        val result = sample()
        val sections = Reports.build(result, ReportOptions("2.2.24", "3.24"))
        assertEquals(listOf("March 2024", "1"), sections[1].rows[1])
        assertEquals(listOf("2.2.2024.", "1"), sections[2].rows[1])
        val empty = ChatAnalyzer(AnalysisConfig(listOf("Alex"), "2.24", "2.24")).result()
        val summary = Reports.build(empty, ReportOptions()).last()
        assertEquals("Undefined (zero denominator)", summary.rows.first { it[0] == "Alex / Alex" }[1])
        assertEquals(emptyList(), summary.rows.filter { it.first().startsWith("Export") })
    }

    @Test
    fun reportDatesExpandShortYearsWithoutChangingFullYears() {
        assertEquals("September 2026", ReportDates.month("9.26"))
        assertEquals("September 2026", ReportDates.month("09.2026."))
        assertEquals("23.9.2023.", ReportDates.day("23.9.23"))
        assertEquals("23.9.2023.", ReportDates.day("23.9.2023."))
        assertEquals(2000, ReportDates.fullYear(0))
        assertEquals("December 2099", ReportDates.month("12.99"))
    }

    @Test
    fun calendarPresentationUsesRealDaysAndKeepsPythonCountersUnchanged() {
        val result = sample()
        val sections = Reports.build(result, ReportOptions(startingDate = "2.2.24"))
        val calendar = sections[2].calendarMonths
        assertEquals(listOf("February 2024", "March 2024"), calendar.map { it.label })
        assertEquals(29, calendar.first().days.size)
        assertEquals(3, calendar.first().firstWeekday)
        assertEquals(null, calendar.first().days.first().count)
        assertEquals(1L, calendar.first().total)
        assertEquals("2.2.2024.", calendar.first().busiest!!.label)
        assertEquals(0L, calendar.first().days.last().count)
        assertEquals(62, result.days.size)
        assertEquals(3L, result.rangeTotal)
        assertEquals(ReportLayout.YEAR_TABLE, sections[1].layout)
        assertEquals(listOf(2L, 1L), sections[1].months.map { it.count })
    }

    @Test
    fun reportCalendarPreservesEmptyMonthsAndHandlesEmptyFilters() {
        val result = ChatAnalyzer(AnalysisConfig(listOf("Alex"), "12.23", "2.24")).result()
        val sections = Reports.build(result, ReportOptions())
        assertEquals(listOf(2023, 2024, 2024), sections[1].months.map { it.date.year })
        assertEquals(listOf(31, 31, 29), sections[2].calendarMonths.map { it.days.size })
        assertEquals(null, sections[2].calendarMonths.first().busiest)
        val filtered = Reports.build(result, ReportOptions("1.3.24", "3.24"))
        assertEquals(emptyList(), filtered[1].months)
        assertEquals(emptyList(), filtered[2].calendarMonths)
    }

    @Test
    fun latestMonthCalendarIsIndependentOfReportFilters() {
        for (rules in CountingRules.entries) {
            val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex"), "12.23", "3.24", rules = rules))
            analyzer.consume("latest.txt", sequenceOf(
                "31.12.23., 12:00 - Alex: hello",
                "29.2.24., 12:00 - Alex: leap day",
                "29.2.24., 13:00 - Alex: hello again",
            ))
            val sections = Reports.build(analyzer.result(), ReportOptions("1.3.24", "3.24"))
            val latest = sections[3]
            val month = latest.calendarMonths.single()
            assertEquals(ReportLayout.LATEST_CALENDAR, latest.layout)
            assertEquals("February 2024", month.label)
            assertEquals(29, month.days.size)
            assertEquals(3, month.firstWeekday)
            assertEquals(0L, month.days.first().count)
            assertEquals(2L, month.total)
            assertEquals("29.2.2024.", month.busiest!!.label)
            assertEquals(listOf("Count", "2"), latest.rows[1])
            assertEquals(listOf("3.24"), sections[2].calendarMonths.map { it.key })
        }
    }

    @Test
    fun latestMonthCalendarPreservesCountingModeSelection() {
        for (rules in CountingRules.entries) {
            val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex"), "12.23", "2.24", rules = rules))
            analyzer.consume("first-only.txt", sequenceOf("31.12.23., 12:00 - Alex: hello"))
            val month = Reports.build(analyzer.result(), ReportOptions())[3].calendarMonths.single()
            if (rules == CountingRules.PYTHON_COMPATIBLE) {
                assertEquals("2.24", month.key)
                assertEquals(29, month.days.size)
                assertEquals(0L, month.total)
                assertEquals(null, month.busiest)
            } else {
                assertEquals("12.23", month.key)
                assertEquals(31, month.days.size)
                assertEquals(1L, month.total)
            }
        }
    }

    @Test
    fun calendarRulesUseRealDaysExactSendersAndMultilineWords() {
        val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex", "Alex Group", "Blair"), "2.24", "3.24", CountMode.WORDS, CountingRules.CALENDAR_CORRECT))
        analyzer.consume("fixture.txt", fixture["input"].asString.lineSequence())
        val result = analyzer.result()
        assertEquals(60, result.days.size)
        assertEquals(4L, result.people.getValue("Alex").total)
        assertEquals(3L, result.people.getValue("Alex Group").total)
        assertEquals(0L, result.people.getValue("Blair").total)
        assertEquals(7L, result.rangeTotal)
        assertEquals(result.rangeTotal, result.hourlyTotal)
        assertEquals(4L, result.people.getValue("Alex").weekdays[3])
        assertEquals(1L, result.stats.unmatchedSenders)
    }

    @Test
    fun calendarNormalizesPaddedDatesAndDoesNotCountSystemLinesAsWords() {
        val analyzer = ChatAnalyzer(AnalysisConfig(listOf("Alex"), "2.24", "2.24", CountMode.WORDS, CountingRules.CALENDAR_CORRECT))
        analyzer.consume("chat.txt", sequenceOf(
            "01.02.2024, 08:09 - Alex: two words",
            "02.02.2024, 08:10 - Alex changed the group description",
            "unrelated line",
        ))
        assertEquals(2L, analyzer.result().total)
        assertEquals(29, analyzer.result().days.size)
    }
}