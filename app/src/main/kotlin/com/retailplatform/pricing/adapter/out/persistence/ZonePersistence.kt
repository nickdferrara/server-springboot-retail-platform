package com.retailplatform.pricing.adapter.out.persistence

import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.pricing.application.PriceZoneRepository
import com.retailplatform.pricing.application.PricedItemRepository
import com.retailplatform.pricing.application.StorePriceZoneRepository
import com.retailplatform.pricing.domain.PriceZone
import com.retailplatform.pricing.domain.PriceZoneCode
import com.retailplatform.pricing.domain.PricedItem
import com.retailplatform.pricing.domain.StorePriceZone
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.StoreId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "pricing", name = "price_zone")
data class PriceZoneEntity(
    @Id val code: String,
    val name: String,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "pricing", name = "store_price_zone")
data class StorePriceZoneEntity(
    @Id val storeId: UUID,
    val zoneCode: String,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "pricing", name = "priced_item")
data class PricedItemEntity(
    @Id val itemId: UUID,
    val itemNumber: String,
    val discontinuedOn: LocalDate?,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface PriceZoneJdbcRepository : CrudRepository<PriceZoneEntity, String> {
    fun findAllByOrderByCode(): List<PriceZoneEntity>
}

interface StorePriceZoneJdbcRepository : CrudRepository<StorePriceZoneEntity, UUID>

interface PricedItemJdbcRepository : CrudRepository<PricedItemEntity, UUID>

@Repository
class JdbcPriceZoneRepository(
    private val zones: PriceZoneJdbcRepository,
) : PriceZoneRepository {
    override fun add(zone: PriceZone) {
        zones.save(PriceZoneEntity(zone.code.value, zone.name))
    }

    override fun exists(code: PriceZoneCode): Boolean = zones.existsById(code.value)

    override fun findAll(): List<PriceZone> =
        zones.findAllByOrderByCode().map { PriceZone.of(PriceZoneCode.of(it.code), it.name) }
}

@Repository
class JdbcStorePriceZoneRepository(
    private val assignments: StorePriceZoneJdbcRepository,
) : StorePriceZoneRepository {
    override fun save(assignment: StorePriceZone) {
        val existing = assignments.findById(assignment.storeId.value).getOrNull()
        assignments.save(
            existing?.copy(zoneCode = assignment.zone.value)
                ?: StorePriceZoneEntity(assignment.storeId.value, assignment.zone.value),
        )
    }

    override fun findByStore(storeId: StoreId): StorePriceZone? =
        assignments.findById(storeId.value).getOrNull()?.let { entity ->
            StorePriceZone(StoreId.of(entity.storeId), PriceZoneCode.of(entity.zoneCode))
        }
}

@Repository
class JdbcPricedItemRepository(
    private val items: PricedItemJdbcRepository,
) : PricedItemRepository {
    override fun save(item: PricedItem) {
        val existing = items.findById(item.itemId.value).getOrNull()
        items.save(
            existing?.copy(itemNumber = item.itemNumber, discontinuedOn = item.discontinuedOn?.value)
                ?: PricedItemEntity(item.itemId.value, item.itemNumber, item.discontinuedOn?.value),
        )
    }

    override fun findById(itemId: ItemId): PricedItem? =
        items.findById(itemId.value).getOrNull()?.let { entity ->
            PricedItem(ItemId.of(entity.itemId), entity.itemNumber, entity.discontinuedOn?.let(::BusinessDate))
        }
}
