package com.retailplatform.payables.domain

import com.retailplatform.payables.api.events.InvoiceExceptionFacts
import com.retailplatform.payables.api.events.InvoiceFacts
import com.retailplatform.payables.api.events.InvoiceMatchException
import com.retailplatform.payables.api.events.InvoiceMatched
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.VendorId
import java.time.Clock

/** The number a vendor put on its invoice: not blank, at most 30 characters. */
@JvmInline
value class VendorInvoiceNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 30

        /** @throws IllegalArgumentException if [value] is blank or longer than [MAX_LENGTH] once trimmed. */
        fun of(value: String): VendorInvoiceNumber {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "A vendor invoice number cannot be blank" }
            require(trimmed.length <= MAX_LENGTH) { "A vendor invoice number has at most $MAX_LENGTH characters" }
            return VendorInvoiceNumber(trimmed)
        }
    }
}

/** What a vendor bills for one purchase order line: [cases] vendor cases at [caseCost] per case. */
@ConsistentCopyVisibility
data class InvoiceLine private constructor(
    val purchaseOrderLineNumber: Int,
    val cases: Int,
    val caseCost: Money,
) {
    val amount: Money get() = caseCost * cases

    companion object {
        /**
         * @throws IllegalArgumentException unless the line number and cases are above zero and the cost is not
         * negative.
         */
        fun of(
            purchaseOrderLineNumber: Int,
            cases: Int,
            caseCost: Money,
        ): InvoiceLine {
            require(purchaseOrderLineNumber >= 1) { "Line numbers start at one: $purchaseOrderLineNumber" }
            require(cases >= 1) { "An invoice line bills at least one case: $cases" }
            require(!caseCost.isNegative) { "A case cost cannot be negative: $caseCost" }
            return InvoiceLine(purchaseOrderLineNumber, cases, caseCost)
        }
    }
}

/** What a vendor's invoice says: who sent it, for which purchase order, and what it bills. */
data class InvoiceDetails(
    val vendorId: VendorId,
    val number: VendorInvoiceNumber,
    val invoiceDate: BusinessDate,
    val purchaseOrderId: PurchaseOrderId,
    val lines: List<InvoiceLine>,
)

/** Why an invoice line does not agree with its purchase order and receipts. */
enum class MatchExceptionReason {
    /** Nothing has been received on the purchase order line. */
    NOTHING_RECEIVED,

    /** The invoice bills more cases than have been received and not yet invoiced. */
    QUANTITY_OVER_RECEIPT,

    /** The case cost differs from the purchase order's by more than the tolerance. */
    PRICE_OVER_TOLERANCE,
}

/** One reason an invoice did not match, with the numbers behind it for the person resolving it. */
data class MatchException(
    val purchaseOrderLineNumber: Int,
    val reason: MatchExceptionReason,
    val detail: String,
)

/** Someone other than the person who entered an invoice accepted its price exception, for [reason]. */
@ConsistentCopyVisibility
data class MatchOverride private constructor(
    val approvedBy: String,
    val reason: String,
) {
    companion object {
        const val MAX_REASON_LENGTH = 200

        /** @throws IllegalArgumentException if [reason] is blank or longer than [MAX_REASON_LENGTH] once trimmed. */
        fun of(
            approvedBy: String,
            reason: String,
        ): MatchOverride {
            val trimmed = reason.trim()
            require(trimmed.isNotEmpty()) { "An override needs a reason" }
            require(trimmed.length <= MAX_REASON_LENGTH) { "A reason has at most $MAX_REASON_LENGTH characters" }
            return MatchOverride(approvedBy, trimmed)
        }
    }
}

/** Whether an invoice agreed with its purchase order and receipts when it was last matched. */
sealed interface InvoiceStatus {
    /** A short name for the status, stored and shown to users. */
    val code: String

    /**
     * Agreed on [matchedOn]; the amount is owed to the vendor. [override] is set when a price exception was
     * accepted rather than resolved.
     */
    data class Matched(
        val matchedOn: BusinessDate,
        val override: MatchOverride? = null,
    ) : InvoiceStatus {
        override val code = "MATCHED"
    }

    /** Did not agree, for [exceptions]; waiting for someone to resolve it and match again. */
    data class Exception(
        val exceptions: List<MatchException>,
    ) : InvoiceStatus {
        override val code = "EXCEPTION"
    }
}

