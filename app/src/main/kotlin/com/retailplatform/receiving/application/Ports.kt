package com.retailplatform.receiving.application

import com.retailplatform.receiving.domain.ExpectedDelivery
import com.retailplatform.receiving.domain.Receipt
import com.retailplatform.receiving.domain.ReceiptNumber
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.WarehouseId

/** Port: the purchase orders the dock expects, one per order. */
interface ExpectedDeliveryRepository {
    /** Inserts a new expected delivery or updates an existing one; a stale version is refused. */
    fun save(delivery: ExpectedDelivery)

    fun findById(purchaseOrderId: PurchaseOrderId): ExpectedDelivery?

    /** Open deliveries for a warehouse, the earliest expected first. */
    fun findOpen(
        warehouseId: WarehouseId,
        query: PageQuery,
    ): Page<ExpectedDelivery>
}

/** Port: where receipts are kept. Receipts are added and read, never changed. */
interface ReceiptRepository {
    fun add(receipt: Receipt)

    fun findById(id: ReceiptId): Receipt?

    /** The receipts against a purchase order, oldest first. */
    fun findByPurchaseOrder(purchaseOrderId: PurchaseOrderId): List<Receipt>
}

/** Port: hands out receipt numbers, each once. */
interface ReceiptNumbers {
    fun next(): ReceiptNumber
}
