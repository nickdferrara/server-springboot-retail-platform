package com.retailplatform.locations.adapter.out.persistence

import com.retailplatform.platform.persistence.AuditMetadata
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.LocalDate
import java.util.UUID

@Table(schema = "locations", name = "store")
data class StoreEntity(
    @Id val id: UUID,
    val number: String,
    val name: String,
    val timeZone: String,
    val openedOn: LocalDate,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface StoreJdbcRepository : CrudRepository<StoreEntity, UUID> {
    fun existsByNumber(number: String): Boolean
}
