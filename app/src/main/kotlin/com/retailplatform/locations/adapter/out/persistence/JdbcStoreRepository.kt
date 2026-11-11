package com.retailplatform.locations.adapter.out.persistence

import com.retailplatform.locations.application.StoreRepository
import com.retailplatform.locations.domain.Store
import com.retailplatform.locations.domain.StoreName
import com.retailplatform.locations.domain.StoreNumber
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.StoreId
import org.springframework.stereotype.Repository
import java.time.ZoneId
import kotlin.jvm.optionals.getOrNull

@Repository
class JdbcStoreRepository(
    private val stores: StoreJdbcRepository,
) : StoreRepository {
    override fun add(store: Store) {
        stores.save(
            StoreEntity(
                id = store.id.value,
                number = store.number.value,
                name = store.name.value,
                timeZone = store.timeZone.id,
                openedOn = store.openedOn.value,
            ),
        )
    }

    override fun findById(id: StoreId): Store? = stores.findById(id.value).getOrNull()?.toDomain()

    override fun existsByNumber(number: StoreNumber): Boolean = stores.existsByNumber(number.value)

    private fun StoreEntity.toDomain(): Store =
        Store.rehydrate(
            id = StoreId.of(id),
            number = StoreNumber.of(number),
            name = StoreName.of(name),
            timeZone = ZoneId.of(timeZone),
            openedOn = BusinessDate(openedOn),
        )
}
