package com.retailplatform.catalog.domain

import com.retailplatform.shared.Money
import com.retailplatform.shared.VendorId

/**
 * How one vendor supplies an item: the vendor's own SKU, how many base units are in the vendor's case, and the
 * vendor's list cost per case.
 */
@ConsistentCopyVisibility
data class VendorItem private constructor(
    val vendorId: VendorId,
    val vendorSku: String,
    val casePack: Int,
    val listCost: Money,
) {
    companion object {
        const val MAX_SKU_LENGTH = 30

        /**
         * @throws IllegalArgumentException if the SKU is blank or too long, the case pack is below one, or the
         * cost is negative.
         */
        fun of(
            vendorId: VendorId,
            vendorSku: String,
            casePack: Int,
            listCost: Money,
        ): VendorItem {
            val sku = vendorSku.trim()
            require(sku.isNotEmpty()) { "A vendor SKU cannot be blank" }
            require(sku.length <= MAX_SKU_LENGTH) { "A vendor SKU has at most $MAX_SKU_LENGTH characters" }
            require(casePack >= 1) { "A case holds at least one unit: $casePack" }
            require(!listCost.isNegative) { "A list cost cannot be negative: $listCost" }
            return VendorItem(vendorId, sku, casePack, listCost)
        }
    }
}
