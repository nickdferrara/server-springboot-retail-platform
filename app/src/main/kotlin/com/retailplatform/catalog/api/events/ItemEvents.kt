package com.retailplatform.catalog.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.VendorId
import java.math.BigDecimal
import java.time.Instant

/** What an item is, as other modules need to know it. */
data class ItemFacts(
    val itemNumber: String,
    val description: String,
    val departmentCode: String,
    val categoryCode: String,
    val baseUnit: String,
    val upcs: List<String>,
)

/** An item was added to the catalog. */
data class ItemCreated(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val itemId: ItemId,
    val item: ItemFacts,
    val schemaVersion: Int = 1,
)

/** An item's description, category, barcodes, or packs changed. [item] is its complete new state. */
data class ItemChanged(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val itemId: ItemId,
    val item: ItemFacts,
    val schemaVersion: Int = 1,
)

/** An item will no longer be bought or sold after [businessDate]. */
data class ItemDiscontinued(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val itemId: ItemId,
    val itemNumber: String,
    val schemaVersion: Int = 1,
)

/**
 * A vendor's list cost for an item was set or changed. Costs are per vendor case of [casePack] base units.
 * [previousCost] is absent the first time the vendor is assigned to the item.
 */
data class VendorCostChanged(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val itemId: ItemId,
    val vendorId: VendorId,
    val vendorSku: String,
    val casePack: Int,
    val previousCost: BigDecimal?,
    val newCost: BigDecimal,
    val currency: String,
    val schemaVersion: Int = 1,
)
