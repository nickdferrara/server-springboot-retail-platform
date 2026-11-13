package com.retailplatform.receiving.domain

import com.retailplatform.receiving.api.events.DirectDeliveryCorrected
import com.retailplatform.receiving.api.events.DirectDeliveryCorrectionLine
import com.retailplatform.receiving.api.events.DirectDeliveryFacts
import com.retailplatform.receiving.api.events.DirectDeliveryLine
import com.retailplatform.receiving.api.events.DirectDeliveryReceived
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import java.time.Clock

/** The number on a vendor driver's delivery ticket: not blank, at most 30 characters. */
@JvmInline
value class DeliveryTicketNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 30

        /** @throws IllegalArgumentException if [value] is blank or longer than [MAX_LENGTH] once trimmed. */
        fun of(value: String): DeliveryTicketNumber {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "A delivery ticket number cannot be blank" }
            require(trimmed.length <= MAX_LENGTH) { "A delivery ticket number has at most $MAX_LENGTH characters" }
            return DeliveryTicketNumber(trimmed)
        }
    }
}

/**
 * What a vendor charges one store for one base unit of an item, from [effectiveFrom] until a later cost replaces
 * it. A vendor may deliver an item to a store only while it has an agreed cost there.
 */
@ConsistentCopyVisibility
data class AgreedCost private constructor(
    val vendorId: VendorId,
    val itemId: ItemId,
    val storeId: StoreId,
    val unitCost: Money,
    val effectiveFrom: BusinessDate,
) {
    companion object {
        /** @throws IllegalArgumentException if [unitCost] is negative. */
        fun of(
            vendorId: VendorId,
            itemId: ItemId,
            storeId: StoreId,
            unitCost: Money,
            effectiveFrom: BusinessDate,
        ): AgreedCost {
            require(!unitCost.isNegative) { "An agreed cost cannot be negative: $unitCost" }
            return AgreedCost(vendorId, itemId, storeId, unitCost, effectiveFrom)
        }
    }
}

/** What a store manager counted for one item on a delivery ticket, in the item's base units. */
@ConsistentCopyVisibility
data class TicketLine private constructor(
    val itemId: ItemId,
    val unitsDelivered: Int,
    val unitsReturned: Int,
) {
    companion object {
        /** @throws IllegalArgumentException if a count is negative or both are zero. */
        fun of(
            itemId: ItemId,
            unitsDelivered: Int,
            unitsReturned: Int = 0,
        ): TicketLine {
            require(unitsDelivered >= 0) { "Units delivered cannot be negative: $unitsDelivered" }
            require(unitsReturned >= 0) { "Units returned cannot be negative: $unitsReturned" }
            require(unitsDelivered + unitsReturned > 0) { "A ticket line delivers or returns at least one unit" }
            return TicketLine(itemId, unitsDelivered, unitsReturned)
        }
    }
}

/** One item on a checked-in delivery: units delivered and returned at the cost agreed for the store. */
data class DeliveredLine(
    val lineNumber: Int,
    val itemId: ItemId,
    val unitsDelivered: Int,
    val unitsReturned: Int,
    val unitCost: Money,
) {
    /** What is owed to the vendor for the units delivered. */
    val deliveredCost: Money get() = unitCost * unitsDelivered

    /** What the vendor owes for the units it took back. */
    val returnedCost: Money get() = unitCost * unitsReturned
}

/** Who delivered what ticket to which store. A vendor's ticket number is checked in once per store. */
data class DeliveryTicket(
    val vendorId: VendorId,
    val storeId: StoreId,
    val number: DeliveryTicketNumber,
)

/**
 * A vendor's delivery straight to a store, checked in by the store manager from the driver's ticket: what went on
 * the shelf and what the driver took back. There is no purchase order; each item is costed at what was agreed
 * with the vendor for that store. It never changes once written; a mistake is put right by a
 * [DeliveryCorrection].
 */
