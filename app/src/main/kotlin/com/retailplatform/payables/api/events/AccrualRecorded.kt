package com.retailplatform.payables.api.events

import com.retailplatform.shared.AccrualId
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import java.math.BigDecimal
import java.time.Instant

/**
 * A liability of [amount] to a vendor was accrued for goods received and not yet invoiced. [businessDate] is the
 * day the goods were received.
 */
data class AccrualRecorded(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val accrualId: AccrualId,
    val receiptId: ReceiptId,
    val receiptNumber: String,
    val purchaseOrderId: PurchaseOrderId,
    val purchaseOrderNumber: String,
    val vendorId: VendorId,
    val warehouseId: WarehouseId,
    val amount: BigDecimal,
    val currency: String,
    val schemaVersion: Int = 1,
)
