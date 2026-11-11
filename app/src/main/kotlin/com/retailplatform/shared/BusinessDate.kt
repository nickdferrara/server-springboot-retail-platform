package com.retailplatform.shared

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The trading day a transaction belongs to. It is recorded explicitly and never derived from a timestamp at read
 * time, because a sale rung up after midnight can belong to the previous day.
 */
@JvmInline
value class BusinessDate(
    val value: LocalDate,
) : Comparable<BusinessDate> {
    fun plusDays(days: Long): BusinessDate = BusinessDate(value.plusDays(days))

    fun minusDays(days: Long): BusinessDate = BusinessDate(value.minusDays(days))

    override fun compareTo(other: BusinessDate): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    companion object {
        /** The calendar date of [instant] in [zone], the company's configured time zone. */
        fun of(
            instant: Instant,
            zone: ZoneId,
        ): BusinessDate = BusinessDate(instant.atZone(zone).toLocalDate())

        /** Today's calendar date according to [clock], which carries the company's time zone. */
        fun today(clock: Clock): BusinessDate = BusinessDate(LocalDate.now(clock))

        /** @throws IllegalArgumentException unless [text] is an ISO-8601 date such as `2026-11-10`. */
        fun parse(text: String): BusinessDate =
            try {
                BusinessDate(LocalDate.parse(text))
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("Not an ISO-8601 date: '$text'", e)
            }
    }
}
