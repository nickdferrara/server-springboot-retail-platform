package com.retailplatform.locations.api

import com.retailplatform.shared.WarehouseId

/** Lets other modules check a warehouse reference before they store it. */
interface WarehouseDirectory {
    fun exists(warehouseId: WarehouseId): Boolean
}
