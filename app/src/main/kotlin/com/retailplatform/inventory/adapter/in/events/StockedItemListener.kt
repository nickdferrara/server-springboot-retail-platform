package com.retailplatform.inventory.adapter.`in`.events

import com.retailplatform.catalog.api.events.ItemCreated
import com.retailplatform.inventory.application.RegisterStockedItem
import com.retailplatform.inventory.domain.StockedItem
import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.shared.UnitOfMeasure
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Keeps inventory's own list of catalog items and their base units, so stock is always counted in the right unit. */
@Component
class StockedItemListener(
    private val registerStockedItem: RegisterStockedItem,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: ItemCreated) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            registerStockedItem.handle(
                StockedItem(event.itemId, event.item.itemNumber, UnitOfMeasure.of(event.item.baseUnit)),
            )
        }
    }

    private companion object {
        const val MODULE = "inventory"
        const val LISTENER = "StockedItemListener"
    }
}
