package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.DeliveryAdjustmentRepository
import com.retailplatform.payables.application.DirectDeliveryAccrualRepository
import com.retailplatform.payables.application.VendorCreditRepository
import com.retailplatform.payables.domain.AdjustmentResolution
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.DeliveryAdjustment
import com.retailplatform.payables.domain.DeliveryAdjustmentId
import com.retailplatform.payables.domain.DeliveryAdjustmentKind
import com.retailplatform.payables.domain.DirectDeliveryAccrual
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.PaymentRunId
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.VendorCredit
import com.retailplatform.payables.domain.VendorCreditId
import com.retailplatform.payables.domain.VendorCreditStatus
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PaymentId
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
import kotlin.jvm.optionals.getOrNull

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
    val relievedOn: LocalDate?,
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
    val status: String,
    val paymentRunId: UUID?,
    val paymentId: UUID?,
    val appliedOn: LocalDate?,
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
        "SELECT * FROM payables.direct_delivery_accrual WHERE vendor_id = :vendorId AND amount > 0 " +
            "ORDER BY received_on, delivery_id LIMIT :limit OFFSET :offset",
    )
    fun findByVendor(
        vendorId: UUID,
        limit: Int,
        offset: Long,
    ): List<DirectDeliveryAccrualEntity>

    @Query("SELECT count(*) FROM payables.direct_delivery_accrual WHERE vendor_id = :vendorId AND amount > 0")
    fun countByVendor(vendorId: UUID): Long

    fun findByVendorIdAndStoreIdAndTicketNumber(
        vendorId: UUID,
        storeId: UUID,
        ticketNumber: String,
    ): DirectDeliveryAccrualEntity?
}

interface VendorCreditJdbcRepository : CrudRepository<VendorCreditEntity, UUID> {
    @Query(
        "SELECT * FROM payables.vendor_credit WHERE delivery_id = :deliveryId AND status = 'OPEN' " +
            "ORDER BY id LIMIT 1",
    )
    fun findOpenByDelivery(deliveryId: UUID): VendorCreditEntity?

    // The row locks keep a second payment run, proposed at the same moment, from deducting the same credits.
    @Query(
        "SELECT * FROM payables.vendor_credit WHERE status = 'OPEN' AND amount > 0 AND vendor_id IN (:vendorIds) " +
            "ORDER BY credited_on, id FOR UPDATE",
    )
    fun findOpen(vendorIds: Collection<UUID>): List<VendorCreditEntity>

    @Query(
        "SELECT * FROM payables.vendor_credit WHERE vendor_id = :vendorId AND amount > 0 " +
            "ORDER BY credited_on, id LIMIT :limit OFFSET :offset",
    )
    fun findByVendor(
        vendorId: UUID,
        limit: Int,
        offset: Long,
    ): List<VendorCreditEntity>

    @Query("SELECT count(*) FROM payables.vendor_credit WHERE vendor_id = :vendorId AND amount > 0")
    fun countByVendor(vendorId: UUID): Long
}

internal fun List<TicketAmountLine>.toEntities() =
    map {
        TicketAmountLineEntity(it.itemId.value, it.units, it.unitCost.amount)
    }

internal fun TicketAmountLineEntity.toDomain(currency: Currency) =
    TicketAmountLine.of(ItemId.of(itemId), units, Money.of(unitCost, currency))