class DirectDelivery private constructor(
    val id: DirectDeliveryId,
    val receiptNumber: ReceiptNumber,
    val ticket: DeliveryTicket,
    val receivedOn: BusinessDate,
    val receivedBy: String,
    val lines: List<DeliveredLine>,
) : AggregateRoot() {
    /** What is owed to the vendor for everything delivered. */
    val deliveredCost: Money get() = lines.map { it.deliveredCost }.reduce(Money::plus)

    /** What the vendor owes for everything it took back. */
    val returnedCost: Money get() = lines.map { it.returnedCost }.reduce(Money::plus)

    /** What the ticket reads once [corrections] are applied to what was first checked in. */
    fun currentLines(corrections: List<DeliveryCorrection>): List<DeliveredLine> {
        val changes = corrections.flatMap { it.lines }
        val known = lines.map { it.itemId }.toSet()
        val added =
            changes
                .filterNot { it.itemId in known }
                .distinctBy { it.itemId }
                .mapIndexed {
                    index,
                    change,
                    ->
                    DeliveredLine(lines.size + index + 1, change.itemId, 0, 0, change.unitCost)
                }
        return (lines + added).map { line ->
            val forItem = changes.filter { it.itemId == line.itemId }
            line.copy(
                unitsDelivered = line.unitsDelivered + forItem.sumOf { it.unitsDeliveredChange },
                unitsReturned = line.unitsReturned + forItem.sumOf { it.unitsReturnedChange },
            )
        }
    }

    /**
     * Corrects the delivery so that each item in [counts] reads what it should, given the [earlier] corrections,
     * and raises [DirectDeliveryCorrected] with the changes. An item not yet on the ticket is added at the cost
     * in [costs], the costs agreed on the day the delivery was checked in, so the ticket stays at one cost.
     *
     * @throws DeliveryItemRepeated if two counts are for the same item.
     * @throws NoAgreedCost if an added item had no agreed cost for the vendor at the store.
     * @throws CorrectionChangesNothing if every count is what is already on record.
     * @throws CorrectionWouldVoidDelivery if nothing would be left delivered or returned.
     */
    fun correct(
        header: ReceiptHeader,
        reason: CorrectionReason,
        counts: List<CorrectedCount>,
        earlier: List<DeliveryCorrection>,
        costs: Map<ItemId, Money>,
        clock: Clock,
    ): DeliveryCorrection {
        val current = currentLines(earlier).associateBy { it.itemId }
        val currency = lines.first().unitCost.currency
        requireEachItemOnceWithCost(ticket, counts.map { it.itemId }, costs + current.mapValues { it.value.unitCost })
        val changes =
            counts.mapNotNull { count ->
                val line = current[count.itemId]
                val unitCost = line?.unitCost ?: costs.getValue(count.itemId)
                if (unitCost.currency != currency) throw DeliveryInMixedCurrencies(ticket.number)
                CorrectionLine(
                    itemId = count.itemId,
                    unitsDeliveredChange = count.unitsDelivered - (line?.unitsDelivered ?: 0),
                    unitsReturnedChange = count.unitsReturned - (line?.unitsReturned ?: 0),
                    unitCost = unitCost,
                ).takeUnless { it.unitsDeliveredChange == 0 && it.unitsReturnedChange == 0 }
            }
        val correction =
            DeliveryCorrection(
                id = DeliveryCorrectionId.generate(clock),
                deliveryId = id,
                receiptNumber = header.number,
                correctedOn = BusinessDate.today(clock),
                correctedBy = header.receivedBy,
                reason = reason,
                lines = changes,
            )
        requireMeaningful(correction, earlier)
        raise(corrected(correction, clock))
        return correction
    }

    private fun requireMeaningful(
        correction: DeliveryCorrection,
        earlier: List<DeliveryCorrection>,
    ) {
        if (correction.lines.isEmpty()) throw CorrectionChangesNothing(ticket.number)
        val left = currentLines(earlier + correction).sumOf { it.unitsDelivered + it.unitsReturned }
        if (left == 0) throw CorrectionWouldVoidDelivery(ticket.number)
    }

    private fun corrected(
        correction: DeliveryCorrection,
        clock: Clock,
    ) = DirectDeliveryCorrected(
        eventId = EventId.generate(clock),
        occurredAt = clock.instant(),
        businessDate = correction.correctedOn,
        delivery = facts(),
        correctionReceiptNumber = correction.receiptNumber.value,
        reason = correction.reason.value,
        currency =
            lines
                .first()
                .unitCost.currency.currencyCode,
        lines =
            correction.lines.map {
                DirectDeliveryCorrectionLine(
                    it.itemId,
                    it.unitsDeliveredChange,
                    it.unitsReturnedChange,
                    it.unitCost.amount,
                )
            },
    )

    private fun facts() =
        DirectDeliveryFacts(id, receiptNumber.value, ticket.vendorId, ticket.storeId, ticket.number.value)

    companion object {
        /**
         * Checks in a delivery today and raises [DirectDeliveryReceived]. [costs] holds the agreed unit cost of
         * each item the vendor may deliver to the store.
         *
         * @throws DeliveryItemRepeated if two lines are for the same item.
         * @throws NoAgreedCost if an item has no agreed cost for the vendor at the store.
         * @throws DeliveryInMixedCurrencies if the agreed costs are not all in one currency.
         */
        fun receive(
            header: ReceiptHeader,
            ticket: DeliveryTicket,
            counted: List<TicketLine>,
            costs: Map<ItemId, Money>,
            clock: Clock,
        ): DirectDelivery {
            require(counted.isNotEmpty()) { "A delivery has at least one line" }
            requireEachItemOnceWithCost(ticket, counted.map { it.itemId }, costs)

            val lines =
                counted.mapIndexed { index, line ->
                    DeliveredLine(
                        lineNumber = index + 1,
                        itemId = line.itemId,
                        unitsDelivered = line.unitsDelivered,
                        unitsReturned = line.unitsReturned,
                        unitCost = costs.getValue(line.itemId),
                    )
                }
            val currencies = lines.map { it.unitCost.currency }.toSet()
            if (currencies.size > 1) throw DeliveryInMixedCurrencies(ticket.number)

            val delivery =
                DirectDelivery(
                    id = DirectDeliveryId.generate(clock),
                    receiptNumber = header.number,
                    ticket = ticket,
                    receivedOn = BusinessDate.today(clock),
                    receivedBy = header.receivedBy,
                    lines = lines,
                )
            delivery.raise(delivery.received(currencies.single().currencyCode, clock))
            return delivery
        }

        private fun requireEachItemOnceWithCost(
            ticket: DeliveryTicket,
            items: List<ItemId>,
            costs: Map<ItemId, Money>,
        ) {
            if (items.size != items.toSet().size) throw DeliveryItemRepeated(ticket.number)
            val uncosted = items.filterNot(costs::containsKey)
            if (uncosted.isNotEmpty()) throw NoAgreedCost(uncosted)
        }

        /** Rebuilds a delivery from stored state. Raises no events. */
        fun rehydrate(
            id: DirectDeliveryId,
            receiptNumber: ReceiptNumber,
            ticket: DeliveryTicket,
            receivedOn: BusinessDate,
            receivedBy: String,
            lines: List<DeliveredLine>,
        ): DirectDelivery = DirectDelivery(id, receiptNumber, ticket, receivedOn, receivedBy, lines)
    }

    private fun received(
        currency: String,
        clock: Clock,
    ) = DirectDeliveryReceived(
        eventId = EventId.generate(clock),
        occurredAt = clock.instant(),
        businessDate = receivedOn,
        delivery = facts(),
        currency = currency,
        lines =
            lines.map {
                DirectDeliveryLine(it.lineNumber, it.itemId, it.unitsDelivered, it.unitsReturned, it.unitCost.amount)
            },
    )
}

