package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.DirectDeliveryPayablesQueries
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.DirectDeliveryAccrual
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.VendorCredit
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.Money
import com.retailplatform.shared.VendorId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

data class TicketAmountLineResponse(
    val itemId: String,
    val units: Int,
    val unitCost: MoneyDto,
    val amount: MoneyDto,
) {
    companion object {
        fun from(line: TicketAmountLine) =
            TicketAmountLineResponse(
                line.itemId.toString(),
                line.units,
                MoneyDto.from(line.unitCost),
                MoneyDto.from(line.amount),
            )
    }
}

/** A direct delivery accrual or a vendor credit: an amount for one delivery ticket. [id] is `null` for an accrual. */
data class TicketAmountResponse(
    val id: String?,
    val deliveryId: String,
    val receiptNumber: String,
    val vendorId: String,
    val storeId: String,
    val ticketNumber: String,
    val receivedOn: LocalDate,
    val amount: MoneyDto,
    val lines: List<TicketAmountLineResponse>,
) {
    companion object {
        fun from(accrual: DirectDeliveryAccrual) = of(null, accrual.ticket, accrual.amount, accrual.lines)

        fun from(credit: VendorCredit) = of(credit.id.toString(), credit.ticket, credit.amount, credit.lines)

        private fun of(
            id: String?,
            ticket: DeliveredTicket,
            amount: Money,
            lines: List<TicketAmountLine>,
        ) = TicketAmountResponse(
            id = id,
            deliveryId = ticket.deliveryId.toString(),
            receiptNumber = ticket.receiptNumber,
            vendorId = ticket.vendorId.toString(),
            storeId = ticket.storeId.toString(),
            ticketNumber = ticket.ticketNumber,
            receivedOn = ticket.receivedOn.value,
            amount = MoneyDto.from(amount),
            lines = lines.map(TicketAmountLineResponse::from),
        )
    }
}

@RestController
@RequestMapping("/api/v1/payables")
class DirectDeliveryPayablesController(
    private val queries: DirectDeliveryPayablesQueries,
) {
    /** What is accrued for a vendor's direct store deliveries, the oldest delivery first. */
    @GetMapping("/direct-delivery-accruals")
    fun accruals(
        @RequestParam vendorId: String,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<TicketAmountResponse> {
        val vendor = mapRequest { VendorId.parse(vendorId) }
        return PageResponse.from(queries.accruals(vendor, pageQuery(page, size))) { TicketAmountResponse.from(it) }
    }

    /** The credits a vendor owes for product taken back from stores, the oldest first. */
    @GetMapping("/vendor-credits")
    fun credits(
        @RequestParam vendorId: String,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<TicketAmountResponse> {
        val vendor = mapRequest { VendorId.parse(vendorId) }
        return PageResponse.from(queries.credits(vendor, pageQuery(page, size))) { TicketAmountResponse.from(it) }
    }
}
