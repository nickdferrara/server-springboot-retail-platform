package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.DirectDeliveryInvoiceQueries
import com.retailplatform.payables.application.EnterDirectDeliveryInvoice
import com.retailplatform.payables.application.OverrideDirectDeliveryException
import com.retailplatform.payables.application.RematchDirectDeliveryInvoice
import com.retailplatform.payables.application.ShortPayDirectDeliveryInvoice
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DirectDeliveryInvoice
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.TicketInvoiceDetails
import com.retailplatform.payables.domain.TicketInvoiceStatus
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
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

data class BilledLineRequest(
    @field:NotBlank
    val itemId: String?,
    @field:NotNull @field:Min(1)
    val units: Int?,
    @field:NotNull @field:Valid
    val unitCost: MoneyDto?,
)

data class EnterDirectDeliveryInvoiceRequest(
    @field:NotBlank
    val vendorId: String?,
    @field:NotBlank
    val storeId: String?,
    @field:NotBlank @field:Size(max = TICKET_NUMBER_MAX_LENGTH)
    val ticketNumber: String?,
    @field:NotBlank @field:Size(max = VendorInvoiceNumber.MAX_LENGTH)
    val vendorInvoiceNumber: String?,
    @field:NotNull
    val invoiceDate: LocalDate?,
    @field:NotEmpty @field:Valid
    val lines: List<BilledLineRequest>?,
) {
    fun toDetails(): TicketInvoiceDetails =
        mapRequest {
            TicketInvoiceDetails(
                ticket =
                    BilledTicket(
                        VendorId.parse(requireNotNull(vendorId)),
                        StoreId.parse(requireNotNull(storeId)),
                        requireNotNull(ticketNumber).trim(),
                    ),
                number = VendorInvoiceNumber.of(requireNotNull(vendorInvoiceNumber)),
                invoiceDate = BusinessDate(requireNotNull(invoiceDate)),
                lines =
                    requireNotNull(lines).map {
                        TicketAmountLine.of(
                            ItemId.parse(requireNotNull(it.itemId)),
                            requireNotNull(it.units),
                            requireNotNull(it.unitCost).toDomain(),
                        )
                    },
            )
        }

    private companion object {
        const val TICKET_NUMBER_MAX_LENGTH = 30
    }
}

data class TicketExceptionResponse(
    val itemId: String?,
    val reason: String,
    val detail: String,
)

data class DirectDeliveryInvoiceResponse(
    val id: String,
    val vendorId: String,
    val storeId: String,
    val ticketNumber: String,
    val vendorInvoiceNumber: String,
    val invoiceDate: LocalDate,
    val enteredBy: String,
    val status: String,
    val matchedOn: LocalDate?,
    val overrideApprovedBy: String?,
    val overrideReason: String?,
    val amount: MoneyDto,
    val deduction: MoneyDto?,
    val payable: MoneyDto?,
    val lines: List<TicketAmountLineResponse>,
    val exceptions: List<TicketExceptionResponse>,
) {
    companion object {
        fun from(invoice: DirectDeliveryInvoice): DirectDeliveryInvoiceResponse {
            val status = invoice.status
            val approval =
                when (status) {
                    is TicketInvoiceStatus.Matched -> status.override
                    is TicketInvoiceStatus.ShortPaid -> status.approval
                    is TicketInvoiceStatus.Exception -> null
                }
            val payable = if (status is TicketInvoiceStatus.Exception) null else invoice.matched()
            val ticket = invoice.details.ticket
            return DirectDeliveryInvoiceResponse(
                id = invoice.id.toString(),
                vendorId = ticket.vendorId.toString(),
                storeId = ticket.storeId.toString(),
                ticketNumber = ticket.ticketNumber,
                vendorInvoiceNumber = invoice.details.number.value,
                invoiceDate = invoice.details.invoiceDate.value,
                enteredBy = invoice.enteredBy,
                status = status.code,
                matchedOn = payable?.matchedOn?.value,
                overrideApprovedBy = approval?.approvedBy,
                overrideReason = approval?.reason,
                amount = MoneyDto.from(invoice.amount),
                deduction = (status as? TicketInvoiceStatus.ShortPaid)?.deduction?.let(MoneyDto::from),
                payable = payable?.amount?.let(MoneyDto::from),
                lines = invoice.lines.map(TicketAmountLineResponse::from),
                exceptions =
                    (status as? TicketInvoiceStatus.Exception)?.exceptions.orEmpty().map {
                        TicketExceptionResponse(it.itemId?.toString(), it.reason.name, it.detail)
                    },
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/payables/direct-delivery-invoices")
class DirectDeliveryInvoiceController(
    private val enterInvoice: EnterDirectDeliveryInvoice,
    private val rematchInvoice: RematchDirectDeliveryInvoice,
    private val overrideException: OverrideDirectDeliveryException,
    private val shortPayInvoice: ShortPayDirectDeliveryInvoice,
    private val queries: DirectDeliveryInvoiceQueries,
) {
    /** Enters a vendor's invoice for one delivery ticket and matches it at once. */
    @PostMapping
    fun enter(
        @Valid @RequestBody request: EnterDirectDeliveryInvoiceRequest,
    ): ResponseEntity<DirectDeliveryInvoiceResponse> {
        val id = enterInvoice.handle(request.toDetails())
        return ResponseEntity
            .created(URI.create("/api/v1/payables/direct-delivery-invoices/$id"))
            .body(DirectDeliveryInvoiceResponse.from(queries.find(id)))
    }

    /** Invoices with the newest first; the buyer's exception queue with `exceptionsOnly=true`. */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "false") exceptionsOnly: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<DirectDeliveryInvoiceResponse> =
        PageResponse.from(queries.list(exceptionsOnly, pageQuery(page, size)), DirectDeliveryInvoiceResponse::from)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): DirectDeliveryInvoiceResponse = DirectDeliveryInvoiceResponse.from(queries.find(invoiceId(id)))

    /** Matches an invoice again after what held it up was resolved. */
    @PostMapping("/{id}/match")
    fun match(
        @PathVariable id: String,
    ): DirectDeliveryInvoiceResponse {
        val invoiceId = invoiceId(id)
        rematchInvoice.handle(invoiceId)
        return DirectDeliveryInvoiceResponse.from(queries.find(invoiceId))
    }

    /**
     * Accepts an invoice whose unit cost is outside tolerance. For a buyer who may approve, other than the person
     * who entered the invoice.
     */
    @PostMapping("/{id}/override")
    fun override(
        @PathVariable id: String,
        @Valid @RequestBody request: OverrideMatchExceptionRequest,
    ): DirectDeliveryInvoiceResponse {
        val invoiceId = invoiceId(id)
        overrideException.handle(invoiceId, requireNotNull(request.reason))
        return DirectDeliveryInvoiceResponse.from(queries.find(invoiceId))
    }

    /**
     * Pays an invoice for what the store received and holds the rest back as a deduction against the vendor.
     * For an accounting manager other than the person who entered the invoice.
     */
    @PostMapping("/{id}/short-pay")
    fun shortPay(
        @PathVariable id: String,
        @Valid @RequestBody request: OverrideMatchExceptionRequest,
    ): DirectDeliveryInvoiceResponse {
        val invoiceId = invoiceId(id)
        shortPayInvoice.handle(invoiceId, requireNotNull(request.reason))
        return DirectDeliveryInvoiceResponse.from(queries.find(invoiceId))
    }

    private fun invoiceId(id: String): InvoiceId = mapRequest { InvoiceId.parse(id) }
}
