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

/** One received purchase order line: [casesReceived] vendor cases owed at [caseCost] per case. */
@ConsistentCopyVisibility
data class AccrualLine private constructor(
    val purchaseOrderLineNumber: Int,
    val itemId: ItemId,
    val casesReceived: Int,
    val caseCost: Money,
) {
    val amount: Money get() = caseCost * casesReceived

    companion object {
        /** @throws IllegalArgumentException unless cases are above zero and the cost is not negative. */
        fun of(
            purchaseOrderLineNumber: Int,
            itemId: ItemId,
            casesReceived: Int,
            caseCost: Money,
        ): AccrualLine {
            require(casesReceived >= 1) { "An accrual line is for at least one case: $casesReceived" }
            require(!caseCost.isNegative) { "A case cost cannot be negative: $caseCost" }
            return AccrualLine(purchaseOrderLineNumber, itemId, casesReceived, caseCost)
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
    val lines: List<AccrualLine>,
    openAmount: Money,
    val version: Int,
) : AggregateRoot() {
    /** The sum of the lines. */
    val amount: Money get() = lines.map { it.amount }.reduce(Money::plus)

    var openAmount: Money = openAmount
        private set

    val isOpen: Boolean get() = !openAmount.isZero

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
            val accrual = Accrual(AccrualId.generate(clock), receipt, lines.toList(), total, version = 0)
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
            openAmount: Money,
            version: Int,
        ): Accrual = Accrual(id, receipt, lines, openAmount, version)
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
