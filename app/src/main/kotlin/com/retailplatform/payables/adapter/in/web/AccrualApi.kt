package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.AccrualQueries
import com.retailplatform.payables.domain.Accrual
import com.retailplatform.payables.domain.VendorAccrualBalance
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.AccrualId
import com.retailplatform.shared.VendorId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

data class AccrualLineResponse(
    val purchaseOrderLineNumber: Int,
    val itemId: String,
    val casesReceived: Int,
    val caseCost: MoneyDto,
    val amount: MoneyDto,
)

data class AccrualResponse(
    val id: String,
    val receiptId: String,
    val receiptNumber: String,
    val purchaseOrderId: String,
    val purchaseOrderNumber: String,
    val vendorId: String,
    val warehouseId: String,
    val receivedOn: LocalDate,
    val amount: MoneyDto,
    val openAmount: MoneyDto,
    val lines: List<AccrualLineResponse>,
) {
    companion object {
        fun from(accrual: Accrual) =
            AccrualResponse(
                id = accrual.id.toString(),
                receiptId = accrual.receipt.receiptId.toString(),
                receiptNumber = accrual.receipt.receiptNumber,
                purchaseOrderId = accrual.receipt.purchaseOrderId.toString(),
                purchaseOrderNumber = accrual.receipt.purchaseOrderNumber,
                vendorId = accrual.receipt.vendorId.toString(),
                warehouseId = accrual.receipt.warehouseId.toString(),
                receivedOn = accrual.receipt.receivedOn.value,
                amount = MoneyDto.from(accrual.amount),
                openAmount = MoneyDto.from(accrual.openAmount),
                lines =
                    accrual.lines.map {
                        AccrualLineResponse(
                            it.purchaseOrderLineNumber,
                            it.itemId.toString(),
                            it.casesReceived,
                            MoneyDto.from(it.caseCost),
                            MoneyDto.from(it.amount),
                        )
                    },
            )
    }
}

data class VendorAccrualBalanceResponse(
    val vendorId: String,
    val openAmount: MoneyDto,
    val openAccruals: Int,
    val oldestReceivedOn: LocalDate,
    val oldestAgeDays: Long,
    val stale: Boolean,
) {
    companion object {
        fun from(balance: VendorAccrualBalance) =
            VendorAccrualBalanceResponse(
                vendorId = balance.vendorId.toString(),
                openAmount = MoneyDto.from(balance.openAmount),
                openAccruals = balance.openAccruals,
                oldestReceivedOn = balance.oldestReceivedOn.value,
                oldestAgeDays = balance.oldestAgeDays,
                stale = balance.stale,
            )
    }
}

@RestController
@RequestMapping("/api/v1/payables/accruals")
class AccrualController(
    private val queries: AccrualQueries,
) {
    /** A vendor's open accruals, the oldest receipt first. */
    @GetMapping
    fun open(
        @RequestParam vendorId: String,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<AccrualResponse> {
        val vendor = mapRequest { VendorId.parse(vendorId) }
        return PageResponse.from(queries.open(vendor, pageQuery(page, size)), AccrualResponse::from)
    }

    /** What each vendor is owed for goods received and not invoiced, the oldest open receipt first. */
    @GetMapping("/balances")
    fun balances(): List<VendorAccrualBalanceResponse> = queries.balances().map(VendorAccrualBalanceResponse::from)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): AccrualResponse = AccrualResponse.from(queries.find(mapRequest { AccrualId.parse(id) }))
}
