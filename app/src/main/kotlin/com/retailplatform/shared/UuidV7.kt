package com.retailplatform.shared

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Generates version 7 UUIDs (RFC 9562): a 48-bit millisecond timestamp followed by random bits, so identifiers
 * created later sort after identifiers created earlier and index well.
 */
object UuidV7 {
    private const val TIMESTAMP_SHIFT = 16
    private const val RANDOM_A_MASK = 0x0FFFL
    private const val VERSION_7 = 0x7000L
    private const val RANDOM_B_MASK = 0x3FFFFFFFFFFFFFFFL
    private const val VARIANT_RFC_9562 = Long.MIN_VALUE

    private val random = SecureRandom()

    fun generate(clock: Clock): UUID {
        val mostSignificant =
            (clock.millis() shl TIMESTAMP_SHIFT) or VERSION_7 or (random.nextLong() and RANDOM_A_MASK)
        val leastSignificant = VARIANT_RFC_9562 or (random.nextLong() and RANDOM_B_MASK)
        return UUID(mostSignificant, leastSignificant)
    }
}
