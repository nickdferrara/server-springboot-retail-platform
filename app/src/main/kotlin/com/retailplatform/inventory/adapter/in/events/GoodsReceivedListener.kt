package com.retailplatform.inventory.adapter.`in`.events

import com.retailplatform.inventory.application.ReceiveStock
import com.retailplatform.inventory.application.ReceiveStockCommand
import com.retailplatform.inventory.application.ReceivedStock
import com.retailplatform.inventory.domain.MovementSource
import com.retailplatform.inventory.domain.StockLocation
import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.receiving.api.events.GoodsReceived
import com.retailplatform.shared.Money
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/** Takes goods received at a warehouse dock into that warehouse's stock, at purchase order cost. */
@Component
class GoodsReceivedListener(
    private val receiveStock: ReceiveStock,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: GoodsReceived) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            val currency = Currency.getInstance(event.currency)
            receiveStock.handle(
                ReceiveStockCommand(
                    location = StockLocation.Warehouse(event.receipt.warehouseId),
                    businessDate = event.businessDate,
                    source = MovementSource("Receipt ${event.receipt.receiptNumber}", event.eventId),
                    lines =
                        event.lines.map { line ->
                            val casePack = BigDecimal(line.casePack)
                            ReceivedStock(
                                itemId = line.itemId,
                                baseUnits = BigDecimal(line.casesReceived) * casePack,
                                // The cost of one base unit: the case cost spread over the units in the case.
                                unitCost =
                                    Money.of(
                                        line.caseCost.divide(casePack, Money.SCALE, RoundingMode.HALF_EVEN),
                                        currency,
                                    ),
                            )
                        },
                ),
            )
        }
    }

    private companion object {
        const val MODULE = "inventory"
        const val LISTENER = "GoodsReceivedListener"
    }
}
