package com.retailplatform.shared

import java.math.BigDecimal

/** A percentage from 0 to 100 inclusive, held to at most four decimal places. `2.5` means 2.5%. */
@ConsistentCopyVisibility
data class Percentage private constructor(
    val value: BigDecimal,
) : Comparable<Percentage> {
    val isZero: Boolean get() = value.signum() == 0

    /** This percentage of [amount], rounded half-even to the amount's four decimal places. */
    fun of(amount: Money): Money = amount * value.movePointLeft(2)

    override fun compareTo(other: Percentage): Int = value.compareTo(other.value)

    override fun toString(): String = "${value.stripTrailingZeros().toPlainString()}%"

    companion object {
        const val SCALE = 4
        private val HUNDRED = BigDecimal(100)

        /** @throws IllegalArgumentException unless [value] is between 0 and 100 with at most four decimal places. */
        fun of(value: BigDecimal): Percentage {
            require(value.signum() >= 0 && value <= HUNDRED) { "A percentage is between 0 and 100: $value" }
            require(value.stripTrailingZeros().scale() <= SCALE) {
                "A percentage holds at most $SCALE decimal places: $value"
            }
            return Percentage(value.setScale(SCALE))
        }

        /** @throws IllegalArgumentException if [value] is not a decimal number between 0 and 100. */
        fun of(value: String): Percentage =
            of(requireNotNull(value.toBigDecimalOrNull()) { "Not a decimal number: $value" })
    }
}
