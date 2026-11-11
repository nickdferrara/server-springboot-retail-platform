package com.retailplatform.inventory.application

import com.retailplatform.inventory.domain.AdjustmentReason
import com.retailplatform.inventory.domain.InventoryMovement
import com.retailplatform.inventory.domain.ItemNotKnownToInventory
import com.retailplatform.inventory.domain.StockLevel
import com.retailplatform.inventory.domain.StockLocation
import com.retailplatform.inventory.domain.UnknownStockLocation
import com.retailplatform.locations.api.StoreDirectory
import com.retailplatform.locations.api.WarehouseDirectory
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.Quantity
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Finds the stock level a command changes, starting an empty one the first time an item is stocked somewhere. */
@Component
class StockLevels(
    private val levels: StockLevelRepository,
    private val items: StockedItemRepository,
    private val warehouses: WarehouseDirectory,
    private val stores: StoreDirectory,
    private val clock: Clock,
) {
    /**
     * @throws ItemNotKnownToInventory if the item is not in the catalog.
     * @throws UnknownStockLocation if the warehouse or store does not exist.
     */
    fun at(
        itemId: ItemId,
        location: StockLocation,
    ): StockLevel {
        val item = items.findById(itemId) ?: throw ItemNotKnownToInventory(itemId)
        return levels.find(itemId, location) ?: run {
            if (!exists(location)) throw UnknownStockLocation(location)
            StockLevel.empty(itemId, location, item.baseUnit, clock)
        }
    }

    private fun exists(location: StockLocation): Boolean =
        when (location) {
            is StockLocation.Warehouse -> warehouses.exists(location.warehouseId)
            is StockLocation.Store -> stores.exists(location.storeId)
        }
}

/** Saves a changed stock level with the ledger entry that explains the change, and publishes what it raised. */
@Component
class StockPosting(
    private val levels: StockLevelRepository,
    private val ledger: InventoryLedger,
    private val events: ApplicationEventPublisher,
) {
    fun post(
        level: StockLevel,
        movement: InventoryMovement,
    ) {
        levels.save(level)
        ledger.append(movement)
        level.releaseEvents().forEach(events::publishEvent)
    }
}

data class AdjustInventoryCommand(
    val itemId: ItemId,
    val location: StockLocation,
    val quantity: Quantity,
    val reason: AdjustmentReason,
    val unitCost: Money?,
)

/** Use case: correct on-hand for shrink, damage, or a counted difference. */
@Service
class AdjustInventory(
    private val stockLevels: StockLevels,
    private val posting: StockPosting,
    private val clock: Clock,
) {
    @RequiresRole(Roles.INVENTORY_CONTROLLER)
    @Transactional
    fun handle(command: AdjustInventoryCommand): InventoryMovement {
        val level = stockLevels.at(command.itemId, command.location)
        val movement = level.adjust(command.quantity, command.reason, command.unitCost, clock)
        posting.post(level, movement)
        return movement
    }
}

data class RecordCycleCountCommand(
    val itemId: ItemId,
    val location: StockLocation,
    val counted: Quantity,
    val unitCost: Money?,
)

/** Use case: record what a physical count found, adjusting on-hand to it. */
@Service
class RecordCycleCount(
    private val stockLevels: StockLevels,
    private val posting: StockPosting,
    private val clock: Clock,
) {
    /** @return the adjustment the count caused, or `null` if it agreed with the book quantity. */
    @RequiresRole(Roles.INVENTORY_CONTROLLER)
    @Transactional
    fun handle(command: RecordCycleCountCommand): InventoryMovement? {
        val level = stockLevels.at(command.itemId, command.location)
        return level.count(command.counted, command.unitCost, clock)?.also { posting.post(level, it) }
    }
}

/** Queries: what a location has on hand, and the ledger entries behind it. */
@Service
@Transactional(readOnly = true)
class StockQueries(
    private val stockLevels: StockLevels,
    private val levels: StockLevelRepository,
    private val ledger: InventoryLedger,
) {
    /** The stock level of an item at a location; nothing on hand if it was never stocked there. */
    fun onHand(
        itemId: ItemId,
        location: StockLocation,
    ): StockLevel = stockLevels.at(itemId, location)

    fun onHand(
        location: StockLocation,
        query: PageQuery,
    ): Page<StockLevel> = levels.findPage(location, query)

    fun movements(
        itemId: ItemId,
        location: StockLocation,
        query: PageQuery,
    ): Page<InventoryMovement> = ledger.findPage(itemId, location, query)
}
