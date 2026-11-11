package com.retailplatform.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeSortedBy
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TypedIdTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `generated identifiers are version 7 UUIDs carrying the clock time`() {
        val uuid = UuidV7.generate(clock)

        uuid.version() shouldBe 7
        uuid.variant() shouldBe 2
        uuid.mostSignificantBits ushr 16 shouldBe clock.millis()
    }

    @Test
    fun `generated identifiers are unique within one millisecond`() {
        List(1_000) { StoreId.generate(clock) }.toSet() shouldHaveSize 1_000
    }

    @Test
    fun `identifiers generated later sort after identifiers generated earlier`() {
        val ids =
            (0L until 50L).map { second ->
                ItemId.generate(Clock.offset(clock, java.time.Duration.ofSeconds(second)))
            }

        ids shouldBeSortedBy { it.value.toString() }
    }

    @Test
    fun `round-trips through its text form`() {
        val id = VendorId.generate(clock)

        VendorId.parse(id.toString()) shouldBe id
        VendorId.of(id.value) shouldBe id
    }

    @Test
    fun `rejects text that is not a canonical UUID`() {
        shouldThrow<IllegalArgumentException> { EventId.parse("not-a-uuid") }
        shouldThrow<IllegalArgumentException> { EventId.parse("1-2-3-4-5") }
    }

    @Test
    fun `identifiers of different kinds are different types even with the same value`() {
        val value = UUID.fromString("019a6f3e-0000-7000-8000-000000000001")

        StoreId.of(value).value shouldBe ItemId.of(value).value
        (StoreId.of(value) as Any) shouldNotBe (ItemId.of(value) as Any)
    }
}
