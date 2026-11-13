package com.retailplatform.payables.domain

import com.retailplatform.payables.api.events.DirectDeliveryExceptionFacts
import com.retailplatform.payables.api.events.DirectDeliveryInvoiceFacts
import com.retailplatform.payables.api.events.DirectDeliveryInvoiceMatchException
import com.retailplatform.payables.api.events.DirectDeliveryInvoiceMatched
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import java.time.Clock

/** The delivery ticket an invoice bills: a vendor's ticket number at one store. */
data class BilledTicket(
    val vendorId: VendorId,
    val storeId: StoreId,
    val ticketNumber: String,
)

/** What a vendor's invoice says about one delivery ticket: its number and date, and the units it bills by item. */
data class TicketInvoiceDetails(
    val ticket: BilledTicket,
    val number: VendorInvoiceNumber,
    val invoiceDate: BusinessDate,
    val lines: List<TicketAmountLine>,
)

/** Why an invoice does not agree with the store's receipt of its ticket or the agreed costs. */
enum class TicketExceptionReason {
    /** The store has not checked the ticket in, or nothing on it is waiting for an invoice. */
    TICKET_NOT_RECEIVED,

    /** The invoice bills an item the store did not receive on the ticket. */
    ITEM_NOT_ON_TICKET,

    /** The store received an item on the ticket that the invoice does not bill. */
    ITEM_NOT_BILLED,

    /** The units billed are not the units the store received. */
    QUANTITY_DIFFERS,

    /** The unit cost differs from the agreed cost by more than the tolerance. */
    PRICE_OVER_TOLERANCE,
}

/** One reason an invoice did not match. [itemId] is `null` when the reason is about the whole ticket. */
data class TicketException(
    val itemId: ItemId?,
    val reason: TicketExceptionReason,
    val detail: String,
)

/** Whether a direct delivery invoice agreed with its ticket when it was last matched. */
sealed interface TicketInvoiceStatus {
    /** A short name for the status, stored and shown to users. */
    val code: String

    /** Agreed on [matchedOn]; [override] is set when a price exception was accepted rather than resolved. */
    data class Matched(
        val matchedOn: BusinessDate,
        val override: MatchOverride? = null,
    ) : TicketInvoiceStatus {
        override val code = "MATCHED"
    }

    /**
     * Paid short on [matchedOn]: the store's count stood, so only the units received are paid for and
     * [deduction] is held back from the vendor. [approval] says who decided and why.
     */
    data class ShortPaid(
        val matchedOn: BusinessDate,
        val approval: MatchOverride,
        val deduction: Money,
    ) : TicketInvoiceStatus {
        override val code = "SHORT_PAID"
    }

    /** Did not agree, for [exceptions]; waiting for a buyer to resolve it. */
    data class Exception(
        val exceptions: List<TicketException>,
    ) : TicketInvoiceStatus {
        override val code = "EXCEPTION"
    }
}

/** What comparing an invoice with its ticket found. A caller must handle both outcomes. */
sealed interface TicketMatchOutcome {
    /** Every line agreed. [accruedAmount] is what the ticket was accrued at, agreed cost. */
    data class Matched(
        val accruedAmount: Money,
    ) : TicketMatchOutcome

    /** Something did not agree. */
    data class Exceptions(
        val exceptions: List<TicketException>,
    ) : TicketMatchOutcome
}

/**
 * The two-way match for a direct store delivery, which has no purchase order: the invoice agrees when it bills
 * exactly the items and units the store received on the ticket, each at a unit cost within tolerance of the cost
 * agreed for that store.
 */
