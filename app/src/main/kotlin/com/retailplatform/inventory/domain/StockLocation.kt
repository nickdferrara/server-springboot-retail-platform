package com.retailplatform.inventory.domain

import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.UnitOfMeasure
import com.retailplatform.shared.WarehouseId
import java.util.UUID

/** Where stock is held: a warehouse or a store. */
sealed interface StockLocation {
    /** A short name for the kind of location, stored with [id]. */
    val type: String
    val id: UUID

    data class Warehouse(
        val warehouseId: WarehouseId,
    ) : StockLocation {
        override val type get() = WAREHOUSE
        override val id get() = warehouseId.value
    }

    data class Store(
        val storeId: StoreId,
    ) : StockLocation {
        override val type get() = STORE
        override val id get() = storeId.value
    }

    companion object {
        const val WAREHOUSE = "WAREHOUSE"
        const val STORE = "STORE"

        /** @throws IllegalArgumentException unless [type] is `WAREHOUSE` or `STORE`. */
        fun of(
            type: String,
            id: UUID,
        ): StockLocation =
            when (type) {
                WAREHOUSE -> Warehouse(WarehouseId.of(id))
                STORE -> Store(StoreId.of(id))
                else -> throw IllegalArgumentException("Not a stock location type: '$type'")
            }
    }
}

/** What inventory knows about a catalog item: that it exists, and the base unit its stock is counted in. */
data class StockedItem(
    val itemId: ItemId,
    val itemNumber: String,
    val baseUnit: UnitOfMeasure,
)

class ItemNotKnownToInventory(
    itemId: ItemId,
) : BusinessRuleViolation("inventory.unknown-item", "Item $itemId is not in the catalog")

class UnknownStockLocation(
    location: StockLocation,
) : BusinessRuleViolation(
        "inventory.unknown-location",
        "${location.type.lowercase().replaceFirstChar(Char::uppercase)} ${location.id} does not exist",
    )
