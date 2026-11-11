package com.retailplatform.purchasing.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import java.math.BigDecimal
import java.time.Instant

/**
 * One line of a purchase order: [cases] vendor cases of [casePack] base units each, at [caseCost] per case in the
 * order's currency.
 */
data class PurchaseOrderLineFacts(
    val lineNumber: Int,
    val itemId: ItemId,
    val vendorSku: String,
    val casePack: Int,
    val cases: Int,
    val caseCost: BigDecimal,
)

/** A purchase order as other modules need to know it. [total] is the sum of the lines in [currency]. */
data class PurchaseOrderFacts(
    val purchaseOrderId: PurchaseOrderId,
    val purchaseOrderNumber: String,
    val vendorId: VendorId,
    val warehouseId: WarehouseId,
    val expectedOn: BusinessDate,
    val currency: String,
    val total: BigDecimal,
    val lines: List<PurchaseOrderLineFacts>,
)

/** A purchase order was approved by [approvedBy] and may be sent to the vendor. */
data class PurchaseOrderApproved(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val purchaseOrder: PurchaseOrderFacts,
    val approvedBy: String,
    val schemaVersion: Int = 1,
)

/** A purchase order was sent to the vendor; the warehouse can expect the delivery. */
data class PurchaseOrderSent(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val purchaseOrder: PurchaseOrderFacts,
    val schemaVersion: Int = 1,
)

/** An approved or sent purchase order was cancelled; nothing more will be received against it. */
data class PurchaseOrderCancelled(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val purchaseOrderId: PurchaseOrderId,
    val purchaseOrderNumber: String,
    val reason: String,
    val schemaVersion: Int = 1,
)
