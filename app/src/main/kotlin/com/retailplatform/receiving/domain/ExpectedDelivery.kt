package com.retailplatform.receiving.domain

import com.retailplatform.receiving.api.events.GoodsReceived
import com.retailplatform.receiving.api.events.GoodsReceivedLine
import com.retailplatform.receiving.api.events.ReceiptDiscrepancyLine
import com.retailplatform.receiving.api.events.ReceiptDiscrepancyRecorded
import com.retailplatform.receiving.api.events.ReceiptFacts
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.ReceiptId
import java.time.Clock

/** Whether more can be received against a purchase order. */
enum class DeliveryStatus {
    /** Sent to the vendor; goods are still expected. */
    OPEN,

    /** Everything ordered has arrived, or a clerk said nothing more will come. */
    COMPLETE,

    /** The buyer cancelled the order. */
    CANCELLED,
}

/** One purchase order line as the dock sees it: what was ordered and how much of it has gone into stock. */
data class ExpectedLine(
    val lineNumber: Int,
    val itemId: ItemId,
    val vendorSku: String,
    val casePack: Int,
    val caseCost: Money,
    val casesOrdered: Int,
    val casesReceived: Int = 0,
) {
    val casesOutstanding: Int get() = (casesOrdered - casesReceived).coerceAtLeast(0)
}

/** Who checks a delivery in, and the number the receipt will carry. */
data class ReceiptHeader(
    val number: ReceiptNumber,
    val receivedBy: String,
)

/**
 * What the dock knows about a purchase order that was sent to a vendor: the lines to expect and what has arrived
 * so far. Receiving against it writes a [Receipt].
 */
