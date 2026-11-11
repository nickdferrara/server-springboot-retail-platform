package com.retailplatform.pricing.application

import com.retailplatform.pricing.domain.StorePriceZone
import com.retailplatform.shared.StoreId

/** Port: where store price zone assignments are kept. */
interface StorePriceZoneRepository {
    fun add(assignment: StorePriceZone)

    fun findByStore(storeId: StoreId): StorePriceZone?
}
