package com.retailplatform.purchasing.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.Money

/** From [effectiveFrom], a purchase order totalling more than [amount] needs a second person's approval. */
@ConsistentCopyVisibility
data class ApprovalThreshold private constructor(
    val amount: Money,
    val effectiveFrom: BusinessDate,
) {
    companion object {
        /** @throws IllegalArgumentException if [amount] is negative. */
        fun of(
            amount: Money,
            effectiveFrom: BusinessDate,
        ): ApprovalThreshold {
            require(!amount.isNegative) { "An approval threshold cannot be negative: $amount" }
            return ApprovalThreshold(amount, effectiveFrom)
        }
    }
}

/** The person approving a purchase order, and whether they hold the approver role. */
data class Approver(
    val user: String,
    val mayApproveOverThreshold: Boolean,
)

/**
 * Policy: who may approve a purchase order.
 *
 * An order at or under the threshold in effect may be approved by any buyer, including the buyer who created it.
 * An order over the threshold, in another currency than the threshold, or approved while no threshold is set,
 * needs an approver who did not create it.
 */
class PoApprovalPolicy(
    private val threshold: Money?,
) {
    fun needsSecondApprover(total: Money): Boolean =
        threshold == null || threshold.currency != total.currency || total > threshold

    /**
     * @throws ApprovalNeedsApprover if the order needs a second approver and [approver] lacks the approver role.
     * @throws ApprovalByCreator if the order needs a second approver and [approver] created it.
     */
    fun check(
        order: PurchaseOrder,
        approver: Approver,
    ) {
        if (!needsSecondApprover(order.total)) return
        if (!approver.mayApproveOverThreshold) throw ApprovalNeedsApprover(order.number, order.total)
        if (approver.user == order.buyer) throw ApprovalByCreator(order.number)
    }
}

class ApprovalNeedsApprover(
    number: PurchaseOrderNumber,
    total: Money,
) : BusinessRuleViolation(
        "purchasing.approval-needs-approver",
        "Purchase order $number totals $total, which is over the approval threshold and needs an approver",
    )

class ApprovalByCreator(
    number: PurchaseOrderNumber,
) : BusinessRuleViolation(
        "purchasing.approval-by-creator",
        "Purchase order $number cannot be approved by the buyer who created it",
    )

class ThresholdEffectiveInPast(
    effectiveFrom: BusinessDate,
    today: BusinessDate,
) : BusinessRuleViolation(
        "purchasing.threshold-effective-in-past",
        "An approval threshold cannot take effect on $effectiveFrom, before today ($today)",
    )
