package com.retailplatform.receiving.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import java.math.BigDecimal
import java.time.Instant

/**
 * How a correction changed one item on a delivery ticket, in the item's base units. A change is positive when
 * more was delivered or returned than first recorded and negative when less was. [unitCost] is the cost the
 * ticket carries for the item.
 */
data class DirectDeliveryCorrectionLine(
    val itemId: ItemId,
    val unitsDeliveredChange: Int,
    val unitsReturnedChange: Int,
    val unitCost: BigDecimal,
)

/**
 * A store corrected its record of a direct delivery. [delivery] is the delivery as first checked in;
 * [correctionReceiptNumber] is the number of the correction, a receipt of its own. [lines] holds only the items
 * that changed, as changes from what was on record, costed in [currency]. [businessDate] is the day of the
 * correction.
 */
data class DirectDeliveryCorrected(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val delivery: DirectDeliveryFacts,
    val correctionReceiptNumber: String,
    val reason: String,
    val currency: String,
    val lines: List<DirectDeliveryCorrectionLine>,
    val schemaVersion: Int = 1,
)
