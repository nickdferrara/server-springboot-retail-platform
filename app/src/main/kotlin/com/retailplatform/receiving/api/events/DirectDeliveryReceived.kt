package com.retailplatform.receiving.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import java.math.BigDecimal
import java.time.Instant

/** The direct store delivery an event is about. [ticketNumber] is the number on the vendor driver's ticket. */
data class DirectDeliveryFacts(
    val deliveryId: DirectDeliveryId,
    val receiptNumber: String,
    val vendorId: VendorId,
    val storeId: StoreId,
    val ticketNumber: String,
)

/**
 * One item on a delivery ticket, in the item's base units: [unitsDelivered] put on the shelf and [unitsReturned]
 * taken back by the driver, both at the [unitCost] agreed with the vendor for that store.
 */
data class DirectDeliveryLine(
    val lineNumber: Int,
    val itemId: ItemId,
    val unitsDelivered: Int,
    val unitsReturned: Int,
    val unitCost: BigDecimal,
)

/**
 * A vendor delivered straight to a store, with no purchase order, and may have taken product back. Delivered
 * units are owed to the vendor and returned units are owed by the vendor, both at agreed cost in [currency].
 */
data class DirectDeliveryReceived(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val delivery: DirectDeliveryFacts,
    val currency: String,
    val lines: List<DirectDeliveryLine>,
    val schemaVersion: Int = 1,
)
