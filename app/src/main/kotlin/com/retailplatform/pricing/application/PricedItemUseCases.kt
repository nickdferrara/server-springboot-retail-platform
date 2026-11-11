package com.retailplatform.pricing.application

import com.retailplatform.pricing.domain.PricedItem
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Use case: remember that a catalog item exists, so it can be priced. */
@Service
class RegisterPricedItem(
    private val items: PricedItemRepository,
) {
    @Transactional
    fun handle(
        itemId: ItemId,
        itemNumber: String,
    ) {
        if (items.findById(itemId) == null) items.save(PricedItem(itemId, itemNumber))
    }
}

/** Use case: record the last day an item may be sold, so no price is scheduled after it. */
@Service
class DiscontinuePricedItem(
    private val items: PricedItemRepository,
) {
    @Transactional
    fun handle(
        itemId: ItemId,
        itemNumber: String,
        lastDay: BusinessDate,
    ) {
        val item = items.findById(itemId) ?: PricedItem(itemId, itemNumber)
        items.save(item.discontinue(lastDay))
    }
}
