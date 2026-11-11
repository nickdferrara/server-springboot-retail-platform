package com.retailplatform.locations.domain

import com.retailplatform.locations.api.events.StoreOpened
import com.retailplatform.shared.BusinessDate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class StoreTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T15:00:00Z"), ZoneOffset.UTC)
    private val chicago = ZoneId.of("America/Chicago")
    private val firstDay = BusinessDate.parse("2026-12-01")

    @Test
    fun `opening a store raises StoreOpened with the facts consumers need`() {
        val store = Store.open(StoreNumber.of("0101"), StoreName.of("Main Street"), chicago, firstDay, clock)

        val events = store.releaseEvents()

        events shouldHaveSize 1
        with(events.single() as StoreOpened) {
            storeId shouldBe store.id
            storeNumber shouldBe "0101"
            storeName shouldBe "Main Street"
            timeZone shouldBe "America/Chicago"
            businessDate shouldBe firstDay
            occurredAt shouldBe clock.instant()
            schemaVersion shouldBe 1
        }
    }

    @Test
    fun `events are released once`() {
        val store = Store.open(StoreNumber.of("7"), StoreName.of("Depot Road"), chicago, firstDay, clock)

        store.releaseEvents() shouldHaveSize 1
        store.releaseEvents().shouldBeEmpty()
    }

    @Test
    fun `a store rebuilt from stored state raises nothing`() {
        val opened = Store.open(StoreNumber.of("7"), StoreName.of("Depot Road"), chicago, firstDay, clock)

        val rebuilt = Store.rehydrate(opened.id, opened.number, opened.name, opened.timeZone, opened.openedOn)

        rebuilt.releaseEvents().shouldBeEmpty()
        rebuilt.id shouldBe opened.id
    }

    @Test
    fun `a store number is one to six digits and keeps leading zeros`() {
        StoreNumber.of("000101").value shouldBe "000101"
        listOf("", "1234567", "12A", " 12").forEach { text ->
            shouldThrow<IllegalArgumentException> { StoreNumber.of(text) }
        }
    }

    @Test
    fun `a store name is trimmed, not blank, and at most 100 characters`() {
        StoreName.of("  Main Street ").value shouldBe "Main Street"
        shouldThrow<IllegalArgumentException> { StoreName.of("   ") }
        shouldThrow<IllegalArgumentException> { StoreName.of("x".repeat(101)) }
    }

    @Test
    fun `a duplicate store number is a business rule violation with a stable code`() {
        val violation = StoreNumberAlreadyInUse(StoreNumber.of("0101"))

        violation.code shouldBe "locations.store-number-in-use"
        violation.message shouldBe "Store number 0101 is already in use"
    }
}
