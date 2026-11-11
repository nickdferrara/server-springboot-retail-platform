package com.retailplatform.inventory.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InventoryMovementId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.WarehouseId
import java.math.BigDecimal
import java.time.Instant

/**
 * On-hand stock of an item at one location was corrected for [reason] (`SHRINK`, `DAMAGE`, or `COUNT`).
 *
 * Exactly one of [warehouseId] and [storeId] is set. [quantity] is in the item's base [unit] and is negative when
 * stock was written off. [extendedCost] is [quantity] times [unitCost], the location's average cost at the time,
 * and carries the same sign.
 */
data class InventoryAdjusted(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val movementId: InventoryMovementId,
    val itemId: ItemId,
    val warehouseId: WarehouseId?,
    val storeId: StoreId?,
    val reason: String,
    val quantity: BigDecimal,
    val unit: String,
    val unitCost: BigDecimal,
    val extendedCost: BigDecimal,
    val currency: String,
    val schemaVersion: Int = 1,
)
