package com.retailplatform.pricing.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.ItemId

/** What pricing knows about a catalog item: that it exists, and the last day it may be sold. */
data class PricedItem(
    val itemId: ItemId,
    val itemNumber: String,
    val discontinuedOn: BusinessDate? = null,
) {
    /** Whether a retail price may take effect on [date]. */
    fun isPriceableOn(date: BusinessDate): Boolean = discontinuedOn == null || date <= discontinuedOn

    fun discontinue(lastDay: BusinessDate): PricedItem = copy(discontinuedOn = lastDay)
}

class ItemNotKnownToPricing(
    itemId: ItemId,
) : BusinessRuleViolation("pricing.unknown-item", "Item $itemId is not in the catalog")

class ItemNotPriceable(
    item: PricedItem,
    date: BusinessDate,
) : BusinessRuleViolation(
        "pricing.item-discontinued",
        "Item ${item.itemNumber} is discontinued after ${item.discontinuedOn} and cannot take a price on $date",
    )
