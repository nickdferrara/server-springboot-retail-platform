package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.AccrualRepository
import com.retailplatform.payables.application.OpenBalance
import com.retailplatform.payables.domain.Accrual
import com.retailplatform.payables.domain.AccrualLine
import com.retailplatform.payables.domain.AccruedReceipt
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.AccrualId
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "payables", name = "accrual")
data class AccrualEntity(
    @Id val id: UUID,
    val receiptId: UUID,
    val receiptNumber: String,
    val purchaseOrderId: UUID,
    val purchaseOrderNumber: String,
    val vendorId: UUID,
    val warehouseId: UUID,
    val receivedOn: LocalDate,
    val amount: BigDecimal,
    val openAmount: BigDecimal,
    val currency: String,
    @MappedCollection(idColumn = "accrual_id", keyColumn = "position") val lines: List<AccrualLineEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "payables", name = "accrual_line")
data class AccrualLineEntity(
    val purchaseOrderLineNumber: Int,
    val itemId: UUID,
    val casesReceived: Int,
    val caseCost: BigDecimal,
    val casesInvoiced: Int,
)

interface AccrualJdbcRepository : CrudRepository<AccrualEntity, UUID> {
    fun existsByReceiptId(receiptId: UUID): Boolean

    @Query(
        "SELECT * FROM payables.accrual WHERE vendor_id = :vendorId AND open_amount > 0 " +
            "ORDER BY received_on, id LIMIT :limit OFFSET :offset",
    )
    fun findOpen(
        vendorId: UUID,
        limit: Int,
        offset: Long,
    ): List<AccrualEntity>

    @Query("SELECT count(*) FROM payables.accrual WHERE vendor_id = :vendorId AND open_amount > 0")
    fun countOpen(vendorId: UUID): Long

    @Query(
        "SELECT * FROM payables.accrual WHERE purchase_order_id = :purchaseOrderId AND open_amount > 0 " +
            "ORDER BY received_on, id",
    )
    fun findOpenByPurchaseOrder(purchaseOrderId: UUID): List<AccrualEntity>
}

@Repository
class JdbcAccrualRepository(
    private val accruals: AccrualJdbcRepository,
    private val jdbc: JdbcClient,
) : AccrualRepository {
    override fun save(accrual: Accrual) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit =
            if (accrual.version == 0) AuditMetadata() else accruals.findById(accrual.id.value).orElseThrow().audit
        accruals.save(
            AccrualEntity(
                id = accrual.id.value,
                receiptId = accrual.receipt.receiptId.value,
                receiptNumber = accrual.receipt.receiptNumber,
                purchaseOrderId = accrual.receipt.purchaseOrderId.value,
                purchaseOrderNumber = accrual.receipt.purchaseOrderNumber,
                vendorId = accrual.receipt.vendorId.value,
                warehouseId = accrual.receipt.warehouseId.value,
                receivedOn = accrual.receipt.receivedOn.value,
                amount = accrual.amount.amount,
                openAmount = accrual.openAmount.amount,
                currency = accrual.amount.currency.currencyCode,
                lines =
                    accrual.lines.map {
                        AccrualLineEntity(
                            it.purchaseOrderLineNumber,
                            it.itemId.value,
                            it.casesReceived,
                            it.caseCost.amount,
                            it.casesInvoiced,
                        )
                    },
                version = accrual.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: AccrualId): Accrual? = accruals.findById(id.value).getOrNull()?.toDomain()

    override fun existsForReceipt(receiptId: ReceiptId): Boolean = accruals.existsByReceiptId(receiptId.value)

    override fun findOpen(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<Accrual> =
        Page(
            accruals.findOpen(vendorId.value, query.size, query.offset).map { it.toDomain() },
            query.page,
            query.size,
            accruals.countOpen(vendorId.value),
        )

    override fun findOpenByPurchaseOrder(purchaseOrderId: PurchaseOrderId): List<Accrual> =
        accruals.findOpenByPurchaseOrder(purchaseOrderId.value).map { it.toDomain() }

    override fun openBalances(): List<OpenBalance> =
        jdbc
            .sql(
                """
                SELECT vendor_id, currency, sum(open_amount) AS open_amount, count(*) AS open_accruals,
                       min(received_on) AS oldest_received_on
                FROM payables.accrual
                WHERE open_amount > 0
                GROUP BY vendor_id, currency
                """.trimIndent(),
            ).query { row, _ ->
                OpenBalance(
                    vendorId = VendorId.of(row.getObject("vendor_id", UUID::class.java)),
                    openAmount =
                        Money.of(row.getBigDecimal("open_amount"), Currency.getInstance(row.getString("currency"))),
                    openAccruals = row.getInt("open_accruals"),
                    oldestReceivedOn = BusinessDate(row.getObject("oldest_received_on", LocalDate::class.java)),
                )
            }.list()

    private fun AccrualEntity.toDomain(): Accrual {
        val money = Currency.getInstance(currency)
        return Accrual.rehydrate(
            id = AccrualId.of(id),
            receipt =
                AccruedReceipt(
                    receiptId = ReceiptId.of(receiptId),
                    receiptNumber = receiptNumber,
                    purchaseOrderId = PurchaseOrderId.of(purchaseOrderId),
                    purchaseOrderNumber = purchaseOrderNumber,
                    vendorId = VendorId.of(vendorId),
                    warehouseId = WarehouseId.of(warehouseId),
                    receivedOn = BusinessDate(receivedOn),
                ),
            lines =
                lines.map {
                    AccrualLine.of(
                        it.purchaseOrderLineNumber,
                        ItemId.of(it.itemId),
                        it.casesReceived,
                        Money.of(it.caseCost, money),
                        it.casesInvoiced,
                    )
                },
            version = version,
        )
    }
}
