package com.retailplatform.ledger.domain

import com.retailplatform.shared.BusinessDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** One period of a fiscal year: the business dates from [startsOn] to [endsOn], both included. */
data class FiscalPeriod(
    val fiscalYear: Int,
    val period: Int,
    val startsOn: BusinessDate,
    val endsOn: BusinessDate,
) {
    operator fun contains(date: BusinessDate): Boolean = date >= startsOn && date <= endsOn
}

/**
 * The rules a 4-4-5 fiscal calendar is generated from: each quarter has periods of four, four, and five weeks,
 * and every period ends on the same weekday.
 *
 * These are placeholder rules until the business confirms its own: a fiscal year starts on the [weekStartsOn]
 * on or before 1 January, and in a year of 53 weeks the extra week joins the last period.
 */
data class FiscalCalendarRules(
    val weekStartsOn: DayOfWeek = DayOfWeek.SUNDAY,
) {
    /** The first day of fiscal year [year]. */
    fun startOf(year: Int): LocalDate = LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(weekStartsOn))

    /** The fiscal year [date] falls in. */
    fun yearOf(date: BusinessDate): Int {
        val calendarYear = date.value.year
        return if (date.value >= startOf(calendarYear + 1)) calendarYear + 1 else calendarYear
    }

    /** The twelve periods of fiscal year [year], in order and without gaps. */
    fun periodsOf(year: Int): List<FiscalPeriod> {
        val yearEnd = startOf(year + 1).minusDays(1)
        var start = startOf(year)
        return WEEKS_PER_PERIOD.mapIndexed { index, weeks ->
            val isLast = index == WEEKS_PER_PERIOD.lastIndex
            val end = if (isLast) yearEnd else start.plusWeeks(weeks.toLong()).minusDays(1)
            FiscalPeriod(year, index + 1, BusinessDate(start), BusinessDate(end)).also { start = end.plusDays(1) }
        }
    }

    private companion object {
        val WEEKS_PER_PERIOD = listOf(4, 4, 5, 4, 4, 5, 4, 4, 5, 4, 4, 5)
    }
}
