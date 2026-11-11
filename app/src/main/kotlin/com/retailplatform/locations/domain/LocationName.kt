package com.retailplatform.locations.domain

/**
 * The display name of a store, warehouse, or route: not blank, at most 100 characters, without surrounding
 * whitespace.
 */
@JvmInline
value class LocationName private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 100

        /** @throws IllegalArgumentException if [value] is blank or longer than [MAX_LENGTH] once trimmed. */
        fun of(value: String): LocationName {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "A name cannot be blank" }
            require(trimmed.length <= MAX_LENGTH) { "A name has at most $MAX_LENGTH characters" }
            return LocationName(trimmed)
        }
    }
}
