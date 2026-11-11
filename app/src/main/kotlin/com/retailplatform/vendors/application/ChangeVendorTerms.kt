package com.retailplatform.vendors.application

import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.domain.PaymentTerms
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

data class ChangeVendorTermsCommand(
    val vendorId: VendorId,
    val paymentTerms: PaymentTerms,
)

/** Use case: change a vendor's payment terms. */
@Service
class ChangeVendorTerms(
    private val vendors: VendorRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: ChangeVendorTermsCommand) {
        val vendor =
            vendors.findById(command.vendorId) ?: throw ResourceNotFound("Vendor", command.vendorId.toString())

        vendor.changePaymentTerms(command.paymentTerms, clock)
        vendors.save(vendor)
        vendor.releaseEvents().forEach(events::publishEvent)
    }
}
