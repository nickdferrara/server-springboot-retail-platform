package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.DirectDeliveryInvoiceRepository
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DirectDeliveryInvoice
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.TicketException
import com.retailplatform.payables.domain.TicketExceptionReason
import com.retailplatform.payables.domain.TicketInvoiceDetails
import com.retailplatform.payables.domain.TicketInvoiceStatus
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "payables", name = "direct_delivery_invoice")
data class DirectDeliveryInvoiceEntity(
    @Id val id: UUID,
    val vendorId: UUID,
    val storeId: UUID,
    val ticketNumber: String,
    val vendorInvoiceNumber: String,
    val invoiceDate: LocalDate,
    val currency: String,
    val enteredBy: String,
    val status: String,
    val matchedOn: LocalDate?,
    val overrideApprovedBy: String?,
    val overrideReason: String?,
    @MappedCollection(idColumn = "direct_delivery_invoice_id", keyColumn = "position")
    val lines: List<DirectDeliveryInvoiceLineEntity>,
    @MappedCollection(idColumn = "direct_delivery_invoice_id", keyColumn = "position")
    val exceptions: List<DirectDeliveryInvoiceExceptionEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "payables", name = "direct_delivery_invoice_line")
data class DirectDeliveryInvoiceLineEntity(
    @Embedded.Empty val line: TicketAmountLineEntity,
)

@Table(schema = "payables", name = "direct_delivery_invoice_exception")
data class DirectDeliveryInvoiceExceptionEntity(
    val itemId: UUID?,
    val reason: String,
    val detail: String,
)

interface DirectDeliveryInvoiceJdbcRepository :
    CrudRepository<DirectDeliveryInvoiceEntity, UUID>,
    PagingAndSortingRepository<DirectDeliveryInvoiceEntity, UUID> {
    fun existsByVendorIdAndStoreIdAndTicketNumber(
        vendorId: UUID,
        storeId: UUID,
        ticketNumber: String,
    ): Boolean

    fun findByVendorIdAndStoreIdAndTicketNumber(
        vendorId: UUID,
        storeId: UUID,
        ticketNumber: String,
    ): DirectDeliveryInvoiceEntity?

    fun existsByVendorIdAndVendorInvoiceNumber(
        vendorId: UUID,
        vendorInvoiceNumber: String,
    ): Boolean

    fun findByStatus(
        status: String,
        pageable: Pageable,
    ): org.springframework.data.domain.Page<DirectDeliveryInvoiceEntity>
}

@Repository
class JdbcDirectDeliveryInvoiceRepository(
    private val invoices: DirectDeliveryInvoiceJdbcRepository,
) : DirectDeliveryInvoiceRepository {
    override fun save(invoice: DirectDeliveryInvoice) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit =
            if (invoice.version == 0) AuditMetadata() else invoices.findById(invoice.id.value).orElseThrow().audit
        val status = invoice.status
        val override = (status as? TicketInvoiceStatus.Matched)?.override
        val ticket = invoice.details.ticket
        invoices.save(
            DirectDeliveryInvoiceEntity(
                id = invoice.id.value,
                vendorId = ticket.vendorId.value,
                storeId = ticket.storeId.value,
                ticketNumber = ticket.ticketNumber,
                vendorInvoiceNumber = invoice.details.number.value,
                invoiceDate = invoice.details.invoiceDate.value,
                currency = invoice.amount.currency.currencyCode,
                enteredBy = invoice.enteredBy,
                status = status.code,
                matchedOn = (status as? TicketInvoiceStatus.Matched)?.matchedOn?.value,
                overrideApprovedBy = override?.approvedBy,
                overrideReason = override?.reason,
                lines = invoice.lines.toEntities().map(::DirectDeliveryInvoiceLineEntity),
                exceptions =
                    (status as? TicketInvoiceStatus.Exception)?.exceptions.orEmpty().map {
                        DirectDeliveryInvoiceExceptionEntity(it.itemId?.value, it.reason.name, it.detail)
                    },
                version = invoice.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: InvoiceId): DirectDeliveryInvoice? = invoices.findById(id.value).getOrNull()?.toDomain()

    override fun existsFor(ticket: BilledTicket): Boolean =
        invoices.existsByVendorIdAndStoreIdAndTicketNumber(
            ticket.vendorId.value,
            ticket.storeId.value,
            ticket.ticketNumber,
        )

    override fun findFor(ticket: BilledTicket): DirectDeliveryInvoice? =
        invoices
            .findByVendorIdAndStoreIdAndTicketNumber(ticket.vendorId.value, ticket.storeId.value, ticket.ticketNumber)
            ?.toDomain()

    override fun exists(
        vendorId: VendorId,
        number: VendorInvoiceNumber,
    ): Boolean = invoices.existsByVendorIdAndVendorInvoiceNumber(vendorId.value, number.value)

    override fun findPage(
        exceptionsOnly: Boolean,
        query: PageQuery,
    ): Page<DirectDeliveryInvoice> {
        // Identifiers are time-ordered, so the newest invoice comes first.
        val newestFirst = PageRequest.of(query.page, query.size, Sort.by(Sort.Direction.DESC, "id"))
        val page = if (exceptionsOnly) invoices.findByStatus(EXCEPTION, newestFirst) else invoices.findAll(newestFirst)
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun DirectDeliveryInvoiceEntity.toDomain(): DirectDeliveryInvoice {
        val money = Currency.getInstance(currency)
        return DirectDeliveryInvoice.rehydrate(
            id = InvoiceId.of(id),
            details =
                TicketInvoiceDetails(
                    ticket = BilledTicket(VendorId.of(vendorId), StoreId.of(storeId), ticketNumber),
                    number = VendorInvoiceNumber.of(vendorInvoiceNumber),
                    invoiceDate = BusinessDate(invoiceDate),
                    lines = lines.map { it.line.toDomain(money) },
                ),
            enteredBy = enteredBy,
            status =
                matchedOn?.let { TicketInvoiceStatus.Matched(BusinessDate(it), override()) }
                    ?: TicketInvoiceStatus.Exception(
                        exceptions.map {
                            TicketException(
                                it.itemId?.let(ItemId::of),
                                TicketExceptionReason.valueOf(it.reason),
                                it.detail,
                            )
                        },
                    ),
            version = version,
        )
    }

    private fun DirectDeliveryInvoiceEntity.override(): MatchOverride? =
        if (overrideApprovedBy != null && overrideReason != null) {
            MatchOverride.of(overrideApprovedBy, overrideReason)
        } else {
            null
        }

    private companion object {
        const val EXCEPTION = "EXCEPTION"
    }
}
