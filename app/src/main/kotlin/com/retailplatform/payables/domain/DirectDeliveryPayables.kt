package com.retailplatform.payables.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.TypedId
import com.retailplatform.shared.TypedIdFactory
import com.retailplatform.shared.VendorId
import java.time.Clock
import java.util.UUID

/** Identifies a vendor credit. */
@JvmInline
value class VendorCreditId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<VendorCreditId>(::VendorCreditId)
}

/** The direct store delivery an accrual or credit comes from, as the store checked it in. */
data class DeliveredTicket(
    val deliveryId: DirectDeliveryId,
    val receiptNumber: String,
    val vendorId: VendorId,
    val storeId: StoreId,
    val ticketNumber: String,
    val receivedOn: BusinessDate,
)

/** [units] of an item on a delivery ticket at the [unitCost] agreed with the vendor for the store. */
@ConsistentCopyVisibility
data class TicketAmountLine private constructor(
    val itemId: ItemId,
    val units: Int,
    val unitCost: Money,
) {
    val amount: Money get() = unitCost * units

    companion object {
        /** @throws IllegalArgumentException unless [units] is above zero and [unitCost] is not negative. */
        fun of(
            itemId: ItemId,
            units: Int,
            unitCost: Money,
        ): TicketAmountLine {
            require(units >= 1) { "A line is for at least one unit: $units" }
            require(!unitCost.isNegative) { "A unit cost cannot be negative: $unitCost" }
            return TicketAmountLine(itemId, units, unitCost)
        }
    }
}

/**
 * What is owed to a vendor for the units it delivered straight to a store on one ticket, at agreed cost, until
 * the vendor's invoice for that ticket is matched.
 */
class DirectDeliveryAccrual private constructor(
    val ticket: DeliveredTicket,
    val lines: List<TicketAmountLine>,
    val version: Int,
) {
    val amount: Money get() = lines.map { it.amount }.reduce(Money::plus)

    companion object {
        /** Accrues the delivered [lines] of [ticket]. */
        fun record(
            ticket: DeliveredTicket,
            lines: List<TicketAmountLine>,
        ): DirectDeliveryAccrual {
            require(lines.isNotEmpty()) { "An accrual has at least one line" }
            return DirectDeliveryAccrual(ticket, lines, version = 0)
        }

        /** Rebuilds an accrual from stored state. */
        fun rehydrate(
            ticket: DeliveredTicket,
            lines: List<TicketAmountLine>,
            version: Int,
        ): DirectDeliveryAccrual = DirectDeliveryAccrual(ticket, lines, version)
    }
}

/**
 * What a vendor owes for product its driver took back from a store on one ticket, at agreed cost. It stands on
 * its own until it is deducted from a payment to the vendor.
 */
class VendorCredit private constructor(
    val id: VendorCreditId,
    val ticket: DeliveredTicket,
    val lines: List<TicketAmountLine>,
    val version: Int,
) {
    val amount: Money get() = lines.map { it.amount }.reduce(Money::plus)

    companion object {
        /** Records a credit for the returned [lines] of [ticket]. */
        fun record(
            ticket: DeliveredTicket,
            lines: List<TicketAmountLine>,
            clock: Clock,
        ): VendorCredit {
            require(lines.isNotEmpty()) { "A credit has at least one line" }
            return VendorCredit(VendorCreditId.generate(clock), ticket, lines, version = 0)
        }

        /** Rebuilds a credit from stored state. */
        fun rehydrate(
            id: VendorCreditId,
            ticket: DeliveredTicket,
            lines: List<TicketAmountLine>,
            version: Int,
        ): VendorCredit = VendorCredit(id, ticket, lines, version)
    }
}
