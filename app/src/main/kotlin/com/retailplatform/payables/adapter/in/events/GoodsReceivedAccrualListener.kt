package com.retailplatform.payables.adapter.`in`.events

import com.retailplatform.payables.application.RecordAccrual
import com.retailplatform.payables.application.RecordAccrualCommand
import com.retailplatform.payables.domain.AccrualLine
import com.retailplatform.payables.domain.AccruedReceipt
import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.receiving.api.events.GoodsReceived
import com.retailplatform.shared.Money
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.util.Currency

/** Accrues what is owed to the vendor for goods a warehouse received, at purchase order cost. */
@Component
class GoodsReceivedAccrualListener(
    private val recordAccrual: RecordAccrual,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: GoodsReceived) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            val currency = Currency.getInstance(event.currency)
            recordAccrual.handle(
                RecordAccrualCommand(
                    receipt =
                        with(event.receipt) {
                            AccruedReceipt(
                                receiptId = receiptId,
                                receiptNumber = receiptNumber,
                                purchaseOrderId = purchaseOrderId,
                                purchaseOrderNumber = purchaseOrderNumber,
                                vendorId = vendorId,
                                warehouseId = warehouseId,
                                receivedOn = event.businessDate,
                            )
                        },
                    lines =
                        event.lines.map {
                            AccrualLine.of(
                                it.purchaseOrderLineNumber,
                                it.itemId,
                                it.casesReceived,
                                Money.of(it.caseCost, currency),
                            )
                        },
                ),
            )
        }
    }

    private companion object {
        const val MODULE = "payables"
        const val LISTENER = "GoodsReceivedAccrualListener"
    }
}
