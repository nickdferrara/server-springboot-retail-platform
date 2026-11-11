package com.retailplatform.purchasing.domain

import com.retailplatform.purchasing.api.events.PurchaseOrderApproved
import com.retailplatform.purchasing.api.events.PurchaseOrderCancelled
import com.retailplatform.purchasing.api.events.PurchaseOrderFacts
import com.retailplatform.purchasing.api.events.PurchaseOrderLineFacts
import com.retailplatform.purchasing.api.events.PurchaseOrderSent
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import java.time.Clock

/** Who approved a purchase order, and on which day. */
data class Approval(
    val approvedBy: String,
    val approvedOn: BusinessDate,
)

/** Where a purchase order is in its life. Each state carries the facts recorded on the way to it. */
sealed interface PurchaseOrderState {
    /** A short name for the state, stored and shown to users. */
    val code: String

    /** The approval, once the order has had one. */
    val approval: Approval?

    /** The day the order went to the vendor, once it has. */
    val sentOn: BusinessDate?

    /** Being written by the buyer; lines can still change. */
    data object Draft : PurchaseOrderState {
        override val code = "DRAFT"
        override val approval: Approval? = null
        override val sentOn: BusinessDate? = null
    }

    data class Approved(
        override val approval: Approval,
    ) : PurchaseOrderState {
        override val code = "APPROVED"
        override val sentOn: BusinessDate? = null
    }

    data class Sent(
        override val approval: Approval,
        override val sentOn: BusinessDate,
    ) : PurchaseOrderState {
        override val code = "SENT"
    }

    /** Cancelled on [cancelledOn]; [previous] is the state the order was cancelled from. */
    data class Cancelled(
        val cancelledOn: BusinessDate,
        val reason: String,
        val previous: PurchaseOrderState,
    ) : PurchaseOrderState {
        override val code = "CANCELLED"
        override val approval: Approval? get() = previous.approval
        override val sentOn: BusinessDate? get() = previous.sentOn
    }
}

/** What a buyer decides about an order while it is a draft: where and when it is delivered, and what it orders. */
data class PurchaseOrderTerms(
    val warehouseId: WarehouseId,
    val expectedOn: BusinessDate,
    val lines: List<PurchaseOrderLine>,
)

