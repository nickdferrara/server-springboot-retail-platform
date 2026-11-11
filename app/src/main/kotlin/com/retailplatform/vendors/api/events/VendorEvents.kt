package com.retailplatform.vendors.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.VendorId
import java.math.BigDecimal
import java.time.Instant

/** Payment terms as facts: pay within [netDays]; [discountPercent] off if paid within [discountDays]. */
data class PaymentTermsFacts(
    val netDays: Int,
    val discountPercent: BigDecimal?,
    val discountDays: Int?,
)

/** A vendor was set up. */
data class VendorCreated(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val vendorId: VendorId,
    val vendorNumber: String,
    val vendorName: String,
    val paymentTerms: PaymentTermsFacts,
    val schemaVersion: Int = 1,
)

/** A vendor's payment terms changed. Invoices dated on or after [businessDate] use [paymentTerms]. */
data class VendorTermsChanged(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val vendorId: VendorId,
    val paymentTerms: PaymentTermsFacts,
    val schemaVersion: Int = 1,
)
