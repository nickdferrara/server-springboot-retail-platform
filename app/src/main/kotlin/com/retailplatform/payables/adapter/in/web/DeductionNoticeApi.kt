package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.DeductionNoticeQueries
import com.retailplatform.payables.application.NoticeSent
import com.retailplatform.payables.application.RecordDeductionNoticeSent
import com.retailplatform.payables.domain.DeductionLine
import com.retailplatform.payables.domain.DeductionNotice
import com.retailplatform.payables.domain.DeductionNoticeId
import com.retailplatform.payables.domain.NoticeChannel
import com.retailplatform.payables.domain.NoticeSending
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.VendorId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

data class DeductionNoticeSentRequest(
    @field:NotNull
    val sentOn: LocalDate?,
    @field:NotBlank
    val channel: String?,
    @field:NotBlank @field:Size(max = NoticeSending.MAX_RECIPIENT_LENGTH)
    val recipient: String?,
) {
    fun toSent(): NoticeSent =
        mapRequest {
            NoticeSent(
                BusinessDate(requireNotNull(sentOn)),
                NoticeChannel.valueOf(requireNotNull(channel)),
                requireNotNull(recipient),
            )
        }
}

data class DeductionLineResponse(
    val itemId: String,
    val unitsBilled: Int,
    val unitsReceived: Int,
    val unitsDeducted: Int,
    val unitCost: MoneyDto,
    val amount: MoneyDto,
) {
    companion object {
        fun from(line: DeductionLine) =
            DeductionLineResponse(
                line.itemId.toString(),
                line.unitsBilled,
                line.unitsReceived,
                line.unitsDeducted,
                MoneyDto.from(line.unitCost),
                MoneyDto.from(line.amount),
            )
    }
}

/** What a vendor is told was held back from one of its invoices, and whether it has been told. */
data class DeductionNoticeResponse(
    val id: String,
    val number: String,
    val invoiceId: String,
    val vendorInvoiceNumber: String,
    val invoiceDate: LocalDate,
    val vendorId: String,
    val storeId: String,
    val ticketNumber: String,
    val raisedOn: LocalDate,
    val reason: String,
    val amount: MoneyDto,
    val lines: List<DeductionLineResponse>,
    val sentOn: LocalDate?,
    val sentBy: String?,
    val sentTo: String?,
    val sentRecordedBy: String?,
) {
    companion object {
        fun from(notice: DeductionNotice): DeductionNoticeResponse {
            val invoice = notice.invoice
            return DeductionNoticeResponse(
                id = notice.id.toString(),
                number = notice.number.value,
                invoiceId = invoice.invoiceId.toString(),
                vendorInvoiceNumber = invoice.number.value,
                invoiceDate = invoice.invoiceDate.value,
                vendorId = invoice.ticket.vendorId.toString(),
                storeId = invoice.ticket.storeId.toString(),
                ticketNumber = invoice.ticket.ticketNumber,
                raisedOn = notice.raisedOn.value,
                reason = notice.reason,
                amount = MoneyDto.from(notice.amount),
                lines = notice.lines.map(DeductionLineResponse::from),
                sentOn = notice.sending?.sentOn?.value,
                sentBy = notice.sending?.channel?.name,
                sentTo = notice.sending?.recipient,
                sentRecordedBy = notice.sending?.recordedBy,
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/payables")
class DeductionNoticeController(
    private val recordSent: RecordDeductionNoticeSent,
    private val queries: DeductionNoticeQueries,
) {
    /** A vendor's deduction notices, the oldest first; only those not yet sent with `unsentOnly=true`. */
    @GetMapping("/deduction-notices")
    fun list(
        @RequestParam vendorId: String,
        @RequestParam(defaultValue = "false") unsentOnly: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<DeductionNoticeResponse> {
        val vendor = mapRequest { VendorId.parse(vendorId) }
        return PageResponse.from(queries.list(vendor, unsentOnly, pageQuery(page, size)), DeductionNoticeResponse::from)
    }

    @GetMapping("/deduction-notices/{id}")
    fun get(
        @PathVariable id: String,
    ): DeductionNoticeResponse = DeductionNoticeResponse.from(queries.find(noticeId(id)))

    /** The notice raised when an invoice was paid short. */
    @GetMapping("/direct-delivery-invoices/{invoiceId}/deduction-notice")
    fun forInvoice(
        @PathVariable invoiceId: String,
    ): DeductionNoticeResponse {
        val invoice = mapRequest { InvoiceId.parse(invoiceId) }
        return DeductionNoticeResponse.from(queries.forInvoice(invoice))
    }

    /** Records when, how, and to whom accounting sent a notice. The system sends nothing itself. */
    @PostMapping("/deduction-notices/{id}/sent")
    fun sent(
        @PathVariable id: String,
        @Valid @RequestBody request: DeductionNoticeSentRequest,
    ): DeductionNoticeResponse {
        val noticeId = noticeId(id)
        recordSent.handle(noticeId, request.toSent())
        return DeductionNoticeResponse.from(queries.find(noticeId))
    }

    private fun noticeId(id: String): DeductionNoticeId = mapRequest { DeductionNoticeId.parse(id) }
}
