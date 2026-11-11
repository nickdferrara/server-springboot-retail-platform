package com.retailplatform.vendors.application

import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.api.VendorDirectory
import com.retailplatform.vendors.domain.Vendor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Queries: vendors by identifier and by page; also answers other modules through [VendorDirectory]. */
@Service
@Transactional(readOnly = true)
class VendorQueries(
    private val vendors: VendorRepository,
) : VendorDirectory {
    fun find(id: VendorId): Vendor = vendors.findById(id) ?: throw ResourceNotFound("Vendor", id.toString())

    fun list(query: PageQuery): Page<Vendor> = vendors.findPage(query)

    override fun exists(vendorId: VendorId): Boolean = vendors.existsById(vendorId)
}
