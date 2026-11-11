package com.retailplatform.inventory.adapter.`in`.web

import com.retailplatform.inventory.application.AdjustInventory
import com.retailplatform.inventory.application.RecordCycleCount
import com.retailplatform.inventory.application.StockQueries
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.ItemId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/inventory")
class InventoryController(
    private val adjustInventory: AdjustInventory,
    private val recordCycleCount: RecordCycleCount,
    private val queries: StockQueries,
) {
    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    fun adjust(
        @Valid @RequestBody request: AdjustInventoryRequest,
    ): InventoryMovementResponse = InventoryMovementResponse.from(adjustInventory.handle(request.toCommand()))

    /** Answers 201 with the adjustment the count caused, or 204 if the count agreed with the book quantity. */
    @PostMapping("/counts")
    fun count(
        @Valid @RequestBody request: RecordCycleCountRequest,
    ): ResponseEntity<InventoryMovementResponse> {
        val movement = recordCycleCount.handle(request.toCommand())
        return movement?.let { ResponseEntity.status(HttpStatus.CREATED).body(InventoryMovementResponse.from(it)) }
            ?: ResponseEntity.noContent().build()
    }

    /** What one warehouse or store has on hand. */
    @GetMapping("/on-hand")
    fun onHand(
        @RequestParam(required = false) warehouseId: String?,
        @RequestParam(required = false) storeId: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<StockLevelResponse> {
        val location = mapRequest { stockLocation(warehouseId, storeId) }
        return PageResponse.from(queries.onHand(location, pageQuery(page, size)), StockLevelResponse::from)
    }

    @GetMapping("/on-hand/{itemId}")
    fun onHandOfItem(
        @PathVariable itemId: String,
        @RequestParam(required = false) warehouseId: String?,
        @RequestParam(required = false) storeId: String?,
    ): StockLevelResponse {
        val level =
            queries.onHand(
                mapRequest { ItemId.parse(itemId) },
                mapRequest { stockLocation(warehouseId, storeId) },
            )
        return StockLevelResponse.from(level)
    }

    /** The ledger entries for an item at a warehouse or store, newest first. */
    @GetMapping("/movements")
    fun movements(
        @RequestParam itemId: String,
        @RequestParam(required = false) warehouseId: String?,
        @RequestParam(required = false) storeId: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<InventoryMovementResponse> {
        val item = mapRequest { ItemId.parse(itemId) }
        val location = mapRequest { stockLocation(warehouseId, storeId) }
        return PageResponse.from(
            queries.movements(item, location, pageQuery(page, size)),
            InventoryMovementResponse::from,
        )
    }
}
