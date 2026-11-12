package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.PaymentNumbers
import com.retailplatform.payables.application.PaymentRepository
import com.retailplatform.payables.application.PaymentRunRepository
import com.retailplatform.payables.domain.Payment
import com.retailplatform.payables.domain.PaymentNumber
import com.retailplatform.payables.domain.PaymentRun
import com.retailplatform.payables.domain.PaymentRunId
import com.retailplatform.payables.domain.PaymentRunLine
import com.retailplatform.payables.domain.PaymentRunStatus
import com.retailplatform.payables.domain.PaymentWindow
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.payables.domain.VoucherNumber
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PaymentId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.VoucherId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jdbc.repository.query.Query
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

@Table(schema = "payables", name = "payment_run")
data class PaymentRunEntity(
    @Id val id: UUID,
    val paymentDate: LocalDate,
    val payThrough: LocalDate,
    val proposedBy: String,
    val status: String,
    val decidedBy: String?,
    val decidedOn: LocalDate?,
    @MappedCollection(idColumn = "payment_run_id", keyColumn = "position") val lines: List<PaymentRunLineEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "payables", name = "payment_run_line")
data class PaymentRunLineEntity(
    val voucherId: UUID,
    val voucherNumber: String,
    val vendorId: UUID,
    val vendorInvoiceNumber: String,
    val amount: BigDecimal,
    val discount: BigDecimal,
    val currency: String,
) {
    fun toDomain(): PaymentRunLine {
        val money = Currency.getInstance(currency)
        return PaymentRunLine(
            voucherId = VoucherId.of(voucherId),
            voucherNumber = VoucherNumber.of(voucherNumber),
            vendorId = VendorId.of(vendorId),
            vendorInvoiceNumber = VendorInvoiceNumber.of(vendorInvoiceNumber),
            amount = Money.of(amount, money),
            discount = Money.of(discount, money),
        )
    }
}

@Table(schema = "payables", name = "payment")
data class PaymentEntity(
    @Id val id: UUID,
    val number: String,
    val paymentRunId: UUID,
    val vendorId: UUID,
    val paidOn: LocalDate,
    val grossAmount: BigDecimal,
    val discount: BigDecimal,
    val netAmount: BigDecimal,
    val currency: String,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface PaymentRunJdbcRepository :
    CrudRepository<PaymentRunEntity, UUID>,
    PagingAndSortingRepository<PaymentRunEntity, UUID>

interface PaymentJdbcRepository : CrudRepository<PaymentEntity, UUID> {
    @Query("SELECT * FROM payables.payment WHERE payment_run_id = :paymentRunId ORDER BY number")
    fun findByRun(paymentRunId: UUID): List<PaymentEntity>
}

@Repository
class JdbcPaymentRunRepository(
    private val runs: PaymentRunJdbcRepository,
) : PaymentRunRepository {
    override fun save(run: PaymentRun) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit = if (run.version == 0) AuditMetadata() else runs.findById(run.id.value).orElseThrow().audit
        val status = run.status
        runs.save(
            PaymentRunEntity(
                id = run.id.value,
                paymentDate = run.window.paymentDate.value,
                payThrough = run.window.payThrough.value,
                proposedBy = run.proposedBy,
                status = status.code,
                decidedBy =
                    when (status) {
                        is PaymentRunStatus.Approved -> status.approvedBy
                        is PaymentRunStatus.Cancelled -> status.cancelledBy
                        PaymentRunStatus.Proposed -> null
                    },
                decidedOn =
                    when (status) {
                        is PaymentRunStatus.Approved -> status.approvedOn.value
                        is PaymentRunStatus.Cancelled -> status.cancelledOn.value
                        PaymentRunStatus.Proposed -> null
                    },
                lines =
                    run.lines.map {
                        PaymentRunLineEntity(
                            voucherId = it.voucherId.value,
                            voucherNumber = it.voucherNumber.value,
                            vendorId = it.vendorId.value,
                            vendorInvoiceNumber = it.vendorInvoiceNumber.value,
                            amount = it.amount.amount,
                            discount = it.discount.amount,
                            currency = it.amount.currency.currencyCode,
                        )
                    },
                version = run.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: PaymentRunId): PaymentRun? = runs.findById(id.value).getOrNull()?.toDomain()

    override fun findPage(query: PageQuery): Page<PaymentRun> {
        // Identifiers are time-ordered, so the newest run comes first.
        val page = runs.findAll(PageRequest.of(query.page, query.size, Sort.by(Sort.Direction.DESC, "id")))
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun PaymentRunEntity.toDomain(): PaymentRun =
        PaymentRun.rehydrate(
            id = PaymentRunId.of(id),
            window = PaymentWindow.of(BusinessDate(paymentDate), BusinessDate(payThrough)),
            proposedBy = proposedBy,
            lines = lines.map { it.toDomain() },
            status =
                when {
                    decidedBy == null || decidedOn == null -> PaymentRunStatus.Proposed
                    status == APPROVED -> PaymentRunStatus.Approved(decidedBy, BusinessDate(decidedOn))
                    else -> PaymentRunStatus.Cancelled(decidedBy, BusinessDate(decidedOn))
                },
            version = version,
        )

    private companion object {
        const val APPROVED = "APPROVED"
    }
}

@Repository
class JdbcPaymentRepository(
    private val payments: PaymentJdbcRepository,
    private val runs: PaymentRunJdbcRepository,
) : PaymentRepository {
    override fun save(payment: Payment) {
        payments.save(
            PaymentEntity(
                id = payment.id.value,
                number = payment.number.value,
                paymentRunId = payment.paymentRunId.value,
                vendorId = payment.vendorId.value,
                paidOn = payment.paidOn.value,
                grossAmount = payment.gross.amount,
                discount = payment.discount.amount,
                netAmount = payment.net.amount,
                currency = payment.gross.currency.currencyCode,
            ),
        )
    }

    override fun findById(id: PaymentId): Payment? =
        payments.findById(id.value).getOrNull()?.let { it.toDomain(linesOf(it.paymentRunId)) }

    override fun findByRun(paymentRunId: PaymentRunId): List<Payment> {
        val lines = linesOf(paymentRunId.value)
        return payments.findByRun(paymentRunId.value).map { it.toDomain(lines) }
    }

    private fun linesOf(paymentRunId: UUID): List<PaymentRunLine> =
        runs
            .findById(paymentRunId)
            .orElseThrow()
            .lines
            .map { it.toDomain() }

    /** A payment settles its run's lines for its vendor in its currency. */
    private fun PaymentEntity.toDomain(runLines: List<PaymentRunLine>): Payment {
        val vendor = VendorId.of(vendorId)
        return Payment.rehydrate(
            id = PaymentId.of(id),
            number = PaymentNumber.of(number),
            paymentRunId = PaymentRunId.of(paymentRunId),
            vendorId = vendor,
            paidOn = BusinessDate(paidOn),
            vouchers = runLines.filter { it.vendorId == vendor && it.amount.currency.currencyCode == currency },
        )
    }
}

@Repository
class SequencePaymentNumbers(
    private val jdbc: JdbcClient,
) : PaymentNumbers {
    override fun next(): PaymentNumber =
        PaymentNumber.of(
            jdbc
                .sql("SELECT nextval('payables.payment_number_seq')")
                .query(Long::class.java)
                .single()
                .toString(),
        )
}
