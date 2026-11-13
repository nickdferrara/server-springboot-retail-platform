package com.retailplatform.inventory.adapter.`in`.events

import com.retailplatform.inventory.application.DeliveredStock
import com.retailplatform.inventory.application.StockDirectDelivery
import com.retailplatform.inventory.application.StockDirectDeliveryCommand
import com.retailplatform.inventory.domain.MovementSource
import com.retailplatform.inventory.domain.StockLocation
import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.receiving.api.events.DirectDeliveryReceived
import com.retailplatform.shared.Money
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.util.Currency

/** Takes a vendor's direct delivery into the store's stock at agreed cost, and takes out what was returned. */
@Component
class DirectDeliveryStockListener(
    private val stockDirectDelivery: StockDirectDelivery,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: DirectDeliveryReceived) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            val currency = Currency.getInstance(event.currency)
            stockDirectDelivery.handle(
                StockDirectDeliveryCommand(
                    store = StockLocation.Store(event.delivery.storeId),
                    businessDate = event.businessDate,
                    source = MovementSource("Receipt ${event.delivery.receiptNumber}", event.eventId),
                    lines =
                        event.lines.map {
                            DeliveredStock(
                                it.itemId,
                                it.unitsDelivered,
                                it.unitsReturned,
                                Money.of(it.unitCost, currency),
                            )
                        },
                ),
            )
        }
    }

    private companion object {
        const val MODULE = "inventory"
        const val LISTENER = "DirectDeliveryStockListener"
    }
}
