package com.retailplatform.vendors.domain

/** A vendor's name: not blank, at most 100 characters, without surrounding whitespace. */
@JvmInline
value class VendorName private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 100

        /** @throws IllegalArgumentException if [value] is blank or longer than [MAX_LENGTH] once trimmed. */
        fun of(value: String): VendorName {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "A vendor name cannot be blank" }
            require(trimmed.length <= MAX_LENGTH) { "A vendor name has at most $MAX_LENGTH characters" }
            return VendorName(trimmed)
        }
    }
}