class ExpectedDelivery private constructor(
    val against: ReceivedAgainst,
    val expectedOn: BusinessDate,
    lines: List<ExpectedLine>,
    status: DeliveryStatus,
    val version: Int,
) : AggregateRoot() {
    var lines: List<ExpectedLine> = lines.toList()
        private set
    var status: DeliveryStatus = status
        private set

    /**
     * Checks in a delivery. Cases received go into stock, even beyond what was ordered; damaged cases are refused
     * and stay outstanding. The order completes when every line has fully arrived or [finalDelivery] says nothing
     * more will come, and what is then missing is recorded as short.
     *
     * Raises [GoodsReceived] if anything went into stock, and [ReceiptDiscrepancyRecorded] if anything was over,
     * short, or damaged.
     *
     * @throws DeliveryNotOpen if the order is complete or cancelled.
     * @throws LineNotOnOrder if a counted line is not on the order.
     * @throws LineCountedTwice if a line is counted more than once.
     * @throws ReceiptRecordsNothing if nothing was received or damaged and the delivery is not final.
     */
    fun receive(
        counted: List<CountedLine>,
        finalDelivery: Boolean,
        header: ReceiptHeader,
        clock: Clock,
    ): Receipt {
        check(counted, finalDelivery)
        val countedByLine = counted.associateBy { it.lineNumber }
        val before = lines
        lines =
            lines.map { it.copy(casesReceived = it.casesReceived + (countedByLine[it.lineNumber]?.casesReceived ?: 0)) }
        if (finalDelivery || lines.all { it.casesOutstanding == 0 }) status = DeliveryStatus.COMPLETE

        val receiptLines =
            before.zip(lines).mapNotNull { (was, now) ->
                val count = countedByLine[was.lineNumber]
                val received = count?.casesReceived ?: 0
                ReceiptLine(
                    lineNumber = was.lineNumber,
                    itemId = was.itemId,
                    casePack = was.casePack,
                    caseCost = was.caseCost,
                    casesReceived = received,
                    casesDamaged = count?.casesDamaged ?: 0,
                    casesOver = (received - was.casesOutstanding).coerceAtLeast(0),
                    casesShort = if (finalDelivery) now.casesOutstanding else 0,
                ).takeIf { it.casesReceived > 0 || it.hasDiscrepancy }
            }
        val receipt =
            Receipt(
                id = ReceiptId.generate(clock),
                number = header.number,
                against = against,
                receivedOn = BusinessDate.today(clock),
                receivedBy = header.receivedBy,
                finalDelivery = finalDelivery,
                lines = receiptLines,
            )
        announce(receipt, clock)
        return receipt
    }

    /** Stops expecting the order after the buyer cancelled it. Does nothing unless the order is open. */
    fun cancel() {
        if (status == DeliveryStatus.OPEN) status = DeliveryStatus.CANCELLED
    }

    private fun check(
        counted: List<CountedLine>,
        finalDelivery: Boolean,
    ) {
        val known = lines.map { it.lineNumber }.toSet()
        val numbers = counted.map { it.lineNumber }
        val violation =
            when {
                status != DeliveryStatus.OPEN -> DeliveryNotOpen(against.purchaseOrderNumber, status)
                !known.containsAll(numbers) -> LineNotOnOrder(against.purchaseOrderNumber, numbers - known)
                numbers.size != numbers.toSet().size -> LineCountedTwice()
                !finalDelivery && counted.all { it.casesReceived + it.casesDamaged == 0 } -> ReceiptRecordsNothing()
                else -> null
            }
        if (violation != null) throw violation
    }

    private fun announce(
        receipt: Receipt,
        clock: Clock,
    ) {
        val facts =
            ReceiptFacts(
                receiptId = receipt.id,
                receiptNumber = receipt.number.value,
                purchaseOrderId = against.purchaseOrderId,
                purchaseOrderNumber = against.purchaseOrderNumber,
                vendorId = against.vendorId,
                warehouseId = against.warehouseId,
            )
        val accepted = receipt.lines.filter { it.casesReceived > 0 }
        if (accepted.isNotEmpty()) {
            raise(
                GoodsReceived(
                    eventId = EventId.generate(clock),
                    occurredAt = clock.instant(),
                    businessDate = receipt.receivedOn,
                    receipt = facts,
                    currency =
                        accepted
                            .first()
                            .caseCost.currency.currencyCode,
                    lines =
                        accepted.map {
                            GoodsReceivedLine(
                                it.lineNumber,
                                it.itemId,
                                it.casePack,
                                it.casesReceived,
                                it.caseCost.amount,
                            )
                        },
                    purchaseOrderComplete = status == DeliveryStatus.COMPLETE,
                ),
            )
        }
        val differing = receipt.lines.filter { it.hasDiscrepancy }
        if (differing.isNotEmpty()) {
            raise(
                ReceiptDiscrepancyRecorded(
                    eventId = EventId.generate(clock),
                    occurredAt = clock.instant(),
                    businessDate = receipt.receivedOn,
                    receipt = facts,
                    lines =
                        differing.map {
                            ReceiptDiscrepancyLine(
                                it.lineNumber,
                                it.itemId,
                                it.casesOver,
                                it.casesShort,
                                it.casesDamaged,
                            )
                        },
                ),
            )
        }
    }

    companion object {
        /** Starts expecting a purchase order that was sent to its vendor. */
        fun expect(
            against: ReceivedAgainst,
            expectedOn: BusinessDate,
            lines: List<ExpectedLine>,
        ): ExpectedDelivery = ExpectedDelivery(against, expectedOn, lines, DeliveryStatus.OPEN, version = 0)

        /** Rebuilds an expected delivery from stored state. Raises no events. */
        fun rehydrate(
            against: ReceivedAgainst,
            expectedOn: BusinessDate,
            lines: List<ExpectedLine>,
            status: DeliveryStatus,
            version: Int,
        ): ExpectedDelivery = ExpectedDelivery(against, expectedOn, lines, status, version)
    }
}

class DeliveryNotOpen(
    purchaseOrderNumber: String,
    status: DeliveryStatus,
) : BusinessRuleViolation(
        "receiving.delivery-not-open",
        "Nothing more can be received on purchase order $purchaseOrderNumber: it is ${status.name.lowercase()}",
    )

class LineNotOnOrder(
    purchaseOrderNumber: String,
    lineNumbers: Collection<Int>,
) : BusinessRuleViolation(
        "receiving.line-not-on-order",
        "Purchase order $purchaseOrderNumber has no line ${lineNumbers.joinToString()}",
    )

class LineCountedTwice :
    BusinessRuleViolation("receiving.line-counted-twice", "Each purchase order line can be counted once per receipt")

class ReceiptRecordsNothing :
    BusinessRuleViolation(
        "receiving.receipt-records-nothing",
        "A receipt records cases received or damaged, or that the delivery was the last",
    )

class PurchaseOrderNotExpected(
    purchaseOrderId: Any,
) : BusinessRuleViolation(
        "receiving.purchase-order-not-expected",
        "Purchase order $purchaseOrderId has not been sent to its vendor",
    )
