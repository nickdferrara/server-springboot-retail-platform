package com.retailplatform.pricing.adapter.out.persistence

import com.retailplatform.pricing.application.StorePriceZoneRepository
import com.retailplatform.pricing.domain.PriceZoneCode
import com.retailplatform.pricing.domain.StorePriceZone
import com.retailplatform.shared.StoreId
import org.springframework.stereotype.Repository
import kotlin.jvm.optionals.getOrNull

@Repository
class JdbcStorePriceZoneRepository(
    private val assignments: StorePriceZoneJdbcRepository,
) : StorePriceZoneRepository {
    override fun add(assignment: StorePriceZone) {
        assignments.save(StorePriceZoneEntity(storeId = assignment.storeId.value, zoneCode = assignment.zone.value))
    }

    override fun findByStore(storeId: StoreId): StorePriceZone? =
        assignments.findById(storeId.value).getOrNull()?.let { entity ->
            StorePriceZone(StoreId.of(entity.storeId), PriceZoneCode.of(entity.zoneCode))
        }
}
