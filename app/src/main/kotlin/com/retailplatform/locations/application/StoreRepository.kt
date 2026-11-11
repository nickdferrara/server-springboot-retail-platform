package com.retailplatform.locations.application

import com.retailplatform.locations.domain.Store
import com.retailplatform.locations.domain.StoreNumber
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.StoreId

/** Port: where stores are kept. */
interface StoreRepository {
    /** Inserts a new store or updates an existing one; a stale version is refused. */
    fun save(store: Store)

    fun findById(id: StoreId): Store?

    fun findAllById(ids: Collection<StoreId>): List<Store>

    fun existsByNumber(number: StoreNumber): Boolean

    /** Stores ordered by number. */
    fun findPage(query: PageQuery): Page<Store>
}
