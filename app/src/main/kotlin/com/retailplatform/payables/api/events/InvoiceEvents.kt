package com.retailplatform.payables.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.VendorId
import java.math.BigDecimal
import java.time.Instant

/**
 * The vendor invoice an event is about. [purchaseOrderId] is `null` for an invoice for a direct store delivery,
 * which has no purchase order.
 */
data class InvoiceFacts(
    val invoiceId: InvoiceId,
    val vendorInvoiceNumber: String,
    val vendorId: VendorId,
    val purchaseOrderId: PurchaseOrderId?,
)

/**
 * A vendor invoice agreed with its purchase order and receipts. [invoiceAmount] is now owed to the vendor;
 * [accruedAmount] is what the matched cases were accrued at, purchase order cost; [priceVariance] is the invoice
 * amount less the accrued amount, negative when the vendor billed less.
 */
data class InvoiceMatched(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val invoice: InvoiceFacts,
    val invoiceAmount: BigDecimal,
    val accruedAmount: BigDecimal,
    val priceVariance: BigDecimal,
    val currency: String,
    val schemaVersion: Int = 1,
)

/** Why one invoice line did not match: `NOTHING_RECEIVED`, `QUANTITY_OVER_RECEIPT`, or `PRICE_OVER_TOLERANCE`. */
data class InvoiceExceptionFacts(
    val purchaseOrderLineNumber: Int,
    val reason: String,
)

/** A vendor invoice did not agree with its purchase order and receipts, and needs someone to look at it. */
data class InvoiceMatchException(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val invoice: InvoiceFacts,
    val exceptions: List<InvoiceExceptionFacts>,
    val schemaVersion: Int = 1,
)
