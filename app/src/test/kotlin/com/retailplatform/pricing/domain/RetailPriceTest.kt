package com.retailplatform.pricing.domain

import com.retailplatform.pricing.api.events.PriceChangeEffective
import com.retailplatform.pricing.api.events.RetailPriceScheduled
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.StoreId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RetailPriceTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.parse("2026-11-10")
    private val itemId = ItemId.generate(clock)
    private val zone = PriceScope.Zone(PriceZoneCode.DEFAULT)
    private val price = Money.of("3.49", "USD")

    @Test
    fun `scheduling a price raises RetailPriceScheduled dated today`() {
        val scheduled = RetailPrice.schedule(itemId, zone, price, today.plusDays(7), clock)

        val event = scheduled.releaseEvents().single() as RetailPriceScheduled
        event.businessDate shouldBe today
        event.retailPrice.itemId shouldBe itemId
        event.retailPrice.zoneCode shouldBe "DEFAULT"
        event.retailPrice.storeId shouldBe null
        event.retailPrice.price shouldBe BigDecimal("3.4900")
        event.retailPrice.currency shouldBe "USD"
        event.retailPrice.effectiveFrom shouldBe today.plusDays(7)
        scheduled.announcedOn shouldBe null
    }

    @Test
    fun `a store price carries the store and no zone`() {
        val storeId = StoreId.generate(clock)

        val facts =
            (
                RetailPrice
                    .schedule(
                        itemId,
                        PriceScope.Store(storeId),
                        price,
                        today,
                        clock,
                    ).releaseEvents()
                    .single() as RetailPriceScheduled
            ).retailPrice

        facts.storeId shouldBe storeId
        facts.zoneCode shouldBe null
    }

    @Test
    fun `a price is announced once, on or after its effective date`() {
        val scheduled = RetailPrice.schedule(itemId, zone, price, today.plusDays(2), clock).also { it.releaseEvents() }

        scheduled.announce(today.plusDays(1), clock)
        scheduled.releaseEvents().shouldBeEmpty()
        scheduled.isDueOn(today.plusDays(1)) shouldBe false

        scheduled.announce(today.plusDays(3), clock)
        scheduled.announce(today.plusDays(4), clock)

        val event = scheduled.releaseEvents().single() as PriceChangeEffective
        event.businessDate shouldBe today.plusDays(2)
        scheduled.announcedOn shouldBe today.plusDays(3)
        scheduled.isDueOn(today.plusDays(5)) shouldBe false
    }

    @Test
    fun `a price must be positive and cannot start in the past`() {
        shouldThrow<RetailPriceNotPositive> { RetailPrice.schedule(itemId, zone, Money.of("0", "USD"), today, clock) }
            .code shouldBe "pricing.price-not-positive"
        shouldThrow<PriceEffectiveInPast> { RetailPrice.schedule(itemId, zone, price, today.minusDays(1), clock) }
            .code shouldBe "pricing.price-effective-in-past"
    }

    @Test
    fun `an item can take a price up to the day it is discontinued`() {
        val item = PricedItem(itemId, "100200")
        val discontinued = item.discontinue(today.plusDays(10))

        item.isPriceableOn(today.plusDays(365)) shouldBe true
        discontinued.isPriceableOn(today.plusDays(10)) shouldBe true
        discontinued.isPriceableOn(today.plusDays(11)) shouldBe false
        ItemNotPriceable(discontinued, today.plusDays(11)).code shouldBe "pricing.item-discontinued"
        ItemNotKnownToPricing(itemId).code shouldBe "pricing.unknown-item"
    }

    @Test
    fun `a price zone needs a name, and each rule has a stable code`() {
        PriceZone.of(PriceZoneCode.of("METRO"), " Metro stores ").name shouldBe "Metro stores"
        shouldThrow<IllegalArgumentException> { PriceZone.of(PriceZoneCode.of("METRO"), " ") }
        shouldThrow<IllegalArgumentException> { PriceZone.of(PriceZoneCode.of("METRO"), "x".repeat(61)) }
        PriceZoneCodeAlreadyInUse(PriceZoneCode.DEFAULT).code shouldBe "pricing.price-zone-code-in-use"
        UnknownPriceZone(PriceZoneCode.DEFAULT).code shouldBe "pricing.unknown-price-zone"
        StoreNotKnownToPricing("store").code shouldBe "pricing.unknown-store"
        PriceAlreadyScheduled(today).code shouldBe "pricing.price-already-scheduled"
    }

    @Test
    fun `a rebuilt price raises nothing`() {
        val original = RetailPrice.schedule(itemId, zone, price, today, clock)
        val rebuilt = RetailPrice.rehydrate(original.id, itemId, zone, price, today, today, 2)

        rebuilt.releaseEvents().shouldBeEmpty()
        rebuilt.version shouldBe 2
        rebuilt.announcedOn shouldBe today
    }
}
