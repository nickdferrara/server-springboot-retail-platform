package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.EnterVendorInvoice
import com.retailplatform.payables.application.InvoiceQueries
import com.retailplatform.payables.application.RematchVendorInvoice
import com.retailplatform.payables.domain.InvoiceDetails
import com.retailplatform.payables.domain.InvoiceLine
import com.retailplatform.payables.domain.InvoiceStatus
import com.retailplatform.payables.domain.VendorInvoice
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.PurchaseOrderId
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

data class InvoiceLineRequest(
    @field:NotNull @field:Min(1)
    val purchaseOrderLineNumber: Int?,
    @field:NotNull @field:Min(1)
    val cases: Int?,
    @field:NotNull @field:Valid
    val caseCost: MoneyDto?,
)

data class EnterVendorInvoiceRequest(
    @field:NotBlank
    val vendorId: String?,
    @field:NotBlank @field:Size(max = VendorInvoiceNumber.MAX_LENGTH)
    val vendorInvoiceNumber: String?,
    @field:NotNull
    val invoiceDate: LocalDate?,
    @field:NotBlank
    val purchaseOrderId: String?,
    @field:NotEmpty @field:Valid
    val lines: List<InvoiceLineRequest>?,
) {
    fun toDetails(): InvoiceDetails =
        mapRequest {
            InvoiceDetails(
                vendorId = VendorId.parse(requireNotNull(vendorId)),
                number = VendorInvoiceNumber.of(requireNotNull(vendorInvoiceNumber)),
                invoiceDate = BusinessDate(requireNotNull(invoiceDate)),
                purchaseOrderId = PurchaseOrderId.parse(requireNotNull(purchaseOrderId)),
                lines =
                    requireNotNull(lines).map {
                        InvoiceLine.of(
                            requireNotNull(it.purchaseOrderLineNumber),
                            requireNotNull(it.cases),
                            requireNotNull(it.caseCost).toDomain(),
                        )
                    },
            )
        }
}

data class InvoiceLineResponse(
    val purchaseOrderLineNumber: Int,
    val cases: Int,
    val caseCost: MoneyDto,
    val amount: MoneyDto,
)

data class MatchExceptionResponse(
    val purchaseOrderLineNumber: Int,
    val reason: String,
    val detail: String,
)

data class VendorInvoiceResponse(
    val id: String,
    val vendorId: String,
    val vendorInvoiceNumber: String,
    val invoiceDate: LocalDate,
    val purchaseOrderId: String,
    val enteredBy: String,
    val status: String,
    val matchedOn: LocalDate?,
    val amount: MoneyDto,
    val lines: List<InvoiceLineResponse>,
    val exceptions: List<MatchExceptionResponse>,
) {
    companion object {
        fun from(invoice: VendorInvoice): VendorInvoiceResponse {
            val status = invoice.status
            return VendorInvoiceResponse(
                id = invoice.id.toString(),
                vendorId = invoice.details.vendorId.toString(),
                vendorInvoiceNumber = invoice.details.number.value,
                invoiceDate = invoice.details.invoiceDate.value,
                purchaseOrderId = invoice.details.purchaseOrderId.toString(),
                enteredBy = invoice.enteredBy,
                status = status.code,
                matchedOn = (status as? InvoiceStatus.Matched)?.matchedOn?.value,
                amount = MoneyDto.from(invoice.amount),
                lines =
                    invoice.lines.map {
                        InvoiceLineResponse(
                            it.purchaseOrderLineNumber,
                            it.cases,
                            MoneyDto.from(it.caseCost),
                            MoneyDto.from(it.amount),
                        )
                    },
                exceptions =
                    (status as? InvoiceStatus.Exception)?.exceptions.orEmpty().map {
                        MatchExceptionResponse(it.purchaseOrderLineNumber, it.reason.name, it.detail)
                    },
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/payables/invoices")
class VendorInvoiceController(
    private val enterVendorInvoice: EnterVendorInvoice,
    private val rematchVendorInvoice: RematchVendorInvoice,
    private val queries: InvoiceQueries,
) {
    /** Enters an invoice and matches it at once; the response says whether it matched. */
    @PostMapping
    fun enter(
        @Valid @RequestBody request: EnterVendorInvoiceRequest,
    ): ResponseEntity<VendorInvoiceResponse> {
        val id = enterVendorInvoice.handle(request.toDetails())
        return ResponseEntity
            .created(URI.create("/api/v1/payables/invoices/$id"))
            .body(VendorInvoiceResponse.from(queries.find(id)))
    }

    /** Invoices with the newest first; only those waiting on an exception with `exceptionsOnly=true`. */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "false") exceptionsOnly: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<VendorInvoiceResponse> =
        PageResponse.from(queries.list(exceptionsOnly, pageQuery(page, size)), VendorInvoiceResponse::from)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): VendorInvoiceResponse = VendorInvoiceResponse.from(queries.find(invoiceId(id)))

    /** Matches an invoice again after what held it up was resolved. */
    @PostMapping("/{id}/match")
    fun match(
        @PathVariable id: String,
    ): VendorInvoiceResponse {
        val invoiceId = invoiceId(id)
        rematchVendorInvoice.handle(invoiceId)
        return VendorInvoiceResponse.from(queries.find(invoiceId))
    }

    private fun invoiceId(id: String): InvoiceId = mapRequest { InvoiceId.parse(id) }
}
