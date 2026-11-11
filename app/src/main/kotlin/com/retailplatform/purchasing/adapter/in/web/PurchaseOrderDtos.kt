package com.retailplatform.purchasing.adapter.`in`.web

import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.purchasing.application.CreatePurchaseOrderCommand
import com.retailplatform.purchasing.application.PurchaseOrderTermsInput
import com.retailplatform.purchasing.domain.ApprovalThreshold
import com.retailplatform.purchasing.domain.CancellationReason
import com.retailplatform.purchasing.domain.PurchaseOrder
import com.retailplatform.purchasing.domain.PurchaseOrderLine
import com.retailplatform.purchasing.domain.PurchaseOrderState
import com.retailplatform.purchasing.domain.RequestedLine
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDate

data class PurchaseOrderLineRequest(
    @field:NotBlank
    val itemId: String?,
    @field:NotNull @field:Min(1)
    val cases: Int?,
    /** Leave out to order at the vendor's list cost. */
    @field:Valid
    val caseCost: MoneyDto? = null,
) {
    fun toDomain(): RequestedLine =
        RequestedLine.of(ItemId.parse(requireNotNull(itemId)), requireNotNull(cases), caseCost?.toDomain())
}

data class PurchaseOrderTermsRequest(
    @field:NotBlank
    val warehouseId: String?,
    @field:NotNull
    val expectedOn: LocalDate?,
    @field:NotEmpty @field:Valid
    val lines: List<PurchaseOrderLineRequest>?,
) {
    fun toInput(): PurchaseOrderTermsInput =
        mapRequest {
            PurchaseOrderTermsInput(
                warehouseId = WarehouseId.parse(requireNotNull(warehouseId)),
                expectedOn = BusinessDate(requireNotNull(expectedOn)),
                lines = requireNotNull(lines).map { it.toDomain() },
            )
        }
}

data class CreatePurchaseOrderRequest(
    @field:NotBlank
    val vendorId: String?,
    @field:NotNull @field:Valid
    val terms: PurchaseOrderTermsRequest?,
) {
    fun toCommand(): CreatePurchaseOrderCommand =
        CreatePurchaseOrderCommand(
            vendorId = mapRequest { VendorId.parse(requireNotNull(vendorId)) },
            terms = requireNotNull(terms).toInput(),
        )
}

data class CancelPurchaseOrderRequest(
    @field:NotBlank @field:Size(max = CancellationReason.MAX_LENGTH)
    val reason: String?,
)

data class ApprovalThresholdDto(
    @field:NotNull @field:Valid
    val amount: MoneyDto?,
    @field:NotNull
    val effectiveFrom: LocalDate?,
) {
    fun toDomain(): ApprovalThreshold =
        mapRequest {
            ApprovalThreshold.of(
                requireNotNull(amount).toDomain(),
                BusinessDate(requireNotNull(effectiveFrom)),
            )
        }

    companion object {
        fun from(threshold: ApprovalThreshold) =
            ApprovalThresholdDto(MoneyDto.from(threshold.amount), threshold.effectiveFrom.value)
    }
}

data class PurchaseOrderLineResponse(
    val lineNumber: Int,
    val itemId: String,
    val vendorSku: String,
    val casePack: Int,
    val cases: Int,
    val caseCost: MoneyDto,
    val extendedCost: MoneyDto,
) {
    companion object {
        fun from(
            lineNumber: Int,
            line: PurchaseOrderLine,
        ) = PurchaseOrderLineResponse(
            lineNumber = lineNumber,
            itemId = line.itemId.toString(),
            vendorSku = line.vendorSku,
            casePack = line.casePack,
            cases = line.cases,
            caseCost = MoneyDto.from(line.caseCost),
            extendedCost = MoneyDto.from(line.extendedCost),
        )
    }
}

data class PurchaseOrderResponse(
    val id: String,
    val number: String,
    val vendorId: String,
    val warehouseId: String,
    val buyer: String,
    val expectedOn: LocalDate,
    val status: String,
    val approvedBy: String?,
    val approvedOn: LocalDate?,
    val sentOn: LocalDate?,
    val cancelledOn: LocalDate?,
    val cancelReason: String?,
    val total: MoneyDto,
    val lines: List<PurchaseOrderLineResponse>,
) {
    companion object {
        fun from(order: PurchaseOrder): PurchaseOrderResponse {
            val cancelled = order.state as? PurchaseOrderState.Cancelled
            return PurchaseOrderResponse(
                id = order.id.toString(),
                number = order.number.value,
                vendorId = order.vendorId.toString(),
                warehouseId = order.terms.warehouseId.toString(),
                buyer = order.buyer,
                expectedOn = order.terms.expectedOn.value,
                status = order.state.code,
                approvedBy = order.state.approval?.approvedBy,
                approvedOn =
                    order.state.approval
                        ?.approvedOn
                        ?.value,
                sentOn = order.state.sentOn?.value,
                cancelledOn = cancelled?.cancelledOn?.value,
                cancelReason = cancelled?.reason,
                total = MoneyDto.from(order.total),
                lines = order.lines.mapIndexed { index, line -> PurchaseOrderLineResponse.from(index + 1, line) },
            )
        }
    }
}
