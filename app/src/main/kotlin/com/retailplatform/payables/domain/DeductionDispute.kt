package com.retailplatform.payables.domain

import com.retailplatform.payables.api.events.DeductionDisputeConceded
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.Money
import com.retailplatform.shared.TypedId
import com.retailplatform.shared.TypedIdFactory
import java.time.Clock
import java.util.UUID

/** Identifies a vendor's dispute of a deduction. */
@JvmInline
value class DeductionDisputeId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<DeductionDisputeId>(::DeductionDisputeId)
}

/**
 * The deduction a vendor disputes: the notice that told the vendor of it, the ticket, the [amount] held back,
 * and [deductedBy], who paid the invoice short.
 */
data class DisputedDeduction(
    val noticeId: DeductionNoticeId,
    val noticeNumber: DeductionNoticeNumber,
    val ticket: BilledTicket,
    val amount: Money,
    val deductedBy: String,
)

/** What the vendor says: it arrived on [receivedOn] under the vendor's [reference], and claims [statement]. */
@ConsistentCopyVisibility
data class DisputeClaim private constructor(
    val receivedOn: BusinessDate,
    val reference: VendorInvoiceNumber,
    val statement: String,
) {
    companion object {
        const val MAX_STATEMENT_LENGTH = 200

        /** @throws IllegalArgumentException if [statement] is blank or longer than [MAX_STATEMENT_LENGTH] trimmed. */
        fun of(
            receivedOn: BusinessDate,
            reference: VendorInvoiceNumber,
            statement: String,
        ): DisputeClaim {
            val trimmed = statement.trim()
            require(trimmed.isNotEmpty()) { "A dispute says what the vendor claims" }
            require(trimmed.length <= MAX_STATEMENT_LENGTH) {
                "A claim has at most $MAX_STATEMENT_LENGTH characters"
            }
            return DisputeClaim(receivedOn, reference, trimmed)
        }
    }
}

/** Where a dispute stands. */
sealed interface DisputeStatus {
    /** A short name for the status, stored and shown to users. */
    val code: String

    /** Waiting for accounting to decide. */
    data object Open : DisputeStatus {
        override val code = "OPEN"
    }

    /** Decided on [decidedOn]: the deduction stands in full. [decision] says who and why. */
    data class Upheld(
        val decidedOn: BusinessDate,
        val decision: MatchOverride,
    ) : DisputeStatus {
        override val code = "UPHELD"
    }

    /**
     * Decided on [decidedOn]: [amount] of the deduction is given back to the vendor and a voucher was released
     * for it; the rest stands. [decision] says who and why.
     */
    data class Conceded(
        val decidedOn: BusinessDate,
        val decision: MatchOverride,
        val amount: Money,
    ) : DisputeStatus {
        override val code = "CONCEDED"
    }
}

/**
 * A vendor's dispute of a deduction. Accounting decides it once: the deduction is upheld, or some or all of it
 * is conceded and paid. The person who made the deduction does not decide the challenge to it.
 */
