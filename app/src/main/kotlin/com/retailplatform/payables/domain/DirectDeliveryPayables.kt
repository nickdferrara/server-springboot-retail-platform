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
    relievedOn: BusinessDate?,
    val version: Int,
) {
    /** The day the vendor's invoice matched and the amount moved to accounts payable; `null` while it waits. */
    var relievedOn: BusinessDate? = relievedOn
        private set

    val amount: Money get() = lines.map { it.amount }.reduce(Money::plus)

    /** Whether the accrual still waits for the vendor's invoice. */
    val isOpen: Boolean get() = relievedOn == null

    /** Relieves the whole accrual: a ticket is invoiced once, for exactly what was received. */
    fun relieve(on: BusinessDate) {
        check(isOpen) { "The accrual for ticket ${ticket.ticketNumber} was already relieved" }
        relievedOn = on
    }

    companion object {
        /** Accrues the delivered [lines] of [ticket]. */
        fun record(
            ticket: DeliveredTicket,
            lines: List<TicketAmountLine>,
        ): DirectDeliveryAccrual {
            require(lines.isNotEmpty()) { "An accrual has at least one line" }
            return DirectDeliveryAccrual(ticket, lines, relievedOn = null, version = 0)
        }

        /** Rebuilds an accrual from stored state. */
        fun rehydrate(
            ticket: DeliveredTicket,
            lines: List<TicketAmountLine>,
            relievedOn: BusinessDate?,
            version: Int,
        ): DirectDeliveryAccrual = DirectDeliveryAccrual(ticket, lines, relievedOn, version)
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
    val lines: List<TicketAmountLine>,
    status: VendorCreditStatus,
    val version: Int,
) {
    var status: VendorCreditStatus = status
        private set

    val amount: Money get() = lines.map { it.amount }.reduce(Money::plus)

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
            return VendorCredit(VendorCreditId.generate(clock), ticket, lines, VendorCreditStatus.Open, version = 0)
        }

        /** Rebuilds a credit from stored state. */
        fun rehydrate(
            id: VendorCreditId,
            ticket: DeliveredTicket,
            lines: List<TicketAmountLine>,
            status: VendorCreditStatus,
            version: Int,
        ): VendorCredit = VendorCredit(id, ticket, lines, status, version)
    }
}
