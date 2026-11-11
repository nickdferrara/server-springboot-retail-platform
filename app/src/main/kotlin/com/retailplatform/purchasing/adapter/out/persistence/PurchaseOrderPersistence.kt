package com.retailplatform.purchasing.adapter.out.persistence

import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.purchasing.application.PurchaseOrderNumbers
import com.retailplatform.purchasing.application.PurchaseOrderRepository
import com.retailplatform.purchasing.domain.Approval
import com.retailplatform.purchasing.domain.PurchaseOrder
import com.retailplatform.purchasing.domain.PurchaseOrderLine
import com.retailplatform.purchasing.domain.PurchaseOrderNumber
import com.retailplatform.purchasing.domain.PurchaseOrderState
import com.retailplatform.purchasing.domain.PurchaseOrderTerms
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "purchasing", name = "purchase_order")
data class PurchaseOrderEntity(
    @Id val id: UUID,
    val number: String,
    val vendorId: UUID,
    val warehouseId: UUID,
    val buyer: String,
    val expectedOn: LocalDate,
    val status: String,
    val approvedBy: String?,
    val approvedOn: LocalDate?,
    val sentOn: LocalDate?,
    val cancelledOn: LocalDate?,
    val cancelReason: String?,
    @MappedCollection(idColumn = "purchase_order_id", keyColumn = "position") val lines: List<PurchaseOrderLineEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "purchasing", name = "purchase_order_line")
data class PurchaseOrderLineEntity(
    val itemId: UUID,
    val vendorSku: String,
    val casePack: Int,
    val cases: Int,
    val caseCost: BigDecimal,
    val currency: String,
)

interface PurchaseOrderJdbcRepository :
    CrudRepository<PurchaseOrderEntity, UUID>,
    PagingAndSortingRepository<PurchaseOrderEntity, UUID>

@Repository
class JdbcPurchaseOrderRepository(
    private val orders: PurchaseOrderJdbcRepository,
) : PurchaseOrderRepository {
    override fun save(order: PurchaseOrder) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit = if (order.version == 0) AuditMetadata() else orders.findById(order.id.value).orElseThrow().audit
        orders.save(order.toEntity(audit))
    }

    override fun findById(id: PurchaseOrderId): PurchaseOrder? = orders.findById(id.value).getOrNull()?.toDomain()

    override fun findPage(query: PageQuery): Page<PurchaseOrder> {
        // Numbers are handed out in order and identifiers are time-ordered, so the newest order comes first.
        val page = orders.findAll(PageRequest.of(query.page, query.size, Sort.by(Sort.Direction.DESC, "id")))
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun PurchaseOrder.toEntity(audit: AuditMetadata): PurchaseOrderEntity {
        val cancelled = state as? PurchaseOrderState.Cancelled
        return PurchaseOrderEntity(
            id = id.value,
            number = number.value,
            vendorId = vendorId.value,
            warehouseId = terms.warehouseId.value,
            buyer = buyer,
            expectedOn = terms.expectedOn.value,
            status = state.code,
            approvedBy = state.approval?.approvedBy,
            approvedOn = state.approval?.approvedOn?.value,
            sentOn = state.sentOn?.value,
            cancelledOn = cancelled?.cancelledOn?.value,
            cancelReason = cancelled?.reason,
            lines =
                lines.map {
                    PurchaseOrderLineEntity(
                        itemId = it.itemId.value,
                        vendorSku = it.vendorSku,
                        casePack = it.casePack,
                        cases = it.cases,
                        caseCost = it.caseCost.amount,
                        currency = it.caseCost.currency.currencyCode,
                    )
                },
            version = version,
            audit = audit,
        )
    }

    private fun PurchaseOrderEntity.toDomain(): PurchaseOrder =
        PurchaseOrder.rehydrate(
            id = PurchaseOrderId.of(id),
            number = PurchaseOrderNumber.of(number),
            vendorId = VendorId.of(vendorId),
            buyer = buyer,
            terms =
                PurchaseOrderTerms(
                    warehouseId = WarehouseId.of(warehouseId),
                    expectedOn = BusinessDate(expectedOn),
                    lines =
                        lines.map {
                            PurchaseOrderLine(
                                itemId = ItemId.of(it.itemId),
                                vendorSku = it.vendorSku,
                                casePack = it.casePack,
                                cases = it.cases,
                                caseCost = Money.of(it.caseCost, Currency.getInstance(it.currency)),
                            )
                        },
                ),
            state = state(),
            version = version,
        )

    private fun PurchaseOrderEntity.state(): PurchaseOrderState {
        val approval =
            if (approvedBy != null &&
                approvedOn != null
            ) {
                Approval(approvedBy, BusinessDate(approvedOn))
            } else {
                null
            }
        val reached =
            when {
                approval == null -> PurchaseOrderState.Draft
                sentOn == null -> PurchaseOrderState.Approved(approval)
                else -> PurchaseOrderState.Sent(approval, BusinessDate(sentOn))
            }
        return if (cancelledOn != null && cancelReason != null) {
            PurchaseOrderState.Cancelled(BusinessDate(cancelledOn), cancelReason, reached)
        } else {
            reached
        }
    }
}

@Repository
class SequencePurchaseOrderNumbers(
    private val jdbc: JdbcClient,
) : PurchaseOrderNumbers {
    override fun next(): PurchaseOrderNumber =
        PurchaseOrderNumber.of(
            jdbc
                .sql("SELECT nextval('purchasing.purchase_order_number_seq')")
                .query(Long::class.java)
                .single()
                .toString(),
        )
}
