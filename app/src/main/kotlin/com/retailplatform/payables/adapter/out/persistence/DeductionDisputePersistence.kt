package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.DeductionDisputeRepository
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeductionDispute
import com.retailplatform.payables.domain.DeductionDisputeId
import com.retailplatform.payables.domain.DeductionNoticeId
import com.retailplatform.payables.domain.DeductionNoticeNumber
import com.retailplatform.payables.domain.DisputeClaim
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
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface DeductionDisputeJdbcRepository : CrudRepository<DeductionDisputeEntity, UUID> {
    fun findByDeductionNoticeId(deductionNoticeId: UUID): DeductionDisputeEntity?

    fun findByVendorIdAndStoreIdAndTicketNumber(
        vendorId: UUID,
        storeId: UUID,
        ticketNumber: String,
    ): DeductionDisputeEntity?

    fun existsByVendorIdAndVendorReference(
        vendorId: UUID,
        vendorReference: String,
    ): Boolean

    @Query(
        "SELECT * FROM payables.deduction_dispute WHERE vendor_id = :vendorId " +
            "AND (NOT :openOnly OR status = 'OPEN') ORDER BY recorded_on, id LIMIT :limit OFFSET :offset",
    )
    fun findByVendor(
        vendorId: UUID,
        openOnly: Boolean,
        limit: Int,
        offset: Long,
    ): List<DeductionDisputeEntity>

    @Query(
        "SELECT count(*) FROM payables.deduction_dispute WHERE vendor_id = :vendorId " +
            "AND (NOT :openOnly OR status = 'OPEN')",
    )
    fun countByVendor(
        vendorId: UUID,
        openOnly: Boolean,
    ): Long
}

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
                version = dispute.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: DeductionDisputeId): DeductionDispute? =
        disputes.findById(id.value).getOrNull()?.toDomain()

    override fun findByNotice(noticeId: DeductionNoticeId): DeductionDispute? =
        disputes.findByDeductionNoticeId(noticeId.value)?.toDomain()

    override fun findByTicket(ticket: BilledTicket): DeductionDispute? =
        disputes
            .findByVendorIdAndStoreIdAndTicketNumber(ticket.vendorId.value, ticket.storeId.value, ticket.ticketNumber)
            ?.toDomain()

    override fun existsReference(
        vendorId: VendorId,
        reference: VendorInvoiceNumber,
    ): Boolean = disputes.existsByVendorIdAndVendorReference(vendorId.value, reference.value)

    override fun findByVendor(
        vendorId: VendorId,
        openOnly: Boolean,
        query: PageQuery,
    ): Page<DeductionDispute> =
        Page(
            disputes.findByVendor(vendorId.value, openOnly, query.size, query.offset).map { it.toDomain() },
            query.page,
            query.size,
            disputes.countByVendor(vendorId.value, openOnly),
        )

    /** The day a status decided the dispute and who decided; `null` while it waits. */
    private fun DisputeStatus.decision(): Pair<BusinessDate, MatchOverride>? =
        when (this) {
            is DisputeStatus.Upheld -> decidedOn to decision
            is DisputeStatus.Conceded -> decidedOn to decision
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
        )
    }

    /** The decision columns are filled together, so the day it was decided says whether it was. */
    private fun DeductionDisputeEntity.status(money: Currency): DisputeStatus {
        val day = decidedOn ?: return DisputeStatus.Open
        val decision = MatchOverride.of(checkNotNull(decidedBy), checkNotNull(decisionReason))
        return conceded
            ?.let { DisputeStatus.Conceded(BusinessDate(day), decision, Money.of(it, money)) }
            ?: DisputeStatus.Upheld(BusinessDate(day), decision)
    }
}
