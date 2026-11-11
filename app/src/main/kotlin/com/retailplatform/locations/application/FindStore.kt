package com.retailplatform.locations.application

import com.retailplatform.locations.domain.Store
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Query: one store by its identifier. */
@Service
class FindStore(
    private val stores: StoreRepository,
) {
    @Transactional(readOnly = true)
    fun handle(id: StoreId): Store = stores.findById(id) ?: throw ResourceNotFound("Store", id.toString())
}
