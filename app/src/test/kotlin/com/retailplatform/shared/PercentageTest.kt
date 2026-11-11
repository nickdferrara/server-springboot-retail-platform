package com.retailplatform.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PercentageTest {
    @Test
    fun `holds values from 0 to 100 at four decimal places`() {
        Percentage.of("2.5").value shouldBe BigDecimal("2.5000")
        Percentage.of(BigDecimal.ZERO).isZero shouldBe true
        Percentage.of("100") shouldBe Percentage.of("100.0")
    }

    @Test
    fun `rejects values outside 0 to 100, too precise, or not numeric`() {
        listOf("-0.01", "100.01", "1.00001", "two").forEach { text ->
            shouldThrow<IllegalArgumentException> { Percentage.of(text) }
        }
    }

    @Test
    fun `takes a share of an amount of money`() {
        Percentage.of("2").of(Money.of("150.00", "USD")) shouldBe Money.of("3.00", "USD")
        Percentage.of("33.3333").of(Money.of("0.01", "USD")) shouldBe Money.of("0.0033", "USD")
    }

    @Test
    fun `compares and prints without trailing zeros`() {
        Percentage.of("1.5") shouldBeLessThan Percentage.of("2")
        Percentage.of("2.50").toString() shouldBe "2.5%"
    }
}
