package com.retailplatform.catalog.domain

/**
 * A product barcode: a 12-digit UPC-A or 13-digit EAN-13 whose last digit is a valid check digit, so a mistyped
 * code is caught when it is entered.
 */
@JvmInline
value class Upc private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{12,13}")
        private const val ODD_WEIGHT = 3
        private const val MODULUS = 10

        /** @throws IllegalArgumentException unless [value] is 12 or 13 digits with a correct check digit. */
        fun of(value: String): Upc {
            require(FORMAT.matches(value)) { "A UPC is 12 or 13 digits: '$value'" }
            require(checkDigit(value.dropLast(1)) == value.last().digitToInt()) { "UPC check digit is wrong: '$value'" }
            return Upc(value)
        }

        /** The GTIN check digit for [payload]: weights of 3 and 1 alternate, starting with 3 at the right. */
        private fun checkDigit(payload: String): Int {
            val sum =
                payload.reversed().withIndex().sumOf { (index, digit) ->
                    digit.digitToInt() * if (index % 2 == 0) ODD_WEIGHT else 1
                }
            return (MODULUS - sum % MODULUS) % MODULUS
        }
    }
}
