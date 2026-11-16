package com.retailplatform.payables.domain

import com.retailplatform.payables.api.events.DirectDeliveryLateBillAccepted
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.Money
import com.retailplatform.shared.TypedId
import com.retailplatform.shared.TypedIdFactory
import java.time.Clock
import java.util.UUID

/** Identifies a late bill. */
@JvmInline
value class LateBillId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<LateBillId>(::LateBillId)
}

/** What a late bill says: the ticket it is for, the vendor's document number and date, and the total billed. */
data class LateBillDetails(
    val ticket: BilledTicket,
    val number: VendorInvoiceNumber,
    val billDate: BusinessDate,
    val amount: Money,
)

/** Where a late bill stands. */
sealed interface LateBillStatus {
    /** A short name for the status, stored and shown to users. */
    val code: String

    /** Waiting for accounting to decide. */
    data object Open : LateBillStatus {
        override val code = "OPEN"
    }

    /** Accepted on [settledOn]: the vendor is owed the bill and a voucher was released. [decision] says who and why. */
    data class Paid(
        val settledOn: BusinessDate,
        val decision: MatchOverride,
    ) : LateBillStatus {
        override val code = "PAID"
    }

    /** Refused on [settledOn]: nothing is owed on it. [decision] says who and why. */
    data class Rejected(
        val settledOn: BusinessDate,
        val decision: MatchOverride,
    ) : LateBillStatus {
        override val code = "REJECTED"
    }
}

/**
 * A further bill from a vendor for a delivery ticket whose invoice is already payable. The ticket is closed, so
 * nothing is matched and nothing is accrued for it: accounting either rejects the bill or accepts it for payment,
 * once and whole.
 */
data class LateBill(
    val id: LateBillId,
    val details: LateBillDetails,
    val recordedBy: String,
    val recordedOn: BusinessDate,
    val status: LateBillStatus = LateBillStatus.Open,
    val version: Int = 0,
) {
    val amount: Money get() = details.amount

    /**
     * The bill accepted for payment on the word of [decision].
     *
     * @throws LateBillAlreadySettled if it was paid or rejected before.
     * @throws LateBillSettledByRecorder if [decision] comes from the person who recorded it.
     */
    fun pay(
        decision: MatchOverride,
        clock: Clock,
    ): LateBill {
        requireSettleable(decision)
        return copy(status = LateBillStatus.Paid(BusinessDate.today(clock), decision))
    }

    /**
     * The bill refused on the word of [decision].
     *
     * @throws LateBillAlreadySettled if it was paid or rejected before.
     * @throws LateBillSettledByRecorder if [decision] comes from the person who recorded it.
     */
    fun reject(
        decision: MatchOverride,
        clock: Clock,
    ): LateBill {
        requireSettleable(decision)
        return copy(status = LateBillStatus.Rejected(BusinessDate.today(clock), decision))
    }

    /**
     * The bill as a voucher needs it: it stands in for an invoice.
     *
     * @throws IllegalStateException unless the bill was accepted for payment.
     */
    fun payable(): MatchedInvoice {
        val paid = status as? LateBillStatus.Paid ?: error("Late bill ${details.number} is not to be paid")
        val document =
            VoucheredInvoice(
                InvoiceId.of(id.value),
                details.number,
                details.ticket.vendorId,
                purchaseOrderId = null,
                details.billDate,
            )
        return MatchedInvoice(document, amount, paid.settledOn, paid.decision.approvedBy)
    }

    /**
     * The event that tells the ledger the bill is owed.
     *
     * @throws IllegalStateException unless the bill was accepted for payment.
     */
    fun accepted(clock: Clock): DirectDeliveryLateBillAccepted {
        val paid = status as? LateBillStatus.Paid ?: error("Late bill ${details.number} is not to be paid")
        return DirectDeliveryLateBillAccepted(
            eventId = EventId.generate(clock),
            occurredAt = clock.instant(),
            businessDate = paid.settledOn,
            lateBillId = id.value,
            vendorId = details.ticket.vendorId,
            storeId = details.ticket.storeId,
            ticketNumber = details.ticket.ticketNumber,
            vendorInvoiceNumber = details.number.value,
            amount = amount.amount,
            currency = amount.currency.currencyCode,
        )
    }

    private fun requireSettleable(decision: MatchOverride) {
        if (status != LateBillStatus.Open) throw LateBillAlreadySettled(details.number)
        if (decision.approvedBy == recordedBy) throw LateBillSettledByRecorder(details.number)
    }

    companion object {
        /**
         * Records a late bill as [recordedBy] entered it; it waits for accounting.
         *
         * @throws IllegalArgumentException unless the amount billed is more than nothing.
         */
        fun record(
            details: LateBillDetails,
            recordedBy: String,
            clock: Clock,
        ): LateBill {
            require(details.amount.isPositive) { "A late bill bills more than nothing" }
            return LateBill(LateBillId.generate(clock), details, recordedBy, BusinessDate.today(clock))
        }
    }
}

class TicketNotClosed(
    ticketNumber: String,
) : BusinessRuleViolation(
        "payables.ticket-not-closed",
        "Ticket $ticketNumber has no payable invoice yet; enter or resolve its invoice instead of a late bill",
    )

class LateBillInAnotherCurrency(
    number: VendorInvoiceNumber,
) : BusinessRuleViolation(
        "payables.late-bill-in-another-currency",
        "Late bill $number is not in the currency of the ticket's invoice",
    )

class LateBillAlreadySettled(
    number: VendorInvoiceNumber,
) : BusinessRuleViolation(
        "payables.late-bill-already-settled",
        "Late bill $number has already been paid or rejected",
    )

class LateBillSettledByRecorder(
    number: VendorInvoiceNumber,
) : BusinessRuleViolation(
        "payables.late-bill-settled-by-recorder",
        "Late bill $number cannot be paid or rejected by the person who recorded it",
    )
