package com.retailplatform.payables.application

import com.retailplatform.payables.api.events.DirectDeliveryCorrectionRecorded
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.DeliveryAdjustment
import com.retailplatform.payables.domain.DeliveryAdjustmentId
import com.retailplatform.payables.domain.DeliveryAdjustmentKind
import com.retailplatform.payables.domain.DirectDeliveryAccrual
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.TicketInvoiceStatus
import com.retailplatform.payables.domain.TicketUnitChange
import com.retailplatform.payables.domain.VendorCredit
import com.retailplatform.payables.domain.VendorCreditId
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.VendorId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.Currency

/** Port: where accruals for direct store deliveries are kept, one per delivery. */
interface DirectDeliveryAccrualRepository {
    fun save(accrual: DirectDeliveryAccrual)

    fun existsFor(deliveryId: DirectDeliveryId): Boolean

    /** The accrual for a vendor's ticket at a store, if the store has checked the ticket in. */
    fun findFor(ticket: BilledTicket): DirectDeliveryAccrual?

    fun findByDelivery(deliveryId: DirectDeliveryId): DirectDeliveryAccrual?

    /** A vendor's accruals for more than nothing, the oldest delivery first. */
    fun findByVendor(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<DirectDeliveryAccrual>
}

/** Port: where vendor credits are kept, at most one per delivery. */
interface VendorCreditRepository {
    fun save(credit: VendorCredit)

    /** The open credit for a delivery, if it has one. */
    fun findOpenByDelivery(deliveryId: DirectDeliveryId): VendorCredit?

    fun findByIds(ids: List<VendorCreditId>): List<VendorCredit>

    /**
     * The open credits of [vendorIds], the oldest first. Their rows are locked until the transaction ends, so two
     * payment runs proposed at once cannot both deduct a credit.
     */
    fun findOpen(vendorIds: Set<VendorId>): List<VendorCredit>

    /** A vendor's credits for more than nothing, the oldest first. */
    fun findByVendor(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<VendorCredit>
}

/** Port: where adjustments that accounting has to look at are kept. */
interface DeliveryAdjustmentRepository {
    fun save(adjustment: DeliveryAdjustment)

