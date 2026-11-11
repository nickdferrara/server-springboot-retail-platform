package com.retailplatform.receiving.adapter.`in`.web

import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.receiving.application.ReceiveGoodsCommand
import com.retailplatform.receiving.domain.CountedLine
import com.retailplatform.receiving.domain.ExpectedDelivery
import com.retailplatform.receiving.domain.Receipt
import com.retailplatform.shared.PurchaseOrderId
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDate

data class CountedLineRequest(
    @field:NotNull @field:Min(1)
    val lineNumber: Int?,
    @field:NotNull @field:Min(0)
    val casesReceived: Int?,
    @field:Min(0)
    val casesDamaged: Int = 0,
)

data class ReceiveGoodsRequest(
    @field:NotBlank
    val purchaseOrderId: String?,
    @field:Valid
    val lines: List<CountedLineRequest> = emptyList(),
    /** True when nothing more will come on the order; what is missing is then recorded as short. */
    val finalDelivery: Boolean = false,
) {
    fun toCommand(): ReceiveGoodsCommand =
        mapRequest {
            ReceiveGoodsCommand(
                purchaseOrderId = PurchaseOrderId.parse(requireNotNull(purchaseOrderId)),
                counted =
                    lines.map {
                        CountedLine.of(requireNotNull(it.lineNumber), requireNotNull(it.casesReceived), it.casesDamaged)
                    },
                finalDelivery = finalDelivery,
            )
        }
}

data class ExpectedLineResponse(
    val lineNumber: Int,
    val itemId: String,
    val vendorSku: String,
    val casePack: Int,
    val casesOrdered: Int,
    val casesReceived: Int,
    val casesOutstanding: Int,
)

data class ExpectedDeliveryResponse(
    val purchaseOrderId: String,
    val purchaseOrderNumber: String,
    val vendorId: String,
    val warehouseId: String,
    val expectedOn: LocalDate,
    val status: String,
    val lines: List<ExpectedLineResponse>,
) {
    companion object {
        fun from(delivery: ExpectedDelivery) =
            ExpectedDeliveryResponse(
                purchaseOrderId = delivery.against.purchaseOrderId.toString(),
                purchaseOrderNumber = delivery.against.purchaseOrderNumber,
                vendorId = delivery.against.vendorId.toString(),
                warehouseId = delivery.against.warehouseId.toString(),
                expectedOn = delivery.expectedOn.value,
                status = delivery.status.name,
                lines =
                    delivery.lines.map {
                        ExpectedLineResponse(
                            lineNumber = it.lineNumber,
                            itemId = it.itemId.toString(),
                            vendorSku = it.vendorSku,
                            casePack = it.casePack,
                            casesOrdered = it.casesOrdered,
                            casesReceived = it.casesReceived,
                            casesOutstanding = it.casesOutstanding,
                        )
                    },
            )
    }
}

data class ReceiptLineResponse(
    val lineNumber: Int,
    val itemId: String,
    val casePack: Int,
    val caseCost: MoneyDto,
    val casesReceived: Int,
    val casesDamaged: Int,
    val casesOver: Int,
    val casesShort: Int,
    val extendedCost: MoneyDto,
)

data class ReceiptResponse(
    val id: String,
    val number: String,
    val purchaseOrderId: String,
    val purchaseOrderNumber: String,
    val vendorId: String,
    val warehouseId: String,
    val receivedOn: LocalDate,
    val receivedBy: String,
    val finalDelivery: Boolean,
    val lines: List<ReceiptLineResponse>,
) {
    companion object {
        fun from(receipt: Receipt) =
            ReceiptResponse(
                id = receipt.id.toString(),
                number = receipt.number.value,
                purchaseOrderId = receipt.against.purchaseOrderId.toString(),
                purchaseOrderNumber = receipt.against.purchaseOrderNumber,
                vendorId = receipt.against.vendorId.toString(),
                warehouseId = receipt.against.warehouseId.toString(),
                receivedOn = receipt.receivedOn.value,
                receivedBy = receipt.receivedBy,
                finalDelivery = receipt.finalDelivery,
                lines =
                    receipt.lines.map {
                        ReceiptLineResponse(
                            lineNumber = it.lineNumber,
                            itemId = it.itemId.toString(),
                            casePack = it.casePack,
                            caseCost = MoneyDto.from(it.caseCost),
                            casesReceived = it.casesReceived,
                            casesDamaged = it.casesDamaged,
                            casesOver = it.casesOver,
                            casesShort = it.casesShort,
                            extendedCost = MoneyDto.from(it.extendedCost),
                        )
                    },
            )
    }
}
