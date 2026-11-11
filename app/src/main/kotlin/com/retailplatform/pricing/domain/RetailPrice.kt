package com.retailplatform.pricing.domain

import com.retailplatform.pricing.api.events.PriceChangeEffective
import com.retailplatform.pricing.api.events.RetailPriceFacts
import com.retailplatform.pricing.api.events.RetailPriceScheduled
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.TypedId
import com.retailplatform.shared.TypedIdFactory
import java.time.Clock
import java.util.UUID

@JvmInline
value class RetailPriceId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<RetailPriceId>(::RetailPriceId)
}

/** Where a retail price applies: every store in a zone, or one store, which overrides its zone. */
sealed interface PriceScope {
    data class Zone(
        val code: PriceZoneCode,
    ) : PriceScope

    data class Store(
        val storeId: StoreId,
    ) : PriceScope
}

/** What an item sells for in a zone or a store from a given day until a later price replaces it. */
@Suppress("LongParameterList")
class RetailPrice private constructor(
    val id: RetailPriceId,
    val itemId: ItemId,
    val scope: PriceScope,
    val price: Money,
    val effectiveFrom: BusinessDate,
    announcedOn: BusinessDate?,
    val version: Int,
) : AggregateRoot() {
    /** The day [PriceChangeEffective] was published for this price, if it has been. */
    var announcedOn: BusinessDate? = announcedOn
        private set

    /** Whether the price is in effect on [date] or earlier and has not yet been announced. */
    fun isDueOn(date: BusinessDate): Boolean = announcedOn == null && effectiveFrom <= date

    /** Announces that the price is now in effect, raising [PriceChangeEffective]. Does nothing unless it is due. */
    fun announce(
        today: BusinessDate,
        clock: Clock,
    ) {
        if (!isDueOn(today)) return
        announcedOn = today
        raise(PriceChangeEffective(EventId.generate(clock), clock.instant(), effectiveFrom, facts()))
    }

    private fun facts() =
        RetailPriceFacts(
            itemId = itemId,
            zoneCode = (scope as? PriceScope.Zone)?.code?.value,
            storeId = (scope as? PriceScope.Store)?.storeId,
            price = price.amount,
            currency = price.currency.currencyCode,
            effectiveFrom = effectiveFrom,
        )

    companion object {
        /**
         * Enters a price to take effect on [effectiveFrom], raising [RetailPriceScheduled].
         *
         * @throws RetailPriceNotPositive if [price] is zero or negative.
         * @throws PriceEffectiveInPast if [effectiveFrom] is before today.
         */
        fun schedule(
            itemId: ItemId,
            scope: PriceScope,
            price: Money,
            effectiveFrom: BusinessDate,
            clock: Clock,
        ): RetailPrice {
            val today = BusinessDate.today(clock)
            if (!price.isPositive) throw RetailPriceNotPositive(price)
            if (effectiveFrom < today) throw PriceEffectiveInPast(effectiveFrom, today)

            val retailPrice =
                RetailPrice(RetailPriceId.generate(clock), itemId, scope, price, effectiveFrom, null, version = 0)
            retailPrice.raise(
                RetailPriceScheduled(EventId.generate(clock), clock.instant(), today, retailPrice.facts()),
            )
            return retailPrice
        }

        /** Rebuilds a price from stored state. Raises no events. */
        fun rehydrate(
            id: RetailPriceId,
            itemId: ItemId,
            scope: PriceScope,
            price: Money,
            effectiveFrom: BusinessDate,
            announcedOn: BusinessDate?,
            version: Int,
        ): RetailPrice = RetailPrice(id, itemId, scope, price, effectiveFrom, announcedOn, version)
    }
}

class RetailPriceNotPositive(
    price: Money,
) : BusinessRuleViolation("pricing.price-not-positive", "A retail price must be more than zero: $price")

class PriceEffectiveInPast(
    effectiveFrom: BusinessDate,
    today: BusinessDate,
) : BusinessRuleViolation(
        "pricing.price-effective-in-past",
        "A price cannot take effect on $effectiveFrom, before today ($today)",
    )

class PriceAlreadyScheduled(
    effectiveFrom: BusinessDate,
) : BusinessRuleViolation(
        "pricing.price-already-scheduled",
        "A price for this item and scope is already scheduled for $effectiveFrom",
    )
