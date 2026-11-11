package com.retailplatform.vendors.domain

import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.api.events.PaymentTermsFacts
import com.retailplatform.vendors.api.events.VendorCreated
import com.retailplatform.vendors.api.events.VendorTermsChanged
import java.time.Clock

/** A company the business buys from. */
class Vendor private constructor(
    val id: VendorId,
    val number: VendorNumber,
    name: VendorName,
    paymentTerms: PaymentTerms,
    remitTo: PostalAddress,
    contacts: List<VendorContact>,
    val version: Int,
) : AggregateRoot() {
    var name: VendorName = name
        private set
    var paymentTerms: PaymentTerms = paymentTerms
        private set
    var remitTo: PostalAddress = remitTo
        private set
    var contacts: List<VendorContact> = contacts.toList()
        private set

    /** Changes the payment terms, raising [VendorTermsChanged] unless they are already [newTerms]. */
    fun changePaymentTerms(
        newTerms: PaymentTerms,
        clock: Clock,
    ) {
        if (newTerms == paymentTerms) return
        paymentTerms = newTerms
        raise(
            VendorTermsChanged(
                eventId = EventId.generate(clock),
                occurredAt = clock.instant(),
                businessDate = BusinessDate.today(clock),
                vendorId = id,
                paymentTerms = newTerms.toFacts(),
            ),
        )
    }

    /** Replaces the name, remit-to address, and contacts. */
    fun updateDetails(
        name: VendorName,
        remitTo: PostalAddress,
        contacts: List<VendorContact>,
    ) {
        this.name = name
        this.remitTo = remitTo
        this.contacts = contacts.toList()
    }

    companion object {
        /** Sets up a new vendor, raising [VendorCreated]. */
        fun create(
            number: VendorNumber,
            name: VendorName,
            paymentTerms: PaymentTerms,
            remitTo: PostalAddress,
            contacts: List<VendorContact>,
            clock: Clock,
        ): Vendor {
            val vendor = Vendor(VendorId.generate(clock), number, name, paymentTerms, remitTo, contacts, version = 0)
            vendor.raise(
                VendorCreated(
                    eventId = EventId.generate(clock),
                    occurredAt = clock.instant(),
                    businessDate = BusinessDate.today(clock),
                    vendorId = vendor.id,
                    vendorNumber = number.value,
                    vendorName = name.value,
                    paymentTerms = paymentTerms.toFacts(),
                ),
            )
            return vendor
        }

        /** Rebuilds a vendor from stored state. Raises no events. */
        @Suppress("LongParameterList")
        fun rehydrate(
            id: VendorId,
            number: VendorNumber,
            name: VendorName,
            paymentTerms: PaymentTerms,
            remitTo: PostalAddress,
            contacts: List<VendorContact>,
            version: Int,
        ): Vendor = Vendor(id, number, name, paymentTerms, remitTo, contacts, version)

        private fun PaymentTerms.toFacts() = PaymentTermsFacts(netDays, discount?.percent?.value, discount?.days)
    }
}
