package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.DirectDeliveryPayablesQueries
import com.retailplatform.payables.application.OpenItemAgeing
import com.retailplatform.payables.application.PayDeliveryAdjustment
import com.retailplatform.payables.application.VendorDocument
import com.retailplatform.payables.application.WriteOffDeliveryAdjustment
import com.retailplatform.payables.domain.DeliveryAdjustmentId
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

data class PaidUnitsRequest(
    @field:NotBlank
    val itemId: String?,
    @field:NotNull @field:Min(1)
    val units: Int?,
)

data class PayDeliveryAdjustmentRequest(
    @field:NotBlank @field:Size(max = VendorInvoiceNumber.MAX_LENGTH)
    val vendorReference: String?,
    @field:NotNull
    val referenceDate: LocalDate?,
    @field:NotBlank @field:Size(max = MatchOverride.MAX_REASON_LENGTH)
    val reason: String?,
    /** The units of each item to pay; left out to pay the whole adjustment. */
    @field:Valid
    val units: List<PaidUnitsRequest>? = null,
) {
    fun toUnits(): List<Pair<ItemId, Int>>? =
        mapRequest { units?.map { ItemId.parse(requireNotNull(it.itemId)) to requireNotNull(it.units) } }

    fun toDocument(): VendorDocument =
        mapRequest {
            VendorDocument(
                VendorInvoiceNumber.of(requireNotNull(vendorReference)),
                BusinessDate(requireNotNull(referenceDate)),
            )
        }
}

@RestController
@RequestMapping("/api/v1/payables/direct-delivery-adjustments/{id}")
class DeliveryAdjustmentController(
    private val payAdjustment: PayDeliveryAdjustment,
    private val writeOffAdjustment: WriteOffDeliveryAdjustment,
    private val queries: DirectDeliveryPayablesQueries,
    private val ageing: OpenItemAgeing,
) {
    @GetMapping
    fun get(
        @PathVariable id: String,
    ): DeliveryAdjustmentResponse = response(adjustmentId(id))

    /**
     * Pays an adjustment: the vendor is owed the amount and billed it on the document given, and a voucher is
     * released. With `units`, only those units of each item are paid and the rest is written off in the same
     * step. For an accounting manager.
     */
    @PostMapping("/pay")
    fun pay(
        @PathVariable id: String,
        @Valid @RequestBody request: PayDeliveryAdjustmentRequest,
    ): DeliveryAdjustmentResponse {
        val adjustmentId = adjustmentId(id)
        payAdjustment.handle(adjustmentId, request.toDocument(), requireNotNull(request.reason), request.toUnits())
        return response(adjustmentId)
    }

    /**
     * Writes an adjustment off: the vendor is not owed the amount, which leaves what is accrued as a favourable
     * variance. For an accounting manager.
     */
    @PostMapping("/write-off")
    fun writeOff(
        @PathVariable id: String,
        @Valid @RequestBody request: OverrideMatchExceptionRequest,
    ): DeliveryAdjustmentResponse {
        val adjustmentId = adjustmentId(id)
        writeOffAdjustment.handle(adjustmentId, requireNotNull(request.reason))
        return response(adjustmentId)
    }

    private fun response(id: DeliveryAdjustmentId): DeliveryAdjustmentResponse {
        val adjustment = queries.adjustment(id)
        return DeliveryAdjustmentResponse.from(adjustment, ageing.ageOf(adjustment))
    }

    private fun adjustmentId(id: String): DeliveryAdjustmentId = mapRequest { DeliveryAdjustmentId.parse(id) }
}
