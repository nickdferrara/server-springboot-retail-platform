package com.retailplatform.inventory.application

import com.retailplatform.inventory.domain.InventoryMovement
import com.retailplatform.inventory.domain.StockLevel
import com.retailplatform.inventory.domain.StockLocation
import com.retailplatform.inventory.domain.StockedItem
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery

/** Port: the catalog items inventory knows about. */
interface StockedItemRepository {
    /** Inserts the item or replaces what is stored for it. */
    fun save(item: StockedItem)

    fun findById(itemId: ItemId): StockedItem?
}

/** Port: where on-hand quantities and average costs are kept, one per item and location. */
interface StockLevelRepository {
    /**
     * Makes this transaction the only one changing the stock of [itemId] at [location] until it ends; another
     * transaction asking for the same lock waits. Taken before the stock level is read, so two changes arriving
     * together are applied one after the other, including the first change, when there is no row to lock yet.
     */
    fun lock(
        itemId: ItemId,
        location: StockLocation,
    )

    /** Inserts a new stock level or updates an existing one; a stale version is refused. */
    fun save(level: StockLevel)

    fun find(
        itemId: ItemId,
        location: StockLocation,
    ): StockLevel?

    /** What a location holds, in the order the items were first stocked there. */
    fun findPage(
        location: StockLocation,
        query: PageQuery,
    ): Page<StockLevel>
}

/** Port: the inventory ledger. Entries can be added and read, never changed or removed. */
interface InventoryLedger {
    fun append(movement: InventoryMovement)

    /** The entries for an item at a location, newest first. */
    fun findPage(
        itemId: ItemId,
        location: StockLocation,
        query: PageQuery,
    ): Page<InventoryMovement>
}
