package com.retailplatform.payables.application

import com.retailplatform.payables.domain.Accrual
import com.retailplatform.payables.domain.AccrualLine
import com.retailplatform.payables.domain.AccruedReceipt
import com.retailplatform.payables.domain.VendorAccrualBalance
import com.retailplatform.shared.AccrualId
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock

/** Port: where accruals are kept, one per receipt. */
interface AccrualRepository {
    /** Inserts a new accrual or updates an existing one; a stale version is refused. */
    fun save(accrual: Accrual)

    fun findById(id: AccrualId): Accrual?

    fun existsForReceipt(receiptId: ReceiptId): Boolean

    /** A vendor's open accruals, the oldest receipt first. */
    fun findOpen(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<Accrual>

    /** A purchase order's open accruals, the oldest receipt first. */
    fun findOpenByPurchaseOrder(purchaseOrderId: PurchaseOrderId): List<Accrual>

    /** Open accruals totalled by vendor and currency, with the day of each vendor's oldest open receipt. */
    fun openBalances(): List<OpenBalance>
}

/** What a vendor is owed in open accruals, before age is judged. */
data class OpenBalance(
    val vendorId: VendorId,
    val openAmount: Money,
    val openAccruals: Int,
    val oldestReceivedOn: BusinessDate,
)

/** Settings under `retail.payables`. */
@ConfigurationProperties("retail.payables")
data class PayablesProperties(
    /** An open accrual older than this many days is flagged as stale. */
    val staleAccrualDays: Long = 60,
    /** A voucher for a vendor whose payment terms are not known yet falls due this many days after its invoice. */
    val defaultNetDays: Int = 30,
    val match: MatchProperties = MatchProperties(),
) {
    /** How far an invoice line's cost may differ from the purchase order's and still match. */
    data class MatchProperties(
        /** The most a case cost may differ from the purchase order's, as a percentage of it. */
        val priceTolerancePercent: BigDecimal = BigDecimal("2.00"),
        /** The most a line's total may differ from purchase order cost, in the invoice's currency. */
        val priceToleranceAmount: BigDecimal = BigDecimal("10.00"),
    )
}

/** Makes [PayablesProperties] available to the module's use cases. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PayablesProperties::class)
class PayablesConfiguration

data class RecordAccrualCommand(
    val receipt: AccruedReceipt,
    val lines: List<AccrualLine>,
)

/** Use case: accrue what is owed for goods a warehouse received, until the vendor's invoice arrives. */
@Service
class RecordAccrual(
    private val accruals: AccrualRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** Does nothing if the receipt has been accrued already, so a receipt is never accrued twice. */
    @Transactional
    fun handle(command: RecordAccrualCommand) {
        if (accruals.existsForReceipt(command.receipt.receiptId)) return

        val accrual = Accrual.record(command.receipt, command.lines, clock)
        accruals.save(accrual)
        accrual.releaseEvents().forEach(events::publishEvent)
    }
}

/** Queries: open accruals by vendor, and what each vendor is owed for goods received and not invoiced. */
@Service
@Transactional(readOnly = true)
class AccrualQueries(
    private val accruals: AccrualRepository,
    private val properties: PayablesProperties,
    private val clock: Clock,
) {
    fun find(id: AccrualId): Accrual = accruals.findById(id) ?: throw ResourceNotFound("Accrual", id.toString())

    fun open(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<Accrual> = accruals.findOpen(vendorId, query)

    /** Open balances by vendor as of today, the vendor with the oldest open receipt first. */
    fun balances(): List<VendorAccrualBalance> {
        val today = BusinessDate.today(clock)
        return accruals
            .openBalances()
            .map {
                val age = today.value.toEpochDay() - it.oldestReceivedOn.value.toEpochDay()
                VendorAccrualBalance(
                    vendorId = it.vendorId,
                    openAmount = it.openAmount,
                    openAccruals = it.openAccruals,
                    oldestReceivedOn = it.oldestReceivedOn,
                    oldestAgeDays = age,
                    stale = age > properties.staleAccrualDays,
                )
            }.sortedBy { it.oldestReceivedOn }
    }
}
