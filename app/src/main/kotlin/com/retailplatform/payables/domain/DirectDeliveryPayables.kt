package com.retailplatform.payables.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PaymentId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.TypedId
import com.retailplatform.shared.TypedIdFactory
import com.retailplatform.shared.VendorId
import java.time.Clock
import java.util.Currency
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

/** How a correction changed the units of one item on a ticket: positive for more, negative for fewer. */
data class TicketUnitChange(
    val itemId: ItemId,
    val unitsChange: Int,
    val unitCost: Money,
) {
    /** The value of the change at the ticket's cost; negative for fewer units. */
    val amount: Money get() = unitCost * unitsChange
}

/** These lines once [changes] are applied; an item left with no units drops out. */
private fun List<TicketAmountLine>.changedBy(changes: List<TicketUnitChange>): List<TicketAmountLine> {
    val units = associate { it.itemId to it.units }.toMutableMap()
    val costs = associate { it.itemId to it.unitCost }.toMutableMap()
    changes.forEach { change ->
        val after = (units[change.itemId] ?: 0) + change.unitsChange
        check(after >= 0) { "Item ${change.itemId} cannot be left with $after units" }
        units[change.itemId] = after
        costs.putIfAbsent(change.itemId, change.unitCost)
    }
    return units.filterValues { it > 0 }.map { (itemId, left) ->
        TicketAmountLine.of(itemId, left, costs.getValue(itemId))
    }
}

/**
 * What is owed to a vendor for the units it delivered straight to a store on one ticket, at agreed cost, until
 * the vendor's invoice for that ticket is matched. Every delivery has one, with no lines if nothing was
 * delivered.
 */
class DirectDeliveryAccrual private constructor(
    val ticket: DeliveredTicket,
    val currency: Currency,
    lines: List<TicketAmountLine>,
    relievedOn: BusinessDate?,
    val version: Int,
) {
    var lines: List<TicketAmountLine> = lines
        private set

    /** The day the vendor's invoice matched and the amount moved to accounts payable; `null` while it waits. */
    var relievedOn: BusinessDate? = relievedOn
        private set

    val amount: Money get() = lines.map { it.amount }.fold(Money.zero(currency), Money::plus)

    /** Whether the accrual still waits for the vendor's invoice. */
    val isOpen: Boolean get() = relievedOn == null

    /** Relieves the whole accrual: a ticket is invoiced once, for exactly what was received. */
    fun relieve(on: BusinessDate) {
        check(isOpen) { "The accrual for ticket ${ticket.ticketNumber} was already relieved" }
        relievedOn = on
    }

    /** Brings an accrual that still waits for its invoice in line with a correction to the store's receipt. */
    fun correct(changes: List<TicketUnitChange>) {
        check(isOpen) { "The accrual for ticket ${ticket.ticketNumber} was already relieved" }
        lines = lines.changedBy(changes)
    }

    companion object {
        /** Accrues the delivered [lines] of [ticket]; none if the ticket only took product back. */
        fun record(
            ticket: DeliveredTicket,
            currency: Currency,
            lines: List<TicketAmountLine>,
        ): DirectDeliveryAccrual = DirectDeliveryAccrual(ticket, currency, lines, relievedOn = null, version = 0)

        /** Rebuilds an accrual from stored state. */
        fun rehydrate(
            ticket: DeliveredTicket,
            currency: Currency,
            lines: List<TicketAmountLine>,
            relievedOn: BusinessDate?,
            version: Int,
        ): DirectDeliveryAccrual = DirectDeliveryAccrual(ticket, currency, lines, relievedOn, version)
    }
}

/** Whether a vendor credit has been used. */
sealed interface VendorCreditStatus {
    /** A short name for the status, stored and shown to users. */
    val code: String

    /** Waiting to be deducted from a payment to the vendor. */
    data object Open : VendorCreditStatus {
        override val code = "OPEN"
    }

    /** Selected by a payment run that has not been approved yet. */
    data class Scheduled(
        val paymentRunId: PaymentRunId,
    ) : VendorCreditStatus {
        override val code = "SCHEDULED"
    }

    /** Deducted on [appliedOn] from the payment [paymentId] that the run [paymentRunId] issued. */
    data class Applied(
        val paymentRunId: PaymentRunId,
        val paymentId: PaymentId,
        val appliedOn: BusinessDate,
    ) : VendorCreditStatus {
        override val code = "APPLIED"
    }
}

