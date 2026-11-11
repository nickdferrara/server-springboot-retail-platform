package com.retailplatform.shared

import java.time.Clock
import java.util.UUID

/**
 * An identifier that names what it identifies, so a store ID cannot be passed where an item ID is expected.
 *
 * Modules declare their own identifiers the same way the shared ones are declared:
 *
 * ```
 * @JvmInline
 * value class StoreOrderId private constructor(override val value: UUID) : TypedId {
 *     companion object : TypedIdFactory<StoreOrderId>(::StoreOrderId)
 * }
 * ```
 */
interface TypedId {
    val value: UUID
}

/** Gives the companion object of a [TypedId] its factory functions. */
abstract class TypedIdFactory<T : TypedId>(
    private val wrap: (UUID) -> T,
) {
    /** A new version 7 identifier stamped with the current time of [clock]. */
    fun generate(clock: Clock): T = wrap(UuidV7.generate(clock))

    fun of(value: UUID): T = wrap(value)

    /** @throws IllegalArgumentException unless [text] is a UUID in its canonical 36-character form. */
    fun parse(text: String): T {
        require(text.length == CANONICAL_LENGTH) { "Not a UUID: '$text'" }
        return wrap(UUID.fromString(text))
    }

    private companion object {
        const val CANONICAL_LENGTH = 36
    }
}
