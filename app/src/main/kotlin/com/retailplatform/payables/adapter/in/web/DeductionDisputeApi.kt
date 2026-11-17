package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.ConcedeDeduction
import com.retailplatform.payables.application.DeductionDisputeQueries
import com.retailplatform.payables.application.RecordDeductionDispute
import com.retailplatform.payables.application.UpholdDeduction
import com.retailplatform.payables.domain.DeductionDispute
import com.retailplatform.payables.domain.DeductionDisputeId
import com.retailplatform.payables.domain.DeductionNoticeId
import com.retailplatform.payables.domain.DisputeClaim
import com.retailplatform.payables.domain.DisputeStatus
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Money
import com.retailplatform.shared.VendorId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.LocalDate

data class RecordDeductionDisputeRequest(
    @field:NotNull
    val receivedOn: LocalDate?,
    @field:NotBlank @field:Size(max = VendorInvoiceNumber.MAX_LENGTH)
    val vendorReference: String?,
    @field:NotBlank @field:Size(max = DisputeClaim.MAX_STATEMENT_LENGTH)
    val claim: String?,
) {
    fun toClaim(): DisputeClaim =
        mapRequest {
            DisputeClaim.of(
                BusinessDate(requireNotNull(receivedOn)),
                VendorInvoiceNumber.of(requireNotNull(vendorReference)),
                requireNotNull(claim),
            )
        }
}

data class ConcedeDeductionRequest(
    @field:NotNull @field:Valid
    val amount: MoneyDto?,
    @field:NotBlank @field:Size(max = MatchOverride.MAX_REASON_LENGTH)
    val reason: String?,
)

/** A vendor's dispute of a deduction, and what accounting decided. */
data class DeductionDisputeResponse(
    val id: String,
    val deductionNoticeId: String,
    val deductionNoticeNumber: String,
    val vendorId: String,
    val storeId: String,
    val ticketNumber: String,
    val deduction: MoneyDto,
    val receivedOn: LocalDate,
    val vendorReference: String,
    val claim: String,
    val recordedBy: String,
    val recordedOn: LocalDate,
    val status: String,
    val decidedOn: LocalDate?,
    val decidedBy: String?,
    val decisionReason: String?,
    val conceded: MoneyDto?,
    val concedable: MoneyDto?,
) {
    companion object {
        /** [concedable] is what can still be conceded while the dispute waits; `null` once it is decided. */
        fun from(
            dispute: DeductionDispute,
            concedable: Money?,
        ): DeductionDisputeResponse {
            val decision =
                when (val status = dispute.status) {
                    is DisputeStatus.Upheld -> status.decidedOn to status.decision
                    is DisputeStatus.Conceded -> status.decidedOn to status.decision
                    DisputeStatus.Open -> null
                }
            val deduction = dispute.deduction
            return DeductionDisputeResponse(
                id = dispute.id.toString(),
                deductionNoticeId = deduction.noticeId.toString(),
                deductionNoticeNumber = deduction.noticeNumber.value,
                vendorId = deduction.ticket.vendorId.toString(),
                storeId = deduction.ticket.storeId.toString(),
                ticketNumber = deduction.ticket.ticketNumber,
                deduction = MoneyDto.from(deduction.amount),
                receivedOn = dispute.claim.receivedOn.value,
                vendorReference = dispute.claim.reference.value,
                claim = dispute.claim.statement,
                recordedBy = dispute.recordedBy,
                recordedOn = dispute.recordedOn.value,
                status = dispute.status.code,
                decidedOn = decision?.first?.value,
                decidedBy = decision?.second?.approvedBy,
                decisionReason = decision?.second?.reason,
                conceded = (dispute.status as? DisputeStatus.Conceded)?.amount?.let(MoneyDto::from),
                concedable = concedable?.let(MoneyDto::from),
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/payables")
class DeductionDisputeController(
    private val recordDispute: RecordDeductionDispute,
    private val upholdDeduction: UpholdDeduction,
    private val concedeDeduction: ConcedeDeduction,
    private val queries: DeductionDisputeQueries,
) {
    /** Records that the vendor disputes the deduction a notice told it of. A deduction is disputed once. */
    @PostMapping("/deduction-notices/{noticeId}/dispute")
    fun record(
        @PathVariable noticeId: String,
        @Valid @RequestBody request: RecordDeductionDisputeRequest,
    ): ResponseEntity<DeductionDisputeResponse> {
        val notice = mapRequest { DeductionNoticeId.parse(noticeId) }
        val id = recordDispute.handle(notice, request.toClaim())
        return ResponseEntity
            .created(URI.create("/api/v1/payables/deduction-disputes/$id"))
            .body(response(queries.find(id)))
    }

    /** The dispute of the deduction a notice told the vendor of. */
    @GetMapping("/deduction-notices/{noticeId}/dispute")
    fun forNotice(
        @PathVariable noticeId: String,
    ): DeductionDisputeResponse {
        val notice = mapRequest { DeductionNoticeId.parse(noticeId) }
        return response(queries.forNotice(notice))
    }

    /** A vendor's disputes, the oldest first; only those still waiting with `openOnly=true`. */
    @GetMapping("/deduction-disputes")
    fun list(
        @RequestParam vendorId: String,
        @RequestParam(defaultValue = "false") openOnly: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<DeductionDisputeResponse> {
        val vendor = mapRequest { VendorId.parse(vendorId) }
        return PageResponse.from(queries.list(vendor, openOnly, pageQuery(page, size)), ::response)
    }

    @GetMapping("/deduction-disputes/{id}")
    fun get(
        @PathVariable id: String,
    ): DeductionDisputeResponse = response(queries.find(disputeId(id)))

    /**
     * Decides that a disputed deduction stands in full. For an accounting manager other than the one who paid
     * the invoice short.
     */
    @PostMapping("/deduction-disputes/{id}/uphold")
    fun uphold(
        @PathVariable id: String,
        @Valid @RequestBody request: OverrideMatchExceptionRequest,
    ): DeductionDisputeResponse {
        val disputeId = disputeId(id)
        upholdDeduction.handle(disputeId, requireNotNull(request.reason))
        return response(queries.find(disputeId))
    }

    /**
     * Gives some or all of a disputed deduction back to the vendor: a voucher is released for the amount. For an
     * accounting manager other than the one who paid the invoice short.
     */
    @PostMapping("/deduction-disputes/{id}/concede")
    fun concede(
        @PathVariable id: String,
        @Valid @RequestBody request: ConcedeDeductionRequest,
    ): DeductionDisputeResponse {
        val disputeId = disputeId(id)
        val amount = mapRequest { requireNotNull(request.amount).toDomain() }
        concedeDeduction.handle(disputeId, amount, requireNotNull(request.reason))
        return response(queries.find(disputeId))
    }

    private fun response(dispute: DeductionDispute) =
        DeductionDisputeResponse.from(dispute, queries.concedable(dispute))

    private fun disputeId(id: String): DeductionDisputeId = mapRequest { DeductionDisputeId.parse(id) }
}
