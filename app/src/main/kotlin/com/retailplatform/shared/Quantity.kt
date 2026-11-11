package com.retailplatform.shared

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount of something in a unit of measure, held to three decimal places and never as floating point.
 *
 * A quantity may be negative: inventory movements and adjustments subtract as well as add. Arithmetic between
 * different units is rejected; converting between units is the catalog's job.
 */
@ConsistentCopyVisibility
data class Quantity private constructor(
    val amount: BigDecimal,
    val unit: UnitOfMeasure,
) : Comparable<Quantity> {
    val isZero: Boolean get() = amount.signum() == 0
    val isPositive: Boolean get() = amount.signum() > 0
    val isNegative: Boolean get() = amount.signum() < 0

    operator fun plus(other: Quantity): Quantity = Quantity(amount + requireSameUnit(other).amount, unit)

    operator fun minus(other: Quantity): Quantity = Quantity(amount - requireSameUnit(other).amount, unit)

    operator fun unaryMinus(): Quantity = Quantity(amount.negate(), unit)

    operator fun times(factor: BigDecimal): Quantity = Quantity((amount * factor).setScale(SCALE, ROUNDING), unit)

    override fun compareTo(other: Quantity): Int = amount.compareTo(requireSameUnit(other).amount)

    override fun toString(): String = "${amount.toPlainString()} $unit"

    private fun requireSameUnit(other: Quantity): Quantity {
        require(unit == other.unit) { "Cannot combine $unit with ${other.unit}" }
        return other
    }

    companion object {
        /** Decimal places kept for every quantity. */
        const val SCALE = 3
        private val ROUNDING = RoundingMode.HALF_EVEN

        /** @throws IllegalArgumentException if [amount] has more than three decimal places. */
        fun of(
            amount: BigDecimal,
            unit: UnitOfMeasure,
        ): Quantity {
            require(amount.stripTrailingZeros().scale() <= SCALE) {
                "Quantity holds at most $SCALE decimal places: $amount"
            }
            return Quantity(amount.setScale(SCALE), unit)
        }

        fun of(
            amount: Int,
            unit: UnitOfMeasure,
        ): Quantity = of(BigDecimal(amount), unit)

        fun zero(unit: UnitOfMeasure): Quantity = Quantity(BigDecimal.ZERO.setScale(SCALE), unit)
    }
}
