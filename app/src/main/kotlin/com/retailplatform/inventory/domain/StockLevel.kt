package com.retailplatform.inventory.domain

import com.retailplatform.inventory.api.events.InventoryAdjusted
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InventoryMovementId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Quantity
import com.retailplatform.shared.TypedId
import com.retailplatform.shared.TypedIdFactory
import com.retailplatform.shared.UnitOfMeasure
import java.math.RoundingMode
import java.time.Clock
import java.util.UUID

@JvmInline
value class StockLevelId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<StockLevelId>(::StockLevelId)
}

/**
 * How much of an item one location has on hand and what it cost on average. It is the running total of the
 * inventory ledger for that item and location: every change goes through a method here, which returns the ledger
 * entry to append.
 *
 * Cost is a weighted average per base unit. Stock arriving with a cost of its own moves the average; stock
 * leaving, or arriving without a cost, is valued at the average and leaves it unchanged.
 */
class StockLevel private constructor(
    val id: StockLevelId,
    val itemId: ItemId,
    val location: StockLocation,
    position: StockPosition,
    val version: Int,
) : AggregateRoot() {
    var position: StockPosition = position
        private set

    val onHand: Quantity get() = position.onHand
    val averageCost: Money? get() = position.averageCost

    /** What the stock on hand is worth at average cost; nothing if no cost is known yet. */
    val value: Money? get() = averageCost?.let { it * onHand.amount }

    /**
     * Takes in [quantity] bought at [unitCost], which moves the average cost.
     *
     * @throws QuantityNotInBaseUnit if [quantity] is not in the item's base unit.
     * @throws QuantityNotPositive unless [quantity] is above zero.
     * @throws CostCurrencyMismatch if [unitCost] is in another currency than the average cost.
     */
    fun receive(
        quantity: Quantity,
        unitCost: Money,
        businessDate: BusinessDate,
        source: MovementSource,
        clock: Clock,
    ): InventoryMovement {
        requireBaseUnit(quantity)
        if (!quantity.isPositive) throw QuantityNotPositive(quantity)
        val entry = Entry(MovementType.RECEIPT, reason = null, quantity, businessDate, source)
        return post(entry, unitCost, clock)
    }

    /**
     * Gives [quantity] back to its vendor. Like all stock leaving, it is valued at average cost and leaves the
     * average unchanged; [creditedCost], what the vendor credits per unit, is used only when no cost is known
     * yet. On-hand may go below zero, because what the driver took was on the shelf whatever the books say.
     *
     * @throws QuantityNotInBaseUnit if [quantity] is not in the item's base unit.
     * @throws QuantityNotPositive unless [quantity] is above zero.
     */
    fun returnToVendor(
        quantity: Quantity,
        creditedCost: Money,
        businessDate: BusinessDate,
        source: MovementSource,
        clock: Clock,
    ): InventoryMovement {
        requireBaseUnit(quantity)
        if (!quantity.isPositive) throw QuantityNotPositive(quantity)
        val entry = Entry(MovementType.VENDOR_RETURN, reason = null, -quantity, businessDate, source)
        return post(entry, creditedCost.takeIf { averageCost == null }, clock)
    }

    /**
     * Takes back out [quantity] that a receipt brought in by mistake, at the [unitCost] it came in at, so the
     * receipt is undone exactly: the average cost moves back. On-hand may go below zero.
     *
     * @throws QuantityNotInBaseUnit if [quantity] is not in the item's base unit.
     * @throws QuantityNotPositive unless [quantity] is above zero.
     * @throws CostCurrencyMismatch if [unitCost] is in another currency than the average cost.
     */
    fun reverseReceipt(
        quantity: Quantity,
        unitCost: Money,
        businessDate: BusinessDate,
        source: MovementSource,
        clock: Clock,
    ): InventoryMovement {
        requireBaseUnit(quantity)
        if (!quantity.isPositive) throw QuantityNotPositive(quantity)
        return post(Entry(MovementType.RECEIPT, reason = null, -quantity, businessDate, source), unitCost, clock)
    }

    /**
     * Puts back [quantity] that was recorded as returned to its vendor by mistake. Like the return it undoes, it
     * is valued at average cost; [creditedCost] is used only when no cost is known yet.
     *
     * @throws QuantityNotInBaseUnit if [quantity] is not in the item's base unit.
     * @throws QuantityNotPositive unless [quantity] is above zero.
     */
    fun reverseVendorReturn(
        quantity: Quantity,
        creditedCost: Money,
        businessDate: BusinessDate,
        source: MovementSource,
        clock: Clock,
    ): InventoryMovement {
        requireBaseUnit(quantity)
        if (!quantity.isPositive) throw QuantityNotPositive(quantity)
        val entry = Entry(MovementType.VENDOR_RETURN, reason = null, quantity, businessDate, source)
        return post(entry, creditedCost.takeIf { averageCost == null }, clock)
    }

    /**
     * Corrects on-hand by [quantity], raising [InventoryAdjusted]. A reduction is valued at average cost. An
     * increase is valued at [unitCost] if given, otherwise at average cost.
     *
     * @throws QuantityNotInBaseUnit if [quantity] is not in the item's base unit.
     * @throws AdjustmentChangesNothing if [quantity] is zero.
     * @throws AdjustmentMustReduceStock if a shrink or damage adjustment is not a reduction.
     * @throws AdjustmentExceedsOnHand if a shrink or damage adjustment takes more than is on hand.
     * @throws CostNotKnown if no cost is given and the location has no average cost yet.
     */
    fun adjust(
        quantity: Quantity,
        reason: AdjustmentReason,
        unitCost: Money?,
        clock: Clock,
    ): InventoryMovement {
        requireBaseUnit(quantity)
        checkAdjustment(quantity, reason)
        val entry = Entry(MovementType.ADJUSTMENT, reason, quantity, BusinessDate.today(clock), MovementSource.NONE)
        val movement = post(entry, unitCost.takeIf { quantity.isPositive }, clock)
        raise(adjusted(movement, reason, clock))
        return movement
    }

    /**
     * Sets on-hand to what was physically [counted], adjusting by the difference for reason
     * [AdjustmentReason.COUNT].
     *
     * @return the ledger entry, or `null` if the count agrees with the book quantity.
     * @throws QuantityNotInBaseUnit if [counted] is not in the item's base unit.
     * @throws CountIsNegative if [counted] is below zero.
     * @throws CostNotKnown if the count finds stock, no cost is given, and the location has no average cost yet.
     */
    fun count(
        counted: Quantity,
        unitCost: Money?,
        clock: Clock,
    ): InventoryMovement? {
        requireBaseUnit(counted)
        if (counted.isNegative) throw CountIsNegative(counted)
        val difference = counted - onHand
        return if (difference.isZero) null else adjust(difference, AdjustmentReason.COUNT, unitCost, clock)
    }

    private fun checkAdjustment(
        quantity: Quantity,
        reason: AdjustmentReason,
    ) {
        val violation =
            when {
                quantity.isZero -> AdjustmentChangesNothing()
                reason == AdjustmentReason.COUNT -> null
                quantity.isPositive -> AdjustmentMustReduceStock(reason)
                onHand + quantity < Quantity.zero(onHand.unit) -> AdjustmentExceedsOnHand(quantity, onHand)
                else -> null
            }
        if (violation != null) throw violation
    }

    /** Applies [entry] at [ownCost] if it brings one, otherwise at average cost, and returns the ledger entry. */
    private fun post(
        entry: Entry,
        ownCost: Money?,
        clock: Clock,
    ): InventoryMovement {
        val average = averageCost
        if (ownCost != null && average != null && ownCost.currency != average.currency) {
            throw CostCurrencyMismatch(ownCost, average)
        }
        val unitCost = ownCost ?: average ?: throw CostNotKnown(itemId)
        val newOnHand = onHand + entry.quantity
        position = StockPosition(newOnHand, if (ownCost == null) average else averageAfter(entry.quantity, ownCost))
        return InventoryMovement(
            id = InventoryMovementId.generate(clock),
            itemId = itemId,
            location = location,
            type = entry.type,
            reason = entry.reason,
            quantity = entry.quantity,
            unitCost = unitCost,
            after = position,
            businessDate = entry.businessDate,
            occurredAt = clock.instant(),
            source = entry.source,
        )
    }

    /**
     * The weighted average once [incoming] at [cost] joins what is on hand, or leaves it when [incoming] is
     * negative. Stock that is at or below zero carries no value worth averaging, so the incoming cost becomes the
     * average.
     */
    private fun averageAfter(
        incoming: Quantity,
        cost: Money,
    ): Money {
        val average = averageCost
        if (average == null || !onHand.isPositive) return cost
        val totalValue = onHand.amount * average.amount + incoming.amount * cost.amount
        val totalQuantity = onHand.amount + incoming.amount
        // Stock taken back out can leave nothing, or nothing of value, to average: the average then stands.
        return if (totalQuantity.signum() <= 0 || totalValue.signum() < 0) {
            average
        } else {
            Money.of(totalValue.divide(totalQuantity, Money.SCALE, RoundingMode.HALF_EVEN), cost.currency)
        }
    }

    private fun requireBaseUnit(quantity: Quantity) {
        if (quantity.unit != onHand.unit) throw QuantityNotInBaseUnit(quantity, onHand.unit)
    }

    private fun adjusted(
        movement: InventoryMovement,
        reason: AdjustmentReason,
        clock: Clock,
    ) = InventoryAdjusted(
        eventId = EventId.generate(clock),
        occurredAt = movement.occurredAt,
        businessDate = movement.businessDate,
        movementId = movement.id,
        itemId = itemId,
        warehouseId = (location as? StockLocation.Warehouse)?.warehouseId,
        storeId = (location as? StockLocation.Store)?.storeId,
        reason = reason.name,
        quantity = movement.quantity.amount,
        unit = movement.quantity.unit.code,
        unitCost = movement.unitCost.amount,
        extendedCost = movement.extendedCost.amount,
        currency = movement.unitCost.currency.currencyCode,
    )

    private data class Entry(
        val type: MovementType,
        val reason: AdjustmentReason?,
        val quantity: Quantity,
        val businessDate: BusinessDate,
        val source: MovementSource,
    )

    companion object {
        /** A location's first record of an item: nothing on hand, no cost known. */
        fun empty(
            itemId: ItemId,
            location: StockLocation,
            baseUnit: UnitOfMeasure,
            clock: Clock,
        ): StockLevel =
            StockLevel(
                StockLevelId.generate(clock),
                itemId,
                location,
                StockPosition(Quantity.zero(baseUnit), averageCost = null),
                version = 0,
            )

        /** Rebuilds a stock level from stored state. Raises no events. */
        fun rehydrate(
            id: StockLevelId,
            itemId: ItemId,
            location: StockLocation,
            position: StockPosition,
            version: Int,
        ): StockLevel = StockLevel(id, itemId, location, position, version)
    }
}

