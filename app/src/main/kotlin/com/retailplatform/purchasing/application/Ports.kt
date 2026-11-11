package com.retailplatform.purchasing.application

import com.retailplatform.purchasing.domain.ApprovalThreshold
import com.retailplatform.purchasing.domain.PurchaseOrder
import com.retailplatform.purchasing.domain.PurchaseOrderNumber
import com.retailplatform.purchasing.domain.VendorItemCost
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.VendorId

/** Port: where purchase orders are kept. */
interface PurchaseOrderRepository {
    /** Inserts a new order or updates an existing one; a stale version is refused. */
    fun save(order: PurchaseOrder)

    fun findById(id: PurchaseOrderId): PurchaseOrder?

    /** Orders with the newest number first. */
    fun findPage(query: PageQuery): Page<PurchaseOrder>
}

/** Port: hands out purchase order numbers, each once. */
interface PurchaseOrderNumbers {
    fun next(): PurchaseOrderNumber
}

/** Port: the vendor costs purchasing has learned from the catalog. */
interface VendorItemCostRepository {
    /** Inserts the cost or replaces what is stored for the vendor and item. */
    fun save(cost: VendorItemCost)

    /** What [vendorId] supplies among [itemIds], keyed by item. */
    fun findByVendorAndItems(
        vendorId: VendorId,
        itemIds: Collection<ItemId>,
    ): Map<ItemId, VendorItemCost>
}

/** Port: where approval thresholds are kept, one per effective date. */
interface ApprovalThresholdRepository {
    /** Inserts the threshold or replaces the one with the same effective date. */
    fun save(threshold: ApprovalThreshold)

    /** The threshold with the latest effective date on or before [date]. */
    fun findEffective(date: BusinessDate): ApprovalThreshold?
}
