package com.retailplatform.receiving.adapter.out.persistence

import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.receiving.application.ExpectedDeliveryRepository
import com.retailplatform.receiving.application.ReceiptNumbers
import com.retailplatform.receiving.application.ReceiptRepository
import com.retailplatform.receiving.domain.DeliveryStatus
import com.retailplatform.receiving.domain.ExpectedDelivery
import com.retailplatform.receiving.domain.ExpectedLine
import com.retailplatform.receiving.domain.Receipt
import com.retailplatform.receiving.domain.ReceiptLine
import com.retailplatform.receiving.domain.ReceiptNumber
import com.retailplatform.receiving.domain.ReceivedAgainst
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
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
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

@Table(schema = "receiving", name = "expected_delivery")
data class ExpectedDeliveryEntity(
    @Id val purchaseOrderId: UUID,
    val purchaseOrderNumber: String,
    val vendorId: UUID,
    val warehouseId: UUID,
    val expectedOn: LocalDate,
    val status: String,
    @MappedCollection(idColumn = "purchase_order_id", keyColumn = "position") val lines: List<ExpectedLineEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "receiving", name = "expected_delivery_line")
data class ExpectedLineEntity(
    val lineNumber: Int,
    val itemId: UUID,
    val vendorSku: String,
    val casePack: Int,
    val caseCost: BigDecimal,
    val currency: String,
    val casesOrdered: Int,
    val casesReceived: Int,
)

interface ExpectedDeliveryJdbcRepository : CrudRepository<ExpectedDeliveryEntity, UUID> {
    fun findByWarehouseIdAndStatus(
        warehouseId: UUID,
        status: String,
        pageable: Pageable,
    ): org.springframework.data.domain.Page<ExpectedDeliveryEntity>
}