/** A vendor's invoice against one purchase order, and the outcome of matching it. */
class VendorInvoice private constructor(
    val id: InvoiceId,
    val details: InvoiceDetails,
    val enteredBy: String,
    status: InvoiceStatus,
    val version: Int,
) : AggregateRoot() {
    var status: InvoiceStatus = status
        private set

    val lines: List<InvoiceLine> get() = details.lines

    /** The sum of the lines. */
    val amount: Money get() = lines.map { it.amount }.reduce(Money::plus)

    /**
     * Records the [outcome] of a three-way match, raising [InvoiceMatched] or [InvoiceMatchException]. [override]
     * is given when the match accepted a price exception.
     *
     * @throws InvoiceAlreadyMatched if the invoice has matched before.
     * @throws OverrideByInvoiceCreator if [override] was approved by the person who entered the invoice.
     */
    fun record(
        outcome: MatchOutcome,
        clock: Clock,
        override: MatchOverride? = null,
    ) {
        requireMatchable(override)
        val today = BusinessDate.today(clock)
        when (outcome) {
            is MatchOutcome.Matched -> {
                status = InvoiceStatus.Matched(today, override)
                raise(
                    InvoiceMatched(
                        eventId = EventId.generate(clock),
                        occurredAt = clock.instant(),
                        businessDate = today,
                        invoice = facts(),
                        invoiceAmount = amount.amount,
                        accruedAmount = outcome.accruedAmount.amount,
                        priceVariance = (amount - outcome.accruedAmount).amount,
                        currency = amount.currency.currencyCode,
                    ),
                )
            }

            is MatchOutcome.Exceptions -> {
                status = InvoiceStatus.Exception(outcome.exceptions)
                raise(
                    InvoiceMatchException(
                        eventId = EventId.generate(clock),
                        occurredAt = clock.instant(),
                        businessDate = today,
                        invoice = facts(),
                        exceptions =
                            outcome.exceptions.map {
                                InvoiceExceptionFacts(
                                    it.purchaseOrderLineNumber,
                                    it.reason.name,
                                )
                            },
                    ),
                )
            }
        }
    }

    /**
     * Checks that the invoice can still be matched, and that [override], if given, comes from someone else than
     * the person who entered it.
     *
     * @throws InvoiceAlreadyMatched if the invoice has matched before.
     * @throws OverrideByInvoiceCreator if [override] was approved by the person who entered the invoice.
     */
    fun requireMatchable(override: MatchOverride? = null) {
        if (status is InvoiceStatus.Matched) throw InvoiceAlreadyMatched(details.number)
        if (override?.approvedBy == enteredBy) throw OverrideByInvoiceCreator(details.number)
    }

    private fun facts() = InvoiceFacts(id, details.number.value, details.vendorId, details.purchaseOrderId)

    companion object {
        /**
         * Enters a vendor's invoice. It starts unmatched with no exceptions listed; the caller matches it at once.
         *
         * @throws InvoiceLineRepeated if two lines bill the same purchase order line.
         * @throws InvoiceInMixedCurrencies if the lines are not all in one currency.
         */
        fun enter(
            details: InvoiceDetails,
            enteredBy: String,
            clock: Clock,
        ): VendorInvoice {
            require(details.lines.isNotEmpty()) { "An invoice has at least one line" }
            val numbers = details.lines.map { it.purchaseOrderLineNumber }
            if (numbers.size != numbers.toSet().size) throw InvoiceLineRepeated(details.number)
            if (details.lines
                    .map { it.caseCost.currency }
                    .toSet()
                    .size > 1
            ) {
                throw InvoiceInMixedCurrencies()
            }
            return VendorInvoice(
                InvoiceId.generate(clock),
                details,
                enteredBy,
                InvoiceStatus.Exception(emptyList()),
                version = 0,
            )
        }

        /** Rebuilds an invoice from stored state. Raises no events. */
        fun rehydrate(
            id: InvoiceId,
            details: InvoiceDetails,
            enteredBy: String,
            status: InvoiceStatus,
            version: Int,
        ): VendorInvoice = VendorInvoice(id, details, enteredBy, status, version)
    }
}

class InvoiceAlreadyEntered(
    number: VendorInvoiceNumber,
) : BusinessRuleViolation(
        "payables.invoice-already-entered",
        "Invoice $number from this vendor has already been entered",
    )

class InvoiceAlreadyMatched(
    number: VendorInvoiceNumber,
) : BusinessRuleViolation("payables.invoice-already-matched", "Invoice $number has already been matched")

class InvoiceLineRepeated(
    number: VendorInvoiceNumber,
) : BusinessRuleViolation(
        "payables.invoice-line-repeated",
        "Invoice $number bills the same purchase order line more than once",
    )

class InvoiceInMixedCurrencies :
    BusinessRuleViolation(
        "payables.invoice-in-mixed-currencies",
        "Every line of an invoice must be in the same currency",
    )

class OverrideByInvoiceCreator(
    number: VendorInvoiceNumber,
) : BusinessRuleViolation(
        "payables.override-by-invoice-creator",
        "Invoice $number cannot be overridden by the person who entered it",
    )

class OverrideDoesNotResolve(
    number: VendorInvoiceNumber,
) : BusinessRuleViolation(
        "payables.override-does-not-resolve",
        "Invoice $number bills cases that have not been received; only a price exception can be overridden",
    )
