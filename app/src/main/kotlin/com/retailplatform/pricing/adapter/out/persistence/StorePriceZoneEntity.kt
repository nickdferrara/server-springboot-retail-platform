package com.retailplatform.pricing.adapter.out.persistence

import com.retailplatform.platform.persistence.AuditMetadata
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.util.UUID

@Table(schema = "pricing", name = "store_price_zone")
data class StorePriceZoneEntity(
    @Id val storeId: UUID,
    val zoneCode: String,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface StorePriceZoneJdbcRepository : CrudRepository<StorePriceZoneEntity, UUID>
