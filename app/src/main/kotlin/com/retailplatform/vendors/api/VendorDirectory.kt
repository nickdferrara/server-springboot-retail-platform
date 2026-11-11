package com.retailplatform.vendors.api

import com.retailplatform.shared.VendorId

/** Lets other modules check a vendor reference before they store it. */
interface VendorDirectory {
    fun exists(vendorId: VendorId): Boolean
}
