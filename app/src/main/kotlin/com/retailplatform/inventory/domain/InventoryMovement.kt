package com.retailplatform.inventory.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InventoryMovementId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Quantity
import java.time.Instant

/** What kind of business fact moved stock. */
enum class MovementType {
    /** Goods arrived from a vendor at their purchase cost. */
    RECEIPT,

    /** On-hand was corrected: shrink, damage, or a count. */
    ADJUSTMENT,
}

/** Why on-hand was corrected. */
enum class AdjustmentReason {
    /** Stock that is gone without a sale: theft, loss, or spoilage. Always a reduction. */
    SHRINK,

    /** Stock that can no longer be sold. Always a reduction. */
    DAMAGE,

    /** The difference between a physical count and the book quantity, up or down. */
    COUNT,
}

/** The business fact behind a movement: the document users know it by and the event that carried it, if any. */
data class MovementSource(
    val reference: String?,
    val eventId: EventId?,
) {
    companion object {
        val NONE = MovementSource(reference = null, eventId = null)
    }
}

/** The quantity and average cost at a location once a movement has been applied. */
data class StockPosition(
    val onHand: Quantity,
    val averageCost: Money?,
)

/**
 * One entry in the inventory ledger: [quantity] of an item moved into (positive) or out of (negative) a location
 * at [unitCost] per base unit. Entries are never changed or removed; a mistake is corrected by a further entry.
 */
data class InventoryMovement(
    val id: InventoryMovementId,
    val itemId: ItemId,
    val location: StockLocation,
    val type: MovementType,
    val reason: AdjustmentReason?,
    val quantity: Quantity,
    val unitCost: Money,
    val after: StockPosition,
    val businessDate: BusinessDate,
    val occurredAt: Instant,
    val source: MovementSource,
) {
    /** The value moved: [quantity] times [unitCost], negative when stock left. */
    val extendedCost: Money get() = unitCost * quantity.amount
}
