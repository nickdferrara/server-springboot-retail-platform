package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.LateBillRepository
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.LateBill
import com.retailplatform.payables.domain.LateBillDetails
import com.retailplatform.payables.domain.LateBillId
import com.retailplatform.payables.domain.LateBillStatus
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

@Table(schema = "payables", name = "direct_delivery_late_bill")
data class LateBillEntity(
    @Id val id: UUID,
    val vendorId: UUID,
    val storeId: UUID,
    val ticketNumber: String,
    val vendorInvoiceNumber: String,
    val billDate: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val recordedBy: String,
    val recordedOn: LocalDate,
    val status: String,
    val settledOn: LocalDate?,
    val settledBy: String?,
    val settlementReason: String?,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface LateBillJdbcRepository : CrudRepository<LateBillEntity, UUID> {
    @Query(
        "SELECT * FROM payables.direct_delivery_late_bill WHERE vendor_id = :vendorId " +
            "AND (NOT :openOnly OR status = 'OPEN') ORDER BY recorded_on, id LIMIT :limit OFFSET :offset",
    )
    fun findByVendor(
        vendorId: UUID,
        openOnly: Boolean,
        limit: Int,
        offset: Long,
    ): List<LateBillEntity>

    @Query(
        "SELECT count(*) FROM payables.direct_delivery_late_bill WHERE vendor_id = :vendorId " +
            "AND (NOT :openOnly OR status = 'OPEN')",
    )
    fun countByVendor(
        vendorId: UUID,
        openOnly: Boolean,
    ): Long

    fun existsByVendorIdAndVendorInvoiceNumber(
        vendorId: UUID,
        vendorInvoiceNumber: String,
    ): Boolean
}

@Repository
class JdbcLateBillRepository(
    private val lateBills: LateBillJdbcRepository,
) : LateBillRepository {
    override fun save(lateBill: LateBill) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit =
            if (lateBill.version == 0) AuditMetadata() else lateBills.findById(lateBill.id.value).orElseThrow().audit
        val details = lateBill.details
        val settlement = lateBill.status.settlement()
        lateBills.save(
            LateBillEntity(
                id = lateBill.id.value,
                vendorId = details.ticket.vendorId.value,
                storeId = details.ticket.storeId.value,
                ticketNumber = details.ticket.ticketNumber,
                vendorInvoiceNumber = details.number.value,
                billDate = details.billDate.value,
                amount = details.amount.amount,
                currency = details.amount.currency.currencyCode,
                recordedBy = lateBill.recordedBy,
                recordedOn = lateBill.recordedOn.value,
                status = lateBill.status.code,
                settledOn = settlement?.first?.value,
                settledBy = settlement?.second?.approvedBy,
                settlementReason = settlement?.second?.reason,
                version = lateBill.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: LateBillId): LateBill? = lateBills.findById(id.value).getOrNull()?.toDomain()

    override fun exists(
        vendorId: VendorId,
        number: VendorInvoiceNumber,
    ): Boolean = lateBills.existsByVendorIdAndVendorInvoiceNumber(vendorId.value, number.value)

    override fun findByVendor(
        vendorId: VendorId,
        openOnly: Boolean,
        query: PageQuery,
    ): Page<LateBill> =
        Page(
            lateBills.findByVendor(vendorId.value, openOnly, query.size, query.offset).map { it.toDomain() },
            query.page,
            query.size,
            lateBills.countByVendor(vendorId.value, openOnly),
        )

    /** The day a status settled the bill and who decided; `null` while it waits. */
    private fun LateBillStatus.settlement(): Pair<BusinessDate, MatchOverride>? =
        when (this) {
            is LateBillStatus.Paid -> settledOn to decision
            is LateBillStatus.Rejected -> settledOn to decision
            LateBillStatus.Open -> null
        }

    private fun LateBillEntity.toDomain() =
        LateBill(
            id = LateBillId.of(id),
            details =
                LateBillDetails(
                    BilledTicket(VendorId.of(vendorId), StoreId.of(storeId), ticketNumber),
                    VendorInvoiceNumber.of(vendorInvoiceNumber),
                    BusinessDate(billDate),
                    Money.of(amount, Currency.getInstance(currency)),
                ),
            recordedBy = recordedBy,
            recordedOn = BusinessDate(recordedOn),
            status = status(),
            version = version,
        )

    private fun LateBillEntity.status(): LateBillStatus {
        if (settledOn == null || settledBy == null || settlementReason == null) return LateBillStatus.Open
        val decision = MatchOverride.of(settledBy, settlementReason)
        return if (status == PAID) {
            LateBillStatus.Paid(BusinessDate(settledOn), decision)
        } else {
            LateBillStatus.Rejected(BusinessDate(settledOn), decision)
        }
    }

    private companion object {
        const val PAID = "PAID"
    }
}
