package com.retailplatform.receiving.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.TypedId
import com.retailplatform.shared.TypedIdFactory
import java.util.UUID

/** Identifies a correction to a direct store delivery. */
@JvmInline
value class DeliveryCorrectionId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<DeliveryCorrectionId>(::DeliveryCorrectionId)
}

/** Why a delivery was corrected: not blank, at most 200 characters. */
@JvmInline
value class CorrectionReason private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 200

        /** @throws IllegalArgumentException if [value] is blank or longer than [MAX_LENGTH] once trimmed. */
        fun of(value: String): CorrectionReason {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "A correction needs a reason" }
            require(trimmed.length <= MAX_LENGTH) { "A reason has at most $MAX_LENGTH characters" }
            return CorrectionReason(trimmed)
        }
    }
}

/** What an item on a ticket should read after a correction: the right totals, in the item's base units. */
@ConsistentCopyVisibility
data class CorrectedCount private constructor(
    val itemId: ItemId,
    val unitsDelivered: Int,
    val unitsReturned: Int,
) {
    companion object {
        /** @throws IllegalArgumentException if a count is negative. */
        fun of(
            itemId: ItemId,
            unitsDelivered: Int,
            unitsReturned: Int = 0,
        ): CorrectedCount {
            require(unitsDelivered >= 0) { "Units delivered cannot be negative: $unitsDelivered" }
            require(unitsReturned >= 0) { "Units returned cannot be negative: $unitsReturned" }
            return CorrectedCount(itemId, unitsDelivered, unitsReturned)
        }
    }
}

/**
 * How a correction changed one item: positive when more was delivered or returned than was on record, negative
 * when less was. [unitCost] is the cost the ticket carries for the item.
 */
data class CorrectionLine(
    val itemId: ItemId,
    val unitsDeliveredChange: Int,
    val unitsReturnedChange: Int,
    val unitCost: Money,
)

/**
 * A correction to a direct store delivery: a further receipt, with a number of its own, that records how the
 * counts changed and why. Like the delivery it corrects, it never changes once written.
 */
data class DeliveryCorrection(
    val id: DeliveryCorrectionId,
    val deliveryId: DirectDeliveryId,
    val receiptNumber: ReceiptNumber,
    val correctedOn: BusinessDate,
    val correctedBy: String,
    val reason: CorrectionReason,
    val lines: List<CorrectionLine>,
)

class CorrectionChangesNothing(
    ticket: DeliveryTicketNumber,
) : BusinessRuleViolation(
        "receiving.correction-changes-nothing",
        "The counts given for ticket $ticket are what is already on record",
    )

class CorrectionWouldVoidDelivery(
    ticket: DeliveryTicketNumber,
) : BusinessRuleViolation(
        "receiving.correction-would-void-delivery",
        "A correction cannot leave ticket $ticket with nothing delivered or returned",
    )