class NoAgreedCost(
    items: List<ItemId>,
) : BusinessRuleViolation(
        "receiving.no-agreed-cost",
        "This vendor has no agreed cost at this store for: ${items.joinToString()}. Ask the buyer to set one",
    )

class DeliveryItemRepeated(
    ticket: DeliveryTicketNumber,
) : BusinessRuleViolation(
        "receiving.delivery-item-repeated",
        "Ticket $ticket lists the same item more than once",
    )

class DeliveryInMixedCurrencies(
    ticket: DeliveryTicketNumber,
) : BusinessRuleViolation(
        "receiving.delivery-in-mixed-currencies",
        "The agreed costs of the items on ticket $ticket are not all in one currency",
    )

class DeliveryTicketAlreadyReceived(
    ticket: DeliveryTicketNumber,
) : BusinessRuleViolation(
        "receiving.delivery-ticket-already-received",
        "Ticket $ticket from this vendor has already been checked in at this store",
    )

class AgreedCostEffectiveInPast(
    effectiveFrom: BusinessDate,
    today: BusinessDate,
) : BusinessRuleViolation(
        "receiving.agreed-cost-effective-in-past",
        "An agreed cost cannot take effect on $effectiveFrom, before today ($today)",
    )

class UnknownDeliveryParty(
    what: String,
    id: Any,
) : BusinessRuleViolation("receiving.unknown-delivery-party", "$what $id does not exist")
