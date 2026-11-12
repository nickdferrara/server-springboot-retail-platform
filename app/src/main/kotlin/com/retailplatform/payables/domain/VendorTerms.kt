package com.retailplatform.payables.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Percentage

/** [percent] off an invoice paid within [days] days of its date. */
data class EarlyPaymentDiscount(
    val percent: Percentage,
    val days: Int,
)

/**
 * A vendor's payment terms as payables keeps them: an invoice falls due [netDays] after its date, and [discount]
 * may be taken for paying early.
 */
@ConsistentCopyVisibility
data class VendorTerms private constructor(
    val netDays: Int,
    val discount: EarlyPaymentDiscount?,
) {
    /** The day an invoice dated [invoiceDate] falls due. */
    fun dueOn(invoiceDate: BusinessDate): BusinessDate = invoiceDate.plusDays(netDays.toLong())

    /** The discount on an invoice dated [invoiceDate] and the last day it can be taken, if the terms give one. */
    fun discountFor(invoiceDate: BusinessDate): VoucherDiscount? =
        discount?.let { VoucherDiscount(it.percent, invoiceDate.plusDays(it.days.toLong())) }

    companion object {
        /** @throws IllegalArgumentException if a number of days is negative or the discount is 0%. */
        fun of(
            netDays: Int,
            discount: EarlyPaymentDiscount? = null,
        ): VendorTerms {
            require(netDays >= 0) { "Net days cannot be negative: $netDays" }
            if (discount != null) {
                require(discount.days >= 0) { "Discount days cannot be negative: ${discount.days}" }
                require(!discount.percent.isZero) { "An early payment discount must be more than 0%" }
            }
            return VendorTerms(netDays, discount)
        }
    }
}
