package com.retailplatform.payables.adapter.`in`.events

import com.retailplatform.payables.application.RecordDeliveryCorrection
import com.retailplatform.payables.application.RecordDeliveryCorrectionCommand
import com.retailplatform.payables.application.RecordDirectDelivery
import com.retailplatform.payables.application.RecordDirectDeliveryCommand
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.TicketUnitChange
import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.receiving.api.events.DirectDeliveryCorrected
import com.retailplatform.receiving.api.events.DirectDeliveryReceived
import com.retailplatform.shared.Money
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.util.Currency

/**
 * Accrues what a vendor delivered straight to a store, credits what its driver took back, and follows any
 * correction to the delivery.
 */
@Component
class DirectDeliveryAccrualListener(
    private val recordDirectDelivery: RecordDirectDelivery,
    private val recordDeliveryCorrection: RecordDeliveryCorrection,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: DirectDeliveryReceived) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            val currency = Currency.getInstance(event.currency)
            val delivery = event.delivery
            recordDirectDelivery.handle(
                RecordDirectDeliveryCommand(
                    ticket =
                        DeliveredTicket(
                            delivery.deliveryId,
                            delivery.receiptNumber,
                            delivery.vendorId,
                            delivery.storeId,
                            delivery.ticketNumber,
                            event.businessDate,
                        ),
                    delivered =
                        event.lines.filter { it.unitsDelivered > 0 }.map {
                            TicketAmountLine.of(it.itemId, it.unitsDelivered, Money.of(it.unitCost, currency))
                        },
                    returned =
                        event.lines.filter { it.unitsReturned > 0 }.map {
                            TicketAmountLine.of(it.itemId, it.unitsReturned, Money.of(it.unitCost, currency))
                        },
                ),
            )
        }
    }

    @ApplicationModuleListener
    fun on(event: DirectDeliveryCorrected) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            val currency = Currency.getInstance(event.currency)
            val delivery = event.delivery
            recordDeliveryCorrection.handle(
                RecordDeliveryCorrectionCommand(
                    corrected =
                        DeliveredTicket(
                            delivery.deliveryId,
                            event.correctionReceiptNumber,
                            delivery.vendorId,
                            delivery.storeId,
                            delivery.ticketNumber,
                            event.businessDate,
                        ),
                    delivered =
                        event.lines.filter { it.unitsDeliveredChange != 0 }.map {
                            TicketUnitChange(it.itemId, it.unitsDeliveredChange, Money.of(it.unitCost, currency))
                        },
                    returned =
                        event.lines.filter { it.unitsReturnedChange != 0 }.map {
                            TicketUnitChange(it.itemId, it.unitsReturnedChange, Money.of(it.unitCost, currency))
                        },
                ),
            )
        }
    }

    private companion object {
        const val MODULE = "payables"
        const val LISTENER = "DirectDeliveryAccrualListener"
    }
}
