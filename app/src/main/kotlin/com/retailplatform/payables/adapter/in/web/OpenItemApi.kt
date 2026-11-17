package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.AgedOpenItem
import com.retailplatform.payables.application.OpenItemFilter
import com.retailplatform.payables.application.OpenItemQueries
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.VendorId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** One direct delivery item waiting for accounting: an adjustment, a late bill, or a dispute. */
data class OpenItemResponse(
    val kind: String,
    val id: String,
    val vendorId: String,
    val storeId: String,
    val ticketNumber: String,
    val openSince: LocalDate,
    val amount: MoneyDto,
    val ageDays: Long,
    val overdue: Boolean,
) {
    companion object {
        fun from(aged: AgedOpenItem) =
            OpenItemResponse(
                kind = aged.item.kind.name,
                id = aged.item.id.toString(),
                vendorId = aged.item.vendorId.toString(),
                storeId = aged.item.storeId.toString(),
                ticketNumber = aged.item.ticketNumber,
                openSince = aged.item.openSince.value,
                amount = MoneyDto.from(aged.item.amount),
                ageDays = aged.age.days,
                overdue = aged.age.overdue,
            )
    }
}

@RestController
@RequestMapping("/api/v1/payables/direct-delivery-open-items")
class OpenItemController(
    private val queries: OpenItemQueries,
) {
    /**
     * Every adjustment, late bill, and dispute still waiting for accounting, across vendors, the one that has
     * waited longest first. Only overdue ones with `overdueOnly=true`; one vendor's with `vendorId`.
     */
    @GetMapping
    fun list(
        @RequestParam(required = false) vendorId: String?,
        @RequestParam(defaultValue = "false") overdueOnly: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<OpenItemResponse> {
        val vendor = mapRequest { vendorId?.let(VendorId::parse) }
        val filter = OpenItemFilter(vendor, overdueOnly)
        return PageResponse.from(queries.list(filter, pageQuery(page, size)), OpenItemResponse::from)
    }
}
