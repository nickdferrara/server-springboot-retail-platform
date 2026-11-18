package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.DeductionDisputeRepository
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeductionDispute
import com.retailplatform.payables.domain.DeductionDisputeId
import com.retailplatform.payables.domain.DeductionNoticeId
import com.retailplatform.payables.domain.DeductionNoticeNumber
import com.retailplatform.payables.domain.DisputeClaim
import com.retailplatform.payables.domain.DisputeReopening
import com.retailplatform.payables.domain.DisputeStatus
import com.retailplatform.payables.domain.DisputedDeduction
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.jdbc.repository.query.Query
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

@Table(schema = "payables", name = "deduction_dispute")
data class DeductionDisputeEntity(
    @Id val id: UUID,
    val deductionNoticeId: UUID,
    val deductionNoticeNumber: String,
    val vendorId: UUID,
    val storeId: UUID,
    val ticketNumber: String,
    val deduction: BigDecimal,
    val currency: String,
    val deductedBy: String,
    val receivedOn: LocalDate,
    val vendorReference: String,
    val claim: String,
    val recordedBy: String,
    val recordedOn: LocalDate,
    val status: String,
    val decidedOn: LocalDate?,
    val decidedBy: String?,
    val decisionReason: String?,
    val conceded: BigDecimal?,
    @MappedCollection(idColumn = "deduction_dispute_id", keyColumn = "position")
    val reopenings: List<DisputeReopeningEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "payables", name = "deduction_dispute_reopening")
data class DisputeReopeningEntity(
    val decidedOn: LocalDate,
    val decidedBy: String,
    val decisionReason: String,
    val reopenedOn: LocalDate,
    val reopenedBy: String,
    val reason: String,
)

interface DeductionDisputeJdbcRepository : CrudRepository<DeductionDisputeEntity, UUID> {
    @Query(
        "SELECT id FROM payables.deduction_dispute WHERE deduction_notice_id = :deductionNoticeId " +
            "AND status <> 'WITHDRAWN'",
    )
    fun findStandingIdByNotice(deductionNoticeId: UUID): UUID?

    @Query(
        "SELECT id FROM payables.deduction_dispute WHERE vendor_id = :vendorId AND store_id = :storeId " +
            "AND ticket_number = :ticketNumber AND status <> 'WITHDRAWN'",
    )
    fun findStandingIdByTicket(
        vendorId: UUID,
        storeId: UUID,
        ticketNumber: String,
    ): UUID?

    @Query(
        "SELECT count(*) > 0 FROM payables.deduction_dispute WHERE vendor_id = :vendorId " +
            "AND vendor_reference = :vendorReference AND status <> 'WITHDRAWN'",
    )
    fun existsStandingReference(
        vendorId: UUID,
        vendorReference: String,
    ): Boolean

    @Query(
        "SELECT id FROM payables.deduction_dispute WHERE vendor_id = :vendorId " +
            "AND (NOT :openOnly OR status = 'OPEN') ORDER BY recorded_on, id LIMIT :limit OFFSET :offset",
    )
    fun findIdsByVendor(
        vendorId: UUID,
        openOnly: Boolean,
        limit: Int,
        offset: Long,
    ): List<UUID>

    @Query(
        "SELECT count(*) FROM payables.deduction_dispute WHERE vendor_id = :vendorId " +
            "AND (NOT :openOnly OR status = 'OPEN')",
    )
    fun countByVendor(
        vendorId: UUID,
        openOnly: Boolean,
    ): Long
}

private fun DisputeReopening.toEntity() =
    DisputeReopeningEntity(
        decidedOn = decidedOn.value,
        decidedBy = upheld.approvedBy,
        decisionReason = upheld.reason,
        reopenedOn = reopenedOn.value,
        reopenedBy = decision.approvedBy,
        reason = decision.reason,
    )

private fun DisputeReopeningEntity.toDomain() =
    DisputeReopening(
        BusinessDate(decidedOn),
        MatchOverride.of(decidedBy, decisionReason),
        BusinessDate(reopenedOn),
        MatchOverride.of(reopenedBy, reason),
    )

