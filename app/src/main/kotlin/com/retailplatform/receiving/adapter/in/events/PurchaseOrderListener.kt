package com.retailplatform.receiving.adapter.`in`.events

import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.purchasing.api.events.PurchaseOrderCancelled
import com.retailplatform.purchasing.api.events.PurchaseOrderSent
import com.retailplatform.receiving.application.CancelExpectedDelivery
import com.retailplatform.receiving.application.ExpectDelivery
import com.retailplatform.receiving.domain.ExpectedDelivery
import com.retailplatform.receiving.domain.ExpectedLine
import com.retailplatform.receiving.domain.ReceivedAgainst
import com.retailplatform.shared.Money
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.util.Currency

/** Keeps the dock's list of purchase orders to expect, from the orders purchasing sends and cancels. */
@Component
class PurchaseOrderListener(
    private val expectDelivery: ExpectDelivery,
    private val cancelExpectedDelivery: CancelExpectedDelivery,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: PurchaseOrderSent) {
        processedEvents.once(MODULE, "PurchaseOrderListener.sent", event.eventId) {
            val order = event.purchaseOrder
            val currency = Currency.getInstance(order.currency)
            expectDelivery.handle(
                ExpectedDelivery.expect(
                    against =
                        ReceivedAgainst(
                            order.purchaseOrderId,
                            order.purchaseOrderNumber,
                            order.vendorId,
                            order.warehouseId,
                        ),
                    expectedOn = order.expectedOn,
                    lines =
                        order.lines.map {
                            ExpectedLine(
                                lineNumber = it.lineNumber,
                                itemId = it.itemId,
                                vendorSku = it.vendorSku,
                                casePack = it.casePack,
                                caseCost = Money.of(it.caseCost, currency),
                                casesOrdered = it.cases,
                            )
                        },
                ),
            )
        }
    }

    @ApplicationModuleListener
    fun on(event: PurchaseOrderCancelled) {
        processedEvents.once(MODULE, "PurchaseOrderListener.cancelled", event.eventId) {
            cancelExpectedDelivery.handle(event.purchaseOrderId)
        }
    }

    private companion object {
        const val MODULE = "receiving"
    }
}
