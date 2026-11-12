package com.retailplatform.inventory.adapter.out.persistence

import com.retailplatform.inventory.application.StockLevelRepository
import com.retailplatform.inventory.application.StockedItemRepository
import com.retailplatform.inventory.domain.StockLevel
import com.retailplatform.inventory.domain.StockLevelId
import com.retailplatform.inventory.domain.StockLocation
import com.retailplatform.inventory.domain.StockPosition
import com.retailplatform.inventory.domain.StockedItem
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.Quantity
import com.retailplatform.shared.UnitOfMeasure
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "inventory", name = "stocked_item")
data class StockedItemEntity(
    @Id val itemId: UUID,
    val itemNumber: String,
    val baseUnit: String,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface StockedItemJdbcRepository : CrudRepository<StockedItemEntity, UUID>

@Repository
class JdbcStockedItemRepository(
    private val items: StockedItemJdbcRepository,
) : StockedItemRepository {
    override fun save(item: StockedItem) {
        val existing = items.findById(item.itemId.value).getOrNull()
        items.save(
            existing?.copy(itemNumber = item.itemNumber, baseUnit = item.baseUnit.code)
                ?: StockedItemEntity(item.itemId.value, item.itemNumber, item.baseUnit.code),
        )
    }

    override fun findById(itemId: ItemId): StockedItem? =
        items.findById(itemId.value).getOrNull()?.let {
            StockedItem(ItemId.of(it.itemId), it.itemNumber, UnitOfMeasure.of(it.baseUnit))
        }
}

@Table(schema = "inventory", name = "stock_level")
data class StockLevelEntity(
    @Id val id: UUID,
    val itemId: UUID,
    val locationType: String,
    val locationId: UUID,
    val onHand: BigDecimal,
    val unit: String,
    val averageCost: BigDecimal?,
    val currency: String?,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface StockLevelJdbcRepository : CrudRepository<StockLevelEntity, UUID> {
    fun findByItemIdAndLocationTypeAndLocationId(
        itemId: UUID,
        locationType: String,
        locationId: UUID,
    ): StockLevelEntity?

    fun findByLocationTypeAndLocationId(
        locationType: String,
        locationId: UUID,
        pageable: Pageable,
    ): org.springframework.data.domain.Page<StockLevelEntity>
}

@Repository
class JdbcStockLevelRepository(
    private val levels: StockLevelJdbcRepository,
    private val jdbc: JdbcClient,
) : StockLevelRepository {
    override fun save(level: StockLevel) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit = if (level.version == 0) AuditMetadata() else levels.findById(level.id.value).orElseThrow().audit
        levels.save(
            StockLevelEntity(
                id = level.id.value,
                itemId = level.itemId.value,
                locationType = level.location.type,
                locationId = level.location.id,
                onHand = level.onHand.amount,
                unit = level.onHand.unit.code,
                averageCost = level.averageCost?.amount,
                currency = level.averageCost?.currency?.currencyCode,
                version = level.version,
                audit = audit,
            ),
        )
    }

    override fun lock(
        itemId: ItemId,
        location: StockLocation,
    ) {
        // A transaction-scoped advisory lock: it needs no row to exist and is released at commit or rollback.
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "inventory.stock_level:${itemId.value}:${location.type}:${location.id}")
            .query { _, _ -> Unit }
            .list()
    }

    override fun find(
        itemId: ItemId,
        location: StockLocation,
    ): StockLevel? =
        levels.findByItemIdAndLocationTypeAndLocationId(itemId.value, location.type, location.id)?.toDomain()

    override fun findPage(
        location: StockLocation,
        query: PageQuery,
    ): Page<StockLevel> {
        val page =
            levels.findByLocationTypeAndLocationId(
                location.type,
                location.id,
                PageRequest.of(query.page, query.size, Sort.by("id")),
            )
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun StockLevelEntity.toDomain(): StockLevel =
        StockLevel.rehydrate(
            id = StockLevelId.of(id),
            itemId = ItemId.of(itemId),
            location = StockLocation.of(locationType, locationId),
            position = StockPosition(Quantity.of(onHand, UnitOfMeasure.of(unit)), money(averageCost, currency)),
            version = version,
        )
}

internal fun money(
    amount: BigDecimal?,
    currency: String?,
): Money? = if (amount != null && currency != null) Money.of(amount, Currency.getInstance(currency)) else null
