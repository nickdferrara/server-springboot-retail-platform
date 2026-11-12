package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.VendorInvoiceRepository
import com.retailplatform.payables.domain.InvoiceDetails
import com.retailplatform.payables.domain.InvoiceLine
import com.retailplatform.payables.domain.InvoiceStatus
import com.retailplatform.payables.domain.MatchException
import com.retailplatform.payables.domain.MatchExceptionReason
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.VendorInvoice
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "payables", name = "vendor_invoice")
data class VendorInvoiceEntity(
    @Id val id: UUID,
    val vendorId: UUID,
    val vendorInvoiceNumber: String,
    val invoiceDate: LocalDate,
    val purchaseOrderId: UUID,
    val enteredBy: String,
    val status: String,
    val matchedOn: LocalDate?,
    val overrideApprovedBy: String?,
    val overrideReason: String?,
    @MappedCollection(idColumn = "vendor_invoice_id", keyColumn = "position") val lines: List<InvoiceLineEntity>,
    @MappedCollection(idColumn = "vendor_invoice_id", keyColumn = "position")
    val exceptions: List<InvoiceExceptionEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "payables", name = "vendor_invoice_line")
data class InvoiceLineEntity(
    val purchaseOrderLineNumber: Int,
    val cases: Int,
    val caseCost: BigDecimal,
    val currency: String,
)

@Table(schema = "payables", name = "vendor_invoice_exception")
data class InvoiceExceptionEntity(
    val purchaseOrderLineNumber: Int,
    val reason: String,
    val detail: String,
)

interface VendorInvoiceJdbcRepository :
    CrudRepository<VendorInvoiceEntity, UUID>,
    PagingAndSortingRepository<VendorInvoiceEntity, UUID> {
    fun existsByVendorIdAndVendorInvoiceNumber(
        vendorId: UUID,
        vendorInvoiceNumber: String,
    ): Boolean

    fun findByStatus(
        status: String,
        pageable: Pageable,
    ): org.springframework.data.domain.Page<VendorInvoiceEntity>
}

@Repository
class JdbcVendorInvoiceRepository(
    private val invoices: VendorInvoiceJdbcRepository,
) : VendorInvoiceRepository {
    override fun save(invoice: VendorInvoice) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit =
            if (invoice.version == 0) AuditMetadata() else invoices.findById(invoice.id.value).orElseThrow().audit
        val status = invoice.status
        val override = (status as? InvoiceStatus.Matched)?.override
        invoices.save(
            VendorInvoiceEntity(
                id = invoice.id.value,
                vendorId = invoice.details.vendorId.value,
                vendorInvoiceNumber = invoice.details.number.value,
                invoiceDate = invoice.details.invoiceDate.value,
                purchaseOrderId = invoice.details.purchaseOrderId.value,
                enteredBy = invoice.enteredBy,
                status = status.code,
                matchedOn = (status as? InvoiceStatus.Matched)?.matchedOn?.value,
                overrideApprovedBy = override?.approvedBy,
                overrideReason = override?.reason,
                lines =
                    invoice.lines.map {
                        InvoiceLineEntity(
                            it.purchaseOrderLineNumber,
                            it.cases,
                            it.caseCost.amount,
                            it.caseCost.currency.currencyCode,
                        )
                    },
                exceptions =
                    (status as? InvoiceStatus.Exception)?.exceptions.orEmpty().map {
                        InvoiceExceptionEntity(it.purchaseOrderLineNumber, it.reason.name, it.detail)
                    },
                version = invoice.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: InvoiceId): VendorInvoice? = invoices.findById(id.value).getOrNull()?.toDomain()

    override fun exists(
        vendorId: VendorId,
        number: VendorInvoiceNumber,
    ): Boolean = invoices.existsByVendorIdAndVendorInvoiceNumber(vendorId.value, number.value)

    override fun findPage(
        exceptionsOnly: Boolean,
        query: PageQuery,
    ): Page<VendorInvoice> {
        // Identifiers are time-ordered, so the newest invoice comes first.
        val newestFirst = PageRequest.of(query.page, query.size, Sort.by(Sort.Direction.DESC, "id"))
        val page = if (exceptionsOnly) invoices.findByStatus(EXCEPTION, newestFirst) else invoices.findAll(newestFirst)
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun VendorInvoiceEntity.toDomain(): VendorInvoice =
        VendorInvoice.rehydrate(
            id = InvoiceId.of(id),
            details =
                InvoiceDetails(
                    vendorId = VendorId.of(vendorId),
                    number = VendorInvoiceNumber.of(vendorInvoiceNumber),
                    invoiceDate = BusinessDate(invoiceDate),
                    purchaseOrderId = PurchaseOrderId.of(purchaseOrderId),
                    lines =
                        lines.map {
                            InvoiceLine.of(
                                it.purchaseOrderLineNumber,
                                it.cases,
                                Money.of(it.caseCost, Currency.getInstance(it.currency)),
                            )
                        },
                ),
            enteredBy = enteredBy,
            status =
                matchedOn?.let { InvoiceStatus.Matched(BusinessDate(it), override()) }
                    ?: InvoiceStatus.Exception(
                        exceptions.map {
                            MatchException(
                                it.purchaseOrderLineNumber,
                                MatchExceptionReason.valueOf(it.reason),
                                it.detail,
                            )
                        },
                    ),
            version = version,
        )

    private fun VendorInvoiceEntity.override(): MatchOverride? =
        if (overrideApprovedBy != null && overrideReason != null) {
            MatchOverride.of(overrideApprovedBy, overrideReason)
        } else {
            null
        }

    private companion object {
        const val EXCEPTION = "EXCEPTION"
    }
}
