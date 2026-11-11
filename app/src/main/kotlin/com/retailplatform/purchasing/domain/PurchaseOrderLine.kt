package com.retailplatform.purchasing.domain

import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money

/** The number the business knows a purchase order by: one to ten digits, kept as text. */
@JvmInline
value class PurchaseOrderNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{1,10}")

        /** @throws IllegalArgumentException unless [value] is one to ten digits. */
        fun of(value: String): PurchaseOrderNumber {
            require(FORMAT.matches(value)) { "Not a purchase order number: '$value'" }
            return PurchaseOrderNumber(value)
        }
    }
}

/** What a buyer asks for on one line: [cases] vendor cases of an item, at [caseCost] if it differs from list. */
@ConsistentCopyVisibility
data class RequestedLine private constructor(
    val itemId: ItemId,
    val cases: Int,
    val caseCost: Money?,
) {
    companion object {
        /** @throws IllegalArgumentException if [cases] is below one or [caseCost] is negative. */
        fun of(
            itemId: ItemId,
            cases: Int,
            caseCost: Money? = null,
        ): RequestedLine {
            require(cases >= 1) { "A line orders at least one case: $cases" }
            require(caseCost?.isNegative != true) { "A case cost cannot be negative: $caseCost" }
            return RequestedLine(itemId, cases, caseCost)
        }
    }
}

/**
 * One line of a purchase order: [cases] of the vendor's case of [casePack] base units, at [caseCost] per case. The
 * SKU, case pack, and cost are copied from the catalog when the line is written, so later catalog changes do not
 * alter an order.
 */
data class PurchaseOrderLine(
    val itemId: ItemId,
    val vendorSku: String,
    val casePack: Int,
    val cases: Int,
    val caseCost: Money,
) {
    val extendedCost: Money get() = caseCost * cases

    /** How many of the item's base units the line orders. */
    val baseUnits: Int get() = cases * casePack

    companion object {
        /** Writes [requested] as a line supplied as [supply] describes, at the requested cost or the list cost. */
        fun of(
            requested: RequestedLine,
            supply: VendorItemCost,
        ): PurchaseOrderLine =
            PurchaseOrderLine(
                itemId = requested.itemId,
                vendorSku = supply.vendorSku,
                casePack = supply.casePack,
                cases = requested.cases,
                caseCost = requested.caseCost ?: supply.caseCost,
            )
    }
}
