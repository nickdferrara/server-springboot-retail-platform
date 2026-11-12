package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.VendorTermsRepository
import com.retailplatform.payables.application.VoucherNumbers
import com.retailplatform.payables.application.VoucherRepository
import com.retailplatform.payables.domain.EarlyPaymentDiscount
import com.retailplatform.payables.domain.PaymentRunId
import com.retailplatform.payables.domain.PaymentWindow
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.payables.domain.VendorTerms
import com.retailplatform.payables.domain.Voucher
import com.retailplatform.payables.domain.VoucherDiscount
import com.retailplatform.payables.domain.VoucherNumber
import com.retailplatform.payables.domain.VoucherStatus
import com.retailplatform.payables.domain.VoucherTerms
import com.retailplatform.payables.domain.VoucheredInvoice
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PaymentId
import com.retailplatform.shared.Percentage
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.VoucherId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "payables", name = "voucher")
data class VoucherEntity(
    @Id val id: UUID,
    val number: String,
    val invoiceId: UUID,
    val vendorInvoiceNumber: String,
    val vendorId: UUID,
    val purchaseOrderId: UUID,
    val invoiceDate: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val dueOn: LocalDate,
    val discountPercent: BigDecimal?,
    val discountGoodThrough: LocalDate?,
    val releasedOn: LocalDate,
    val status: String,
    val paymentRunId: UUID?,
    val paymentId: UUID?,
    val paidOn: LocalDate?,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface VoucherJdbcRepository : CrudRepository<VoucherEntity, UUID> {
    // The row locks keep a second payment run, proposed at the same moment, from selecting the same vouchers.
    @Query(
        "SELECT * FROM payables.voucher WHERE status = 'RELEASED' " +
            "AND (due_on <= :through OR discount_good_through <= :through) " +
            "ORDER BY vendor_id, due_on, number FOR UPDATE",
    )
    fun findReleasedDueOrDiscountedThrough(through: LocalDate): List<VoucherEntity>

    @Query(
        "SELECT * FROM payables.voucher " +
            "WHERE (CAST(:vendorId AS uuid) IS NULL OR vendor_id = CAST(:vendorId AS uuid)) " +
            "AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text)) " +
            "ORDER BY due_on, number LIMIT :limit OFFSET :offset",
    )
    fun findPage(
        vendorId: UUID?,
        status: String?,
        limit: Int,
        offset: Long,
    ): List<VoucherEntity>

    @Query(
        "SELECT count(*) FROM payables.voucher " +
            "WHERE (CAST(:vendorId AS uuid) IS NULL OR vendor_id = CAST(:vendorId AS uuid)) " +
            "AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))",
    )
    fun count(
        vendorId: UUID?,
        status: String?,
    ): Long
}

@Repository
class JdbcVoucherRepository(
    private val vouchers: VoucherJdbcRepository,
) : VoucherRepository {
    override fun save(voucher: Voucher) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit =
            if (voucher.version == 0) AuditMetadata() else vouchers.findById(voucher.id.value).orElseThrow().audit
        val status = voucher.status
        vouchers.save(
            VoucherEntity(
                id = voucher.id.value,
                number = voucher.number.value,
                invoiceId = voucher.invoice.invoiceId.value,
                vendorInvoiceNumber = voucher.invoice.vendorInvoiceNumber.value,
                vendorId = voucher.invoice.vendorId.value,
                purchaseOrderId = voucher.invoice.purchaseOrderId.value,
                invoiceDate = voucher.invoice.invoiceDate.value,
                amount = voucher.amount.amount,
                currency = voucher.amount.currency.currencyCode,
                dueOn = voucher.terms.dueOn.value,
                discountPercent =
                    voucher.terms.discount
                        ?.percent
                        ?.value,
                discountGoodThrough =
                    voucher.terms.discount
                        ?.goodThrough
                        ?.value,
                releasedOn = voucher.releasedOn.value,
                status = status.code,
                paymentRunId =
                    when (status) {
                        is VoucherStatus.Scheduled -> status.paymentRunId.value
                        is VoucherStatus.Paid -> status.paymentRunId.value
                        VoucherStatus.Released -> null
                    },
                paymentId = (status as? VoucherStatus.Paid)?.paymentId?.value,
                paidOn = (status as? VoucherStatus.Paid)?.paidOn?.value,
                version = voucher.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: VoucherId): Voucher? = vouchers.findById(id.value).getOrNull()?.toDomain()

    override fun findByIds(ids: List<VoucherId>): List<Voucher> =
        vouchers.findAllById(ids.map { it.value }).map { it.toDomain() }

    override fun findPayable(window: PaymentWindow): List<Voucher> =
        vouchers
            .findReleasedDueOrDiscountedThrough(window.payThrough.value)
            .map { it.toDomain() }
            .filter(window::selects)

    override fun findPage(
        vendorId: VendorId?,
        statusCode: String?,
        query: PageQuery,
    ): Page<Voucher> =
        Page(
            vouchers.findPage(vendorId?.value, statusCode, query.size, query.offset).map { it.toDomain() },
            query.page,
            query.size,
            vouchers.count(vendorId?.value, statusCode),
        )

    private fun VoucherEntity.toDomain(): Voucher {
        val money = Currency.getInstance(currency)
        return Voucher.rehydrate(
            id = VoucherId.of(id),
            number = VoucherNumber.of(number),
            invoice =
                VoucheredInvoice(
                    InvoiceId.of(invoiceId),
                    VendorInvoiceNumber.of(vendorInvoiceNumber),
                    VendorId.of(vendorId),
                    PurchaseOrderId.of(purchaseOrderId),
                    BusinessDate(invoiceDate),
                ),
            terms =
                VoucherTerms(
                    amount = Money.of(amount, money),
                    dueOn = BusinessDate(dueOn),
                    discount =
                        if (discountPercent != null && discountGoodThrough != null) {
                            VoucherDiscount(Percentage.of(discountPercent), BusinessDate(discountGoodThrough))
                        } else {
                            null
                        },
                ),
            releasedOn = BusinessDate(releasedOn),
            status = status(),
            version = version,
        )
    }

    private fun VoucherEntity.status(): VoucherStatus =
        when {
            paymentRunId == null -> {
                VoucherStatus.Released
            }

            paymentId != null && paidOn != null -> {
                VoucherStatus.Paid(PaymentRunId.of(paymentRunId), PaymentId.of(paymentId), BusinessDate(paidOn))
            }

            else -> {
                VoucherStatus.Scheduled(PaymentRunId.of(paymentRunId))
            }
        }
}

@Repository
class SequenceVoucherNumbers(
    private val jdbc: JdbcClient,
) : VoucherNumbers {
    override fun next(): VoucherNumber =
        VoucherNumber.of(
            jdbc
                .sql("SELECT nextval('payables.voucher_number_seq')")
                .query(Long::class.java)
                .single()
                .toString(),
        )
}

@Repository
class JdbcVendorTermsRepository(
    private val jdbc: JdbcClient,
) : VendorTermsRepository {
    override fun save(
        vendorId: VendorId,
        effectiveFrom: BusinessDate,
        terms: VendorTerms,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO payables.vendor_terms (vendor_id, effective_from, net_days, discount_percent, discount_days)
                VALUES (:vendorId, :effectiveFrom, :netDays, :discountPercent, :discountDays)
                ON CONFLICT (vendor_id, effective_from) DO UPDATE
                    SET net_days = excluded.net_days,
                        discount_percent = excluded.discount_percent,
                        discount_days = excluded.discount_days
                """.trimIndent(),
            ).param("vendorId", vendorId.value)
            .param("effectiveFrom", effectiveFrom.value)
            .param("netDays", terms.netDays)
            .param("discountPercent", terms.discount?.percent?.value)
            .param("discountDays", terms.discount?.days)
            .update()
    }

    override fun findFor(
        vendorId: VendorId,
        invoiceDate: BusinessDate,
    ): VendorTerms? =
        jdbc
            .sql(
                """
                SELECT net_days, discount_percent, discount_days
                FROM payables.vendor_terms
                WHERE vendor_id = :vendorId
                ORDER BY effective_from <= :invoiceDate DESC,
                         CASE WHEN effective_from <= :invoiceDate THEN effective_from END DESC,
                         effective_from
                LIMIT 1
                """.trimIndent(),
            ).param("vendorId", vendorId.value)
            .param("invoiceDate", invoiceDate.value)
            .query { row, _ ->
                val percent = row.getBigDecimal("discount_percent")
                VendorTerms.of(
                    netDays = row.getInt("net_days"),
                    discount = percent?.let { EarlyPaymentDiscount(Percentage.of(it), row.getInt("discount_days")) },
                )
            }.optional()
            .getOrNull()
}
