package com.retailplatform.vendors.domain

/** The number the business knows a vendor by: one to ten digits, kept as text so leading zeros survive. */
@JvmInline
value class VendorNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{1,10}")

        /** @throws IllegalArgumentException unless [value] is one to ten digits. */
        fun of(value: String): VendorNumber {
            require(FORMAT.matches(value)) { "Not a vendor number: '$value'" }
            return VendorNumber(value)
        }
    }
}
