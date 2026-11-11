package com.retailplatform.locations.domain

/** A store's display name: not blank, at most 100 characters, without surrounding whitespace. */
@JvmInline
value class StoreName private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 100

        /** @throws IllegalArgumentException if [value] is blank or longer than [MAX_LENGTH] once trimmed. */
        fun of(value: String): StoreName {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "A store name cannot be blank" }
            require(trimmed.length <= MAX_LENGTH) { "A store name has at most $MAX_LENGTH characters" }
            return StoreName(trimmed)
        }
    }
}
