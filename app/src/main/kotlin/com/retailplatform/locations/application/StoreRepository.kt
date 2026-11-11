package com.retailplatform.locations.application

import com.retailplatform.locations.domain.Store
import com.retailplatform.locations.domain.StoreNumber
import com.retailplatform.shared.StoreId

/** Port: where stores are kept. */
interface StoreRepository {
    fun add(store: Store)

    fun findById(id: StoreId): Store?

    fun existsByNumber(number: StoreNumber): Boolean
}
