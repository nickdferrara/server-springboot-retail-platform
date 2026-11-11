package com.retailplatform.receiving.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId

/** The number the business knows a receipt by: one to ten digits, kept as text. */
@JvmInline
value class ReceiptNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{1,10}")

        /** @throws IllegalArgumentException unless [value] is one to ten digits. */
        fun of(value: String): ReceiptNumber {
            require(FORMAT.matches(value)) { "Not a receipt number: '$value'" }
            return ReceiptNumber(value)
        }
    }
}

/** What a clerk counted at the dock for one purchase order line, in vendor cases. */
@ConsistentCopyVisibility
data class CountedLine private constructor(
    val lineNumber: Int,
    val casesReceived: Int,
    val casesDamaged: Int,
) {
    companion object {
        /** @throws IllegalArgumentException if a count is negative or the line number is below one. */
        fun of(
            lineNumber: Int,
            casesReceived: Int,
            casesDamaged: Int = 0,
        ): CountedLine {
            require(lineNumber >= 1) { "Line numbers start at one: $lineNumber" }
            require(casesReceived >= 0) { "Cases received cannot be negative: $casesReceived" }
            require(casesDamaged >= 0) { "Cases damaged cannot be negative: $casesDamaged" }
            return CountedLine(lineNumber, casesReceived, casesDamaged)
        }
    }
}

/**
 * One purchase order line on a receipt, in vendor cases. [casesReceived] went into stock; [casesDamaged] were
 * refused. [casesOver] of those received were beyond what was still expected, and [casesShort] were never
 * delivered when the order was completed.
 */
data class ReceiptLine(
    val lineNumber: Int,
    val itemId: ItemId,
    val casePack: Int,
    val caseCost: Money,
    val casesReceived: Int,
    val casesDamaged: Int,
    val casesOver: Int,
    val casesShort: Int,
) {
    val hasDiscrepancy: Boolean get() = casesOver > 0 || casesShort > 0 || casesDamaged > 0

    /** The purchase order cost of what went into stock. */
    val extendedCost: Money get() = caseCost * casesReceived
}

/** The purchase order a receipt was received against. */
data class ReceivedAgainst(
    val purchaseOrderId: PurchaseOrderId,
    val purchaseOrderNumber: String,
    val vendorId: VendorId,
    val warehouseId: WarehouseId,
)

/**
 * The record of one delivery checked in at a warehouse dock. A receipt never changes once written; a mistake is
 * corrected in inventory.
 */
data class Receipt(
    val id: ReceiptId,
    val number: ReceiptNumber,
    val against: ReceivedAgainst,
    val receivedOn: BusinessDate,
    val receivedBy: String,
    /** Whether the clerk said nothing more will come on the order. */
    val finalDelivery: Boolean,
    val lines: List<ReceiptLine>,
)