    /** A vendor's adjustments, the oldest first. */
    fun findByVendor(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<DeliveryAdjustment>
}

data class RecordDirectDeliveryCommand(
    val ticket: DeliveredTicket,
    val delivered: List<TicketAmountLine>,
    val returned: List<TicketAmountLine>,
)

/**
 * Use case: record what a direct store delivery means for payables. The units delivered are accrued as owed to
 * the vendor; the units the driver took back become a credit the vendor owes.
 */
@Service
class RecordDirectDelivery(
    private val accruals: DirectDeliveryAccrualRepository,
    private val credits: VendorCreditRepository,
    private val clock: Clock,
) {
    /**
     * Does nothing for a delivery already recorded, so a delivery is never accrued or credited twice. Every
     * delivery gets an accrual, empty if nothing was delivered, which is how a later correction knows the
     * delivery has been recorded.
     */
    @Transactional
    fun handle(command: RecordDirectDeliveryCommand) {
        if (accruals.existsFor(command.ticket.deliveryId)) return

        val currency = (command.delivered + command.returned).first().unitCost.currency
        accruals.save(DirectDeliveryAccrual.record(command.ticket, currency, command.delivered))
        if (command.returned.isNotEmpty()) credits.save(VendorCredit.record(command.ticket, command.returned, clock))
    }
}

/**
 * A correction to a store's receipt as payables sees it: [corrected] is the delivery under the correction's own
 * receipt number and date, and the changes are in units at the ticket's cost.
 */
data class RecordDeliveryCorrectionCommand(
    val corrected: DeliveredTicket,
    val delivered: List<TicketUnitChange>,
    val returned: List<TicketUnitChange>,
)

/**
 * Use case: take account of a correction to a store's receipt of a direct delivery.
 *
 * While the ticket's accrual waits for its invoice, and while its credit has not been used, each is corrected in
 * place, and an invoice waiting in exception is matched again at once. Once the invoice has matched, fewer units
 * delivered become a further vendor credit, and more units delivered become an adjustment for accounting. More
 * units returned always become a credit; fewer units returned, once the credit was used, become an adjustment.
 */
@Service
class RecordDeliveryCorrection(
    private val accruals: DirectDeliveryAccrualRepository,
    private val credits: VendorCreditRepository,
    private val adjustments: DeliveryAdjustmentRepository,
    private val invoices: DirectDeliveryInvoiceRepository,
    private val matching: TicketInvoiceMatching,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * @throws IllegalStateException if the delivery itself has not been recorded yet; the correction is retried
     * once it has.
     */
    @Transactional
    fun handle(command: RecordDeliveryCorrectionCommand) {
        val ticket = command.corrected
        val accrual =
            checkNotNull(accruals.findByDelivery(ticket.deliveryId)) {
                "Delivery ${ticket.deliveryId} has not been recorded yet"
            }
        val effect = CorrectionEffect(accrual.currency)
        delivered(command, accrual, effect)
        returned(command, effect)

        if (effect.creditLines.isNotEmpty()) credits.save(VendorCredit.record(ticket, effect.creditLines, clock))
        effect.adjustments(ticket, clock).forEach(adjustments::save)
        events.publishEvent(effect.recorded(ticket, clock))
        if (accrual.isOpen) rematch(ticket)
    }

    private fun delivered(
        command: RecordDeliveryCorrectionCommand,
        accrual: DirectDeliveryAccrual,
        effect: CorrectionEffect,
    ) {
        if (command.delivered.isEmpty()) return
        if (accrual.isOpen) {
            accrual.correct(command.delivered)
            accruals.save(accrual)
            effect.accrue(command.delivered)
        } else {
            effect.credit(
                command.delivered.filter { it.unitsChange < 0 }.map { it.copy(unitsChange = -it.unitsChange) },
            )
            effect.adjust(
                DeliveryAdjustmentKind.RECEIVED_AFTER_INVOICE,
                command.delivered.filter { it.unitsChange > 0 },
            )
        }
    }

    private fun returned(
        command: RecordDeliveryCorrectionCommand,
        effect: CorrectionEffect,
    ) {
        if (command.returned.isEmpty()) return
        val open = credits.findOpenByDelivery(command.corrected.deliveryId)
        if (open != null && open.canAbsorb(command.returned)) {
            open.correct(command.returned)
            credits.save(open)
            effect.credited(command.returned)
        } else {
            effect.credit(command.returned.filter { it.unitsChange > 0 })
            effect.adjust(
                DeliveryAdjustmentKind.RETURN_REVERSED_AFTER_CREDIT_USED,
                command.returned.filter { it.unitsChange < 0 }.map { it.copy(unitsChange = -it.unitsChange) },
            )
        }
    }

    /** Matches the ticket's invoice again if it is waiting in exception: the corrected receipt may now agree. */
    private fun rematch(ticket: DeliveredTicket) {
        val invoice = invoices.findFor(BilledTicket(ticket.vendorId, ticket.storeId, ticket.ticketNumber)) ?: return
        if (invoice.status is TicketInvoiceStatus.Exception) matching.match(invoice)
    }
}

/** What a correction did to the accrual, the credits, and the adjustments, added up as it is worked through. */
private class CorrectionEffect(
    private val currency: Currency,
) {
    private var accrualChange = Money.zero(currency)
    private var creditChange = Money.zero(currency)
    private val flagged = mutableMapOf<DeliveryAdjustmentKind, Money>()
    private val newCredit = mutableMapOf<ItemId, TicketAmountLine>()

    /** The lines of the further credit the correction calls for; none if it calls for none. */
    val creditLines: List<TicketAmountLine> get() = newCredit.values.toList()

    /** The open accrual was corrected in place by [changes]. */
    fun accrue(changes: List<TicketUnitChange>) {
        accrualChange += total(changes)
    }

    /** An open credit was corrected in place by [changes]. */
    fun credited(changes: List<TicketUnitChange>) {
        creditChange += total(changes)
    }

    /** [units], all positive, are owed by the vendor and go on a further credit. */
    fun credit(units: List<TicketUnitChange>) {
        units.forEach { unit ->
            val sofar = newCredit[unit.itemId]?.units ?: 0
            newCredit[unit.itemId] = TicketAmountLine.of(unit.itemId, sofar + unit.unitsChange, unit.unitCost)
        }
        creditChange += total(units)
    }

    /** [units], all positive, are owed to the vendor with nothing to settle them: accrued and flagged. */
    fun adjust(
        kind: DeliveryAdjustmentKind,
        units: List<TicketUnitChange>,
    ) {
        if (units.isEmpty()) return
        flagged[kind] = (flagged[kind] ?: Money.zero(currency)) + total(units)
        accrualChange += total(units)
    }

    fun adjustments(
        ticket: DeliveredTicket,
        clock: Clock,
    ): List<DeliveryAdjustment> =
        flagged.map { (kind, amount) -> DeliveryAdjustment(DeliveryAdjustmentId.generate(clock), ticket, kind, amount) }

    fun recorded(
        ticket: DeliveredTicket,
        clock: Clock,
    ) = DirectDeliveryCorrectionRecorded(
        eventId = EventId.generate(clock),
        occurredAt = clock.instant(),
        businessDate = ticket.receivedOn,
        vendorId = ticket.vendorId,
        storeId = ticket.storeId,
        ticketNumber = ticket.ticketNumber,
        correctionReceiptNumber = ticket.receiptNumber,
        inventoryChange = (accrualChange - creditChange).amount,
        accrualChange = accrualChange.amount,
        creditChange = creditChange.amount,
        currency = currency.currencyCode,
    )

    private fun total(changes: List<TicketUnitChange>): Money =
        changes
            .map {
                it.amount
            }.fold(Money.zero(currency), Money::plus)
}

/** Queries: what is accrued for a vendor's direct deliveries, and the credits the vendor owes. */
@Service
@Transactional(readOnly = true)
class DirectDeliveryPayablesQueries(
    private val accruals: DirectDeliveryAccrualRepository,
    private val credits: VendorCreditRepository,
    private val adjustments: DeliveryAdjustmentRepository,
) {
    fun adjustments(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<DeliveryAdjustment> = adjustments.findByVendor(vendorId, query)

    fun accruals(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<DirectDeliveryAccrual> = accruals.findByVendor(vendorId, query)

    fun credits(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<VendorCredit> = credits.findByVendor(vendorId, query)
}
