package com.retailplatform.payables.domain

import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.Money

/**
 * Units a short-pay held back can come back to the vendor two ways: the store corrects its receipt, which leaves
 * an adjustment for the units received after all, or accounting concedes a dispute. This works out, item by item,
 * what of a deduction the adjustments on its ticket already cover, so the same units are not paid for twice.
 */
object DeductionRecovery {
    /**
     * What of the deduction in [lines] can still be conceded, given the [adjustments] on the ticket. For each
     * item held back, units that an adjustment for units received after the invoice covers, unless it was written
     * off, no longer count; the rest do, at the unit cost billed. Other items on the ticket change nothing.
     */
    fun concedable(
        lines: List<DeductionLine>,
        adjustments: List<DeliveryAdjustment>,
    ): Money {
        val covered =
            adjustments
                .filter { it.kind == DeliveryAdjustmentKind.RECEIVED_AFTER_INVOICE && it.isLive }
                .flatMap { it.lines }
                .groupBy({ it.itemId }, { it.units })
                .mapValues { (_, units) -> units.sum() }
        return lines
            .map { it.unitCost * (it.unitsDeducted - minOf(it.unitsDeducted, covered[it.itemId] ?: 0)) }
            .reduce(Money::plus)
    }
}

class DeductionAlreadyRecovered(
    number: DeductionNoticeNumber,
    concedable: Money,
) : BusinessRuleViolation(
        "payables.deduction-already-recovered",
        "No more than $concedable of the deduction on notice $number can be conceded: a correction to the " +
            "store's receipt already covers the rest",
    )

class AdjustmentCoveredByConcession(
    ticketNumber: String,
    number: DeductionNoticeNumber,
) : BusinessRuleViolation(
        "payables.adjustment-covered-by-concession",
        "The adjustment on ticket $ticketNumber cannot be paid: the deduction on notice $number was already " +
            "conceded for these units; write the adjustment off instead",
    )
