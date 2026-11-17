package com.retailplatform.payables.application

import com.retailplatform.payables.domain.DeductionDispute
import com.retailplatform.payables.domain.DeductionNotice
import com.retailplatform.payables.domain.DeliveryAdjustment
import com.retailplatform.payables.domain.DisputeStatus
import com.retailplatform.payables.domain.LateBill
import com.retailplatform.payables.domain.LateBillStatus
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The kinds of direct delivery item that wait for accounting: three for a manager to decide, and a deduction
 * notice for a clerk to send.
 */
enum class OpenItemKind { ADJUSTMENT, LATE_BILL, DISPUTE, NOTICE }

/** How long an item has waited, in days, and whether that is longer than its kind allows. */
data class ItemAge(
    val days: Long,
    val overdue: Boolean,
)

/**
 * Works out how long an open item has waited and whether it is overdue. An adjustment waits since the
 * correction that raised it, a late bill since it was recorded, a dispute since the vendor's claim arrived, and a
 * deduction notice since it was raised.
 */
@Component
class OpenItemAgeing(
    private val properties: PayablesProperties,
    private val clock: Clock,
) {
    fun ageOf(
        kind: OpenItemKind,
        openSince: BusinessDate,
    ): ItemAge {
        val days = BusinessDate.today(clock).value.toEpochDay() - openSince.value.toEpochDay()
        return ItemAge(days, days > allowedDays(kind))
    }

    /** How long [adjustment] has waited; `null` once it is settled. */
    fun ageOf(adjustment: DeliveryAdjustment): ItemAge? =
        if (adjustment.isOpen) ageOf(OpenItemKind.ADJUSTMENT, adjustment.ticket.receivedOn) else null

    /** How long [lateBill] has waited; `null` once it is settled. */
    fun ageOf(lateBill: LateBill): ItemAge? =
        if (lateBill.status == LateBillStatus.Open) ageOf(OpenItemKind.LATE_BILL, lateBill.recordedOn) else null

    /** How long [dispute] has waited; `null` once it is decided. */
    fun ageOf(dispute: DeductionDispute): ItemAge? =
        if (dispute.status == DisputeStatus.Open) ageOf(OpenItemKind.DISPUTE, dispute.claim.receivedOn) else null

    /** How long [notice] has waited to be sent; `null` once it was. */
    fun ageOf(notice: DeductionNotice): ItemAge? =
        if (notice.sending == null) ageOf(OpenItemKind.NOTICE, notice.raisedOn) else null

    /** An item of [kind] is overdue if it has been open since before this day. */
    fun overdueBefore(kind: OpenItemKind): BusinessDate = BusinessDate.today(clock).minusDays(allowedDays(kind))

    private fun allowedDays(kind: OpenItemKind): Long =
        when (kind) {
            OpenItemKind.ADJUSTMENT -> properties.ageing.adjustmentDays
            OpenItemKind.LATE_BILL -> properties.ageing.lateBillDays
            OpenItemKind.DISPUTE -> properties.ageing.disputeDays
            OpenItemKind.NOTICE -> properties.ageing.noticeDays
        }
}

/**
 * One item waiting for accounting, whatever its kind: [amount] is the adjustment's, the late bill's, the
 * deduction a dispute contests, or the deduction a notice tells of, and [openSince] is the day it started to wait.
 */
data class OpenItem(
    val kind: OpenItemKind,
    val id: UUID,
    val vendorId: VendorId,
    val storeId: StoreId,
    val ticketNumber: String,
    val openSince: BusinessDate,
    val amount: Money,
)

/** An open item with how long it has waited. */
data class AgedOpenItem(
    val item: OpenItem,
    val age: ItemAge,
)

/** How many items of one kind are open, and how many of those are overdue. */
data class OpenItemCount(
    val open: Long,
    val overdue: Long,
)

/**
 * Which open items to list: only one vendor's if [vendorId] is given, only overdue ones if [overdueOnly], and
 * only those of [kinds] unless it is empty, which means every kind.
 */
data class OpenItemFilter(
    val vendorId: VendorId?,
    val overdueOnly: Boolean,
    val kinds: Set<OpenItemKind> = emptySet(),
)

/** Port: reads the items waiting for accounting across adjustments, late bills, disputes, and unsent notices. */
interface OpenItemReader {
    /** Open items, the one that has waited longest first. [overdueBefore] gives each kind's overdue day. */
    fun find(
        filter: OpenItemFilter,
        overdueBefore: Map<OpenItemKind, BusinessDate>,
        query: PageQuery,
    ): Page<OpenItem>

    /** Open and overdue counts for every kind, zero where there are none. */
    fun counts(overdueBefore: Map<OpenItemKind, BusinessDate>): Map<OpenItemKind, OpenItemCount>
}

/** Queries: everything waiting for accounting on direct deliveries, across vendors, with its age. */
@Service
@Transactional(readOnly = true)
class OpenItemQueries(
    private val reader: OpenItemReader,
    private val ageing: OpenItemAgeing,
) {
    fun list(
        filter: OpenItemFilter,
        query: PageQuery,
    ): Page<AgedOpenItem> =
        reader.find(filter, overdueDays(), query).map { AgedOpenItem(it, ageing.ageOf(it.kind, it.openSince)) }

    fun counts(): Map<OpenItemKind, OpenItemCount> = reader.counts(overdueDays())

    private fun overdueDays(): Map<OpenItemKind, BusinessDate> =
        OpenItemKind.entries.associateWith(ageing::overdueBefore)
}
