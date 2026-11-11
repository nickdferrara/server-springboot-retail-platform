package com.retailplatform.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

class MoneyTest {
    private val usd = Currency.getInstance("USD")

    @Test
    fun `amounts are held to four decimal places`() {
        Money.of("12.5", "USD").amount shouldBe BigDecimal("12.5000")
        Money.of(BigDecimal("0.0001"), usd).amount shouldBe BigDecimal("0.0001")
    }

    @Test
    fun `amounts written with different scales are equal`() {
        Money.of("1.5", "USD") shouldBe Money.of("1.5000", "USD")
        Money.of("1.5", "USD").hashCode() shouldBe Money.of("1.50", "USD").hashCode()
    }

    @Test
    fun `more than four decimal places is rejected`() {
        shouldThrow<IllegalArgumentException> { Money.of("1.00001", "USD") }
    }

    @Test
    fun `text that is not a number or a currency is rejected`() {
        shouldThrow<IllegalArgumentException> { Money.of("twelve", "USD") }
        shouldThrow<IllegalArgumentException> { Money.of("12.00", "DOLLARS") }
    }

    @Test
    fun `adds and subtracts exactly`() {
        Money.of("0.10", "USD") + Money.of("0.20", "USD") shouldBe Money.of("0.30", "USD")
        Money.of("1.00", "USD") - Money.of("2.50", "USD") shouldBe Money.of("-1.50", "USD")
        -Money.of("3.00", "USD") shouldBe Money.of("-3.00", "USD")
    }

    @Test
    fun `multiplication rounds half-even to four places`() {
        Money.of("0.0001", "USD") * BigDecimal("0.5") shouldBe Money.zero(usd)
        Money.of("0.0003", "USD") * BigDecimal("0.5") shouldBe Money.of("0.0002", "USD")
        Money.of("2.49", "USD") * 3 shouldBe Money.of("7.47", "USD")
    }

    @Test
    fun `different currencies cannot be combined or compared`() {
        val dollars = Money.of("1.00", "USD")
        val euros = Money.of("1.00", "EUR")

        shouldThrow<IllegalArgumentException> { dollars + euros }
        shouldThrow<IllegalArgumentException> { dollars - euros }
        shouldThrow<IllegalArgumentException> { dollars.compareTo(euros) }
    }

    @Test
    fun `compares by amount and reports its sign`() {
        Money.of("2.00", "USD") shouldBeGreaterThan Money.of("1.99", "USD")
        Money.zero(usd).isZero shouldBe true
        Money.of("0.01", "USD").isPositive shouldBe true
        Money.of("-0.01", "USD").isNegative shouldBe true
    }

    @Test
    fun `prints the currency code and the plain amount`() {
        Money.of("1234.5", "USD").toString() shouldBe "USD 1234.5000"
    }
}
