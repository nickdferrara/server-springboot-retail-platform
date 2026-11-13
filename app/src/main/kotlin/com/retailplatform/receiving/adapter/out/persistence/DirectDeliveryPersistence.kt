package com.retailplatform.receiving.adapter.out.persistence

import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.receiving.application.AgreedCostRepository
import com.retailplatform.receiving.application.DirectDeliveryRepository
import com.retailplatform.receiving.domain.AgreedCost
import com.retailplatform.receiving.domain.CorrectionLine
import com.retailplatform.receiving.domain.CorrectionReason
import com.retailplatform.receiving.domain.DeliveredLine
import com.retailplatform.receiving.domain.DeliveryCorrection
import com.retailplatform.receiving.domain.DeliveryCorrectionId
import com.retailplatform.receiving.domain.DeliveryTicket
import com.retailplatform.receiving.domain.DeliveryTicketNumber
import com.retailplatform.receiving.domain.DirectDelivery
import com.retailplatform.receiving.domain.ReceiptNumber
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "receiving", name = "direct_delivery")
data class DirectDeliveryEntity(
    @Id val id: UUID,
    val receiptNumber: String,
    val vendorId: UUID,
    val storeId: UUID,
    val ticketNumber: String,
    val receivedOn: LocalDate,
    val receivedBy: String,
    @MappedCollection(idColumn = "direct_delivery_id", keyColumn = "position")
    val lines: List<DirectDeliveryLineEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "receiving", name = "direct_delivery_line")
data class DirectDeliveryLineEntity(
    val lineNumber: Int,
    val itemId: UUID,
    val unitsDelivered: Int,
    val unitsReturned: Int,
    val unitCost: BigDecimal,
    val currency: String,
)

@Table(schema = "receiving", name = "direct_delivery_correction")
data class DeliveryCorrectionEntity(
    @Id val id: UUID,
    val directDeliveryId: UUID,
    val receiptNumber: String,
    val correctedOn: LocalDate,
    val correctedBy: String,
    val reason: String,
    @MappedCollection(idColumn = "direct_delivery_correction_id", keyColumn = "position")
    val lines: List<DeliveryCorrectionLineEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "receiving", name = "direct_delivery_correction_line")
data class DeliveryCorrectionLineEntity(
    val itemId: UUID,
    val unitsDeliveredChange: Int,
    val unitsReturnedChange: Int,
    val unitCost: BigDecimal,
    val currency: String,
)

interface DeliveryCorrectionJdbcRepository : CrudRepository<DeliveryCorrectionEntity, UUID> {
    fun findByDirectDeliveryIdOrderById(directDeliveryId: UUID): List<DeliveryCorrectionEntity>
}

interface DirectDeliveryJdbcRepository :
    CrudRepository<DirectDeliveryEntity, UUID>,
    PagingAndSortingRepository<DirectDeliveryEntity, UUID> {
    fun existsByVendorIdAndStoreIdAndTicketNumber(
        vendorId: UUID,
        storeId: UUID,
        ticketNumber: String,
    ): Boolean

    fun findByStoreId(
        storeId: UUID,
        pageable: Pageable,
    ): org.springframework.data.domain.Page<DirectDeliveryEntity>

    @Query("SELECT id FROM receiving.direct_delivery WHERE id = :id FOR UPDATE")
    fun lock(id: UUID): UUID?
}

