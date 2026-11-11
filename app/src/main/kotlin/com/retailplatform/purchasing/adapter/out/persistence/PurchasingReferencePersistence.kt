package com.retailplatform.purchasing.adapter.out.persistence

import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.purchasing.application.ApprovalThresholdRepository
import com.retailplatform.purchasing.application.VendorItemCostRepository
import com.retailplatform.purchasing.domain.ApprovalThreshold
import com.retailplatform.purchasing.domain.VendorItemCost
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.UuidV7
import com.retailplatform.shared.VendorId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID

@Repository
class JdbcVendorItemCostRepository(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : VendorItemCostRepository {
    override fun save(cost: VendorItemCost) {
        jdbc
            .sql(
                """
                INSERT INTO purchasing.vendor_item_cost
                    (vendor_id, item_id, vendor_sku, case_pack, amount, currency, updated_at)
                VALUES (:vendorId, :itemId, :vendorSku, :casePack, :amount, :currency, :updatedAt)
                ON CONFLICT (vendor_id, item_id) DO UPDATE
                    SET vendor_sku = EXCLUDED.vendor_sku, case_pack = EXCLUDED.case_pack, amount = EXCLUDED.amount,
                        currency = EXCLUDED.currency, updated_at = EXCLUDED.updated_at
                """.trimIndent(),
            ).param("vendorId", cost.vendorId.value)
            .param("itemId", cost.itemId.value)
            .param("vendorSku", cost.vendorSku)
            .param("casePack", cost.casePack)
            .param("amount", cost.caseCost.amount)
            .param("currency", cost.caseCost.currency.currencyCode)
            .param("updatedAt", clock.instant().atOffset(ZoneOffset.UTC))
            .update()
    }

    override fun findByVendorAndItems(
        vendorId: VendorId,
        itemIds: Collection<ItemId>,
    ): Map<ItemId, VendorItemCost> {
        if (itemIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                SELECT item_id, vendor_sku, case_pack, amount, currency FROM purchasing.vendor_item_cost
                WHERE vendor_id = :vendorId AND item_id IN (:itemIds)
                """.trimIndent(),
            ).param("vendorId", vendorId.value)
            .param("itemIds", itemIds.map { it.value })
            .query { row, _ ->
                VendorItemCost(
                    vendorId = vendorId,
                    itemId = ItemId.of(row.getObject("item_id", UUID::class.java)),
                    vendorSku = row.getString("vendor_sku"),
                    casePack = row.getInt("case_pack"),
                    caseCost = Money.of(row.getBigDecimal("amount"), Currency.getInstance(row.getString("currency"))),
                )
            }.list()
            .associateBy { it.itemId }
    }
}

@Table(schema = "purchasing", name = "approval_threshold")
data class ApprovalThresholdEntity(
    @Id val id: UUID,
    val amount: BigDecimal,
    val currency: String,
    val effectiveFrom: LocalDate,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface ApprovalThresholdJdbcRepository : CrudRepository<ApprovalThresholdEntity, UUID> {
    fun findByEffectiveFrom(effectiveFrom: LocalDate): ApprovalThresholdEntity?

    @Query(
        "SELECT * FROM purchasing.approval_threshold WHERE effective_from <= :date " +
            "ORDER BY effective_from DESC LIMIT 1",
    )
    fun findEffective(date: LocalDate): ApprovalThresholdEntity?
}

@Repository
class JdbcApprovalThresholdRepository(
    private val thresholds: ApprovalThresholdJdbcRepository,
    private val clock: Clock,
) : ApprovalThresholdRepository {
    override fun save(threshold: ApprovalThreshold) {
        val amount = threshold.amount.amount
        val currency = threshold.amount.currency.currencyCode
        val existing = thresholds.findByEffectiveFrom(threshold.effectiveFrom.value)
        thresholds.save(
            existing?.copy(amount = amount, currency = currency)
                ?: ApprovalThresholdEntity(UuidV7.generate(clock), amount, currency, threshold.effectiveFrom.value),
        )
    }

    override fun findEffective(date: BusinessDate): ApprovalThreshold? =
        thresholds.findEffective(date.value)?.let {
            ApprovalThreshold.of(Money.of(it.amount, Currency.getInstance(it.currency)), BusinessDate(it.effectiveFrom))
        }
}
