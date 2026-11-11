package com.retailplatform.vendors.application

import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.domain.PaymentTerms
import com.retailplatform.vendors.domain.PostalAddress
import com.retailplatform.vendors.domain.Vendor
import com.retailplatform.vendors.domain.VendorContact
import com.retailplatform.vendors.domain.VendorName
import com.retailplatform.vendors.domain.VendorNumber
import com.retailplatform.vendors.domain.VendorNumberAlreadyInUse
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

data class CreateVendorCommand(
    val number: VendorNumber,
    val name: VendorName,
    val paymentTerms: PaymentTerms,
    val remitTo: PostalAddress,
    val contacts: List<VendorContact>,
)

/** Use case: set up a new vendor. */
@Service
class CreateVendor(
    private val vendors: VendorRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: CreateVendorCommand): VendorId {
        if (vendors.existsByNumber(command.number)) throw VendorNumberAlreadyInUse(command.number)

        val vendor =
            Vendor.create(
                command.number,
                command.name,
                command.paymentTerms,
                command.remitTo,
                command.contacts,
                clock,
            )
        vendors.save(vendor)
        vendor.releaseEvents().forEach(events::publishEvent)
        return vendor.id
    }
}
