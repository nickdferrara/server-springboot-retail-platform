package com.retailplatform.pricing.adapter.`in`.events

import com.retailplatform.locations.api.events.StoreOpened
import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.pricing.application.AssignDefaultPriceZone
import com.retailplatform.pricing.application.AssignDefaultPriceZoneCommand
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Reacts to a store opening by giving it a price zone. */
@Component
class StoreOpenedListener(
    private val assignDefaultPriceZone: AssignDefaultPriceZone,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: StoreOpened) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            assignDefaultPriceZone.handle(AssignDefaultPriceZoneCommand(event.storeId))
        }
    }

    private companion object {
        const val MODULE = "pricing"
        const val LISTENER = "StoreOpenedListener"
    }
}
