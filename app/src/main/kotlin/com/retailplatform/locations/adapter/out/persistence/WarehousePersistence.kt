package com.retailplatform.locations.adapter.out.persistence

import com.retailplatform.locations.application.WarehouseRepository
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.Warehouse
import com.retailplatform.locations.domain.WarehouseNumber
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.WarehouseId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import org.springframework.stereotype.Repository
import java.time.ZoneId
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "locations", name = "warehouse")
data class WarehouseEntity(
    @Id val id: UUID,
    val number: String,
    val name: String,
    val timeZone: String,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface WarehouseJdbcRepository :
    CrudRepository<WarehouseEntity, UUID>,
    PagingAndSortingRepository<WarehouseEntity, UUID> {
    fun existsByNumber(number: String): Boolean
}

@Repository
class JdbcWarehouseRepository(
    private val warehouses: WarehouseJdbcRepository,
) : WarehouseRepository {
    override fun save(warehouse: Warehouse) {
        val audit =
            if (warehouse.version == 0) {
                AuditMetadata()
            } else {
                warehouses.findById(warehouse.id.value).orElseThrow().audit
            }
        warehouses.save(
            WarehouseEntity(
                id = warehouse.id.value,
                number = warehouse.number.value,
                name = warehouse.name.value,
                timeZone = warehouse.timeZone.id,
                version = warehouse.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: WarehouseId): Warehouse? = warehouses.findById(id.value).getOrNull()?.toDomain()

    override fun existsById(id: WarehouseId): Boolean = warehouses.existsById(id.value)

    override fun existsByNumber(number: WarehouseNumber): Boolean = warehouses.existsByNumber(number.value)

    override fun findPage(query: PageQuery): Page<Warehouse> {
        val page = warehouses.findAll(PageRequest.of(query.page, query.size, Sort.by("number")))
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun WarehouseEntity.toDomain(): Warehouse =
        Warehouse.rehydrate(
            id = WarehouseId.of(id),
            number = WarehouseNumber.of(number),
            name = LocationName.of(name),
            timeZone = ZoneId.of(timeZone),
            version = version,
        )
}
