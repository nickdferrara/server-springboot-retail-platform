package com.retailplatform.pricing.application

import com.retailplatform.pricing.domain.StorePriceZone
import com.retailplatform.shared.StoreId

/** Port: where store price zone assignments are kept. */
interface StorePriceZoneRepository {
    /** Inserts the store's assignment or replaces its zone. */
    fun save(assignment: StorePriceZone)

    fun findByStore(storeId: StoreId): StorePriceZone?
}
