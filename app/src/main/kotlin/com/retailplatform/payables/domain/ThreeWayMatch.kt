package com.retailplatform.payables.domain

import com.retailplatform.shared.Money
import com.retailplatform.shared.Percentage
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How far an invoice line's cost may differ from the purchase order's and still match: the case cost by at most
 * [percent] of the purchase order's, and the line's total by at most [amount] in the invoice's currency.
 */
@ConsistentCopyVisibility
data class MatchTolerance private constructor(
    val percent: Percentage,
    val amount: BigDecimal,
) {
    /** Whether billing [cases] at [invoiced] a case is close enough to the purchase order's [ordered] a case. */
    fun allows(
        ordered: Money,
        invoiced: Money,
        cases: Int,
    ): Boolean {
        val difference = (invoiced.amount - ordered.amount).abs()
        return when {
            difference.signum() == 0 -> {
                true
            }

            ordered.isZero -> {
                false
            }

            else -> {
                val percentOff =
                    difference.multiply(HUNDRED).divide(ordered.amount, Percentage.SCALE, RoundingMode.HALF_EVEN)
                percentOff <= percent.value && difference * BigDecimal(cases) <= amount
            }
        }
    }

    companion object {
        private val HUNDRED = BigDecimal(100)

        /** @throws IllegalArgumentException if [amount] is negative. */
        fun of(
            percent: Percentage,
            amount: BigDecimal,
        ): MatchTolerance {
            require(amount.signum() >= 0) { "A tolerance amount cannot be negative: $amount" }
            return MatchTolerance(percent, amount)
        }
    }
}

/** What comparing an invoice with its purchase order and receipts found. A caller must handle both outcomes. */
sealed interface MatchOutcome {
    /** Every line agreed. [accruedAmount] is what the matched cases were accrued at, purchase order cost. */
    data class Matched(
        val accruedAmount: Money,
    ) : MatchOutcome

    /** At least one line did not agree; nothing was relieved. */
    data class Exceptions(
        val exceptions: List<MatchException>,
    ) : MatchOutcome
}

/**
 * The three-way match: an invoice line agrees when the cases it bills have been received and not yet invoiced,
 * and its case cost is within tolerance of the purchase order's. Receipts and purchase order cost come from the
 * accruals recorded when the goods arrived.
 */
class ThreeWayMatch(
    private val tolerance: MatchTolerance,
) {
    /**
     * Compares [invoice] with the open [accruals] of its purchase order, oldest receipt first. If every line
     * agrees, the matched cases are relieved from the accruals, oldest first; otherwise nothing changes.
     *
     * With [acceptPrice] a case cost outside tolerance is accepted, as an approved override does. Cases that were
     * not received, and a cost in another currency than the purchase order's, are never accepted.
     */
    fun match(
        invoice: VendorInvoice,
        accruals: List<Accrual>,
        acceptPrice: Boolean = false,
    ): MatchOutcome {
        val exceptions = invoice.lines.mapNotNull { exceptionFor(it, accruals, acceptPrice) }
        if (exceptions.isNotEmpty()) return MatchOutcome.Exceptions(exceptions)

        val before = accruals.map { it.openAmount }.reduce(Money::plus)
        invoice.lines.forEach { line ->
            accruals.fold(line.cases) { remaining, accrual ->
                remaining - accrual.relieve(line.purchaseOrderLineNumber, remaining)
            }
        }
        return MatchOutcome.Matched(before - accruals.map { it.openAmount }.reduce(Money::plus))
    }

    private fun exceptionFor(
        line: InvoiceLine,
        accruals: List<Accrual>,
        acceptPrice: Boolean,
    ): MatchException? {
        val number = line.purchaseOrderLineNumber
        val received = accruals.flatMap { it.lines }.filter { it.purchaseOrderLineNumber == number }
        val uninvoiced = received.sumOf { it.casesUninvoiced }
        val ordered = received.firstOrNull()?.caseCost
        return when {
            ordered == null || uninvoiced == 0 -> {
                MatchException(number, MatchExceptionReason.NOTHING_RECEIVED, "No uninvoiced receipt for this line")
            }

            line.cases > uninvoiced -> {
                MatchException(
                    number,
                    MatchExceptionReason.QUANTITY_OVER_RECEIPT,
                    "Billed ${line.cases} cases; $uninvoiced received and not invoiced",
                )
            }

            ordered.currency != line.caseCost.currency ||
                (!acceptPrice && !tolerance.allows(ordered, line.caseCost, line.cases)) -> {
                MatchException(
                    number,
                    MatchExceptionReason.PRICE_OVER_TOLERANCE,
                    "Billed ${line.caseCost} a case; purchase order cost is $ordered",
                )
            }

            else -> {
                null
            }
        }
    }
}
