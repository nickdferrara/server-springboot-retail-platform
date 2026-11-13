package com.retailplatform.payables.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.PaymentId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.VoucherId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * A payable voucher was released for payment: [amount] is owed to the vendor on [invoice] and falls due on
 * [dueOn]. A voucher is released when its invoice matches, or when a match exception is overridden;
 * [overrideApprovedBy] names who approved the override, and is `null` for a plain match.
 */
data class PayableVoucherReleased(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val voucherId: VoucherId,
    val voucherNumber: String,
    val invoice: InvoiceFacts,
    val amount: BigDecimal,
    val currency: String,
    val dueOn: LocalDate,
    val overrideApprovedBy: String?,
    val schemaVersion: Int = 1,
)

/** One voucher a payment settles: [amount] owed, less [discount] taken for paying early. */
data class PaidVoucherFacts(
    val voucherId: VoucherId,
    val voucherNumber: String,
    val vendorInvoiceNumber: String,
    val amount: BigDecimal,
    val discount: BigDecimal,
)

/**
 * A payment to a vendor was issued. [grossAmount] is what the vouchers owed, [discountAmount] the early-payment
 * discounts taken, [creditAmount] the vendor credits deducted, and [netAmount] what leaves the bank: gross less
 * discount and credit. [businessDate] is the payment date.
 */
data class PaymentIssued(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val paymentId: PaymentId,
    val paymentNumber: String,
    val vendorId: VendorId,
    val grossAmount: BigDecimal,
    val discountAmount: BigDecimal,
    val netAmount: BigDecimal,
    val currency: String,
    val vouchers: List<PaidVoucherFacts>,
    val creditAmount: BigDecimal = BigDecimal.ZERO,
    val schemaVersion: Int = 1,
)
