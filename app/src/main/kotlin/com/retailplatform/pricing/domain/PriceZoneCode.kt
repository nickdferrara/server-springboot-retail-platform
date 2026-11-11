package com.retailplatform.pricing.domain

/** Identifies a price zone: a group of stores that share retail prices. */
@JvmInline
value class PriceZoneCode private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[A-Z][A-Z0-9_]{0,19}")

        /** The zone a store belongs to until the pricing team moves it. */
        val DEFAULT = PriceZoneCode("DEFAULT")

        /** @throws IllegalArgumentException unless [value] is 1 to 20 upper-case letters, digits, or underscores. */
        fun of(value: String): PriceZoneCode {
            require(FORMAT.matches(value)) { "Not a price zone code: '$value'" }
            return PriceZoneCode(value)
        }
    }
}
