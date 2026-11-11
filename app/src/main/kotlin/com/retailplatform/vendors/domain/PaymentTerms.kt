package com.retailplatform.vendors.domain

import com.retailplatform.shared.Percentage

/** A discount for paying early: [percent] off when paid within [days] of the invoice date. */
data class EarlyPaymentDiscount(
    val percent: Percentage,
    val days: Int,
)

/**
 * When a vendor's invoices fall due, for example "2% 10, net 30": pay within 30 days, or within 10 days and take
 * 2% off.
 */
@ConsistentCopyVisibility
data class PaymentTerms private constructor(
    val netDays: Int,
    val discount: EarlyPaymentDiscount?,
) {
    override fun toString(): String = discount?.let { "${it.percent} ${it.days}, net $netDays" } ?: "net $netDays"

    companion object {
        const val MAX_NET_DAYS = 365

        /** @throws IllegalArgumentException unless [netDays] is between 0 and [MAX_NET_DAYS]. */
        fun net(netDays: Int): PaymentTerms {
            require(netDays in 0..MAX_NET_DAYS) { "Net days must be between 0 and $MAX_NET_DAYS: $netDays" }
            return PaymentTerms(netDays, null)
        }

        /**
         * @throws IllegalArgumentException unless the discount is above zero and its window ends before the
         * invoice falls due.
         */
        fun withDiscount(
            percent: Percentage,
            discountDays: Int,
            netDays: Int,
        ): PaymentTerms {
            require(netDays in 0..MAX_NET_DAYS) { "Net days must be between 0 and $MAX_NET_DAYS: $netDays" }
            require(!percent.isZero) { "An early payment discount must be more than 0%" }
            require(discountDays in 0..<netDays) {
                "The discount window ($discountDays days) must end before the invoice is due ($netDays days)"
            }
            return PaymentTerms(netDays, EarlyPaymentDiscount(percent, discountDays))
        }
    }
}