class QuantityNotInBaseUnit(
    quantity: Quantity,
    baseUnit: UnitOfMeasure,
) : BusinessRuleViolation(
        "inventory.quantity-not-in-base-unit",
        "Stock of this item is counted in $baseUnit, not ${quantity.unit}",
    )

class QuantityNotPositive(
    quantity: Quantity,
) : BusinessRuleViolation("inventory.quantity-not-positive", "A receipt must be more than zero: $quantity")

class AdjustmentChangesNothing :
    BusinessRuleViolation("inventory.adjustment-changes-nothing", "An adjustment of zero changes nothing")

class AdjustmentMustReduceStock(
    reason: AdjustmentReason,
) : BusinessRuleViolation(
        "inventory.adjustment-must-reduce-stock",
        "A ${reason.name.lowercase()} adjustment takes stock away; use a count to add stock",
    )

class AdjustmentExceedsOnHand(
    quantity: Quantity,
    onHand: Quantity,
) : BusinessRuleViolation(
        "inventory.adjustment-exceeds-on-hand",
        "Cannot take ${-quantity} from $onHand on hand; record a count instead",
    )

class CountIsNegative(
    counted: Quantity,
) : BusinessRuleViolation("inventory.count-is-negative", "A count cannot be below zero: $counted")

class CostNotKnown(
    itemId: ItemId,
) : BusinessRuleViolation(
        "inventory.cost-not-known",
        "Item $itemId has no average cost at this location yet; give a unit cost",
    )

class CostCurrencyMismatch(
    cost: Money,
    average: Money,
) : BusinessRuleViolation(
        "inventory.cost-currency-mismatch",
        "A cost in ${cost.currency} cannot join stock valued in ${average.currency}",
    )
