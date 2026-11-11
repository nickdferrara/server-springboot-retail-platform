package com.retailplatform.receiving.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import java.math.BigDecimal
import java.time.Instant

/** The receipt an event is about, and the purchase order it was received against. */
data class ReceiptFacts(
    val receiptId: ReceiptId,
    val receiptNumber: String,
    val purchaseOrderId: PurchaseOrderId,
    val purchaseOrderNumber: String,
    val vendorId: VendorId,
    val warehouseId: WarehouseId,
)

/**
 * Goods accepted into stock on one purchase order line: [casesReceived] vendor cases of [casePack] base units, at
 * the purchase order's [caseCost] per case.
 */
data class GoodsReceivedLine(
    val purchaseOrderLineNumber: Int,
    val itemId: ItemId,
    val casePack: Int,
    val casesReceived: Int,
    val caseCost: BigDecimal,
)

/**
 * Goods from a vendor were accepted into a warehouse's stock. [lines] holds only what was accepted, costed in
 * [currency] at purchase order cost. [purchaseOrderComplete] is true when nothing more is expected on the order.
 */
data class GoodsReceived(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val receipt: ReceiptFacts,
    val currency: String,
    val lines: List<GoodsReceivedLine>,
    val purchaseOrderComplete: Boolean,
    val schemaVersion: Int = 1,
)

/**
 * How a delivery differed from one purchase order line, in vendor cases: [casesOver] accepted beyond what was
 * still expected, [casesShort] never delivered when the order was completed, [casesDamaged] refused at the dock.
 */
data class ReceiptDiscrepancyLine(
    val purchaseOrderLineNumber: Int,
    val itemId: ItemId,
    val casesOver: Int,
    val casesShort: Int,
    val casesDamaged: Int,
)

/** A receipt differed from its purchase order. [lines] holds only the lines that differed. */
data class ReceiptDiscrepancyRecorded(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val receipt: ReceiptFacts,
    val lines: List<ReceiptDiscrepancyLine>,
    val schemaVersion: Int = 1,
)