@Repository
class JdbcDirectDeliveryAccrualRepository(
    private val accruals: DirectDeliveryAccrualJdbcRepository,
) : DirectDeliveryAccrualRepository {
    override fun save(accrual: DirectDeliveryAccrual) {
        val ticket = accrual.ticket
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit =
            if (accrual.version == 0) {
                AuditMetadata()
            } else {
                accruals.findById(ticket.deliveryId.value).orElseThrow().audit
            }
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
                relievedOn = accrual.relievedOn?.value,
                lines = accrual.lines.toEntities().map(::DirectDeliveryAccrualLineEntity),
                version = accrual.version,
                audit = audit,
            ),
        )
    }

    override fun existsFor(deliveryId: DirectDeliveryId): Boolean = accruals.existsById(deliveryId.value)

    override fun findByDelivery(deliveryId: DirectDeliveryId): DirectDeliveryAccrual? =
        accruals.findById(deliveryId.value).getOrNull()?.toDomain()

    override fun findFor(ticket: BilledTicket): DirectDeliveryAccrual? =
        accruals
            .findByVendorIdAndStoreIdAndTicketNumber(ticket.vendorId.value, ticket.storeId.value, ticket.ticketNumber)
            ?.toDomain()

    override fun findByVendor(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<DirectDeliveryAccrual> =
        Page(
            accruals.findByVendor(vendorId.value, query.size, query.offset).map { it.toDomain() },
            query.page,
            query.size,
            accruals.countByVendor(vendorId.value),
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
            currency = money,
            lines = lines.map { it.line.toDomain(money) },
            relievedOn = relievedOn?.let(::BusinessDate),
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
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit = if (credit.version == 0) AuditMetadata() else credits.findById(credit.id.value).orElseThrow().audit
        val status = credit.status
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
                status = status.code,
                paymentRunId =
                    when (status) {
                        is VendorCreditStatus.Scheduled -> status.paymentRunId.value
                        is VendorCreditStatus.Applied -> status.paymentRunId.value
                        VendorCreditStatus.Open -> null
                    },
                paymentId = (status as? VendorCreditStatus.Applied)?.paymentId?.value,
                appliedOn = (status as? VendorCreditStatus.Applied)?.appliedOn?.value,
                lines = credit.lines.toEntities().map(::VendorCreditLineEntity),
                version = credit.version,
                audit = audit,
            ),
        )
    }

    override fun findOpenByDelivery(deliveryId: DirectDeliveryId): VendorCredit? =
        credits.findOpenByDelivery(deliveryId.value)?.toDomain()

    override fun findByIds(ids: List<VendorCreditId>): List<VendorCredit> =
        credits.findAllById(ids.map { it.value }).map { it.toDomain() }

    override fun findOpen(vendorIds: Set<VendorId>): List<VendorCredit> =
        if (vendorIds.isEmpty()) emptyList() else credits.findOpen(vendorIds.map { it.value }).map { it.toDomain() }

    override fun findByVendor(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<VendorCredit> =
        Page(
            credits.findByVendor(vendorId.value, query.size, query.offset).map { it.toDomain() },
            query.page,
            query.size,
            credits.countByVendor(vendorId.value),
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
            currency = money,
            lines = lines.map { it.line.toDomain(money) },
            status = status(),
            version = version,
        )
    }

    private fun VendorCreditEntity.status(): VendorCreditStatus =
        when {
            paymentRunId == null -> {
                VendorCreditStatus.Open
            }

            paymentId != null && appliedOn != null -> {
                VendorCreditStatus.Applied(
                    PaymentRunId.of(paymentRunId),
                    PaymentId.of(paymentId),
                    BusinessDate(appliedOn),
                )
            }

            else -> {
                VendorCreditStatus.Scheduled(PaymentRunId.of(paymentRunId))
            }
        }
}

@Table(schema = "payables", name = "direct_delivery_adjustment")
data class DeliveryAdjustmentEntity(
    @Id val id: UUID,
    val deliveryId: UUID,
    val receiptNumber: String,
    val vendorId: UUID,
    val storeId: UUID,
    val ticketNumber: String,
    val recordedOn: LocalDate,
    val kind: String,
    val amount: BigDecimal,
    val currency: String,
    val status: String,
    val resolvedOn: LocalDate?,
    val resolvedBy: String?,
    val resolutionReason: String?,
    val vendorReference: String?,
    val referenceDate: LocalDate?,
    @MappedCollection(idColumn = "direct_delivery_adjustment_id", keyColumn = "position")
    val lines: List<DeliveryAdjustmentLineEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "payables", name = "direct_delivery_adjustment_line")
data class DeliveryAdjustmentLineEntity(
    @Embedded.Empty val line: TicketAmountLineEntity,
)

interface DeliveryAdjustmentJdbcRepository : CrudRepository<DeliveryAdjustmentEntity, UUID> {
    @Query(
        "SELECT id FROM payables.direct_delivery_adjustment WHERE vendor_id = :vendorId " +
            "AND (NOT :openOnly OR status = 'OPEN') ORDER BY recorded_on, id LIMIT :limit OFFSET :offset",
    )
    fun findIdsByVendor(
        vendorId: UUID,
        openOnly: Boolean,
        limit: Int,
        offset: Long,
    ): List<UUID>

    fun findByVendorIdAndStoreIdAndTicketNumber(
        vendorId: UUID,
        storeId: UUID,
        ticketNumber: String,
    ): List<DeliveryAdjustmentEntity>

