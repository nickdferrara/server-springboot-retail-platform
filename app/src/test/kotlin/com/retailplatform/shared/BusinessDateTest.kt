package com.retailplatform.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BusinessDateTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `an instant belongs to the calendar date of the company time zone`() {
        val justAfterMidnightUtc = Instant.parse("2026-11-11T00:30:00Z")

        BusinessDate.of(justAfterMidnightUtc, newYork) shouldBe BusinessDate(LocalDate.of(2026, 11, 10))
        BusinessDate.of(justAfterMidnightUtc, ZoneId.of("UTC")) shouldBe BusinessDate(LocalDate.of(2026, 11, 11))
    }

    @Test
    fun `today comes from the injected clock`() {
        val clock = Clock.fixed(Instant.parse("2026-03-01T04:59:00Z"), newYork)

        BusinessDate.today(clock) shouldBe BusinessDate.parse("2026-02-28")
    }

    @Test
    fun `parses ISO dates and rejects anything else`() {
        BusinessDate.parse("2026-11-10").toString() shouldBe "2026-11-10"
        shouldThrow<IllegalArgumentException> { BusinessDate.parse("11/10/2026") }
        shouldThrow<IllegalArgumentException> { BusinessDate.parse("2026-02-30") }
    }

    @Test
    fun `moves by days and orders chronologically`() {
        val date = BusinessDate.parse("2026-12-31")

        date.plusDays(1) shouldBe BusinessDate.parse("2027-01-01")
        date.minusDays(31) shouldBe BusinessDate.parse("2026-11-30")
        date shouldBeLessThan date.plusDays(1)
    }
}
