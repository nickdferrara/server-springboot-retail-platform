package com.retailplatform.pricing.domain

import com.retailplatform.shared.StoreId

/** The price zone a store takes its retail prices from. */
data class StorePriceZone(
    val storeId: StoreId,
    val zone: PriceZoneCode,
) {
    companion object {
        /** A newly opened store starts in the default zone. */
        fun forNewStore(storeId: StoreId): StorePriceZone = StorePriceZone(storeId, PriceZoneCode.DEFAULT)
    }
}
