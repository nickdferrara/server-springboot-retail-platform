package com.retailplatform.pricing.application

import com.retailplatform.pricing.domain.PriceScope
import com.retailplatform.pricing.domain.PriceZone
import com.retailplatform.pricing.domain.PriceZoneCode
import com.retailplatform.pricing.domain.PricedItem
import com.retailplatform.pricing.domain.RetailPrice
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId

/** Port: where price zones are kept. */
interface PriceZoneRepository {
    fun add(zone: PriceZone)

    fun exists(code: PriceZoneCode): Boolean

    /** Zones ordered by code. */
    fun findAll(): List<PriceZone>
}

/** Port: the catalog items pricing knows about. */
interface PricedItemRepository {
    /** Inserts the item or replaces what is stored for it. */
    fun save(item: PricedItem)

    fun findById(itemId: ItemId): PricedItem?
}

/** Port: where retail prices are kept. */
interface RetailPriceRepository {
    /** Inserts a new price or updates an existing one; a stale version is refused. */
    fun save(price: RetailPrice)

    fun exists(
        itemId: ItemId,
        scope: PriceScope,
        effectiveFrom: BusinessDate,
    ): Boolean

    /** The price for [scope] with the latest effective date on or before [date]. */
    fun findEffective(
        itemId: ItemId,
        scope: PriceScope,
        date: BusinessDate,
    ): RetailPrice?

    /** Every price of an item, oldest effective date first. */
    fun findByItem(itemId: ItemId): List<RetailPrice>

    /**
     * Up to [limit] unannounced prices effective on or before [date], oldest first, locked for this transaction.
     * Prices another transaction has locked are skipped, so two application instances never announce the same one.
     */
    fun claimDue(
        date: BusinessDate,
        limit: Int,
    ): List<RetailPrice>
}