@Repository
class JdbcDeductionDisputeRepository(
    private val disputes: DeductionDisputeJdbcRepository,
) : DeductionDisputeRepository {
    override fun save(dispute: DeductionDispute) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit =
            if (dispute.version == 0) AuditMetadata() else disputes.findById(dispute.id.value).orElseThrow().audit
        val deduction = dispute.deduction
        val decision = dispute.status.decision()
        disputes.save(
            DeductionDisputeEntity(
                id = dispute.id.value,
                deductionNoticeId = deduction.noticeId.value,
                deductionNoticeNumber = deduction.noticeNumber.value,
                vendorId = deduction.ticket.vendorId.value,
                storeId = deduction.ticket.storeId.value,
                ticketNumber = deduction.ticket.ticketNumber,
                deduction = deduction.amount.amount,
                currency = deduction.amount.currency.currencyCode,
                deductedBy = deduction.deductedBy,
                receivedOn = dispute.claim.receivedOn.value,
                vendorReference = dispute.claim.reference.value,
                claim = dispute.claim.statement,
                recordedBy = dispute.recordedBy,
                recordedOn = dispute.recordedOn.value,
                status = dispute.status.code,
                decidedOn = decision?.first?.value,
                decidedBy = decision?.second?.approvedBy,
                decisionReason = decision?.second?.reason,
                conceded = (dispute.status as? DisputeStatus.Conceded)?.amount?.amount,
                reopenings = dispute.reopenings.map { it.toEntity() },
                version = dispute.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: DeductionDisputeId): DeductionDispute? =
        disputes.findById(id.value).getOrNull()?.toDomain()

    override fun findByNotice(noticeId: DeductionNoticeId): DeductionDispute? =
        disputes.findStandingIdByNotice(noticeId.value)?.let(::load)

    override fun findByTicket(ticket: BilledTicket): DeductionDispute? =
        disputes
            .findStandingIdByTicket(ticket.vendorId.value, ticket.storeId.value, ticket.ticketNumber)
            ?.let(::load)

    override fun existsReference(
        vendorId: VendorId,
        reference: VendorInvoiceNumber,
    ): Boolean = disputes.existsStandingReference(vendorId.value, reference.value)

    override fun findByVendor(
        vendorId: VendorId,
        openOnly: Boolean,
        query: PageQuery,
    ): Page<DeductionDispute> =
        Page(
            disputes.findIdsByVendor(vendorId.value, openOnly, query.size, query.offset).mapNotNull(::load),
            query.page,
            query.size,
            disputes.countByVendor(vendorId.value, openOnly),
        )

    private fun load(id: UUID): DeductionDispute? = disputes.findById(id).getOrNull()?.toDomain()

    /**
     * The day a status ended the dispute's wait and who ended it; `null` while it waits. A withdrawal is kept in
     * the decision columns, since a withdrawn dispute was never decided.
     */
    private fun DisputeStatus.decision(): Pair<BusinessDate, MatchOverride>? =
        when (this) {
            is DisputeStatus.Upheld -> decidedOn to decision
            is DisputeStatus.Conceded -> decidedOn to decision
            is DisputeStatus.Withdrawn -> withdrawnOn to decision
            DisputeStatus.Open -> null
        }

    private fun DeductionDisputeEntity.toDomain(): DeductionDispute {
        val money = Currency.getInstance(currency)
        return DeductionDispute(
            id = DeductionDisputeId.of(id),
            deduction =
                DisputedDeduction(
                    DeductionNoticeId.of(deductionNoticeId),
                    DeductionNoticeNumber.of(deductionNoticeNumber),
                    BilledTicket(VendorId.of(vendorId), StoreId.of(storeId), ticketNumber),
                    Money.of(deduction, money),
                    deductedBy,
                ),
            claim = DisputeClaim.of(BusinessDate(receivedOn), VendorInvoiceNumber.of(vendorReference), claim),
            recordedBy = recordedBy,
            recordedOn = BusinessDate(recordedOn),
            status = status(money),
            version = version,
            reopenings = reopenings.map { it.toDomain() },
        )
    }

    /** The decision columns are filled together, so the day it was decided says whether it still waits. */
    private fun DeductionDisputeEntity.status(money: Currency): DisputeStatus {
        val day = decidedOn ?: return DisputeStatus.Open
        val decision = MatchOverride.of(checkNotNull(decidedBy), checkNotNull(decisionReason))
        val amount = conceded
        return when {
            status == "WITHDRAWN" -> DisputeStatus.Withdrawn(BusinessDate(day), decision)
            amount != null -> DisputeStatus.Conceded(BusinessDate(day), decision, Money.of(amount, money))
            else -> DisputeStatus.Upheld(BusinessDate(day), decision)
        }
    }
}
