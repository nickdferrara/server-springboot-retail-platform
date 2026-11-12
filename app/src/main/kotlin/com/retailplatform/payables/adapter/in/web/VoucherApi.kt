package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.VoucherQueries
import com.retailplatform.payables.domain.Voucher
import com.retailplatform.payables.domain.VoucherStatus
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.VoucherId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

data class VoucherResponse(
    val id: String,
    val number: String,
    val invoiceId: String,
    val vendorInvoiceNumber: String,
    val vendorId: String,
    val purchaseOrderId: String,
    val invoiceDate: LocalDate,
    val amount: MoneyDto,
    val dueOn: LocalDate,
    val discountPercent: BigDecimal?,
    val discountGoodThrough: LocalDate?,
    val releasedOn: LocalDate,
    val status: String,
    val paymentRunId: String?,
    val paymentId: String?,
    val paidOn: LocalDate?,
) {
    companion object {
        fun from(voucher: Voucher): VoucherResponse {
            val status = voucher.status
            val paid = status as? VoucherStatus.Paid
            return VoucherResponse(
                id = voucher.id.toString(),
                number = voucher.number.value,
                invoiceId = voucher.invoice.invoiceId.toString(),
                vendorInvoiceNumber = voucher.invoice.vendorInvoiceNumber.value,
                vendorId = voucher.invoice.vendorId.toString(),
                purchaseOrderId = voucher.invoice.purchaseOrderId.toString(),
                invoiceDate = voucher.invoice.invoiceDate.value,
                amount = MoneyDto.from(voucher.amount),
                dueOn = voucher.terms.dueOn.value,
                discountPercent =
                    voucher.terms.discount
                        ?.percent
                        ?.value,
                discountGoodThrough =
                    voucher.terms.discount
                        ?.goodThrough
                        ?.value,
                releasedOn = voucher.releasedOn.value,
                status = status.code,
                paymentRunId = (paid?.paymentRunId ?: (status as? VoucherStatus.Scheduled)?.paymentRunId)?.toString(),
                paymentId = paid?.paymentId?.toString(),
                paidOn = paid?.paidOn?.value,
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/payables/vouchers")
class VoucherController(
    private val queries: VoucherQueries,
) {
    /**
     * Vouchers by due date, the earliest first; narrowed to one vendor with `vendorId` and to `RELEASED`,
     * `SCHEDULED`, or `PAID` with `status`.
     */
    @GetMapping
    fun list(
        @RequestParam(required = false) vendorId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<VoucherResponse> {
        val vendor = vendorId?.let { mapRequest { VendorId.parse(it) } }
        val code =
            status?.let {
                mapRequest {
                    require(it in STATUS_CODES) { "Not a voucher status: '$it'" }
                    it
                }
            }
        return PageResponse.from(queries.list(vendor, code, pageQuery(page, size)), VoucherResponse::from)
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): VoucherResponse = VoucherResponse.from(queries.find(mapRequest { VoucherId.parse(id) }))

    private companion object {
        val STATUS_CODES = setOf("RELEASED", "SCHEDULED", "PAID")
    }
}