data class DeductionDispute(
    val id: DeductionDisputeId,
    val deduction: DisputedDeduction,
    val claim: DisputeClaim,
    val recordedBy: String,
    val recordedOn: BusinessDate,
    val status: DisputeStatus = DisputeStatus.Open,
    val version: Int = 0,
) {
    /**
     * The dispute decided against the vendor, on the word of [decision].
     *
     * @throws DisputeAlreadyDecided if it was decided before.
     * @throws DisputeDecidedByDeductor if [decision] comes from the person who paid the invoice short.
     */
    fun uphold(
        decision: MatchOverride,
        clock: Clock,
    ): DeductionDispute {
        requireDecidable(decision)
        return copy(status = DisputeStatus.Upheld(BusinessDate.today(clock), decision))
    }

    /**
     * The dispute decided for the vendor to the extent of [amount], on the word of [decision].
     *
     * @throws DisputeAlreadyDecided if it was decided before.
     * @throws DisputeDecidedByDeductor if [decision] comes from the person who paid the invoice short.
     * @throws ConcessionOutOfRange unless [amount] is more than nothing, no more than the deduction, and in its
     * currency.
     */
    fun concede(
        amount: Money,
        decision: MatchOverride,
        clock: Clock,
    ): DeductionDispute {
        requireDecidable(decision)
        val held = deduction.amount
        if (amount.currency != held.currency || !amount.isPositive || amount > held) {
            throw ConcessionOutOfRange(deduction.noticeNumber)
        }
        return copy(status = DisputeStatus.Conceded(BusinessDate.today(clock), decision, amount))
    }

    /**
     * The concession as a voucher needs it: the vendor's claim stands in for an invoice.
     *
     * @throws IllegalStateException unless the dispute was conceded.
     */
    fun payable(): MatchedInvoice {
        val conceded = conceded()
        val document =
            VoucheredInvoice(
                InvoiceId.of(id.value),
                claim.reference,
                deduction.ticket.vendorId,
                purchaseOrderId = null,
                claim.receivedOn,
            )
        return MatchedInvoice(document, conceded.amount, conceded.decidedOn, conceded.decision.approvedBy)
    }

    /**
     * The event that tells the ledger what was conceded.
     *
     * @throws IllegalStateException unless the dispute was conceded.
     */
    fun concession(clock: Clock): DeductionDisputeConceded {
        val conceded = conceded()
        return DeductionDisputeConceded(
            eventId = EventId.generate(clock),
            occurredAt = clock.instant(),
            businessDate = conceded.decidedOn,
            disputeId = id.value,
            deductionNoticeNumber = deduction.noticeNumber.value,
            vendorId = deduction.ticket.vendorId,
            storeId = deduction.ticket.storeId,
            ticketNumber = deduction.ticket.ticketNumber,
            vendorReference = claim.reference.value,
            amount = conceded.amount.amount,
            currency = conceded.amount.currency.currencyCode,
        )
    }

    private fun conceded(): DisputeStatus.Conceded =
        status as? DisputeStatus.Conceded ?: error("Dispute of notice ${deduction.noticeNumber} was not conceded")

    private fun requireDecidable(decision: MatchOverride) {
        if (status != DisputeStatus.Open) throw DisputeAlreadyDecided(deduction.noticeNumber)
        if (decision.approvedBy == deduction.deductedBy) throw DisputeDecidedByDeductor(deduction.noticeNumber)
    }

    companion object {
        /**
         * Records that the vendor disputes [deduction], as [recordedBy] entered it; it waits for accounting.
         *
         * @throws DisputeDateInvalid if the claim arrived after today.
         */
        fun record(
            deduction: DisputedDeduction,
            claim: DisputeClaim,
            recordedBy: String,
            clock: Clock,
        ): DeductionDispute {
            val today = BusinessDate.today(clock)
            if (claim.receivedOn > today) throw DisputeDateInvalid(deduction.noticeNumber)
            return DeductionDispute(DeductionDisputeId.generate(clock), deduction, claim, recordedBy, today)
        }
    }
}

class DeductionAlreadyDisputed(
    number: DeductionNoticeNumber,
) : BusinessRuleViolation(
        "payables.deduction-already-disputed",
        "The deduction on notice $number has already been disputed",
    )

class DisputeDateInvalid(
    number: DeductionNoticeNumber,
) : BusinessRuleViolation(
        "payables.dispute-date-invalid",
        "A dispute of notice $number cannot have arrived before the deduction was made or after today",
    )

class DisputeAlreadyDecided(
    number: DeductionNoticeNumber,
) : BusinessRuleViolation(
        "payables.dispute-already-decided",
        "The dispute of notice $number has already been decided",
    )

class DisputeDecidedByDeductor(
    number: DeductionNoticeNumber,
) : BusinessRuleViolation(
        "payables.dispute-decided-by-deductor",
        "The dispute of notice $number cannot be decided by the person who paid the invoice short",
    )

class ConcessionOutOfRange(
    number: DeductionNoticeNumber,
) : BusinessRuleViolation(
        "payables.concession-out-of-range",
        "What is conceded on notice $number must be more than nothing and no more than was held back",
    )
