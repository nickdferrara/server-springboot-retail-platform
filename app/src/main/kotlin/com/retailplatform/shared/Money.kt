package com.retailplatform.shared

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * An amount of money in one currency, held to four decimal places and never as floating point.
 *
 * Create instances with [Money.of]. Arithmetic between different currencies is rejected, and results of
 * multiplication are rounded half-even to four places.
 */
@ConsistentCopyVisibility
data class Money private constructor(
    val amount: BigDecimal,
    val currency: Currency,
) : Comparable<Money> {
    val isZero: Boolean get() = amount.signum() == 0
    val isPositive: Boolean get() = amount.signum() > 0
    val isNegative: Boolean get() = amount.signum() < 0

    operator fun plus(other: Money): Money = Money(amount + requireSameCurrency(other).amount, currency)

    operator fun minus(other: Money): Money = Money(amount - requireSameCurrency(other).amount, currency)

    operator fun unaryMinus(): Money = Money(amount.negate(), currency)

    operator fun times(factor: BigDecimal): Money = Money((amount * factor).setScale(SCALE, ROUNDING), currency)

    operator fun times(factor: Int): Money = times(BigDecimal(factor))

    override fun compareTo(other: Money): Int = amount.compareTo(requireSameCurrency(other).amount)

    override fun toString(): String = "${currency.currencyCode} ${amount.toPlainString()}"

    private fun requireSameCurrency(other: Money): Money {
        require(currency == other.currency) { "Cannot combine $currency with ${other.currency}" }
        return other
    }

    companion object {
        /** Decimal places kept for every amount; matches `NUMERIC(19,4)` in the database. */
        const val SCALE = 4
        private val ROUNDING = RoundingMode.HALF_EVEN

        /** @throws IllegalArgumentException if [amount] has more than four decimal places. */
        fun of(
            amount: BigDecimal,
            currency: Currency,
        ): Money {
            require(amount.stripTrailingZeros().scale() <= SCALE) {
                "Money holds at most $SCALE decimal places: $amount"
            }
            return Money(amount.setScale(SCALE), currency)
        }

        /** @throws IllegalArgumentException if [amount] is not a decimal number or [currencyCode] is not ISO 4217. */
        fun of(
            amount: String,
            currencyCode: String,
        ): Money {
            val parsed = requireNotNull(amount.toBigDecimalOrNull()) { "Not a decimal amount: $amount" }
            return of(parsed, Currency.getInstance(currencyCode))
        }

        fun zero(currency: Currency): Money = Money(BigDecimal.ZERO.setScale(SCALE), currency)
    }
}
