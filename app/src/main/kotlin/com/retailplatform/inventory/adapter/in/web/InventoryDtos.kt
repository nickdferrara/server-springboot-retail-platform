package com.retailplatform.inventory.adapter.`in`.web

import com.retailplatform.inventory.application.AdjustInventoryCommand
import com.retailplatform.inventory.application.RecordCycleCountCommand
import com.retailplatform.inventory.domain.AdjustmentReason
import com.retailplatform.inventory.domain.InventoryMovement
import com.retailplatform.inventory.domain.StockLevel
import com.retailplatform.inventory.domain.StockLocation
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.QuantityDto
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.WarehouseId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate

/** @throws IllegalArgumentException unless exactly one of the two identifiers is given and well formed. */
fun stockLocation(
    warehouseId: String?,
    storeId: String?,
): StockLocation {
    require((warehouseId == null) != (storeId == null)) { "Give exactly one of warehouseId and storeId" }
    return warehouseId?.let { StockLocation.Warehouse(WarehouseId.parse(it)) }
        ?: StockLocation.Store(StoreId.parse(requireNotNull(storeId)))
}

data class AdjustInventoryRequest(
    @field:NotBlank
    val itemId: String?,
    val warehouseId: String? = null,
    val storeId: String? = null,
    /** Signed: negative takes stock away. */
    @field:NotNull @field:Valid
    val quantity: QuantityDto?,
    @field:NotNull
    val reason: AdjustmentReason?,
    /** Values stock added by a count; left out, the location's average cost is used. */
    @field:Valid
    val unitCost: MoneyDto? = null,
) {
    fun toCommand(): AdjustInventoryCommand =
        mapRequest {
            AdjustInventoryCommand(
                itemId = ItemId.parse(requireNotNull(itemId)),
                location = stockLocation(warehouseId, storeId),
                quantity = requireNotNull(quantity).toDomain(),
                reason = requireNotNull(reason),
                unitCost = unitCost?.toDomain(),
            )
        }
}

data class RecordCycleCountRequest(
    @field:NotBlank
    val itemId: String?,
    val warehouseId: String? = null,
    val storeId: String? = null,
    @field:NotNull @field:Valid
    val counted: QuantityDto?,
    /** Values stock the count finds; left out, the location's average cost is used. */
    @field:Valid
    val unitCost: MoneyDto? = null,
) {
    fun toCommand(): RecordCycleCountCommand =
        mapRequest {
            RecordCycleCountCommand(
                itemId = ItemId.parse(requireNotNull(itemId)),
                location = stockLocation(warehouseId, storeId),
                counted = requireNotNull(counted).toDomain(),
                unitCost = unitCost?.toDomain(),
            )
        }
}

data class StockLevelResponse(
    val itemId: String,
    val warehouseId: String?,
    val storeId: String?,
    val onHand: QuantityDto,
    val averageCost: MoneyDto?,
    val value: MoneyDto?,
) {
    companion object {
        fun from(level: StockLevel) =
            StockLevelResponse(
                itemId = level.itemId.toString(),
                warehouseId = level.location.warehouseId(),
                storeId = level.location.storeId(),
                onHand = QuantityDto.from(level.onHand),
                averageCost = level.averageCost?.let(MoneyDto::from),
                value = level.value?.let(MoneyDto::from),
            )
    }
}

data class InventoryMovementResponse(
    val id: String,
    val itemId: String,
    val warehouseId: String?,
    val storeId: String?,
    val type: String,
    val reason: String?,
    val quantity: QuantityDto,
    val unitCost: MoneyDto,
    val extendedCost: MoneyDto,
    val onHandAfter: QuantityDto,
    val averageCostAfter: MoneyDto?,
    val businessDate: LocalDate,
    val occurredAt: Instant,
    val sourceReference: String?,
) {
    companion object {
        fun from(movement: InventoryMovement) =
            InventoryMovementResponse(
                id = movement.id.toString(),
                itemId = movement.itemId.toString(),
                warehouseId = movement.location.warehouseId(),
                storeId = movement.location.storeId(),
                type = movement.type.name,
                reason = movement.reason?.name,
                quantity = QuantityDto.from(movement.quantity),
                unitCost = MoneyDto.from(movement.unitCost),
                extendedCost = MoneyDto.from(movement.extendedCost),
                onHandAfter = QuantityDto.from(movement.after.onHand),
                averageCostAfter = movement.after.averageCost?.let(MoneyDto::from),
                businessDate = movement.businessDate.value,
                occurredAt = movement.occurredAt,
                sourceReference = movement.source.reference,
            )
    }
}

private fun StockLocation.warehouseId(): String? = (this as? StockLocation.Warehouse)?.warehouseId?.toString()

private fun StockLocation.storeId(): String? = (this as? StockLocation.Store)?.storeId?.toString()