@Repository
class JdbcDirectDeliveryRepository(
    private val deliveries: DirectDeliveryJdbcRepository,
    private val corrections: DeliveryCorrectionJdbcRepository,
) : DirectDeliveryRepository {
    override fun findForCorrection(id: DirectDeliveryId): DirectDelivery? =
        deliveries.lock(id.value)?.let { findById(id) }

    override fun addCorrection(correction: DeliveryCorrection) {
        corrections.save(
            DeliveryCorrectionEntity(
                id = correction.id.value,
                directDeliveryId = correction.deliveryId.value,
                receiptNumber = correction.receiptNumber.value,
                correctedOn = correction.correctedOn.value,
                correctedBy = correction.correctedBy,
                reason = correction.reason.value,
                lines =
                    correction.lines.map {
                        DeliveryCorrectionLineEntity(
                            itemId = it.itemId.value,
                            unitsDeliveredChange = it.unitsDeliveredChange,
                            unitsReturnedChange = it.unitsReturnedChange,
                            unitCost = it.unitCost.amount,
                            currency = it.unitCost.currency.currencyCode,
                        )
                    },
            ),
        )
    }

    override fun findCorrections(id: DirectDeliveryId): List<DeliveryCorrection> =
        corrections.findByDirectDeliveryIdOrderById(id.value).map {
            DeliveryCorrection(
                id = DeliveryCorrectionId.of(it.id),
                deliveryId = DirectDeliveryId.of(it.directDeliveryId),
                receiptNumber = ReceiptNumber.of(it.receiptNumber),
                correctedOn = BusinessDate(it.correctedOn),
                correctedBy = it.correctedBy,
                reason = CorrectionReason.of(it.reason),
                lines =
                    it.lines.map { line ->
                        CorrectionLine(
                            itemId = ItemId.of(line.itemId),
                            unitsDeliveredChange = line.unitsDeliveredChange,
                            unitsReturnedChange = line.unitsReturnedChange,
                            unitCost = Money.of(line.unitCost, Currency.getInstance(line.currency)),
                        )
                    },
            )
        }

    override fun add(delivery: DirectDelivery) {
        deliveries.save(
            DirectDeliveryEntity(
                id = delivery.id.value,
                receiptNumber = delivery.receiptNumber.value,
                vendorId = delivery.ticket.vendorId.value,
                storeId = delivery.ticket.storeId.value,
                ticketNumber = delivery.ticket.number.value,
                receivedOn = delivery.receivedOn.value,
                receivedBy = delivery.receivedBy,
                lines =
                    delivery.lines.map {
                        DirectDeliveryLineEntity(
                            lineNumber = it.lineNumber,
                            itemId = it.itemId.value,
                            unitsDelivered = it.unitsDelivered,
                            unitsReturned = it.unitsReturned,
                            unitCost = it.unitCost.amount,
                            currency = it.unitCost.currency.currencyCode,
                        )
                    },
            ),
        )
    }

    override fun findById(id: DirectDeliveryId): DirectDelivery? = deliveries.findById(id.value).getOrNull()?.toDomain()

    override fun exists(ticket: DeliveryTicket): Boolean =
        deliveries.existsByVendorIdAndStoreIdAndTicketNumber(
            ticket.vendorId.value,
            ticket.storeId.value,
            ticket.number.value,
        )

    override fun findByStore(
        storeId: StoreId,
        query: PageQuery,
    ): Page<DirectDelivery> {
        // Identifiers are time-ordered, so the newest delivery comes first.
        val newestFirst = PageRequest.of(query.page, query.size, Sort.by(Sort.Direction.DESC, "id"))
        val page = deliveries.findByStoreId(storeId.value, newestFirst)
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun DirectDeliveryEntity.toDomain(): DirectDelivery =
        DirectDelivery.rehydrate(
            id = DirectDeliveryId.of(id),
            receiptNumber = ReceiptNumber.of(receiptNumber),
            ticket = DeliveryTicket(VendorId.of(vendorId), StoreId.of(storeId), DeliveryTicketNumber.of(ticketNumber)),
            receivedOn = BusinessDate(receivedOn),
            receivedBy = receivedBy,
            lines =
                lines.map {
                    DeliveredLine(
                        lineNumber = it.lineNumber,
                        itemId = ItemId.of(it.itemId),
                        unitsDelivered = it.unitsDelivered,
                        unitsReturned = it.unitsReturned,
                        unitCost = Money.of(it.unitCost, Currency.getInstance(it.currency)),
                    )
                },
        )
}

@Repository
class JdbcAgreedCostRepository(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : AgreedCostRepository {
    override fun save(
        cost: AgreedCost,
        setBy: String,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO receiving.direct_delivery_cost
                    (vendor_id, item_id, store_id, effective_from, unit_cost, currency, set_by, set_at)
                VALUES (:vendorId, :itemId, :storeId, :effectiveFrom, :unitCost, :currency, :setBy, :setAt)
                ON CONFLICT (vendor_id, store_id, item_id, effective_from) DO UPDATE
                    SET unit_cost = excluded.unit_cost,
                        currency = excluded.currency,
                        set_by = excluded.set_by,
                        set_at = excluded.set_at
                """.trimIndent(),
            ).param("vendorId", cost.vendorId.value)
            .param("itemId", cost.itemId.value)
            .param("storeId", cost.storeId.value)
            .param("effectiveFrom", cost.effectiveFrom.value)
            .param("unitCost", cost.unitCost.amount)
            .param("currency", cost.unitCost.currency.currencyCode)
            .param("setBy", setBy)
            .param("setAt", clock.instant().atOffset(ZoneOffset.UTC))
            .update()
    }

    override fun findEffective(
        vendorId: VendorId,
        storeId: StoreId,
        asOf: BusinessDate,
    ): List<AgreedCost> =
        jdbc
            .sql(
                """
                SELECT DISTINCT ON (item_id) item_id, effective_from, unit_cost, currency
                FROM receiving.direct_delivery_cost
                WHERE vendor_id = :vendorId AND store_id = :storeId AND effective_from <= :asOf
                ORDER BY item_id, effective_from DESC
                """.trimIndent(),
            ).param("vendorId", vendorId.value)
            .param("storeId", storeId.value)
            .param("asOf", asOf.value)
            .query { row, _ ->
                AgreedCost.of(
                    vendorId = vendorId,
                    itemId = ItemId.of(row.getObject("item_id", UUID::class.java)),
                    storeId = storeId,
                    unitCost =
                        Money.of(row.getBigDecimal("unit_cost"), Currency.getInstance(row.getString("currency"))),
                    effectiveFrom = BusinessDate(row.getObject("effective_from", LocalDate::class.java)),
                )
            }.list()
}
