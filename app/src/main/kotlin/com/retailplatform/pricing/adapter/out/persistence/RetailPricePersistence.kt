package com.retailplatform.pricing.adapter.out.persistence

import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.pricing.application.RetailPriceRepository
import com.retailplatform.pricing.domain.PriceScope
import com.retailplatform.pricing.domain.PriceZoneCode
import com.retailplatform.pricing.domain.RetailPrice
import com.retailplatform.pricing.domain.RetailPriceId
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.StoreId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

@Table(schema = "pricing", name = "retail_price")
data class RetailPriceEntity(
    @Id val id: UUID,
    val itemId: UUID,
    val zoneCode: String?,
    val storeId: UUID?,
    val amount: BigDecimal,
    val currency: String,
    val effectiveFrom: LocalDate,
    val announcedOn: LocalDate?,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

private const val SAME_SCOPE =
    "item_id = :itemId " +
        "AND zone_code IS NOT DISTINCT FROM CAST(:zoneCode AS VARCHAR) " +
        "AND store_id IS NOT DISTINCT FROM CAST(:storeId AS UUID)"

interface RetailPriceJdbcRepository : CrudRepository<RetailPriceEntity, UUID> {
    fun findByItemIdOrderByEffectiveFrom(itemId: UUID): List<RetailPriceEntity>

    @Query("SELECT EXISTS (SELECT 1 FROM pricing.retail_price WHERE $SAME_SCOPE AND effective_from = :effectiveFrom)")
    fun existsFor(
        itemId: UUID,
        zoneCode: String?,
        storeId: UUID?,
        effectiveFrom: LocalDate,
    ): Boolean

    @Query(
        "SELECT * FROM pricing.retail_price WHERE $SAME_SCOPE AND effective_from <= :date " +
            "ORDER BY effective_from DESC LIMIT 1",
    )
    fun findEffective(
        itemId: UUID,
        zoneCode: String?,
        storeId: UUID?,
        date: LocalDate,
    ): RetailPriceEntity?

    @Query(
        "SELECT * FROM pricing.retail_price WHERE announced_on IS NULL AND effective_from <= :date " +
            "ORDER BY effective_from, id LIMIT :limit FOR UPDATE SKIP LOCKED",
    )
    fun claimDue(
        date: LocalDate,
        limit: Int,
    ): List<RetailPriceEntity>
}

@Repository
class JdbcRetailPriceRepository(
    private val prices: RetailPriceJdbcRepository,
) : RetailPriceRepository {
    override fun save(price: RetailPrice) {
        val audit = if (price.version == 0) AuditMetadata() else prices.findById(price.id.value).orElseThrow().audit
        prices.save(
            RetailPriceEntity(
                id = price.id.value,
                itemId = price.itemId.value,
                zoneCode = price.scope.zoneCode(),
                storeId = price.scope.storeId(),
                amount = price.price.amount,
                currency = price.price.currency.currencyCode,
                effectiveFrom = price.effectiveFrom.value,
                announcedOn = price.announcedOn?.value,
                version = price.version,
                audit = audit,
            ),
        )
    }

    override fun exists(
        itemId: ItemId,
        scope: PriceScope,
        effectiveFrom: BusinessDate,
    ): Boolean = prices.existsFor(itemId.value, scope.zoneCode(), scope.storeId(), effectiveFrom.value)

    override fun findEffective(
        itemId: ItemId,
        scope: PriceScope,
        date: BusinessDate,
    ): RetailPrice? = prices.findEffective(itemId.value, scope.zoneCode(), scope.storeId(), date.value)?.toDomain()

    override fun findByItem(itemId: ItemId): List<RetailPrice> =
        prices.findByItemIdOrderByEffectiveFrom(itemId.value).map { it.toDomain() }

    override fun claimDue(
        date: BusinessDate,
        limit: Int,
    ): List<RetailPrice> = prices.claimDue(date.value, limit).map { it.toDomain() }

    private fun PriceScope.zoneCode(): String? = (this as? PriceScope.Zone)?.code?.value

    private fun PriceScope.storeId(): UUID? = (this as? PriceScope.Store)?.storeId?.value

    private fun RetailPriceEntity.toDomain(): RetailPrice =
        RetailPrice.rehydrate(
            id = RetailPriceId.of(id),
            itemId = ItemId.of(itemId),
            scope =
                zoneCode?.let { PriceScope.Zone(PriceZoneCode.of(it)) }
                    ?: PriceScope.Store(StoreId.of(requireNotNull(storeId))),
            price = Money.of(amount, Currency.getInstance(currency)),
            effectiveFrom = BusinessDate(effectiveFrom),
            announcedOn = announcedOn?.let(::BusinessDate),
            version = version,
        )
}
