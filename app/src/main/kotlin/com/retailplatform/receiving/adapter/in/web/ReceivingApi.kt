package com.retailplatform.receiving.adapter.`in`.web

import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.receiving.application.ReceiveGoods
import com.retailplatform.receiving.application.ReceivingQueries
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.WarehouseId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1/expected-deliveries")
class ExpectedDeliveryController(
    private val queries: ReceivingQueries,
) {
    /** The purchase orders a warehouse still expects, the earliest first. */
    @GetMapping
    fun open(
        @RequestParam warehouseId: String,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<ExpectedDeliveryResponse> {
        val warehouse = mapRequest { WarehouseId.parse(warehouseId) }
        return PageResponse.from(
            queries.openDeliveries(warehouse, pageQuery(page, size)),
            ExpectedDeliveryResponse::from,
        )
    }

    @GetMapping("/{purchaseOrderId}")
    fun get(
        @PathVariable purchaseOrderId: String,
    ): ExpectedDeliveryResponse =
        ExpectedDeliveryResponse.from(queries.expected(mapRequest { PurchaseOrderId.parse(purchaseOrderId) }))
}

@RestController
@RequestMapping("/api/v1/receipts")
class ReceiptController(
    private val receiveGoods: ReceiveGoods,
    private val queries: ReceivingQueries,
) {
    @PostMapping
    fun receive(
        @Valid @RequestBody request: ReceiveGoodsRequest,
    ): ResponseEntity<ReceiptResponse> {
        val id = receiveGoods.handle(request.toCommand())
        return ResponseEntity
            .created(
                URI.create("/api/v1/receipts/$id"),
            ).body(ReceiptResponse.from(queries.receipt(id)))
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): ReceiptResponse = ReceiptResponse.from(queries.receipt(mapRequest { ReceiptId.parse(id) }))

    /** The receipts written against one purchase order, oldest first. */
    @GetMapping
    fun forPurchaseOrder(
        @RequestParam purchaseOrderId: String,
    ): List<ReceiptResponse> =
        queries.receiptsFor(mapRequest { PurchaseOrderId.parse(purchaseOrderId) }).map(ReceiptResponse::from)
}
