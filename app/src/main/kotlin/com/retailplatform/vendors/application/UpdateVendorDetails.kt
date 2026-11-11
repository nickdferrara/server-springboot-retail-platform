package com.retailplatform.vendors.application

import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.domain.PostalAddress
import com.retailplatform.vendors.domain.VendorContact
import com.retailplatform.vendors.domain.VendorName
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class UpdateVendorDetailsCommand(
    val vendorId: VendorId,
    val name: VendorName,
    val remitTo: PostalAddress,
    val contacts: List<VendorContact>,
)

/** Use case: replace a vendor's name, remit-to address, and contacts. */
@Service
class UpdateVendorDetails(
    private val vendors: VendorRepository,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: UpdateVendorDetailsCommand) {
        val vendor =
            vendors.findById(command.vendorId) ?: throw ResourceNotFound("Vendor", command.vendorId.toString())

        vendor.updateDetails(command.name, command.remitTo, command.contacts)
        vendors.save(vendor)
    }
}
