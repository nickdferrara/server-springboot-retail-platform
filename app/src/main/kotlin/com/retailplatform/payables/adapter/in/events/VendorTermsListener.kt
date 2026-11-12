package com.retailplatform.payables.adapter.`in`.events

import com.retailplatform.payables.application.RecordVendorTerms
import com.retailplatform.payables.domain.EarlyPaymentDiscount
import com.retailplatform.payables.domain.VendorTerms
import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.shared.Percentage
import com.retailplatform.vendors.api.events.PaymentTermsFacts
import com.retailplatform.vendors.api.events.VendorCreated
import com.retailplatform.vendors.api.events.VendorTermsChanged
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * Keeps payables' own copy of each vendor's payment terms, so a voucher falls due and is discounted as agreed
 * with its vendor.
 */
@Component
class VendorTermsListener(
    private val recordVendorTerms: RecordVendorTerms,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: VendorCreated) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            recordVendorTerms.handle(event.vendorId, event.businessDate, event.paymentTerms.toTerms())
        }
    }

    @ApplicationModuleListener
    fun on(event: VendorTermsChanged) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            recordVendorTerms.handle(event.vendorId, event.businessDate, event.paymentTerms.toTerms())
        }
    }

    private fun PaymentTermsFacts.toTerms(): VendorTerms {
        val percent = discountPercent
        val days = discountDays
        return VendorTerms.of(
            netDays = netDays,
            discount =
                if (percent != null &&
                    days != null
                ) {
                    EarlyPaymentDiscount(Percentage.of(percent), days)
                } else {
                    null
                },
        )
    }

    private companion object {
        const val MODULE = "payables"
        const val LISTENER = "VendorTermsListener"
    }
}
