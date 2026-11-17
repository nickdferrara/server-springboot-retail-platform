package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.DeductionNoticeNumbers
import com.retailplatform.payables.application.DeductionNoticeRepository
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeductedInvoice
import com.retailplatform.payables.domain.DeductionLine
import com.retailplatform.payables.domain.DeductionNotice
import com.retailplatform.payables.domain.DeductionNoticeId
import com.retailplatform.payables.domain.DeductionNoticeNumber
import com.retailplatform.payables.domain.NoticeChannel
import com.retailplatform.payables.domain.NoticeSending
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "payables", name = "deduction_notice")
data class DeductionNoticeEntity(
    @Id val id: UUID,
    val number: String,
    val invoiceId: UUID,
    val vendorInvoiceNumber: String,
    val invoiceDate: LocalDate,
    val vendorId: UUID,
    val storeId: UUID,
    val ticketNumber: String,
    val raisedOn: LocalDate,
    val reason: String,
    val amount: BigDecimal,
    val currency: String,
    val sentOn: LocalDate?,
    val sentBy: String?,
    val sentTo: String?,
    val sentRecordedBy: String?,
    @MappedCollection(idColumn = "deduction_notice_id", keyColumn = "position")
    val lines: List<DeductionNoticeLineEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "payables", name = "deduction_notice_line")
data class DeductionNoticeLineEntity(
    val itemId: UUID,
    val unitsBilled: Int,
    val unitsReceived: Int,
    val unitCost: BigDecimal,
)

interface DeductionNoticeJdbcRepository : CrudRepository<DeductionNoticeEntity, UUID> {
    fun findByInvoiceId(invoiceId: UUID): DeductionNoticeEntity?

    @Query(
        "SELECT id FROM payables.deduction_notice WHERE vendor_id = :vendorId " +
            "AND (NOT :unsentOnly OR sent_on IS NULL) ORDER BY raised_on, id LIMIT :limit OFFSET :offset",
    )
    fun findIdsByVendor(
        vendorId: UUID,
        unsentOnly: Boolean,
        limit: Int,
        offset: Long,
    ): List<UUID>

    @Query(
        "SELECT count(*) FROM payables.deduction_notice WHERE vendor_id = :vendorId " +
            "AND (NOT :unsentOnly OR sent_on IS NULL)",
    )
    fun countByVendor(
        vendorId: UUID,
        unsentOnly: Boolean,
    ): Long
}

@Component
class SequenceDeductionNoticeNumbers(
    private val jdbc: JdbcClient,
) : DeductionNoticeNumbers {
    override fun next(): DeductionNoticeNumber =
        DeductionNoticeNumber.of(
            jdbc
                .sql("SELECT nextval('payables.deduction_notice_number_seq')")
                .query(Long::class.java)
                .single()
                .toString(),
        )
}

@Repository
class JdbcDeductionNoticeRepository(
    private val notices: DeductionNoticeJdbcRepository,
) : DeductionNoticeRepository {
    override fun save(notice: DeductionNotice) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit = if (notice.version == 0) AuditMetadata() else notices.findById(notice.id.value).orElseThrow().audit
        val invoice = notice.invoice
        val sending = notice.sending
        notices.save(
            DeductionNoticeEntity(
                id = notice.id.value,
                number = notice.number.value,
                invoiceId = invoice.invoiceId.value,
                vendorInvoiceNumber = invoice.number.value,
                invoiceDate = invoice.invoiceDate.value,
                vendorId = invoice.ticket.vendorId.value,
                storeId = invoice.ticket.storeId.value,
                ticketNumber = invoice.ticket.ticketNumber,
                raisedOn = notice.raisedOn.value,
                reason = notice.reason,
                amount = notice.amount.amount,
                currency = notice.amount.currency.currencyCode,
                sentOn = sending?.sentOn?.value,
                sentBy = sending?.channel?.name,
                sentTo = sending?.recipient,
                sentRecordedBy = sending?.recordedBy,
                lines =
                    notice.lines.map {
                        DeductionNoticeLineEntity(it.itemId.value, it.unitsBilled, it.unitsReceived, it.unitCost.amount)
                    },
                version = notice.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: DeductionNoticeId): DeductionNotice? = notices.findById(id.value).getOrNull()?.toDomain()

    override fun findByInvoice(invoiceId: InvoiceId): DeductionNotice? =
        notices.findByInvoiceId(invoiceId.value)?.toDomain()

    override fun findByVendor(
        vendorId: VendorId,
        unsentOnly: Boolean,
        query: PageQuery,
    ): Page<DeductionNotice> {
        // The page is found by identifier and then loaded whole, so each notice comes with its lines.
        val ids = notices.findIdsByVendor(vendorId.value, unsentOnly, query.size, query.offset)
        val found = notices.findAllById(ids).associateBy { it.id }
        return Page(
            ids.mapNotNull { found[it]?.toDomain() },
            query.page,
            query.size,
            notices.countByVendor(vendorId.value, unsentOnly),
        )
    }

    private fun DeductionNoticeEntity.toDomain(): DeductionNotice {
        val money = Currency.getInstance(currency)
        return DeductionNotice(
            id = DeductionNoticeId.of(id),
            number = DeductionNoticeNumber.of(number),
            invoice =
                DeductedInvoice(
                    InvoiceId.of(invoiceId),
                    VendorInvoiceNumber.of(vendorInvoiceNumber),
                    BusinessDate(invoiceDate),
                    BilledTicket(VendorId.of(vendorId), StoreId.of(storeId), ticketNumber),
                ),
            raisedOn = BusinessDate(raisedOn),
            reason = reason,
            lines =
                lines.map {
                    DeductionLine.of(
                        ItemId.of(it.itemId),
                        it.unitsBilled,
                        it.unitsReceived,
                        Money.of(it.unitCost, money),
                    )
                },
            sending = sending(),
            version = version,
        )
    }

    /** The sending columns are filled together, so the day it was sent says whether it was. */
    private fun DeductionNoticeEntity.sending(): NoticeSending? =
        sentOn?.let {
            NoticeSending.of(
                BusinessDate(it),
                NoticeChannel.valueOf(checkNotNull(sentBy)),
                checkNotNull(sentTo),
                checkNotNull(sentRecordedBy),
            )
        }
}