class TwoWayMatch(
    private val tolerance: MatchTolerance,
) {
    /**
     * Compares [invoice] with the [accrual] recorded when the store checked the ticket in; `null` if it has not.
     * With [acceptPrice] a unit cost outside tolerance is accepted, as an approved override does. Wrong items,
     * wrong quantities, and a cost in another currency are never accepted.
     */
    fun match(
        invoice: DirectDeliveryInvoice,
        accrual: DirectDeliveryAccrual?,
        acceptPrice: Boolean = false,
    ): TicketMatchOutcome {
        if (accrual == null || !accrual.isOpen) {
            val notReceived =
                TicketException(null, TicketExceptionReason.TICKET_NOT_RECEIVED, "No receipt of this ticket is waiting")
            return TicketMatchOutcome.Exceptions(listOf(notReceived))
        }
        val received = accrual.lines.associateBy { it.itemId }
        val billed = invoice.lines.map { it.itemId }.toSet()
        val exceptions =
            invoice.lines.mapNotNull { exceptionFor(it, received[it.itemId], acceptPrice) } +
                accrual.lines.filterNot { it.itemId in billed }.map {
                    TicketException(it.itemId, TicketExceptionReason.ITEM_NOT_BILLED, "Received ${it.units} units")
                }
        return if (exceptions.isEmpty()) {
            TicketMatchOutcome.Matched(accrual.amount)
        } else {
            TicketMatchOutcome.Exceptions(exceptions)
        }
    }

    /**
     * What [invoice] is worth when the store's count stands: for each item billed, the units the store received
     * or the units billed, whichever is fewer, at the unit cost billed.
     *
     * @throws ShortPayNotPossible if the ticket has no accrual waiting, a billed price is outside tolerance, or
     * the store's count leaves nothing to hold back or nothing to pay.
     */
    fun shortPay(
        invoice: DirectDeliveryInvoice,
        accrual: DirectDeliveryAccrual?,
    ): ShortPayment {
        val number = invoice.details.number
        if (accrual == null || !accrual.isOpen) throw ShortPayNotPossible(number, "the ticket has no receipt waiting")
        val received = accrual.lines.associateBy { it.itemId }
        val priced =
            invoice.lines.all { billed ->
                val agreed = received[billed.itemId]?.unitCost
                agreed == null ||
                    (
                        agreed.currency == billed.unitCost.currency &&
                            tolerance.allows(agreed, billed.unitCost, billed.units)
                    )
            }
        val payable =
            invoice.lines
                .map { it.unitCost * minOf(it.units, received[it.itemId]?.units ?: 0) }
                .reduce(Money::plus)
        val reason =
            when {
                !priced -> "a billed price is outside tolerance"
                payable.isZero -> "none of the units billed were received"
                payable >= invoice.amount -> "it bills no more than was received"
                else -> null
            }
        if (reason != null) throw ShortPayNotPossible(number, reason)
        return ShortPayment(payable, invoice.amount - payable, accrual.amount)
    }

    private fun exceptionFor(
        billed: TicketAmountLine,
        received: TicketAmountLine?,
        acceptPrice: Boolean,
    ): TicketException? =
        when {
            received == null -> {
                TicketException(billed.itemId, TicketExceptionReason.ITEM_NOT_ON_TICKET, "Billed ${billed.units} units")
            }

            received.units != billed.units -> {
                TicketException(
                    billed.itemId,
                    TicketExceptionReason.QUANTITY_DIFFERS,
                    "Billed ${billed.units} units; received ${received.units}",
                )
            }

            received.unitCost.currency != billed.unitCost.currency ||
                (!acceptPrice && !tolerance.allows(received.unitCost, billed.unitCost, billed.units)) -> {
                TicketException(
                    billed.itemId,
                    TicketExceptionReason.PRICE_OVER_TOLERANCE,
                    "Billed ${billed.unitCost} a unit; agreed cost is ${received.unitCost}",
                )
            }

            else -> {
                null
            }
        }
}

/**
 * An invoice paid for what the store received: [payable] is owed, [deduction] is held back from what the vendor
 * billed, and [accruedAmount] is what the ticket was accrued at.
 */
data class ShortPayment(
    val payable: Money,
    val deduction: Money,
    val accruedAmount: Money,
)