@Repository
class JdbcExpectedDeliveryRepository(
    private val deliveries: ExpectedDeliveryJdbcRepository,
) : ExpectedDeliveryRepository {
    override fun save(delivery: ExpectedDelivery) {
        val id = delivery.against.purchaseOrderId.value
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit = if (delivery.version == 0) AuditMetadata() else deliveries.findById(id).orElseThrow().audit
        deliveries.save(
            ExpectedDeliveryEntity(
                purchaseOrderId = id,
                purchaseOrderNumber = delivery.against.purchaseOrderNumber,
                vendorId = delivery.against.vendorId.value,
                warehouseId = delivery.against.warehouseId.value,
                expectedOn = delivery.expectedOn.value,
                status = delivery.status.name,
                lines =
                    delivery.lines.map {
                        ExpectedLineEntity(
                            lineNumber = it.lineNumber,
                            itemId = it.itemId.value,
                            vendorSku = it.vendorSku,
                            casePack = it.casePack,
                            caseCost = it.caseCost.amount,
                            currency = it.caseCost.currency.currencyCode,
                            casesOrdered = it.casesOrdered,
                            casesReceived = it.casesReceived,
                        )
                    },
                version = delivery.version,
                audit = audit,
            ),
        )
    }

    override fun findById(purchaseOrderId: PurchaseOrderId): ExpectedDelivery? =
        deliveries.findById(purchaseOrderId.value).getOrNull()?.toDomain()

    override fun findOpen(
        warehouseId: WarehouseId,
        query: PageQuery,
    ): Page<ExpectedDelivery> {
        val earliestFirst = PageRequest.of(query.page, query.size, Sort.by("expectedOn", "purchaseOrderNumber"))
        val page = deliveries.findByWarehouseIdAndStatus(warehouseId.value, DeliveryStatus.OPEN.name, earliestFirst)
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun ExpectedDeliveryEntity.toDomain(): ExpectedDelivery =
        ExpectedDelivery.rehydrate(
            against = receivedAgainst(purchaseOrderId, purchaseOrderNumber, vendorId, warehouseId),
            expectedOn = BusinessDate(expectedOn),
            lines =
                lines.map {
                    ExpectedLine(
                        lineNumber = it.lineNumber,
                        itemId = ItemId.of(it.itemId),
                        vendorSku = it.vendorSku,
                        casePack = it.casePack,
                        caseCost = Money.of(it.caseCost, Currency.getInstance(it.currency)),
                        casesOrdered = it.casesOrdered,
                        casesReceived = it.casesReceived,
                    )
                },
            status = DeliveryStatus.valueOf(status),
            version = version,
        )
}

@Table(schema = "receiving", name = "receipt")
data class ReceiptEntity(
    @Id val id: UUID,
    val number: String,
    val purchaseOrderId: UUID,
    val purchaseOrderNumber: String,
    val vendorId: UUID,
    val warehouseId: UUID,
    val receivedOn: LocalDate,
    val receivedBy: String,
    val finalDelivery: Boolean,
    @MappedCollection(idColumn = "receipt_id", keyColumn = "position") val lines: List<ReceiptLineEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "receiving", name = "receipt_line")
data class ReceiptLineEntity(
    val lineNumber: Int,
    val itemId: UUID,
    val casePack: Int,
    val caseCost: BigDecimal,
    val currency: String,
    val casesReceived: Int,
    val casesDamaged: Int,
    val casesOver: Int,
    val casesShort: Int,
)

interface ReceiptJdbcRepository : CrudRepository<ReceiptEntity, UUID> {
    fun findByPurchaseOrderIdOrderById(purchaseOrderId: UUID): List<ReceiptEntity>
}

@Repository
class JdbcReceiptRepository(
    private val receipts: ReceiptJdbcRepository,
) : ReceiptRepository {
    override fun add(receipt: Receipt) {
        receipts.save(
            ReceiptEntity(
                id = receipt.id.value,
                number = receipt.number.value,
                purchaseOrderId = receipt.against.purchaseOrderId.value,
                purchaseOrderNumber = receipt.against.purchaseOrderNumber,
                vendorId = receipt.against.vendorId.value,
                warehouseId = receipt.against.warehouseId.value,
                receivedOn = receipt.receivedOn.value,
                receivedBy = receipt.receivedBy,
                finalDelivery = receipt.finalDelivery,
                lines =
                    receipt.lines.map {
                        ReceiptLineEntity(
                            lineNumber = it.lineNumber,
                            itemId = it.itemId.value,
                            casePack = it.casePack,
                            caseCost = it.caseCost.amount,
                            currency = it.caseCost.currency.currencyCode,
                            casesReceived = it.casesReceived,
                            casesDamaged = it.casesDamaged,
                            casesOver = it.casesOver,
                            casesShort = it.casesShort,
                        )
                    },
            ),
        )
    }

    override fun findById(id: ReceiptId): Receipt? = receipts.findById(id.value).getOrNull()?.toDomain()

    override fun findByPurchaseOrder(purchaseOrderId: PurchaseOrderId): List<Receipt> =
        receipts.findByPurchaseOrderIdOrderById(purchaseOrderId.value).map { it.toDomain() }

    private fun ReceiptEntity.toDomain(): Receipt =
        Receipt(
            id = ReceiptId.of(id),
            number = ReceiptNumber.of(number),
            against = receivedAgainst(purchaseOrderId, purchaseOrderNumber, vendorId, warehouseId),
            receivedOn = BusinessDate(receivedOn),
            receivedBy = receivedBy,
            finalDelivery = finalDelivery,
            lines =
                lines.map {
                    ReceiptLine(
                        lineNumber = it.lineNumber,
                        itemId = ItemId.of(it.itemId),
                        casePack = it.casePack,
                        caseCost = Money.of(it.caseCost, Currency.getInstance(it.currency)),
                        casesReceived = it.casesReceived,
                        casesDamaged = it.casesDamaged,
                        casesOver = it.casesOver,
                        casesShort = it.casesShort,
                    )
                },
        )
}

@Repository
class SequenceReceiptNumbers(
    private val jdbc: JdbcClient,
) : ReceiptNumbers {
    override fun next(): ReceiptNumber =
        ReceiptNumber.of(
            jdbc
                .sql("SELECT nextval('receiving.receipt_number_seq')")
                .query(Long::class.java)
                .single()
                .toString(),
        )
}

private fun receivedAgainst(
    purchaseOrderId: UUID,
    purchaseOrderNumber: String,
    vendorId: UUID,
    warehouseId: UUID,
) = ReceivedAgainst(
    PurchaseOrderId.of(purchaseOrderId),
    purchaseOrderNumber,
    VendorId.of(vendorId),
    WarehouseId.of(warehouseId),
)
