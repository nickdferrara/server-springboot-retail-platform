package com.retailplatform.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class QuantityTest {
    @Test
    fun `amounts are held to three decimal places`() {
        Quantity.of(BigDecimal("8123.4"), UnitOfMeasure.GALLON).amount shouldBe BigDecimal("8123.400")
        Quantity.of(12, UnitOfMeasure.CASE).amount shouldBe BigDecimal("12.000")
    }

    @Test
    fun `more than three decimal places is rejected`() {
        shouldThrow<IllegalArgumentException> { Quantity.of(BigDecimal("1.0001"), UnitOfMeasure.GALLON) }
    }

    @Test
    fun `adds, subtracts, and negates within one unit`() {
        val six = Quantity.of(6, UnitOfMeasure.EACH)
        val ten = Quantity.of(10, UnitOfMeasure.EACH)

        six + ten shouldBe Quantity.of(16, UnitOfMeasure.EACH)
        six - ten shouldBe Quantity.of(-4, UnitOfMeasure.EACH)
        -six shouldBe Quantity.of(-6, UnitOfMeasure.EACH)
        (six - ten).isNegative shouldBe true
        (six - six).isZero shouldBe true
        six.isPositive shouldBe true
    }

    @Test
    fun `multiplication rounds half-even to three places`() {
        Quantity.of(BigDecimal("0.001"), UnitOfMeasure.GALLON) * BigDecimal("0.5") shouldBe
            Quantity.zero(UnitOfMeasure.GALLON)
        Quantity.of(100, UnitOfMeasure.GALLON) * BigDecimal("0.5") shouldBe Quantity.of(50, UnitOfMeasure.GALLON)
    }

    @Test
    fun `different units cannot be combined or compared`() {
        val cases = Quantity.of(1, UnitOfMeasure.CASE)
        val eaches = Quantity.of(24, UnitOfMeasure.EACH)

        shouldThrow<IllegalArgumentException> { cases + eaches }
        shouldThrow<IllegalArgumentException> { cases - eaches }
        shouldThrow<IllegalArgumentException> { cases.compareTo(eaches) }
    }

    @Test
    fun `compares by amount and prints the amount with its unit`() {
        Quantity.of(1, UnitOfMeasure.POUND) shouldBeLessThan Quantity.of(2, UnitOfMeasure.POUND)
        Quantity.of(BigDecimal("2.5"), UnitOfMeasure.POUND).toString() shouldBe "2.500 LB"
    }
}
