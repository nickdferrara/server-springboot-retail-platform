package com.retailplatform.payables.domain

import com.retailplatform.payables.api.events.PaidVoucherFacts
import com.retailplatform.payables.api.events.PaymentIssued
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PaymentId
import com.retailplatform.shared.TypedId
import com.retailplatform.shared.TypedIdFactory
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.VoucherId
import java.time.Clock
import java.util.UUID

/** Identifies a payment run. */
@JvmInline
value class PaymentRunId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<PaymentRunId>(::PaymentRunId)
}

/** The number payables gives a payment: one to ten digits. */
@JvmInline
value class PaymentNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{1,10}")

        /** @throws IllegalArgumentException unless [value] is one to ten digits. */
        fun of(value: String): PaymentNumber {
            require(FORMAT.matches(value)) { "Not a payment number: '$value'" }
            return PaymentNumber(value)
        }
    }
}

/**
 * What a payment run pays: vouchers due on or before [payThrough], and vouchers whose early-payment discount
 * would run out by then, all paid on [paymentDate]. [payThrough] is normally the day of the next run.
 */
@ConsistentCopyVisibility
data class PaymentWindow private constructor(
    val paymentDate: BusinessDate,
    val payThrough: BusinessDate,
) {
    /** Whether [voucher] belongs in a run for this window. */
    fun selects(voucher: Voucher): Boolean {
        val discount = voucher.terms.discount
        val discountRunsOut =
            discount != null && discount.goodThrough >= paymentDate && discount.goodThrough <= payThrough
        return voucher.terms.dueOn <= payThrough || discountRunsOut
    }

    companion object {
        /** @throws IllegalArgumentException if [payThrough] is before [paymentDate]. */
        fun of(
            paymentDate: BusinessDate,
            payThrough: BusinessDate,
        ): PaymentWindow {
            require(payThrough >= paymentDate) { "Pay-through date $payThrough is before payment date $paymentDate" }
            return PaymentWindow(paymentDate, payThrough)
        }
    }
}

/** One voucher in a payment run: [amount] owed, less [discount] taken for paying early. */
data class PaymentRunLine(
    val voucherId: VoucherId,
    val voucherNumber: VoucherNumber,
    val vendorId: VendorId,
    val vendorInvoiceNumber: VendorInvoiceNumber,
    val amount: Money,
    val discount: Money,
) {
    val net: Money get() = amount - discount
}

/** Whether a payment run has been decided. */
sealed interface PaymentRunStatus {
    /** A short name for the status, stored and shown to users. */
    val code: String

    /** Waiting for approval; its vouchers are reserved. */
    data object Proposed : PaymentRunStatus {
        override val code = "PROPOSED"
    }

    /** Approved by [approvedBy] on [approvedOn]; its payments were issued. */
    data class Approved(
        val approvedBy: String,
        val approvedOn: BusinessDate,
    ) : PaymentRunStatus {
        override val code = "APPROVED"
    }

    /** Cancelled by [cancelledBy] on [cancelledOn]; its vouchers were given back. */
    data class Cancelled(
        val cancelledBy: String,
        val cancelledOn: BusinessDate,
    ) : PaymentRunStatus {
        override val code = "CANCELLED"
    }
}

/**
 * A payment run: the vouchers selected to be paid on one day, proposed by one person and approved by another.
 * Approving it issues one payment per vendor and currency.
 */
class PaymentRun private constructor(
    val id: PaymentRunId,
    val window: PaymentWindow,
    val proposedBy: String,
    val lines: List<PaymentRunLine>,
    status: PaymentRunStatus,
    val version: Int,
) : AggregateRoot() {
    var status: PaymentRunStatus = status
        private set

    /**
     * Approves the run and issues its payments, one per vendor and currency, numbered by [nextNumber].
     *
     * @throws PaymentRunNotProposed if the run was already approved or cancelled.
     * @throws PaymentRunApprovedByProposer if [approvedBy] proposed the run.
     * @throws PaymentRunOutOfDate if the payment date has passed; the discounts were worked out for that day.
     */
    fun approve(
        approvedBy: String,
        nextNumber: () -> PaymentNumber,
        clock: Clock,
    ): List<Payment> {
        requireProposed()
        if (approvedBy == proposedBy) throw PaymentRunApprovedByProposer(id)
        val today = BusinessDate.today(clock)
        if (today > window.paymentDate) throw PaymentRunOutOfDate(id, window.paymentDate)

        status = PaymentRunStatus.Approved(approvedBy, today)
        return lines
            .groupBy { it.vendorId to it.amount.currency }
            .map { (payee, vouchers) ->
                Payment.issue(nextNumber(), id, payee.first, window.paymentDate, vouchers, clock)
            }
    }

    /**
     * Cancels a run that has not been approved.
     *
     * @throws PaymentRunNotProposed if the run was already approved or cancelled.
     */
    fun cancel(
        cancelledBy: String,
        clock: Clock,
    ) {
        requireProposed()
        status = PaymentRunStatus.Cancelled(cancelledBy, BusinessDate.today(clock))
    }

    private fun requireProposed() {
        if (status != PaymentRunStatus.Proposed) throw PaymentRunNotProposed(id)
    }

    companion object {
        /**
         * Proposes a run that pays [vouchers] on the window's payment date, reserving each of them and taking
         * each early-payment discount still available that day.
         *
         * @throws PaymentDateInPast if the payment date is before today.
         * @throws NothingToPay if [vouchers] is empty.
         * @throws VoucherNotReleased if a voucher is already in a run or paid.
         */
        fun propose(
            window: PaymentWindow,
            vouchers: List<Voucher>,
            proposedBy: String,
            clock: Clock,
        ): PaymentRun {
            val today = BusinessDate.today(clock)
            if (window.paymentDate < today) throw PaymentDateInPast(window.paymentDate, today)
            if (vouchers.isEmpty()) throw NothingToPay(window.payThrough)

            val id = PaymentRunId.generate(clock)
            val lines =
                vouchers.map {
                    it.schedule(id)
                    PaymentRunLine(
                        voucherId = it.id,
                        voucherNumber = it.number,
                        vendorId = it.invoice.vendorId,
                        vendorInvoiceNumber = it.invoice.vendorInvoiceNumber,
                        amount = it.amount,
                        discount = it.discountOn(window.paymentDate),
                    )
                }
            return PaymentRun(id, window, proposedBy, lines, PaymentRunStatus.Proposed, version = 0)
        }

        /** Rebuilds a payment run from stored state. Raises no events. */
        fun rehydrate(
            id: PaymentRunId,
            window: PaymentWindow,
            proposedBy: String,
            lines: List<PaymentRunLine>,
            status: PaymentRunStatus,
            version: Int,
        ): PaymentRun = PaymentRun(id, window, proposedBy, lines, status, version)
    }
}

