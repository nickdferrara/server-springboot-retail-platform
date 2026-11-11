package com.retailplatform.pricing.adapter.`in`.events

import com.retailplatform.catalog.api.events.ItemCreated
import com.retailplatform.catalog.api.events.ItemDiscontinued
import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.pricing.application.DiscontinuePricedItem
import com.retailplatform.pricing.application.RegisterPricedItem
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Keeps pricing's own list of catalog items, so prices are only scheduled for items that can be sold. */
@Component
class CatalogItemListener(
    private val registerPricedItem: RegisterPricedItem,
    private val discontinuePricedItem: DiscontinuePricedItem,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: ItemCreated) {
        processedEvents.once(MODULE, "CatalogItemListener.created", event.eventId) {
            registerPricedItem.handle(event.itemId, event.item.itemNumber)
        }
    }

    @ApplicationModuleListener
    fun on(event: ItemDiscontinued) {
        processedEvents.once(MODULE, "CatalogItemListener.discontinued", event.eventId) {
            discontinuePricedItem.handle(event.itemId, event.itemNumber, event.businessDate)
        }
    }

    private companion object {
        const val MODULE = "pricing"
    }
}