/**
 * What a vendor owes for product its driver took back from a store on one ticket, at agreed cost. It stands on
 * its own until a payment run deducts it, whole, from a payment to the vendor.
 */
class VendorCredit private constructor(
    val id: VendorCreditId,
    val ticket: DeliveredTicket,
    val currency: Currency,
    lines: List<TicketAmountLine>,
    status: VendorCreditStatus,
    val version: Int,
) {
    var lines: List<TicketAmountLine> = lines
        private set
    var status: VendorCreditStatus = status
        private set

    val amount: Money get() = lines.map { it.amount }.fold(Money.zero(currency), Money::plus)

    /** Whether the credit is open and holds at least the units [changes] would take away from it. */
    fun canAbsorb(changes: List<TicketUnitChange>): Boolean {
        val units = lines.associate { it.itemId to it.units }
        return isOpen && changes.all { (units[it.itemId] ?: 0) + it.unitsChange >= 0 }
    }

    /** Brings a credit that has not been used in line with a correction to the store's receipt. */
    fun correct(changes: List<TicketUnitChange>) {
        check(canAbsorb(changes)) { "Credit $id cannot take this correction" }
        lines = lines.changedBy(changes)
    }

    val isOpen: Boolean get() = status == VendorCreditStatus.Open

    /** Reserves the credit for a payment run, so no other run deducts it. */
    fun schedule(paymentRunId: PaymentRunId) {
        check(isOpen) { "Credit $id is already in a payment run or applied" }
        status = VendorCreditStatus.Scheduled(paymentRunId)
    }

    /** Gives the credit back when the payment run [paymentRunId] that reserved it is cancelled. */
    fun unschedule(paymentRunId: PaymentRunId) {
        check(status == VendorCreditStatus.Scheduled(paymentRunId)) { "Credit $id is not in payment run $paymentRunId" }
        status = VendorCreditStatus.Open
    }

    /** Deducts the credit from the payment [paymentId] made on [appliedOn] by the run that reserved it. */
    fun apply(
        paymentRunId: PaymentRunId,
        paymentId: PaymentId,
        appliedOn: BusinessDate,
    ) {
        check(status == VendorCreditStatus.Scheduled(paymentRunId)) { "Credit $id is not in payment run $paymentRunId" }
        status = VendorCreditStatus.Applied(paymentRunId, paymentId, appliedOn)
    }

    companion object {
        /** Records a credit for the returned [lines] of [ticket]. */
        fun record(
            ticket: DeliveredTicket,
            lines: List<TicketAmountLine>,
            clock: Clock,
        ): VendorCredit {
            require(lines.isNotEmpty()) { "A credit has at least one line" }
            val currency = lines.first().unitCost.currency
            return VendorCredit(VendorCreditId.generate(clock), ticket, currency, lines, VendorCreditStatus.Open, 0)
        }

        /** Rebuilds a credit from stored state. */
        fun rehydrate(
            id: VendorCreditId,
            ticket: DeliveredTicket,
            currency: Currency,
            lines: List<TicketAmountLine>,
            status: VendorCreditStatus,
            version: Int,
        ): VendorCredit = VendorCredit(id, ticket, currency, lines, status, version)
    }
}

/** Identifies an adjustment that accounting has to look at. */
@JvmInline
value class DeliveryAdjustmentId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<DeliveryAdjustmentId>(::DeliveryAdjustmentId)
}

/** Why a correction left an amount that nothing settles by itself. */
enum class DeliveryAdjustmentKind {
    /** More was received than first recorded, after the vendor's invoice for the ticket had already matched. */
    RECEIVED_AFTER_INVOICE,

    /** Fewer units were returned than first recorded, after the credit for them had been used. */
    RETURN_REVERSED_AFTER_CREDIT_USED,
}

/**
 * An amount owed to a vendor that a late correction to a store's receipt brought to light. It is accrued, but no
 * invoice will match it and no payment run will pay it: accounting settles it with the vendor.
 */
data class DeliveryAdjustment(
    val id: DeliveryAdjustmentId,
    val ticket: DeliveredTicket,
    val kind: DeliveryAdjustmentKind,
    val amount: Money,
)
