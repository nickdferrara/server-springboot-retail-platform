package com.retailplatform.locations.application

import com.retailplatform.locations.domain.Warehouse
import com.retailplatform.locations.domain.WarehouseNumber
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.WarehouseId

/** Port: where warehouses are kept. */
interface WarehouseRepository {
    /** Inserts a new warehouse or updates an existing one; a stale version is refused. */
    fun save(warehouse: Warehouse)

    fun findById(id: WarehouseId): Warehouse?

    fun existsById(id: WarehouseId): Boolean

    fun existsByNumber(number: WarehouseNumber): Boolean

    /** Warehouses ordered by number. */
    fun findPage(query: PageQuery): Page<Warehouse>
}
