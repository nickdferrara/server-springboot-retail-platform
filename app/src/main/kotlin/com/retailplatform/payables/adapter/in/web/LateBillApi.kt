package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.ItemAge
import com.retailplatform.payables.application.LateBillQueries
import com.retailplatform.payables.application.OpenItemAgeing
import com.retailplatform.payables.application.PayLateBill
import com.retailplatform.payables.application.RecordLateBill
import com.retailplatform.payables.application.RejectLateBill
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.LateBill
import com.retailplatform.payables.domain.LateBillDetails
import com.retailplatform.payables.domain.LateBillId
import com.retailplatform.payables.domain.LateBillStatus
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.StoreId
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

data class RecordLateBillRequest(
    @field:NotBlank
    val vendorId: String?,
    @field:NotBlank
    val storeId: String?,
    @field:NotBlank @field:Size(max = TICKET_NUMBER_MAX_LENGTH)
    val ticketNumber: String?,
    @field:NotBlank @field:Size(max = VendorInvoiceNumber.MAX_LENGTH)
    val vendorInvoiceNumber: String?,
    @field:NotNull
    val billDate: LocalDate?,
    @field:NotNull @field:Valid
    val amount: MoneyDto?,
) {
    fun toDetails(): LateBillDetails =
        mapRequest {
            val billed = requireNotNull(amount).toDomain()
            require(billed.isPositive) { "A late bill bills more than nothing" }
            LateBillDetails(
                BilledTicket(
                    VendorId.parse(requireNotNull(vendorId)),
                    StoreId.parse(requireNotNull(storeId)),
                    requireNotNull(ticketNumber).trim(),
                ),
                VendorInvoiceNumber.of(requireNotNull(vendorInvoiceNumber)),
                BusinessDate(requireNotNull(billDate)),
                billed,
            )
        }

    private companion object {
        const val TICKET_NUMBER_MAX_LENGTH = 30
    }
}

/** A further bill from a vendor for a ticket that was already invoiced, and what accounting decided about it. */
data class LateBillResponse(
    val id: String,
    val vendorId: String,
    val storeId: String,
    val ticketNumber: String,
    val vendorInvoiceNumber: String,
    val billDate: LocalDate,
    val amount: MoneyDto,
    val recordedBy: String,
    val recordedOn: LocalDate,
    val status: String,
    val settledOn: LocalDate?,
    val settledBy: String?,
    val settlementReason: String?,
    val ageDays: Long?,
    val overdue: Boolean,
) {
    companion object {
        /** [age] is how long the late bill has waited; `null` once it is settled. */
        fun from(
            lateBill: LateBill,
            age: ItemAge?,
        ): LateBillResponse {
            val settlement =
                when (val status = lateBill.status) {
                    is LateBillStatus.Paid -> status.settledOn to status.decision
                    is LateBillStatus.Rejected -> status.settledOn to status.decision
                    LateBillStatus.Open -> null
                }
            val ticket = lateBill.details.ticket
            return LateBillResponse(
                id = lateBill.id.toString(),
                vendorId = ticket.vendorId.toString(),
                storeId = ticket.storeId.toString(),
                ticketNumber = ticket.ticketNumber,
                vendorInvoiceNumber = lateBill.details.number.value,
                billDate = lateBill.details.billDate.value,
                amount = MoneyDto.from(lateBill.amount),
                recordedBy = lateBill.recordedBy,
                recordedOn = lateBill.recordedOn.value,
                status = lateBill.status.code,
                settledOn = settlement?.first?.value,
                settledBy = settlement?.second?.approvedBy,
                settlementReason = settlement?.second?.reason,
                ageDays = age?.days,
                overdue = age?.overdue ?: false,
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/payables/direct-delivery-late-bills")
class LateBillController(
    private val recordLateBill: RecordLateBill,
    private val payLateBill: PayLateBill,
    private val rejectLateBill: RejectLateBill,
    private val queries: LateBillQueries,
    private val ageing: OpenItemAgeing,
) {
    /** Records a further bill from a vendor for a ticket whose invoice is already payable. */
    @PostMapping
    fun record(
        @Valid @RequestBody request: RecordLateBillRequest,
    ): ResponseEntity<LateBillResponse> {
        val id = recordLateBill.handle(request.toDetails())
        return ResponseEntity
            .created(URI.create("/api/v1/payables/direct-delivery-late-bills/$id"))
            .body(response(queries.find(id)))
    }

    /** A vendor's late bills, the oldest first; only those still waiting with `openOnly=true`. */
    @GetMapping
    fun list(
        @RequestParam vendorId: String,
        @RequestParam(defaultValue = "false") openOnly: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<LateBillResponse> {
        val vendor = mapRequest { VendorId.parse(vendorId) }
        return PageResponse.from(queries.list(vendor, openOnly, pageQuery(page, size)), ::response)
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): LateBillResponse = response(queries.find(lateBillId(id)))

    /**
     * Accepts a late bill for payment: a voucher is released for its total. For an accounting manager other than
     * the person who recorded it.
     */
    @PostMapping("/{id}/pay")
    fun pay(
        @PathVariable id: String,
        @Valid @RequestBody request: OverrideMatchExceptionRequest,
    ): LateBillResponse {
        val lateBillId = lateBillId(id)
        payLateBill.handle(lateBillId, requireNotNull(request.reason))
        return response(queries.find(lateBillId))
    }

    /**
     * Rejects a late bill: nothing is owed on it and nothing is paid. For an accounting manager other than the
     * person who recorded it.
     */
    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: String,
        @Valid @RequestBody request: OverrideMatchExceptionRequest,
    ): LateBillResponse {
        val lateBillId = lateBillId(id)
        rejectLateBill.handle(lateBillId, requireNotNull(request.reason))
        return response(queries.find(lateBillId))
    }

    private fun response(lateBill: LateBill) = LateBillResponse.from(lateBill, ageing.ageOf(lateBill))

    private fun lateBillId(id: String): LateBillId = mapRequest { LateBillId.parse(id) }
}
