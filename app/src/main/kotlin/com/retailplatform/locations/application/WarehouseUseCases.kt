package com.retailplatform.locations.application

import com.retailplatform.locations.api.WarehouseDirectory
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.Warehouse
import com.retailplatform.locations.domain.WarehouseNumber
import com.retailplatform.locations.domain.WarehouseNumberAlreadyInUse
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.WarehouseId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneId

data class CreateWarehouseCommand(
    val number: WarehouseNumber,
    val name: LocationName,
    val timeZone: ZoneId,
)

/** Use case: set up a new warehouse. */
@Service
class CreateWarehouse(
    private val warehouses: WarehouseRepository,
    private val clock: Clock,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: CreateWarehouseCommand): WarehouseId {
        if (warehouses.existsByNumber(command.number)) throw WarehouseNumberAlreadyInUse(command.number)

        val warehouse = Warehouse.create(command.number, command.name, command.timeZone, clock)
        warehouses.save(warehouse)
        return warehouse.id
    }
}

data class UpdateWarehouseDetailsCommand(
    val warehouseId: WarehouseId,
    val name: LocationName,
    val timeZone: ZoneId,
)

/** Use case: change a warehouse's name and time zone. */
@Service
class UpdateWarehouseDetails(
    private val warehouses: WarehouseRepository,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: UpdateWarehouseDetailsCommand) {
        val warehouse =
            warehouses.findById(command.warehouseId)
                ?: throw ResourceNotFound("Warehouse", command.warehouseId.toString())

        warehouse.updateDetails(command.name, command.timeZone)
        warehouses.save(warehouse)
    }
}

/** Queries: warehouses by identifier and by page; also answers other modules through [WarehouseDirectory]. */
@Service
@Transactional(readOnly = true)
class WarehouseQueries(
    private val warehouses: WarehouseRepository,
) : WarehouseDirectory {
    fun find(id: WarehouseId): Warehouse = warehouses.findById(id) ?: throw ResourceNotFound("Warehouse", id.toString())

    fun list(query: PageQuery): Page<Warehouse> = warehouses.findPage(query)

    override fun exists(warehouseId: WarehouseId): Boolean = warehouses.existsById(warehouseId)
}
