package com.retailplatform.locations.domain

import com.retailplatform.locations.api.events.StoreClosed
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
        val store = Store.open(StoreNumber.of("0101"), LocationName.of("Main Street"), chicago, firstDay, clock)

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
        val store = Store.open(StoreNumber.of("7"), LocationName.of("Depot Road"), chicago, firstDay, clock)

        store.releaseEvents() shouldHaveSize 1
        store.releaseEvents().shouldBeEmpty()
    }

    @Test
    fun `a store rebuilt from stored state raises nothing`() {
        val opened = Store.open(StoreNumber.of("7"), LocationName.of("Depot Road"), chicago, firstDay, clock)

        val rebuilt =
            Store.rehydrate(opened.id, opened.number, opened.name, opened.timeZone, opened.openedOn, null, 3)

        rebuilt.releaseEvents().shouldBeEmpty()
        rebuilt.id shouldBe opened.id
        rebuilt.version shouldBe 3
        rebuilt.isOpen shouldBe true
    }

    @Test
    fun `closing a store raises StoreClosed dated on its last trading day`() {
        val store = Store.open(StoreNumber.of("7"), LocationName.of("Depot Road"), chicago, firstDay, clock)
        store.releaseEvents()

        store.close(firstDay.plusDays(30), clock)

        val event = store.releaseEvents().single() as StoreClosed
        event.storeId shouldBe store.id
        event.businessDate shouldBe firstDay.plusDays(30)
        store.isOpen shouldBe false
        store.closedOn shouldBe firstDay.plusDays(30)
    }

    @Test
    fun `a store cannot close twice or before it opened`() {
        val store = Store.open(StoreNumber.of("7"), LocationName.of("Depot Road"), chicago, firstDay, clock)

        shouldThrow<StoreClosedBeforeOpening> { store.close(firstDay.minusDays(1), clock) }
            .code shouldBe "locations.store-closed-before-opening"
        store.close(firstDay, clock)
        shouldThrow<StoreAlreadyClosed> { store.close(firstDay.plusDays(1), clock) }
            .code shouldBe "locations.store-already-closed"
    }

    @Test
    fun `details can be changed without an event`() {
        val store = Store.open(StoreNumber.of("7"), LocationName.of("Depot Road"), chicago, firstDay, clock)
        store.releaseEvents()

        store.updateDetails(LocationName.of("Depot Road East"), ZoneId.of("America/New_York"))

        store.name.value shouldBe "Depot Road East"
        store.timeZone shouldBe ZoneId.of("America/New_York")
        store.releaseEvents().shouldBeEmpty()
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
        LocationName.of("  Main Street ").value shouldBe "Main Street"
        shouldThrow<IllegalArgumentException> { LocationName.of("   ") }
        shouldThrow<IllegalArgumentException> { LocationName.of("x".repeat(101)) }
    }

    @Test
    fun `a duplicate store number is a business rule violation with a stable code`() {
        val violation = StoreNumberAlreadyInUse(StoreNumber.of("0101"))

        violation.code shouldBe "locations.store-number-in-use"
        violation.message shouldBe "Store number 0101 is already in use"
    }
}
