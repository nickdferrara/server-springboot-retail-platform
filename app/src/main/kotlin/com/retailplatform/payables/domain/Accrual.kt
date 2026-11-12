package com.retailplatform.payables.domain

import com.retailplatform.payables.api.events.AccrualRecorded
import com.retailplatform.shared.AccrualId
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import java.time.Clock

/** The receipt an accrual was recorded for. */
data class AccruedReceipt(
    val receiptId: ReceiptId,
    val receiptNumber: String,
    val purchaseOrderId: PurchaseOrderId,
    val purchaseOrderNumber: String,
    val vendorId: VendorId,
    val warehouseId: WarehouseId,
    val receivedOn: BusinessDate,
)

/**
 * One received purchase order line: [casesReceived] vendor cases owed at [caseCost] per case, of which
 * [casesInvoiced] have been matched to a vendor invoice.
 */
@ConsistentCopyVisibility
data class AccrualLine private constructor(
    val purchaseOrderLineNumber: Int,
    val itemId: ItemId,
    val casesReceived: Int,
    val caseCost: Money,
    val casesInvoiced: Int,
) {
    val amount: Money get() = caseCost * casesReceived

    /** Cases received that no invoice has been matched to yet. */
    val casesUninvoiced: Int get() = casesReceived - casesInvoiced

    val openAmount: Money get() = caseCost * casesUninvoiced

    /** This line with [cases] more matched to an invoice. */
    fun invoiced(cases: Int): AccrualLine {
        require(cases in 0..casesUninvoiced) { "Cannot invoice $cases of $casesUninvoiced uninvoiced cases" }
        return copy(casesInvoiced = casesInvoiced + cases)
    }

    companion object {
        /** @throws IllegalArgumentException unless cases are above zero and the cost is not negative. */
        fun of(
            purchaseOrderLineNumber: Int,
            itemId: ItemId,
            casesReceived: Int,
            caseCost: Money,
            casesInvoiced: Int = 0,
        ): AccrualLine {
            require(casesReceived >= 1) { "An accrual line is for at least one case: $casesReceived" }
            require(!caseCost.isNegative) { "A case cost cannot be negative: $caseCost" }
            require(casesInvoiced in 0..casesReceived) { "Cases invoiced must be within cases received" }
            return AccrualLine(purchaseOrderLineNumber, itemId, casesReceived, caseCost, casesInvoiced)
        }
    }
}

/**
 * What the business owes a vendor for one receipt whose invoice has not arrived: goods received, not invoiced.
 * [amount] is what was received at purchase order cost; [openAmount] is how much of it no invoice has yet
 * relieved.
 */
class Accrual private constructor(
    val id: AccrualId,
    val receipt: AccruedReceipt,
    lines: List<AccrualLine>,
    val version: Int,
) : AggregateRoot() {
    var lines: List<AccrualLine> = lines.toList()
        private set

    /** The sum of the lines. */
    val amount: Money get() = lines.map { it.amount }.reduce(Money::plus)

    val openAmount: Money get() = lines.map { it.openAmount }.reduce(Money::plus)

    val isOpen: Boolean get() = !openAmount.isZero

    /** Cases of purchase order line [lineNumber] on this receipt that no invoice has been matched to. */
    fun uninvoiced(lineNumber: Int): Int =
        lines.filter { it.purchaseOrderLineNumber == lineNumber }.sumOf { it.casesUninvoiced }

    /**
     * Relieves up to [cases] of purchase order line [lineNumber], because an invoice was matched to them.
     *
     * @return how many cases were relieved here; the rest belong to another receipt.
     */
    fun relieve(
        lineNumber: Int,
        cases: Int,
    ): Int {
        var remaining = cases
        lines =
            lines.map { line ->
                if (line.purchaseOrderLineNumber != lineNumber) return@map line
                val taken = minOf(remaining, line.casesUninvoiced)
                remaining -= taken
                line.invoiced(taken)
            }
        return cases - remaining
    }

    /** Whole days from the receipt to [date]. */
    fun ageOn(date: BusinessDate): Long = date.value.toEpochDay() - receipt.receivedOn.value.toEpochDay()

    companion object {
        /**
         * Accrues what [receipt] brought in, raising [AccrualRecorded].
         *
         * @throws IllegalArgumentException if there are no lines or they are not all in one currency.
         */
        fun record(
            receipt: AccruedReceipt,
            lines: List<AccrualLine>,
            clock: Clock,
        ): Accrual {
            require(lines.isNotEmpty()) { "An accrual has at least one line" }
            require(lines.map { it.caseCost.currency }.toSet().size == 1) { "An accrual is in one currency" }
            val total = lines.map { it.amount }.reduce(Money::plus)
            val accrual = Accrual(AccrualId.generate(clock), receipt, lines, version = 0)
            accrual.raise(
                AccrualRecorded(
                    eventId = EventId.generate(clock),
                    occurredAt = clock.instant(),
                    businessDate = receipt.receivedOn,
                    accrualId = accrual.id,
                    receiptId = receipt.receiptId,
                    receiptNumber = receipt.receiptNumber,
                    purchaseOrderId = receipt.purchaseOrderId,
                    purchaseOrderNumber = receipt.purchaseOrderNumber,
                    vendorId = receipt.vendorId,
                    warehouseId = receipt.warehouseId,
                    amount = total.amount,
                    currency = total.currency.currencyCode,
                ),
            )
            return accrual
        }

        /** Rebuilds an accrual from stored state. Raises no events. */
        fun rehydrate(
            id: AccrualId,
            receipt: AccruedReceipt,
            lines: List<AccrualLine>,
            version: Int,
        ): Accrual = Accrual(id, receipt, lines, version)
    }
}

/**
 * What is owed to one vendor in one currency for goods received and not invoiced, and how old the oldest open
 * receipt is. [stale] marks a vendor whose oldest open accrual has waited longer than the business allows.
 */
data class VendorAccrualBalance(
    val vendorId: VendorId,
    val openAmount: Money,
    val openAccruals: Int,
    val oldestReceivedOn: BusinessDate,
    val oldestAgeDays: Long,
    val stale: Boolean,
)