    @Query(
        "SELECT count(*) FROM payables.direct_delivery_adjustment WHERE vendor_id = :vendorId " +
            "AND (NOT :openOnly OR status = 'OPEN')",
    )
    fun countByVendor(
        vendorId: UUID,
        openOnly: Boolean,
    ): Long

    fun existsByVendorIdAndVendorReference(
        vendorId: UUID,
        vendorReference: String,
    ): Boolean
}

@Repository
class JdbcDeliveryAdjustmentRepository(
    private val adjustments: DeliveryAdjustmentJdbcRepository,
) : DeliveryAdjustmentRepository {
    override fun save(adjustment: DeliveryAdjustment) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit =
            if (adjustment.version == 0) {
                AuditMetadata()
            } else {
                adjustments.findById(adjustment.id.value).orElseThrow().audit
            }
        val ticket = adjustment.ticket
        val resolution = adjustment.resolution
        val paid = resolution as? AdjustmentResolution.Paid
        adjustments.save(
            DeliveryAdjustmentEntity(
                id = adjustment.id.value,
                deliveryId = ticket.deliveryId.value,
                receiptNumber = ticket.receiptNumber,
                vendorId = ticket.vendorId.value,
                storeId = ticket.storeId.value,
                ticketNumber = ticket.ticketNumber,
                recordedOn = ticket.receivedOn.value,
                kind = adjustment.kind.name,
                amount = adjustment.amount.amount,
                currency = adjustment.amount.currency.currencyCode,
                status = adjustment.statusCode,
                resolvedOn = resolution?.resolvedOn?.value,
                resolvedBy = resolution?.decision?.approvedBy,
                resolutionReason = resolution?.decision?.reason,
                vendorReference = paid?.reference?.value,
                referenceDate = paid?.referenceDate?.value,
                lines = adjustment.lines.toEntities().map(::DeliveryAdjustmentLineEntity),
                version = adjustment.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: DeliveryAdjustmentId): DeliveryAdjustment? =
        adjustments.findById(id.value).getOrNull()?.toDomain()

    override fun existsReference(
        vendorId: VendorId,
        reference: VendorInvoiceNumber,
    ): Boolean = adjustments.existsByVendorIdAndVendorReference(vendorId.value, reference.value)

    override fun findByVendor(
        vendorId: VendorId,
        openOnly: Boolean,
        query: PageQuery,
    ): Page<DeliveryAdjustment> {
        // The page is found by identifier and then loaded whole, so each adjustment comes with its lines.
        val ids = adjustments.findIdsByVendor(vendorId.value, openOnly, query.size, query.offset)
        val found = adjustments.findAllById(ids).associateBy { it.id }
        return Page(
            ids.mapNotNull { found[it]?.toDomain() },
            query.page,
            query.size,
            adjustments.countByVendor(vendorId.value, openOnly),
        )
    }

    override fun findByTicket(ticket: BilledTicket): List<DeliveryAdjustment> =
        adjustments
            .findByVendorIdAndStoreIdAndTicketNumber(ticket.vendorId.value, ticket.storeId.value, ticket.ticketNumber)
            .map { it.toDomain() }

    private fun DeliveryAdjustmentEntity.toDomain() =
        DeliveryAdjustment(
            id = DeliveryAdjustmentId.of(id),
            ticket =
                DeliveredTicket(
                    DirectDeliveryId.of(deliveryId),
                    receiptNumber,
                    VendorId.of(vendorId),
                    StoreId.of(storeId),
                    ticketNumber,
                    BusinessDate(recordedOn),
                ),
            kind = DeliveryAdjustmentKind.valueOf(kind),
            amount = Money.of(amount, Currency.getInstance(currency)),
            resolution = resolution(),
            version = version,
            lines = lines.map { it.line.toDomain(Currency.getInstance(currency)) },
        )

    private fun DeliveryAdjustmentEntity.resolution(): AdjustmentResolution? {
        if (resolvedOn == null || resolvedBy == null || resolutionReason == null) return null
        val decision = MatchOverride.of(resolvedBy, resolutionReason)
        return if (vendorReference != null && referenceDate != null) {
            AdjustmentResolution.Paid(
                BusinessDate(resolvedOn),
                decision,
                VendorInvoiceNumber.of(vendorReference),
                BusinessDate(referenceDate),
            )
        } else {
            AdjustmentResolution.WrittenOff(BusinessDate(resolvedOn), decision)
        }
    }
}
