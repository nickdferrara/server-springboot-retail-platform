package com.retailplatform.payables.domain

import com.retailplatform.payables.api.events.InvoiceFacts
import com.retailplatform.payables.api.events.PayableVoucherReleased
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PaymentId
import com.retailplatform.shared.Percentage
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.VoucherId
import java.time.Clock

/** The number payables gives a voucher: one to ten digits. */
@JvmInline
value class VoucherNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{1,10}")

        /** @throws IllegalArgumentException unless [value] is one to ten digits. */
        fun of(value: String): VoucherNumber {
            require(FORMAT.matches(value)) { "Not a voucher number: '$value'" }
            return VoucherNumber(value)
        }
    }
}

/** [percent] off a voucher paid on or before [goodThrough]. */
data class VoucherDiscount(
    val percent: Percentage,
    val goodThrough: BusinessDate,
)

/** The matched invoice a voucher pays. [purchaseOrderId] is `null` for a direct store delivery invoice. */
data class VoucheredInvoice(
    val invoiceId: InvoiceId,
    val vendorInvoiceNumber: VendorInvoiceNumber,
    val vendorId: VendorId,
    val purchaseOrderId: PurchaseOrderId?,
    val invoiceDate: BusinessDate,
)

/**
 * An invoice that matched, or whose exception was overridden, and so may be vouchered: [amount] became owed on
 * [matchedOn]. [overrideApprovedBy] names who approved an override, and is `null` for a plain match.
 */
data class MatchedInvoice(
    val invoice: VoucheredInvoice,
    val amount: Money,
    val matchedOn: BusinessDate,
    val overrideApprovedBy: String?,
)

/** What a voucher is for and when it is to be paid. */
data class VoucherTerms(
    val amount: Money,
    val dueOn: BusinessDate,
    val discount: VoucherDiscount?,
)

/** Where a voucher is on its way to being paid. */
sealed interface VoucherStatus {
    /** A short name for the status, stored and shown to users. */
    val code: String

    /** Approved for payment and waiting for a payment run. */
    data object Released : VoucherStatus {
        override val code = "RELEASED"
    }

    /** Selected by a payment run that has not been approved yet. */
    data class Scheduled(
        val paymentRunId: PaymentRunId,
    ) : VoucherStatus {
        override val code = "SCHEDULED"
    }

    /** Settled on [paidOn] by the payment [paymentId] that the run [paymentRunId] issued. */
    data class Paid(
        val paymentRunId: PaymentRunId,
        val paymentId: PaymentId,
        val paidOn: BusinessDate,
    ) : VoucherStatus {
        override val code = "PAID"
    }
}

/**
 * A payable voucher: the amount owed to a vendor on one matched invoice, approved for payment. It is released
 * when its invoice matches and is paid by a payment run.
 */
class Voucher private constructor(
    val id: VoucherId,
    val number: VoucherNumber,
    val invoice: VoucheredInvoice,
    val terms: VoucherTerms,
    val releasedOn: BusinessDate,
    status: VoucherStatus,
    val version: Int,
) : AggregateRoot() {
    var status: VoucherStatus = status
        private set

    val amount: Money get() = terms.amount

    /** The early-payment discount that can be taken if the voucher is paid on [paymentDate]; zero if none. */
    fun discountOn(paymentDate: BusinessDate): Money {
        val discount = terms.discount
        return if (discount != null && paymentDate <= discount.goodThrough) {
            discount.percent.of(amount)
        } else {
            Money.zero(amount.currency)
        }
    }

    /**
     * Reserves the voucher for a payment run, so no other run selects it.
     *
     * @throws VoucherNotReleased unless the voucher is waiting for a payment run.
     */
    fun schedule(paymentRunId: PaymentRunId) {
        if (status != VoucherStatus.Released) throw VoucherNotReleased(number)
        status = VoucherStatus.Scheduled(paymentRunId)
    }

    /** Gives the voucher back when the payment run [paymentRunId] that reserved it is cancelled. */
    fun unschedule(paymentRunId: PaymentRunId) {
        check(status == VoucherStatus.Scheduled(paymentRunId)) { "Voucher $number is not in payment run $paymentRunId" }
        status = VoucherStatus.Released
    }

    /** Settles the voucher with the payment [paymentId] made on [paidOn] by the run that reserved it. */
    fun pay(
        paymentRunId: PaymentRunId,
        paymentId: PaymentId,
        paidOn: BusinessDate,
    ) {
        check(status == VoucherStatus.Scheduled(paymentRunId)) { "Voucher $number is not in payment run $paymentRunId" }
        status = VoucherStatus.Paid(paymentRunId, paymentId, paidOn)
    }

    companion object {
        /**
         * Releases a voucher for a [matched] invoice, due and discounted as the vendor's [terms] say, and raises
         * [PayableVoucherReleased].
         */
        fun release(
            number: VoucherNumber,
            matched: MatchedInvoice,
            terms: VendorTerms,
            clock: Clock,
        ): Voucher {
            val invoice = matched.invoice
            val voucher =
                Voucher(
                    id = VoucherId.generate(clock),
                    number = number,
                    invoice = invoice,
                    terms =
                        VoucherTerms(
                            matched.amount,
                            terms.dueOn(invoice.invoiceDate),
                            terms.discountFor(invoice.invoiceDate),
                        ),
                    releasedOn = matched.matchedOn,
                    status = VoucherStatus.Released,
                    version = 0,
                )
            voucher.raise(
                PayableVoucherReleased(
                    eventId = EventId.generate(clock),
                    occurredAt = clock.instant(),
                    businessDate = matched.matchedOn,
                    voucherId = voucher.id,
                    voucherNumber = number.value,
                    invoice =
                        InvoiceFacts(
                            invoice.invoiceId,
                            invoice.vendorInvoiceNumber.value,
                            invoice.vendorId,
                            invoice.purchaseOrderId,
                        ),
                    amount = voucher.amount.amount,
                    currency = voucher.amount.currency.currencyCode,
                    dueOn = voucher.terms.dueOn.value,
                    overrideApprovedBy = matched.overrideApprovedBy,
                ),
            )
            return voucher
        }

        /** Rebuilds a voucher from stored state. Raises no events. */
        fun rehydrate(
            id: VoucherId,
            number: VoucherNumber,
            invoice: VoucheredInvoice,
            terms: VoucherTerms,
            releasedOn: BusinessDate,
            status: VoucherStatus,
            version: Int,
        ): Voucher = Voucher(id, number, invoice, terms, releasedOn, status, version)
    }
}

class VoucherNotReleased(
    number: VoucherNumber,
) : BusinessRuleViolation(
        "payables.voucher-not-released",
        "Voucher $number is already in a payment run or paid",
    )
