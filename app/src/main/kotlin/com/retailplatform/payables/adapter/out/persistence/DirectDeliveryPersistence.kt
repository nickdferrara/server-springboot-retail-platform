package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.DirectDeliveryAccrualRepository
import com.retailplatform.payables.application.VendorCreditRepository
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.DirectDeliveryAccrual
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.VendorCredit
import com.retailplatform.payables.domain.VendorCreditId
import com.retailplatform.platform.persistence.AuditMetadata
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
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/** One line of an accrual or credit, stored the same way in either table. */
data class TicketAmountLineEntity(
    val itemId: UUID,
    val units: Int,
    val unitCost: BigDecimal,
)

@Table(schema = "payables", name = "direct_delivery_accrual")
data class DirectDeliveryAccrualEntity(
    @Id @Column("delivery_id") val deliveryId: UUID,
    val receiptNumber: String,
    val vendorId: UUID,
    val storeId: UUID,
    val ticketNumber: String,
    val receivedOn: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    @MappedCollection(idColumn = "delivery_id", keyColumn = "position")
    val lines: List<DirectDeliveryAccrualLineEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "payables", name = "direct_delivery_accrual_line")
data class DirectDeliveryAccrualLineEntity(
    @Embedded.Empty val line: TicketAmountLineEntity,
)

@Table(schema = "payables", name = "vendor_credit")
data class VendorCreditEntity(
    @Id val id: UUID,
    val deliveryId: UUID,
    val receiptNumber: String,
    val vendorId: UUID,
    val storeId: UUID,
    val ticketNumber: String,
    val creditedOn: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    @MappedCollection(idColumn = "vendor_credit_id", keyColumn = "position")
    val lines: List<VendorCreditLineEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "payables", name = "vendor_credit_line")
data class VendorCreditLineEntity(
    @Embedded.Empty val line: TicketAmountLineEntity,
)

interface DirectDeliveryAccrualJdbcRepository : CrudRepository<DirectDeliveryAccrualEntity, UUID> {
    @Query(
        "SELECT * FROM payables.direct_delivery_accrual WHERE vendor_id = :vendorId " +
            "ORDER BY received_on, delivery_id LIMIT :limit OFFSET :offset",
    )
    fun findByVendor(
        vendorId: UUID,
        limit: Int,
        offset: Long,
    ): List<DirectDeliveryAccrualEntity>

    fun countByVendorId(vendorId: UUID): Long
}

interface VendorCreditJdbcRepository : CrudRepository<VendorCreditEntity, UUID> {
    fun existsByDeliveryId(deliveryId: UUID): Boolean

    @Query(
        "SELECT * FROM payables.vendor_credit WHERE vendor_id = :vendorId " +
            "ORDER BY credited_on, id LIMIT :limit OFFSET :offset",
    )
    fun findByVendor(
        vendorId: UUID,
        limit: Int,
        offset: Long,
    ): List<VendorCreditEntity>

    fun countByVendorId(vendorId: UUID): Long
}

private fun List<TicketAmountLine>.toEntities() =
    map {
        TicketAmountLineEntity(it.itemId.value, it.units, it.unitCost.amount)
    }

private fun TicketAmountLineEntity.toDomain(currency: Currency) =
    TicketAmountLine.of(ItemId.of(itemId), units, Money.of(unitCost, currency))

@Repository
class JdbcDirectDeliveryAccrualRepository(
    private val accruals: DirectDeliveryAccrualJdbcRepository,
) : DirectDeliveryAccrualRepository {
    override fun save(accrual: DirectDeliveryAccrual) {
        val ticket = accrual.ticket
        accruals.save(
            DirectDeliveryAccrualEntity(
                deliveryId = ticket.deliveryId.value,
                receiptNumber = ticket.receiptNumber,
                vendorId = ticket.vendorId.value,
                storeId = ticket.storeId.value,
                ticketNumber = ticket.ticketNumber,
                receivedOn = ticket.receivedOn.value,
                amount = accrual.amount.amount,
                currency = accrual.amount.currency.currencyCode,
                lines = accrual.lines.toEntities().map(::DirectDeliveryAccrualLineEntity),
                version = accrual.version,
            ),
        )
    }

    override fun existsFor(deliveryId: DirectDeliveryId): Boolean = accruals.existsById(deliveryId.value)

    override fun findByVendor(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<DirectDeliveryAccrual> =
        Page(
            accruals.findByVendor(vendorId.value, query.size, query.offset).map { it.toDomain() },
            query.page,
            query.size,
            accruals.countByVendorId(vendorId.value),
        )

    private fun DirectDeliveryAccrualEntity.toDomain(): DirectDeliveryAccrual {
        val money = Currency.getInstance(currency)
        return DirectDeliveryAccrual.rehydrate(
            ticket =
                DeliveredTicket(
                    DirectDeliveryId.of(deliveryId),
                    receiptNumber,
                    VendorId.of(vendorId),
                    StoreId.of(storeId),
                    ticketNumber,
                    BusinessDate(receivedOn),
                ),
            lines = lines.map { it.line.toDomain(money) },
            version = version,
        )
    }
}

@Repository
class JdbcVendorCreditRepository(
    private val credits: VendorCreditJdbcRepository,
) : VendorCreditRepository {
    override fun save(credit: VendorCredit) {
        val ticket = credit.ticket
        credits.save(
            VendorCreditEntity(
                id = credit.id.value,
                deliveryId = ticket.deliveryId.value,
                receiptNumber = ticket.receiptNumber,
                vendorId = ticket.vendorId.value,
                storeId = ticket.storeId.value,
                ticketNumber = ticket.ticketNumber,
                creditedOn = ticket.receivedOn.value,
                amount = credit.amount.amount,
                currency = credit.amount.currency.currencyCode,
                lines = credit.lines.toEntities().map(::VendorCreditLineEntity),
                version = credit.version,
            ),
        )
    }

    override fun existsFor(deliveryId: DirectDeliveryId): Boolean = credits.existsByDeliveryId(deliveryId.value)

    override fun findByVendor(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<VendorCredit> =
        Page(
            credits.findByVendor(vendorId.value, query.size, query.offset).map { it.toDomain() },
            query.page,
            query.size,
            credits.countByVendorId(vendorId.value),
        )

    private fun VendorCreditEntity.toDomain(): VendorCredit {
        val money = Currency.getInstance(currency)
        return VendorCredit.rehydrate(
            id = VendorCreditId.of(id),
            ticket =
                DeliveredTicket(
                    DirectDeliveryId.of(deliveryId),
                    receiptNumber,
                    VendorId.of(vendorId),
                    StoreId.of(storeId),
                    ticketNumber,
                    BusinessDate(creditedOn),
                ),
            lines = lines.map { it.line.toDomain(money) },
            version = version,
        )
    }
}
