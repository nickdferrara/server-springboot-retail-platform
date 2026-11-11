package com.retailplatform.vendors.application

import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.domain.Vendor
import com.retailplatform.vendors.domain.VendorNumber

/** Port: where vendors are kept. */
interface VendorRepository {
    /** Inserts a new vendor or updates an existing one; a stale version is refused. */
    fun save(vendor: Vendor)

    fun findById(id: VendorId): Vendor?

    fun existsById(id: VendorId): Boolean

    fun existsByNumber(number: VendorNumber): Boolean

    /** Vendors ordered by number. */
    fun findPage(query: PageQuery): Page<Vendor>
}
