package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.ApprovePaymentRun
import com.retailplatform.payables.application.CancelPaymentRun
import com.retailplatform.payables.application.PaymentQueries
import com.retailplatform.payables.application.PaymentRunDetail
import com.retailplatform.payables.application.ProposePaymentRun
import com.retailplatform.payables.domain.Payment
import com.retailplatform.payables.domain.PaymentRun
import com.retailplatform.payables.domain.PaymentRunId
import com.retailplatform.payables.domain.PaymentRunLine
import com.retailplatform.payables.domain.PaymentRunStatus
import com.retailplatform.payables.domain.PaymentWindow
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.PaymentId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
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

data class ProposePaymentRunRequest(
    @field:NotNull
    val paymentDate: LocalDate?,
    @field:NotNull
    val payThrough: LocalDate?,
) {
    fun toWindow(): PaymentWindow =
        mapRequest {
            PaymentWindow.of(BusinessDate(requireNotNull(paymentDate)), BusinessDate(requireNotNull(payThrough)))
        }
}

data class PaidVoucherResponse(
    val voucherId: String,
    val voucherNumber: String,
    val vendorId: String,
    val vendorInvoiceNumber: String,
    val amount: MoneyDto,
    val discount: MoneyDto,
    val net: MoneyDto,
) {
    companion object {
        fun from(line: PaymentRunLine) =
            PaidVoucherResponse(
                voucherId = line.voucherId.toString(),
                voucherNumber = line.voucherNumber.value,
                vendorId = line.vendorId.toString(),
                vendorInvoiceNumber = line.vendorInvoiceNumber.value,
                amount = MoneyDto.from(line.amount),
                discount = MoneyDto.from(line.discount),
                net = MoneyDto.from(line.net),
            )
    }
}

data class PaymentResponse(
    val id: String,
    val number: String,
    val paymentRunId: String,
    val vendorId: String,
    val paidOn: LocalDate,
    val gross: MoneyDto,
    val discount: MoneyDto,
    val net: MoneyDto,
    val vouchers: List<PaidVoucherResponse>,
) {
    companion object {
        fun from(payment: Payment) =
            PaymentResponse(
                id = payment.id.toString(),
                number = payment.number.value,
                paymentRunId = payment.paymentRunId.toString(),
                vendorId = payment.vendorId.toString(),
                paidOn = payment.paidOn.value,
                gross = MoneyDto.from(payment.gross),
                discount = MoneyDto.from(payment.discount),
                net = MoneyDto.from(payment.net),
                vouchers = payment.vouchers.map(PaidVoucherResponse::from),
            )
    }
}

data class PaymentRunResponse(
    val id: String,
    val paymentDate: LocalDate,
    val payThrough: LocalDate,
    val proposedBy: String,
    val status: String,
    val decidedBy: String?,
    val decidedOn: LocalDate?,
    val lines: List<PaidVoucherResponse>,
    val payments: List<PaymentResponse>,
) {
    companion object {
        fun from(detail: PaymentRunDetail) = from(detail.run, detail.payments)

        fun summary(run: PaymentRun) = from(run, emptyList())

        private fun from(
            run: PaymentRun,
            payments: List<Payment>,
        ): PaymentRunResponse {
            val status = run.status
            return PaymentRunResponse(
                id = run.id.toString(),
                paymentDate = run.window.paymentDate.value,
                payThrough = run.window.payThrough.value,
                proposedBy = run.proposedBy,
                status = status.code,
                decidedBy =
                    when (status) {
                        is PaymentRunStatus.Approved -> status.approvedBy
                        is PaymentRunStatus.Cancelled -> status.cancelledBy
                        PaymentRunStatus.Proposed -> null
                    },
                decidedOn =
                    when (status) {
                        is PaymentRunStatus.Approved -> status.approvedOn.value
                        is PaymentRunStatus.Cancelled -> status.cancelledOn.value
                        PaymentRunStatus.Proposed -> null
                    },
                lines = run.lines.map(PaidVoucherResponse::from),
                payments = payments.map(PaymentResponse::from),
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/payables")
class PaymentRunController(
    private val proposePaymentRun: ProposePaymentRun,
    private val approvePaymentRun: ApprovePaymentRun,
    private val cancelPaymentRun: CancelPaymentRun,
    private val queries: PaymentQueries,
) {
    /**
     * Proposes a run that pays, on `paymentDate`, every released voucher due on or before `payThrough` or whose
     * early-payment discount would run out by then.
     */
    @PostMapping("/payment-runs")
    fun propose(
        @Valid @RequestBody request: ProposePaymentRunRequest,
    ): ResponseEntity<PaymentRunResponse> {
        val id = proposePaymentRun.handle(request.toWindow())
        return ResponseEntity
            .created(URI.create("/api/v1/payables/payment-runs/$id"))
            .body(PaymentRunResponse.from(queries.run(id)))
    }

    /** Payment runs with the newest first, without their payments. */
    @GetMapping("/payment-runs")
    fun list(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<PaymentRunResponse> =
        PageResponse.from(queries.runs(pageQuery(page, size)), PaymentRunResponse::summary)

    @GetMapping("/payment-runs/{id}")
    fun get(
        @PathVariable id: String,
    ): PaymentRunResponse = PaymentRunResponse.from(queries.run(runId(id)))

    /** Approves a run and issues its payments. For an accounting manager other than the person who proposed it. */
    @PostMapping("/payment-runs/{id}/approve")
    fun approve(
        @PathVariable id: String,
    ): PaymentRunResponse {
        val runId = runId(id)
        approvePaymentRun.handle(runId)
        return PaymentRunResponse.from(queries.run(runId))
    }

    /** Cancels a run that has not been approved and gives its vouchers back. */
    @PostMapping("/payment-runs/{id}/cancel")
    fun cancel(
        @PathVariable id: String,
    ): PaymentRunResponse {
        val runId = runId(id)
        cancelPaymentRun.handle(runId)
        return PaymentRunResponse.from(queries.run(runId))
    }

    @GetMapping("/payments/{id}")
    fun payment(
        @PathVariable id: String,
    ): PaymentResponse = PaymentResponse.from(queries.payment(mapRequest { PaymentId.parse(id) }))

    private fun runId(id: String): PaymentRunId = mapRequest { PaymentRunId.parse(id) }
}
