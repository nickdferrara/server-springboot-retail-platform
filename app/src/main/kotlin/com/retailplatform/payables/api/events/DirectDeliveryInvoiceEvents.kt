package com.retailplatform.payables.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** The vendor invoice for one direct store delivery ticket that an event is about. */
data class DirectDeliveryInvoiceFacts(
    val invoiceId: InvoiceId,
    val vendorInvoiceNumber: String,
    val vendorId: VendorId,
    val storeId: StoreId,
    val ticketNumber: String,
)

/**
 * A vendor invoice for a delivery ticket became payable: it agreed with the store's receipt and the agreed
 * costs, a price exception was overridden, it was short-paid, or it was accepted though it bills less than the
 * store received. [invoiceAmount] is what the vendor billed and [deductionAmount] what was held back for units
 * billed but not received, so the invoice amount less the deduction is now owed to the vendor. [accruedAmount]
 * is what the delivered units were accrued at, agreed cost; [costVariance] is what is owed less the accrued
 * amount, negative when it is less. [unbilledAmount] is the part of the accrued amount for units received that
 * the invoice does not bill; the ticket is closed, so it is not owed and is already within the cost variance.
 */
data class DirectDeliveryInvoiceMatched(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val invoice: DirectDeliveryInvoiceFacts,
    val invoiceAmount: BigDecimal,
    val accruedAmount: BigDecimal,
    val costVariance: BigDecimal,
    val currency: String,
    val deductionAmount: BigDecimal = BigDecimal.ZERO,
    val unbilledAmount: BigDecimal = BigDecimal.ZERO,
    val schemaVersion: Int = 1,
)

/**
 * Why an invoice did not match its ticket: `TICKET_NOT_RECEIVED`, `ITEM_NOT_ON_TICKET`, `ITEM_NOT_BILLED`,
 * `QUANTITY_DIFFERS`, or `PRICE_OVER_TOLERANCE`. [itemId] is `null` when the reason is about the whole ticket.
 */
data class DirectDeliveryExceptionFacts(
    val itemId: ItemId?,
    val reason: String,
)

/** A vendor invoice did not agree with the store's receipt or the agreed costs, and needs a buyer to look at it. */
data class DirectDeliveryInvoiceMatchException(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val invoice: DirectDeliveryInvoiceFacts,
    val exceptions: List<DirectDeliveryExceptionFacts>,
    val schemaVersion: Int = 1,
)

/**
 * Payables took account of a correction to a store's receipt of a direct delivery. [inventoryChange] is the
 * change in the value of what the store holds, [accrualChange] the change in what is accrued as owed to the
 * vendor, and [creditChange] the change in what the vendor owes as credits; each is negative for a decrease, and
 * the inventory change is the accrual change less the credit change. [businessDate] is the day of the correction.
 */
data class DirectDeliveryCorrectionRecorded(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val vendorId: VendorId,
    val storeId: StoreId,
    val ticketNumber: String,
    val correctionReceiptNumber: String,
    val inventoryChange: BigDecimal,
    val accrualChange: BigDecimal,
    val creditChange: BigDecimal,
    val currency: String,
    val schemaVersion: Int = 1,
)

/**
 * Accounting settled an amount that a late correction to a store's receipt had left accrued with no invoice to
 * match it. [resolution] is `PAID` when the vendor is owed [amount] and a voucher was released for it against the
 * vendor's document [vendorReference], or `WRITTEN_OFF` when it is not owed and leaves what is accrued as a
 * favourable variance; [vendorReference] is then `null`. [businessDate] is the day it was settled.
 */
data class DirectDeliveryAdjustmentResolved(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val adjustmentId: UUID,
    val vendorId: VendorId,
    val storeId: StoreId,
    val ticketNumber: String,
    val resolution: String,
    val amount: BigDecimal,
    val currency: String,
    val vendorReference: String?,
    val schemaVersion: Int = 1,
)
