package com.retailplatform.purchasing.adapter.`in`.web

import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.purchasing.application.ApprovalThresholdQueries
import com.retailplatform.purchasing.application.ApprovePurchaseOrder
import com.retailplatform.purchasing.application.CancelPurchaseOrder
import com.retailplatform.purchasing.application.CancelPurchaseOrderCommand
import com.retailplatform.purchasing.application.CreatePurchaseOrder
import com.retailplatform.purchasing.application.PurchaseOrderQueries
import com.retailplatform.purchasing.application.RevisePurchaseOrder
import com.retailplatform.purchasing.application.RevisePurchaseOrderCommand
import com.retailplatform.purchasing.application.SendPurchaseOrder
import com.retailplatform.purchasing.application.SetApprovalThreshold
import com.retailplatform.purchasing.domain.CancellationReason
import com.retailplatform.shared.PurchaseOrderId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1/purchase-orders")
class PurchaseOrderController(
    private val createPurchaseOrder: CreatePurchaseOrder,
    private val revisePurchaseOrder: RevisePurchaseOrder,
    private val approvePurchaseOrder: ApprovePurchaseOrder,
    private val sendPurchaseOrder: SendPurchaseOrder,
    private val cancelPurchaseOrder: CancelPurchaseOrder,
    private val queries: PurchaseOrderQueries,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreatePurchaseOrderRequest,
    ): ResponseEntity<PurchaseOrderResponse> {
        val id = createPurchaseOrder.handle(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/purchase-orders/$id"))
            .body(PurchaseOrderResponse.from(queries.find(id)))
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<PurchaseOrderResponse> =
        PageResponse.from(queries.list(pageQuery(page, size)), PurchaseOrderResponse::from)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): PurchaseOrderResponse = PurchaseOrderResponse.from(queries.find(purchaseOrderId(id)))

    @PutMapping("/{id}")
    fun revise(
        @PathVariable id: String,
        @Valid @RequestBody request: PurchaseOrderTermsRequest,
    ): PurchaseOrderResponse {
        val purchaseOrderId = purchaseOrderId(id)
        revisePurchaseOrder.handle(RevisePurchaseOrderCommand(purchaseOrderId, request.toInput()))
        return PurchaseOrderResponse.from(queries.find(purchaseOrderId))
    }

    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: String,
    ): PurchaseOrderResponse {
        val purchaseOrderId = purchaseOrderId(id)
        approvePurchaseOrder.handle(purchaseOrderId)
        return PurchaseOrderResponse.from(queries.find(purchaseOrderId))
    }

    @PostMapping("/{id}/send")
    fun send(
        @PathVariable id: String,
    ): PurchaseOrderResponse {
        val purchaseOrderId = purchaseOrderId(id)
        sendPurchaseOrder.handle(purchaseOrderId)
        return PurchaseOrderResponse.from(queries.find(purchaseOrderId))
    }

    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: String,
        @Valid @RequestBody request: CancelPurchaseOrderRequest,
    ): PurchaseOrderResponse {
        val purchaseOrderId = purchaseOrderId(id)
        cancelPurchaseOrder.handle(
            mapRequest {
                CancelPurchaseOrderCommand(
                    purchaseOrderId,
                    CancellationReason.of(requireNotNull(request.reason)),
                )
            },
        )
        return PurchaseOrderResponse.from(queries.find(purchaseOrderId))
    }

    private fun purchaseOrderId(id: String): PurchaseOrderId = mapRequest { PurchaseOrderId.parse(id) }
}

@RestController
@RequestMapping("/api/v1/purchasing/approval-threshold")
class ApprovalThresholdController(
    private val setApprovalThreshold: SetApprovalThreshold,
    private val queries: ApprovalThresholdQueries,
) {
    @PutMapping
    fun set(
        @Valid @RequestBody request: ApprovalThresholdDto,
    ): ApprovalThresholdDto {
        val threshold = request.toDomain()
        setApprovalThreshold.handle(threshold)
        return ApprovalThresholdDto.from(threshold)
    }

    /** The threshold in effect today. */
    @GetMapping
    fun current(): ApprovalThresholdDto = ApprovalThresholdDto.from(queries.current())
}
