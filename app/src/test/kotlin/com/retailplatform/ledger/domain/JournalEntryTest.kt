package com.retailplatform.ledger.domain

import com.retailplatform.ledger.api.events.JournalEntryPosted
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class JournalEntryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-11T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val rules = FiscalCalendarRules()
    private val period = rules.periodsOf(rules.yearOf(today)).first { today in it }
    private val inventory = AccountNumber.of("01-1400-0000-00")
    private val accrued = AccountNumber.of("01-2100-0000-00")
    private val number = JournalEntryNumber.of("100001")

    @Test
    fun `a balanced entry is posted in its fiscal period and announced`() {
        val eventId = EventId.generate(clock)

        val entry =
            JournalEntry.post(
                number,
                period,
                content(JournalLine.debit(inventory, usd("180.00")), JournalLine.credit(accrued, usd("180.00")))
                    .copy(source = EntrySource("receiving", eventId, "Receipt 100001")),
                clock,
            )

        entry.amount shouldBe usd("180.00")
        entry.period shouldBe period
        val event = entry.releaseEvents().single().shouldBeInstanceOf<JournalEntryPosted>()
        event.amount shouldBe BigDecimal("180.0000")
        event.journal shouldBe "INVENTORY"
        event.fiscalPeriod shouldBe period.period
        event.sourceEventId shouldBe eventId
        event.journalEntryNumber shouldBe "100001"
    }

    @Test
    fun `an entry may split a side across accounts as long as it balances`() {
        val entry =
            JournalEntry.post(
                number,
                period,
                content(
                    JournalLine.debit(inventory, usd("100.00")),
                    JournalLine.debit(AccountNumber.of("01-1400-0101-00"), usd("80.00")),
                    JournalLine.credit(accrued, usd("180.00")),
                ),
                clock,
            )

        entry.amount shouldBe usd("180.00")
    }

    @Test
    fun `an entry that does not balance, lacks a side, or mixes currencies is refused`() {
        shouldThrow<JournalEntryUnbalanced> {
            post(JournalLine.debit(inventory, usd("180.00")), JournalLine.credit(accrued, usd("179.99")))
        }
        shouldThrow<JournalEntryIncomplete> { post(JournalLine.debit(inventory, usd("180.00"))) }
        shouldThrow<JournalEntryIncomplete> { post() }
        shouldThrow<JournalEntryIncomplete> {
            post(JournalLine.debit(inventory, usd("1.00")), JournalLine.credit(accrued, Money.of("1.00", "CAD")))
        }
        shouldThrow<IllegalArgumentException> { JournalLine.debit(inventory, usd("0.00")) }
        shouldThrow<IllegalArgumentException> { AccountNumber.of("1400") }
        shouldThrow<IllegalArgumentException> { JournalEntryNumber.of("JE-1") }
        shouldThrow<IllegalArgumentException> { Account.of(inventory, " ", AccountType.ASSET) }
    }

    @Test
    fun `an entry is posted in the period its business date falls in`() {
        val lines = arrayOf(JournalLine.debit(inventory, usd("1.00")), JournalLine.credit(accrued, usd("1.00")))
        val nextYear = content(*lines).copy(businessDate = period.endsOn.plusDays(400))

        shouldThrow<BusinessDateOutsidePeriod> { JournalEntry.post(number, period, nextYear, clock) }
    }

    @Test
    fun `a fiscal year has twelve periods of four, four, and five weeks without gaps`() {
        val periods = rules.periodsOf(2026)

        periods.size shouldBe 12
        periods.first().startsOn shouldBe BusinessDate(LocalDate.of(2025, 12, 28))
        periods.map { weeks(it) } shouldBe listOf(4L, 4L, 5L, 4L, 4L, 5L, 4L, 4L, 5L, 4L, 4L, 5L)
        periods.zipWithNext().all { (earlier, later) -> earlier.endsOn.plusDays(1) == later.startsOn } shouldBe true
        periods.all { it.startsOn.value.dayOfWeek == DayOfWeek.SUNDAY } shouldBe true
        periods.all { it.endsOn.value.dayOfWeek == DayOfWeek.SATURDAY } shouldBe true
        periods.last().endsOn.plusDays(1) shouldBe BusinessDate(rules.startOf(2027))
    }

    @Test
    fun `a year of fifty-three weeks gives the extra week to the last period`() {
        // 1 January 2028 is a Saturday, so fiscal 2028 runs from 26 December 2027 to 30 December 2028.
        val periods = rules.periodsOf(2028)

        weeks(periods.last()) shouldBe 6
        periods.sumOf { weeks(it) } shouldBe 53
    }

    @Test
    fun `a date belongs to the fiscal year that has started on or before it`() {
        rules.yearOf(BusinessDate(LocalDate.of(2025, 12, 27))) shouldBe 2025
        rules.yearOf(BusinessDate(LocalDate.of(2025, 12, 28))) shouldBe 2026
        rules.yearOf(BusinessDate(LocalDate.of(2026, 6, 15))) shouldBe 2026
    }

    private fun weeks(period: FiscalPeriod): Long =
        (period.endsOn.value.toEpochDay() - period.startsOn.value.toEpochDay() + 1) / DAYS_PER_WEEK

    private fun post(vararg lines: JournalLine) = JournalEntry.post(number, period, content(*lines), clock)

    private fun content(vararg lines: JournalLine) =
        JournalEntryContent(
            Journal.INVENTORY,
            today,
            EntrySource("receiving", null, "Receipt 100001"),
            "Goods received",
            lines.toList(),
        )

    private fun usd(amount: String) = Money.of(amount, "USD")

    private companion object {
        const val DAYS_PER_WEEK = 7
    }
}
