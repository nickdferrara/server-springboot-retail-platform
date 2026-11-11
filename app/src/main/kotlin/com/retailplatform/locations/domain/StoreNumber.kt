package com.retailplatform.locations.domain

/** The number the business knows a store by: one to six digits, kept as text so leading zeros survive. */
@JvmInline
value class StoreNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{1,6}")

        /** @throws IllegalArgumentException unless [value] is one to six digits. */
        fun of(value: String): StoreNumber {
            require(FORMAT.matches(value)) { "Not a store number: '$value'" }
            return StoreNumber(value)
        }
    }
}
