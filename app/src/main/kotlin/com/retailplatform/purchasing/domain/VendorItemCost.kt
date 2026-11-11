package com.retailplatform.purchasing.domain

import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.VendorId

/**
 * What purchasing knows about how a vendor supplies an item, learned from the catalog: the vendor's SKU, the base
 * units in the vendor's case, and the list cost per case.
 */
data class VendorItemCost(
    val vendorId: VendorId,
    val itemId: ItemId,
    val vendorSku: String,
    val casePack: Int,
    val caseCost: Money,
)

class ItemNotSuppliedByVendor(
    itemId: ItemId,
    vendorId: VendorId,
) : BusinessRuleViolation(
        "purchasing.item-not-supplied-by-vendor",
        "Item $itemId has no cost from vendor $vendorId in the catalog",
    )
