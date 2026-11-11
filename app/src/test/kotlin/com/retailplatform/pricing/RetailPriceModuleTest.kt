package com.retailplatform.pricing

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.catalog.api.events.ItemCreated
import com.retailplatform.catalog.api.events.ItemDiscontinued
import com.retailplatform.catalog.api.events.ItemFacts
import com.retailplatform.platform.security.Roles
import com.retailplatform.pricing.api.events.PriceChangeEffective
import com.retailplatform.pricing.api.events.RetailPriceScheduled
import com.retailplatform.pricing.application.AnnounceEffectivePrices
import com.retailplatform.pricing.application.AssignDefaultPriceZone
import com.retailplatform.pricing.application.AssignDefaultPriceZoneCommand
import com.retailplatform.pricing.application.AssignStoreToPriceZone
import com.retailplatform.pricing.application.AssignStoreToPriceZoneCommand
import com.retailplatform.pricing.application.CreatePriceZone
import com.retailplatform.pricing.application.PriceQueries
import com.retailplatform.pricing.application.PriceZoneQueries
import com.retailplatform.pricing.application.PricedItemRepository
import com.retailplatform.pricing.application.RegisterPricedItem
import com.retailplatform.pricing.application.RetailPriceRepository
import com.retailplatform.pricing.application.ScheduleRetailPrice
import com.retailplatform.pricing.application.ScheduleRetailPriceCommand
import com.retailplatform.pricing.domain.ItemNotKnownToPricing
import com.retailplatform.pricing.domain.ItemNotPriceable
import com.retailplatform.pricing.domain.PriceAlreadyScheduled
import com.retailplatform.pricing.domain.PriceScope
import com.retailplatform.pricing.domain.PriceZone
import com.retailplatform.pricing.domain.PriceZoneCode
import com.retailplatform.pricing.domain.PriceZoneCodeAlreadyInUse
import com.retailplatform.pricing.domain.RetailPrice
import com.retailplatform.pricing.domain.RetailPriceId
import com.retailplatform.pricing.domain.StoreNotKnownToPricing
import com.retailplatform.pricing.domain.UnknownPriceZone
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.test.context.support.WithMockUser
import java.time.Clock
import java.util.Optional
import java.util.function.Supplier

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(authorities = [Roles.PRICING_ANALYST])
class RetailPriceModuleTest(
    @Autowired private val registerPricedItem: RegisterPricedItem,
    @Autowired private val pricedItems: PricedItemRepository,
    @Autowired private val assignDefaultPriceZone: AssignDefaultPriceZone,
    @Autowired private val createPriceZone: CreatePriceZone,
    @Autowired private val assignStoreToPriceZone: AssignStoreToPriceZone,
    @Autowired private val zones: PriceZoneQueries,
    @Autowired private val scheduleRetailPrice: ScheduleRetailPrice,
    @Autowired private val announceEffectivePrices: AnnounceEffectivePrices,
    @Autowired private val prices: PriceQueries,
    @Autowired private val priceRepository: RetailPriceRepository,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)
    private val defaultZone = PriceScope.Zone(PriceZoneCode.DEFAULT)

    @Test
    fun `items created and discontinued in the catalog become known to pricing`(scenario: Scenario) {
        val itemId = ItemId.generate(clock)
        val facts = ItemFacts("500001", "Cola", "BEV", "SODA", "EA", emptyList())

        scenario
            .publish(ItemCreated(EventId.generate(clock), clock.instant(), today, itemId, facts))
            .andWaitForStateChange(Supplier { Optional.ofNullable(pricedItems.findById(itemId)) })
            .andVerify { item -> item.orElseThrow().itemNumber shouldBe "500001" }

        scenario
            .publish(ItemDiscontinued(EventId.generate(clock), clock.instant(), today.plusDays(5), itemId, "500001"))
            .andWaitForStateChange(
                Supplier { Optional.ofNullable(pricedItems.findById(itemId)?.discontinuedOn) },
            ).andVerify { lastDay -> lastDay.orElseThrow() shouldBe today.plusDays(5) }

        scheduleRetailPrice.handle(command(itemId, defaultZone, "1.99", today.plusDays(5)))
        shouldThrow<ItemNotPriceable> {
            scheduleRetailPrice.handle(command(itemId, defaultZone, "1.99", today.plusDays(6)))
        }
    }

    @Test
    fun `a price effective today is scheduled and in effect at once`(scenario: Scenario) {
        val itemId = item("500002")

        scenario
            .stimulate(Runnable { scheduleRetailPrice.handle(command(itemId, defaultZone, "2.49", today)) })
            .andWaitForEventOfType(PriceChangeEffective::class.java)
            .matching { it.retailPrice.itemId == itemId }
            .toArriveAndVerify { event ->
                event.businessDate shouldBe today
                prices.forItem(itemId).single().announcedOn shouldBe today
            }
    }

    @Test
    fun `a future price is scheduled now and announced when its day comes`(scenario: Scenario) {
        val itemId = item("500003")

        scenario
            .stimulate(Runnable { scheduleRetailPrice.handle(command(itemId, defaultZone, "2.99", today.plusDays(3))) })
            .andWaitForEventOfType(RetailPriceScheduled::class.java)
            .matching { it.retailPrice.itemId == itemId }
            .toArriveAndVerify { event -> event.retailPrice.effectiveFrom shouldBe today.plusDays(3) }
        prices.forItem(itemId).single().announcedOn shouldBe null

        // A price that fell due while nothing was running, as if entered days ago.
        val overdueItem = item("500004")
        priceRepository.save(
            RetailPrice.rehydrate(
                RetailPriceId.generate(clock),
                overdueItem,
                defaultZone,
                Money.of("4.25", "USD"),
                today.minusDays(1),
                announcedOn = null,
                version = 0,
            ),
        )

        scenario
            .stimulate(Runnable { announceEffectivePrices.handle() shouldBeGreaterThanOrEqual 1 })
            .andWaitForEventOfType(PriceChangeEffective::class.java)
            .matching { it.retailPrice.itemId == overdueItem }
            .toArriveAndVerify { event ->
                event.businessDate shouldBe today.minusDays(1)
                prices.forItem(overdueItem).single().announcedOn shouldBe today
                prices.forItem(itemId).single().announcedOn shouldBe null
            }
        announceEffectivePrices.handle() shouldBe 0
    }

    @Test
    fun `a store charges its own price when it has one, otherwise its zone's latest price`() {
        val itemId = item("500005")
        val storeId = store()
        val other = store()
        scheduleRetailPrice.handle(command(itemId, defaultZone, "3.00", today))
        scheduleRetailPrice.handle(command(itemId, defaultZone, "3.25", today.plusDays(10)))
        scheduleRetailPrice.handle(command(itemId, PriceScope.Store(storeId), "2.75", today.plusDays(2)))

        fun priceAt(
            store: StoreId,
            daysAhead: Long,
        ) = prices.resolve(itemId, store, today.plusDays(daysAhead)).source.price

        priceAt(storeId, 0) shouldBe Money.of("3.00", "USD")
        priceAt(storeId, 2) shouldBe Money.of("2.75", "USD")
        priceAt(storeId, 30) shouldBe Money.of("2.75", "USD")
        priceAt(other, 9) shouldBe Money.of("3.00", "USD")
        priceAt(other, 10) shouldBe Money.of("3.25", "USD")
        prices.forItem(itemId).map { it.effectiveFrom } shouldBe listOf(today, today.plusDays(2), today.plusDays(10))
        shouldThrow<ResourceNotFound> { prices.resolve(itemId, storeId, today.minusDays(1)) }
        shouldThrow<StoreNotKnownToPricing> { prices.resolve(itemId, StoreId.generate(clock), today) }
    }

    @Test
    fun `a store moved to another zone takes that zone's prices`() {
        val itemId = item("500006")
        val storeId = store()
        val metro = PriceZoneCode.of("METRO_${storeId.toString().take(8).uppercase().replace("-", "")}")
        createPriceZone.handle(PriceZone.of(metro, "Metro stores"))
        scheduleRetailPrice.handle(command(itemId, defaultZone, "3.00", today))
        scheduleRetailPrice.handle(command(itemId, PriceScope.Zone(metro), "3.60", today))

        assignStoreToPriceZone.handle(AssignStoreToPriceZoneCommand(storeId, metro))

        zones.zoneOf(storeId).zone shouldBe metro
        zones.zones().map { it.code } shouldContain metro
        prices.resolve(itemId, storeId, today).source.price shouldBe Money.of("3.60", "USD")
        shouldThrow<PriceZoneCodeAlreadyInUse> { createPriceZone.handle(PriceZone.of(metro, "Again")) }
        shouldThrow<UnknownPriceZone> {
            assignStoreToPriceZone.handle(AssignStoreToPriceZoneCommand(storeId, PriceZoneCode.of("NOWHERE")))
        }
        shouldThrow<StoreNotKnownToPricing> {
            assignStoreToPriceZone.handle(AssignStoreToPriceZoneCommand(StoreId.generate(clock), metro))
        }
    }

    @Test
    fun `a price needs a known item, an existing scope, and a free effective date`() {
        val itemId = item("500007")
        scheduleRetailPrice.handle(command(itemId, defaultZone, "1.00", today.plusDays(1)))

        shouldThrow<PriceAlreadyScheduled> {
            scheduleRetailPrice.handle(command(itemId, defaultZone, "1.10", today.plusDays(1)))
        }
        shouldThrow<ItemNotKnownToPricing> {
            scheduleRetailPrice.handle(command(ItemId.generate(clock), defaultZone, "1.00", today))
        }
        shouldThrow<UnknownPriceZone> {
            scheduleRetailPrice.handle(command(itemId, PriceScope.Zone(PriceZoneCode.of("NOWHERE")), "1.00", today))
        }
        shouldThrow<StoreNotKnownToPricing> {
            scheduleRetailPrice.handle(command(itemId, PriceScope.Store(StoreId.generate(clock)), "1.00", today))
        }
    }

    @Test
    @WithMockUser(authorities = [Roles.MASTER_DATA_EDITOR])
    fun `only a pricing analyst may schedule prices`() {
        shouldThrow<AccessDeniedException> {
            scheduleRetailPrice.handle(command(ItemId.generate(clock), defaultZone, "1.00", today))
        }
    }

    private fun item(number: String): ItemId = ItemId.generate(clock).also { registerPricedItem.handle(it, number) }

    private fun store(): StoreId =
        StoreId.generate(clock).also { assignDefaultPriceZone.handle(AssignDefaultPriceZoneCommand(it)) }

    private fun command(
        itemId: ItemId,
        scope: PriceScope,
        amount: String,
        effectiveFrom: BusinessDate,
    ) = ScheduleRetailPriceCommand(itemId, scope, Money.of(amount, "USD"), effectiveFrom)
}