/** An order to one vendor for delivery to one warehouse. */
@Suppress("LongParameterList")
class PurchaseOrder private constructor(
    val id: PurchaseOrderId,
    val number: PurchaseOrderNumber,
    val vendorId: VendorId,
    val buyer: String,
    terms: PurchaseOrderTerms,
    state: PurchaseOrderState,
    val version: Int,
) : AggregateRoot() {
    var terms: PurchaseOrderTerms = terms
        private set
    var state: PurchaseOrderState = state
        private set

    val lines: List<PurchaseOrderLine> get() = terms.lines

    /** The sum of the lines. */
    val total: Money get() = lines.map { it.extendedCost }.reduce(Money::plus)

    /**
     * Replaces the delivery details and lines of a draft.
     *
     * @throws PurchaseOrderStateForbids unless the order is a draft.
     */
    fun revise(
        newTerms: PurchaseOrderTerms,
        clock: Clock,
    ) {
        if (state != PurchaseOrderState.Draft) throw PurchaseOrderStateForbids(number, "revised", state)
        checkTerms(newTerms, BusinessDate.today(clock))
        terms = newTerms
    }

    /**
     * Approves a draft, raising [PurchaseOrderApproved].
     *
     * @throws PurchaseOrderStateForbids unless the order is a draft.
     * @throws ApprovalNeedsApprover or [ApprovalByCreator] if [policy] does not let [approver] approve it.
     */
    fun approve(
        approver: Approver,
        policy: PoApprovalPolicy,
        clock: Clock,
    ) {
        if (state != PurchaseOrderState.Draft) throw PurchaseOrderStateForbids(number, "approved", state)
        policy.check(this, approver)
        val today = BusinessDate.today(clock)
        state = PurchaseOrderState.Approved(Approval(approver.user, today))
        raise(PurchaseOrderApproved(EventId.generate(clock), clock.instant(), today, facts(), approver.user))
    }

    /**
     * Records that the order went to the vendor, raising [PurchaseOrderSent].
     *
     * @throws PurchaseOrderStateForbids unless the order is approved and not yet sent.
     */
    fun send(clock: Clock) {
        val approved =
            state as? PurchaseOrderState.Approved ?: throw PurchaseOrderStateForbids(number, "sent", state)
        val today = BusinessDate.today(clock)
        state = PurchaseOrderState.Sent(approved.approval, today)
        raise(PurchaseOrderSent(EventId.generate(clock), clock.instant(), today, facts()))
    }

    /**
     * Cancels the order. Raises [PurchaseOrderCancelled] if it had been approved, because only then have other
     * modules heard of it.
     *
     * @throws PurchaseOrderStateForbids if the order is already cancelled.
     */
    fun cancel(
        reason: CancellationReason,
        clock: Clock,
    ) {
        val previous = state
        if (previous is PurchaseOrderState.Cancelled) throw PurchaseOrderStateForbids(number, "cancelled", state)
        val today = BusinessDate.today(clock)
        state = PurchaseOrderState.Cancelled(today, reason.value, previous)
        if (previous != PurchaseOrderState.Draft) {
            raise(
                PurchaseOrderCancelled(EventId.generate(clock), clock.instant(), today, id, number.value, reason.value),
            )
        }
    }

    private fun facts() =
        PurchaseOrderFacts(
            purchaseOrderId = id,
            purchaseOrderNumber = number.value,
            vendorId = vendorId,
            warehouseId = terms.warehouseId,
            expectedOn = terms.expectedOn,
            currency = total.currency.currencyCode,
            total = total.amount,
            lines =
                lines.mapIndexed { index, line ->
                    PurchaseOrderLineFacts(
                        lineNumber = index + 1,
                        itemId = line.itemId,
                        vendorSku = line.vendorSku,
                        casePack = line.casePack,
                        cases = line.cases,
                        caseCost = line.caseCost.amount,
                    )
                },
        )

    companion object {
        /**
         * Starts a draft order.
         *
         * @throws ExpectedDeliveryInPast if the delivery is expected before today.
         * @throws ItemRepeatedOnOrder if two lines order the same item.
         * @throws MixedCurrenciesOnOrder if the lines are not all costed in one currency.
         */
        fun draft(
            number: PurchaseOrderNumber,
            vendorId: VendorId,
            buyer: String,
            terms: PurchaseOrderTerms,
            clock: Clock,
        ): PurchaseOrder {
            checkTerms(terms, BusinessDate.today(clock))
            return PurchaseOrder(
                PurchaseOrderId.generate(clock),
                number,
                vendorId,
                buyer,
                terms,
                PurchaseOrderState.Draft,
                version = 0,
            )
        }

        /** Rebuilds an order from stored state. Raises no events. */
        fun rehydrate(
            id: PurchaseOrderId,
            number: PurchaseOrderNumber,
            vendorId: VendorId,
            buyer: String,
            terms: PurchaseOrderTerms,
            state: PurchaseOrderState,
            version: Int,
        ): PurchaseOrder = PurchaseOrder(id, number, vendorId, buyer, terms, state, version)

        private fun checkTerms(
            terms: PurchaseOrderTerms,
            today: BusinessDate,
        ) {
            require(terms.lines.isNotEmpty()) { "A purchase order has at least one line" }
            val repeated =
                terms.lines
                    .groupBy { it.itemId }
                    .filterValues { it.size > 1 }
                    .keys
            val violation =
                when {
                    terms.expectedOn < today -> ExpectedDeliveryInPast(terms.expectedOn, today)

                    repeated.isNotEmpty() -> ItemRepeatedOnOrder(repeated)

                    terms.lines
                        .map { it.caseCost.currency }
                        .toSet()
                        .size > 1 -> MixedCurrenciesOnOrder()

                    else -> null
                }
            if (violation != null) throw violation
        }
    }
}

/** Why an order was cancelled: not blank, at most 200 characters. */
@JvmInline
value class CancellationReason private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 200

        /** @throws IllegalArgumentException if [value] is blank or longer than [MAX_LENGTH] once trimmed. */
        fun of(value: String): CancellationReason {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "A cancellation reason cannot be blank" }
            require(trimmed.length <= MAX_LENGTH) { "A cancellation reason has at most $MAX_LENGTH characters" }
            return CancellationReason(trimmed)
        }
    }
}

class PurchaseOrderStateForbids(
    number: PurchaseOrderNumber,
    action: String,
    state: PurchaseOrderState,
) : BusinessRuleViolation(
        "purchasing.order-state-forbids-action",
        "Purchase order $number cannot be $action while it is ${state.code.lowercase()}",
    )

class ExpectedDeliveryInPast(
    expectedOn: BusinessDate,
    today: BusinessDate,
) : BusinessRuleViolation(
        "purchasing.expected-delivery-in-past",
        "A delivery cannot be expected on $expectedOn, before today ($today)",
    )

class ItemRepeatedOnOrder(
    itemIds: Collection<ItemId>,
) : BusinessRuleViolation(
        "purchasing.item-repeated-on-order",
        "Each item can be on one line of an order: ${itemIds.joinToString()}",
    )

class MixedCurrenciesOnOrder :
    BusinessRuleViolation(
        "purchasing.mixed-currencies-on-order",
        "Every line of a purchase order must be costed in the same currency",
    )

class UnknownVendor(
    vendorId: VendorId,
) : BusinessRuleViolation("purchasing.unknown-vendor", "Vendor $vendorId does not exist")

class UnknownWarehouse(
    warehouseId: WarehouseId,
) : BusinessRuleViolation("purchasing.unknown-warehouse", "Warehouse $warehouseId does not exist")