/** A vendor's invoice for one direct store delivery ticket, and the outcome of matching it. */
class DirectDeliveryInvoice private constructor(
    val id: InvoiceId,
    val details: TicketInvoiceDetails,
    val enteredBy: String,
    status: TicketInvoiceStatus,
    val version: Int,
) : AggregateRoot() {
    var status: TicketInvoiceStatus = status
        private set

    val lines: List<TicketAmountLine> get() = details.lines

    /** The sum of the lines. */
    val amount: Money get() = lines.map { it.amount }.reduce(Money::plus)

    /**
     * Checks that the invoice can still be matched, and that [override], if given, comes from someone else than
     * the person who entered it.
     *
     * @throws InvoiceAlreadyMatched if the invoice has matched before.
     * @throws OverrideByInvoiceCreator if [override] was approved by the person who entered the invoice.
     */
    fun requireMatchable(override: MatchOverride? = null) {
        if (status !is TicketInvoiceStatus.Exception) throw InvoiceAlreadyMatched(details.number)
        if (override?.approvedBy == enteredBy) throw OverrideByInvoiceCreator(details.number)
    }

    /**
     * Records the [outcome] of a two-way match, raising [DirectDeliveryInvoiceMatched] or
     * [DirectDeliveryInvoiceMatchException]. [override] is given when the match accepted a price exception.
     */
    fun record(
        outcome: TicketMatchOutcome,
        clock: Clock,
        override: MatchOverride? = null,
    ) {
        requireMatchable(override)
        val today = BusinessDate.today(clock)
        when (outcome) {
            is TicketMatchOutcome.Matched -> {
                status = TicketInvoiceStatus.Matched(today, override)
                raise(
                    DirectDeliveryInvoiceMatched(
                        eventId = EventId.generate(clock),
                        occurredAt = clock.instant(),
                        businessDate = today,
                        invoice = facts(),
                        invoiceAmount = amount.amount,
                        accruedAmount = outcome.accruedAmount.amount,
                        costVariance = (amount - outcome.accruedAmount).amount,
                        currency = amount.currency.currencyCode,
                    ),
                )
            }

            is TicketMatchOutcome.Exceptions -> {
                status = TicketInvoiceStatus.Exception(outcome.exceptions)
                raise(
                    DirectDeliveryInvoiceMatchException(
                        eventId = EventId.generate(clock),
                        occurredAt = clock.instant(),
                        businessDate = today,
                        invoice = facts(),
                        exceptions = outcome.exceptions.map { DirectDeliveryExceptionFacts(it.itemId, it.reason.name) },
                    ),
                )
            }
        }
    }

    /**
     * Pays the invoice short, as [payment] says, on the word of [approval], and raises
     * [DirectDeliveryInvoiceMatched] with the deduction.
     *
     * @throws InvoiceAlreadyMatched if the invoice is already payable.
     * @throws OverrideByInvoiceCreator if [approval] comes from the person who entered the invoice.
     */
    fun shortPay(
        payment: ShortPayment,
        approval: MatchOverride,
        clock: Clock,
    ) {
        requireMatchable(approval)
        val today = BusinessDate.today(clock)
        status = TicketInvoiceStatus.ShortPaid(today, approval, payment.deduction)
        raise(
            DirectDeliveryInvoiceMatched(
                eventId = EventId.generate(clock),
                occurredAt = clock.instant(),
                businessDate = today,
                invoice = facts(),
                invoiceAmount = amount.amount,
                accruedAmount = payment.accruedAmount.amount,
                costVariance = (payment.payable - payment.accruedAmount).amount,
                currency = amount.currency.currencyCode,
                deductionAmount = payment.deduction.amount,
            ),
        )
    }

    /**
     * The invoice as a voucher needs it: what is owed on it, which is less than it bills if it was paid short.
     *
     * @throws IllegalStateException unless the invoice is payable.
     */
    fun matched(): MatchedInvoice {
        val invoice =
            VoucheredInvoice(id, details.number, details.ticket.vendorId, purchaseOrderId = null, details.invoiceDate)
        return when (val payable = status) {
            is TicketInvoiceStatus.Matched -> {
                MatchedInvoice(invoice, amount, payable.matchedOn, payable.override?.approvedBy)
            }

            is TicketInvoiceStatus.ShortPaid -> {
                MatchedInvoice(invoice, amount - payable.deduction, payable.matchedOn, payable.approval.approvedBy)
            }

            is TicketInvoiceStatus.Exception -> {
                error("Invoice ${details.number} has not matched")
            }
        }
    }

    private fun facts() =
        DirectDeliveryInvoiceFacts(
            id,
            details.number.value,
            details.ticket.vendorId,
            details.ticket.storeId,
            details.ticket.ticketNumber,
        )

    companion object {
        /**
         * Enters a vendor's invoice for a ticket. It starts unmatched with no exceptions listed; the caller
         * matches it at once.
         *
         * @throws InvoiceLineRepeated if two lines bill the same item.
         * @throws InvoiceInMixedCurrencies if the lines are not all in one currency.
         */
        fun enter(
            details: TicketInvoiceDetails,
            enteredBy: String,
            clock: Clock,
        ): DirectDeliveryInvoice {
            require(details.lines.isNotEmpty()) { "An invoice has at least one line" }
            val items = details.lines.map { it.itemId }
            if (items.size != items.toSet().size) throw InvoiceLineRepeated(details.number)
            val currencies = details.lines.map { it.unitCost.currency }.toSet()
            if (currencies.size > 1) throw InvoiceInMixedCurrencies()
            return DirectDeliveryInvoice(
                InvoiceId.generate(clock),
                details,
                enteredBy,
                TicketInvoiceStatus.Exception(emptyList()),
                version = 0,
            )
        }

        /** Rebuilds an invoice from stored state. Raises no events. */
        fun rehydrate(
            id: InvoiceId,
            details: TicketInvoiceDetails,
            enteredBy: String,
            status: TicketInvoiceStatus,
            version: Int,
        ): DirectDeliveryInvoice = DirectDeliveryInvoice(id, details, enteredBy, status, version)
    }
}

class TicketAlreadyInvoiced(
    ticketNumber: String,
) : BusinessRuleViolation(
        "payables.ticket-already-invoiced",
        "An invoice for ticket $ticketNumber from this vendor at this store has already been entered",
    )

class ShortPayNotPossible(
    number: VendorInvoiceNumber,
    why: String,
) : BusinessRuleViolation(
        "payables.short-pay-not-possible",
        "Invoice $number cannot be paid short: $why",
    )
