package com.retailplatform.pricing.application

import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.pricing.domain.ItemNotKnownToPricing
import com.retailplatform.pricing.domain.ItemNotPriceable
import com.retailplatform.pricing.domain.PriceAlreadyScheduled
import com.retailplatform.pricing.domain.PriceScope
import com.retailplatform.pricing.domain.RetailPrice
import com.retailplatform.pricing.domain.RetailPriceId
import com.retailplatform.pricing.domain.StoreNotKnownToPricing
import com.retailplatform.pricing.domain.UnknownPriceZone
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Policy: a price needs an item that may be sold on its effective date, and a zone or store that exists. */
@Component
class PriceablePolicy(
    private val items: PricedItemRepository,
    private val zones: PriceZoneRepository,
    private val assignments: StorePriceZoneRepository,
) {
    fun check(
        itemId: ItemId,
        scope: PriceScope,
        effectiveFrom: BusinessDate,
    ) {
        val item = items.findById(itemId) ?: throw ItemNotKnownToPricing(itemId)
        if (!item.isPriceableOn(effectiveFrom)) throw ItemNotPriceable(item, effectiveFrom)
        requireScopeExists(scope)
    }

    private fun requireScopeExists(scope: PriceScope) {
        when (scope) {
            is PriceScope.Zone -> if (!zones.exists(scope.code)) throw UnknownPriceZone(scope.code)
            is PriceScope.Store -> assignments.findByStore(scope.storeId) ?: throw StoreNotKnownToPricing(scope.storeId)
        }
    }
}

data class ScheduleRetailPriceCommand(
    val itemId: ItemId,
    val scope: PriceScope,
    val price: Money,
    val effectiveFrom: BusinessDate,
)

/** Use case: enter a retail price for a zone or a store, effective today or on a later day. */
@Service
class ScheduleRetailPrice(
    private val prices: RetailPriceRepository,
    private val policy: PriceablePolicy,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.PRICING_ANALYST)
    @Transactional
    fun handle(command: ScheduleRetailPriceCommand): RetailPriceId {
        policy.check(command.itemId, command.scope, command.effectiveFrom)
        if (prices.exists(command.itemId, command.scope, command.effectiveFrom)) {
            throw PriceAlreadyScheduled(command.effectiveFrom)
        }

        val price = RetailPrice.schedule(command.itemId, command.scope, command.price, command.effectiveFrom, clock)
        // A price effective today is in effect at once; later prices are announced by AnnounceEffectivePrices.
        price.announce(BusinessDate.today(clock), clock)
        prices.save(price)
        price.releaseEvents().forEach(events::publishEvent)
        return price.id
    }
}

/**
 * Use case: announce every scheduled price whose effective date has arrived. Run on a schedule; safe to run from
 * several application instances at once and to run again after a failure.
 */
@Service
class AnnounceEffectivePrices(
    private val prices: RetailPriceRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** @return how many prices were announced. More may remain if the batch was full. */
    @Transactional
    fun handle(): Int {
        val today = BusinessDate.today(clock)
        val due = prices.claimDue(today, BATCH_SIZE)
        due.forEach { price ->
            price.announce(today, clock)
            prices.save(price)
            price.releaseEvents().forEach(events::publishEvent)
        }
        return due.size
    }

    companion object {
        const val BATCH_SIZE = 500
    }
}

/** The price a store charges for an item on a day, and the price record it came from. */
data class ResolvedPrice(
    val itemId: ItemId,
    val storeId: StoreId,
    val date: BusinessDate,
    val source: RetailPrice,
)

/** Queries: the prices of an item, and the price in effect at a store. */
@Service
@Transactional(readOnly = true)
class PriceQueries(
    private val prices: RetailPriceRepository,
    private val assignments: StorePriceZoneRepository,
) {
    fun forItem(itemId: ItemId): List<RetailPrice> = prices.findByItem(itemId)

    /**
     * The price [storeId] charges for [itemId] on [date]: the store's own price if it has one in effect, otherwise
     * the price of the store's zone.
     *
     * @throws StoreNotKnownToPricing if the store has no price zone.
     * @throws ResourceNotFound if no price is in effect.
     */
    fun resolve(
        itemId: ItemId,
        storeId: StoreId,
        date: BusinessDate,
    ): ResolvedPrice {
        val zone = assignments.findByStore(storeId) ?: throw StoreNotKnownToPricing(storeId)
        val price =
            prices.findEffective(itemId, PriceScope.Store(storeId), date)
                ?: prices.findEffective(itemId, PriceScope.Zone(zone.zone), date)
                ?: throw ResourceNotFound("Retail price", "for item $itemId at store $storeId on $date")
        return ResolvedPrice(itemId, storeId, date, price)
    }
}