/** A payment to one vendor: the [vouchers] it settles, paid on [paidOn]. Issued by approving a payment run. */
class Payment private constructor(
    val id: PaymentId,
    val number: PaymentNumber,
    val paymentRunId: PaymentRunId,
    val vendorId: VendorId,
    val paidOn: BusinessDate,
    val vouchers: List<PaymentRunLine>,
) : AggregateRoot() {
    /** What the vouchers owed. */
    val gross: Money get() = vouchers.map { it.amount }.reduce(Money::plus)

    /** The early-payment discounts taken. */
    val discount: Money get() = vouchers.map { it.discount }.reduce(Money::plus)

    /** What is paid to the vendor: gross less discount. */
    val net: Money get() = gross - discount

    companion object {
        /** Issues a payment for [vouchers], all owed to [vendorId] in one currency, and raises [PaymentIssued]. */
        fun issue(
            number: PaymentNumber,
            paymentRunId: PaymentRunId,
            vendorId: VendorId,
            paidOn: BusinessDate,
            vouchers: List<PaymentRunLine>,
            clock: Clock,
        ): Payment {
            require(vouchers.isNotEmpty()) { "A payment settles at least one voucher" }
            val payment = Payment(PaymentId.generate(clock), number, paymentRunId, vendorId, paidOn, vouchers)
            payment.raise(
                PaymentIssued(
                    eventId = EventId.generate(clock),
                    occurredAt = clock.instant(),
                    businessDate = paidOn,
                    paymentId = payment.id,
                    paymentNumber = number.value,
                    vendorId = vendorId,
                    grossAmount = payment.gross.amount,
                    discountAmount = payment.discount.amount,
                    netAmount = payment.net.amount,
                    currency = payment.gross.currency.currencyCode,
                    vouchers =
                        vouchers.map {
                            PaidVoucherFacts(
                                it.voucherId,
                                it.voucherNumber.value,
                                it.vendorInvoiceNumber.value,
                                it.amount.amount,
                                it.discount.amount,
                            )
                        },
                ),
            )
            return payment
        }

        /** Rebuilds a payment from stored state. Raises no events. */
        fun rehydrate(
            id: PaymentId,
            number: PaymentNumber,
            paymentRunId: PaymentRunId,
            vendorId: VendorId,
            paidOn: BusinessDate,
            vouchers: List<PaymentRunLine>,
        ): Payment = Payment(id, number, paymentRunId, vendorId, paidOn, vouchers)
    }
}

class NothingToPay(
    payThrough: BusinessDate,
) : BusinessRuleViolation(
        "payables.nothing-to-pay",
        "No released voucher is due, or has a discount running out, on or before $payThrough",
    )

class PaymentDateInPast(
    paymentDate: BusinessDate,
    today: BusinessDate,
) : BusinessRuleViolation(
        "payables.payment-date-in-past",
        "A payment run cannot pay on $paymentDate, before today ($today)",
    )

class PaymentRunNotProposed(
    id: PaymentRunId,
) : BusinessRuleViolation(
        "payables.payment-run-not-proposed",
        "Payment run $id has already been approved or cancelled",
    )

class PaymentRunApprovedByProposer(
    id: PaymentRunId,
) : BusinessRuleViolation(
        "payables.payment-run-approved-by-proposer",
        "Payment run $id cannot be approved by the person who proposed it",
    )

class PaymentRunOutOfDate(
    id: PaymentRunId,
    paymentDate: BusinessDate,
) : BusinessRuleViolation(
        "payables.payment-run-out-of-date",
        "Payment run $id was to pay on $paymentDate, which has passed; cancel it and propose a new run",
    )
