package com.retailplatform.locations.api

import com.retailplatform.shared.StoreId
import com.retailplatform.shared.WarehouseId

/** Lets other modules check a warehouse reference before they store it. */
interface WarehouseDirectory {
    fun exists(warehouseId: WarehouseId): Boolean
}

/** Lets other modules check a store reference before they store it. A closed store still exists. */
interface StoreDirectory {
    fun exists(storeId: StoreId): Boolean
}
