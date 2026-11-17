package com.retailplatform.payables.adapter.`in`.web

import com.retailplatform.payables.application.DirectDeliveryPayablesQueries
import com.retailplatform.payables.application.ItemAge
import com.retailplatform.payables.application.OpenItemAgeing
import com.retailplatform.payables.domain.AdjustmentResolution
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.DeliveryAdjustment
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

/**
 * A direct delivery accrual or a vendor credit: an amount for one delivery ticket. [id] is `null` for an accrual.
 * [status] is `OPEN` or `RELIEVED` for an accrual, and `OPEN`, `SCHEDULED`, or `APPLIED` for a credit.
 */
data class TicketAmountResponse(
    val id: String?,
    val deliveryId: String,
    val receiptNumber: String,
    val vendorId: String,
    val storeId: String,
    val ticketNumber: String,
    val receivedOn: LocalDate,
    val amount: MoneyDto,
    val status: String,
    val lines: List<TicketAmountLineResponse>,
) {
    companion object {
        fun from(accrual: DirectDeliveryAccrual) =
            of(null, accrual.ticket, accrual.amount, if (accrual.isOpen) "OPEN" else "RELIEVED", accrual.lines)

        fun from(credit: VendorCredit) =
            of(credit.id.toString(), credit.ticket, credit.amount, credit.status.code, credit.lines)

        private fun of(
            id: String?,
            ticket: DeliveredTicket,
            amount: Money,
            status: String,
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
            status = status,
            lines = lines.map(TicketAmountLineResponse::from),
        )
    }
}

/** An amount owed to a vendor that a late correction brought to light, for accounting to settle. */
data class DeliveryAdjustmentResponse(
    val id: String,
    val deliveryId: String,
    val receiptNumber: String,
    val vendorId: String,
    val storeId: String,
    val ticketNumber: String,
    val recordedOn: LocalDate,
    val kind: String,
    val amount: MoneyDto,
    val status: String,
    val resolvedOn: LocalDate?,
    val resolvedBy: String?,
    val resolutionReason: String?,
    val vendorReference: String?,
    val referenceDate: LocalDate?,
    val paidAmount: MoneyDto,
    val writtenOffAmount: MoneyDto,
    val lines: List<TicketAmountLineResponse>,
    val paidLines: List<TicketAmountLineResponse>?,
    val ageDays: Long?,
    val overdue: Boolean,
) {
    companion object {
        /** [age] is how long the adjustment has waited; `null` once it is settled. */
        fun from(
            adjustment: DeliveryAdjustment,
            age: ItemAge?,
        ) = DeliveryAdjustmentResponse(
            id = adjustment.id.toString(),
            deliveryId = adjustment.ticket.deliveryId.toString(),
            receiptNumber = adjustment.ticket.receiptNumber,
            vendorId = adjustment.ticket.vendorId.toString(),
            storeId = adjustment.ticket.storeId.toString(),
            ticketNumber = adjustment.ticket.ticketNumber,
            recordedOn = adjustment.ticket.receivedOn.value,
            kind = adjustment.kind.name,
            amount = MoneyDto.from(adjustment.amount),
            status = adjustment.statusCode,
            resolvedOn = adjustment.resolution?.resolvedOn?.value,
            resolvedBy = adjustment.resolution?.decision?.approvedBy,
            resolutionReason = adjustment.resolution?.decision?.reason,
            vendorReference = (adjustment.resolution as? AdjustmentResolution.Paid)?.reference?.value,
            referenceDate = (adjustment.resolution as? AdjustmentResolution.Paid)?.referenceDate?.value,
            paidAmount = MoneyDto.from(adjustment.paidAmount),
            writtenOffAmount = MoneyDto.from(adjustment.writtenOffAmount),
            lines = adjustment.lines.map(TicketAmountLineResponse::from),
            paidLines =
                (adjustment.resolution as? AdjustmentResolution.Paid)
                    ?.part
                    ?.map(TicketAmountLineResponse::from),
            ageDays = age?.days,
            overdue = age?.overdue ?: false,
        )
    }
}

@RestController
@RequestMapping("/api/v1/payables")
class DirectDeliveryPayablesController(
    private val queries: DirectDeliveryPayablesQueries,
    private val ageing: OpenItemAgeing,
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

    /**
     * Amounts owed to a vendor that a late correction to a store's receipt brought to light, the oldest first.
     * No invoice matches them; accounting pays each or writes it off. Only those still waiting with
     * `openOnly=true`.
     */
    @GetMapping("/direct-delivery-adjustments")
    fun adjustments(
        @RequestParam vendorId: String,
        @RequestParam(defaultValue = "false") openOnly: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<DeliveryAdjustmentResponse> {
        val vendor = mapRequest { VendorId.parse(vendorId) }
        val found = queries.adjustments(vendor, openOnly, pageQuery(page, size))
        return PageResponse.from(found) { DeliveryAdjustmentResponse.from(it, ageing.ageOf(it)) }
    }
}
