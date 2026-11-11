package com.retailplatform.shared

/**
 * The unit a quantity is counted or measured in, identified by a short code such as `EA` or `GAL`.
 *
 * The catalog owns the list of units and the conversions between them; this type only guarantees a well-formed code.
 */
@JvmInline
value class UnitOfMeasure private constructor(
    val code: String,
) {
    override fun toString(): String = code

    companion object {
        private val CODE_FORMAT = Regex("[A-Z][A-Z0-9]{0,9}")

        val EACH = UnitOfMeasure("EA")
        val CASE = UnitOfMeasure("CS")
        val POUND = UnitOfMeasure("LB")
        val GALLON = UnitOfMeasure("GAL")

        /**
         * @throws IllegalArgumentException unless [code] is 1 to 10 upper-case letters or digits, starting with a
         * letter.
         */
        fun of(code: String): UnitOfMeasure {
            require(CODE_FORMAT.matches(code)) { "Not a unit of measure code: '$code'" }
            return UnitOfMeasure(code)
        }
    }
}
